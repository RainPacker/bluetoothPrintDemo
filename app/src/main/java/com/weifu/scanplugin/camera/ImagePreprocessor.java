package com.weifu.scanplugin.camera;

/**
 * 图像预处理器（F-25 ~ F-28）
 * <p>
 * DPM（Direct Part Marking，零件直接刻印码）增强核心：
 * 金属刻印的 DM 码普遍存在低对比度、明暗不均、反光、点阵网格等问题，
 * zxing 默认的全局阈值二值化对此类图像几乎无识别能力。
 * <p>
 * 本类提供以下处理（均基于灰度 byte[]，每像素 0~255）：
 * 1. 自适应二值化：积分图求局部均值，O(1)/像素，解决明暗不均（F-25）；
 * 2. 反色：适配暗底亮码（激光刻印白字）（F-26）；
 * 3. 旋转：0/90/180/270 四方向重试（F-27）；
 * 4. ROI 裁剪 + 2 倍最近邻放大：提升小尺寸 DM 码模块分辨率（F-28）。
 */
final class ImagePreprocessor {

    private ImagePreprocessor() {
    }

    /** 自适应二值化窗口（奇数） */
    static final int ADAPTIVE_WINDOW = 25;
    /** 均值偏移阈值 C：像素低于局部均值-C 判为黑 */
    static final int ADAPTIVE_C = 12;

    /**
     * 自适应二值化（局部均值法，积分图加速）
     *
     * @param gray   灰度图（将被原地修改）
     * @param width  宽
     * @param height 高
     */
    static void adaptiveThreshold(byte[] gray, int width, int height) {
        // 积分图 (width+1) x (height+1)，用 long 防溢出
        long[] integral = new long[(width + 1) * (height + 1)];
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            for (int x = 0; x < width; x++) {
                rowSum += (gray[y * width + x] & 0xFF);
                integral[(y + 1) * (width + 1) + (x + 1)] =
                        integral[y * (width + 1) + (x + 1)] + rowSum;
            }
        }
        int half = ADAPTIVE_WINDOW / 2;
        for (int y = 0; y < height; y++) {
            int y1 = Math.max(0, y - half);
            int y2 = Math.min(height - 1, y + half);
            for (int x = 0; x < width; x++) {
                int x1 = Math.max(0, x - half);
                int x2 = Math.min(width - 1, x + half);
                int area = (y2 - y1 + 1) * (x2 - x1 + 1);
                long sum = integral[(y2 + 1) * (width + 1) + (x2 + 1)]
                        - integral[y1 * (width + 1) + (x2 + 1)]
                        - integral[(y2 + 1) * (width + 1) + x1]
                        + integral[y1 * (width + 1) + x1];
                int mean = (int) (sum / area);
                int pixel = gray[y * width + x] & 0xFF;
                gray[y * width + x] = (byte) (pixel < mean - ADAPTIVE_C ? 0 : 255);
            }
        }
    }

    /**
     * 反色（F-26）
     */
    static void invert(byte[] gray) {
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (255 - (gray[i] & 0xFF));
        }
    }

    /**
     * 旋转灰度图（F-27）
     *
     * @param degrees 90/180/270，其他值原样返回
     * @return 旋转后的新数组（宽高互换）
     */
    static byte[] rotate(byte[] gray, int width, int height, int degrees) {
        if (degrees == 0) {
            return gray;
        }
        if (degrees == 180) {
            byte[] out = new byte[gray.length];
            for (int i = 0; i < gray.length; i++) {
                out[i] = gray[gray.length - 1 - i];
            }
            return out;
        }
        // 90 或 270
        int newWidth = height;
        int newHeight = width;
        byte[] out = new byte[gray.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int src = y * width + x;
                int dst;
                if (degrees == 90) {
                    // 顺时针 90°: (x,y) -> (newHeight-1-y, x)
                    dst = x * newWidth + (newHeight - 1 - y);
                } else {
                    // 逆时针 90°（顺时针 270°）: (x,y) -> (y, newWidth-1-x)
                    dst = (newWidth - 1 - x) * newHeight + y;
                }
                out[dst] = gray[src];
            }
        }
        return out;
    }

    /**
     * 中心 ROI 裁剪 + 放大（F-28）
     *
     * @param ratioX  ROI 宽占原图比例 (0,1]
     * @param ratioY  ROI 高占原图比例 (0,1]
     * @param scale   放大倍数（>=1）
     * @return {bytes, width, height}
     */
    static Object[] cropAndScale(byte[] gray, int width, int height,
                                 float ratioX, float ratioY, int scale) {
        int cropW = Math.max(1, (int) (width * ratioX));
        int cropH = Math.max(1, (int) (height * ratioY));
        int offX = (width - cropW) / 2;
        int offY = (height - cropH) / 2;

        int outW = cropW * scale;
        int outH = cropH * scale;
        byte[] out = new byte[outW * outH];
        for (int y = 0; y < outH; y++) {
            int srcY = offY + y / scale;
            for (int x = 0; x < outW; x++) {
                int srcX = offX + x / scale;
                out[y * outW + x] = gray[srcY * width + srcX];
            }
        }
        return new Object[]{out, outW, outH};
    }

    /**
     * 简单对比度拉伸（灰度直方图 2% 分位裁剪后线性拉伸）
     * 用于低对比度刻印码的快速增强。
     */
    static void stretchContrast(byte[] gray) {
        int[] hist = new int[256];
        for (byte b : gray) {
            hist[b & 0xFF]++;
        }
        int total = gray.length;
        int lowCut = (int) (total * 0.02);
        int highCut = total - lowCut;
        int low = 0, high = 255, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= lowCut) {
                low = i;
                break;
            }
        }
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= lowCut) {
                high = i;
                break;
            }
        }
        if (high - low < 10) {
            return; // 近乎单色，无拉伸意义
        }
        int range = high - low;
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 0xFF;
            int nv = (v - low) * 255 / range;
            gray[i] = (byte) Math.max(0, Math.min(255, nv));
        }
    }
}
