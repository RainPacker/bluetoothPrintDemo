import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.datamatrix.DataMatrixWriter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * DotPeen（点针打标）DataMatrix 模拟图生成器
 *
 * 模拟真实点针压痕在金属/闪存表面的视觉特征，用于扫码算法测试集构建：
 *  - 铝件银灰底 / 闪存黑色环氧底（压痕反光成亮点）
 *  - 压痕圆点 + 边缘半影（凹坑斜坡）+ 受光侧凸起环微亮
 *  - 点相互重叠（点针直径 > 模块间距一半，真实 DotPeen 外观）
 *  - 点径/点位机械抖动（振动打标机特征）
 *  - 喷砂底噪、随机划痕、径向/侧向光照不均
 *
 * 自检方式：
 *  - 结构校验：逐模块中心采样比对 BitMatrix，保证渲染忠实于码结构
 *  - 对照组：方块渲染图必须被原生 zxing Java reader 直读（基线校准）
 *  - DotPeen 组：原生 reader 读不出是预期行为（zxing Java DM Detector
 *    对分离圆点结构弱；app 实际依赖 cpp/zxing-c++ 层，真机日志证实
 *    DotPeen 出码全部来自 cpp 层），可读性由 app 相册模式真机验证
 *
 * 用法: java DotPeenGen [输出目录]   （无参数则输出到 ./out）
 */
public class DotPeenGen {

    static class Preset {
        String name, lighting, desc;
        int base, dot, noiseAmp, scratches, jitterPct;
        double radiusRatio;
        boolean edgeHighlight, block;
        Preset(String n,int b,int d,int na,int sc,String li,double rr,int jp,boolean eh,String de){
            this(n,b,d,na,sc,li,rr,jp,eh,false,de);
        }
        Preset(String n,int b,int d,int na,int sc,String li,double rr,int jp,
               boolean eh,boolean bl,String de){
            name=n; base=b; dot=d; noiseAmp=na; scratches=sc; lighting=li;
            radiusRatio=rr; jitterPct=jp; edgeHighlight=eh; block=bl; desc=de;
        }
    }

    static final Preset[] PRESETS = {
        new Preset("ctrl_block_binary",255,   0,  0, 0, "uniform", 0.50, 0, false, true,  "对照·方块黑白（必须直读）"),
        new Preset("ctrl_block_gray",  190,  70,  8, 0, "uniform", 0.50, 0, false, true,  "对照·方块灰度（原生基线）"),
        new Preset("alu_clean",    190,  70,  8, 0, "uniform", 0.44, 0, false, "铝件·干净均匀光照（基准）"),
        new Preset("alu_scratched",185,  65, 14, 5, "side",    0.44, 6, true,  "铝件·划痕+侧光"),
        new Preset("alu_uneven",   180,  60, 12, 2, "radial",  0.44, 4, true,  "铝件·光照不均（边缘暗）"),
        new Preset("flash_dark",    55, 150, 10, 1, "radial",  0.42, 4, true,  "闪存黑环氧·压痕反光亮点"),
        new Preset("alu_faint",    200, 130, 10, 2, "side",    0.43, 5, false, "铝件·浅打标低对比"),
        new Preset("alu_jitter",   185,  68, 14, 3, "side",    0.42, 14, true, "铝件·强机械振动（点径/点位不均）"),
    };

    static final String[] TEXTS = {
        "P5317576|S26030016|V523690",
        "BYD476ZQC-1118020A1014040086261090578",
        "ABC-abc-1234",
    };

