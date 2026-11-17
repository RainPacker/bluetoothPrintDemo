package com.weifu.scanplugin.camera;

import android.graphics.Bitmap;

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
        // 按相机旋转角矫正：未矫正时横屏 ROTATION_270 / 竖屏下二维码在帧中是旋转的，快速路径必失败
        if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            gray = ImagePreprocessor.rotate(gray, width, height, rotationDegrees);
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                int t = width;
                width = height;
                height = t;
            }
        }

        // 快速路径（微信策略：中心 ROI 优先）——整帧二值化分块过粗时取景框内的小码必漏检，
        // 中心裁剪解码既快又稳；随后全帧兜底
        GrayscaleSource source = new GrayscaleSource(gray, width, height);
        Result result = tryDecode(cropCenter(source, 0.6f), true);
        if (result != null) {
            return result;
        }
        result = tryDecode(source, true);
        if (result != null) {
            return result;
        }
        result = tryDecode(cropCenter(source, 0.6f), false);
        if (result != null) {
            return result;
        }
        result = tryDecode(source, false);
        if (result != null) {
            return result;
        }

        if (!dpmMode) {
            return null;
        }

        // DPM 增强路径
        return decodeGrayDpm(gray, width, height);
    }

    /**
     * 解码 Bitmap（相册选图 / F-12）
     */
    Result decodeBitmap(Bitmap bitmap) {
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
        GrayscaleSource source = new GrayscaleSource(gray, w, h);
        Result result = tryDecode(source, false);
        if (result != null) {
            return result;
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
        for (int y = 0; y < height; y++) {
            int src = y * stride;
            int dst = y * width;
            System.arraycopy(yPlane, src, out, dst, width);
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
