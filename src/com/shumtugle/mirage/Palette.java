package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Shared colors and type. A warm near-black ground, paper-colored text,
 * a cinnabar accent for the one action that matters and brass for rings
 * and the chosen state. Display type is a high-contrast serif, body type a
 * plain grotesque; both ship in assets with their licence.
 */
final class Palette {
    static final int BG = 0xFF120D09;
    static final int SURFACE = 0xFF21170F;
    static final int RAISED = 0xFF31221A;
    static final int LINE = 0xFF4A3426;
    static final int INK = 0xFFF3E7CF;
    static final int MUTED = 0xFFCDB8A0;
    static final int HINT = 0xFF95806B;
    static final int ACCENT = 0xFFB83A2B;
    static final int ACCENT_TEXT = 0xFFE8826C;
    static final int BRASS = 0xFFE0A84A;

    static Typeface display = Typeface.SERIF;
    static Typeface body = Typeface.SANS_SERIF;
    static Typeface bodyStrong = Typeface.DEFAULT_BOLD;

    private static boolean loaded;

    private Palette() {
    }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        display = font(c, "fonts/display.ttf", Typeface.SERIF);
        body = font(c, "fonts/text.ttf", Typeface.SANS_SERIF);
        bodyStrong = font(c, "fonts/text-strong.ttf", Typeface.DEFAULT_BOLD);
    }

    private static Typeface font(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The color with its alpha replaced, 0..1. */
    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }
}
