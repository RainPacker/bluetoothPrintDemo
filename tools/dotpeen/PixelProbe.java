import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 量化 TYPE_BYTE_GRAY 的 getRGB 色彩转换偏差：同坐标 getRGB vs Raster 采样对比 */
public class PixelProbe {
    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        BufferedImage img = ImageIO.read(f);
        int w = img.getWidth(), h = img.getHeight();
        System.out.println("type=" + img.getType() + " " + w + "x" + h);
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int maxDiff = 0;
        long sumDiff = 0;
        int[] diffs = new int[256];
        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                int viaGetRGB = (rgb[y * w + x] >> 16) & 0xFF;
                int viaRaster = img.getRaster().getSample(x, y, 0);
                int d = Math.abs(viaGetRGB - viaRaster);
                if (d < 256) diffs[d]++;
                if (d > maxDiff) maxDiff = d;
                sumDiff += d;
            }
        }
        long n = ((h + 2) / 3) * ((w + 2) / 3);
        System.out.println("maxDiff=" + maxDiff + " avgDiff=" + (sumDiff / (double) n));
        // 典型样本值对比（背景/中心/角落）
        int[][] pts = {{5, 5}, {w / 2, h / 2}, {w / 4, h / 4}, {w - 5, h - 5}};
        for (int[] p : pts) {
            System.out.println("(" + p[0] + "," + p[1] + ") getRGB="
                    + ((rgb[p[1] * w + p[0]] >> 16) & 0xFF)
                    + " raster=" + img.getRaster().getSample(p[0], p[1], 0));
        }
        // 直方图摘要（Raster 路径）
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                hist[img.getRaster().getSample(x, y, 0)]++;
            }
        }
        for (int v = 0; v < 256; v++) {
            if (hist[v] > w * h / 100.0) {
                System.out.println("hist[" + v + "]=" + hist[v]);
            }
        }
    }
}
