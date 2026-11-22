package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Shared colors and type. A near-black ground lit by the viewfinder itself,
 * paper-white ink, frosted glass for everything that floats over the
 * picture, amber for values the hand has changed and red only for recording.
 * Type is one grotesque in three weights and its monospaced sibling for
 * numbers; both ship in assets with their licence.
 */
final class Palette {
    static final int BG = 0xFF0A0908;
    static final int SURFACE = 0xFF17130F;
    static final int RAISED = 0xFF262019;
    static final int LINE = 0xFF3A322A;
    static final int INK = 0xFFF4F1EC;
    static final int MUTED = 0xFFBDB6AC;
    static final int HINT = 0xFF8E877D;
    /** Recording, and nothing else. */
    static final int ACCENT = 0xFFE0464F;
    /** A value the hand has moved: brightness, steady, the default mark. */
    static final int ACCENT_TEXT = 0xFFFFC46B;
    static final int BRASS = ACCENT_TEXT;

    /** Frosted glass: a lighter top, a fainter bottom, a fine edge lit from above. */
    static final int GLASS_TOP = 0x29FFFFFF;
    static final int GLASS_BOTTOM = 0x0DFFFFFF;
    static final int GLASS_EDGE = 0x3DFFFFFF;
    static final int GLASS_LIGHT = 0x66FFFFFF;

    static Typeface display = Typeface.SANS_SERIF;
    static Typeface body = Typeface.SANS_SERIF;
    static Typeface bodyStrong = Typeface.DEFAULT_BOLD;
    static Typeface mono = Typeface.MONOSPACE;

    private static boolean loaded;

    private Palette() {
    }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        display = font(c, "fonts/geist.ttf", 200, Typeface.SANS_SERIF);
        body = font(c, "fonts/geist.ttf", 400, Typeface.SANS_SERIF);
        bodyStrong = font(c, "fonts/geist.ttf", 500, Typeface.DEFAULT_BOLD);
        mono = font(c, "fonts/geist-mono.ttf", 400, Typeface.MONOSPACE);
    }

    /** One weight of a variable font from the assets. */
    private static Typeface font(Context c, String path, int weight, Typeface fallback) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), path)
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The color with its alpha replaced, 0..1. */
    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }
}
