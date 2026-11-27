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
    // DM 确认门槛 2 次：跨帧两次同文本仍互证（乱码需连续两帧读出同一错文本，
    // 同角度系统性误读概率低于真文本命中概率——真文本总被任何层稳定读出）。
    // 3 次门槛在 miss 帧 1~2s 的低帧率下把慢层（cppSm/dg/链族）命中后的
    // 出码拖到 3~6s（真机 dm pending 2~11 轮才 confirmed 实证）；cpp1+g1
    // 同帧双命中已占满 2 次，快层路径不受影响。dg 层维持 1 次、兜底层 +1
    private static final int DM_CONFIRM_HITS = 2;
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

    // ===== QR 智能渐进变焦（微信式：持续未识别 → 逐级放大试扫远处小码）=====
    // 首次未识别时间：0=已重置（识别成功/用户手动缩放后重新计时）
    private long qrNoResultSinceMs = 0;
    // 上次升档时间
    private long qrLastStepMs = 0;
    // 当前 QR 目标倍率（与 Activity 实际倍率同步起点，只升不降）
    private float qrZoomRatio = 1.0f;
    // 数码 zoom 上限 4x：QR 对插值容忍高于 DPM 点阵，超过 4x 画质衰减识别率反降
    private static final float QR_ZOOM_MAX = 4.0f;
    private static final float QR_ZOOM_STEP = 0.5f;
    // 升档间隔：每档停留 1.2s 给 AF 收敛 + 多帧尝试
    private static final long QR_ZOOM_STEP_MS = 1200;

    void setZoomListener(ZoomRequestListener l) {
        this.zoomListener = l;
    }

    /** 暗底自动补光监听：解码线程回调，实现方调 CameraControl.enableTorch */
    interface LightRequestListener {
        void onAutoTorchRequest(boolean on);
    }

    private volatile LightRequestListener lightListener;
    // 暗底持续起始时刻（自动补光判定，仅解码线程访问）
    private long darkSinceMs = 0;
    // 自动补光是否已触发（每会话一次；用户手动关灯后不再自动干预）
    private boolean autoTorchDone = false;

    void setLightListener(LightRequestListener l) {
        this.lightListener = l;
    }

    /**
     * 自动变焦决策（仅解码线程调用，decodeCascade 的 pitchEst 之后）：
     * pitchEst 3~7 = 点距低于甜蜜区一半，码太远/太小 → 建议光学变焦
     * 把点距推到 ~9.5px（与软放大同公式，但光学放大保留真实像素，
     * 质量高于 upscale 插值——zoom 后 pitchEst 变大，软放大自然不再触发）；
     * 连续 2 帧小点距才触发（防噪声）；冷却防 zoom 后反复震荡。
     * 回退：6s 未追踪到任何疑似码 → 建议 1.0（用户已换目标，3x 视野过窄）。
     */
    /** pitchEst 平滑环形缓冲：run 统计的 estimatePitch 在锈蚀背景上乱跳
     *  8~74（今早日志 149 帧 pitchEst 两峰分布），导致 main skip 乱切 +
     *  auto zoom 抖动。dg 质心法 pitch（pDn247@5.19）逐帧稳定，miss 时
     *  回填此缓冲，决策取中位数 */
    private final int[] pitchHist = new int[9];
    private int pitchHistN;
    private int noSignalStreak;

    private void pushPitch(int p) {
        if (p <= 0) {
            return;
        }
        pitchHist[pitchHistN++ % pitchHist.length] = p;
    }

    private int medianPitch() {
        if (pitchHistN == 0) {
            return -1;
        }
        int n = Math.min(pitchHistN, pitchHist.length);
        int[] a = new int[n];
        System.arraycopy(pitchHist, 0, a, 0, n);
        java.util.Arrays.sort(a);
        return a[n / 2];
    }

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
        // 回退：长时间无码信号（点距不可测或过大）且 6s 无任何追踪命中。
        // 连击保护：estimatePitch 乱跳时单帧 noSignal 会反复触发 req/reset
        // 横跳（今早日志 applied 1.5 → 3s 后 reset 1.0 实证）
        boolean noSignal = pitchEst < 3 || pitchEst > 11;
        if (noSignal) {
            noSignalStreak++;
        } else {
            noSignalStreak = 0;
        }
        if (noSignalStreak >= 8 && now - lastTrackMs > 6000) {
            noSignalStreak = 0;
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

    /**
     * 暗底自动补光（仅 DPM 模式）：持续 ≥1.2s 暗底（ROI 中值 <128，直方图
     * O(N)）→ 请求开灯。黑环氧压痕（flash_dark 型）弱反光点在无补光 +
     * DPM 金属高光负曝光补偿（bind 时 torch off 设 -3EV）下被压到阈值下
     * （真机质心召回仅 20~30%），开灯后点反光进入传感器动态范围、AF 获得
     * 对比度。每会话触发一次即止；确认中（dmHitCounts 非空）不打断凑帧；
     * 用户手动操作过补光后由 Activity 侧拦截不再干预
     */
    private void handleAutoTorch(boolean dark, long now) {
        if (!dark) {
            darkSinceMs = 0;
            return;
        }
        if (!dpmMode || autoTorchDone || !dmHitCounts.isEmpty()) {
            return;
        }
        if (darkSinceMs == 0) {
            darkSinceMs = now;
        } else if (now - darkSinceMs >= 1200) {
            autoTorchDone = true;
            LightRequestListener l = lightListener;
            if (l != null) {
                ScanFileLog.log("auto torch on (dark scene)");
                l.onAutoTorchRequest(true);
            }
        }
    }

    /** QR 智能变焦：Activity 应用初始/记忆倍率后同步起点（解码开始前调用） */
    void initQrZoom(float startRatio) {
        qrZoomRatio = Math.max(1.0f, Math.min(QR_ZOOM_MAX, startRatio));
    }

    /** 用户手动捏合缩放：用户接管变焦，重置未识别计时（重新观察 1.2s 再考虑升档） */
    void notifyUserZoom() {
        qrNoResultSinceMs = 0;
    }

    /** QR 识别成功：重置计时；已放大时请求回 1.0（连续模式扫下一个码从全景视野开始） */
    private void onQrDecoded(long now) {
        qrNoResultSinceMs = 0;
        qrLastStepMs = now;
        if (qrZoomRatio > 1.0f) {
            qrZoomRatio = 1.0f;
            ZoomRequestListener l = zoomListener;
            if (l != null) {
                l.onZoomRequest(1.0f);
            }
        }
    }

    /**
     * QR 渐进变焦决策（仅 QR 模式解码线程，cpp1 未命中处调用）：
     * 持续 QR_ZOOM_STEP_MS 未识别 → 升 0.5 档（上限 4x）再试——远处小码
     * 放大后模块像素变大，可解码性显著提升（微信"扫不到就自动放大"同款策略）；
     * 识别成功/用户捏合缩放即重置计时。升档只升不降，回 1.0 唯一路径是识别成功。
     */
    private void handleQrAutoZoom(long now) {
        ZoomRequestListener l = zoomListener;
        if (l == null) {
            return;
        }
        if (qrNoResultSinceMs == 0) {
            qrNoResultSinceMs = now;
            return;
        }
        if (now - qrNoResultSinceMs < QR_ZOOM_STEP_MS
                || now - qrLastStepMs < QR_ZOOM_STEP_MS) {
            return;
        }
        if (qrZoomRatio >= QR_ZOOM_MAX) {
            return; // 已到上限，保持最大倍率持续尝试
        }
        qrZoomRatio = Math.min(QR_ZOOM_MAX, qrZoomRatio + QR_ZOOM_STEP);
        qrLastStepMs = now;
        Log.i(TAG, "qr zoom req " + qrZoomRatio);
        ScanFileLog.log("qr zoom req " + qrZoomRatio);
        l.onZoomRequest(qrZoomRatio);
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

        // 小码探针专用 reader（全格式，含 QR）：DPM 模式 cpp1/g1 限 DM-only
        // 是历史决策（喷砂纹理稳定误判 QR 直出），但代价是普通 QR 只能靠
        // Java 链族偶发命中（真机 ROL-W10 实测小 QR 出码 5~32s）。
        // 本 reader 仅用于 cppSm 小码探针层（取景框 ROI 内、结果走跨帧
        // 2 次确认防误读），速度与产线安全兼顾
        zxingcpp.BarcodeReader.Options qrOpts = new zxingcpp.BarcodeReader.Options();
        qrOpts.setTryHarder(true);
        qrOpts.setTryRotate(true);
        qrOpts.setTryInvert(true);
        qrOpts.setTryDownscale(true);
        qrOpts.setTextMode(zxingcpp.BarcodeReader.TextMode.PLAIN);
        cppReaderQr = new zxingcpp.BarcodeReader(qrOpts);

        // BOOL_CAST 二值透传 reader：输入已是 0/255 二值图，cpp 跳过二值化
        // 直接定位解码。解决 LOCAL_AVERAGE 窗口≈短边/8 吞小码模块的问题
        // （小 QR 整码落在 1~2 个窗口内被均值抹平，放大亦无解——真机
        // 16:01 会话 cppSm 91s 全 miss 实证）。二值化由 Java 自适应阈值
        // （win=25 固定窗口，对小码有效）完成，检测/解码交给 cpp
        zxingcpp.BarcodeReader.Options binOpts = new zxingcpp.BarcodeReader.Options();
        binOpts.setTryHarder(true);
        binOpts.setTryRotate(true);
        binOpts.setTryInvert(true);
        binOpts.setTryDownscale(true);
        binOpts.setTextMode(zxingcpp.BarcodeReader.TextMode.PLAIN);
        binOpts.setBinarizer(zxingcpp.BarcodeReader.Binarizer.BOOL_CAST);
        cppReaderQrBool = new zxingcpp.BarcodeReader(binOpts);
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
        // 非 DM（QR/一维码）与 DM 统一走 2 次跨帧确认：曾试过非 DM 单次直出
        // （15:13 版本），但产线喷砂纹理伪造 QR 是稳定复现的（历史 14:05:36
        // 连续 7 帧误读），单次直出会放行错误数据；cppSm 探针命中率上来后
        // 2 次确认总延迟 ~1s，安全代价可接受
        String key = text;
        long now = android.os.SystemClock.elapsedRealtime();
        // "f"（全分辨率 ROI 粗二值化）垃圾误读率高（真机实测产出 "88"/"07132546"），
        // 确认门槛提高一档；"dg"（DotGrid 网格直解）相反单次即确认：网格对齐 +
        // RS 内核 + 多阈值一致性检验的置信度远高于链族（真机日志 dg 命中文本
        // 全部正确），且兜底层命中稀疏，跨帧凑 2 次的窗口经常等不到
        // （真机 11:26:30 pending [dg] 后无第二次命中，用户已感知为识别失败）
        int need = scaleTag.startsWith("dg") ? 1
                : scaleTag.startsWith("f") ? DM_CONFIRM_HITS + 1 : DM_CONFIRM_HITS;
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
            dmHitCounts.put(key, new long[]{need, now});
            Log.d(TAG, "dm pending confirm [" + scaleTag + "]: " + text);
            ScanFileLog.log("dm pending [" + scaleTag + "]: " + text);
            if (need <= 1) {
                // dg 单次直出：走与确认路径相同的去重/输出逻辑
                dmHitCounts.remove(key);
                Long last0 = deliveredAt.get(text);
                if (last0 != null && now - last0 < DELIVER_DEDUPE_MS) {
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
        Result result = cppDecodeInRoi(grayToBitmap(gray, width, height), cppReader, roi, "c1");
        long t1 = android.os.SystemClock.elapsedRealtime();
        Log.d(TAG, "cpp1 " + (t1 - t0) + "ms hit=" + (result != null));
        ScanFileLog.log("cpp1 " + (t1 - t0) + "ms hit=" + (result != null));
        if (result != null) {
            // 高置信度格式直出；DM 走文本级跨帧确认（未确认时继续后续层级）
            if (result.getBarcodeFormat() != com.google.zxing.BarcodeFormat.DATA_MATRIX) {
                if (!dpmMode) {
                    onQrDecoded(android.os.SystemClock.elapsedRealtime());
                }
                return result;
            }
            Result confirmed = confirmCameraResult(result, "c1");
            if (confirmed != null) {
                return confirmed;
            }
        }

        if (!dpmMode) {
            // QR 模式 cpp1 未命中：渐进变焦决策（远处小码 → 逐级放大试扫）
            handleQrAutoZoom(android.os.SystemClock.elapsedRealtime());
            return null;
        }

        // ===== 第 2 层：全局直方图二值化兜底（圆点 DM 专项，全分辨率）=====
        // DotPeen 圆点码的模块是孤立圆点，局部均值二值化（LOCAL_AVERAGE）在点间隙处
        // 易产生误判；GLOBAL_HISTOGRAM 按全图灰度分布取阈值，对圆点边缘响应不同
        result = cppDecodeInRoi(grayToBitmap(gray, width, height), cppReaderGlobal, roi, "g1");
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
        // cpp1/g1 快速命中层保持全帧解码（特写大码码 >框时 ROI 会裁掉 L 型定位边），
        // 框外码由 cppDecodeInRoi 按码面积交集过滤输出（平板框外码误识别修复）
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
        // 落入 (win,C) 参数族工作区间且提速 4 倍。
        // DPM 阈值 700：viewRoi 裁剪帧 972x748 ≤1000 会直落 factor=1，m2
        // 全分辨率 dot chains 单帧 4~5s（11-24 14:19:05 cascade 5203ms 实证，
        // 期间 pitchEst=10 触发 m2 但吃全分辨率）。QR 模式保持 1000 保细节
        int factor = Math.max(width, height) > (dpmMode ? 700 : 1000) ? 2 : 1;
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
     * 3.2 辅尺度 {3,4,5,6,8,10} 逐帧轮转，只跑并行命中链 + C++ 兜底
     *     （高倍降采样像素量小，代价几十 ms；多帧累积覆盖全尺度带）
     */
    private Result decodeCascade(byte[] gray, int width, int height, byte[] small, int sw, int sh, int factor) {
        Result result;
        frameCounter++;
        // 级联软时限：miss 帧各层累计可达 1~3s（真机 12:59 会话实证），
        // 有效分析帧率跌到 0.3~0.8fps——确认帧跟不上、用户体感"慢"。
        // 尾部分层（aux/fullResRoi/cppDotProbe）超时即跳过，下帧补位；
        // 首中即返回的层（main/cppSm/dg）不受限
        long tCascade = android.os.SystemClock.elapsedRealtime();
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

        // ===== 点距估计 + DotPeen 点阵网格直解（dg，快层前移）=====
        // 离线实证（合成图原始像素，Raster 直读与真机 YUV 语义对齐）：
        // DotGridDecoder 双极性（暗底亮点/亮底暗点）网格直解对点阵码全解
        // （flash_dark 负片 59ms / alu_clean 39ms，文本全对），而 adaptive
        // 链族对合成点阵零覆盖（全参数空间扫描全灭）。此前该层排在级联
        // 最尾（m2/aux/f 之后）——慢层 miss 帧 1~3s + 软时限逐层扣减，
        // dg 大部分帧根本跑不到，点阵码（尤其负片极性 flash_dark 型）
        // 实际只剩 cpp 碰运气。前移后 DPM 模式每帧必跑（40~150ms），
        // 点阵码获得稳定 Java 命中层；QR/普通码由 cpp1 首帧直出短路，
        // 不会白跑 dg（dpmMode 限定）
        int pitchEst = dpmMode ? estimatePitch(gray, width, height) : -1;
        // 暗底判定（直方图中值 O(N)，~2ms）：统一驱动三处——自动补光判定、
        // m2 暗底限流、小点距分支链族/探针跳过
        boolean dark = dpmMode && medianGray(gray) < 128;
        if (dpmMode) {
            handleAutoTorch(dark, android.os.SystemClock.elapsedRealtime());
            pushPitch(pitchEst);
            int mp = medianPitch();
            if (mp > 0) {
                pitchEst = mp;
            }
            ScanFileLog.log("pitchEst=" + pitchEst);
            handleAutoZoom(pitchEst, android.os.SystemClock.elapsedRealtime());
            // 小点距帧（1~11）此层跳过：全分辨率点径仅 1~2px 质心丢 ~80%
            // （真机 pBn45），由小点距分支的放大图路径专跑；点距未知（-1）
            // 保守直跑保底
            if (pitchEst <= 0 || pitchEst > 11) {
                Result dg = decodeDotGridLayer(gray, width, height, pitchEst);
                if (dg != null) {
                    return confirmCameraResult(dg, "dg");
                }
            }
        }

        // ===== 小码 cpp 探针层（cppSm，全格式 reader）【前移到主链之前】=====
        // 关键教训（真机 15:20 日志 cpp2x 每帧 miss 根因复盘）：
        // 1) DPM 模式 cppReader 是 DM-only（喷砂 QR 误读史），QR 对 cpp 不可见
        //    ——必须用全格式专用 reader；
        // 2) cpp 的 LOCAL_AVERAGE 局部均值窗口 ≈ 图像短边/8：全帧 150px、
        //    ×2 放大图 164px，小 QR（100~200px）整码落在 1~2 个窗口内，
        //    局部对比度被均值抹平——放大并不能修复均值窗口吞模块问题，
        //    真正有效的是紧裁剪（decodeRoi 的 0.3×3 档窗口仅 ~40px）与
        //    全格式 reader 本身；
        // 本层：原始 ROI（cppReaderQr）每帧探测 + 二值去噪探针（每帧）+ ×2
        // 变体（隔帧）覆盖偏小码；ROI 内不裁剪、不依赖码居中。QR 非 DM 走
        // 跨帧 2 次确认（防喷砂伪造 QR 稳定误读直出）。
        // 【前移依据】16:49 真机日志：本层 40~190ms 即可命中小码，但排在
        // 主链（miss 帧 2~3s）之后实际每 2~3 秒才跑一次，小码确认被拖到
        // 1.5~6s。移到级联最前：每帧开头即探小码，命中直接短路整个级联
        if ((long) width * height <= 1_300_000L) {
            long tSm = android.os.SystemClock.elapsedRealtime();
            Result smallHit = cppDecode(grayToBitmap(gray, width, height), cppReaderQr);
            if (smallHit == null) {
                // BOOL_CAST 二值透传探针（每帧）：Java 固定 25px 窗口自适应阈值
                // 保小码模块存活 + 连通域面积去噪清铝面纹理噪斑（开运算会连
                // 细条码条/小模块一起抹掉，不可用——despeckle 只清 <8px 孤立
                // 噪斑）+ cpp 检测器定位解码（tryInvert 覆盖暗底亮码极性）
                byte[] binProbe = gray.clone();
                ImagePreprocessor.adaptiveThreshold(binProbe, width, height,
                        ImagePreprocessor.ADAPTIVE_WINDOW, ImagePreprocessor.ADAPTIVE_C);
                ImagePreprocessor.despeckle(binProbe, width, height, 8);
                smallHit = cppDecode(grayToBitmap(binProbe, width, height), cppReaderQrBool);
            }
            if (smallHit == null && frameCounter % 2 == 0) {
                Object[] up2x = ImagePreprocessor.cropAndScale(gray, width, height, 0.75f, 0.75f, 2);
                smallHit = cppDecode(grayToBitmap((byte[]) up2x[0], (int) up2x[1], (int) up2x[2]), cppReaderQr);
            }
            if (smallHit == null && frameCounter % 2 == 1) {
                // ×2 二值探针（隔帧）：中心 1/2 裁剪 ×2 放大后二值化——窗口随
                // 倍率扩到 50px 保持相对覆盖，专攻全分辨率下模块 ≤2px 的极小码
                Object[] up2x = ImagePreprocessor.cropAndScale(gray, width, height, 0.5f, 0.5f, 2);
                byte[] binUp = (byte[]) up2x[0];
                int upW = (int) up2x[1], upH = (int) up2x[2];
                ImagePreprocessor.adaptiveThreshold(binUp, upW, upH, 50, ImagePreprocessor.ADAPTIVE_C);
                ImagePreprocessor.despeckle(binUp, upW, upH, 16);
                smallHit = cppDecode(grayToBitmap(binUp, upW, upH), cppReaderQrBool);
                if (smallHit == null) {
                    // 反色变体（暗底亮码）
                    ImagePreprocessor.invert(binUp);
                    smallHit = cppDecode(grayToBitmap(binUp, upW, upH), cppReaderQrBool);
                }
            }
            if (smallHit == null && frameCounter % 4 == 2) {
                // CLAHE 反光变体（每 4 帧）：金属管曲面高光把局部对比度吃掉，
                // cpp 检测器全帧/ROI 探针均定位失败（真机金属管 DM cpp1/g1/cppSm
                // 长期 miss 根因之一）；分块均衡化保留局部对比度、压缩高光平坦区
                // 后再交全格式 reader 探测（~30ms/帧分摊，命中即免进 1~2s 慢级联）
                byte[] cl = gray.clone();
                ImagePreprocessor.clahe(cl, width, height);
                smallHit = cppDecode(grayToBitmap(cl, width, height), cppReaderQr);
            }
            long tSmDone = android.os.SystemClock.elapsedRealtime();
            ScanFileLog.log("cppSm " + (tSmDone - tSm) + "ms hit=" + (smallHit != null));
            if (smallHit != null) {
                return confirmCameraResult(smallHit, "cppSm");
            }
        }

        // pending 优先：已有待确认文本时跳过探索性慢层（m2/f），后续帧只靠
        // 快层（cpp1/g1/cppSm/记忆尺度/upscale）凑确认——真机日志：确认间隔
        // 1.6~3.5s 全耗在 miss 帧 m2 全管线上；确认加速即出码加速。
        // 多码场景代价：pending 期间（TTL 2s）另一码的探索暂停，可接受
        boolean confirming = !dmHitCounts.isEmpty();
        byte[] workImg = small;
        int workW = sw, workH = sh;
        // 主尺度 /2 条件化（decodeYuv 传入 /2 图）：小码保细节且点距落入 (win,C)
        // 参数族工作区间，全分辨率 dot chains 实测只产出乱码误读。
        // 真机日志：m2 对 DotPeen 点阵码几乎从不命中（命中全来自 cppSm/记忆
        // 尺度/cpp1）却每帧吃 1~3.5s（有效帧率 0.3~0.8fps 的元凶）——
        // pitchEst>22 时 /2 后点距 >11px 明确出甜蜜区带，m2 注定 miss，跳过
        // （点距未知 pitchEst=-1 时保守照跑，防误判漏码）
        // 小点距帧（pitchEst 3~7）m2 限流为每 3 帧一次：flash_dark 实测该
        // 场景 m2 单帧吃 5s+（6354ms 帧的构成），10 分钟零 confirmed 贡献
        // （命中全来自 u2/cppSm/dg）——全分辨率 dot chains 在点距 4~7px 的
        // 帧上参数族不工作，只留低频保底防漏
        boolean m2Throttle = pitchEst >= 3 && pitchEst <= 7 && frameCounter % 3 != 0;
        // 暗底帧（负片极性场景，如 flash_dark 黑环氧压痕亮点码）m2 限流为每 4 帧
        // 一次：adaptive"暗=前景"+fillHoles"白=背景"语义对负片系统性失效（10
        // 分钟 flash_dark 会话 m2 零 confirmed，命中全来自 dg/cppSm），每帧 ~0.6s
        // 全跑是纯浪费；留 1/4 低频保底防暗光正片码漏扫（dark 复用级联头部判定）
        boolean m2DarkThrottle = dark && frameCounter % 4 != 0;
        if (!confirming && (pitchEst < 0 || pitchEst <= 22) && !m2Throttle && !m2DarkThrottle) {
            ScanFileLog.log("main=/" + factor + " " + workW + "x" + workH
                    + " roiPhase=" + (frameCounter % 3));
            result = decodeGrayDpm(workImg, workW, workH, true, frameCounter % 3,
                    null, tCascade + 700L);
            if (result != null) {
                return confirmCameraResult(result, "m" + factor);
            }
        } else {
            ScanFileLog.log("main=/" + factor + " skip confirming=" + confirming
                    + " pitchEst=" + pitchEst);
        }

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
            // 暗底帧跳过链族+探针（负片极性实证零贡献，省 ~400ms/帧直奔
            // dg 放大图）；每 4 帧保底一次防暗光正片小码漏扫（cppSm 每帧
            // 仍在跑，普通小码不受影响）
            result = null;
            if (!dark || frameCounter % 4 == 0) {
                result = decodeDotChainsParallel(upImg, upW, upH);
                if (result == null
                        && android.os.SystemClock.elapsedRealtime() - tCascade < 800L) {
                    result = cppDotProbe(upImg, upW, upH);
                }
            }
            if (result != null) {
                return confirmCameraResult(result, "u" + scale);
            }
            // DotPeen 点阵网格直解兜底（点式 L 定位边场景：zxing 定位器无法定位，
            // 但网格采样直解内核可解——平板暗底点阵图实测根因）。
            // 先放大图：小点距帧（4~8px）全分辨率点径仅 1~2px，质心提取丢
            // ~80%（真机 pBn45 vs 码 ~250 点）；×scale 放大图点径翻倍质心
            // 召回完整。放大图 miss 再跑原图保底（crop 比率收缩截碎码时兜底）
            Result dg = decodeDotGridLayer(upImg, upW, upH,
                    (int) Math.round(pitchEst * scale), false);
            // 原图保底限流为每 2 帧一次：小点距帧全分辨率点径 1~2px，质心
            // 召回 ~20%（pBn51 vs ~250 点）几乎必 miss，放大图 dg 已覆盖
            if (dg == null && frameCounter % 2 == 1) {
                dg = decodeDotGridLayer(gray, width, height, pitchEst);
            }
            if (dg != null) {
                return confirmCameraResult(dg, "dg");
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
            // /3 档：合成图 BYD 长文本（19 模块码）全参数扫描唯一成功档
            // （/2 主尺度与 /4~10 全败，仅 /3 + eq + win91 命中），帧内点距
            // 12~15px 带的专用补档
            int[] auxScales = {3, 4, 5, 6, 8, 10};
            aux = auxScales[frameCounter % auxScales.length];
        }
        ScanFileLog.log("aux=/" + aux);
        if (aux != lastSuccessScale && width / aux >= 120 && height / aux >= 120
                && android.os.SystemClock.elapsedRealtime() - tCascade < 800L) {
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
        // 每 3 帧跑一次，其余帧跳过（省下的时间让有效帧率翻倍）；
        // pending 优先：确认中跳过（该层确认门槛还需 +1，凑确认效率低于快层）
        if (!confirming && frameCounter % 3 == 0
                && android.os.SystemClock.elapsedRealtime() - tCascade < 600L) {
            Result f = confirmCameraResult(decodeFullResRoi(gray, width, height), "f");
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /**
     * DotPeen 点阵网格直解层（DotGridDecoder）：像素预算控制
     * （>800px 侧先 /2 降采样，点距落入 3.5~60px 搜索区间即可）。
     * <p>
     * allowDownscale：ROI 原图调用（主 dg 层/小点距保底）true；放大图调用
     * （小点距分支 upscale 后）false——放大图已按目标点距定尺，再降采样
     * 会抵消放大意图，恒直跑。
     */
    private Result decodeDotGridLayer(byte[] gray, int width, int height, int pitchEst) {
        return decodeDotGridLayer(gray, width, height, pitchEst, true);
    }

    private Result decodeDotGridLayer(byte[] gray, int width, int height, int pitchEst,
                                      boolean allowDownscale) {
        // 点距感知降采样：pitch>22 且长边>800 时 /2（/2 后点距仍 >11 在搜索
        // 区间内且质心更快更稳）。原长边门槛 1600 在 ROI 帧（972~1134 长边）
        // 永不满足——大点距帧全分辨率跑完 miss 再触发 decode() 内部 /2 重试，
        // 等于双倍全图 BFS（每帧 ~1.4s 的大头）。pitchEst 12~22 一律直跑：
        // /2 会把点径压半（质心丢 27~80%，真机 pBn45/pBn183 实证）；
        // pitchEst≤11 的小点距帧由小点距分支的放大图路径覆盖（内部另有
        // 失败后 /2 重试兜底）
        int factor = (allowDownscale && pitchEst > 22 && Math.max(width, height) > 800) ? 2 : 1;
        byte[] img = factor == 1 ? gray : ImagePreprocessor.downscale(gray, width, height, factor);
        Result r = DotGridDecoder.decode(img, width / factor, height / factor);
        if (r != null) {
            ScanFileLog.log("dotGrid hit: " + r.getText());
        } else {
            // miss 时用 dg 质心法 pitch 回填平滑缓冲（比 run 统计的
            // estimatePitch 可靠——锈蚀背景上后者乱跳 8~74）
            if (DotGridDecoder.lastGridPitch > 3.5
                    && DotGridDecoder.lastGridPitch < 60) {
                pushPitch((int) Math.round(DotGridDecoder.lastGridPitch));
            }
            if ((frameCounter & 3) == 0) {
                // miss 诊断（每 4 帧），暴露卡死阶段：质心数/点距/候选尺寸/对比度
                ScanFileLog.log("dotGrid miss diag: " + DotGridDecoder.lastDiag);
            }
        }
        return r;
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
    private final zxingcpp.BarcodeReader cppReaderQr;
    private final zxingcpp.BarcodeReader cppReaderQrBool;

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
     * cpp 全帧解码 + 取景框位置过滤：cpp1/g1 按设计跑全帧（特写码大于方框时
     * 裁 ROI 会裁掉定位边），代价是框外码也会被识别（平板实测反馈：旁边的
     * 码/标签不在方块区域内也出码）。本方法按码尺寸分级过滤：
     * ① 码跨框（特写大码，任一边长 > 框对应边长）：中心点在框内即有效瞄准；
     * ② 码可完整落入框（常规瞄准）：要求 ≥90% 面积在框内——大部分在框外、
     *    仅中心点贴框的码不再输出（原中心点过滤的漏网场景）。
     *
     * @param roi 取景框 {x0,y0,w,h}（旋转矫正后坐标，已含外扩容差），null=不过滤
     */
    private Result cppDecodeInRoi(Bitmap bitmap, zxingcpp.BarcodeReader reader, int[] roi, String tag) {
        try {
            java.util.List<zxingcpp.BarcodeReader.Result> results =
                    reader.read(bitmap, new android.graphics.Rect(), 0);
            if (results != null && !results.isEmpty()) {
                int rejected = 0;
                for (zxingcpp.BarcodeReader.Result rr : results) {
                    if (isPositionInRoi(rr.getPosition(), roi)) {
                        return toZxingResult(rr);
                    }
                    rejected++;
                }
                if (rejected > 0) {
                    // 框外码被过滤的证据（验证有效区域处理是否生效）
                    ScanFileLog.log("cpp[" + tag + "] roi-filter rejected " + rejected + " code(s)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cpp decode failed, fallback to java", t);
        }
        return null;
    }

    /**
     * 码位置是否有效瞄准取景框（按码尺寸分级）：
     * ① 码跨框（特写，任一边长 > 框边长）：中心点判定（外扩 5% 容差）——
     *    特写时码大于方框，四角必然出框，面积判定会误杀；
     * ② 常规码（两维均 ≤ 框）：码包围盒与框交集 ≥90% 码面积才接受——
     *    仅中心点在框内、大部分面积在框外的码被拒绝（平板框外码误识别根因）。
     */
    private static boolean isPositionInRoi(zxingcpp.BarcodeReader.Position pos, int[] roi) {
        if (pos == null || roi == null || roi.length != 4) {
            return true; // 无位置信息或无取景框：不过滤
        }
        android.graphics.Point tl = pos.getTopLeft();
        android.graphics.Point tr = pos.getTopRight();
        android.graphics.Point br = pos.getBottomRight();
        android.graphics.Point bl = pos.getBottomLeft();
        if (tl == null || tr == null || br == null || bl == null) {
            return true;
        }
        float bx0 = Math.min(Math.min(tl.x, tr.x), Math.min(bl.x, br.x));
        float bx1 = Math.max(Math.max(tl.x, tr.x), Math.max(bl.x, br.x));
        float by0 = Math.min(Math.min(tl.y, tr.y), Math.min(bl.y, br.y));
        float by1 = Math.max(Math.max(tl.y, tr.y), Math.max(bl.y, br.y));
        float bw = bx1 - bx0;
        float bh = by1 - by0;
        float cx = (bx0 + bx1) / 2f;
        float cy = (by0 + by1) / 2f;
        int mx = Math.max(1, roi[2] / 20);
        int my = Math.max(1, roi[3] / 20);
        if (bw > roi[2] || bh > roi[3]) {
            // ① 特写大码（码跨框）：中心在框内（含 5% 容差）即有效瞄准
            return cx >= roi[0] - mx && cx <= roi[0] + roi[2] + mx
                    && cy >= roi[1] - my && cy <= roi[1] + roi[3] + my;
        }
        // ② 常规码：与框交集面积 ≥90% 码面积（框外占比 >10% 拒绝）
        float ix0 = Math.max(bx0, roi[0]);
        float iy0 = Math.max(by0, roi[1]);
        float ix1 = Math.min(bx1, roi[0] + roi[2]);
        float iy1 = Math.min(by1, roi[1] + roi[3]);
        if (ix1 <= ix0 || iy1 <= iy0) {
            return false; // 码与框无交集（完全在框外）
        }
        float interArea = (ix1 - ix0) * (iy1 - iy0);
        return interArea >= 0.9f * Math.max(1f, bw * bh);
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
        // DotPeen 点阵网格直解兜底（点式 L 定位边场景）：
        // 命中结果参与多数派投票，与全变体命中互证
        Result dg = DotGridDecoder.decode(gray, w, h);
        if (dg != null) {
            hits.add(dg);
            ScanFileLog.log("album dotGrid hit: " + dg.getText());
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
        return decodeGrayDpm(gray, width, height, preDownscaled, roiPhase, null, Long.MAX_VALUE);
    }

    private Result decodeGrayDpm(byte[] gray, int width, int height, boolean preDownscaled, int roiPhase,
                                 java.util.List<Result> collectList) {
        return decodeGrayDpm(gray, width, height, preDownscaled, roiPhase, collectList, Long.MAX_VALUE);
    }

    /**
     * @param collectList 非 null = 收集模式（相册单次解码）：全部变体的命中都加入
     *                    列表并继续跑后续变体，不提前返回；调用方做多数派投票。
     *                    null = 首中即返回（相机帧，低延迟优先）
     * @param deadline    绝对时限（elapsedRealtime，相机帧传 tCascade+预算）：
     *                    超时跳过收尾阶段（③均衡变体块、ROI 放大档），
     *                    下帧补位；相册路径传 Long.MAX_VALUE 不限
     */
    private Result decodeGrayDpm(byte[] gray, int width, int height, boolean preDownscaled, int roiPhase,
                                 java.util.List<Result> collectList, long deadline) {
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
        if (roiPhase < 0 || ((frameCounter & 1) == 0
                && android.os.SystemClock.elapsedRealtime() < deadline)) {
            byte[] binRaw = enhanced.clone();
            ImagePreprocessor.adaptiveThreshold(binRaw, dw, dh);

            byte[] close1 = binRaw.clone();
            ImagePreprocessor.closeMorph(close1, dw, dh);
            r = acceptDpmJava(tryDecodeBinWithInvert(close1, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            byte[] close2 = binRaw.clone();
            ImagePreprocessor.closeMorph(close2, dw, dh, 2);
            r = acceptDpmJava(tryDecodeBinWithInvert(close2, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            byte[] filled = binRaw.clone();
            ImagePreprocessor.fillHoles(filled, dw, dh);
            r = acceptDpmJava(tryDecodeBinWithInvert(filled, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(filled, dw, dh, 2);
            r = acceptDpmJava(tryDecodeBinWithInvert(filled, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }

            // 离线合成图测试验证的最优链（变体 22/23 均成功）：
            // fillHoles（高光环点→实心盘，实心盘能扛住开运算）→ denoise（清背景散点）
            // → 闭运算（弥合点间隙）。顺序不可颠倒：先去噪会把环形圆点一并抹掉
            byte[] dotSolid = binRaw.clone();
            ImagePreprocessor.fillHoles(dotSolid, dw, dh);
            ImagePreprocessor.denoise(dotSolid, dw, dh);
            r = acceptDpmJava(tryDecodeBinWithInvert(dotSolid, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(dotSolid, dw, dh);
            r = acceptDpmJava(tryDecodeBinWithInvert(dotSolid, dw, dh));
            if (r != null) {
                if (collectList != null) collectList.add(r); else return r;
            }
            ImagePreprocessor.closeMorph(dotSolid, dw, dh);
            r = acceptDpmJava(tryDecodeBinWithInvert(dotSolid, dw, dh));
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
        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
            // 主链预算耗尽：跳过本帧 ROI 放大档（单档数百 ms），下帧轮转补位
            return null;
        }
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
        // cpp 引擎接力（首选）：小码（QR/DM）模块在全帧只有 3~4px，cpp 全帧定位
        // 失败（tryDownscale 只能向下找码，小码需要的是放大）；ROI 2x 放大后模块
        // 翻倍到 6~8px，cpp 命中率大幅提升。真机实测：小 QR 全帧 cpp1 每帧 miss，
        // 仅 /2 链族偶发命中，扫码延迟 5~32s——放大 ROI 交 cpp 是缺的最后一环
        Result rCpp = cppDecode(grayToBitmap(roi, roiW, roiH), cppReader);
        if (rCpp != null) {
            if (collectList != null) collectList.add(rCpp); else return rCpp;
        }

        Result r = acceptDpmJava(tryDecode(new GrayscaleSource(roi, roiW, roiH), true));
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        byte[] roiBin = roi.clone();
        // 放大 scale 倍后模块间距同步放大，窗口需按倍数扩大，
        // 否则局部均值跟随圆点自适应会把点"吃掉"
        ImagePreprocessor.adaptiveThreshold(roiBin, roiW, roiH,
                ImagePreprocessor.ADAPTIVE_WINDOW * scale, ImagePreprocessor.ADAPTIVE_C);
        // 二值图统一走 cpp BOOL_CAST 优先（"全 cpp 识别"），Java 兜底；
        // tryInvert/兜底内部已覆盖反色，无需独立反色站点
        r = acceptDpmJava(tryDecodeBinWithInvert(roiBin, roiW, roiH));
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }

        // 圆点 DM 兜底：填洞（高光坑底呈黑环+白心）→ 开运算去噪 → 闭运算弥合点间隙
        byte[] roiDot = roiBin.clone();
        ImagePreprocessor.fillHoles(roiDot, roiW, roiH);
        ImagePreprocessor.denoise(roiDot, roiW, roiH);
        ImagePreprocessor.closeMorph(roiDot, roiW, roiH, 2);
        r = acceptDpmJava(tryDecodeBinWithInvert(roiDot, roiW, roiH));
        if (r != null) {
            if (collectList != null) collectList.add(r); else return r;
        }
        return null;
    }

    /**
     * Java 引擎 DM 结果统一出口：过滤短数字误读（见
     * {@link #isSuspiciousShortNumericDm}），cpp 引擎结果不经此过滤。
     */
    private static Result acceptDpmJava(Result r) {
        return isSuspiciousShortNumericDm(r) ? null : r;
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
     * Java 引擎 DM 短数字误读过滤：DotPeen 噪声帧经点链/ROI 二值化后，
     * Java zxing 会把残余点阵系统性误读为 ≤8 位纯数字（真机日志实锤：
     * "21111112"/"27771211"/"16239928"/"10113363" 刷 pending；相册多数派
     * 甚至被 "17262279" 5 票压过真码 BYD476ZQC-...）。
     * 工业 DPM 码（零件号/批次号）远长于 8 位；仅过滤 DATA_MATRIX 格式
     * （不影响 EAN-8/ITF 等短数字一维码），cpp 引擎路径不受影响——
     * cpp 对这类噪声的误读率低，合法短数字码仍可经 cpp 层输出。
     */
    private static boolean isSuspiciousShortNumericDm(Result r) {
        if (r == null || r.getBarcodeFormat() != com.google.zxing.BarcodeFormat.DATA_MATRIX) {
            return false;
        }
        String t = r.getText();
        if (t == null || t.isEmpty() || t.length() > 8) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
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
        // {win, C, eq, inv}：小窗口链覆盖高对比/常规打标（真机主力）。
        // 曾按合成图标定加过 {91,10,eq} 大窗均衡化与 {21,5,inv} 反相小窗
        // 变体——后发现离线工具 toGray 的 getRGB 对灰度 PNG 做 gamma 展开
        // （暗部 45→117，avgDiff=70），全部"可解"结论建立在与真机 YUV 语义
        // 脱节的污染数据上（Raster 直读原始像素后全灭，真机日志该层也零
        // 命中），已撤。点阵码（含负片极性）主力命中层是前移的 dg
        // （DotGridDecoder 网格直解）。链间竞速 + 多数派裁决防单链误读
        int[][] params = {{61, 4, 0, 0}, {61, 6, 0, 0},
                {ImagePreprocessor.ADAPTIVE_WINDOW, 4, 0, 0}, {ImagePreprocessor.ADAPTIVE_WINDOW, 6, 0, 0}};
        java.util.List<java.util.concurrent.Callable<Result>> tasks = new java.util.ArrayList<>();
        for (int[] wc : params) {
            final int win = wc[0];
            final int c = wc[1];
            final boolean eq = wc[2] == 1;
            final boolean inv = wc[3] == 1;
            if (win < Math.min(width, height) - 2) {
                tasks.add(() -> decodeDotChain(gray, width, height, win, c, eq, inv));
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
                if (r != null && r.getText() != null && !r.getText().isEmpty()
                        && !isSuspiciousShortNumericDm(r)) {
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
    private Result decodeDotChain(byte[] gray, int width, int height, int win, int c, boolean eq, boolean inv) {
        byte[] bin = gray.clone();
        if (inv) {
            // 负片极性变体：暗底亮点码（如闪存黑环氧压痕反光——flash_dark 实测
            // 整条链族对负片系统性失效：adaptive"暗=前景" + fillHoles"白=背景"
            // 的语义会把黑底误当码点间隙处理，链后反相也救不回（结构已破坏），
            // 唯一可行路径是链前反相（合成图消融实验实锤）
            ImagePreprocessor.invert(bin);
        }
        if (eq) {
            // 均衡化变体：合成图/低对比实拍的全参数扫描实锤，部分点距带只有
            // "eq + 大窗口"组合可解（/3 尺度 win=91~101 C=10~12，/2 与小窗口
            // 全败）。均衡化在链内 clone 上做，不影响其他并行链的输入
            ImagePreprocessor.equalizeHistogram(bin);
        }
        ImagePreprocessor.adaptiveThreshold(bin, width, height, win, c);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.fillHoles(bin, width, height);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.denoise(bin, width, height);
        if (Thread.currentThread().isInterrupted()) return null;
        ImagePreprocessor.closeMorph(bin, width, height);
        // cpp 优先（"全 cpp 识别"）：二值图 BOOL_CAST 透传，cpp 检测器/解码器
        // 强于 Java（且规避 Java 链的短数字误读）。zxing-cpp read() 线程安全，
        // 可在并行链中直接调用；Java 作为链内兜底
        Result r = cppDecode(grayToBitmap(bin, width, height), cppReaderQrBool);
        if (r != null) return r;
        MultiFormatReader local = newLocalReader();
        r = tryDecodeWith(local, bin, width, height);
        if (r != null) return r;
        ImagePreprocessor.closeMorph(bin, width, height);
        r = cppDecode(grayToBitmap(bin, width, height), cppReaderQrBool);
        if (r != null) return r;
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
    /**
     * 二值图统一解码出口（"全 cpp 识别"主体）：
     * 1) cpp BOOL_CAST 透传优先——cpp 检测器/解码器强于 Java，tryInvert 已
     *    覆盖正反色，无需手动反色重试；
     * 2) Java zxing 兜底（GlobalHistogram/Hybrid × 正反色四路）——保留点式
     *    DM 场景的既有容错，cpp 漏检时补位。
     */
    private Result tryDecodeBinWithInvert(byte[] bin, int width, int height) {
        Result r = cppDecode(grayToBitmap(bin, width, height), cppReaderQrBool);
        if (r != null) {
            return r;
        }
        GrayscaleSource source = new GrayscaleSource(bin, width, height);
        r = tryDecode(source, false);
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
