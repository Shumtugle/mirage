package com.shumtugle.mirage;

import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * The light of the picture: one automatic drum, and the numbers it finds.
 *
 * The automatic drum runs from the picture as it is to a sheet of paper made
 * white. Along it the black and white points are read from the picture's own
 * histogram, with more of the extremes let go the further it turns; a middle
 * that has wandered from grey is brought back by a curve; and from the middle
 * of the drum on, the brightest reds and blues are balanced so that white is
 * white. Should the picture be too light for its black point to come down,
 * the strength steps back by itself rather than break the picture.
 *
 * What it finds it writes into brightness, contrast, the curve and warmth;
 * brightness, contrast and warmth have drums of their own beside it, with
 * saturation, and a turn of the automatic drum writes them afresh. The
 * stops of the drum are not spaced evenly along it but by equal effect,
 * measured on pictures rather than chosen: neighbouring stops always give
 * different pictures.
 *
 * The light falls on the photograph and nothing else. Words on it keep their
 * colours: they lie on the picture but were not taken by the camera.
 *
 * Colour is mixed first, then everything else folds into one table for each
 * channel, so a pixel is touched once.
 */
final class Light {

    /** The stops of the automatic drum, from nothing done to a sheet of paper made white. */
    static final int[] STOPS = {0, 6, 30, 51, 65, 76, 88, 100};

    /** Where the automatic drum stands, as an index into the stops. */
    int stop;
    float br;
    float co;
    float ga;
    float te;
    float sat;

    Light copy() {
        Light l = new Light();
        l.stop = stop;
        l.br = br;
        l.co = co;
        l.ga = ga;
        l.te = te;
        l.sat = sat;
        return l;
    }

    boolean plain() {
        return br == 0f && co == 0f && ga == 0f && te == 0f && sat == 0f;
    }

    /** The automatic stop taken on a picture: its findings written into the numbers. */
    void auto(Bitmap small, int at) {
        stop = Math.max(0, Math.min(STOPS.length - 1, at));
        float[] f = autoOf(small, new RectF(0f, 0f, 1f, 1f), STOPS[stop]);
        br = f[0];
        co = f[1];
        ga = f[2];
        te = f[3];
    }

    /** The light laid on a picture in place, or onto another of the same size. */
    void onto(Bitmap src, Bitmap dst) {
        paint(src, dst, tables(br, co, ga, te), sat);
    }

    /** The automatic findings at a stop, from 0 to 100: brightness, contrast, curve, warmth. */
    static float[] autoOf(Bitmap b, RectF crop, int stopValue) {
        float[] none = {0f, 0f, 0f, 0f};
        float t = Math.max(0, Math.min(100, stopValue)) / 100f;
        if (t <= 0f) {
            return none;
        }
        float k = 0.15f + 0.85f * t;
        float paper = Math.max(0f, (t - 0.5f) * 2f);
        int w = b.getWidth();
        int h = b.getHeight();
        int x0 = Math.max(0, (int) Math.floor(crop.left * w));
        int y0 = Math.max(0, (int) Math.floor(crop.top * h));
        int x1 = Math.min(w, (int) Math.ceil(crop.right * w));
        int y1 = Math.min(h, (int) Math.ceil(crop.bottom * h));
        if (x1 <= x0 || y1 <= y0) {
            return none;
        }
        /* Every pixel need not be asked: a quarter of a million tell the same story. */
        long all = (long) (x1 - x0) * (y1 - y0);
        int stride = Math.max(1, (int) Math.round(Math.sqrt(all / 250000.0)));
        int[] lum = new int[256];
        int[] red = new int[256];
        int[] blue = new int[256];
        int n = 0;
        int[] row = new int[x1 - x0];
        for (int y = y0; y < y1; y += stride) {
            b.getPixels(row, 0, x1 - x0, x0, y, x1 - x0, 1);
            for (int x = 0; x < row.length; x += stride) {
                int p = row[x];
                int r = (p >> 16) & 255;
                int g = (p >> 8) & 255;
                int bl = p & 255;
                int l = (int) (r * 0.2126f + g * 0.7152f + bl * 0.0722f);
                lum[l > 255 ? 255 : l]++;
                red[r]++;
                blue[bl]++;
                n++;
            }
        }
        if (n == 0) {
            return none;
        }
        float[] strengths = {k, 0.65f, 0.50f, 0.35f, 0.20f, 0.05f};
        float br = 0f;
        float co = 0f;
        float ga = 0f;
        for (int i = 0; i < strengths.length; i++) {
            if (strengths[i] > k) {
                continue;
            }
            float ks = strengths[i];
            double trim = 0.005 * Math.pow(10, (ks - 0.5) * 1.2);
            double hw = 0.10 * Math.pow(2, (0.5 - ks) * 2);
            double gaCap = 30 * Math.pow(2, (ks - 0.5) * 2);
            double cut = n * trim;
            int lo = 0;
            int hi = 255;
            int med = 128;
            long acc = 0;
            for (int j = 0; j < 256; j++) {
                acc += lum[j];
                if (acc > cut) {
                    lo = j;
                    break;
                }
            }
            acc = 0;
            for (int j = 255; j >= 0; j--) {
                acc += lum[j];
                if (acc > cut) {
                    hi = j;
                    break;
                }
            }
            acc = 0;
            for (int j = 0; j < 256; j++) {
                acc += lum[j];
                if (acc >= n / 2.0) {
                    med = j;
                    break;
                }
            }
            double l = lo / 255.0;
            double u = hi / 255.0;
            double m = med / 255.0;
            if (!(u > l)) {
                continue;
            }
            double s = 1.0 / (u - l);
            double off = -l / (u - l);
            double bb = s + 2 * off;
            if (!(bb > 0)) {
                continue;
            }
            double cc = s / bb;
            double gam = 0;
            if (m < 0.5 - hw || m > 0.5 + hw) {
                double mm = Math.max(0.02, Math.min(0.98, s * m + off));
                double g = Math.log(0.5) / Math.log(mm);
                if (g > 0) {
                    gam = Math.max(-gaCap, Math.min(gaCap, -100 * Math.log(g) / Math.log(2)));
                }
            }
            br = Math.round(Math.max(-100, Math.min(100, (bb - 1) * 100)));
            co = Math.round(Math.max(-100, Math.min(100, (cc - 1) * 100)));
            ga = Math.round(Math.max(-100, Math.min(100, gam)));
            break;
        }
        float te = 0f;
        if (paper > 0f) {
            /* White is white: the brightest reds and blues, a hundredth of the picture let go. */
            double cut = n * 0.01;
            int wr = 255;
            int wb = 255;
            long acc = 0;
            for (int j = 255; j >= 0; j--) {
                acc += red[j];
                if (acc > cut) {
                    wr = j;
                    break;
                }
            }
            acc = 0;
            for (int j = 255; j >= 0; j--) {
                acc += blue[j];
                if (acc > cut) {
                    wb = j;
                    break;
                }
            }
            if (wr > 0 && wb > 0) {
                float whole = Math.max(-100f, Math.min(100f, Math.round(220f * (wb - wr) / (float) (wb + wr))));
                te = Math.round(Math.max(-100f, Math.min(100f, whole * paper)));
            }
        }
        return new float[] {br, co, ga, te};
    }

