package com.wokflow.api.images;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.wokflow.api.common.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;

/**
 * Turns an uploaded photo into the three sizes the storefront uses.
 *
 * <ul>
 *   <li>The format is decided by the file's magic bytes, never by its name or Content-Type.</li>
 *   <li>Dimensions are read from the header before decoding, so a small file that claims to be a
 *       50 000 x 50 000 image (a decompression bomb) is refused before it can allocate gigabytes.</li>
 *   <li>EXIF orientation is applied (phone photos are often stored sideways), then the image is
 *       re-encoded from pixels, which also drops every byte of metadata, GPS position included.</li>
 *   <li>Names are a hash of the original bytes, so the same upload twice is the same files, two
 *       replicas processing it at once write identical bytes, and URLs can be cached forever.</li>
 * </ul>
 */
public class ImageService {
    public static final List<Integer> WIDTHS = List.of(320, 640, 1024);
    static final int MAX_SIDE = 8_000;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MIN_WIDTH = 400;
    static final int MIN_HEIGHT = 300;
    static final float QUALITY = 0.82f;

    enum Format { JPEG, PNG, WEBP }

    private final ImageStore store;
    private final String publicBase;

    public ImageService(ImageStore store, String publicBase) {
        this.store = store;
        this.publicBase = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
    }

    /** "d/3fa9…" -> "/api/images/d/3fa9…"; the client appends "-640.jpg". */
    public String url(String key) {
        return key == null ? null : publicBase + "/" + key;
    }

    public ImageStore store() { return store; }

    static Format sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return Format.JPEG;
        }
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Format.PNG;
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Format.WEBP;
        }
        return null;
    }

    /** Validates, normalizes and stores an upload; returns its key. */
    public String storeUpload(String prefix, byte[] bytes) {
        Format f = sniff(bytes);
        if (f == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_image", "Upload a JPEG, PNG or WebP photo");
        }
        BufferedImage img = decode(bytes);
        if (f == Format.JPEG) {
            img = orient(img, exifOrientation(bytes));
        }
        if (img.getWidth() < MIN_WIDTH || img.getHeight() < MIN_HEIGHT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "image_too_small",
                    "The photo must be at least " + MIN_WIDTH + " x " + MIN_HEIGHT + " pixels");
        }
        return storePixels(prefix, hash(bytes), img);
    }

    /** Stores generated artwork under a key derived from what it depicts (stable across restarts). */
    public String storeGenerated(String prefix, String seed, BufferedImage img) {
        return storePixels(prefix, hash(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)), img);
    }

    private String storePixels(String prefix, String hash, BufferedImage img) {
        String key = prefix + "/" + hash;
        if (store.exists(key + "-" + WIDTHS.getLast() + ".jpg")) {
            return key; // same content already processed (the largest size is written last)
        }
        BufferedImage cropped = cropTo(img, 4, 3);
        for (int w : WIDTHS) {
            store.put(key + "-" + w + ".jpg", jpeg(scale(cropped, Math.min(w, cropped.getWidth()))), "image/jpeg");
        }
        return key;
    }

    static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_image", "Upload a JPEG, PNG or WebP photo");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "image_too_large",
                            "The photo is " + w + " x " + h + " pixels; the limit is " + MAX_SIDE + " on each side");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unreadable_image", "The photo could not be read");
        }
    }

    static int exifOrientation(byte[] bytes) {
        try {
            var meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var dir = meta.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // no or broken EXIF: keep the pixels as stored
        }
        return 1;
    }

    /** EXIF orientations 1-8: the transform that makes the image upright. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    /** Centre crop to the storefront's card shape. */
    static BufferedImage cropTo(BufferedImage src, int aw, int ah) {
        int w = src.getWidth();
        int h = src.getHeight();
        int cw = w;
        int ch = w * ah / aw;
        if (ch > h) {
            ch = h;
            cw = h * aw / ah;
        }
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE); // transparent PNG areas become white, not black
        g.fillRect(0, 0, cw, ch);
        g.drawImage(src, -(w - cw) / 2, -(h - ch) / 2, null);
        g.dispose();
        return out;
    }

    /** Downscale in halving steps, then one bicubic pass: sharp without the aliasing of a single big jump. */
    static BufferedImage scale(BufferedImage src, int targetW) {
        BufferedImage cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        int targetH = Math.max(1, Math.round((float) h * targetW / w));
        while (w / 2 >= targetW) {
            w /= 2;
            h /= 2;
            cur = draw(cur, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return w == targetW ? cur : draw(cur, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static byte[] jpeg(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(QUALITY);
            p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
