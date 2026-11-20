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
        adaptiveThreshold(gray, width, height, ADAPTIVE_WINDOW, ADAPTIVE_C);
    }

    /**
     * 自适应二值化（可调窗口与阈值偏移）：
     * - 窗口需覆盖 ≥2 个模块间距，否则局部均值跟随圆点自适应，会把点"吃掉"；
     *   ROI 放大路径中模块间距同步放大，窗口需按放大倍数同步扩大；
     * - C 是"黑判定余量"：低对比圆点码（点与背景灰度差 < C）需减小 C 才能取到弱黑点。
     *
     * @param window 局部均值窗口边长（奇数化处理）
     * @param c      像素低于局部均值-c 判为黑
     */
    static void adaptiveThreshold(byte[] gray, int width, int height, int window, int c) {
        if (window < 3) {
            window = 3;
        } else if (window % 2 == 0) {
            window++;
        }
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
        int half = window / 2;
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
                gray[y * width + x] = (byte) (pixel < mean - c ? 0 : 255);
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
     * Otsu 全局阈值（类间方差最大化）：点距估计专用。
     * adaptiveThreshold 的局部窗口在"点径≈窗口"时会切断大点（点中心局部
     * 均值被拉低反而不黑），run 统计失真——全局阈值保持点结构完整
     */
    static int otsuThreshold(byte[] gray) {
        int[] hist = new int[256];
        for (byte b : gray) {
            hist[b & 0xFF]++;
        }
        int total = gray.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += (double) i * hist[i];
        }
        double sumB = 0;
        int wB = 0;
        double maxVar = -1;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            int wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += (double) t * hist[t];
            double mB = sumB / wB, mF = (sum - sumB) / wF;
            double var = wB * (double) wF * (mB - mF) * (mB - mF);
            if (var > maxVar) {
                maxVar = var;
                threshold = t;
            }
        }
        return threshold;
    }

    /**
     * 双线性任意倍率缩放：相册大点距图（点距>11px）归一化到甜蜜区用。
     * 相机路径的 cropAndScale 只支持整数倍放大；相册单次解码面对任意
     * 分辨率的图（测试图/特写照点距 14px+），需要非整数缩放把点距
     * 精确推到 9~10px 链族参数基准区
     *
     * @return {bytes, width, height}
     */
    static Object[] bilinearScale(byte[] gray, int width, int height, float kx, float ky) {
        int outW = Math.max(1, Math.round(width * kx));
        int outH = Math.max(1, Math.round(height * ky));
        byte[] out = new byte[outW * outH];
        for (int y = 0; y < outH; y++) {
            float sy = (y + 0.5f) / ky - 0.5f;
            int y0 = (int) Math.floor(sy);
            float fy = sy - y0;
            int y1 = Math.min(height - 1, y0 + 1);
            y0 = Math.max(0, y0);
            for (int x = 0; x < outW; x++) {
                float sx = (x + 0.5f) / kx - 0.5f;
                int x0 = (int) Math.floor(sx);
                float fx = sx - x0;
                int x1 = Math.min(width - 1, x0 + 1);
                x0 = Math.max(0, x0);
                float p = (gray[y0 * width + x0] & 0xFF) * (1 - fx) * (1 - fy)
                        + (gray[y0 * width + x1] & 0xFF) * fx * (1 - fy)
                        + (gray[y1 * width + x0] & 0xFF) * (1 - fx) * fy
                        + (gray[y1 * width + x1] & 0xFF) * fx * fy;
                out[y * outW + x] = (byte) Math.round(p);
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
     * CLAHE 限制对比度自适应直方图均衡化（DPM 增强）：
     * 8x8 分块各自均衡化 + clipLimit 抑制噪声放大 + tile 间双线性插值消除块边界。
     * 与全局 equalizeHistogram 的差异：金属面局部反光/阴影导致"一亮一暗两个区域"
     * 时，全局均衡化会把亮区压成一片白、暗区一片黑，分块均衡化保留局部对比度——
     * 正是金属打标码光照不均场景的对症手段。喷砂噪声的对比度放大由 clipLimit 约束。
     */
    static void clahe(byte[] gray, int width, int height) {
        final int GX = 8, GY = 8;
        // tile 最小 16px：更小时统计失真，退化不值得做
        if (width < GX * 16 || height < GY * 16) {
            return;
        }
        int tx = width / GX;
        int ty = height / GY;
        short[][] luts = new short[GY * GX][256];
        for (int gy = 0; gy < GY; gy++) {
            for (int gx = 0; gx < GX; gx++) {
                int x0 = gx * tx;
                int y0 = gy * ty;
                int x1 = (gx == GX - 1) ? width : (gx + 1) * tx;
                int y1 = (gy == GY - 1) ? height : (gy + 1) * ty;
                int[] hist = new int[256];
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        hist[gray[y * width + x] & 0xFF]++;
                    }
                }
                int area = (x1 - x0) * (y1 - y0);
                // clipLimit factor=3：clip = 3*area/256（OpenCV 相同换算公式）
                int clip = 3 * area / 256;
                if (clip < 1) {
                    clip = 1;
                }
                int surplus = 0;
                for (int i = 0; i < 256; i++) {
                    if (hist[i] > clip) {
                        surplus += hist[i] - clip;
                        hist[i] = clip;
                    }
                }
                int bonus = surplus / 256;
                int acc = 0;
                for (int i = 0; i < 256; i++) {
                    acc += hist[i] + bonus;
                    int v = acc * 255 / area;
                    luts[gy * GX + gx][i] = (short) Math.min(255, v);
                }
            }
        }
        // 双线性插值映射：以 tile 中心为锚点，4 邻 tile LUT 加权；出界侧 clamp
        // 退化为最近 tile（边缘与角落平滑过渡，无块状边界）
        int halfX = tx / 2, halfY = ty / 2;
        for (int y = 0; y < height; y++) {
            float fy = (y - halfY) / (float) ty;
            int gy0 = (int) Math.floor(fy);
            float wy = fy - gy0;
            if (gy0 < 0) {
                gy0 = 0;
            }
            if (gy0 > GY - 1) {
                gy0 = GY - 1;
            }
            int gy1 = Math.min(GY - 1, gy0 + 1);
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                float fx = (x - halfX) / (float) tx;
                int gx0 = (int) Math.floor(fx);
                float wx = fx - gx0;
                if (gx0 < 0) {
                    gx0 = 0;
                }
                if (gx0 > GX - 1) {
                    gx0 = GX - 1;
                }
                int gx1 = Math.min(GX - 1, gx0 + 1);
                int v = gray[rowBase + x] & 0xFF;
                short[] l00 = luts[gy0 * GX + gx0];
                short[] l10 = luts[gy0 * GX + gx1];
                short[] l01 = luts[gy1 * GX + gx0];
                short[] l11 = luts[gy1 * GX + gx1];
                float top = l00[v] + (l10[v] - l00[v]) * wx;
                float bot = l01[v] + (l11[v] - l01[v]) * wx;
                gray[rowBase + x] = (byte) (top + (bot - top) * wy + 0.5f);
            }
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
     * 3x3 中值滤波（盐椒型颗粒噪声专项）：
     * 喷砂/金属表面颗粒噪声呈孤立亮暗点，均值类滤波会扩散噪声，
     * 中值滤波直接剔除孤立极值且保边，先于二值化执行可显著减少背景误判黑点。
     */
    static void medianFilter3(byte[] gray, int width, int height) {
        if (width < 3 || height < 3) {
            return;
        }
        byte[] src = gray.clone();
        int[] win = new int[9];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        win[idx++] = src[(y + dy) * width + x + dx] & 0xFF;
                    }
                }
                // 9 元素插入排序取中位（窗口极小，开销可忽略）
                for (int i = 1; i < 9; i++) {
                    int v = win[i];
                    int j = i - 1;
                    while (j >= 0 && win[j] > v) {
                        win[j + 1] = win[j];
                        j--;
                    }
                    win[j + 1] = v;
                }
                gray[y * width + x] = (byte) win[4];
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
     * 连通域面积过滤去噪（保细长结构的小码专用）：
     * 开运算 denoise（erode→dilate）会连 1~2px 细条码条、2~3px 小 QR 模块
     * 一起抹掉，不能用于小码探针。本方法只清除面积 < minArea 的孤立黑色
     * （0）噪斑（铝面氧化斑/纹理噪点）：条码细条（1~2px 宽 × 长条面积大）、
     * QR 模块（≥3x3）均保留。
     * 实现：扫描线洪泛填充（span 三元组栈，栈深 O(周长) 远小于逐像素栈），
     * 每个连通域完整遍历后按面积裁决——不能用"面积超限提前中止"捷径：
     * 中止会让大连通域的尾部残留变成伪小组件被误清（1px 细条尾段实测
     * 被切掉 6px）。span 收集在面积超限后停止，大域不占收集内存。
     */
    static void despeckle(byte[] bin, int width, int height, int minArea) {
        if (width < 3 || height < 3 || minArea <= 1) {
            return;
        }
        byte[] visited = new byte[bin.length];
        int[] stack = new int[256];
        int[] spans = new int[96];
        for (int seed = 0; seed < bin.length; seed++) {
            if (visited[seed] != 0 || (bin[seed] & 0xFF) != 0) {
                continue;
            }
            int top = 0, nsp = 0, area = 0;
            boolean keep = false;
            stack[top++] = seed / width;
            stack[top++] = seed % width;
            stack[top++] = seed % width;
            visited[seed] = 1;
            while (top > 0) {
                int x1 = stack[--top], x0 = stack[--top], y = stack[--top];
                // 行内扩展：从 span 中点向两侧延伸连续黑像素
                int lx = x0;
                while (lx > 0 && visited[y * width + lx - 1] == 0
                        && (bin[y * width + lx - 1] & 0xFF) == 0) {
                    visited[y * width + --lx] = 1;
                }
                int rx = x1;
                while (rx < width - 1 && visited[y * width + rx + 1] == 0
                        && (bin[y * width + rx + 1] & 0xFF) == 0) {
                    visited[y * width + ++rx] = 1;
                }
                area += rx - lx + 1;
                if (!keep && area > minArea) {
                    keep = true;
                    nsp = 0;
                }
                if (!keep) {
                    if (nsp + 3 > spans.length) {
                        int[] bigger = new int[spans.length * 2];
                        System.arraycopy(spans, 0, bigger, 0, nsp);
                        spans = bigger;
                    }
                    spans[nsp++] = y;
                    spans[nsp++] = lx;
                    spans[nsp++] = rx;
                }
                // 上下邻行扫新 span（像素在 push 时标记 visited 防重复入栈）
                for (int ny = y - 1; ny <= y + 1; ny += 2) {
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    int rowBase = ny * width;
                    int x = lx;
                    while (x <= rx) {
                        if (visited[rowBase + x] == 0 && (bin[rowBase + x] & 0xFF) == 0) {
                            int sx = x;
                            visited[rowBase + x] = 1;
                            while (x + 1 <= rx && visited[rowBase + x + 1] == 0
                                    && (bin[rowBase + x + 1] & 0xFF) == 0) {
                                x++;
                                visited[rowBase + x] = 1;
                            }
                            if (top + 3 > stack.length) {
                                int[] bigger = new int[stack.length * 2];
                                System.arraycopy(stack, 0, bigger, 0, top);
                                stack = bigger;
                            }
                            stack[top++] = ny;
                            stack[top++] = sx;
                            stack[top++] = x;
                        }
                        x++;
                    }
                }
            }
            if (!keep) {
                for (int k = 0; k < nsp; k += 3) {
                    int y = spans[k];
                    for (int x = spans[k + 1]; x <= spans[k + 2]; x++) {
                        bin[y * width + x] = (byte) 255;
                    }
                }
            }
        }
    }

    /**
     * 3x3 形态学闭运算（膨胀→腐蚀，圆点 DM 专项）：
     * DotPeen 圆点码每个模块是独立圆点、点间隙使模块连接性断裂，
     * 传统采样网格失配而无法识别。闭运算先膨胀（圆点长大弥合间隙）
     * 再腐蚀（恢复边界），把点阵"焊接"成连续模块形态，恢复可解码性。
     */
    static void closeMorph(byte[] bin, int width, int height) {
        closeMorph(bin, width, height, 1);
    }

    /**
     * 多次闭运算（等效半径 iterations 的圆形结构元）：
     * 击打圆点的点间隙可达 2~3px，单次 3x3 闭运算（半径1）焊不上，
     * 迭代 2 次等效半径 2，可弥合更宽间隙。
     *
     * @param iterations 膨胀/腐蚀迭代次数（>=1）
     */
    static void closeMorph(byte[] bin, int width, int height, int iterations) {
        if (width < 3 || height < 3 || iterations < 1) {
            return;
        }
        // 膨胀 ping-pong：dilateInPlace(dst, src)
        byte[] cur = bin.clone();
        for (int i = 0; i < iterations; i++) {
            byte[] next = cur.clone();
            dilateInPlace(next, cur, width, height);
            cur = next;
        }
        // 腐蚀 ping-pong：erode 返回新数组
        for (int i = 0; i < iterations; i++) {
            cur = erode(cur, width, height);
        }
        System.arraycopy(cur, 0, bin, 0, bin.length);
    }

    /**
     * 二值图填洞（圆点 DM 高光专项）：
     * 击打凹坑内壁反光使坑底中心比背景亮，二值化后圆点呈
     * "黑环 + 白心"而非实心黑块，解码器采样网格完全失配。
     * 从边界 flood fill 标记与外界连通的白（真背景），
     * 未被标记的白即"洞"（高光坑底/噪声孔洞）→ 填黑变实心。
     */
    static void fillHoles(byte[] bin, int width, int height) {
        if (width < 3 || height < 3) {
            return;
        }
        int n = width * height;
        byte[] outside = new byte[n];   // 1 = 与边界连通的白（真背景）
        int[] stack = new int[n];       // 手动栈，避免装箱开销
        int sp = 0;
        // 边界白像素入栈
        for (int x = 0; x < width; x++) {
            int top = x;
            int bottom = (height - 1) * width + x;
            if ((bin[top] & 0xFF) == 255 && outside[top] == 0) {
                outside[top] = 1;
                stack[sp++] = top;
            }
            if ((bin[bottom] & 0xFF) == 255 && outside[bottom] == 0) {
                outside[bottom] = 1;
                stack[sp++] = bottom;
            }
        }
        for (int y = 0; y < height; y++) {
            int left = y * width;
            int right = y * width + width - 1;
            if ((bin[left] & 0xFF) == 255 && outside[left] == 0) {
                outside[left] = 1;
                stack[sp++] = left;
            }
            if ((bin[right] & 0xFF) == 255 && outside[right] == 0) {
                outside[right] = 1;
                stack[sp++] = right;
            }
        }
        // 4 邻域 flood fill
        while (sp > 0) {
            int p = stack[--sp];
            int x = p % width;
            int y = p / width;
            if (x > 0) {
                int q = p - 1;
                if ((bin[q] & 0xFF) == 255 && outside[q] == 0) {
                    outside[q] = 1;
                    stack[sp++] = q;
                }
            }
            if (x < width - 1) {
                int q = p + 1;
                if ((bin[q] & 0xFF) == 255 && outside[q] == 0) {
                    outside[q] = 1;
                    stack[sp++] = q;
                }
            }
            if (y > 0) {
                int q = p - width;
                if ((bin[q] & 0xFF) == 255 && outside[q] == 0) {
                    outside[q] = 1;
                    stack[sp++] = q;
                }
            }
            if (y < height - 1) {
                int q = p + width;
                if ((bin[q] & 0xFF) == 255 && outside[q] == 0) {
                    outside[q] = 1;
                    stack[sp++] = q;
                }
            }
        }
        // 未与背景连通的白 = 洞 → 填黑
        for (int i = 0; i < n; i++) {
            if ((bin[i] & 0xFF) == 255 && outside[i] == 0) {
                bin[i] = 0;
            }
        }
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