    /**
     * The four numbers of the light as a table for each channel: brightness
     * and contrast as one straight line, the warmth leaning red against blue,
     * and the curve on top.
     */
    static int[][] tables(float br, float co, float ga, float te) {
        float b = 1f + br / 100f;
        float c = 1f + co / 100f;
        float t = te / 220f;
        float inter = b * 0.5f * (1f - c);
        float[] lean = {1f + t, 1f, 1f - t};
        double g = Math.pow(2, -ga / 100.0);
        int[][] out = new int[3][256];
        for (int ch = 0; ch < 3; ch++) {
            float slope = Math.max(0f, b * c * lean[ch]);
            for (int v = 0; v < 256; v++) {
                float x = Math.max(0f, Math.min(1f, slope * (v / 255f) + inter));
                out[ch][v] = Math.max(0, Math.min(255, (int) Math.round(Math.pow(x, g) * 255.0)));
            }
        }
        return out;
    }

    /**
     * Every pixel through the colour mix and then the tables, a strip at a
     * time; the picture may be its own target.
     */
    static void paint(Bitmap src, Bitmap dst, int[][] l, float saturation) {
        int w = src.getWidth();
        int h = src.getHeight();
        int rows = Math.max(1, 262144 / Math.max(1, w));
        int[] px = new int[w * rows];
        int[] r = l[0];
        int[] g = l[1];
        int[] b = l[2];
        boolean mix = saturation != 0f;
        float s = Math.max(0f, 1f + saturation / 100f);
        /* The mixing of a saturation, as the display's own filters mix it, in thousandths. */
        int rr = Math.round((0.213f + 0.787f * s) * 1024f);
        int rg = Math.round((0.715f - 0.715f * s) * 1024f);
        int rb = Math.round((0.072f - 0.072f * s) * 1024f);
        int gr = Math.round((0.213f - 0.213f * s) * 1024f);
        int gg = Math.round((0.715f + 0.285f * s) * 1024f);
        int gb = Math.round((0.072f - 0.072f * s) * 1024f);
        int br = Math.round((0.213f - 0.213f * s) * 1024f);
        int bg = Math.round((0.715f - 0.715f * s) * 1024f);
        int bb = Math.round((0.072f + 0.928f * s) * 1024f);
        for (int y = 0; y < h; y += rows) {
            int count = Math.min(rows, h - y);
            src.getPixels(px, 0, w, 0, y, w, count);
            int end = w * count;
            for (int i = 0; i < end; i++) {
                int p = px[i];
                int pr = (p >> 16) & 255;
                int pg = (p >> 8) & 255;
                int pb = p & 255;
                if (mix) {
                    int nr = (rr * pr + rg * pg + rb * pb) >> 10;
                    int ng = (gr * pr + gg * pg + gb * pb) >> 10;
                    int nb = (br * pr + bg * pg + bb * pb) >> 10;
                    pr = nr < 0 ? 0 : (nr > 255 ? 255 : nr);
                    pg = ng < 0 ? 0 : (ng > 255 ? 255 : ng);
                    pb = nb < 0 ? 0 : (nb > 255 ? 255 : nb);
                }
                px[i] = (p & 0xFF000000) | (r[pr] << 16) | (g[pg] << 8) | b[pb];
            }
            dst.setPixels(px, 0, w, 0, y, w, count);
        }
    }
}
