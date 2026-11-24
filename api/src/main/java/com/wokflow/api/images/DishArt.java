package com.wokflow.api.images;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Flat-lay illustrations for the demo menu, drawn from a dish's name, tags and spice level.
 * They are deterministic (seeded by the dish name), so every replica and every restart produces
 * the same picture and therefore the same content-addressed image key. Real restaurants upload
 * photos instead; this only keeps the seeded storefront from being a wall of text.
 */
public final class DishArt {
    public static final int W = 1024;
    public static final int H = 768;

    public enum Kind {
        DUMPLINGS, POTSTICKERS, STEAMER, NOODLE_SOUP, DRY_NOODLES, RICE, CONGEE, STEW, TOFU, CHILI_STIRFRY, FISH,
        GREENS, SKEWERS, BRAISED, FRIED, FLATBREAD, EGG_TARTS, RICE_CAKES, MILK_TEA, LEMON_TEA, BUBBLE_TEA, OMELETTE,
        DIM_SUM_FRIED, NOODLE_ROLLS
    }

    private DishArt() {}

    // ------------------------------------------------------------------ classification

    public static Kind classify(String name, List<String> tags) {
        String n = name.toLowerCase(Locale.ROOT);
        if (has(n, "bubble")) return Kind.BUBBLE_TEA;
        if (has(n, "lemon tea")) return Kind.LEMON_TEA;
        if (n.matches(".*\\btea\\b.*")) return Kind.MILK_TEA;
        if (has(n, "egg tart")) return Kind.EGG_TARTS;
        if (has(n, "rice cake", "osmanthus")) return Kind.RICE_CAKES;
        if (has(n, "omelette")) return Kind.OMELETTE;
        if (has(n, "noodle roll", "cheung fun")) return Kind.NOODLE_ROLLS;
        if (has(n, "turnip cake", "taro", "chicken feet")) return Kind.DIM_SUM_FRIED;
        if (has(n, "xiaolongbao", "soup dumpling", "siu mai", "bun", "bao", "har gow") || tags.contains("dim-sum")) return Kind.STEAMER;
        if (has(n, "pan-fried dumpling", "potsticker")) return Kind.POTSTICKERS;
        if (has(n, "wonton") && !has(n, "noodle")) return Kind.DUMPLINGS;
        if (has(n, "dumpling")) return Kind.DUMPLINGS;
        if (has(n, "congee", "porridge")) return Kind.CONGEE;
        if (has(n, "noodle soup", "paomo") || (tags.contains("noodles") && tags.contains("soup"))) return Kind.NOODLE_SOUP;
        if (tags.contains("noodles") || has(n, "noodle", "chow fun", "mian")) return Kind.DRY_NOODLES;
        if (has(n, "rice") || tags.contains("rice")) return Kind.RICE;
        if (has(n, "tofu")) return Kind.TOFU;
        if (has(n, "fish")) return Kind.FISH;
        if (has(n, "skewer")) return Kind.SKEWERS;
        if (has(n, "burger", "jia mo")) return Kind.FLATBREAD;
        if (has(n, "stew", "soup") || tags.contains("soup")) return Kind.STEW;
        if (has(n, "green bean", "pepper", "cucumber", "di san xian", "bok choy", "eggplant", "cabbage")) return Kind.GREENS;
        if (has(n, "popcorn", "crispy", "sweet and sour")) return Kind.FRIED;
        if (has(n, "braised", "belly", "smoked")) return Kind.BRAISED;
        return Kind.CHILI_STIRFRY;
    }

    private static boolean has(String n, String... words) {
        for (String w : words) {
            if (n.contains(w)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ entry point

    public static BufferedImage render(String name, List<String> tags, int spice, String cuisine) {
        Kind kind = classify(name, tags);
        Random r = new Random(name.hashCode() * 31L + kind.ordinal());
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        new Painter(g, r, spice, cuisine, tags).paint(kind);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ painter

    private static final class Painter {
        final Graphics2D g;
        final Random r;
        final int spice;
        final List<String> tags;
        final Color table;
        static final double CX = W * 0.47;
        static final double CY = H * 0.52;

        Painter(Graphics2D g, Random r, int spice, String cuisine, List<String> tags) {
            this.g = g;
            this.r = r;
            this.spice = spice;
            this.tags = tags;
            this.table = switch (cuisine == null ? "" : cuisine) {
                case "sichuan", "hunan" -> new Color(0x3a2a24);
                case "cantonese" -> new Color(0x2f3b3a);
                case "shanghainese" -> new Color(0x34363f);
                case "northwestern", "shaanxi" -> new Color(0x5a4030);
                case "taiwanese" -> new Color(0x2d3a2f);
                case "dongbei" -> new Color(0x4a3a2c);
                default -> new Color(0x3b3230);
            };
        }

        void paint(Kind k) {
            background();
            switch (k) {
                case DUMPLINGS -> { plate(300, new Color(0xf4f1ea), new Color(0x2f5d8a)); dumplings(false); sauceDish(); chopsticks(); }
                case POTSTICKERS -> { plate(300, new Color(0xf4f1ea), new Color(0x8a2f2f)); dumplings(true); sauceDish(); chopsticks(); }
                case STEAMER -> { steamer(); chopsticks(); }
                case NOODLE_SOUP -> { bowl(spice >= 2 ? new Color(0xb8401f) : new Color(0xc98a45)); noodleStrands(240, 0.55f); soupToppings(); chopsticks(); }
                case DRY_NOODLES -> { plate(300, new Color(0xf6f2ea), new Color(0x1f4e79)); noodleNest(); chopsticks(); }
                case RICE -> { plate(300, new Color(0xf7f3ec), new Color(0x7a5a2a)); rice(); chopsticks(); }
                case CONGEE -> { bowl(new Color(0xeee4cf)); congee(); spoon(); }
                case STEW -> { bowl(new Color(0x9a6a3a)); stew(); spoon(); }
                case TOFU -> { bowl(new Color(0xb5301b)); tofu(); spoon(); }
                case CHILI_STIRFRY -> { plate(300, new Color(0xf5f1ea), new Color(0x2f5d8a)); stirFry(); chopsticks(); }
                case FISH -> { platter(); fish(); chopsticks(); }
                case GREENS -> { plate(290, new Color(0xf5f2ec), new Color(0x2f6b4a)); greens(); chopsticks(); }
                case SKEWERS -> { board(); skewers(); }
                case BRAISED -> { plate(290, new Color(0xf4efe6), new Color(0x8a2f2f)); braised(); chopsticks(); }
                case FRIED -> { plate(290, new Color(0xf6f2ea), new Color(0x2f5d8a)); fried(); chopsticks(); }
                case FLATBREAD -> { board(); flatbread(); }
                case EGG_TARTS -> { plate(290, new Color(0xf6f2ea), new Color(0xb08a3a)); eggTarts(); }
                case RICE_CAKES -> { plate(280, new Color(0xf3efe8), new Color(0x2f5d8a)); riceCakes(); }
                case MILK_TEA -> { glass(new Color(0xc09060), false, false); }
                case LEMON_TEA -> { glass(new Color(0xb5652a), true, false); }
                case BUBBLE_TEA -> { glass(new Color(0xc8a07a), false, true); }
                case OMELETTE -> { plate(300, new Color(0xf6f2ea), new Color(0x2f5d8a)); omelette(); chopsticks(); }
                case DIM_SUM_FRIED -> { plate(290, new Color(0xf6f2ea), new Color(0x2f5d8a)); dimSumFried(); chopsticks(); }
                case NOODLE_ROLLS -> { plate(300, new Color(0xf6f2ea), new Color(0x2f5d8a)); noodleRolls(); chopsticks(); }
            }
            vignette();
        }

        // ---------------------------------------------------------- scene

        void background() {
            Color light = mix(table, Color.WHITE, 0.18);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(W * 0.3, H * 0.2), W * 0.9f,
                    new float[] {0f, 1f}, new Color[] {light, mix(table, Color.BLACK, 0.25)}));
            g.fillRect(0, 0, W, H);
            // linen weave: faint crossing lines
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < W + H; i += 6) {
                g.setColor(alpha(Color.WHITE, 6 + r.nextInt(6)));
                g.drawLine(i, 0, i - H, H);
                g.setColor(alpha(Color.BLACK, 6 + r.nextInt(6)));
                g.drawLine(i - H, 0, i, H);
            }
        }

        void vignette() {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(W / 2.0, H / 2.0), W * 0.75f,
                    new float[] {0.55f, 1f}, new Color[] {alpha(Color.BLACK, 0), alpha(Color.BLACK, 110)}));
            g.fillRect(0, 0, W, H);
        }

