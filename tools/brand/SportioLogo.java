import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Sportio logo generator.
 *
 * Letterforms come from the app's own Plus Jakarta Sans Variable font, so the wordmark is exactly
 * the typeface the UI renders. The wght axis is driven through Font.deriveFont(BOLD), which this
 * JDK maps onto the variable axis (stem 75 -> 106 units confirmed).
 *
 * Coordinate note: java glyph outlines already live at NEGATIVE y with the baseline at 0, which is
 * the orientation a y-down device space wants. So there is no flip anywhere, and cap height is -minY.
 *
 * Emits SVG (real quadratic segments), Android VectorDrawable XML, and PNG rasters.
 */
public class SportioLogo {

    // ============================================================ brand tokens
    static final String PITCH      = "#4FEE94"; // signature green, dark surfaces
    static final String PITCH_DEEP = "#0E6B41"; // signature green, light surfaces
    static final String FIELD      = "#134D2C"; // gradient end / accent container
    static final String CREAM      = "#EAF7EF"; // wordmark on dark
    static final String BALL       = "#F3FCF7"; // play triangle / lightest tone
    static final String INK        = "#081C11"; // wordmark on light
    static final String PAGE_DARK  = "#060B07";
    static final String PAGE_LIGHT = "#F4F8F4";
    static final int    GLOSS_ALPHA = 46;      // white overlay at the very top of the tile

    // ============================================================ type
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    static Font face800;
    static final String WORD = "Sportio";
    static final double TRACKING = -18;                 // -1.8 % of the em, per the guide
    /** Optical kerning the runtime does not apply, in font units. Key = pair before the index. */
    static final Map<Integer, Double> KERN = new LinkedHashMap<>();
    static {
        KERN.put(1, 10.0);   // S|p  open the cap-to-descender joint
        KERN.put(5, 12.0);   // t|i  the narrowest pair in the word
    }
    /** Wordmark letters that carry the accent colour: "Sport". */
    static final int ACCENT_LETTERS = 5;

    // ============================================================ mark geometry, 256-unit box
    static final double BOX = 256;
    static final double TILE_ARC = 0.225 * 2 * BOX;   // RoundRectangle2D takes ARC WIDTH
    static final double TW = 108, TH = 116;            // play triangle
    static final double TX = 76, TY = (BOX - TH) / 2;  // +2 px optical offset right

    // ============================================================ lockup proportions
    static final double CAP_RATIO  = 0.60;   // wordmark cap height / mark size
    static final double GAP_RATIO  = 0.24;   // mark -> wordmark gap / mark size

    static File dirSvg, dirPng, dirAndroid;

    public static void main(String[] args) throws Exception {
        File ttf = new File("app/src/main/res/font/plus_jakarta_sans_variable.ttf");
        face800 = Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont(1000f).deriveFont(Font.BOLD);

        // Run from the repo root: `java -cp out SportioLogo`.
        File root = new File("docs/brand/assets");
        dirSvg = mkdirs(root + "/svg");
        dirPng = mkdirs(root + "/png");
        dirAndroid = mkdirs(root + "/android");

        Run r = run();
        System.out.printf("wordmark ink: %.1f x %.1f  (cap %.1f, desc %.1f)%n",
                r.maxX - r.minX, r.maxY - r.minY, -r.minY, r.maxY);

        buildWordmarks(r);
        buildMarks();
        buildLockups(r);
        buildBanner(r);
        buildAndroid(r);
        renderBoard(r);
        int clipped = verifyRasters();

        System.out.println("\ndone -> " + root);
        if (clipped > 0) {
            System.out.println("*** " + clipped + " raster(s) have ink touching the canvas edge ***");
            System.exit(1);
        }
    }

    static File mkdirs(String p) { File f = new File(p); f.mkdirs(); return f; }

    /**
     * Scans every exported PNG for ink that touches the canvas edge, per the file's intent.
     *
     * A cropped logo is the one defect nobody catches by looking at a file list, and a glyph run
     * translated by the wrong baseline offset still produces a valid-looking image - just one
     * whose cap height is missing. Measuring the real alpha bounds turns that into a build
     * failure instead of a store-listing screenshot.
     *
     * Two files are legitimately full-bleed and must reach the edge: the launcher mark (an icon
     * with a margin is a broken icon) and the TV channel banner. Everything else - the wordmark
     * and the lockups - carries a clear space and must not touch a single edge.
     */
    static boolean fullBleed(String name) {
        return name.startsWith("sportio-mark-") || name.startsWith("sportio-banner-");
    }