    static final int PX = 14;      // 每模块像素
    static final int MARGIN = 6;   // 静区（模块数）

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "out";
        new File(outDir).mkdirs();
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);

        int structOK = 0, structFail = 0, ctrlFail = 0;
        for (Preset p : PRESETS) {
            for (String text : TEXTS) {
                BitMatrix m = new DataMatrixWriter().encode(text,
                        BarcodeFormat.DATA_MATRIX, 0, 0, null);
                BufferedImage img = render(m, p, new Random(text.hashCode() ^ p.name.hashCode()));
                File f = new File(outDir, "dotpeen_" + p.name + "_" + slug(text) + ".png");
                ImageIO.write(img, "png", f);

                boolean structure = verifyStructure(img, m, p);
                String got = decode(img, reader);
                boolean rawOK = text.equals(got);
                boolean pass;
                if (p.block) {
                    pass = structure && rawOK;
                    if (!rawOK) ctrlFail++;
                } else {
                    pass = structure;
                }
                if (structure) structOK++; else structFail++;
                System.out.println((pass ? "OK  " : "FAIL") + "  " + f.getName()
                        + "  struct=" + (structure ? "ok" : "BAD")
                        + "  raw=" + (rawOK ? "yes" : (p.block ? "NO!" : "expected")));
            }
        }
        System.out.println("\n结构校验: " + structOK + " ok / " + structFail + " bad"
                + (ctrlFail > 0 ? ("  对照组直读失败 " + ctrlFail + " 张!") : "  对照组直读全部通过")
                + "  -> " + new File(outDir).getAbsolutePath());
        if (structFail > 0 || ctrlFail > 0) System.exit(1);
    }
    /** 按 DotPeen 压痕特征渲染 BitMatrix 为灰度图 */
    static BufferedImage render(BitMatrix m, Preset p, Random rng) {
        final int cols = m.getWidth(), rows = m.getHeight();
        final int W = (cols + 2 * MARGIN) * PX, H = (rows + 2 * MARGIN) * PX;
        double[][] v = new double[H][W];
        final Random r = rng;

        // 1) 表面底色 + 喷砂噪声
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                v[y][x] = p.base + (p.noiseAmp > 0 ? r.nextInt(2 * p.noiseAmp + 1) - p.noiseAmp : 0);

        // 2) 光照不均：radial=边缘压暗；side=左亮右暗
        double cx = W / 2.0, cy = H / 2.0, maxD = Math.hypot(cx, cy);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double g = 1.0;
                if (p.lighting.equals("radial")) {
                    double d = Math.hypot(x - cx, y - cy) / maxD;
                    g = 1.0 - 0.25 * d * d;
                } else if (p.lighting.equals("side")) {
                    g = 1.06 - 0.12 * ((double) x / W);
                }
                v[y][x] *= g;
            }

        // 3) 模块绘制：block=方块（对照组）；dot=压痕圆点（半影+受光侧凸起环）
        for (int my = 0; my < rows; my++)
            for (int mx = 0; mx < cols; mx++) {
                if (!m.get(mx, my)) continue;
                boolean finder = mx == 0 || my == rows - 1; // L 定位边（左列+底行）
                // 真实打标机打 L 边时针头走刀重叠、压痕连续：点径加大 40% 保证
                // 相邻点重叠成带（黑底上点串间隙会破坏 DM 定位）；抖动减半
                int jp = finder ? p.jitterPct / 2 : p.jitterPct;
                int jx = jp > 0 ? r.nextInt(2 * jp + 1) - jp : 0;
                int jy = jp > 0 ? r.nextInt(2 * jp + 1) - jp : 0;
                if (p.block) {
                    fillRect(v, (MARGIN + mx) * PX, (MARGIN + my) * PX, PX, PX, p.dot, p.noiseAmp, r);
                    continue;
                }
                double dr = finder
                        ? p.radiusRatio * 1.4
                        : p.radiusRatio * (0.85 + 0.3 * r.nextDouble());
                double rad = dr * PX;
                double ox = (MARGIN + mx + 0.5) * PX + 0.01 * jx * PX;
                double oy = (MARGIN + my + 0.5) * PX + 0.01 * jy * PX;
                int x0 = Math.max(0, (int) (ox - rad - 2)), x1 = Math.min(W - 1, (int) (ox + rad + 2));
                int y0 = Math.max(0, (int) (oy - rad - 2)), y1 = Math.min(H - 1, (int) (oy + rad + 2));
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        double dx = x - ox, dy = y - oy, d2 = dx * dx + dy * dy;
                        if (d2 > rad * rad) continue;
                        double d = Math.sqrt(d2) / rad;
                        double t = d < 0.55 ? 1.0 : 1.0 - (d - 0.55) / 0.45;
                        t = t * t * (3 - 2 * t); // smoothstep 半影
                        double val = v[y][x] + (p.dot - p.base) * t;
                        if (p.edgeHighlight && dy < 0 && d >= 0.55 && d <= 0.95) {
                            val += 10 * Math.sin(Math.PI * (d - 0.55) / 0.4);
                        }
                        v[y][x] = val;
                    }
            }

        // 4) 随机划痕：浅色细线
        for (int i = 0; i < p.scratches; i++) {
            int x = r.nextInt(W), y = r.nextInt(H);
            int len = 40 + r.nextInt(120), dirX = r.nextBoolean() ? 1 : -1, dirY = r.nextBoolean() ? 1 : -1;
            for (int s = 0; s < len; s++) {
                int xx = x + s * dirX / 3, yy = y + s * dirY;
                if (xx < 0 || xx >= W || yy < 0 || yy >= H) break;
                v[yy][xx] = Math.min(255, v[yy][xx] + 30 - r.nextInt(20));
            }
        }

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                img.getRaster().setSample(x, y, 0,
                        Math.max(0, Math.min(255, (int) Math.round(v[y][x]))));
        return img;
    }

    static void fillRect(double[][] v, int x0, int y0, int w, int h, int val, int noiseAmp, Random r) {
        for (int y = y0; y < y0 + h && y < v.length; y++)
            for (int x = x0; x < x0 + w && x < v[0].length; x++)
                v[y][x] = val + (noiseAmp > 0 ? r.nextInt(2 * noiseAmp + 1) - noiseAmp : 0);
    }

    /** 结构校验：每模块中心区域采样均值应与 BitMatrix 明暗一致 */
    static boolean verifyStructure(BufferedImage img, BitMatrix m, Preset p) {
        final int cols = m.getWidth(), rows = m.getHeight();
        final int mid = (p.base + p.dot) / 2;
        final int q = PX / 4;
        for (int my = 0; my < rows; my++)
            for (int mx = 0; mx < cols; mx++) {
                int cx = (int) ((MARGIN + mx + 0.5) * PX), cy = (int) ((MARGIN + my + 0.5) * PX);
                int sum = 0, n = 0;
                for (int dy = -q; dy <= q; dy++)
                    for (int dx = -q; dx <= q; dx++) {
                        sum += img.getRaster().getSample(cx + dx, cy + dy, 0);
                        n++;
                    }
                int avg = sum / n;
                boolean on = m.get(mx, my);
                // 反色表面（闪存黑底亮点）判据相反；振动抖动的边缘沾染给容差
                boolean dotDarker = p.dot < p.base;
                int tol = p.jitterPct;
                boolean viol = dotDarker
                        ? (on ? avg > mid + tol : avg < mid - tol)
                        : (on ? avg < mid - tol : avg > mid + tol);
                if (viol) {
                    System.out.println("  VIOL " + (on ? "on " : "off") + " mx=" + mx + " my=" + my + " avg=" + avg + " mid=" + mid);
                    return false;
                }
            }
        return true;
    }

    /** 自检解码（仅 DM） */
    static String decode(BufferedImage img, MultiFormatReader reader) {
        try {
            Result res = reader.decode(new BinaryBitmap(new HybridBinarizer(graySource(img))));
            reader.reset();
            return res.getText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 灰度图 → LuminanceSource（免 javase 依赖的最小实现） */
    static LuminanceSource graySource(BufferedImage img) {
        final int w = img.getWidth(), h = img.getHeight();
        final byte[] buf = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                buf[y * w + x] = (byte) img.getRaster().getSample(x, y, 0);
        return new LuminanceSource(w, h) {
            public byte[] getRow(int y2, byte[] row) {
                if (row == null || row.length < w) row = new byte[w];
                System.arraycopy(buf, y2 * w, row, 0, w);
                return row;
            }
            public byte[] getMatrix() { return buf; }
            public boolean isCropSupported() { return false; }
            public LuminanceSource crop(int l, int t, int cw, int ch) { return null; }
            public boolean isRotateSupported() { return false; }
            public LuminanceSource rotateCounterClockwise() { return null; }
        };
    }

    static String slug(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "_");
    }
}
