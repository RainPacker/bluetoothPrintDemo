package com.weifu.scanplugin.camera;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.datamatrix.decoder.Decoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DotPeen 点阵 DM 网格直解器（点式定位边专项兜底层）。
 * <p>
 * 根因（平板暗底 DotPeen 图实测分析，testimages/dotpeen_flash_dark.png）：
 * DotPeen 点阵码的 L 定位边由孤立圆点构成（非实线），zxing MultiFormatReader /
 * zxing-cpp 的 DM 定位器都依赖"实线 L 边 + 对角扫描线过渡"，点式定位边导致
 * 定位阶段直接失败——即使二值化完全干净（Otsu 阈值下点阵结构清晰可辨），
 * 全部现有链族（自适应阈值/形态学/cpp 多尺度）也 0 成功组合。
 * 而跳过定位器、把重建的位矩阵直接交给 DM 解码内核（ECC200 纠错）即可成功
 * （阈值 135~168 全区间解码成功，纠错 0~2 错误）。
 * <p>
 * 本类实现"网格采样直解内核"管线：
 * 1. Otsu 全局二值化（暗底亮点/亮底暗点双极性）；
 * 2. 连通域质心提取（面积过滤去噪）；
 * 3. 最近邻距离中位数 → 点距；质心百分位边界 → 码区范围；
 * 4. 候选符号尺寸（N-2..N+2 偶数）× 网格相位/点距局部搜索（mask 对比度最大化）；
 * 5. 行/列晶格漂移精化；
 * 6. 格心亮度采样 → 阈值扫描 × 极性 × 四方向 → DM 解码内核直解；
 * 7. 文本多数派投票输出。
 * <p>
 * 线程模型：无共享可变状态（Decoder 内核每次调用新建），可在解码线程直接调用。
 * 仅依赖 ImagePreprocessor 与 zxing core，桌面 JVM 可离线验证（Diag8）。
 */
final class DotGridDecoder {

    private DotGridDecoder() {
    }

    /** 质心数量上限（防噪点洪水） */
    private static final int MAX_CENTROIDS = 2000;
    /** 网格对比度门槛：低于此值视为无点阵结构 */
    private static final double MIN_CONTRAST = 0.15;
    /** 符号尺寸范围（ECC200 方形） */
    private static final int MIN_N = 10;
    private static final int MAX_N = 26;

    /**
     * 入口：先原尺寸解码；失败且图较大时 /2 降采样重试一次
     * （特写/测试图点距可达 14px+，降采样把点距推入搜索区间）。
     */
    static Result decode(byte[] gray, int width, int height) {
        Result r = decodeOnce(gray, width, height);
        if (r != null) {
            return r;
        }
        if (Math.max(width, height) >= 400 && width % 2 == 0 && height % 2 == 0) {
            byte[] small = ImagePreprocessor.downscale(gray, width, height, 2);
            return decodeOnce(small, width / 2, height / 2);
        }
        return null;
    }

