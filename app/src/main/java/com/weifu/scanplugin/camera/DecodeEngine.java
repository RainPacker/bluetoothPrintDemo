package com.weifu.scanplugin.camera;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 解码引擎（F-08 / F-23 / F-24 / F-25 ~ F-29 / F-31）
 * <p>
 * 双路径策略：
 * - 快速路径：原始灰度 + HybridBinarizer + TRY_HARDER（普通二维码毫秒级出结果）；
 * - DPM 增强路径：自适应二值化 → 反色 → 四方向旋转 → ROI 裁剪放大，逐级重试
 *   （金属刻印 DM 码靠多级管线 + 多帧概率累积提升识别率）。
 * <p>
 * 线程模型：由 DpmImageAnalyzer 在独立解码线程调用，本类无锁、无状态共享。
 */
class DecodeEngine {

    private static final String TAG = "DecodeEngine";

    private final MultiFormatReader reader = new MultiFormatReader();
    private final boolean dpmMode;
    private final Set<String> formats;
    // 帧计数（仅解码线程访问）：双尺度交替用
    private int frameCounter = 0;
    // 上帧解码成功所在的辅降采样尺度（跨帧验证加速：下帧优先复跑同尺度）
    private int lastSuccessScale = -1;
    // 圆点命中链并行解码池（惰性创建，release 时关闭）
    private java.util.concurrent.ExecutorService dotDecodePool;
    // 相机 DM 跨帧确认计数：key = 文本#尺度标签，value = {命中次数, 上次命中时间ms}（仅解码线程访问）
    // 同一文本须在同一降采样尺度下命中 ≥2 次才对外输出——不同尺度上的
    // 系统性误读互不累积，从机制上杜绝"重复扫描结果不一样"
    private final java.util.HashMap<String, long[]> dmHitCounts = new java.util.HashMap<>();
    // DM 确认门槛 3 次：DotPeen 码模块噪声大，误读乱码文本偶发重复（同角度同光照
    // 系统性误读），2 次即输出导致"同一码多次扫描结果不一致"；3 次下真文本
    // 仅多等一帧（cpp1+g1 同帧双命中已占 2 次，日志实测），乱码需凑 3 次
    // （跨层一致罕见 / 连续 3 帧同乱码）输出概率大幅下降
    private static final int DM_CONFIRM_HITS = 3;
    // 确认计数 TTL：乱码 2s 无再命中即过期（防乱码洪水挤爆计数表）
    private static final long DM_CONFIRM_TTL_MS = 2000;
    // 已出码文本 → 上次对外输出时间（连续模式同码去重窗口）
    private final java.util.HashMap<String, Long> deliveredAt = new java.util.HashMap<>();
    private static final long DELIVER_DEDUPE_MS = 2500;
    private boolean dimsLogged = false;
    private boolean roiLogged = false;

    // ===== 自动变焦（微信式：检测到小码 → 光学放大 → 点距进甜蜜区）=====
    /** 自动变焦建议监听：解码线程回调，实现方调 CameraControl.setZoomRatio（线程安全） */
    interface ZoomRequestListener {
        void onZoomRequest(float targetRatio);
    }
    private volatile ZoomRequestListener zoomListener;
    // 连续"点距过小"帧计数（防单帧纹理噪声误判，仅解码线程访问）
    private int smallPitchStreak = 0;
    // 上次变焦建议时间：冷却 1.5s（zoom 后 AF 重新收敛、帧流稳定需要时间）
    private long lastZoomReqMs = 0;
    // 最近一次追踪到疑似码的时间（pending/confirmed 任一）：超时回退依据
    private long lastTrackMs = 0;
    // 上次已应用的变焦倍率：req 只升不降（真机日志 14:25~14:28 在 1.5↔2.0↔1.0
    // 反复横跳，每次变化 AF 重建 ~1s 模糊期拉低识别率）；降档唯一路径是 reset
    private float lastZoomRatio = 1.0f;
    // 数码 zoom 上限 3x：超过后画质插值衰减，DotPeen 码 2.5x+ 对焦距离已足够
    private static final float AUTO_ZOOM_MAX = 3.0f;
    private static final long AUTO_ZOOM_COOLDOWN_MS = 1500;

    void setZoomListener(ZoomRequestListener l) {
        this.zoomListener = l;
    }

    /**
     * 自动变焦决策（仅解码线程调用，decodeCascade 的 pitchEst 之后）：
     * pitchEst 3~7 = 点距低于甜蜜区一半，码太远/太小 → 建议光学变焦
     * 把点距推到 ~9.5px（与软放大同公式，但光学放大保留真实像素，
     * 质量高于 upscale 插值——zoom 后 pitchEst 变大，软放大自然不再触发）；
     * 连续 2 帧小点距才触发（防噪声）；冷却防 zoom 后反复震荡。
     * 回退：6s 未追踪到任何疑似码 → 建议 1.0（用户已换目标，3x 视野过窄）。
     */
    private void handleAutoZoom(int pitchEst, long now) {
        ZoomRequestListener l = zoomListener;
        if (l == null) {
            return;
        }
        boolean small = pitchEst >= 3 && pitchEst <= 7;
        if (small) {
            smallPitchStreak++;
        } else {
            smallPitchStreak = 0;
        }
        if (now - lastZoomReqMs <= AUTO_ZOOM_COOLDOWN_MS) {
            return;
        }
        if (small && smallPitchStreak >= 2) {
            // 目标倍率 0.5 步进：pitchEst=4→2.5x、5→2.0x、7→1.5x
            float target = Math.round(9.5f / pitchEst * 2f) / 2f;
            target = Math.max(1.5f, Math.min(AUTO_ZOOM_MAX, target));
            if (target <= lastZoomRatio + 0.05f) {
                return; // 只升不降：pitchEst 在阈值边缘波动时避免反复横跳
            }
            lastZoomRatio = target;
            lastZoomReqMs = now;
            Log.i(TAG, "zoom req " + target + " pitchEst=" + pitchEst);
            ScanFileLog.log("zoom req " + target + " pitchEst=" + pitchEst);
            l.onZoomRequest(target);
            return;
        }
        // 回退：长时间无码信号（点距不可测或过大）且 6s 无任何追踪命中
        boolean noSignal = pitchEst < 3 || pitchEst > 11;
        if (noSignal && now - lastTrackMs > 6000) {
            lastTrackMs = now; // 防止冷却期内每帧重复请求
            lastZoomReqMs = now;
            if (lastZoomRatio > 1.0f) {
                lastZoomRatio = 1.0f;
                Log.i(TAG, "zoom reset 1.0");
                ScanFileLog.log("zoom reset 1.0");
                l.onZoomRequest(1.0f);
            }
        }
    }

    DecodeEngine(boolean dpmMode, Set<String> formats) {
        this.dpmMode = dpmMode;
        this.formats = formats;
        // DPM 模式 cpp 层只留 DATA_MATRIX。曾保留 QR_CODE 想兼容普通二维码
        // （cpp1 首帧直出），但真机日志证伪：金属喷砂纹理会稳定误判成 QR
        // （14:05:36 连续 7 帧 cpp1 QR 命中直出，非 DM 结果无确认门槛直接
        // 对外输出错误文本），产线数据错误不可接受。dpm=true 时 QR 由 Java
        // 级联兜底（hints 全格式 + confirmCameraResult 跨帧确认拦截误读）
        java.util.Set<zxingcpp.BarcodeReader.Format> cppFormats = dpmMode
                ? java.util.Collections.singleton(zxingcpp.BarcodeReader.Format.DATA_MATRIX)
                : null;
        zxingcpp.BarcodeReader.Options opts = new zxingcpp.BarcodeReader.Options();
        opts.setTryHarder(true);
        opts.setTryRotate(true);
        opts.setTryInvert(true);
        opts.setTryDownscale(true);
        if (cppFormats != null) {
            opts.setFormats(cppFormats);
        }
        // TextMode.PLAIN：text 每字符 = 原始字节的 Latin-1 一对一映射（0x00~0xFF 全量直通，
        // 控制字符原样保留，无 HRI 转义/替换层），Java 侧经 ISO-8859-1 可无损还原原始
        // 字节流，再按内容做智能字符集解码（DM 乱码根治）
        opts.setTextMode(zxingcpp.BarcodeReader.TextMode.PLAIN);
        cppReader = new zxingcpp.BarcodeReader(opts);
        zxingcpp.BarcodeReader.Options globalOpts = new zxingcpp.BarcodeReader.Options();
        globalOpts.setTryHarder(true);
        globalOpts.setTryRotate(true);
        globalOpts.setTryInvert(true);
        globalOpts.setTryDownscale(true);
        if (cppFormats != null) {
            globalOpts.setFormats(cppFormats);
        }
        globalOpts.setTextMode(zxingcpp.BarcodeReader.TextMode.PLAIN);
        globalOpts.setBinarizer(zxingcpp.BarcodeReader.Binarizer.GLOBAL_HISTOGRAM);
        cppReaderGlobal = new zxingcpp.BarcodeReader(globalOpts);
    }

