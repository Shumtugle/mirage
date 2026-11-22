package com.shumtugle.mirage;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A pane of frosted glass: a rounded shape filled lighter at the top than at
 * the bottom, its edge brightest where the light falls on it from above.
 * What lies behind it is the caller's business; the pane only tints.
 */
final class Glass extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float radius;
    private final float stroke;
    private final float strength;

    /**
     * @param radius   corner radius in pixels; a large one makes a pill
     * @param stroke   edge width in pixels
     * @param strength 1 for the usual pane, more for a raised one, less for a faint one
     */
    Glass(float radius, float stroke, float strength) {
        this.radius = radius;
        this.stroke = stroke;
        this.strength = strength;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
    }

    private static int scaled(int color, float k) {
        int a = Math.min(255, Math.round((color >>> 24) * k));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                scaled(Palette.GLASS_TOP, strength), scaled(Palette.GLASS_BOTTOM, strength), Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(b.height(), radius * 2.2f + 1),
                scaled(Palette.GLASS_LIGHT, strength), scaled(Palette.GLASS_EDGE, strength * 0.6f), Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float h = stroke / 2f;
        box.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        float r = Math.min(radius, Math.min(box.width(), box.height()) / 2f);
        c.drawRoundRect(box, r, r, fill);
        c.drawRoundRect(box, r, r, edge);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        edge.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        edge.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