        void shadow(Shape s, double dx, double dy, int strength) {
            AffineTransform t = AffineTransform.getTranslateInstance(dx, dy);
            Shape moved = t.createTransformedShape(s);
            for (int i = 12; i >= 1; i--) {
                g.setColor(alpha(Color.BLACK, strength / 12));
                g.setStroke(new BasicStroke(i * 3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(moved);
            }
            g.setColor(alpha(Color.BLACK, strength));
            g.fill(moved);
        }

        void plate(double radius, Color body, Color rim) {
            Ellipse2D outer = circle(CX, CY, radius);
            shadow(outer, 14, 22, 70);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(CX - radius * 0.3, CY - radius * 0.35), (float) (radius * 1.4),
                    new float[] {0f, 1f}, new Color[] {Color.WHITE, mix(body, Color.GRAY, 0.12)}));
            g.fill(outer);
            g.setStroke(new BasicStroke(6f));
            g.setColor(alpha(rim, 170));
            g.draw(circle(CX, CY, radius - 18));
            g.setStroke(new BasicStroke(2f));
            g.setColor(alpha(rim, 110));
            g.draw(circle(CX, CY, radius - 30));
            g.setColor(alpha(Color.BLACK, 18));
            g.fill(circle(CX + 6, CY + 8, radius * 0.72));
            g.setColor(body);
            g.fill(circle(CX, CY, radius * 0.72));
        }

        void platter() {
            Shape outer = new Ellipse2D.Double(CX - 380, CY - 230, 760, 460);
            shadow(outer, 14, 22, 70);
            g.setPaint(new GradientPaint((float) CX, (float) (CY - 230), Color.WHITE, (float) CX, (float) (CY + 230), new Color(0xe7e2d8)));
            g.fill(outer);
            g.setStroke(new BasicStroke(5f));
            g.setColor(alpha(new Color(0x2f5d8a), 160));
            g.draw(new Ellipse2D.Double(CX - 360, CY - 212, 720, 424));
        }