    /**
     * 矩形区域裁剪（row-major 灰度图）：取景框 ROI 专用。
     */
    private static byte[] cropRegion(byte[] gray, int width, int height, int x0, int y0, int cw, int ch) {
        byte[] out = new byte[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(gray, (y0 + y) * width + x0, out, y * cw, cw);
        }
        return out;
    }

    /**
     * 粗估画面中点阵码的点距（像素）：
     * 缩到宽 ≤480 → 自适应二值化 → 逐行统计黑游程长度，中位数 ≈ 点直径
     * （击打圆点直径约为点距 0.8）→ /0.8 ≈ 点距。
     * 无显著游程（虚焦/无码/纯噪）返回 -1。
     */
    private int estimatePitch(byte[] gray, int width, int height) {
        try {
            int factor = Math.max(1, width / 480);
            int w = width / factor, h = height / factor;
            if (w < 100 || h < 100) {
                return -1;
            }
            byte[] img = ImagePreprocessor.downscale(gray, width, height, factor);
            // Otsu 全局阈值而非 adaptiveThreshold：局部窗口与点径相当时会把
            // 大点切碎（点径 12px ≈ 窗口 15px 的生成图/特写照），run 统计全乱
            int otsu = ImagePreprocessor.otsuThreshold(img);
            for (int i = 0; i < img.length; i++) {
                img[i] = (img[i] & 0xFF) < otsu ? (byte) 0 : (byte) 255;
            }
            // 黑/白双通道 run 统计：点化 DM 的"点"在暗底图上是白色（闪存压痕
            // 反光）——只统计黑 run 时暗底图估出的是点间隙（真机生成图 pitch=4，
            // 实际 14px）。取两通道中位数更大的一侧，两种底色下都对应点径
            java.util.ArrayList<Integer> blackRuns = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> whiteRuns = new java.util.ArrayList<>();
            for (int y = 0; y < h; y += 2) {
                int run = 0;
                boolean black = (img[y * w] & 0xFF) == 0;
                for (int x = 0; x < w; x++) {
                    boolean b = (img[y * w + x] & 0xFF) == 0;
                    if (b == black) {
                        run++;
                    } else {
                        if (run >= 2 && run <= 60) {
                            (black ? blackRuns : whiteRuns).add(run);
                        }
                        run = 1;
                        black = b;
                    }
                }
                if (run >= 2 && run <= 60) {
                    (black ? blackRuns : whiteRuns).add(run);
                }
            }
            int median = pickPitchMedian(blackRuns, whiteRuns);
            if (median < 3) {
                return -1;
            }
            return Math.round(median / 0.8f);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 点距中位数取舍：取黑/白 run 中位数更大的一侧。亮底暗点图黑 run=点径、
     * 白 run=点间隙；暗底亮点图相反——更大的一侧总是点径（点直径 0.7~0.9
     * 倍点距，远大于 0.1~0.3 倍的间隙）。
     */
    private static int pickPitchMedian(java.util.ArrayList<Integer> black, java.util.ArrayList<Integer> white) {
        int mb = black.size() >= 20 ? black.get(black.size() / 2) : 0;
        int mw = white.size() >= 20 ? white.get(white.size() / 2) : 0;
        return Math.max(mb, mw);
    }

    /**
     * 灰度中值（直方图累计取中值）：暗底/亮底判定用，比均值抗反光极值干扰。
     */
    private static int medianGray(byte[] img) {
        int[] hist = new int[256];
        for (byte b : img) {
            hist[b & 0xFF]++;
        }
        int half = img.length / 2, acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= half) {
                return v;
            }
        }
        return 128;
    }

    /**
     * 相机帧 DM 结果确认：同一文本命中 DM_CONFIRM_HITS 次才返回结果，
     * 否则返回 null（本帧不输出，等下帧再命中）。确认后移除计数，允许同一码再次扫描。
     * <p>
     * 按文本聚合（不区分尺度标签）：级联辅助尺度逐帧轮转 {4,5,6,8,10}，低帧率下
     * "同文本+同一尺度"命中 2 次的窗口可能永远凑不齐（真机 DotPeen 码超时识别失败的
     * 元凶之一）。不同尺度的独立命中同样互证正确性——系统性误读一般稳定复现同一
     * 错误文本，与真值文本不聚合；文本级聚合仍能防误读。
     * 仅解码线程调用（decodeYuv 单线程串行），无并发问题。
     */
    private Result confirmCameraResult(Result r, String scaleTag) {
        if (r == null) {
            return null;
        }
        String text = r.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        lastTrackMs = android.os.SystemClock.elapsedRealtime(); // 追踪中：变焦回退计时基准
        String key = text;
        long now = android.os.SystemClock.elapsedRealtime();
        // "f"（全分辨率 ROI 粗二值化）垃圾误读率高（真机实测产出 "88"/"07132546"），
        // 确认门槛提高一档
        int need = scaleTag.startsWith("f") ? DM_CONFIRM_HITS + 1 : DM_CONFIRM_HITS;
        long[] cnt = dmHitCounts.get(key);
        // TTL 过期（真机日志：确认表被乱码洪水灌到 175 个 key，48 上限全清
        // 反复打断真码确认——BYD 码 pending 11 轮才凑满 3 次）。乱码 2s 未
        // 再次命中即过期；真码连续命中 3 次仅需 ~240ms，不受影响
        if (cnt != null && now - cnt[1] > DM_CONFIRM_TTL_MS) {
            dmHitCounts.remove(key);
            cnt = null;
        }
        if (cnt == null) {
            if (dmHitCounts.size() > 48) {
                // 防膨胀：先清过期乱码（TTL 后表通常已很小，全清极少触发）
                for (java.util.Iterator<java.util.Map.Entry<String, long[]>> it
                        = dmHitCounts.entrySet().iterator(); it.hasNext(); ) {
                    if (now - it.next().getValue()[1] > DM_CONFIRM_TTL_MS) {
                        it.remove();
                    }
                }
                if (dmHitCounts.size() > 48) {
                    dmHitCounts.clear();
                }
            }
            dmHitCounts.put(key, new long[]{1, now});
            Log.d(TAG, "dm pending confirm [" + scaleTag + "]: " + text);
            ScanFileLog.log("dm pending [" + scaleTag + "]: " + text);
            return null;
        }
        cnt[0]++;
        cnt[1] = now;
        if (cnt[0] >= need) {
            dmHitCounts.remove(key);
            // 已出码文本 2.5s 去重：确认后计数已清零，持续对准同一码会每 2 帧
            // 重新凑满确认 → 连续模式下同一码反复对外输出（真机"重复扫描"）
            Long last = deliveredAt.get(text);
            if (last != null && now - last < DELIVER_DEDUPE_MS) {
                return null;
            }
            if (deliveredAt.size() > 32) {
                deliveredAt.clear();
            }
            deliveredAt.put(text, now);
            Log.i(TAG, "dm confirmed [" + scaleTag + "]: " + text);
            ScanFileLog.log("dm confirmed [" + scaleTag + "]: " + text);
            return r;
        }
        return null;
    }