    private static Result decodeOnce(byte[] gray, int width, int height) {
        if (width < 64 || height < 64 || gray.length != width * height) {
            return null;
        }
        int otsu = ImagePreprocessor.otsuThreshold(gray);
        // 双极性：暗底亮点（闪存压痕）→ 高侧为点；亮底暗点 → 低侧为点。
        // 亮点极性优先（DotPeen 暗底图为主场景）
        for (boolean brightDots : new boolean[]{true, false}) {
            boolean[] mask = new boolean[gray.length];
            for (int i = 0; i < gray.length; i++) {
                int v = gray[i] & 0xFF;
                mask[i] = brightDots ? v >= otsu : v < otsu;
            }
            List<double[]> cents = extractCentroids(mask, width, height);
            if (cents == null) {
                continue;
            }
            double pitch = nnMedianPitch(cents);
            if (pitch < 3.5 || pitch > 60) {
                continue;
            }
            // 码区百分位边界（抗零星噪点外扩）
            double[] bounds = percentileBounds(cents);
            double span = Math.max(bounds[1] - bounds[0], bounds[3] - bounds[2]);
            int n0 = (int) Math.round(span / pitch) + 1;
            Map<String, Integer> votes = new LinkedHashMap<>();
            for (int n = Math.max(MIN_N, n0 - 2); n <= Math.min(MAX_N, n0 + 2); n++) {
                if ((n & 1) != 0) {
                    continue;
                }
                if ((n - 1) * pitch > Math.max(width, height) * 1.2) {
                    continue;
                }
                // 网格相位/点距局部搜索（mask 对比度最大化）
                double bestScore = -1;
                double bestP = pitch, bestGx = bounds[0], bestGy = bounds[2];
                double pStep = Math.max(0.1, pitch / 24);
                for (double p = pitch * 0.88; p <= pitch * 1.12; p += pStep) {
                    for (int ox = -8; ox <= 8; ox++) {
                        for (int oy = -8; oy <= 8; oy++) {
                            double gx = bounds[0] + ox * (p / 8);
                            double gy = bounds[2] + oy * (p / 8);
                            double s = gridContrast(mask, width, height, gx, gy, p, n);
                            if (s > bestScore) {
                                bestScore = s;
                                bestP = p;
                                bestGx = gx;
                                bestGy = gy;
                            }
                        }
                    }
                }
                if (bestScore < MIN_CONTRAST) {
                    continue;
                }
                // 行/列晶格漂移精化
                double[] colX = refineCols(mask, width, height, bestGx, bestGy, bestP, n);
                double[] rowY = refineRows(mask, width, height, colX, bestGy, bestP, n);
                // 格心亮度采样
                float[][] lum = sampleLum(gray, width, height, colX, rowY, bestP);
                // 阈值扫描（Otsu 邻域 + 格心亮度中值）× 极性 × 四方向内核直解
                int mid = cellMidThreshold(lum, n);
                int[] thresholds = {otsu - 20, otsu - 10, otsu, otsu + 10, otsu + 20, mid};
                Decoder kernel = new Decoder();
                for (int t : thresholds) {
                    if (t < 0 || t > 255) {
                        continue;
                    }
                    boolean[][] mat = new boolean[n][n];
                    for (int my = 0; my < n; my++) {
                        for (int mx = 0; mx < n; mx++) {
                            mat[my][mx] = brightDots ? lum[my][mx] >= t : lum[my][mx] < t;
                        }
                    }
                    tryKernelAllOrientations(mat, kernel, votes);
                    if (!votes.isEmpty()) {
                        break;
                    }
                }
                if (!votes.isEmpty()) {
                    break;
                }
            }
            if (!votes.isEmpty()) {
                return buildVotedResult(votes);
            }
        }
        return null;
    }

    // ===== 连通域质心 =====