    static int verifyRasters() throws Exception {
        System.out.println("\n  raster ink bounds:");
        int bad = 0;
        File[] files = dirPng.listFiles();
        if (files == null) return 0;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".png") || f.getName().startsWith("logo-board")) continue;
            BufferedImage im = ImageIO.read(f);
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            for (int y = 0; y < im.getHeight(); y++) {
                for (int x = 0; x < im.getWidth(); x++) {
                    if ((im.getRGB(x, y) >>> 24) > 8) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
            }
            if (x1 < 0) continue;
            boolean bleed = fullBleed(f.getName());
            boolean touch = x0 == 0 || y0 == 0 || x1 == im.getWidth() - 1 || y1 == im.getHeight() - 1;
            String verdict;
            if (bleed) {
                verdict = touch ? "ok (full bleed, as intended)"
                                : "*** SHOULD be full bleed ***";
                if (!touch) bad++;
            } else {
                verdict = touch ? "*** CROPPED at the canvas edge ***" : "ok (clear space " + x0 + " px)";
                if (touch) bad++;
            }
            System.out.printf("    %-38s %4dx%-4d ink %4d,%4d - %4d,%4d  %s%n",
                    f.getName(), im.getWidth(), im.getHeight(), x0, y0, x1, y1, verdict);
        }
        return bad;
    }

    // ============================================================ wordmark variants
    static void buildWordmarks(Run r) throws Exception {
        // two-tone master (dark surface)
        writeSvg(dirSvg + "/sportio-wordmark.svg", wordmarkSvg(r, PITCH, CREAM));
        writeSvg(dirSvg + "/sportio-wordmark-light.svg", wordmarkSvg(r, PITCH_DEEP, INK));
        writeSvg(dirSvg + "/sportio-wordmark-mono-white.svg", wordmarkSvg(r, "#FFFFFF", "#FFFFFF"));
        writeSvg(dirSvg + "/sportio-wordmark-mono-black.svg", wordmarkSvg(r, "#000000", "#000000"));
        writeSvg(dirSvg + "/sportio-wordmark-mono-green.svg", wordmarkSvg(r, PITCH, PITCH));

        rasterWordmark(r, PITCH, CREAM, 1024, dirPng + "/sportio-wordmark.png");
        rasterWordmark(r, PITCH_DEEP, INK, 1024, dirPng + "/sportio-wordmark-light.png");
        System.out.println("  wordmark x5 svg, x2 png");
    }

    static String wordmarkSvg(Run r, String accent, String body) {
        double w = r.maxX - r.minX, h = r.maxY - r.minY;
        double pad = 24;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"")
          .append(trim(-pad)).append(' ').append(trim(-pad)).append(' ')
          .append(trim(w + 2 * pad)).append(' ').append(trim(h + 2 * pad)).append("\"")
          .append(" role=\"img\" aria-label=\"Sportio\">\n")
          .append("  <title>Sportio</title>\n");
        StringBuilder accentPath = new StringBuilder(), bodyPath = new StringBuilder();
        for (int i = 0; i < r.shapes.size(); i++) {
            // baseline sits at -minY; the ink box then starts at y = 0
            String d = path(r.shapes.get(i), -r.minX + pad, -r.minY + pad);
            if (i < ACCENT_LETTERS) accentPath.append("    <path fill=\"").append(accent).append("\" d=\"").append(d).append("\"/>\n");
            else bodyPath.append("    <path fill=\"").append(body).append("\" d=\"").append(d).append("\"/>\n");
        }
        sb.append(accentPath).append(bodyPath);
        return sb.append("</svg>\n").toString();
    }

    static void rasterWordmark(Run r, String accent, String body, int widthPx, String out) throws Exception {
        double w = r.maxX - r.minX, h = r.maxY - r.minY;
        int pad = Math.max(8, (int) (widthPx * 0.02));
        int W = (int) Math.round(widthPx + 2 * pad), H = (int) Math.round(widthPx * h / w + 2 * pad);
        double s = widthPx / w;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hi(g);
        // Glyph outlines run from minY (the cap top, negative) down to maxY (the descender).
        // To land the INK box at [pad, pad+height] the origin must sit at pad - minY*s.
        // Using maxY here instead shifts the whole word down by (maxY - minY)*s and crops the cap.
        g.translate(pad - r.minX * s, pad - r.minY * s);
        g.scale(s, s);
        for (int i = 0; i < r.shapes.size(); i++) {
            g.setPaint(color(i < ACCENT_LETTERS ? accent : body));
            g.fill(r.shapes.get(i));
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    // ============================================================ the mark
    static Shape tileShape() {
        return new RoundRectangle2D.Double(0, 0, BOX, BOX, TILE_ARC, TILE_ARC);
    }

    /**
     * The soft gloss over the tile. Deliberately NOT clipped to a band: the alpha ramps to zero at
     * 62 % of the height, so there is no seam where the highlight used to stop.
     */
    static Shape glossShape() {
        return new RoundRectangle2D.Double(0, 0, BOX, BOX, TILE_ARC, TILE_ARC);
    }

    static Shape triangleShape() {
        return roundedTriangle(TX, TY, TW, TH, 14);
    }

    static void buildMarks() throws Exception {
        writeSvg(dirSvg + "/sportio-mark.svg", markSvg(PITCH, FIELD, BALL, true));
        writeSvg(dirSvg + "/sportio-mark-light.svg", markSvg(PITCH_DEEP, "#C8EFD8", FIELD, true));
        writeSvg(dirSvg + "/sportio-mark-mono.svg", markSvg("#FFFFFF", "#FFFFFF", PAGE_DARK, false));

        for (int px : new int[]{48, 72, 96, 192, 512}) {
            rasterMark(PITCH, FIELD, BALL, px, dirPng + "/sportio-mark-" + px + ".png");
        }
        System.out.println("  mark x3 svg, x5 png");
    }

    static String markSvg(String c1, String c2, String tri, boolean gloss) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 256 256\"")
          .append(" role=\"img\" aria-label=\"Sportio\">\n  <title>Sportio</title>\n");
        if (gloss) {
            sb.append("  <defs>\n")
              .append("    <linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
              .append("<stop offset=\"0\" stop-color=\"").append(c1).append("\"/>")
              .append("<stop offset=\"1\" stop-color=\"").append(c2).append("\"/></linearGradient>\n")
              .append("    <linearGradient id=\"s\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"").append(trim(BOX * 0.62))
              .append("\">")
              .append("<stop offset=\"0\" stop-color=\"#FFFFFF\" stop-opacity=\"")
              .append(trim(GLOSS_ALPHA / 255.0, 3)).append("\"/>")
              .append("<stop offset=\"1\" stop-color=\"#FFFFFF\" stop-opacity=\"0\"/></linearGradient>\n")
              .append("  </defs>\n");
        }
        sb.append("  <path fill=\"").append(gloss ? "url(#g)" : c1).append("\" d=\"")
          .append(path(tileShape(), 0, 0)).append("\"/>\n");
        if (gloss) sb.append("  <path fill=\"url(#s)\" d=\"").append(path(glossShape(), 0, 0)).append("\"/>\n");
        sb.append("  <path fill=\"").append(tri).append("\" d=\"").append(path(triangleShape(), 0, 0)).append("\"/>\n");
        return sb.append("</svg>\n").toString();
    }

    static void rasterMark(String c1, String c2, String tri, int px, String out) throws Exception {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hi(g);
        double s = px / BOX;
        g.scale(s, s);
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BOX, BOX),
                new float[]{0f, 1f}, new Color[]{color(c1), color(c2)}));
        g.fill(tileShape());
        if (px >= 72) paintGloss(g);
        g.setPaint(color(tri));
        g.fill(triangleShape());
        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    // ============================================================ lockups
    static void buildLockups(Run r) throws Exception {
        double markSize = BOX, cap = markSize * CAP_RATIO, gap = markSize * GAP_RATIO;
        double s = cap / (-r.minY);
        double wordW = (r.maxX - r.minX) * s, wordH = (r.maxY - r.minY) * s;
        double top = (BOX - cap) / 2 - 6;                 // optical centring against the descender
        // clear space = the x-height, on all four sides (guide 2.3)
        double pad = xHeight(r) * s;
        double W = BOX + gap + wordW + 2 * pad, H = BOX + 2 * pad;

        writeSvg(dirSvg + "/sportio-logo.svg",
                lockupSvg(r, PITCH, CREAM, s, gap, top, pad, W, H));
        writeSvg(dirSvg + "/sportio-logo-light.svg",
                lockupSvg(r, PITCH_DEEP, INK, s, gap, top, pad, W, H));
        writeSvg(dirSvg + "/sportio-logo-mono-white.svg",
                lockupSvg(r, "#FFFFFF", "#FFFFFF", s, gap, top, pad, W, H));
        writeSvg(dirSvg + "/sportio-logo-mono-black.svg",
                lockupSvg(r, "#000000", "#000000", s, gap, top, pad, W, H));

        // stacked: mark above, wordmark below - for square placements and the preview pane
        double sg = markSize * 0.22;
        double scap = -r.minY * (BOX / (r.maxX - r.minX));   // wordmark width == mark width
        double stackedW = BOX + 2 * pad;
        double stackedH = BOX + sg + scap + 2 * pad;
        writeSvg(dirSvg + "/sportio-logo-stacked.svg",
                stackSvg(r, PITCH, CREAM, sg, pad, stackedW, stackedH));

        rasterLockup(r, PITCH, CREAM, s, gap, top, pad, W, H, 1400, dirPng + "/sportio-logo.png");
        rasterLockup(r, PITCH_DEEP, INK, s, gap, top, pad, W, H, 1400, dirPng + "/sportio-logo-light.png");
        System.out.println("  lockup x4 svg + stacked, x2 png  (mark " + trim(BOX)
                + " | gap " + trim(gap) + " | cap " + trim(cap) + " | clearspace " + trim(pad) + ")");
    }

    /** The x-height of the wordmark, in font units - the guide's clear-space unit. */
    static double xHeight(Run r) {
        Rectangle2D b = face800.createGlyphVector(FRC, "x").getOutline().getBounds2D();
        return b.getHeight();
    }

    static String lockupSvg(Run r, String accent, String body, double s, double gap, double top,
                            double pad, double W, double H) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(trim(W)).append(' ')
          .append(trim(H)).append("\" role=\"img\" aria-label=\"Sportio\">\n  <title>Sportio</title>\n");
        sb.append(markDefs(PITCH, FIELD));
        // the clear space lives INSIDE the viewBox, so the artwork is inset by pad on every side
        sb.append("  <g transform=\"translate(").append(trim(pad)).append(' ').append(trim(pad)).append(")\">\n");
        sb.append(markBody(PITCH, FIELD, BALL));
        sb.append(wordmarkPaths(r, accent, body, BOX + gap, top, s));
        sb.append("  </g>\n");
        return sb.append("</svg>\n").toString();
    }

    static String stackSvg(Run r, String accent, String body, double sg, double pad,
                           double W, double H) {
        // In a stacked lockup the WORDMARK width matches the MARK width - it is not scaled to a
        // cap-height ratio, or the word would be 2.7x wider than the mark it sits under.
        double s = BOX / (r.maxX - r.minX);
        double cap = -r.minY * s;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(trim(W)).append(' ')
          .append(trim(H)).append("\" role=\"img\" aria-label=\"Sportio\">\n  <title>Sportio</title>\n");
        sb.append(markDefs(PITCH, FIELD));
        sb.append("  <g transform=\"translate(").append(trim(pad)).append(' ').append(trim(pad)).append(")\">\n");
        sb.append(markBody(PITCH, FIELD, BALL));
        // wordmark spans the mark's full width, centred on it
        sb.append(wordmarkPaths(r, accent, body, 0, BOX + sg, s));
        sb.append("  </g>\n");
        return sb.append("</svg>\n").toString();
    }

    /** The three mark paths, as SVG, at the origin of a 256-unit box. */
    static String markBody(String c1, String c2, String tri) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <path fill=\"url(#g)\" d=\"").append(path(tileShape(), 0, 0)).append("\"/>\n");
        sb.append("  <path fill=\"url(#s)\" d=\"").append(path(glossShape(), 0, 0)).append("\"/>\n");
        sb.append("  <path fill=\"").append(tri).append("\" d=\"").append(path(triangleShape(), 0, 0)).append("\"/>\n");
        return sb.toString();
    }

    /** The two gradient defs a mark needs: the tile face, and the gloss that sits on it. */
    static String markDefs(String c1, String c2) {
        return "  <defs>"
                + "<linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"
                + "<stop offset=\"0\" stop-color=\"" + c1 + "\"/>"
                + "<stop offset=\"1\" stop-color=\"" + c2 + "\"/></linearGradient>"
                + "<linearGradient id=\"s\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"" + trim(BOX * 0.62) + "\">"
                + "<stop offset=\"0\" stop-color=\"#FFFFFF\" stop-opacity=\"" + trim(GLOSS_ALPHA / 255.0, 3) + "\"/>"
                + "<stop offset=\"1\" stop-color=\"#FFFFFF\" stop-opacity=\"0\"/></linearGradient>"
                + "</defs>\n";
    }

    static String wordmarkPaths(Run r, String accent, String body, double ox, double oy, double s) {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < r.shapes.size(); i++) {
            String d = path(r.shapes.get(i), ox - r.minX * s, oy - r.minY * s, s);
            if (i < ACCENT_LETTERS) a.append("  <path fill=\"").append(accent).append("\" d=\"").append(d).append("\"/>\n");
            else b.append("  <path fill=\"").append(body).append("\" d=\"").append(d).append("\"/>\n");
        }
        return a.toString() + b.toString();
    }

    static void rasterLockup(Run r, String accent, String body, double s, double gap, double top,
                             double pad, double W, double H, int targetW, String out) throws Exception {
        double k = targetW / W;
        int pw = (int) Math.round(targetW), ph = (int) Math.round(H * k);
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hi(g);
        g.scale(k, k);
        g.translate(pad, pad);
        paintMarkAtOrigin(g, PITCH, FIELD, BALL);
        g.translate(BOX + gap - r.minX * s, top - r.minY * s);
        g.scale(s, s);
        for (int i = 0; i < r.shapes.size(); i++) {
            g.setPaint(color(i < ACCENT_LETTERS ? accent : body));
            g.fill(r.shapes.get(i));
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }

    // ============================================================ TV banner
    static void buildBanner(Run r) throws Exception {
        int BW = 320, BH = 180;
        double cap = 40, s = cap / (-r.minY);
        double markSize = 64, gap = markSize * GAP_RATIO;
        double left = 24, markY = BH / 2 - markSize / 2;
        double wordX = left + markSize + gap, wordTop = BH / 2 - cap / 2 - 3;

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(BW).append(' ').append(BH)
          .append("\" role=\"img\" aria-label=\"Sportio\">\n  <title>Sportio</title>\n")
          .append("  <defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
          .append("<stop offset=\"0\" stop-color=\"").append(PITCH).append("\"/>")
          .append("<stop offset=\"1\" stop-color=\"").append(FIELD).append("\"/></linearGradient>\n")
          .append("  <linearGradient id=\"mg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
          .append("<stop offset=\"0\" stop-color=\"").append(BALL).append("\"/>")
          .append("<stop offset=\"1\" stop-color=\"#9BF5BE\"/></linearGradient></defs>\n")
          .append("  <rect width=\"").append(BW).append("\" height=\"").append(BH).append("\" fill=\"url(#bg)\"/>\n");
        // mark, scaled into the banner
        double ms = markSize / BOX;
        sb.append("  <g transform=\"translate(").append(trim(left)).append(' ').append(trim(markY))
          .append(") scale(").append(trim(ms)).append(")\">\n");
        sb.append("    <path fill=\"url(#mg)\" d=\"").append(path(tileShape(), 0, 0)).append("\"/>\n");
        sb.append("    <path fill=\"").append(FIELD).append("\" d=\"").append(path(triangleShape(), 0, 0)).append("\"/>\n");
        sb.append("  </g>\n");
        sb.append(wordmarkPaths(r, "#FFFFFF", "#FFFFFF", wordX, wordTop, s));
        writeSvg(dirSvg + "/sportio-banner-320x180.svg", sb.append("</svg>\n").toString());

        // raster
        BufferedImage img = new BufferedImage(BW * 4, BH * 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hi(g);
        g.scale(4, 4);
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BW, BH),
                new float[]{0f, 1f}, new Color[]{color(PITCH), color(FIELD)}));
        g.fill(new Rectangle2D.Double(0, 0, BW, BH));
        AffineTransform sv = g.getTransform();
        g.translate(left, markY);
        g.scale(ms, ms);
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BOX, BOX),
                new float[]{0f, 1f}, new Color[]{color(BALL), color("#9BF5BE")}));
        g.fill(tileShape());
        g.setPaint(color(FIELD));
        g.fill(triangleShape());
        g.setTransform(sv);
        g.translate(wordX - r.minX * s, wordTop - r.minY * s);
        g.scale(s, s);
        g.setPaint(Color.WHITE);
        for (Shape sh : r.shapes) g.fill(sh);
        g.dispose();
        ImageIO.write(img, "png", new File(dirPng + "/sportio-banner-320x180@4x.png"));
        System.out.println("  banner svg + png");
    }

    // ============================================================ android vector drawables
    static void buildAndroid(Run r) throws Exception {
        // mark, flat fill so it needs no gradient support
        String v = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<!-- Sportio mark. Generated from docs/brand/assets/svg/sportio-mark.svg -->\n"
                + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:width=\"108dp\"\n"
                + "    android:height=\"108dp\"\n"
                + "    android:viewportWidth=\"256\"\n"
                + "    android:viewportHeight=\"256\">\n"
                + "    <path\n"
                + "        android:fillColor=\"" + FIELD + "\"\n"
                + "        android:pathData=\"" + path(tileShape(), 0, 0) + "\" />\n"
                + "    <path\n"
                + "        android:fillColor=\"" + BALL + "\"\n"
                + "        android:pathData=\"" + path(triangleShape(), 0, 0) + "\" />\n"
                + "</vector>\n";
        write(dirAndroid + "/sportio_mark.xml", v);

        double cap = 128, s = cap / (-r.minY);
        double w = (r.maxX - r.minX) * s, h = (r.maxY - r.minY) * s;
        String vw = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<!-- Sportio wordmark. Two-tone: \"Sport\" in pitch green, \"io\" in ink. -->\n"
                + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:width=\"" + trim(Math.round(w)) + "dp\"\n"
                + "    android:height=\"" + trim(Math.round(h)) + "dp\"\n"
                + "    android:viewportWidth=\"" + trim(Math.round(w)) + "\"\n"
                + "    android:viewportHeight=\"" + trim(Math.round(h)) + "\">\n";
        for (int i = 0; i < r.shapes.size(); i++) {
            vw += "    <path\n"
                + "        android:fillColor=\"" + (i < ACCENT_LETTERS ? PITCH_DEEP : INK) + "\"\n"
                + "        android:pathData=\"" + path(r.shapes.get(i), -r.minX * s, -r.minY * s, s) + "\" />\n";
        }
        write(dirAndroid + "/sportio_wordmark.xml", vw + "</vector>\n");
        System.out.println("  android x2");
    }

    // ============================================================ presentation board
    static void renderBoard(Run r) throws Exception {
        final int W = 1800, H = 1120, M = 70;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hi(g);
        g.setPaint(new GradientPaint(0, 0, color(PAGE_DARK), 0, H, color("#0D1C13")));
        g.fillRect(0, 0, W, H);

        final int colW = (W - 3 * M) / 2;
        heading(g, "Sportio", M, 78);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        g.setPaint(color("#7E9C88"));
        g.drawString("Logo system v1.0  -  Plus Jakarta Sans ExtraBold 800, tracking -1.8%", M, 106);

        int y = 150;
        // --- row 1: the primary lockup, dark and light ---
        double markSize = 116, cap = markSize * CAP_RATIO, gap = markSize * GAP_RATIO;
        double s = cap / (-r.minY);

        card(g, M, y, colW, 196, PAGE_DARK);
        paintLockup(g, r, M + 44, y + 40, markSize, PITCH, CREAM, s, gap);
        caption(g, "Primary lockup on #060B07", M + 24, y + 178, "#7E9C88");

        card(g, M * 2 + colW, y, colW, 196, PAGE_LIGHT);
        paintLockup(g, r, M * 2 + colW + 44, y + 40, markSize, PITCH_DEEP, INK, s, gap);
        caption(g, "Light variant on #F4F8F4", M * 2 + colW + 24, y + 178, "#61786A");

        y += 232;
        // --- row 2: the mark at launcher sizes + the wordmark alone ---
        card(g, M, y, colW, 196, PAGE_DARK);
        caption(g, "The mark at launcher sizes", M + 24, y + 32, "#7E9C88");
        int mx = M + 30;
        for (int px : new int[]{96, 72, 48, 32, 24}) {
            paintMark(g, mx, y + 50, px);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setPaint(color("#61786A"));
            String s2 = String.valueOf(px);
            g.drawString(s2, (float) (mx + px / 2.0 - g.getFontMetrics().stringWidth(s2) / 2.0), y + 176);
            mx += px + 26;
        }

        card(g, M * 2 + colW, y, colW, 196, PAGE_DARK);
        caption(g, "Wordmark, mark removed", M * 2 + colW + 24, y + 32, "#7E9C88");
        paintWordmark(g, r, M * 2 + colW + 40, y + 128, cap, PITCH, CREAM);

        y += 232;
        // --- row 3: stacked, banner, mono ---
        card(g, M, y, colW, 196, PAGE_DARK);
        caption(g, "Stacked", M + 24, y + 32, "#7E9C88");
        paintStacked(g, r, M + 40, y + 48, 76, PITCH, CREAM);

        card(g, M * 2 + colW, y, colW, 196, PAGE_DARK);
        caption(g, "Mono white - over video or on green", M * 2 + colW + 24, y + 32, "#7E9C88");
        paintLockup(g, r, M * 2 + colW + 46, y + 118, 80, "#FFFFFF", "#FFFFFF",
                80 * CAP_RATIO / (-r.minY), 80 * GAP_RATIO);

        y += 232;
        // --- row 4: the TV banner at true size, plus over a still ---
        card(g, M, y, colW, 196, "#0E1A12");
        caption(g, "Android TV banner, 320 x 180", M + 24, y + 32, "#7E9C88");
        paintBanner(g, r, M + 24, y + 48, colW - 48, 150);

        card(g, M * 2 + colW, y, colW, 196, "#12261C");
        caption(g, "Over a still, with a scrim", M * 2 + colW + 24, y + 32, "#7E9C88");
        paintOverPhoto(g, r, M * 2 + colW + 24, y + 48, colW - 48, 132);

        g.dispose();
        ImageIO.write(img, "png", new File(dirPng + "/logo-board.png"));
        System.out.println("  logo-board.png " + W + "x" + H);
    }

    static void caption(Graphics2D g, String t, double x, double y, String hex) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setPaint(color(hex));
        g.drawString(t, (float) x, (float) y);
    }

    /** Paints a lockup with (x, y) at the vertical centre of the mark. */
    static void paintLockup(Graphics2D g, Run r, double x, double centreY, double markSize,
                            String accent, String body, double s, double gap) {
        double cap = markSize * CAP_RATIO;
        AffineTransform sv = g.getTransform();
        g.translate(x, centreY - markSize / 2);
        g.scale(markSize / BOX, markSize / BOX);
        paintMarkAtOrigin(g, PITCH, FIELD, BALL);
        g.setTransform(sv);
        paintWordmark(g, r, x + markSize + gap, centreY - cap / 2 - 3, cap, accent, body);
    }

    /** Paints the wordmark with (x, y) at the top of the cap height. */
    static void paintWordmark(Graphics2D g, Run r, double x, double capTop, double cap,
                              String accent, String body) {
        double s = cap / (-r.minY);
        AffineTransform sv = g.getTransform();
        g.translate(x - r.minX * s, capTop - r.minY * s);
        g.scale(s, s);
        for (int i = 0; i < r.shapes.size(); i++) {
            g.setPaint(color(i < ACCENT_LETTERS ? accent : body));
            g.fill(r.shapes.get(i));
        }
        g.setTransform(sv);
    }

    static void paintStacked(Graphics2D g, Run r, double x, double y, double markSize,
                             String accent, String body) {
        double gap = markSize * 0.22;
        double s = (markSize / BOX) * (BOX / (r.maxX - r.minX));   // wordmark width == mark width
        double cap = -r.minY * s;
        AffineTransform sv = g.getTransform();
        g.translate(x, y);
        g.scale(markSize / BOX, markSize / BOX);
        paintMarkAtOrigin(g, PITCH, FIELD, BALL);
        g.setTransform(sv);
        paintWordmark(g, r, x, y + markSize + gap, cap, accent, body);
    }

    static void paintBanner(Graphics2D g, Run r, double x, double y, double w, double h) {
        final int BW = 320, BH = 180, markSize = 64, cap = 40, left = 24;
        final double s = cap / (-r.minY);
        final double gap = markSize * GAP_RATIO;
        AffineTransform sv = g.getTransform();
        g.translate(x, y);
        g.scale(w / BW, h / BH);
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BW, BH),
                new float[]{0f, 1f}, new Color[]{color(PITCH), color(FIELD)}));
        g.fill(new Rectangle2D.Double(0, 0, BW, BH));
        // mark, in banner units
        g.translate(left, BH / 2.0 - markSize / 2.0);
        g.scale(markSize / BOX, markSize / BOX);
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BOX, BOX),
                new float[]{0f, 1f}, new Color[]{color(BALL), color("#9BF5BE")}));
        g.fill(tileShape());
        g.setPaint(color(FIELD));
        g.fill(triangleShape());
        // wordmark, still in banner units - the whole banner is one coordinate system
        g.setTransform(sv);
        g.translate(x, y);
        g.scale(w / BW, h / BH);
        g.translate(left + markSize + gap - r.minX * s, BH / 2.0 - cap / 2.0 - 3 - r.minY * s);
        g.scale(s, s);
        g.setPaint(Color.WHITE);
        for (Shape sh : r.shapes) g.fill(sh);
        g.setTransform(sv);
    }

    /** The lockup over a photograph stand-in, to show the scrim rule working. */
    static void paintOverPhoto(Graphics2D g, Run r, double x, double y, double w, double h) {
        AffineTransform sv = g.getTransform();
        g.translate(x, y);
        // a busy, mid-tone backdrop: the worst case for a wordmark
        double bw = w / 6, bh = h / 3;
        for (int i = 0; i < 18; i++) {
            g.setPaint(new Color(30 + (i * 9) % 200, 70 + (i * 37) % 150, 40 + (i * 53) % 180));
            g.fill(new Rectangle2D.Double((i % 6) * bw, (i / 6) * bh, bw + 1, bh + 1));
        }
        g.setPaint(new Color(2, 6, 4, 158));   // 62 % scrim, per the guide
        g.fill(new Rectangle2D.Double(0, 0, w, h));
        g.setTransform(sv);
        double markSize = h * 0.52;
        paintLockup(g, r, x + 34, y + h / 2, markSize, "#FFFFFF", "#FFFFFF",
                markSize * CAP_RATIO / (-r.minY), markSize * GAP_RATIO);
    }

    static void paintMark(Graphics2D g, double x, double y, int px) {
        AffineTransform sv = g.getTransform();
        g.translate(x, y);
        g.scale(px / BOX, px / BOX);
        paintMarkAtOrigin(g, PITCH, FIELD, BALL);
        g.setTransform(sv);
    }

    static void paintGloss(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(0, 0), new Point2D.Double(0, BOX * 0.62),
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, GLOSS_ALPHA), new Color(255, 255, 255, 0)}));
        g.fill(glossShape());
    }

    static void paintMarkAtOrigin(Graphics2D g, String c1, String c2, String tri) {
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(BOX, BOX),
                new float[]{0f, 1f}, new Color[]{color(c1), color(c2)}));
        g.fill(tileShape());
        paintGloss(g);
        g.setPaint(color(tri));
        g.fill(triangleShape());
    }

    static void heading(Graphics2D g, String t, int x, int y) {
        Font f = new Font("SansSerif", Font.BOLD, 34);
        g.setFont(f);
        g.setPaint(color(CREAM));
        g.drawString(t, x, y);
    }

    static void card(Graphics2D g, double x, double y, double w, double h, String fill) {
        g.setPaint(color(fill));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
    }

    // ============================================================ geometry helpers
    static Shape roundedTriangle(double x, double y, double w, double h, double r) {
        double cy = y + h / 2;
        Point2D[] p = {
                new Point2D.Double(x, y),
                new Point2D.Double(x + w, cy),
                new Point2D.Double(x, y + h),
        };
        return round(p, r);
    }

    static Shape round(Point2D[] p, double r) {
        int n = p.length;
        Point2D[] in = new Point2D[n], out = new Point2D[n];
        for (int i = 0; i < n; i++) {
            Point2D prev = p[(i - 1 + n) % n], cur = p[i], next = p[(i + 1) % n];
            double d1 = cur.distance(prev), d2 = cur.distance(next);
            double rr = Math.min(r, Math.min(d1, d2) / 2);
            in[i] = lerp(cur, prev, rr / d1);
            out[i] = lerp(cur, next, rr / d2);
        }
        Path2D.Double path = new Path2D.Double();
        path.moveTo(out[0].getX(), out[0].getY());
        for (int k = 1; k <= n; k++) {
            int i = k % n;
            path.lineTo(in[i].getX(), in[i].getY());
            path.quadTo(p[i].getX(), p[i].getY(), out[i].getX(), out[i].getY());
        }
        path.closePath();
        return path;
    }

    static Point2D lerp(Point2D a, Point2D b, double t) {
        return new Point2D.Double(a.getX() + (b.getX() - a.getX()) * t, a.getY() + (b.getY() - a.getY()) * t);
    }

    static void hi(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    static Color color(String hex) {
        String h = hex.replace("#", "");
        return new Color(Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16));
    }

    // ============================================================ glyph runs
    static class Run {
        List<Shape> shapes = new ArrayList<>();
        double width, minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        Run(String text, double tracking, Map<Integer, Double> kern) {
            double x = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                GlyphVector gv = face800.createGlyphVector(FRC, String.valueOf(ch));
                Shape s = AffineTransform.getTranslateInstance(x, 0).createTransformedShape(gv.getOutline());
                shapes.add(s);
                Rectangle2D b = s.getBounds2D();
                if (b.getWidth() > 0 || b.getHeight() > 0) {
                    minX = Math.min(minX, b.getMinX()); maxX = Math.max(maxX, b.getMaxX());
                    minY = Math.min(minY, b.getMinY()); maxY = Math.max(maxY, b.getMaxY());
                }
                x += gv.getGlyphPosition(1).getX() + tracking + kern.getOrDefault(i, 0.0);
            }
            width = x - tracking;
        }
    }

    static Run run() { return new Run(WORD, TRACKING, KERN); }

    // ============================================================ svg path conversion
    /**
     * Java shape -> SVG path data. Java glyph outlines are already y-negative with the baseline at
     * 0, which is what a y-down space wants, so this only translates and scales, never flips.
     */
    static String path(Shape s, double ox, double oy) { return path(s, ox, oy, 1); }

    static String path(Shape s, double ox, double oy, double k) {
        StringBuilder sb = new StringBuilder();
        PathIterator pi = s.getPathIterator((AffineTransform) null);
        double[] c = new double[6];
        while (!pi.isDone()) {
            switch (pi.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> sb.append(f("M", c[0] * k + ox, c[1] * k + oy));
                case PathIterator.SEG_LINETO -> sb.append(f("L", c[0] * k + ox, c[1] * k + oy));
                case PathIterator.SEG_QUADTO -> sb.append(f("Q", c[0] * k + ox, c[1] * k + oy,
                        c[2] * k + ox, c[3] * k + oy));
                case PathIterator.SEG_CUBICTO -> sb.append(f("C", c[0] * k + ox, c[1] * k + oy,
                        c[2] * k + ox, c[3] * k + oy, c[4] * k + ox, c[5] * k + oy));
                case PathIterator.SEG_CLOSE -> sb.append('Z');
            }
            pi.next();
        }
        return sb.toString();
    }

    static String f(String cmd, double... v) {
        StringBuilder sb = new StringBuilder(cmd);
        for (double x : v) sb.append(' ').append(trim(x));
        return sb.append(' ').toString();
    }

    static String trim(double v) { return trim(v, 2); }

    static String trim(double v, int decimals) {
        String s = String.format(Locale.ROOT, "%." + decimals + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return "-0".equals(s) ? "0" : s;
    }

    static void writeSvg(String p, String content) throws Exception { write(p, content); }

    static void write(String p, String content) throws Exception {
        try (PrintWriter w = new PrintWriter(p, StandardCharsets.UTF_8)) { w.print(content); }
    }
}