    /**
     * 解码 YUV 帧的 Y 平面数据
     *
     * @param yPlane         Y 平面数据
     * @param width          帧宽
     * @param height         帧高
     * @param rowStride      Y 平面行跨距
     * @param rotationDegrees 相机帧旋转角（ImageAnalysis 输出为传感器原始方向，需矫正）
     * @return 解码结果，失败返回 null
     */
    Result decodeYuv(byte[] yPlane, int width, int height, int rowStride, int rotationDegrees) {
        return decodeYuv(yPlane, width, height, rowStride, rotationDegrees, null);
    }

    /**
     * @param roi 取景框像素 rect {x0, y0, w, h}（旋转矫正后的竖屏显示坐标），null=全图。
     *            只识别框内区域：管线耗时按裁剪面积近似线性下降，框外噪声不进解码
     */
    Result decodeYuv(byte[] yPlane, int width, int height, int rowStride, int rotationDegrees, int[] roi) {
        setHints();
        // 提取紧凑灰度图（去除行跨距）
        byte[] gray = extractGray(yPlane, width, height, rowStride);
        // 按相机旋转角矫正：未矫正时横屏 ROTATION_270 / 竖屏下二维码在帧中是旋转的
        if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            gray = ImagePreprocessor.rotate(gray, width, height, rotationDegrees);
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                int t = width;
                width = height;
                height = t;
            }
        }

        if (!dimsLogged) {
            dimsLogged = true;
            String dims = "camera frame " + width + "x" + height + " dpm=" + dpmMode + " formats=" + formats;
            Log.i(TAG, dims);
            ScanFileLog.log(dims);
        }

        // ===== 第 1 层：zxing-cpp 主引擎（全分辨率）=====
        // 真机日志验证（小米 1200x1600 帧）：全分辨率 cpp1/g1 是 DotPeen DM 的
        // 命中层（c1 pending → 同帧 g1 confirmed，30ms 聚合出码）；cpp 全分辨率
        // 1.9MP 仅 40~75ms，QR 首帧即中，秒级体验不受影响。
        // 保持全帧不裁 ROI：特写拍摄（码占画面 >框，如 DotPeen 测试图贴拍）时
        // 取景框 ROI 只覆盖码的中部，L 型定位边被裁掉 → cpp 必然定位失败
        // （真机"特写无法识别"回归实证）。cpp 层 40~75ms 本就不是瓶颈，
        // ROI 收益集中在慢层级联
        long t0 = android.os.SystemClock.elapsedRealtime();
        Result result = cppDecode(grayToBitmap(gray, width, height), cppReader);
        long t1 = android.os.SystemClock.elapsedRealtime();
        Log.d(TAG, "cpp1 " + (t1 - t0) + "ms hit=" + (result != null));
        ScanFileLog.log("cpp1 " + (t1 - t0) + "ms hit=" + (result != null));
        if (result != null) {
            // 高置信度格式直出；DM 走文本级跨帧确认（未确认时继续后续层级）
            if (result.getBarcodeFormat() != com.google.zxing.BarcodeFormat.DATA_MATRIX) {
                return result;
            }
            Result confirmed = confirmCameraResult(result, "c1");
            if (confirmed != null) {
                return confirmed;
            }
        }

        if (!dpmMode) {
            return null;
        }

        // ===== 第 2 层：全局直方图二值化兜底（圆点 DM 专项，全分辨率）=====
        // DotPeen 圆点码的模块是孤立圆点，局部均值二值化（LOCAL_AVERAGE）在点间隙处
        // 易产生误判；GLOBAL_HISTOGRAM 按全图灰度分布取阈值，对圆点边缘响应不同
        result = cppDecode(grayToBitmap(gray, width, height), cppReaderGlobal);
        if (result != null) {
            if (result.getBarcodeFormat() != com.google.zxing.BarcodeFormat.DATA_MATRIX) {
                return result;
            }
            Result confirmed = confirmCameraResult(result, "g1");
            if (confirmed != null) {
                return confirmed;
            }
        }

        // ===== 取景框 ROI 裁剪（竖屏显示帧坐标，与 UI 取景框同参照系）=====
        // 只作用于慢层（级联/pitchEst/ROI 放大，耗时大头）：中距离拍摄（码在框内）
        // 时按裁剪面积近似线性提速 4~6 倍，框外噪声不进级联（乱码误读来源减少）。
        // cpp1/g1 快速命中层保持全帧：特写大码（码 >框）时 ROI 会裁掉 L 型定位边；
        // 且码在框外时 cpp 层仍可识别（用户未对准框的场景）
        if (roi != null && roi.length == 4) {
            int x0 = Math.max(0, Math.min(width - 32, roi[0]));
            int y0 = Math.max(0, Math.min(height - 32, roi[1]));
            int cw = Math.min(roi[2], width - x0);
            int ch = Math.min(roi[3], height - y0);
            // 面积超过全图 3/4 时裁剪无收益；短边 <96px 点距/视场不足，退回全图
            if (cw >= 96 && ch >= 96 && (long) cw * ch < (long) width * height * 3 / 4) {
                gray = cropRegion(gray, width, height, x0, y0, cw, ch);
                width = cw;
                height = ch;
                if (!roiLogged) {
                    roiLogged = true;
                    String roiLog = "decodeRoi " + x0 + "," + y0 + " " + cw + "x" + ch;
                    Log.i(TAG, roiLog);
                    ScanFileLog.log(roiLog);
                }
            }
        }

        // ===== 第 3 层：Java DPM 兜底级联（/2 小图）=====
        // factor 必须按长边判定：竖屏帧 width=1200/1080，按宽度判定会失效退化为
        // 全分辨率——全分辨率主管线单帧 1~4s 是真机级联延迟的元凶（日志验证）；
        // 且全分辨率 dot chains 对 DotPeen 码只产出 8 位乱码误读，/2 后点距
        // 落入 (win,C) 参数族工作区间且提速 4 倍
        int factor = Math.max(width, height) > 1000 ? 2 : 1;
        byte[] small = ImagePreprocessor.downscale(gray, width, height, factor);
        long t3 = android.os.SystemClock.elapsedRealtime();
        result = decodeCascade(gray, width, height, small, width / factor, height / factor, factor);
        String cascadeLog = "cascade " + (android.os.SystemClock.elapsedRealtime() - t3) + "ms hit=" + (result != null);
        Log.d(TAG, cascadeLog);
        ScanFileLog.log(cascadeLog);
        return result;
    }

    /**
     * 第 3 层 Java DPM 兜底级联（多尺度时间复用）：
     * 相册快/相机慢的差异根源：相册 397px 图 /2 后点距恰落 9~10px 甜蜜区，
     * 而 1080p 相机帧同取景点距可达 ~90px，/2、/3 全部落到甜蜜区外，
     * 需要 /8~10 才能进入。策略：
     * 3.0 命中尺度记忆：上帧曾在某辅尺度解码成功（待跨帧确认），本帧优先
     *     复跑同尺度 → 第二帧立即再次命中，快速通过验证出码
     * 3.1 主尺度 /2 全管线（近距离/小码场景，点距更大保细节）
     * 3.2 辅尺度 {4,5,6,8,10} 逐帧轮转，只跑并行命中链 + C++ 兜底
     *     （高倍降采样像素量小，代价几十 ms；多帧累积覆盖全尺度带）
     */
    private Result decodeCascade(byte[] gray, int width, int height, byte[] small, int sw, int sh, int factor) {
        Result result;
        frameCounter++;
        if (lastSuccessScale >= 2
                && width / lastSuccessScale >= 120 && height / lastSuccessScale >= 120) {
            int s = lastSuccessScale;
            byte[] img = ImagePreprocessor.downscale(gray, width, height, s);
            result = decodeDotChainsParallel(img, width / s, height / s);
            if (result == null) {
                result = cppDotProbe(img, width / s, height / s);
            }
            if (result != null) {
                // 标签与辅尺度轮转一致（a+尺度）：首帧命中后，下帧记忆探针
                // 复跑同尺度即可凑满确认次数，快速出码
                return confirmCameraResult(result, "a" + s);
            }
        }

        byte[] workImg = small;
        int workW = sw, workH = sh;
        // 主尺度为 /2 图（decodeYuv 传入）：小码保细节且点距落入 (win,C) 参数族工作区间，
        // 全分辨率 dot chains 实测只产出乱码误读且单帧 1~4s
        ScanFileLog.log("main=/" + factor + " " + workW + "x" + workH
                + " roiPhase=" + (frameCounter % 3));
        result = decodeGrayDpm(workImg, workW, workH, true, frameCounter % 3);
        if (result != null) {
            return confirmCameraResult(result, "m" + factor);
        }

        int pitchEst = estimatePitch(gray, width, height);
        ScanFileLog.log("pitchEst=" + pitchEst);
        handleAutoZoom(pitchEst, android.os.SystemClock.elapsedRealtime());

        if (pitchEst > 0 && pitchEst <= 11) {
            // 小点距（真机实测 4~6px：码占屏仅 ~7%，1440 宽帧中码约 100px）：
            // 任何降采样都让点距跌破甜蜜区，方向完全反了——正确做法是
            // 中心裁剪 + 放大，把点距推入 9~10px 甜蜜区后跑验证过的命中链。
            // scale 允许 1：pitchEst≥7 时点距已接近甜蜜区，强行 x2 会推到
            // 14px+ 反而错过（真机日志 pitchEst=8 x2 crop=0.6 全败实证）
            int scale = Math.max(1, Math.min(4, Math.round(9.5f / pitchEst)));
            // ROI 裁剪后码占框内比例高（30~70%），中心裁剪比率不能沿用全帧
            // 时代的 0.25/0.6（按"码占屏 7%"设计）——否则码被截碎（真机日志
            // pitchEst=5 x2 crop=0.25 只剩 174px 中心区，L 型定位边必被裁掉）。
            // 起步取全 ROI，像素预算不足时由下方循环自动收缩（点距甜蜜区
            // 由 scale 保证，不受收缩影响）
            float ratio = 1.0f;
            // 放大输出像素预算 ≤~1.2MP：2.76MP（1440x1920）上 fillHoles/闭运算单次
            // 数百 ms，并行命中链必超 300ms 时限、cppDotProbe 秒级——真机单帧
            // cascade 3.8s 的元凶（日志验证）。裁剪比率按预算收缩，缩放倍率
            // （点距甜蜜区）保持不变
            int cw = Math.round(width * ratio);
            int ch = Math.round(height * ratio);
            while (scale > 2 && (long) cw * scale * ch * scale > 1_200_000L) {
                scale--;
            }
            while (ratio > 0.2f && (long) cw * scale * ch * scale > 1_200_000L) {
                ratio -= 0.05f;
                cw = Math.round(width * ratio);
                ch = Math.round(height * ratio);
            }
            Object[] up = ImagePreprocessor.cropAndScale(gray, width, height, ratio, ratio, scale);
            byte[] upImg = (byte[]) up[0];
            int upW = (int) up[1];
            int upH = (int) up[2];
            ScanFileLog.log("upscale x" + scale + " crop=" + ratio + " " + upW + "x" + upH);
            result = decodeDotChainsParallel(upImg, upW, upH);
            if (result == null) {
                result = cppDotProbe(upImg, upW, upH);
            }
            if (result != null) {
                return confirmCameraResult(result, "u" + scale);
            }
            // 小点距帧跑降采样管线是纯浪费，直接等下一帧
            return null;
        }

        int aux;
        if (pitchEst >= 12) {
            // 大点距：按估计值直选降采样尺度（pitch/9.5 ≈ 目标倍数）
            aux = Math.max(2, Math.min(16, Math.round(pitchEst / 9.5f)));
            if (width / aux < 120 || height / aux < 120) {
                aux = 2;
            }
        } else {
            int[] auxScales = {4, 5, 6, 8, 10};
            aux = auxScales[frameCounter % auxScales.length];
        }
        ScanFileLog.log("aux=/" + aux);
        if (aux != lastSuccessScale && width / aux >= 120 && height / aux >= 120) {
            byte[] img = ImagePreprocessor.downscale(gray, width, height, aux);
            result = decodeDotChainsParallel(img, width / aux, height / aux);
            if (result == null) {
                result = cppDotProbe(img, width / aux, height / aux);
            }
            if (result != null) {
                // 记住命中尺度（即使尚未确认）：下帧记忆探针复跑同尺度凑满确认
                lastSuccessScale = aux;
                return confirmCameraResult(result, "a" + aux);
            }
        }
        // fullResRoi：2.76MP 多趟解码（真机实测为级联 2s 的主要构成之一），
        // 每 3 帧跑一次，其余帧跳过（省下的时间让有效帧率翻倍）
        if (frameCounter % 3 == 0) {
            return confirmCameraResult(decodeFullResRoi(gray, width, height), "f");
        }
        return null;
    }

    /**
     * zxing-cpp 主引擎（2.3.0 API）：实例类，read(Bitmap/Rect/rotation) 同步解码。
     * 双实例互补：
     * - cppReader：LOCAL_AVERAGE 局部均值二值化（默认，常规码最优）
     * - cppReaderGlobal：GLOBAL_HISTOGRAM 全局直方图二值化（圆点 DM 兜底，
     *   对孤立圆点的边缘响应与局部均值不同）
     * Options：tryHarder/tryRotate/tryInvert + tryDownscale（C++ 内置多尺度）。
     * 仅解码线程调用，实例复用安全。
     */
    private final zxingcpp.BarcodeReader cppReader;
    private final zxingcpp.BarcodeReader cppReaderGlobal;

    private Result cppDecode(Bitmap bitmap, zxingcpp.BarcodeReader reader) {
        try {
            java.util.List<zxingcpp.BarcodeReader.Result> results =
                    reader.read(bitmap, new android.graphics.Rect(), 0);
            if (results != null && !results.isEmpty()) {
                return toZxingResult(results.get(0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cpp decode failed, fallback to java", t);
        }
        return null;
    }

    /**
     * zxing-cpp 结果 → zxing Result（保持回调链路统一）
     * <p>
     * 乱码根治（DM 工业码）：TextMode.BINARY 下 text 每字符 = 原始字节的 Latin-1 直通，
     * 经 ISO-8859-1 精确还原原始字节流后智能解码，彻底绕过 HRI 转义/替换层。
     */
    private static Result toZxingResult(zxingcpp.BarcodeReader.Result r) {
        com.google.zxing.BarcodeFormat fmt;
        try {
            fmt = com.google.zxing.BarcodeFormat.valueOf(r.getFormat().name());
        } catch (Exception e) {
            fmt = com.google.zxing.BarcodeFormat.QR_CODE;
        }
        return new Result(extractText(r), null, null, fmt);
    }

    private static String extractText(zxingcpp.BarcodeReader.Result r) {
        String text = r.getText();
        if (text == null) {
            return "";
        }
        // 按 text 字符分布判定 C++ 侧输出语义（PLAIN 模式）：
        // ① 含 >0xFF 高位字符：内容是合法 UTF-8，C++ 已解码为真 Unicode
        //    （如全角分号 U+FF1B、中文）→ 直接采用。
        //    不可再走 ISO-8859-1 还原——高位字符无法映射会被替换成 '?'（实测 ?? 乱码根因）！
        // ② 全部 ≤0xFF：Latin-1 直通语义（字节 = char）→ 还原原始字节后智能解码
        //    （覆盖 GBK 等非 UTF-8 编码的刻印内容）
        boolean hasHigh = false;
        boolean hasControl = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0xFF) {
                hasHigh = true;
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                hasControl = true;
            }
        }
        if (hasHigh) {
            // GS 分隔符（0x1D，GS1 元素间隔）转为可读 '|'
            return text.replace("\u001D", "|");
        }
        if (!hasControl) {
            return text; // 纯可打印 ASCII/Latin-1
        }
        byte[] raw;
        try {
            raw = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return text;
        }
        String decoded = smartDecodeBytes(raw);
        // GS 分隔符（0x1D，GS1 元素间隔）转为可读 '|'
        return decoded.replace("\u001D", "|");
    }

    /**
     * 智能字节解码：ASCII 快速通道 → UTF-8 严格校验 → GBK（中文工业标签）→ Latin-1 直通
     */
    private static String smartDecodeBytes(byte[] bytes) {
        // 纯 ASCII（工业 DM 最常见）：零风险直通，避免任何编码猜测
        boolean allAscii = true;
        for (byte b : bytes) {
            if (b < 0) {
                allAscii = false;
                break;
            }
        }
        if (allAscii) {
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        // UTF-8 严格校验（含替换字符则放弃）
        try {
            String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!utf8.contains("\uFFFD")) {
                return utf8;
            }
        } catch (Exception ignored) {
        }
        // GBK：中文工业标签常见编码（宽松解码，可能误判但优于 Latin-1 乱码）
        try {
            return new String(bytes, "GBK");
        } catch (Exception ignored) {
        }
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // cppDecode 专用可复用 Bitmap/像素缓冲（仅解码线程访问，无并发）：
    // 每帧 4~6 次 grayToBitmap，2MP 帧每次分配 ~8MB，频繁分配引发 GC 卡顿
    // （真机识别慢的成因之一），复用后分配归零
    private Bitmap reuseBitmap;
    private int[] reusePixels;
    private int reuseW = -1, reuseH = -1;

    /**
     * 灰度 byte[] → ARGB Bitmap（zxing-cpp Bitmap 接口输入，缓冲复用）
     */
    private Bitmap grayToBitmap(byte[] gray, int width, int height) {
        int n = width * height;
        if (reuseBitmap == null || reuseW != width || reuseH != height) {
            reusePixels = new int[n];
            reuseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            reuseW = width;
            reuseH = height;
        }
        int[] pixels = reusePixels;
        for (int i = 0; i < n; i++) {
            int v = gray[i] & 0xFF;
            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        reuseBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return reuseBitmap;
    }

    /**
     * 全分辨率中心 ROI 解码（第 3 层）：均衡化 + 二值化，两级裁剪（50% / 30%）
     */
    private Result decodeFullResRoi(byte[] gray, int width, int height) {
        Result r = fullResRoiOnce(gray, width, height, 0.5f);
        if (r != null) {
            return r;
        }
        return fullResRoiOnce(gray, width, height, 0.3f);
    }

    private Result fullResRoiOnce(byte[] gray, int width, int height, float ratio) {
        int cropW = Math.max(1, (int) (width * ratio));
        int cropH = Math.max(1, (int) (height * ratio));
        int offX = (width - cropW) / 2;
        int offY = (height - cropH) / 2;
        byte[] roi = new byte[cropW * cropH];
        for (int y = 0; y < cropH; y++) {
            System.arraycopy(gray, (offY + y) * width + offX, roi, y * cropW, cropW);
        }
        ImagePreprocessor.equalizeHistogram(roi);
        Result r = tryDecode(new GrayscaleSource(roi, cropW, cropH), true);
        if (r != null) {
            return r;
        }
        byte[] bin = roi.clone();
        ImagePreprocessor.adaptiveThreshold(bin, cropW, cropH);
        r = tryDecode(new GrayscaleSource(bin, cropW, cropH), false);
        if (r != null) {
            return r;
        }
        // 圆点 DM 兜底：填洞（高光坑底呈黑环+白心）→ 开运算去噪 → 闭运算弥合点间隙
        byte[] dot = bin.clone();
        ImagePreprocessor.fillHoles(dot, cropW, cropH);
        ImagePreprocessor.denoise(dot, cropW, cropH);
        ImagePreprocessor.closeMorph(dot, cropW, cropH, 2);
        r = tryDecode(new GrayscaleSource(dot, cropW, cropH), false);
        if (r != null) {
            return r;
        }
        byte[] dotInv = dot.clone();
        ImagePreprocessor.invert(dotInv);
        r = tryDecode(new GrayscaleSource(dotInv, cropW, cropH), false);
        if (r != null) {
            return r;
        }
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        return tryDecode(new GrayscaleSource(inv, cropW, cropH), false);
    }

    /**
     * 解码 Bitmap（相册选图 / F-12）
     */
    Result decodeBitmap(Bitmap bitmap) {
        long albumT0 = android.os.SystemClock.elapsedRealtime();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        // C++ 主引擎直读（彩色 Bitmap，识别率高于先转灰度）
        Result result = cppDecode(bitmap, cppReader);
        ScanFileLog.log("album cpp " + w + "x" + h + " " + (android.os.SystemClock.elapsedRealtime() - albumT0) + "ms hit=" + (result != null));
        if (result != null) {
            return result;
        }
        setHints();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] gray = new byte[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
        }
        if (!dpmMode) {
            return null;
        }
        // 收集模式（相册单次解码）：全部变体的命中汇总后做文本多数派投票。
        // RS 纠错在模块错误数超容量时可能"假成功"（syndromes 自洽但内容错误），
        // 且不同预处理变体的误读文本互不相同——首个命中直接输出会随机选中
        // 错文本（真机"解码结果与真实内容不对应"）。真值会被多数变体共同读出
        // → 多数派稳定选中真文本；无多数派宁可不输出（报未识别），绝不输出错文本
        java.util.List<Result> hits = new java.util.ArrayList<>();
        decodeGrayDpm(gray, w, h, false, -1, hits);
        int hitsMain = hits.size();
        // 多尺度补充：相册大图（限 1600px）中圆点 DM 点距可能仍远大于解码甜蜜区
        // （点距/降采样后 ≈9~10px，实拍验证），依次补 /4、/8 档
        for (int f : new int[]{4, 8}) {
            if (w / f < 120 || h / f < 120) {
                break;
            }
            byte[] s = ImagePreprocessor.downscale(gray, w, h, f);
            decodeGrayDpm(s, w / f, h / f, true, -1, hits);
        }
        // 点距归一化档：测试图/特写照点距可达 14px+，链族窗口参数与 cpp 的
        // 检测基准都在 9~11px——双线性缩放把点距推回甜蜜区再跑全变体
        // （相机路径 upscale 的相册等价物；/2 档 7px 与原图 14px 都卡在
        // 甜蜜区两侧，真机生成图相册全败的根因）
        int pitch = estimatePitch(gray, w, h);
        ScanFileLog.log("album pitch=" + pitch);
        if (pitch > 10) {
            float k = Math.max(0.3f, 9.5f / pitch);
            Object[] up = ImagePreprocessor.bilinearScale(gray, w, h, k, k);
            byte[] sImg = (byte[]) up[0];
            int sW = (int) up[1], sH = (int) up[2];
            // 暗底图（闪存压痕亮点）反色后与亮底暗点同构，链族/定位器按
            // 常规 DM 亮底暗点处理；直方图中值 <128 判暗底
            boolean inverted = false;
            if (medianGray(sImg) < 128) {
                ImagePreprocessor.invert(sImg);
                inverted = true;
            }
            int before = hits.size();
            decodeGrayDpm(sImg, sW, sH, true, -1, hits);
            ScanFileLog.log("album pitchNorm x" + String.format("%.2f", k)
                    + " -> " + sW + "x" + sH
                    + (inverted ? " inv" : "")
                    + " hits+=" + (hits.size() - before));
        }
        Result voted = pickMajorityResult(hits);
        // 相册诊断埋点：变体命中数与候选文本票型。此前相册路径无日志，
        // 测试图"未识别"时失败环节完全不可见
        java.util.LinkedHashSet<String> texts = new java.util.LinkedHashSet<>();
        for (Result hr : hits) {
            String t = hr.getText();
            if (t != null) {
                texts.add(t.length() > 20 ? t.substring(0, 20) + ".." : t);
            }
        }
        ScanFileLog.log("album gray " + w + "x" + h
                + " hits=" + hitsMain + "/" + hits.size()
                + " votes=" + texts
                + " -> " + (voted == null ? "NONE" : voted.getText())
                + " " + (android.os.SystemClock.elapsedRealtime() - albumT0) + "ms");
        return voted;
    }

    /**
     * 文本多数派投票：票数最高且严格过半者胜出（返回该文本的任一命中），
     * 否则返回 null。单一命中 = 1/1 自然过半，原样输出维持可用性。
     * DotPeen 码模块噪声大时不同变体可能各自"成功"读出不同文本（RS 假成功），
     * 唯有多数派文本值得信任——真值会被不同预处理管线共同读出。
     */
    private static Result pickMajorityResult(java.util.List<Result> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        java.util.HashMap<String, Result> byText = new java.util.HashMap<>();
        java.util.HashMap<String, Integer> votes = new java.util.HashMap<>();
        int total = 0;
        for (Result r : hits) {
            String t = r.getText();
            if (t == null || t.isEmpty()) {
                continue;
            }
            byText.putIfAbsent(t, r);
            votes.merge(t, 1, Integer::sum);
            total++;
        }
        String bestText = null;
        int bestCnt = 0;
        for (java.util.Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCnt) {
                bestText = e.getKey();
                bestCnt = e.getValue();
            }
        }
        if (bestText != null && bestCnt * 2 > total) {
            return byText.get(bestText);
        }
        return null; // 平票/无过半：不输出疑似错文本
    }

    /**
     * DPM 增强管线（F-25 ~ F-28，DM 识别率优化版）：
     * 1. 直方图均衡化（非线性增强，对抗金属反光/明暗渐变）
     * 2. 均衡化 + 拉普拉斯锐化（模块边界增强）
     * 3. 自适应二值化 + 形态学开运算去噪（金属噪点/划痕清理）
     * 4. 反色（暗底亮码）+ 四方向旋转重试
     * 5. 多级 ROI 放大（中心 50% 2x / 30% 3x，覆盖小尺寸 DM 码）
     */
    private Result decodeGrayDpm(byte[] gray, int width, int height) {
        return decodeGrayDpm(gray, width, height, false, -1, null);
    }

    /**
     * @param preDownscaled true=输入已是降采样图（如 decodeYuv 的 factor-3 图），
     *                      跳过内部 /2，避免二次降采样把圆点 DM 点距压到不可解码尺度；
     *                      false=原始大图（相册路径），保持内部 /2 降采样提速
     * @param roiPhase      ROI 放大档位轮转相位：-1=三档全跑（相册单次，正确性优先）；
     *                      >=0 时只跑一档（camera 帧轮转，2.76MP 放大解码单档数百 ms，
     *                      原三档连跑是级联 2s 的主要构成之一）
     */
    private Result decodeGrayDpm(byte[] gray, int width, int height, boolean preDownscaled, int roiPhase) {
        return decodeGrayDpm(gray, width, height, preDownscaled, roiPhase, null);
    }

    /**
     * @param collectList 非 null = 收集模式（相册单次解码）：全部变体的命中都加入
     *                    列表并继续跑后续变体，不提前返回；调用方做多数派投票。
     *                    null = 首中即返回（相机帧，低延迟优先）
     */
    private Result decodeGrayDpm(byte[] gray, int width, int height, boolean preDownscaled, int roiPhase,
                                 java.util.List<Result> collectList) {
        int dw, dh;
        byte[] small;
        if (preDownscaled) {
            dw = width;
            dh = height;
            small = gray;
        } else {
            // 全帧增强管线在 1/2 降采样图上执行：像素量降 4 倍 → 单帧耗时大幅下降 →
            // 单位时间可尝试更多帧，金属刻印码靠多帧概率累积的效率显著提升。
            // ROI 放大路径仍用原始分辨率，保证小尺寸码的模块细节不损失。
            dw = Math.max(1, width / 2);
            dh = Math.max(1, height / 2);
            small = ImagePreprocessor.downscale(gray, width, height, 2);
        }

        // 1. 直方图均衡化（比线性对比度拉伸更适合非线性光照）
        byte[] enhanced = small.clone();
        ImagePreprocessor.equalizeHistogram(enhanced);
        Result r = tryDecode(new GrayscaleSource(enhanced, dw, dh), true);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 1.5 CLAHE 分块自适应均衡化：金属面局部反光/阴影（一亮一暗分区）时
        // 全局均衡化会把亮区压白、暗区压黑，分块均衡化保留局部对比度；
        // clipLimit 抑制喷砂噪声放大
        byte[] cl = small.clone();
        ImagePreprocessor.clahe(cl, dw, dh);
        r = tryDecode(new GrayscaleSource(cl, dw, dh), true);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 2. 均衡化 + 锐化：模块边界增强
        byte[] sharpened = enhanced.clone();
        ImagePreprocessor.sharpen(sharpened, dw, dh);
        r = tryDecode(new GrayscaleSource(sharpened, dw, dh), true);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 3. 自适应二值化 + 形态学开运算去噪
        byte[] bin = enhanced.clone();
        ImagePreprocessor.adaptiveThreshold(bin, dw, dh);
        ImagePreprocessor.denoise(bin, dw, dh);
        r = tryDecode(new GrayscaleSource(bin, dw, dh), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 4. 反色（暗底亮码，激光刻印白字）
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        r = tryDecode(new GrayscaleSource(inv, dw, dh), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 5. 旋转重试（去噪二值图 3 方向）：仅二值图旋转——
        // 锐化灰度图的 3 次旋转解码（真机实测每帧 ~250ms）对识别率贡献极小，
        // zxing-cpp 层 tryRotate 已覆盖旋转场景
        int[] degreesArr = {90, 180, 270};
        for (int deg : degreesArr) {
            byte[] rotatedBin = ImagePreprocessor.rotate(bin, dw, dh, deg);
            int rw = deg == 180 ? dw : dh;
            int rh = deg == 180 ? dh : dw;
            r = tryDecode(new GrayscaleSource(rotatedBin, rw, rh), false);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
        }

        // 5.5 圆点 DM（DotPeen）专项：
        // ① 实拍图参数扫描命中的成功链族【并行优先】（唯一命中组合）：
        //    原始灰度（无均衡化——均衡化会放大喷砂噪声对比度导致全败）
        //    + 低阈值 C=4/6 + fillHoles→denoise→close1/2，
        //    4 组 (窗口,C) 参数独立管线线程池并发（内部已做多数派裁决）；
        // ② 命中参数的 zxing-cpp 兜底；
        // ③ 均衡化变体（未命中时的补充覆盖，串行）。
        // 注意：开运算去噪不能在 fillHoles 之前（会把"黑环+白心"的环形圆点一并抹掉）
        r = decodeDotChainsParallel(small, dw, dh);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // ② 命中参数链交 zxing-cpp（C++ DM 容错远强于 Java 版，tryInvert 已开启）
        r = cppDotProbe(small, dw, dh);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // ③ 均衡化变体（历史兼容覆盖）：相机帧隔帧执行——并行链+cppDotProbe 已覆盖
        // 验证过的组合，这批变体每帧跑会把级联拖到 2s（真机实测）。
        // 相册单次解码（roiPhase<0）必须全量执行：同一张图重复识别结果必须一致，
        // 不能受相机流 frameCounter 相位影响（真机"同图多次扫描结果不一致"根因之一）
        if (roiPhase < 0 || (frameCounter & 1) == 0) {
            byte[] binRaw = enhanced.clone();
            ImagePreprocessor.adaptiveThreshold(binRaw, dw, dh);

            byte[] close1 = binRaw.clone();
            ImagePreprocessor.closeMorph(close1, dw, dh);
            r = tryDecodeBinWithInvert(close1, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            byte[] close2 = binRaw.clone();
            ImagePreprocessor.closeMorph(close2, dw, dh, 2);
            r = tryDecodeBinWithInvert(close2, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            byte[] filled = binRaw.clone();
            ImagePreprocessor.fillHoles(filled, dw, dh);
            r = tryDecodeBinWithInvert(filled, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(filled, dw, dh, 2);
            r = tryDecodeBinWithInvert(filled, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            // 离线合成图测试验证的最优链（变体 22/23 均成功）：
            // fillHoles（高光环点→实心盘，实心盘能扛住开运算）→ denoise（清背景散点）
            // → 闭运算（弥合点间隙）。顺序不可颠倒：先去噪会把环形圆点一并抹掉
            byte[] dotSolid = binRaw.clone();
            ImagePreprocessor.fillHoles(dotSolid, dw, dh);
            ImagePreprocessor.denoise(dotSolid, dw, dh);
            r = tryDecodeBinWithInvert(dotSolid, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(dotSolid, dw, dh);
            r = tryDecodeBinWithInvert(dotSolid, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(dotSolid, dw, dh);
            r = tryDecodeBinWithInvert(dotSolid, dw, dh);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            // Java 引擎全部失败 → 把二值化成果交给 zxing-cpp
            r = cppDecode(grayToBitmap(dotSolid, dw, dh), cppReaderGlobal);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            r = cppDecode(grayToBitmap(close2, dw, dh), cppReaderGlobal);
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
        }

        // 6. ROI 放大（小尺寸 DM 码）：三档轮转
        // （放大解码单档数百 ms，相机帧每帧只跑一档由 roiPhase 轮转；
        //   相册单次识别 roiPhase=-1 三档全跑，正确性优先）
        if (roiPhase < 0) {
            decodeRoi(gray, width, height, 0.5f, 2, collectList);
            decodeRoi(gray, width, height, 0.3f, 3, collectList);
            decodeRoi(gray, width, height, 0.4f, 2, collectList);
            return null;
        }
        int idx = roiPhase % 3;
        return decodeRoi(gray, width, height,
                idx == 0 ? 0.5f : idx == 1 ? 0.3f : 0.4f,
                idx == 1 ? 3 : 2, null);
    }

    /**
     * 中心区域裁剪（微信式 ROI 优先解码）
     */
    private static LuminanceSource cropCenter(LuminanceSource source, float ratio) {
        int w = Math.max(1, (int) (source.getWidth() * ratio));
        int h = Math.max(1, (int) (source.getHeight() * ratio));
        return source.crop((source.getWidth() - w) / 2, (source.getHeight() - h) / 2, w, h);
    }

    /**
     * 单级 ROI：裁剪放大 → 灰度/二值/反色三路解码
     */
    private Result decodeRoi(byte[] gray, int width, int height, float ratio, int scale) {
        return decodeRoi(gray, width, height, ratio, scale, null);
    }

    /**
     * @param collectList 非 null = 收集模式（相册单次解码）：命中加入列表并继续
     */
    private Result decodeRoi(byte[] gray, int width, int height, float ratio, int scale,
                             java.util.List<Result> collectList) {
        Object[] cropped = ImagePreprocessor.cropAndScale(gray, width, height, ratio, ratio, scale);
        @SuppressWarnings("unchecked")
        byte[] roi = (byte[]) cropped[0];
        int roiW = (int) cropped[1];
        int roiH = (int) cropped[2];
        Result r = tryDecode(new GrayscaleSource(roi, roiW, roiH), true);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        byte[] roiBin = roi.clone();
        // 放大 scale 倍后模块间距同步放大，窗口需按倍数扩大，
        // 否则局部均值跟随圆点自适应会把点"吃掉"
        ImagePreprocessor.adaptiveThreshold(roiBin, roiW, roiH,
                ImagePreprocessor.ADAPTIVE_WINDOW * scale, ImagePreprocessor.ADAPTIVE_C);
        r = tryDecode(new GrayscaleSource(roiBin, roiW, roiH), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        byte[] roiInv = roiBin.clone();
        ImagePreprocessor.invert(roiInv);
        r = tryDecode(new GrayscaleSource(roiInv, roiW, roiH), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 圆点 DM 兜底：填洞（高光坑底呈黑环+白心）→ 开运算去噪 → 闭运算弥合点间隙
        byte[] roiDot = roiBin.clone();
        ImagePreprocessor.fillHoles(roiDot, roiW, roiH);
        ImagePreprocessor.denoise(roiDot, roiW, roiH);
        ImagePreprocessor.closeMorph(roiDot, roiW, roiH, 2);
        r = tryDecode(new GrayscaleSource(roiDot, roiW, roiH), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        byte[] roiDotInv = roiDot.clone();
        ImagePreprocessor.invert(roiDotInv);
        r = tryDecode(new GrayscaleSource(roiDotInv, roiW, roiH), false);
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }
        return null;
    }

    /**
     * 实拍命中参数 (win=61, C=4) 链的 zxing-cpp 兜底（C++ DM 容错远强于 Java 版，
     * tryInvert 已开启）。低像素量时代价小，供主尺度与辅尺度探针复用。
     */
    private Result cppDotProbe(byte[] gray, int width, int height) {
        if (61 >= Math.min(width, height) - 2) {
            return null;
        }
        byte[] bin = gray.clone();
        ImagePreprocessor.adaptiveThreshold(bin, width, height, 61, 4);
        ImagePreprocessor.fillHoles(bin, width, height);
        ImagePreprocessor.denoise(bin, width, height);
        ImagePreprocessor.closeMorph(bin, width, height);
        return cppDecode(grayToBitmap(bin, width, height), cppReaderGlobal);
    }

    /**
     * 圆点 DM 命中链并行解码：4 组 (窗口,C) 参数各自独立管线，
     * 线程池并发执行；收集时限内全部完成链的结果做文本多数派裁决
     * （严格过半才输出，无多数派返回 null）。
     * 每条链使用局部 MultiFormatReader——共享 reader 非线程安全。
     */
    private Result decodeDotChainsParallel(byte[] gray, int width, int height) {
        if (dotDecodePool == null) {
            dotDecodePool = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "dot-decode");
                t.setDaemon(true);
                return t;
            });
        }
        int[][] params = {{61, 4}, {61, 6},
                {ImagePreprocessor.ADAPTIVE_WINDOW, 4}, {ImagePreprocessor.ADAPTIVE_WINDOW, 6}};
        java.util.List<java.util.concurrent.Callable<Result>> tasks = new java.util.ArrayList<>();
        for (int[] wc : params) {
            final int win = wc[0];
            final int c = wc[1];
            if (win < Math.min(width, height) - 2) {
                tasks.add(() -> decodeDotChain(gray, width, height, win, c));
            }
        }
        if (tasks.isEmpty()) {
            return null;
        }
        // 收集全部在时限内完成的链结果 + 多数派裁决（invokeAny 竞速随机性的根治）：
        // DotPeen 码模块噪声大时，多条 (win,C) 链可能各自"成功"读出不同文本
        // （RS 纠错超容量时输出错误内容，不同二值化参数下错误模式不同），
        // invokeAny 返回最先完成者——线程调度顺序随机 → 同一张图每次结果不一致。
        // 改为：文本多数派（> 半数）才输出；无多数派返回 null（宁缺毋滥，
        // 交 cppDotProbe/其他层/下帧重试），重复扫描结果稳定一致
        java.util.concurrent.ExecutorCompletionService<Result> cs =
                new java.util.concurrent.ExecutorCompletionService<>(dotDecodePool);
        java.util.List<java.util.concurrent.Future<Result>> futures = new java.util.ArrayList<>();
        for (java.util.concurrent.Callable<Result> t : tasks) {
            futures.add(cs.submit(t));
        }
        java.util.List<Result> hits = new java.util.ArrayList<>();
        long deadline = android.os.SystemClock.elapsedRealtime() + 300;
        int pending = futures.size();
        try {
            while (pending > 0) {
                long remain = deadline - android.os.SystemClock.elapsedRealtime();
                if (remain <= 0) {
                    break; // 未完成链随超时取消，该尺度下帧轮转回来再试
                }
                java.util.concurrent.Future<Result> f =
                        cs.poll(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {
                    break;
                }
                pending--;
                Result r = f.get();
                if (r != null && r.getText() != null && !r.getText().isEmpty()) {
                    hits.add(r);
                }
            }
        } catch (Exception ignored) {
            // 单链异常：继续等其余链
        } finally {
            for (java.util.concurrent.Future<Result> f : futures) {
                f.cancel(true); // 中断未完成链，及时释放线程池
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        java.util.HashMap<String, Integer> votes = new java.util.HashMap<>();
        for (Result r : hits) {
            votes.merge(r.getText(), 1, Integer::sum);
        }
        String bestText = null;
        int bestCnt = 0;
        for (java.util.Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCnt) {
                bestText = e.getKey();
                bestCnt = e.getValue();
            }
        }
        // 多数派门槛：严格过半。链间互相印证才输出
        if (bestText != null && bestCnt * 2 > hits.size()) {
            for (Result r : hits) {
                if (r.getText().equals(bestText)) {
                    return r;
                }
            }
        }
        return null; // 无多数派：不输出随机乱码
    }

    /**
     * 单条圆点命中链：原始灰度（无均衡化）→ 自适应(win,C) → fillHoles → denoise
     * → close1/close2 → GlobalHist/Hybrid × 正/反色（局部 reader）。
     * 各阶段间响应中断：被取消的任务尽快退出，释放线程池。
     */
    private Result decodeDotChain(byte[] gray, int width, int height, int win, int c) {
        byte[] bin = gray.clone();
        ImagePreprocessor.adaptiveThreshold(bin, width, height, win, c);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.fillHoles(bin, width, height);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.denoise(bin, width, height);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.closeMorph(bin, width, height);
        MultiFormatReader local = newLocalReader();
        Result r = tryDecodeWith(local, bin, width, height);
        if (r != null) return r;
        ImagePreprocessor.closeMorph(bin, width, height);
        return tryDecodeWith(local, bin, width, height);
    }

    /**
     * 局部 MultiFormatReader（并行任务专用，非线程安全不可共享）
     */
    private MultiFormatReader newLocalReader() {
        MultiFormatReader local = new MultiFormatReader();
        local.setHints(buildHints());
        return local;
    }

    /**
     * 单一数据多路解码：GlobalHist → Hybrid → 反色 GlobalHist → 反色 Hybrid
     */
    private Result tryDecodeWith(MultiFormatReader r, byte[] data, int width, int height) {
        try {
            GrayscaleSource source = new GrayscaleSource(data, width, height);
            Result res = tryOnce(r, new BinaryBitmap(new GlobalHistogramBinarizer(source)));
            if (res == null) {
                r.reset();
                res = tryOnce(r, new BinaryBitmap(new HybridBinarizer(source)));
                if (res == null) {
                    r.reset();
                    byte[] inv = data.clone();
                    ImagePreprocessor.invert(inv);
                    GrayscaleSource invSource = new GrayscaleSource(inv, width, height);
                    res = tryOnce(r, new BinaryBitmap(new GlobalHistogramBinarizer(invSource)));
                    if (res == null) {
                        r.reset();
                        res = tryOnce(r, new BinaryBitmap(new HybridBinarizer(invSource)));
                    }
                }
            }
            return res;
        } finally {
            r.reset();
        }
    }

    private Result tryOnce(MultiFormatReader r, BinaryBitmap bitmap) {
        try {
            Result res = r.decodeWithState(bitmap);
            if (res != null && res.getBarcodeFormat() == com.google.zxing.BarcodeFormat.DATA_MATRIX) {
                res = fixJavaDmText(res);
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 圆点 DM 二值变体尝试：GlobalHist/Hybrid 二值器 × 正/反色（Java 引擎）。
     * 同一数据换二值器无拷贝开销，临界质量二值图的成功率明显提升。
     */
    private Result tryDecodeBinWithInvert(byte[] bin, int width, int height) {
        GrayscaleSource source = new GrayscaleSource(bin, width, height);
        Result r = tryDecode(source, false);
        if (r != null) {
            return r;
        }
        r = tryDecode(source, true);
        if (r != null) {
            return r;
        }
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        GrayscaleSource invSource = new GrayscaleSource(inv, width, height);
        r = tryDecode(invSource, false);
        if (r != null) {
            return r;
        }
        return tryDecode(invSource, true);
    }

    private Result tryDecode(LuminanceSource source, boolean hybrid) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                    hybrid ? new HybridBinarizer(source) : new GlobalHistogramBinarizer(source));
            Result r = reader.decodeWithState(bitmap);
            if (r != null && r.getBarcodeFormat() == com.google.zxing.BarcodeFormat.DATA_MATRIX) {
                r = fixJavaDmText(r);
            }
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    /**
     * Java zxing DM 解码文本字符集修正：
     * Java DM 解码器把非 ASCII 字节按 Latin-1 直出（真机日志
     * "ä½ å¥½ï¼ä¸çï¼" = "你好，世界！"的 UTF-8 字节），cpp 路径有
     * extractText 智能解码而 Java 路径没有。含高位字符时按 Latin-1
     * 还原字节流 → 严格 UTF-8 校验 → 通过则替换，失败原样返回。
     */
    private static Result fixJavaDmText(Result r) {
        String text = r.getText();
        if (text == null) {
            return r;
        }
        boolean hasHigh = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                hasHigh = true;
                break;
            }
        }
        if (!hasHigh) {
            return r;
        }
        try {
            byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            String utf8 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (!utf8.contains("\uFFFD")) {
                return new Result(utf8, r.getRawBytes(), r.getResultPoints(), r.getBarcodeFormat());
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    private void setHints() {
        reader.setHints(buildHints());
    }

    /**
     * 构建 hints（主 reader 与并行局部 reader 共用）
     */
    private Map<DecodeHintType, Object> buildHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        java.util.List<com.google.zxing.BarcodeFormat> zxingFormats = new java.util.ArrayList<>();
        for (String f : formats) {
            try {
                zxingFormats.add(com.google.zxing.BarcodeFormat.valueOf(f));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (zxingFormats.isEmpty()) {
            hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.allOf(com.google.zxing.BarcodeFormat.class));
        } else {
            hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.copyOf(zxingFormats));
        }
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE); // F-24
        return hints;
    }

    /**
     * 释放资源（DpmImageAnalyzer.release 调用）
     */
    void release() {
        if (dotDecodePool != null) {
            dotDecodePool.shutdownNow();
            dotDecodePool = null;
        }
    }

    /**
     * 从 YUV Y 平面提取紧凑灰度图（去除行跨距）
     */
    private static byte[] extractGray(byte[] yPlane, int width, int height, int rowStride) {
        byte[] out = new byte[width * height];
        int stride = Math.max(rowStride, width);
        int cap = yPlane.length;
        for (int y = 0; y < height; y++) {
            int src = y * stride;
            // Y 平面布局：末行可能不足 rowStride（无 padding 尾部），越界行用上一行灰度近似，
            // 不再整帧丢弃（原实现此处会 ArrayIndexOutOfBounds，analyze 侧曾为规避而整帧 return）
            if (src + width > cap) {
                if (y > 0) {
                    System.arraycopy(out, (y - 1) * width, out, y * width, width);
                } else {
                    java.util.Arrays.fill(out, 0, width, (byte) 128);
                }
                continue;
            }
            System.arraycopy(yPlane, src, out, y * width, width);
        }
        return out;
    }

    /**
     * 灰度 byte[] 的 LuminanceSource 适配
     */
    static final class GrayscaleSource extends LuminanceSource {
        private final byte[] gray;

        GrayscaleSource(byte[] gray, int width, int height) {
            super(width, height);
            this.gray = gray;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < getWidth()) {
                row = new byte[getWidth()];
            }
            System.arraycopy(gray, y * getWidth(), row, 0, getWidth());
            return row;
        }

        @Override
        public byte[] getMatrix() {
            return gray;
        }

        @Override
        public boolean isCropSupported() {
            return true;
        }

        @Override
        public LuminanceSource crop(int left, int top, int width, int height) {
            byte[] out = new byte[width * height];
            for (int y = 0; y < height; y++) {
                System.arraycopy(gray, (top + y) * getWidth() + left, out, y * width, width);
            }
            return new GrayscaleSource(out, width, height);
        }
    }
}
