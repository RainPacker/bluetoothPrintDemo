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

    DecodeEngine(boolean dpmMode, Set<String> formats) {
        this.dpmMode = dpmMode;
        this.formats = formats;
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

        // ===== 第 1 层：zxing-cpp 主引擎（降采样图，大码快扫）=====
        // C++ 检测器/解码器对模糊、低对比、倾斜一维码、DM 的识别率数倍于 Java 版；
        // tryRotate/tryInvert 内置全方向全极性重试，无需 Java 侧级联
        int factor = width > 640 ? 3 : 1;
        byte[] small = ImagePreprocessor.downscale(gray, width, height, factor);
        int sw = width / factor;
        int sh = height / factor;
        Result result = cppDecode(grayToBitmap(small, sw, sh));
        if (result != null) {
            return result;
        }

        // ===== 第 2 层：zxing-cpp 全分辨率（小尺寸码保细节）=====
        if (factor > 1) {
            result = cppDecode(grayToBitmap(gray, width, height));
            if (result != null) {
                return result;
            }
        }

        if (!dpmMode) {
            return null;
        }

        // ===== 第 3 层：Java DPM 兜底管线（均衡化/锐化/去噪等特殊预处理）=====
        result = decodeGrayDpm(small, sw, sh);
        if (result != null) {
            return result;
        }
        return decodeFullResRoi(gray, width, height);
    }

    /**
     * zxing-cpp 主引擎（2.3.0 API）：实例类，read(Bitmap) 同步解码。
     * Options 全开：tryHarder/tryRotate/tryInvert + tryDownscale（C++ 内置多尺度）+
     * tryDenoise（C++ 内置去噪，DM 刻印码利好）。仅解码线程调用，实例复用安全。
     */
    private final zxingcpp.BarcodeReader cppReader;

    {
        zxingcpp.BarcodeReader.Options opts = new zxingcpp.BarcodeReader.Options();
        opts.setTryHarder(true);
        opts.setTryRotate(true);
        opts.setTryInvert(true);
        opts.setTryDownscale(true);
        cppReader = new zxingcpp.BarcodeReader(opts);
    }

    private Result cppDecode(Bitmap bitmap) {
        try {
            java.util.List<zxingcpp.BarcodeReader.Result> results =
                    cppReader.read(bitmap, new android.graphics.Rect(), 0);
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
     * 乱码修复：DM 工业码（GS1/二进制内容）默认 HRI 文本模式会把 FNC1/GS 分隔符(0x1D)
     * 等控制字符渲染为空格/不可见字符，中文字符集错配也会乱码。
     * 按内容类型分流：纯文本用 HRI；其余从原始字节智能解码（UTF-8→GBK→Latin-1），
     * GS 分隔符转为可读的 '|'。
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
        zxingcpp.BarcodeReader.ContentType type = r.getContentType();
        boolean isPlain = type == zxingcpp.BarcodeReader.ContentType.TEXT;
        String hri = r.getText();
        if (isPlain && hri != null && !hasControlChars(hri)) {
            return hri;
        }
        // 非纯文本（GS1/BINARY/MIXED）：从原始字节智能解码
        byte[] bytes = r.getBytes();
        if (bytes == null || bytes.length == 0) {
            return hri == null ? "" : hri;
        }
        String decoded = smartDecodeBytes(bytes);
        // GS 分隔符（0x1D，GS1 元素间隔）转为可读 '|'，其余保留
        return decoded.replace("\u001D", "|");
    }

    /**
     * 检测 HRI 文本是否含未渲染的控制字符（空格/方块乱码的直接来源）
     */
    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    /**
     * 智能字节解码：UTF-8 严格校验 → GBK（中文工业标签）→ Latin-1 字节直通
     */
    private static String smartDecodeBytes(byte[] bytes) {
        try {
            String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!utf8.contains("\uFFFD")) {
                return utf8;
            }
        } catch (Exception ignored) {
        }
        try {
            String gbk = new String(bytes, "GBK");
            if (!gbk.contains("\uFFFD")) {
                return gbk;
            }
        } catch (Exception ignored) {
        }
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * 灰度 byte[] → ARGB Bitmap（zxing-cpp Bitmap 接口输入）
     */
    private static Bitmap grayToBitmap(byte[] gray, int width, int height) {
        int[] pixels = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 0xFF;
            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
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
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        return tryDecode(new GrayscaleSource(inv, cropW, cropH), false);
    }

    /**
     * 解码 Bitmap（相册选图 / F-12）
     */
    Result decodeBitmap(Bitmap bitmap) {
        // C++ 主引擎直读（彩色 Bitmap，识别率高于先转灰度）
        Result result = cppDecode(bitmap);
        if (result != null) {
            return result;
        }
        setHints();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
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
        return decodeGrayDpm(gray, w, h);
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
        // 全帧增强管线在 1/2 降采样图上执行：像素量降 4 倍 → 单帧耗时大幅下降 →
        // 单位时间可尝试更多帧，金属刻印码靠多帧概率累积的效率显著提升。
        // ROI 放大路径仍用原始分辨率，保证小尺寸码的模块细节不损失。
        int dw = Math.max(1, width / 2);
        int dh = Math.max(1, height / 2);
        byte[] small = ImagePreprocessor.downscale(gray, width, height, 2);

        // 1. 直方图均衡化（比线性对比度拉伸更适合非线性光照）
        byte[] enhanced = small.clone();
        ImagePreprocessor.equalizeHistogram(enhanced);
        Result r = tryDecode(new GrayscaleSource(enhanced, dw, dh), true);
        if (r != null) return r;

        // 2. 均衡化 + 锐化：模块边界增强
        byte[] sharpened = enhanced.clone();
        ImagePreprocessor.sharpen(sharpened, dw, dh);
        r = tryDecode(new GrayscaleSource(sharpened, dw, dh), true);
        if (r != null) return r;

        // 3. 自适应二值化 + 形态学开运算去噪
        byte[] bin = enhanced.clone();
        ImagePreprocessor.adaptiveThreshold(bin, dw, dh);
        ImagePreprocessor.denoise(bin, dw, dh);
        r = tryDecode(new GrayscaleSource(bin, dw, dh), false);
        if (r != null) return r;

        // 4. 反色（暗底亮码，激光刻印白字）
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        r = tryDecode(new GrayscaleSource(inv, dw, dh), false);
        if (r != null) return r;

        // 5. 四方向旋转（去噪二值图 + 锐化灰度图）
        int[] degreesArr = {90, 180, 270};
        for (int deg : degreesArr) {
            byte[] rotatedBin = ImagePreprocessor.rotate(bin, dw, dh, deg);
            int rw = deg == 180 ? dw : dh;
            int rh = deg == 180 ? dh : dw;
            r = tryDecode(new GrayscaleSource(rotatedBin, rw, rh), false);
            if (r != null) return r;
        }
        for (int deg : degreesArr) {
            byte[] rotated = ImagePreprocessor.rotate(sharpened, dw, dh, deg);
            int rw = deg == 180 ? dw : dh;
            int rh = deg == 180 ? dh : dw;
            r = tryDecode(new GrayscaleSource(rotated, rw, rh), true);
            if (r != null) return r;
        }

        // 6. 多级 ROI 放大（小尺寸 DM 码，原始分辨率）：中心 50% 2x → 30% 3x
        r = decodeRoi(gray, width, height, 0.5f, 2);
        if (r != null) return r;
        r = decodeRoi(gray, width, height, 0.3f, 3);
        if (r != null) return r;
        return decodeRoi(gray, width, height, 0.4f, 2);
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
        Object[] cropped = ImagePreprocessor.cropAndScale(gray, width, height, ratio, ratio, scale);
        @SuppressWarnings("unchecked")
        byte[] roi = (byte[]) cropped[0];
        int roiW = (int) cropped[1];
        int roiH = (int) cropped[2];
        Result r = tryDecode(new GrayscaleSource(roi, roiW, roiH), true);
        if (r != null) return r;

        byte[] roiBin = roi.clone();
        ImagePreprocessor.adaptiveThreshold(roiBin, roiW, roiH);
        r = tryDecode(new GrayscaleSource(roiBin, roiW, roiH), false);
        if (r != null) return r;

        byte[] roiInv = roiBin.clone();
        ImagePreprocessor.invert(roiInv);
        return tryDecode(new GrayscaleSource(roiInv, roiW, roiH), false);
    }

    private Result tryDecode(LuminanceSource source, boolean hybrid) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                    hybrid ? new HybridBinarizer(source) : new GlobalHistogramBinarizer(source));
            return reader.decodeWithState(bitmap);
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void setHints() {
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
        reader.setHints(hints);
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
