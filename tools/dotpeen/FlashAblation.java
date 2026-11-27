import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/** flash_dark 单因素消融：逐个去除（光照/高光环/抖动/噪声）生成变体，供 sweep 定位致命因素 */
public class FlashAblation {
    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "BYD476ZQC-1118020A1014040086261090578";
        // {name, base, dot, noise, scratches, light, rr, jitter, edgeHighlight}
        Object[][] variants = {
                {"fd_flat",    55,  150, 10, 1, "flat",   0.42, 4, true},   // 去光照不均
                {"fd_light",   55,  150, 10, 1, "radial", 0.42, 4, false},  // 去压痕高光环
                {"fd_nojit",   55,  150, 10, 1, "radial", 0.42, 0, true},   // 去点抖动
                {"fd_clean",   55,  150, 2,  0, "flat",   0.42, 0, true},   // 全净（仅极性反转）
                {"fd_nonoise", 55,  150, 2,  1, "radial", 0.42, 4, true},   // 去噪声
                {"alu_radial", 190, 65,  2,  0, "radial", 0.44, 0, true},   // alu_clean+径向光照
                {"fd_125",    55,  180, 2,  0, "flat",   0.44, 0, false},  // 125级对比镜像alu_clean
                {"fd_hicon",  55,  230, 2,  0, "flat",   0.44, 0, false},  // 175级高对比暗底亮点
                {"alu_95",    200, 105, 2,  0, "flat",   0.44, 0, false},  // 95级亮底暗点(对照)
                {"alu_175",   200, 25,  2,  0, "flat",   0.44, 0, false},  // 175级原生(inv_fd_hicon等价)
        };
        for (Object[] v : variants) {
            DotPeenGen.Preset p = new DotPeenGen.Preset((String) v[0], (int) v[1], (int) v[2],
                    (int) v[3], (int) v[4], (String) v[5], (double) v[6], (int) v[7],
                    (boolean) v[8], "ablation");
            BitMatrix m = new DataMatrixWriter().encode(text,
                    BarcodeFormat.DATA_MATRIX, 0, 0, null);
            BufferedImage img = DotPeenGen.render(m, p,
                    new Random(text.hashCode() ^ ((String) v[0]).hashCode()));
            File f = new File("out/ablate_" + v[0] + ".png");
            ImageIO.write(img, "png", f);
            System.out.println("生成: " + f.getAbsolutePath() + " " + img.getWidth() + "x" + img.getHeight());
        }
    }
}
