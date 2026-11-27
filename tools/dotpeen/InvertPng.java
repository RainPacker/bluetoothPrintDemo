import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** PNG 极性反转（链前反相验证工具） */
public class InvertPng {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = 255 - ((p >> 16) & 0xFF);
            int g = 255 - ((p >> 8) & 0xFF);
            int b = 255 - (p & 0xFF);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(out, "png", new File(args[1]));
        System.out.println("反相完成: " + args[0] + " -> " + args[1]);
    }
}
