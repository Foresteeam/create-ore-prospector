import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Original, hand-authored pixel artwork; run from the project root using Java 21+. */
class GenerateTexture {
    private static Graphics2D g;
    private static void rect(int x, int y, int w, int h, int color) {
        g.setColor(new Color(color, true)); g.fillRect(x, y, w, h);
    }
    public static void main(String[] args) throws Exception {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        rect(13, 25, 7, 7, 0xff302f2a); rect(14, 25, 5, 6, 0xff755d3e);
        rect(14, 27, 5, 1, 0xffb3975c); rect(14, 30, 5, 1, 0xffb3975c);
        rect(9, 1, 2, 5, 0xff4a4b45); rect(8, 0, 4, 2, 0xffd8be7a);
        rect(24, 10, 5, 7, 0xff3d3a30); rect(26, 11, 4, 5, 0xffbda16c);
        rect(4, 5, 23, 20, 0xff2b302d); rect(6, 3, 19, 24, 0xff2b302d);
        rect(5, 6, 21, 18, 0xffb39156); rect(7, 4, 17, 22, 0xffb39156);
        rect(6, 6, 19, 1, 0xfff1d697); rect(7, 5, 17, 1, 0xfff1d697);
        rect(24, 7, 1, 17, 0xff725534); rect(7, 24, 17, 1, 0xff725534);
        rect(8, 7, 15, 15, 0xff493d2f); rect(9, 8, 13, 13, 0xff10252b);
        for (int x = 10; x < 22; x += 3) rect(x, 8, 1, 13, 0xff284348);
        for (int y = 9; y < 21; y += 3) rect(9, y, 13, 1, 0xff284348);
        rect(11, 10, 2, 2, 0xffd5b29c); rect(11, 13, 2, 2, 0xffd5b29c);
        rect(17, 10, 2, 2, 0xffedc64c); rect(20, 16, 2, 2, 0xffdb885d);
        rect(11, 19, 2, 2, 0xff62dfda); rect(17, 19, 2, 2, 0xff48d786);
        rect(15, 12, 1, 2, 0xffffd5bb); rect(14, 14, 3, 4, 0xffe94c50);
        rect(13, 17, 1, 2, 0xffe94c50); rect(17, 17, 1, 2, 0xffe94c50);
        rect(7, 7, 1, 1, 0xffffe3a5); rect(23, 7, 1, 1, 0xffffe3a5);
        rect(7, 23, 1, 1, 0xff4f4633); rect(23, 23, 1, 1, 0xff4f4633);
        rect(11, 23, 3, 1, 0xff4d6455); rect(16, 23, 3, 1, 0xffe5bc64);
        g.dispose();
        var target = Path.of("src/main/resources/assets/create_ore_prospector/textures/item/ore_prospector.png");
        Files.createDirectories(target.getParent()); ImageIO.write(image, "png", target.toFile());
        System.out.println(target.toAbsolutePath());
    }
}
