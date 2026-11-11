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

        // 快速路径：原始灰度 + 双二值化器
        Result result = tryDecode(new GrayscaleSource(gray, width, height), false);
        if (result != null) {
            return result;
        }
        result = tryDecode(new GrayscaleSource(gray, width, height), true);
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
     * DPM 增强管线（F-25 ~ F-28）
     */
    private Result decodeGrayDpm(byte[] gray, int width, int height) {
        // 1. 对比度拉伸 + 自适应二值化
        byte[] enhanced = gray.clone();
        ImagePreprocessor.stretchContrast(enhanced);
        Result r = tryDecode(new GrayscaleSource(enhanced, width, height), true);
        if (r != null) return r;

        byte[] bin = enhanced.clone();
        ImagePreprocessor.adaptiveThreshold(bin, width, height);
        r = tryDecode(new GrayscaleSource(bin, width, height), false);
        if (r != null) return r;

        // 2. 反色（暗底亮码）
        byte[] inv = bin.clone();
        ImagePreprocessor.invert(inv);
        r = tryDecode(new GrayscaleSource(inv, width, height), false);
        if (r != null) return r;

        // 3. 四方向旋转（二值化图 + 原灰度图）
        int[] degreesArr = {90, 180, 270};
        for (int deg : degreesArr) {
            byte[] rotatedBin = ImagePreprocessor.rotate(bin, width, height, deg);
            int rw = deg == 180 ? width : height;
            int rh = deg == 180 ? height : width;
            r = tryDecode(new GrayscaleSource(rotatedBin, rw, rh), false);
            if (r != null) return r;
        }

        // 4. 反色灰度原图（未二值化）+ 旋转
        byte[] invGray = enhanced.clone();
        ImagePreprocessor.invert(invGray);
        for (int deg : degreesArr) {
            byte[] rotated = ImagePreprocessor.rotate(invGray, width, height, deg);
            int rw = deg == 180 ? width : height;
            int rh = deg == 180 ? height : width;
            r = tryDecode(new GrayscaleSource(rotated, rw, rh), true);
            if (r != null) return r;
        }

        // 5. ROI 裁剪放大（小尺寸 DM 码）：中心 70% 区域放大 2 倍
        Object[] cropped = ImagePreprocessor.cropAndScale(enhanced, width, height, 0.7f, 0.7f, 2);
        @SuppressWarnings("unchecked")
        byte[] roi = (byte[]) cropped[0];
        int roiW = (int) cropped[1];
        int roiH = (int) cropped[2];
        r = tryDecode(new GrayscaleSource(roi, roiW, roiH), true);
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
