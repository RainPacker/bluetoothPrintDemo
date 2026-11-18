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
        // 90 或 270（行列主序，目标图行宽 = newWidth = height）
        int newWidth = height;
        int newHeight = width;
        byte[] out = new byte[gray.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int src = y * width + x;
                int dst;
                if (degrees == 90) {
                    // 顺时针 90°: (x,y) -> 新图 (x'=height-1-y 列, y'=x 行)
                    // dst = y' * newWidth + x' = x * newWidth + (height - 1 - y)
                    // 注意分母是 height（源图高=目标行数相关），原公式误用 newHeight
                    // 导致 dst 越界（真机竖屏 rotation=90/270 每帧 ArrayIndexOutOfBounds）
                    dst = x * newWidth + (height - 1 - y);
                } else {
                    // 逆时针 90°（顺时针 270°）: (x,y) -> 新图 (x'=y 列, y'=width-1-x 行)
                    // dst = y' * newWidth + x' = (width - 1 - x) * newWidth + y
                    dst = (width - 1 - x) * newWidth + y;
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
     * 均值降采样（2x2 盒滤波）：DPM 全帧管线提速用。
     * 相比最近邻，盒滤波保留局部亮度均值，降采样后二值化效果更稳定。
     *
     * @param factor 降采样倍数（>=1，1 时原样返回）
     */
    static byte[] downscale(byte[] gray, int width, int height, int factor) {
        if (factor <= 1) {
            return gray;
        }
        int outW = width / factor;
        int outH = height / factor;
        if (outW <= 0 || outH <= 0) {
            return gray;
        }
        byte[] out = new byte[outW * outH];
        for (int y = 0; y < outH; y++) {
            int sy = y * factor;
            for (int x = 0; x < outW; x++) {
                int sx = x * factor;
                int sum = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int base = (sy + dy) * width + sx;
                    for (int dx = 0; dx < factor; dx++) {
                        sum += gray[base + dx] & 0xFF;
                    }
                }
                out[y * outW + x] = (byte) (sum / (factor * factor));
            }
        }
        return out;
    }

    /**
     * 直方图均衡化（DPM 增强）：
     * 非线性重映射灰度分布，对金属反光、明暗渐变的刻印码增强效果
     * 显著优于线性对比度拉伸（stretchContrast）。
     */
    static void equalizeHistogram(byte[] gray) {
        int[] hist = new int[256];
        for (byte b : gray) {
            hist[b & 0xFF]++;
        }
        // 累积分布函数
        int[] cdf = new int[256];
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            cdf[i] = acc;
        }
        // 找到 CDF 最小值（均衡化标准公式分母项）
        int cdfMin = 0;
        for (int i = 0; i < 256; i++) {
            if (cdf[i] > 0) {
                cdfMin = cdf[i];
                break;
            }
        }
        int total = gray.length;
        int denom = total - cdfMin;
        if (denom <= 0) {
            return; // 单色图
        }
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            int v = (cdf[i] - cdfMin) * 255 / denom;
            lut[i] = (byte) Math.max(0, Math.min(255, v));
        }
        for (int i = 0; i < gray.length; i++) {
            gray[i] = lut[gray[i] & 0xFF];
        }
    }

    /**
     * 3x3 拉普拉斯锐化（DPM 增强）：
     * 增强刻印码模块边界，反光模糊场景下提升模块可分辨度。
     * 核：[0,-1,0][-1,5,-1][0,-1,0]
     */
    static void sharpen(byte[] gray, int width, int height) {
        if (width < 3 || height < 3) {
            return;
        }
        byte[] src = gray.clone();
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                int c = src[i] & 0xFF;
                int up = src[i - width] & 0xFF;
                int down = src[i + width] & 0xFF;
                int left = src[i - 1] & 0xFF;
                int right = src[i + 1] & 0xFF;
                int v = 5 * c - up - down - left - right;
                gray[i] = (byte) Math.max(0, Math.min(255, v));
            }
        }
    }

    /**
     * 3x3 形态学腐蚀（原语）：邻域全黑才保留黑，收缩黑色区域
     */
    private static byte[] erode(byte[] bin, int width, int height) {
        byte[] out = new byte[bin.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    out[y * width + x] = (byte) 255;
                    continue;
                }
                boolean allBlack = true;
                for (int dy = -1; dy <= 1 && allBlack; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((bin[(y + dy) * width + (x + dx)] & 0xFF) == 255) {
                            allBlack = false;
                            break;
                        }
                    }
                }
                out[y * width + x] = (byte) (allBlack ? 0 : 255);
            }
        }
        return out;
    }

    /**
     * 3x3 形态学膨胀（原语）：邻域有黑即黑，扩张黑色区域
     */
    private static void dilateInPlace(byte[] bin, byte[] src, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    continue;
                }
                boolean anyBlack = false;
                for (int dy = -1; dy <= 1 && !anyBlack; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((src[(y + dy) * width + (x + dx)] & 0xFF) == 0) {
                            anyBlack = true;
                            break;
                        }
                    }
                }
                bin[y * width + x] = (byte) (anyBlack ? 0 : 255);
            }
        }
    }

    /**
     * 3x3 形态学开运算去噪（腐蚀→膨胀，DPM 增强）：
     * 金属表面噪点/划痕在二值化后形成孤立散点，干扰 DM 码 Finder Pattern 检测，
     * 开运算可去除散点并保留码模块结构。
     */
    static void denoise(byte[] bin, int width, int height) {
        if (width < 3 || height < 3) {
            return;
        }
        byte[] eroded = erode(bin, width, height);
        dilateInPlace(bin, eroded, width, height);
    }

    /**
     * 3x3 形态学闭运算（膨胀→腐蚀，圆点 DM 专项）：
     * DotPeen 圆点码每个模块是独立圆点、点间隙使模块连接性断裂，
     * 传统采样网格失配而无法识别。闭运算先膨胀（圆点长大弥合间隙）
     * 再腐蚀（恢复边界），把点阵"焊接"成连续模块形态，恢复可解码性。
     */
    static void closeMorph(byte[] bin, int width, int height) {
        if (width < 3 || height < 3) {
            return;
        }
        byte[] dilated = bin.clone();
        dilateInPlace(dilated, bin, width, height);
        byte[] closed = erode(dilated, width, height);
        System.arraycopy(closed, 0, bin, 0, bin.length);
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