    /**
     * 连通域质心提取：4 邻接 BFS，面积两轮过滤（绝对 [3,4000] +
     * 相对中位数 [0.15x, 4x]），数量超限返回 null（视为非点阵图）。
     */
    private static List<double[]> extractCentroids(boolean[] mask, int width, int height) {
        int n = width * height;
        int[] stack = new int[n];
        boolean[] visited = new boolean[n];
        List<double[]> all = new ArrayList<>();
        for (int seed = 0; seed < n; seed++) {
            if (!mask[seed] || visited[seed]) {
                continue;
            }
            long sumX = 0, sumY = 0;
            int cnt = 0, sp = 0;
            stack[sp++] = seed;
            visited[seed] = true;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % width, y = p / width;
                sumX += x;
                sumY += y;
                cnt++;
                if (x > 0 && mask[p - 1] && !visited[p - 1]) {
                    visited[p - 1] = true;
                    stack[sp++] = p - 1;
                }
                if (x < width - 1 && mask[p + 1] && !visited[p + 1]) {
                    visited[p + 1] = true;
                    stack[sp++] = p + 1;
                }
                if (y > 0 && mask[p - width] && !visited[p - width]) {
                    visited[p - width] = true;
                    stack[sp++] = p - width;
                }
                if (y < height - 1 && mask[p + width] && !visited[p + width]) {
                    visited[p + width] = true;
                    stack[sp++] = p + width;
                }
            }
            if (cnt >= 3 && cnt <= 4000) {
                all.add(new double[]{(double) sumX / cnt, (double) sumY / cnt, cnt});
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        // 相对面积过滤
        List<Integer> areas = new ArrayList<>();
        for (double[] b : all) {
            areas.add((int) b[2]);
        }
        Collections.sort(areas);
        int med = areas.get(areas.size() / 2);
        List<double[]> cents = new ArrayList<>();
        for (double[] b : all) {
            if (b[2] >= med * 0.15 && b[2] <= med * 4) {
                cents.add(new double[]{b[0], b[1]});
            }
        }
        if (cents.isEmpty() || cents.size() > MAX_CENTROIDS) {
            return null;
        }
        return cents;
    }

    /**
     * 最近邻距离中位数 → 点距（点阵高度规则，NN 中位数即点间距；
     * 同点分裂产生的微小距离被中位数自然淹没）。
     */
    private static double nnMedianPitch(List<double[]> cents) {
        int k = cents.size();
        int stride = k > 1200 ? k / 1200 + 1 : 1;
        List<Double> dists = new ArrayList<>();
        for (int i = 0; i < k; i += stride) {
            double best = Double.MAX_VALUE;
            double[] a = cents.get(i);
            for (int j = 0; j < k; j++) {
                if (j == i) {
                    continue;
                }
                double[] b = cents.get(j);
                double dx = a[0] - b[0], dy = a[1] - b[1];
                double d = dx * dx + dy * dy;
                if (d < best) {
                    best = d;
                }
            }
            if (best < Double.MAX_VALUE) {
                dists.add(Math.sqrt(best));
            }
        }
        if (dists.size() < 10) {
            return -1;
        }
        Collections.sort(dists);
        return dists.get(dists.size() / 2);
    }

    /**
     * 质心 2%/98% 分位边界（抗孤立噪点外扩）。
     */
    private static double[] percentileBounds(List<double[]> cents) {
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (double[] c : cents) {
            xs.add(c[0]);
            ys.add(c[1]);
        }
        Collections.sort(xs);
        Collections.sort(ys);
        int lo = Math.max(0, (int) (xs.size() * 0.02));
        int hi = Math.min(xs.size() - 1, (int) (xs.size() * 0.98));
        return new double[]{xs.get(lo), xs.get(hi), ys.get(lo), ys.get(hi)};
    }

    // ===== 网格对齐 =====

    /**
     * 网格对比度：晶格格心 mask 命中率 − 半点距偏移处命中率。
     * 点阵对齐时格心大量命中、间隙几乎为 0；失配时两者接近。
     */
    private static double gridContrast(boolean[] mask, int width, int height,
                                       double gx, double gy, double p, int n) {
        double on = 0, off = 0;
        int cnt = 0;
        for (int my = 0; my < n; my++) {
            for (int mx = 0; mx < n; mx++) {
                int ix = (int) Math.round(gx + mx * p);
                int iy = (int) Math.round(gy + my * p);
                if (ix >= 0 && iy >= 0 && ix < width && iy < height) {
                    on += mask[iy * width + ix] ? 1 : 0;
                }
                int jx = (int) Math.round(gx + (mx + 0.5) * p);
                int jy = (int) Math.round(gy + (my + 0.5) * p);
                if (jx >= 0 && jy >= 0 && jx < width && jy < height) {
                    off += mask[jy * width + jx] ? 1 : 0;
                }
                cnt++;
            }
        }
        return cnt > 0 ? (on - off) / cnt : 0;
    }

    /**
     * 列晶格漂移精化：每列在 ±p*0.3 内搜索整列 mask 命中峰值，
     * 再在峰值邻域求加权质心（0.5px 步进）。
     */
    private static double[] refineCols(boolean[] mask, int width, int height,
                                       double gx, double gy, double p, int n) {
        double[] colX = new double[n];
        int r = Math.max(2, (int) Math.round(p * 0.3));
        for (int mx = 0; mx < n; mx++) {
            double base = gx + mx * p;
            double best = -1;
            double bestD = 0;
            for (int d = -r; d <= r; d++) {
                double s = colHit(mask, width, height, base + d, gy, p, n);
                if (s > best) {
                    best = s;
                    bestD = d;
                }
            }
            double wsum = 0, sum = 0;
            for (double d = bestD - 1; d <= bestD + 1; d += 0.5) {
                double w = colHit(mask, width, height, base + d, gy, p, n);
                wsum += w;
                sum += w * (base + d);
            }
            colX[mx] = wsum > 0 ? sum / wsum : base;
        }
        return colX;
    }

    private static double[] refineRows(boolean[] mask, int width, int height,
                                       double[] colX, double gy, double p, int n) {
        double[] rowY = new double[n];
        int r = Math.max(2, (int) Math.round(p * 0.3));
        for (int my = 0; my < n; my++) {
            double base = gy + my * p;
            double best = -1;
            double bestD = 0;
            for (int d = -r; d <= r; d++) {
                double s = rowHit(mask, width, height, colX, base + d, n);
                if (s > best) {
                    best = s;
                    bestD = d;
                }
            }
            double wsum = 0, sum = 0;
            for (double d = bestD - 1; d <= bestD + 1; d += 0.5) {
                double w = rowHit(mask, width, height, colX, base + d, n);
                wsum += w;
                sum += w * (base + d);
            }
            rowY[my] = wsum > 0 ? sum / wsum : base;
        }
        return rowY;
    }

    private static double colHit(boolean[] mask, int width, int height,
                                 double cx, double gy, double p, int n) {
        int ix = (int) Math.round(cx);
        if (ix < 0 || ix >= width) {
            return 0;
        }
        double sum = 0;
        for (int my = 0; my < n; my++) {
            int iy = (int) Math.round(gy + my * p);
            if (iy >= 0 && iy < height) {
                sum += mask[iy * width + ix] ? 1 : 0;
            }
        }
        return sum;
    }

    private static double rowHit(boolean[] mask, int width, int height,
                                 double[] colX, double cy, int n) {
        int iy = (int) Math.round(cy);
        if (iy < 0 || iy >= height) {
            return 0;
        }
        double sum = 0;
        for (int mx = 0; mx < n; mx++) {
            int ix = (int) Math.round(colX[mx]);
            if (ix >= 0 && ix < width) {
                sum += mask[iy * width + ix] ? 1 : 0;
            }
        }
        return sum;
    }

    // ===== 格心采样与内核解码 =====

    /**
     * 格心亮度采样：半径 p*0.35 的方形窗均值（≈点径 0.8p 的内切区域）。
     */
    private static float[][] sampleLum(byte[] gray, int width, int height,
                                       double[] colX, double[] rowY, double p) {
        int n = colX.length;
        int r = Math.max(1, (int) Math.round(p * 0.35));
        float[][] lum = new float[n][n];
        for (int my = 0; my < n; my++) {
            for (int mx = 0; mx < n; mx++) {
                double sum = 0;
                int cnt = 0;
                int cx = (int) Math.round(colX[mx]);
                int cy = (int) Math.round(rowY[my]);
                for (int dy = -r; dy <= r; dy++) {
                    int y = cy + dy;
                    if (y < 0 || y >= height) {
                        continue;
                    }
                    for (int dx = -r; dx <= r; dx++) {
                        int x = cx + dx;
                        if (x < 0 || x >= width) {
                            continue;
                        }
                        sum += gray[y * width + x] & 0xFF;
                        cnt++;
                    }
                }
                lum[my][mx] = cnt > 0 ? (float) (sum / cnt) : 128;
            }
        }
        return lum;
    }

    /**
     * 格心亮度中值（排序取中）：阈值扫描补充档。
     */
    private static int cellMidThreshold(float[][] lum, int n) {
        List<Float> vals = new ArrayList<>();
        for (float[] row : lum) {
            for (float v : row) {
                vals.add(v);
            }
        }
        Collections.sort(vals);
        return Math.round(vals.get(vals.size() / 2));
    }

    /**
     * DM 解码内核直解 × 四方向旋转（内核不做定位，需自行覆盖旋转）。
     */
    private static void tryKernelAllOrientations(boolean[][] mat, Decoder kernel,
                                                 Map<String, Integer> votes) {
        boolean[][] cur = mat;
        for (int rot = 0; rot < 4; rot++) {
            try {
                DecoderResult dr = kernel.decode(cur);
                String text = dr != null ? dr.getText() : null;
                if (text != null && !text.isEmpty()) {
                    votes.merge(text, 1, Integer::sum);
                    return; // 任一方向成功即可（旋转间结果相同）
                }
            } catch (Exception ignored) {
                // FormatException / ChecksumException：该方向/阈值不可解
            }
            cur = rotate90(cur);
        }
    }

    private static boolean[][] rotate90(boolean[][] mat) {
        int n = mat.length;
        boolean[][] out = new boolean[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                out[x][n - 1 - y] = mat[y][x];
            }
        }
        return out;
    }

    /**
     * 文本多数派输出：唯一文本直接采用；多文本取票数最高者
     * （不同阈值/极性读出一致才可信）。
     */
    private static Result buildVotedResult(Map<String, Integer> votes) {
        String bestText = null;
        int bestCnt = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCnt) {
                bestText = e.getKey();
                bestCnt = e.getValue();
            }
        }
        return bestText == null ? null
                : new Result(bestText, null, null, BarcodeFormat.DATA_MATRIX);
    }
}
