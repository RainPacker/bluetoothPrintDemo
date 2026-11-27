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
     * ECC200 矩形规格（行x列）。真机场景：铝面 clean DotPeen 码
     * P5317576|S26030016|V523690 为 12x26——方形-only 的网格直解对矩形码
     * 永远失败（离线实测未识别），只能等更慢的定位器/链族路径，表现为
     * "矩形码比方形码明显慢"
     */
    private static final int[][] VALID_RECT_DIMS = {
            {8, 18}, {8, 32}, {12, 26}, {12, 36}, {16, 36}, {16, 48},
    };
    /** 宽高跨度比超过此值判定为矩形码 */
    private static final double RECT_ASPECT_RATIO = 1.25;

    /**
     * 入口：先原尺寸解码；失败且图较大时 /2 降采样重试一次
     * （特写/测试图点距可达 14px+，降采样把点距推入搜索区间）。
     */
    /** 最近一次解码尝试的阶段诊断（供 miss 日志暴露卡死阶段） */
    public static volatile String lastDiag = "-";

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

    /** 最近一次 decode 中质心数≥40 档的网格点距（dg 质心法比 run 统计
     *  的 estimatePitch 可靠——锈蚀背景上后者乱跳 8~74，前者逐帧稳定），
     *  供 DecodeEngine 平滑 pitchEst */
    static double lastGridPitch = -1;

    private static Result decodeOnce(byte[] gray, int width, int height) {
        if (width < 64 || height < 64 || gray.length != width * height) {
            return null;
        }
        int otsu = ImagePreprocessor.otsuThreshold(gray);
        StringBuilder diag = new StringBuilder("otsu=").append(otsu).append(' ');
        // 双极性：暗底亮点（闪存压痕）→ 高侧为点；亮底暗点 → 低侧为点。
        // 亮点极性优先（DotPeen 暗底图为主场景）
        for (boolean brightDots : new boolean[]{true, false}) {
            // 多档阈值质心召回：真机贴近拍（otsu=70~99）单档丢 ~70% 质心
            // （11-24 日志 pBn132 vs 实际 ~500 点）——弱反光点被全局 otsu 切掉
            // → n0 尺寸估计塌缩（span/pitch≈20 vs 真实 40）→ 候选尺寸全偏
            // → 网格一致性 lo → 贴近时段全 miss，拉远才命中。多档
            // （otsu±12/±25 步进）各自提质心后合并去重（0.5px 量化桶）；
            // 重复质心不去重会把最近邻点距拉到 0 破坏中值，面积过滤
            // （med*0.15~4）天然挡掉低阈值引入的大片底连通域
            List<double[]> cents = new ArrayList<>();
            boolean[] mask = new boolean[gray.length];
            int[] ths = brightDots
                    ? new int[]{otsu - 25, otsu - 12, otsu}
                    : new int[]{otsu, otsu + 12, otsu + 25};
            for (int t : ths) {
                if (t < 0 || t > 255) {
                    continue;
                }
                for (int i = 0; i < gray.length; i++) {
                    int v = gray[i] & 0xFF;
                    mask[i] = brightDots ? v >= t : v < t;
                }
                List<double[]> c = extractCentroids(mask, width, height);
                if (c != null) {
                    // 线性距离去重（门限 1.5px）：跨档同点质心距离 ~0.3px，
                    // 量化桶法在桶边界抖动漏去重（合成图实测点距中值塌缩到
                    // 0.28 触发 rng 拒绝）。数量级 ~500 点，O(n²) 代价可忽略
                    outer:
                    for (double[] pt : c) {
                        for (double[] kept : cents) {
                            double dx = pt[0] - kept[0], dy = pt[1] - kept[1];
                            if (dx * dx + dy * dy < 2.25) {
                                continue outer;
                            }
                        }
                        cents.add(pt);
                    }
                }
            }
            if (cents.isEmpty()) {
                diag.append('p').append(brightDots ? 'B' : 'D').append(":none ");
                continue;
            }
            double pitch = nnMedianPitch(cents);
            if (pitch < 3.5 || pitch > 60) {
                diag.append('p').append(brightDots ? 'B' : 'D')
                        .append("n").append(cents.size()).append("@").append(pitch).append("rng ");
                continue;
            }
            // 密度过滤：低阈值档把背景纹理/glare 碎片切成假质心散布全图
            // （真机 14:37:43 pBn855 vs 码实际 ~250 点），percentile 边界外扩
            // → n0 高估出 MAX_N → 候选空集。码点在 ~pitch 网格上密集（3x3
            // 桶邻域必有点），孤立散点剔除后重估点距/边界
            List<double[]> dense = filterDense(cents, pitch, width, height);
            if (dense.size() >= 24 && dense.size() < cents.size()) {
                cents = dense;
                double p2 = nnMedianPitch(cents);
                if (p2 >= 3.5 && p2 <= 60) {
                    pitch = p2;
                }
            }
            diag.append('p').append(brightDots ? 'B' : 'D')
                    .append("n").append(cents.size()).append('@').append(pitch).append(' ');
            if (cents.size() >= 40) {
                lastGridPitch = pitch;
            }
            // 网格搜索必须用主档（otsu）mask：多档循环结束时 mask 是最低阈值
            // 版本（背景污染——真机 24x24=0.045lo 的直接原因，合成图底干净
            // 未暴露），gridContrast 与格心采样的一致性分数会被污染 mask 稀释
            for (int i = 0; i < gray.length; i++) {
                int v = gray[i] & 0xFF;
                mask[i] = brightDots ? v >= otsu : v < otsu;
            }
            // 码区边界：密度聚簇（最大连通桶簇 bbox）优先，聚簇退化回退分位
            // （暗底帧低阈值档放行的背景噪质心散布全图且占多数时，2%/98%
            // 分位挡不住——n0 爆到 100+ 候选空集，真机 pBn111~650 无 NxN
            // 行实证）
            double[] bounds = clusterBounds(cents, pitch, width, height);
            double spanX = bounds[1] - bounds[0];
            double spanY = bounds[3] - bounds[2];
            double span = Math.max(spanX, spanY);
            int n0 = (int) Math.round(span / pitch) + 1;
            // 候选尺寸：方形（n0±2 偶数）+ 矩形（宽高比超阈值时追加 ECC200 矩形规格）
            List<int[]> dims = new ArrayList<>();
            for (int n = Math.max(MIN_N, n0 - 2); n <= Math.min(MAX_N, n0 + 2); n++) {
                if ((n & 1) == 0) {
                    dims.add(new int[]{n, n});
                }
            }
            double aspect = spanX > spanY ? spanX / spanY : spanY / spanX;
            if (aspect >= RECT_ASPECT_RATIO) {
                int nRows0 = (int) Math.round(spanY / pitch) + 1;
                int nCols0 = (int) Math.round(spanX / pitch) + 1;
                for (int[] rect : VALID_RECT_DIMS) {
                    if (Math.abs(rect[0] - nRows0) <= 1 && Math.abs(rect[1] - nCols0) <= 1) {
                        dims.add(rect);
                    }
                }
            }
            Map<String, Integer> votes = new LinkedHashMap<>();
            for (int[] dim : dims) {
                int rows = dim[0], cols = dim[1];
                if ((rows - 1) * pitch > height * 1.2 || (cols - 1) * pitch > width * 1.2) {
                    continue;
                }
                // 网格相位/点距局部搜索（mask 对比度最大化）——相位两级粗→精：
                // 全枚举 = 25 档点距 × 17×17 相位 ≈ 7.2k 次评估/尺寸，多尺寸 ×
                // 双极性 miss 帧单此项数百 ms。相位维对比度周期 = 点距 p、对齐
                // 峰宽 ~p/3：粗扫步进 4（p/2 间距）必有过峰样本，峰邻域 ±4 步
                // （±p/2）精扫必包住真峰；点距维保持全分辨率 p/24 不动（峰窄，
                // 该密度是离线/真机标定的安全值）。评估数 289→106/档（-63%）
                double bestScore = -1;
                double bestP = pitch, bestGx = bounds[0], bestGy = bounds[2];
                double pStep = Math.max(0.1, pitch / 24);
                for (double p = pitch * 0.88; p <= pitch * 1.12; p += pStep) {
                    // 粗扫（5×5，步进 4）：找本档点距的相位峰位
                    int cbx = 0, cby = 0;
                    double cs = -1;
                    for (int ox = -8; ox <= 8; ox += 4) {
                        for (int oy = -8; oy <= 8; oy += 4) {
                            double gx = bounds[0] + ox * (p / 8);
                            double gy = bounds[2] + oy * (p / 8);
                            double s = gridContrast(mask, width, height, gx, gy, p, rows, cols);
                            if (s > cs) {
                                cs = s;
                                cbx = ox;
                                cby = oy;
                            }
                        }
                    }
                    // 精扫（粗扫最优相位 ±4 步）：粗扫点本身在精扫范围内重评，
                    // 全局最优不会被粗扫漏掉
                    for (int ox = cbx - 4; ox <= cbx + 4; ox++) {
                        for (int oy = cby - 4; oy <= cby + 4; oy++) {
                            double gx = bounds[0] + ox * (p / 8);
                            double gy = bounds[2] + oy * (p / 8);
                            double s = gridContrast(mask, width, height, gx, gy, p, rows, cols);
                            if (s > bestScore) {
                                bestScore = s;
                                bestP = p;
                                bestGx = gx;
                                bestGy = gy;
                            }
                        }
                    }
                }
                // 行/列晶格漂移精化。曲面畸变重评：圆柱工件上的码行列弯曲，
                // 静态直线网格对比度被压到门槛下（真机实拍 26x26=0.093，
                // 精化后可达标）——低分候选先精化再用非均匀网格重评
                double[] colX = null, rowY = null;
                if (bestScore < MIN_CONTRAST) {
                    if (bestScore >= 0.04) {
                        colX = refineCols(mask, width, height, bestGx, bestGy, bestP, cols);
                        rowY = refineRows(mask, width, height, colX, bestGy, bestP, rows, cols);
                        double s2 = gridContrastAt(mask, width, height, colX, rowY, rows, cols);
                        if (s2 >= MIN_CONTRAST) {
                            bestScore = s2;
                        }
                    }
                    if (bestScore < MIN_CONTRAST) {
                        diag.append(rows).append('x').append(cols)
                                .append('=').append(bestScore).append("lo ");
                        continue;
                    }
                }
                diag.append(rows).append('x').append(cols)
                        .append('=').append(bestScore)
                        .append(colX != null ? "+ " : " ");
                if (colX == null) {
                    colX = refineCols(mask, width, height, bestGx, bestGy, bestP, cols);
                    rowY = refineRows(mask, width, height, colX, bestGy, bestP, rows, cols);
                }
                // 格心亮度采样（窗口极值，保住 1~2px 小点信号）
                float[][] lum = sampleLum(gray, width, height, colX, rowY, bestP, brightDots);
                // 阈值扫描（Otsu 邻域 + 格心亮度中值 + 矩阵自身 Otsu）
                // × 极性 × 方向内核直解
                int mid = cellMidThreshold(lum, rows * cols);
                int lumOtsu = lumMatrixOtsu(lum, rows, cols);
                int[] thresholds = {otsu - 20, otsu - 10, otsu, otsu + 10, otsu + 20,
                        mid, lumOtsu};
                Decoder kernel = new Decoder();
                for (int t : thresholds) {
                    if (t < 0 || t > 255) {
                        continue;
                    }
                    boolean[][] mat = new boolean[rows][cols];
                    for (int my = 0; my < rows; my++) {
                        for (int mx = 0; mx < cols; mx++) {
                            mat[my][mx] = brightDots ? lum[my][mx] >= t : lum[my][mx] < t;
                        }
                    }
                    tryKernelAllOrientations(mat, rows == cols, kernel, votes);
                    if (!votes.isEmpty()) {
                        break;
                    }
                }
                if (!votes.isEmpty()) {
                    break;
                }
            }
            if (!votes.isEmpty()) {
                lastDiag = diag.append("HIT").toString();
                return buildVotedResult(votes);
            }
        }
        lastDiag = diag.toString();
        return null;
    }

    // ===== 连通域质心 =====

    /**
     * 密度过滤剔除孤立假质心：按 pitch 分桶，3x3 桶邻域计数 <3 的质心
     * 视为孤立散点丢弃。码点间距 ~pitch，真实码区桶密度高；低阈值档
     * 召回的背景碎片质心孤立。O(n) 桶化 + O(n) 邻域求和
     */
    private static List<double[]> filterDense(List<double[]> cents, double pitch, int w, int h) {
        int bw = Math.max(1, (int) (w / pitch) + 1);
        int bh = Math.max(1, (int) (h / pitch) + 1);
        int[] cnt = new int[bw * bh];
        int[] bIdx = new int[cents.size()];
        for (int i = 0; i < cents.size(); i++) {
            double[] p = cents.get(i);
            int bx = (int) (p[0] / pitch);
            int by = (int) (p[1] / pitch);
            if (bx < 0) bx = 0; else if (bx >= bw) bx = bw - 1;
            if (by < 0) by = 0; else if (by >= bh) by = bh - 1;
            bIdx[i] = by * bw + bx;
            cnt[bIdx[i]]++;
        }
        List<double[]> out = new ArrayList<>(cents.size());
        for (int i = 0; i < cents.size(); i++) {
            int bx = bIdx[i] % bw, by = bIdx[i] / bw;
            int around = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = bx + dx, ny = by + dy;
                    if (nx >= 0 && nx < bw && ny >= 0 && ny < bh) {
                        around += cnt[ny * bw + nx];
                    }
                }
            }
            if (around >= 3) {
                out.add(cents.get(i));
            }
        }
        return out;
    }

    /**
     * 连通域质心提取：4 邻接 BFS，面积两轮过滤（绝对 [3,4000] +
     * 相对中位数 [0.15x, 4x]），数量超限返回 null（视为非点阵图）。
     */
    // BFS 复用缓冲（仅解码线程访问——decode 全部调用点都在 scan-decode-thread，
    // 与 DecodeEngine 复用 Bitmap 同一模式）：每帧最多 6 档阈值 × 2 趟（含
    // decode() 内部 /2 重试）= 12 次调用，各自 new int[n]（1MP 帧 4MB）+
    // boolean[n]（1MB）——每帧 ~60MB 垃圾是 miss 帧 GC 卡顿源
    private static int[] bfsStack = new int[0];
    private static boolean[] bfsVisited = new boolean[0];

    private static List<double[]> extractCentroids(boolean[] mask, int width, int height) {
        int n = width * height;
        if (bfsStack.length < n) {
            bfsStack = new int[n];
            bfsVisited = new boolean[n];
        }
        int[] stack = bfsStack;
        boolean[] visited = bfsVisited;
        java.util.Arrays.fill(visited, 0, n, false);
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

    /**
     * 质心密度聚簇边界：按 pitch 分桶，取最大连通占据桶簇的包围盒。
     * 暗底帧（flash_dark 型黑环氧压痕）低阈值档（otsu-25/-12）把背景噪点
     * 放行成散布全图的假质心——噪点占多数时 2%/98% 分位边界仍是全图，
     * n0 = span/pitch 爆到 100+，候选尺寸区间倒挂成空集，网格搜索整层
     * 根本没跑（真机 pBn111~650 帧无任何 NxN 行实证）。真码质心在 ~pitch
     * 网格上连续占据成紧密大簇、背景噪点成小碎片簇——最大连通簇 bbox
     * 即码区。聚簇退化（<4 桶）回退分位。桶数 ≤ 像素数，复用 BFS 缓冲。
     */
    private static double[] clusterBounds(List<double[]> cents, double pitch, int w, int h) {
        int bw = Math.max(1, (int) (w / pitch) + 1);
        int bh = Math.max(1, (int) (h / pitch) + 1);
        int nb = bw * bh;
        if (bfsStack.length < nb) {
            bfsStack = new int[nb];
            bfsVisited = new boolean[nb];
        }
        int[] stack = bfsStack;
        boolean[] seen = bfsVisited;
        java.util.Arrays.fill(seen, 0, nb, false);
        boolean[] occ = new boolean[nb];
        for (double[] c : cents) {
            int bx = (int) (c[0] / pitch), by = (int) (c[1] / pitch);
            if (bx < 0) bx = 0; else if (bx >= bw) bx = bw - 1;
            if (by < 0) by = 0; else if (by >= bh) by = bh - 1;
            occ[by * bw + bx] = true;
        }
        int bestSize = 0, bestLoX = 0, bestHiX = 0, bestLoY = 0, bestHiY = 0;
        for (int s = 0; s < nb; s++) {
            if (!occ[s] || seen[s]) {
                continue;
            }
            int top = 0, cnt = 0;
            stack[top++] = s;
            seen[s] = true;
            int loX = bw, hiX = -1, loY = bh, hiY = -1;
            while (top > 0) {
                int cur = stack[--top];
                cnt++;
                int cx = cur % bw, cy = cur / bw;
                if (cx < loX) loX = cx;
                if (cx > hiX) hiX = cx;
                if (cy < loY) loY = cy;
                if (cy > hiY) hiY = cy;
                if (cx + 1 < bw && occ[cur + 1] && !seen[cur + 1]) {
                    seen[cur + 1] = true;
                    stack[top++] = cur + 1;
                }
                if (cx > 0 && occ[cur - 1] && !seen[cur - 1]) {
                    seen[cur - 1] = true;
                    stack[top++] = cur - 1;
                }
                if (cy + 1 < bh && occ[cur + bw] && !seen[cur + bw]) {
                    seen[cur + bw] = true;
                    stack[top++] = cur + bw;
                }
                if (cy > 0 && occ[cur - bw] && !seen[cur - bw]) {
                    seen[cur - bw] = true;
                    stack[top++] = cur - bw;
                }
            }
            if (cnt > bestSize) {
                bestSize = cnt;
                bestLoX = loX;
                bestHiX = hiX;
                bestLoY = loY;
                bestHiY = hiY;
            }
        }
        if (bestSize >= 4) {
            return new double[]{bestLoX * pitch, (bestHiX + 1) * pitch,
                    bestLoY * pitch, (bestHiY + 1) * pitch};
        }
        return percentileBounds(cents);
    }

    // ===== 网格对齐 =====

    /**
     * 网格对比度：晶格格心 mask 命中率 − 半点距偏移处命中率。
     * 点阵对齐时格心大量命中、间隙几乎为 0；失配时两者接近。
     */
    private static double gridContrast(boolean[] mask, int width, int height,
                                       double gx, double gy, double p, int rows, int cols) {
        double on = 0, off = 0;
        int cnt = 0;
        for (int my = 0; my < rows; my++) {
            for (int mx = 0; mx < cols; mx++) {
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
     * 非均匀网格对比度：用行/列精化后的独立格心坐标重评。曲面弯曲/透视
     * 畸变场景下行列不直，静态直线网格分数失真；格心取精化坐标、
     * 空隙点取相邻格心中点（边界列/行按末差外推半格）。
     */
    private static double gridContrastAt(boolean[] mask, int width, int height,
                                         double[] colX, double[] rowY, int rows, int cols) {
        double on = 0, off = 0;
        int cnt = 0;
        for (int my = 0; my < rows; my++) {
            for (int mx = 0; mx < cols; mx++) {
                int ix = (int) Math.round(colX[mx]);
                int iy = (int) Math.round(rowY[my]);
                if (ix >= 0 && iy >= 0 && ix < width && iy < height) {
                    on += mask[iy * width + ix] ? 1 : 0;
                }
                double jx, jy;
                if (mx + 1 < cols) {
                    jx = (colX[mx] + colX[mx + 1]) / 2;
                } else if (mx > 0) {
                    jx = colX[mx] + (colX[mx] - colX[mx - 1]) / 2;
                } else {
                    jx = colX[mx] + 0.5;
                }
                if (my + 1 < rows) {
                    jy = (rowY[my] + rowY[my + 1]) / 2;
                } else if (my > 0) {
                    jy = rowY[my] + (rowY[my] - rowY[my - 1]) / 2;
                } else {
                    jy = rowY[my] + 0.5;
                }
                int jxi = (int) Math.round(jx), jyi = (int) Math.round(jy);
                if (jxi >= 0 && jyi >= 0 && jxi < width && jyi < height) {
                    off += mask[jyi * width + jxi] ? 1 : 0;
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
                                       double[] colX, double gy, double p, int rows, int cols) {
        double[] rowY = new double[rows];
        int r = Math.max(2, (int) Math.round(p * 0.3));
        for (int my = 0; my < rows; my++) {
            double base = gy + my * p;
            double best = -1;
            double bestD = 0;
            for (int d = -r; d <= r; d++) {
                double s = rowHit(mask, width, height, colX, base + d, cols);
                if (s > best) {
                    best = s;
                    bestD = d;
                }
            }
            double wsum = 0, sum = 0;
            for (double d = bestD - 1; d <= bestD + 1; d += 0.5) {
                double w = rowHit(mask, width, height, colX, base + d, cols);
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
                                 double[] colX, double cy, int cols) {
        int iy = (int) Math.round(cy);
        if (iy < 0 || iy >= height) {
            return 0;
        }
        double sum = 0;
        for (int mx = 0; mx < cols && mx < colX.length; mx++) {
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
                                       double[] colX, double[] rowY, double p,
                                       boolean brightDots) {
        int rows = rowY.length, cols = colX.length;
        int r = Math.max(1, (int) Math.round(p * 0.35));
        float[][] lum = new float[rows][cols];
        for (int my = 0; my < rows; my++) {
            for (int mx = 0; mx < cols; mx++) {
                int cx = (int) Math.round(colX[mx]);
                int cy = (int) Math.round(rowY[my]);
                // 点径 1~2px 的码点在 (2r+1)^2 均值窗口里只占 ~4/25，信号被
                // 稀释 6 倍（暗点 v80 底 v130 → 均值 122，阈值全档切不开，
                // 真机网格达标后内核零 votes 的根因）——改取窗口极值：
                // 亮点码取最亮、暗点码取最暗，保住码点信号
                int best = brightDots ? -1 : 256;
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
                        int v = gray[y * width + x] & 0xFF;
                        if (brightDots ? v > best : v < best) {
                            best = v;
                        }
                    }
                }
                lum[my][mx] = (best >= 0 && best <= 255) ? best : 128;
            }
        }
        return lum;
    }

    /** 采样矩阵自身的 Otsu 阈值：格心极值采样后点/底双峰分布与图像全局
     *  亮度分布差异大，用矩阵直方图 Otsu 自适应切分，比固定 otsu±20 档贴合 */
    private static int lumMatrixOtsu(float[][] lum, int rows, int cols) {
        int[] hist = new int[256];
        int n = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int v = (int) lum[y][x];
                if (v < 0) v = 0; else if (v > 255) v = 255;
                hist[v]++;
                n++;
            }
        }
        if (n == 0) {
            return 128;
        }
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * hist[i];
        }
        double sumB = 0, wB = 0;
        double bestVar = -1;
        int bestT = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            double wF = n - wB;
            if (wF == 0) {
                break;
            }
            sumB += t * hist[t];
            double mB = sumB / wB, mF = (sum - sumB) / wF;
            double var = wB * wF * (mB - mF) * (mB - mF);
            if (var > bestVar) {
                bestVar = var;
                bestT = t;
            }
        }
        return bestT;
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
     * DM 解码内核直解 × 方向旋转（内核不做定位，需自行覆盖旋转）。
     * 矩形码旋转后尺寸不合法（内核 FormatException），仅方形尝试 4 方向。
     */
    private static void tryKernelAllOrientations(boolean[][] mat, boolean square, Decoder kernel,
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
            if (!square) {
                return;
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
