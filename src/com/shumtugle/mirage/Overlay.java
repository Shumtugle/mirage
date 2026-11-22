package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Drawn over the viewfinder: the view outside the frame dimmed, the frame as
 * a fine rounded line, the rule-of-thirds grid inside the frame, and the point
 * of focus with its lock when there is one.
 */
final class Overlay extends View {
    private final Paint dim = new Paint();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF(0f, 0f, 1f, 1f);
    private final RectF px = new RectF();
    private final android.graphics.Path shade = new android.graphics.Path();
    private boolean grid = true;
    private float focusX = -1f;
    private float focusY = -1f;
    private boolean locked;

    Overlay(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        dim.setColor(Palette.alpha(Palette.BG, 0.42f));
        dim.setAntiAlias(true);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, d * 0.6f));
        edge.setStrokeWidth(Math.max(1f, d));
        edge.setColor(Palette.alpha(Palette.INK, 0.6f));
        line.setColor(Palette.alpha(Palette.INK, 0.25f));
        line.setStrokeWidth(Math.max(1f, d * 0.6f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);
        ring.setColor(Palette.BRASS);
        badge.setColor(Palette.alpha(Palette.BG, 0.75f));
        label.setColor(Palette.BRASS);
        label.setTypeface(Palette.bodyStrong);
        label.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(0.08f);
    }

    void setGrid(boolean on) {
        grid = on;
        invalidate();
    }

    boolean grid() {
        return grid;
    }

    /** The frame as shares of the view's sides. */
    void setFrame(RectF r) {
        frame.set(r);
        invalidate();
    }

    void setFocus(float x, float y, boolean lock) {
        focusX = x;
        focusY = y;
        locked = lock;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        px.set(frame.left * w, frame.top * h, frame.right * w, frame.bottom * h);
        boolean whole = frame.width() > 0.999f && frame.height() > 0.999f;

        if (!whole) {
            float k = 18f * d;
            shade.reset();
            shade.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            shade.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
            shade.addRoundRect(px, k, k, android.graphics.Path.Direction.CW);
            c.drawPath(shade, dim);
            c.drawRoundRect(px, k, k, edge);
        }
        if (grid) {
            for (int i = 1; i <= 2; i++) {
                float x = px.left + px.width() * i / 3f;
                float y = px.top + px.height() * i / 3f;
                c.drawLine(x, px.top, x, px.bottom, line);
                c.drawLine(px.left, y, px.right, y, line);
            }
        }
        if (focusX >= 0f) {
            float fx = focusX * w, fy = focusY * h, r = 30f * d;
            c.drawCircle(fx, fy, r, ring);
            c.drawCircle(fx, fy, 2.5f * d, label);
            if (locked) {
                String t = "AE/AF LOCK";
                float tw = label.measureText(t) + 20f * d;
                float th = 22f * d;
                float by = fy - r - 10f * d - th;
                if (by < 6f * d) by = fy + r + 10f * d;
                RectF b = new RectF(fx - tw / 2f, by, fx + tw / 2f, by + th);
                c.drawRoundRect(b, th / 2f, th / 2f, badge);
                c.drawText(t, fx, b.centerY() - (label.descent() + label.ascent()) / 2f, label);
            }
        }
    }
}
