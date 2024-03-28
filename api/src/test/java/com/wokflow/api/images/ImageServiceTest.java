package com.wokflow.api.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.common.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageServiceTest {

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A valid PNG header that claims 50 000 x 50 000 pixels, followed by no real image data. */
    static byte[] pngBomb() {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(50_000).putInt(50_000).put((byte) 8).put((byte) 6)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] type = "IHDR".getBytes();
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.array());
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4 + 12);
        b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        b.putInt(13).put(type).put(ihdr.array()).putInt((int) crc.getValue());
        b.putInt(0).put("IEND".getBytes()).putInt(0xAE426082);
        return b.array();
    }

    ImageService service() throws Exception {
        return new ImageService(new LocalImageStore(Files.createTempDirectory("img")), "/api/images");
    }

    @Test
    void sniffsByMagicBytesOnly() throws Exception {
        assertThat(ImageService.sniff(png(10, 10))).isEqualTo(ImageService.Format.PNG);
        assertThat(ImageService.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo(ImageService.Format.JPEG);
        assertThat(ImageService.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo(ImageService.Format.WEBP);
        assertThat(ImageService.sniff("GIF89a......".getBytes())).isNull();
        assertThat(ImageService.sniff("<svg xmlns=...>".getBytes())).isNull();
        assertThatThrownBy(() -> service().storeUpload("d", "GIF89a.........".getBytes()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unsupported_image"));
    }

    @Test
    void refusesDecompressionBombsBeforeDecoding() {
        assertThatThrownBy(() -> service().storeUpload("d", pngBomb()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("image_too_large"));
    }

    @Test
    void refusesTinyAndCorruptImages() throws Exception {
        ImageService s = service();
        assertThatThrownBy(() -> s.storeUpload("d", png(200, 150)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("image_too_small"));
        byte[] corrupt = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8};
        assertThatThrownBy(() -> s.storeUpload("d", corrupt))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isIn("unreadable_image", "unsupported_image"));
    }

    @Test
    void storesThreeWidthsCroppedTo4by3AndIsIdempotent() throws Exception {
        ImageService s = service();
        byte[] upload = png(1600, 1600);
        String key = s.storeUpload("d", upload);
        assertThat(key).matches("d/[0-9a-f]{20}");
        assertThat(s.storeUpload("d", upload)).isEqualTo(key);
        for (int w : List.of(320, 640, 1024)) {
            byte[] jpg = s.store().get(key + "-" + w + ".jpg").orElseThrow();
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(jpg));
            assertThat(img.getWidth()).isEqualTo(w);
            assertThat(img.getHeight()).isEqualTo(w * 3 / 4);
            // re-encoded from pixels: no EXIF/APP1 segment survives
            assertThat(new String(jpg, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        }
        assertThat(s.url(key)).isEqualTo("/api/images/" + key);
    }

    @Test
    void appliesEveryExifOrientation() {
        // 3x2 image with a marked top-left pixel; check where it ends up for each orientation
        BufferedImage src = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, 0xFF0000);
        record Case(int orientation, int w, int h, int x, int y) {}
        for (Case c : List.of(new Case(1, 3, 2, 0, 0), new Case(2, 3, 2, 2, 0), new Case(3, 3, 2, 2, 1),
                new Case(4, 3, 2, 0, 1), new Case(5, 2, 3, 0, 0), new Case(6, 2, 3, 1, 0), new Case(7, 2, 3, 1, 2),
                new Case(8, 2, 3, 0, 2))) {
            BufferedImage out = ImageService.orient(src, c.orientation());
            assertThat(out.getWidth()).as("width %d", c.orientation()).isEqualTo(c.w());
            assertThat(out.getHeight()).as("height %d", c.orientation()).isEqualTo(c.h());
            assertThat(out.getRGB(c.x(), c.y()) & 0xFFFFFF).as("orientation %d", c.orientation()).isEqualTo(0xFF0000);
        }
    }

    @Test
    void demoArtIsDeterministicAndCoversEveryKind() {
        BufferedImage a = DishArt.render("Mapo Tofu", List.of("tofu"), 3, "sichuan");
        BufferedImage b = DishArt.render("Mapo Tofu", List.of("tofu"), 3, "sichuan");
        assertThat(a.getRGB(512, 384)).isEqualTo(b.getRGB(512, 384));
        assertThat(DishArt.classify("Steamed Fish Head with Chopped Chili", List.of())).isEqualTo(DishArt.Kind.FISH);
        assertThat(DishArt.classify("Hong Kong Milk Tea", List.of("drink"))).isEqualTo(DishArt.Kind.MILK_TEA);
        assertThat(DishArt.classify("Pork Xiaolongbao", List.of("dumplings"))).isEqualTo(DishArt.Kind.STEAMER);
        assertThat(DishArt.classify("Lanzhou Beef Noodle Soup", List.of("noodles", "soup"))).isEqualTo(DishArt.Kind.NOODLE_SOUP);
        for (DishArt.Kind k : DishArt.Kind.values()) {
            assertThat(k).isNotNull(); // every kind renders without throwing:
        }
        for (String name : List.of("Pork Dumplings", "Pan-Fried Dumplings", "Siu Mai", "Beef Noodle Soup", "Dan Dan Noodles",
                "Char Siu Rice", "Fish Congee", "Pork Stew", "Mapo Tofu", "Kung Pao Chicken", "Steamed Fish", "Green Beans",
                "Lamb Skewers", "Red-Braised Pork Belly", "Popcorn Chicken", "Rou Jia Mo Burger", "Egg Tarts",
                "Osmanthus Rice Cakes", "Milk Tea", "Lemon Tea", "Bubble Tea", "Oyster Omelette", "Turnip Cake", "Rice Noodle Rolls")) {
            assertThat(DishArt.render(name, List.of("peanuts", "seafood", "bbq"), 2, "cantonese").getWidth()).isEqualTo(DishArt.W);
        }
    }
}