        void bowl(Color broth) {
            double rOut = 290;
            Ellipse2D outer = circle(CX, CY, rOut);
            shadow(outer, 16, 24, 80);
            // glazed rim with a blue-and-white band
            g.setPaint(new RadialGradientPaint(new Point2D.Double(CX - 90, CY - 110), 420f, new float[] {0f, 1f},
                    new Color[] {Color.WHITE, new Color(0xd9d4ca)}));
            g.fill(outer);
            g.setStroke(new BasicStroke(10f));
            g.setColor(new Color(0x2f5d8a));
            g.draw(circle(CX, CY, rOut - 16));
            g.setStroke(new BasicStroke(3f));
            for (int i = 0; i < 28; i++) {
                double a = i * Math.PI * 2 / 28;
                double x = CX + Math.cos(a) * (rOut - 16);
                double y = CY + Math.sin(a) * (rOut - 16);
                g.setColor(new Color(0xf4f1ea));
                g.fill(circle(x, y, 4));
            }
            // broth with a soft highlight
            double rIn = rOut - 40;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(CX - rIn * 0.35, CY - rIn * 0.35), (float) (rIn * 1.5),
                    new float[] {0f, 0.7f, 1f}, new Color[] {mix(broth, Color.WHITE, 0.25), broth, mix(broth, Color.BLACK, 0.25)}));
            g.fill(circle(CX, CY, rIn));
            g.setColor(alpha(Color.BLACK, 40));
            g.setStroke(new BasicStroke(8f));
            g.draw(circle(CX, CY, rIn - 2));
        }

        void steamer() {
            double rad = 300;
            Ellipse2D outer = circle(CX, CY, rad);
            shadow(outer, 14, 22, 80);
            g.setColor(new Color(0xb98c4f));
            g.fill(outer);
            g.setStroke(new BasicStroke(3f));
            for (int i = 0; i < 9; i++) {
                g.setColor(i % 2 == 0 ? new Color(0x9c7238) : new Color(0xd3a866));
                g.draw(circle(CX, CY, rad - 4 - i * 3.2));
            }
            g.setColor(new Color(0xe8d9b6)); // parchment liner
            g.fill(circle(CX, CY, rad - 36));
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(alpha(new Color(0x8a6a3a), 60));
            for (int i = -10; i <= 10; i++) {
                g.drawLine((int) (CX + i * 26), (int) (CY - rad + 40), (int) (CX + i * 26), (int) (CY + rad - 40));
            }
            double[][] spots = {{0, 0}, {-130, -70}, {130, -70}, {-130, 80}, {130, 80}, {0, -150}, {0, 155}};
            boolean siuMai = tags.contains("seafood") && r.nextBoolean();
            for (double[] s : spots) {
                bun(CX + s[0] + r.nextGaussian() * 4, CY + s[1] + r.nextGaussian() * 4, 62, siuMai);
            }
        }

        void bun(double x, double y, double rad, boolean openTop) {
            shadow(circle(x, y, rad), 5, 8, 40);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x - rad * 0.3, y - rad * 0.35), (float) (rad * 1.5),
                    new float[] {0f, 1f}, new Color[] {new Color(0xfffaf0), new Color(0xe2d3b8)}));
            g.fill(circle(x, y, rad));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int pleats = 14;
            for (int i = 0; i < pleats; i++) {
                double a = i * Math.PI * 2 / pleats + r.nextDouble() * 0.1;
                Path2D p = new Path2D.Double();
                p.moveTo(x + Math.cos(a) * rad * 0.92, y + Math.sin(a) * rad * 0.92);
                p.quadTo(x + Math.cos(a + 0.6) * rad * 0.55, y + Math.sin(a + 0.6) * rad * 0.55,
                        x + Math.cos(a + 1.0) * rad * 0.12, y + Math.sin(a + 1.0) * rad * 0.12);
                g.setColor(alpha(new Color(0x9c8460), 120));
                g.draw(p);
            }
            if (openTop) {
                g.setColor(new Color(0xd9734a));
                g.fill(circle(x, y, rad * 0.22));
                g.setColor(new Color(0xf29a5c));
                g.fill(circle(x - 3, y - 3, rad * 0.1));
            } else {
                g.setColor(new Color(0xfffcf5));
                g.fill(circle(x - rad * 0.05, y - rad * 0.05, rad * 0.12));
            }
        }

        void board() {
            Shape b = new RoundRectangle2D.Double(CX - 400, CY - 230, 800, 460, 60, 60);
            shadow(b, 14, 22, 80);
            g.setPaint(new GradientPaint((float) (CX - 400), (float) CY, new Color(0xc49a62), (float) (CX + 400), (float) CY, new Color(0xa57a45)));
            g.fill(b);
            g.setStroke(new BasicStroke(2f));
            for (int i = 0; i < 26; i++) {
                double yy = CY - 210 + i * 17 + r.nextGaussian() * 2;
                g.setColor(alpha(new Color(0x7a5530), 50 + r.nextInt(40)));
                Path2D p = new Path2D.Double();
                p.moveTo(CX - 390, yy);
                p.curveTo(CX - 150, yy + r.nextGaussian() * 6, CX + 150, yy + r.nextGaussian() * 6, CX + 390, yy);
                g.draw(p);
            }
        }

        void chopsticks() {
            AffineTransform old = g.getTransform();
            g.translate(W * 0.86, H * 0.5);
            g.rotate(Math.toRadians(-14));
            for (int i = 0; i < 2; i++) {
                double off = i * 28;
                Shape s = new RoundRectangle2D.Double(off, -330, 16, 640, 14, 14);
                shadow(s, 8, 10, 40);
                g.setPaint(new GradientPaint(0, -330, new Color(0x4a2c1d), 0, 310, new Color(0x7a4a2e)));
                g.fill(s);
                g.setColor(new Color(0xb8453a));
                g.fill(new RoundRectangle2D.Double(off, -330, 16, 70, 14, 14));
            }
            g.setTransform(old);
        }

        void spoon() {
            AffineTransform old = g.getTransform();
            g.translate(W * 0.86, H * 0.42);
            g.rotate(Math.toRadians(10));
            Shape bowlPart = new Ellipse2D.Double(-46, -40, 92, 130);
            Shape handle = new RoundRectangle2D.Double(-14, 60, 28, 260, 24, 24);
            shadow(bowlPart, 8, 10, 40);
            shadow(handle, 8, 10, 40);
            g.setColor(new Color(0xf3efe7));
            g.fill(handle);
            g.fill(bowlPart);
            g.setColor(new Color(0x2f5d8a));
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Double(-36, -30, 72, 110));
            g.setTransform(old);
        }

        void sauceDish() {
            double x = W * 0.16;
            double y = H * 0.2;
            shadow(circle(x, y, 70), 8, 12, 50);
            g.setColor(new Color(0xf4f1ea));
            g.fill(circle(x, y, 70));
            g.setColor(new Color(0x2a1810));
            g.fill(circle(x, y, 52));
            g.setColor(alpha(new Color(0xc0392b), 180));
            for (int i = 0; i < 6 + spice * 4; i++) {
                g.fill(circle(x + r.nextGaussian() * 20, y + r.nextGaussian() * 20, 3 + r.nextDouble() * 4));
            }
            g.setColor(alpha(Color.WHITE, 90));
            g.fill(new Ellipse2D.Double(x - 30, y - 34, 26, 12));
        }

        // ---------------------------------------------------------- food

        void dumplings(boolean fried) {
            double[][] spots = {{-150, -115}, {0, -125}, {150, -115}, {-150, 5}, {0, -5}, {150, 5}, {-75, 120}, {75, 120}};
            for (double[] p : spots) {
                crescent(CX + p[0] + r.nextGaussian() * 5, CY + p[1] + r.nextGaussian() * 5, r.nextGaussian() * 0.15
                        + (fried ? 0 : (p[0] < 0 ? -0.12 : 0.12)), fried);
            }
            garnish(18, new Color(0x3d8b3d), 4);
        }

        void crescent(double x, double y, double angle, boolean fried) {
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(angle);
            Path2D body = new Path2D.Double();
            body.moveTo(-78, 10);
            body.curveTo(-70, -42, 70, -42, 78, 10);
            body.curveTo(50, 40, -50, 40, -78, 10);
            body.closePath();
            shadow(body, 4, 7, 45);
            Color base = fried ? new Color(0xe9c98d) : new Color(0xf6ecd8);
            g.setPaint(new GradientPaint(0, -36, mix(base, Color.WHITE, 0.4), 0, 34, fried ? new Color(0xb87a32) : new Color(0xd8c8a8)));
            g.fill(body);
            if (fried) {
                g.setColor(alpha(new Color(0x8a4f1c), 160));
                g.fill(new Ellipse2D.Double(-60, 6, 120, 26));
            }
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(new Color(0x9a8566), 150));
            for (int i = -4; i <= 4; i++) {
                double px = i * 15;
                g.draw(new Arc2D.Double(px - 9, -36 + Math.abs(i) * 2.2, 18, 18, 200, 140, Arc2D.OPEN));
            }
            g.setColor(alpha(Color.WHITE, 120));
            g.fill(new Ellipse2D.Double(-30, -20, 34, 10));
            g.setTransform(old);
        }

        void noodleStrands(double radius, float alphaTop) {
            g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 34; i++) {
                double y0 = CY - radius * 0.7 + i * radius * 1.4 / 34;
                Path2D p = new Path2D.Double();
                double x0 = CX - Math.sqrt(Math.max(0, radius * radius - (y0 - CY) * (y0 - CY))) * 0.8;
                p.moveTo(x0, y0);
                double len = (CX - x0) * 2;
                for (int s = 1; s <= 6; s++) {
                    double xs = x0 + len * s / 6;
                    p.quadTo(xs - len / 12, y0 + (s % 2 == 0 ? -14 : 14) + r.nextGaussian() * 4, xs, y0 + r.nextGaussian() * 3);
                }
                g.setColor(alpha(new Color(0xf0d898), (int) (255 * alphaTop) + r.nextInt(40)));
                g.draw(p);
            }
        }

        void soupToppings() {
            // beef slices
            for (int i = 0; i < 4; i++) {
                double a = -0.9 + i * 0.45;
                double x = CX + Math.cos(a) * 120;
                double y = CY + Math.sin(a) * 120 - 20;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(a + 0.4);
                Shape s = new Ellipse2D.Double(-58, -30, 116, 60);
                g.setPaint(new GradientPaint(0, -30, new Color(0x9a5a3a), 0, 30, new Color(0x5e3220)));
                g.fill(s);
                g.setColor(alpha(new Color(0xd8a07a), 140));
                g.setStroke(new BasicStroke(3f));
                g.draw(new Arc2D.Double(-50, -22, 100, 40, 20, 140, Arc2D.OPEN));
                g.setTransform(old);
            }
            // bok choy
            leaf(CX - 140, CY + 70, -0.6, 110, new Color(0x3f8f3a));
            leaf(CX - 100, CY + 120, -0.2, 100, new Color(0x4d9e45));
            // egg half
            if (r.nextBoolean()) {
                g.setColor(new Color(0xfbf7ee));
                g.fill(new Ellipse2D.Double(CX + 40, CY + 60, 110, 80));
                g.setColor(new Color(0xf2a93b));
                g.fill(circle(CX + 95, CY + 100, 26));
            }
            garnish(40, new Color(0x5aa64a), 5);
            chiliOil(spice);
        }

        void noodleNest() {
            Color sauce = spice >= 2 ? new Color(0xb8401f) : new Color(0x8a5a2a);
            g.setColor(alpha(sauce, 190));
            g.fill(blob(CX, CY, 200, 0.18));
            g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            boolean wide = r.nextBoolean();
            for (int i = 0; i < 46; i++) {
                double a0 = r.nextDouble() * Math.PI * 2;
                double rr = 40 + r.nextDouble() * 160;
                Path2D p = new Path2D.Double();
                p.moveTo(CX + Math.cos(a0) * rr, CY + Math.sin(a0) * rr * 0.9);
                for (int s = 1; s < 5; s++) {
                    double a = a0 + s * 0.45;
                    double rs = rr + r.nextGaussian() * 25;
                    p.quadTo(CX + Math.cos(a - 0.2) * (rs + 30), CY + Math.sin(a - 0.2) * (rs + 30) * 0.9,
                            CX + Math.cos(a) * rs, CY + Math.sin(a) * rs * 0.9);
                }
                g.setStroke(new BasicStroke(wide ? 22f : 11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(mix(new Color(0xf1dca0), sauce, 0.15 + r.nextDouble() * 0.25));
                g.draw(p);
            }
            // minced pork and greens on top
            for (int i = 0; i < 40; i++) {
                g.setColor(mix(new Color(0x6a3a22), new Color(0x9a5a35), r.nextDouble()));
                g.fill(circle(px(45), py(), 6 + r.nextDouble() * 6));
            }
            garnish(50, new Color(0x5aa64a), 5);
            chiliOil(spice);
            sesame(30);
        }

        void rice() {
            // rice mound on the left half
            for (int i = 0; i < 900; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(r.nextDouble()) * 150;
                double x = CX - 60 + Math.cos(a) * rr;
                double y = CY + Math.sin(a) * rr * 0.95;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(r.nextDouble() * Math.PI);
                int shade = 225 + r.nextInt(30);
                g.setColor(new Color(shade, shade, Math.min(255, shade - 6)));
                g.fill(new Ellipse2D.Double(-6, -2.6, 12, 5.2));
                g.setTransform(old);
            }
            // topping on the right: glossy braised or roast pork
            boolean charSiu = tags.contains("bbq");
            for (int i = 0; i < 7; i++) {
                double x = CX + 120 + r.nextGaussian() * 30;
                double y = CY - 110 + i * 34;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(-0.25 + r.nextGaussian() * 0.08);
                Shape s = new RoundRectangle2D.Double(-70, -18, 140, 36, 16, 16);
                shadow(s, 3, 5, 40);
                g.setPaint(new GradientPaint(0, -18, charSiu ? new Color(0xc0392b) : new Color(0x7a3e1e), 0, 18,
                        charSiu ? new Color(0x8e1f16) : new Color(0x4a2210)));
                g.fill(s);
                g.setColor(alpha(new Color(0xf2d8b0), charSiu ? 200 : 120));
                g.fill(new RoundRectangle2D.Double(-62, -6, 124, 10, 8, 8));
                g.setTransform(old);
            }
            leaf(CX - 40, CY + 170, 0.2, 90, new Color(0x3f8f3a));
            if (r.nextBoolean()) {
                g.setColor(new Color(0xc99a52));
                g.fill(new Ellipse2D.Double(CX - 150, CY - 170, 90, 64));
                g.setColor(new Color(0xf2a93b));
                g.fill(circle(CX - 105, CY - 138, 18));
            }
        }

        void congee() {
            g.setStroke(new BasicStroke(3f));
            for (int i = 0; i < 9; i++) {
                g.setColor(alpha(Color.WHITE, 60));
                g.draw(new Arc2D.Double(CX - 40 - i * 22, CY - 40 - i * 20, 80 + i * 44, 80 + i * 40, i * 37, 230, Arc2D.OPEN));
            }
            if (tags.contains("seafood")) {
                for (int i = 0; i < 5; i++) {
                    g.setColor(new Color(0xfbf8f2));
                    g.fill(blob(px(70), py(), 34, 0.25));
                }
            } else {
                for (int i = 0; i < 5; i++) {
                    double a = i * 1.25;
                    AffineTransform old = g.getTransform();
                    g.translate(CX + Math.cos(a) * 80, CY + Math.sin(a) * 70);
                    g.rotate(a);
                    g.setColor(new Color(0x2a2a20));
                    g.fill(new Arc2D.Double(-30, -30, 60, 60, 0, 120, Arc2D.PIE));
                    g.setColor(new Color(0x6a7a3a));
                    g.fill(new Arc2D.Double(-15, -15, 30, 30, 10, 100, Arc2D.PIE));
                    g.setTransform(old);
                }
            }
            garnish(60, new Color(0x5aa64a), 5);
            // fried dough sticks across the rim
            for (int i = 0; i < 3; i++) {
                AffineTransform old = g.getTransform();
                g.translate(CX + 150 + i * 30, CY + 150 - i * 20);
                g.rotate(-0.7);
                Shape s = new RoundRectangle2D.Double(-110, -16, 220, 32, 30, 30);
                shadow(s, 4, 6, 50);
                g.setPaint(new GradientPaint(0, -16, new Color(0xe8b060), 0, 16, new Color(0xa8661e)));
                g.fill(s);
                g.setTransform(old);
            }
        }

        void stew() {
            for (int i = 0; i < 9; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = r.nextDouble() * 150;
                Color c = i % 3 == 0 ? new Color(0xe9e1c8) : new Color(0x7a4428);
                g.setColor(c);
                g.fill(blob(CX + Math.cos(a) * rr, CY + Math.sin(a) * rr, 34 + r.nextDouble() * 16, 0.3));
            }
            for (int i = 0; i < 6; i++) {
                leaf(px(90), py(), r.nextDouble() * 3, 70, new Color(0xc9d28a));
            }
            garnish(40, new Color(0x5aa64a), 5);
            chiliOil(spice);
        }

        void tofu() {
            for (int i = 0; i < 16; i++) {
                double x = CX + (i % 4 - 1.5) * 78 + r.nextGaussian() * 8;
                double y = CY + (i / 4 - 1.5) * 74 + r.nextGaussian() * 8;
                if (Math.hypot(x - CX, y - CY) > 200) continue;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(r.nextGaussian() * 0.25);
                Shape s = new RoundRectangle2D.Double(-30, -30, 60, 60, 14, 14);
                shadow(s, 3, 4, 50);
                g.setPaint(new GradientPaint(-30, -30, new Color(0xfff6e3), 30, 30, new Color(0xe9cf9e)));
                g.fill(s);
                g.setColor(alpha(new Color(0xc0392b), 90));
                g.fill(new Ellipse2D.Double(-10, 6, 34, 18));
                g.setTransform(old);
            }
            for (int i = 0; i < 70; i++) {
                g.setColor(alpha(new Color(0x5a2a16), 200));
                g.fill(circle(px(110), py(), 4 + r.nextDouble() * 4));
            }
            garnish(50, new Color(0x5aa64a), 5);
            peppercorns(30);
            chiliOil(3);
        }

        void stirFry() {
            // golden-brown pieces
            for (int i = 0; i < 26; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(r.nextDouble()) * 170;
                Shape piece = blob(CX + Math.cos(a) * rr, CY + Math.sin(a) * rr, 26 + r.nextDouble() * 12, 0.35);
                shadow(piece, 2, 4, 35);
                g.setPaint(new GradientPaint(0, 0, new Color(0xc87a3a), 30, 30, new Color(0x8a4a20), true));
                g.fill(piece);
                g.setColor(alpha(Color.WHITE, 60));
                g.fill(circle(CX + Math.cos(a) * rr - 6, CY + Math.sin(a) * rr - 8, 6));
            }
            if (tags.contains("peanuts")) {
                for (int i = 0; i < 26; i++) {
                    g.setColor(new Color(0xd9a86a));
                    g.fill(new Ellipse2D.Double(px(110), py(), 26, 16));
                }
            }
            for (int i = 0; i < 8 + spice * 9; i++) {
                chili(px(130), py(), r.nextDouble() * Math.PI);
            }
            for (int i = 0; i < 10; i++) {
                g.setColor(new Color(0x4d9e45));
                AffineTransform old = g.getTransform();
                g.translate(px(120), py());
                g.rotate(r.nextDouble() * Math.PI);
                g.fill(new RoundRectangle2D.Double(-22, -6, 44, 12, 10, 10));
                g.setTransform(old);
            }
            sesame(40);
        }

        void fish() {
            AffineTransform old = g.getTransform();
            g.translate(CX, CY);
            Path2D body = new Path2D.Double();
            body.moveTo(-300, 0);
            body.curveTo(-200, -150, 150, -160, 250, -20);
            body.lineTo(330, -90);
            body.lineTo(320, 90);
            body.lineTo(250, 20);
            body.curveTo(150, 160, -200, 150, -300, 0);
            body.closePath();
            shadow(body, 6, 9, 50);
            g.setPaint(new GradientPaint(0, -150, new Color(0xeadfce), 0, 150, new Color(0xb8a68e)));
            g.fill(body);
            g.setTransform(old);
            Color topping = spice >= 3 ? new Color(0xd6331f) : new Color(0x7a9a3a);
            for (int i = 0; i < 260; i++) {
                double x = CX - 230 + r.nextDouble() * 430;
                double y = CY + r.nextGaussian() * 50;
                g.setColor(mix(topping, new Color(0xf25a2a), r.nextDouble() * 0.5));
                g.fill(new Ellipse2D.Double(x, y, 10 + r.nextDouble() * 8, 7 + r.nextDouble() * 5));
            }
            garnish(50, new Color(0x5aa64a), 5);
            g.setColor(alpha(new Color(0x7a3a10), 120));
            g.fill(new Ellipse2D.Double(CX - 330, CY + 150, 660, 40));
        }

        void greens() {
            boolean cucumber = tags.contains("gluten-free") && tags.contains("vegan");
            for (int i = 0; i < (cucumber ? 22 : 34); i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(r.nextDouble()) * 160;
                double x = CX + Math.cos(a) * rr;
                double y = CY + Math.sin(a) * rr;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(r.nextDouble() * Math.PI);
                if (cucumber) {
                    Shape s = new RoundRectangle2D.Double(-36, -22, 72, 44, 18, 18);
                    shadow(s, 2, 4, 35);
                    g.setColor(new Color(0x2f6b2a));
                    g.fill(s);
                    g.setColor(new Color(0xcfe6a8));
                    g.fill(new RoundRectangle2D.Double(-30, -14, 60, 28, 12, 12));
                } else {
                    Shape s = new RoundRectangle2D.Double(-75, -10, 150, 20, 20, 20);
                    shadow(s, 2, 4, 35);
                    g.setPaint(new GradientPaint(0, -10, new Color(0x6fb24a), 0, 10, new Color(0x2f6b2a)));
                    g.fill(s);
                    for (int k = 0; k < 3; k++) {
                        g.setColor(alpha(new Color(0x2a2a12), 150));
                        g.fill(new Ellipse2D.Double(-60 + r.nextDouble() * 110, -6, 14, 9));
                    }
                }
                g.setTransform(old);
            }
            garlic(24);
            for (int i = 0; i < spice * 8; i++) {
                chili(px(120), py(), r.nextDouble() * Math.PI);
            }
            sesame(30);
        }

        void skewers() {
            for (int i = 0; i < 5; i++) {
                AffineTransform old = g.getTransform();
                g.translate(CX - 40, CY - 160 + i * 80);
                g.rotate(-0.12);
                g.setColor(new Color(0xd9b98a));
                g.fill(new RoundRectangle2D.Double(-330, -4, 660, 8, 6, 6));
                for (int k = 0; k < 6; k++) {
                    Shape s = blob(-220 + k * 74, 0, 32, 0.25);
                    shadow(s, 3, 5, 45);
                    g.setPaint(new GradientPaint(0, -30, new Color(0xa0582c), 0, 30, new Color(0x5a2a14)));
                    g.fill(s);
                }
                for (int k = 0; k < 50; k++) {
                    g.setColor(k % 2 == 0 ? new Color(0xc0392b) : new Color(0x8a6a3a));
                    g.fill(circle(-240 + r.nextDouble() * 440, r.nextGaussian() * 14, 2.5));
                }
                g.setTransform(old);
            }
        }

        void braised() {
            for (int i = 0; i < 10; i++) {
                double x = CX + (i % 4 - 1.5) * 85 + r.nextGaussian() * 6;
                double y = CY + (i / 4 - 1) * 90 + r.nextGaussian() * 6;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(r.nextGaussian() * 0.2);
                Shape s = new RoundRectangle2D.Double(-36, -34, 72, 68, 14, 14);
                shadow(s, 3, 5, 55);
                g.setColor(new Color(0x7a2a12));
                g.fill(s);
                g.setColor(new Color(0xe8c9a0));
                g.fill(new RoundRectangle2D.Double(-36, -12, 72, 12, 6, 6));
                g.setColor(new Color(0x9a3a1a));
                g.fill(new RoundRectangle2D.Double(-36, 4, 72, 14, 6, 6));
                g.setColor(alpha(Color.WHITE, 90));
                g.fill(new Ellipse2D.Double(-24, -28, 26, 9));
                g.setTransform(old);
            }
            g.setColor(alpha(new Color(0x5a1a08), 150));
            g.fill(blob(CX, CY + 10, 210, 0.12));
            garnish(30, new Color(0x5aa64a), 5);
            for (int i = 0; i < spice * 8; i++) {
                chili(px(120), py(), r.nextDouble() * Math.PI);
            }
        }

        void fried() {
            boolean sweetSour = r.nextBoolean();
            for (int i = 0; i < 24; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(r.nextDouble()) * 160;
                Shape s = blob(CX + Math.cos(a) * rr, CY + Math.sin(a) * rr, 30 + r.nextDouble() * 10, 0.4);
                shadow(s, 2, 4, 40);
                g.setPaint(new GradientPaint(0, 0, sweetSour ? new Color(0xe07a2e) : new Color(0xe2a64a), 40, 40,
                        sweetSour ? new Color(0xa8401a) : new Color(0xb87420), true));
                g.fill(s);
                g.setColor(alpha(Color.WHITE, 90));
                g.fill(circle(CX + Math.cos(a) * rr - 8, CY + Math.sin(a) * rr - 8, 7));
            }
            for (int i = 0; i < 8; i++) {
                leaf(px(120), py(), r.nextDouble() * 3, 54, new Color(0x2f7a2a));
            }
            sesame(40);
        }

        void flatbread() {
            for (int i = 0; i < 2; i++) {
                double x = CX - 170 + i * 340;
                double y = CY + (i == 0 ? -20 : 20);
                Shape s = circle(x, y, 150);
                shadow(s, 8, 12, 60);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 40, y - 50), 220f, new float[] {0f, 1f},
                        new Color[] {new Color(0xf2d29a), new Color(0xc08a42)}));
                g.fill(s);
                g.setStroke(new BasicStroke(3f));
                g.setColor(alpha(new Color(0x8a5a22), 150));
                for (int k = 0; k < 5; k++) {
                    g.draw(circle(x, y, 30 + k * 22));
                }
                g.setColor(new Color(0x7a3a1a));
                g.fill(new Arc2D.Double(x - 150, y - 150, 300, 300, 200, 40, Arc2D.CHORD));
            }
            garnish(40, new Color(0x5aa64a), 5);
        }

        void eggTarts() {
            double[][] p = {{-110, -90}, {110, -90}, {-110, 100}, {110, 100}, {0, 5}};
            for (double[] o : p) {
                double x = CX + o[0];
                double y = CY + o[1];
                Shape crust = fluted(x, y, 92, 22);
                shadow(crust, 5, 8, 55);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 20, y - 25), 120f, new float[] {0f, 1f},
                        new Color[] {new Color(0xf0c070), new Color(0xb87a2a)}));
                g.fill(crust);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 15, y - 18), 90f, new float[] {0f, 1f},
                        new Color[] {new Color(0xffe27a), new Color(0xf2b536)}));
                g.fill(circle(x, y, 68));
                for (int k = 0; k < 4; k++) {
                    g.setColor(alpha(new Color(0x9a5a1a), 150));
                    g.fill(circle(x + r.nextGaussian() * 25, y + r.nextGaussian() * 25, 6 + r.nextDouble() * 8));
                }
            }
        }

        void riceCakes() {
            for (int i = 0; i < 9; i++) {
                double x = CX + (i % 3 - 1) * 120 + r.nextGaussian() * 6;
                double y = CY + (i / 3 - 1) * 110 + r.nextGaussian() * 6;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(Math.PI / 4 + r.nextGaussian() * 0.05);
                Shape s = new RoundRectangle2D.Double(-46, -46, 92, 92, 18, 18);
                shadow(s, 3, 5, 40);
                g.setPaint(new GradientPaint(-46, -46, new Color(0xfffdf6), 46, 46, new Color(0xe9e2d2)));
                g.fill(s);
                g.setTransform(old);
            }
            g.setColor(alpha(new Color(0xc98a1a), 160));
            g.fill(blob(CX, CY, 70, 0.3));
            for (int i = 0; i < 90; i++) {
                g.setColor(new Color(0xf2b62e));
                g.fill(circle(px(130), py(), 3 + r.nextDouble() * 3));
            }
        }

        void glass(Color tea, boolean lemon, boolean boba) {
            double rad = 230;
            Shape outer = circle(CX, CY, rad);
            shadow(outer, 14, 22, 70);
            g.setColor(alpha(Color.WHITE, 110));
            g.fill(outer);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(CX - 60, CY - 70), 300f, new float[] {0f, 1f},
                    new Color[] {mix(tea, Color.WHITE, 0.25), mix(tea, Color.BLACK, 0.15)}));
            g.fill(circle(CX, CY, rad - 18));
            if (boba) {
                for (int i = 0; i < 40; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double rr = Math.sqrt(r.nextDouble()) * (rad - 50);
                    g.setColor(alpha(new Color(0x2a160c), 200));
                    g.fill(circle(CX + Math.cos(a) * rr, CY + Math.sin(a) * rr, 15));
                    g.setColor(alpha(Color.WHITE, 60));
                    g.fill(circle(CX + Math.cos(a) * rr - 4, CY + Math.sin(a) * rr - 4, 4));
                }
            }
            for (int i = 0; i < 5; i++) {
                AffineTransform old = g.getTransform();
                g.translate(px(80), py());
                g.rotate(r.nextDouble() * Math.PI);
                g.setColor(alpha(Color.WHITE, 90));
                g.fill(new RoundRectangle2D.Double(-36, -36, 72, 72, 16, 16));
                g.setColor(alpha(Color.WHITE, 150));
                g.setStroke(new BasicStroke(3f));
                g.draw(new RoundRectangle2D.Double(-36, -36, 72, 72, 16, 16));
                g.setTransform(old);
            }
            if (lemon) {
                for (int i = 0; i < 3; i++) {
                    double x = CX - 60 + i * 60;
                    double y = CY - 40 + i * 30;
                    g.setColor(new Color(0xf7e15a));
                    g.fill(circle(x, y, 58));
                    g.setColor(new Color(0xfff6b8));
                    g.fill(circle(x, y, 50));
                    g.setStroke(new BasicStroke(3f));
                    g.setColor(new Color(0xf2d23a));
                    for (int k = 0; k < 8; k++) {
                        double a = k * Math.PI / 4;
                        g.drawLine((int) x, (int) y, (int) (x + Math.cos(a) * 48), (int) (y + Math.sin(a) * 48));
                    }
                }
            }
            // straw
            g.setColor(boba ? new Color(0x2f5d8a) : new Color(0xc0392b));
            g.fill(circle(CX + 70, CY - 60, 22));
            g.setColor(mix(tea, Color.BLACK, 0.4));
            g.fill(circle(CX + 70, CY - 60, 13));
            // rim highlight
            g.setStroke(new BasicStroke(6f));
            g.setColor(alpha(Color.WHITE, 160));
            g.draw(new Arc2D.Double(CX - rad + 10, CY - rad + 10, rad * 2 - 20, rad * 2 - 20, 100, 80, Arc2D.OPEN));
        }

        void omelette() {
            Shape egg = blob(CX, CY, 210, 0.15);
            shadow(egg, 4, 6, 40);
            g.setColor(new Color(0xf2d16a));
            g.fill(egg);
            for (int i = 0; i < 18; i++) {
                g.setColor(alpha(Color.WHITE, 150));
                g.fill(blob(px(110), py(), 26, 0.4));
            }
            for (int i = 0; i < 12; i++) {
                g.setColor(new Color(0x8a8070));
                g.fill(blob(px(100), py(), 22, 0.35));
            }
            g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0xe0607a));
            Path2D p = new Path2D.Double();
            p.moveTo(CX - 170, CY - 40);
            for (int i = 1; i <= 8; i++) {
                p.quadTo(CX - 170 + i * 42 - 21, CY - 40 + (i % 2 == 0 ? -40 : 40), CX - 170 + i * 42, CY - 40 + r.nextGaussian() * 10);
            }
            g.draw(p);
            leaf(CX + 120, CY + 120, 0.4, 100, new Color(0x3f8f3a));
        }

        void dimSumFried() {
            for (int i = 0; i < 6; i++) {
                double x = CX + (i % 3 - 1) * 130;
                double y = CY + (i / 3 - 0.5) * 140;
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(r.nextGaussian() * 0.1);
                Shape s = new RoundRectangle2D.Double(-55, -40, 110, 80, 18, 18);
                shadow(s, 3, 6, 50);
                g.setPaint(new GradientPaint(0, -40, new Color(0xf0c27a), 0, 40, new Color(0xb8782e)));
                g.fill(s);
                g.setColor(alpha(new Color(0x8a4f1c), 140));
                for (int k = 0; k < 6; k++) {
                    g.fill(circle(-40 + r.nextDouble() * 80, -26 + r.nextDouble() * 52, 4));
                }
                g.setTransform(old);
            }
            garnish(30, new Color(0x5aa64a), 5);
        }

        void noodleRolls() {
            g.setColor(alpha(new Color(0x5a2a10), 160));
            g.fill(blob(CX, CY, 220, 0.12));
            for (int i = 0; i < 3; i++) {
                AffineTransform old = g.getTransform();
                g.translate(CX, CY - 110 + i * 110);
                g.rotate(-0.08);
                Shape s = new RoundRectangle2D.Double(-200, -40, 400, 80, 60, 60);
                shadow(s, 4, 6, 50);
                g.setPaint(new GradientPaint(0, -40, new Color(0xfbf9f4), 0, 40, new Color(0xdcd6c8)));
                g.fill(s);
                g.setColor(alpha(new Color(0xf08a6a), 120));
                for (int k = 0; k < 5; k++) {
                    g.fill(new Ellipse2D.Double(-170 + k * 75, -14, 50, 28));
                }
                g.setTransform(old);
            }
            garnish(40, new Color(0x5aa64a), 5);
            sesame(30);
        }

        // ---------------------------------------------------------- garnish

        void garnish(int n, Color c, double size) {
            g.setStroke(new BasicStroke(3f));
            for (int i = 0; i < n; i++) {
                double x = px(120);
                double y = py();
                g.setColor(c);
                g.draw(circle(x, y, size + r.nextDouble() * 3));
                g.setColor(mix(c, Color.WHITE, 0.4));
                g.fill(circle(x, y, size * 0.5));
            }
        }

        void sesame(int n) {
            for (int i = 0; i < n; i++) {
                AffineTransform old = g.getTransform();
                g.translate(px(120), py());
                g.rotate(r.nextDouble() * Math.PI);
                g.setColor(new Color(0xf6ead0));
                g.fill(new Ellipse2D.Double(-4, -2, 8, 4));
                g.setTransform(old);
            }
        }

        void peppercorns(int n) {
            for (int i = 0; i < n; i++) {
                g.setColor(new Color(0x5a2a1a));
                g.fill(circle(px(120), py(), 5));
            }
        }

        void garlic(int n) {
            for (int i = 0; i < n; i++) {
                g.setColor(new Color(0xf3ead2));
                g.fill(blob(px(110), py(), 8, 0.3));
            }
        }

        void chili(double x, double y, double a) {
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(a);
            Path2D p = new Path2D.Double();
            p.moveTo(-34, 0);
            p.curveTo(-20, -16, 20, -12, 34, -2);
            p.curveTo(20, 10, -20, 14, -34, 0);
            p.closePath();
            g.setPaint(new GradientPaint(0, -12, new Color(0xe0301e), 0, 12, new Color(0x8e1508)));
            g.fill(p);
            g.setColor(new Color(0x3f6b2a));
            g.fill(new Ellipse2D.Double(-40, -5, 10, 10));
            g.setColor(alpha(Color.WHITE, 90));
            g.fill(new Ellipse2D.Double(-12, -9, 20, 4));
            g.setTransform(old);
        }

        void chiliOil(int level) {
            for (int i = 0; i < level * 22; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(r.nextDouble()) * 220;
                g.setColor(alpha(new Color(0xe25a1c), 120 + r.nextInt(80)));
                g.fill(circle(CX + Math.cos(a) * rr, CY + Math.sin(a) * rr, 4 + r.nextDouble() * 9));
            }
        }

        void leaf(double x, double y, double a, double len, Color c) {
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(a);
            Path2D p = new Path2D.Double();
            p.moveTo(-len / 2, 0);
            p.curveTo(-len / 4, -len / 3, len / 4, -len / 3, len / 2, 0);
            p.curveTo(len / 4, len / 3, -len / 4, len / 3, -len / 2, 0);
            p.closePath();
            g.setPaint(new GradientPaint(0, (float) (-len / 3), mix(c, Color.WHITE, 0.25), 0, (float) (len / 3), mix(c, Color.BLACK, 0.2)));
            g.fill(p);
            g.setStroke(new BasicStroke(2.5f));
            g.setColor(alpha(mix(c, Color.WHITE, 0.6), 200));
            g.drawLine((int) (-len / 2), 0, (int) (len / 2), 0);
            g.setTransform(old);
        }

        // ---------------------------------------------------------- scatter

        private double lastY;

        /** A gaussian-scattered point that stays on the plate (at most 205 px from the centre). */
        double px(double sigma) {
            for (;;) {
                double dx = r.nextGaussian() * sigma;
                double dy = r.nextGaussian() * sigma * 0.92;
                if (Math.hypot(dx, dy) <= 205) {
                    lastY = CY + dy;
                    return CX + dx;
                }
            }
        }

        double py() { return lastY; }

        // ---------------------------------------------------------- shapes

        Shape blob(double x, double y, double rad, double jitter) {
            GeneralPath p = new GeneralPath();
            int n = 9;
            double[] rs = new double[n];
            for (int i = 0; i < n; i++) {
                rs[i] = rad * (1 + r.nextGaussian() * jitter * 0.5);
            }
            for (int i = 0; i <= n; i++) {
                double a = i * Math.PI * 2 / n;
                double px = x + Math.cos(a) * rs[i % n];
                double py = y + Math.sin(a) * rs[i % n];
                if (i == 0) {
                    p.moveTo(px, py);
                } else {
                    double am = (i - 0.5) * Math.PI * 2 / n;
                    double rm = (rs[i % n] + rs[i - 1]) / 2 * 1.08;
                    p.quadTo(x + Math.cos(am) * rm, y + Math.sin(am) * rm, px, py);
                }
            }
            p.closePath();
            return p;
        }

        static Shape fluted(double x, double y, double rad, int flutes) {
            GeneralPath p = new GeneralPath();
            for (int i = 0; i <= flutes * 2; i++) {
                double a = i * Math.PI / flutes;
                double rr = i % 2 == 0 ? rad : rad * 0.92;
                double px = x + Math.cos(a) * rr;
                double py = y + Math.sin(a) * rr;
                if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            p.closePath();
            return p;
        }

        static Ellipse2D circle(double x, double y, double rad) {
            return new Ellipse2D.Double(x - rad, y - rad, rad * 2, rad * 2);
        }

        static Color alpha(Color c, int a) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
        }

        static Color mix(Color a, Color b, double t) {
            t = Math.max(0, Math.min(1, t));
            return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                    (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
        }
    }
}
