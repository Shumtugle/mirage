package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Drawn over the viewfinder: the view outside the frame dimmed, the frame's
 * brass corners, the rule-of-thirds grid inside the frame, and the point of
 * focus with its lock when there is one.
 */
final class Overlay extends View {
    private final Paint dim = new Paint();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF(0f, 0f, 1f, 1f);
    private final RectF px = new RectF();
    private boolean grid = true;
    private float focusX = -1f;
    private float focusY = -1f;
    private boolean locked;

    Overlay(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        dim.setColor(Palette.alpha(Palette.BG, 0.55f));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, d * 0.6f));
        edge.setColor(Palette.alpha(Palette.INK, 0.35f));
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(2f * d);
        corner.setStrokeCap(Paint.Cap.ROUND);
        corner.setColor(Palette.BRASS);
        line.setColor(Palette.alpha(Palette.INK, 0.42f));
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
            c.drawRect(0, 0, w, px.top, dim);
            c.drawRect(0, px.bottom, w, h, dim);
            c.drawRect(0, px.top, px.left, px.bottom, dim);
            c.drawRect(px.right, px.top, w, px.bottom, dim);
            c.drawRect(px, edge);
            float k = 18f * d;
            c.drawLine(px.left, px.top, px.left + k, px.top, corner);
            c.drawLine(px.left, px.top, px.left, px.top + k, corner);
            c.drawLine(px.right, px.top, px.right - k, px.top, corner);
            c.drawLine(px.right, px.top, px.right, px.top + k, corner);
            c.drawLine(px.left, px.bottom, px.left + k, px.bottom, corner);
            c.drawLine(px.left, px.bottom, px.left, px.bottom - k, corner);
            c.drawLine(px.right, px.bottom, px.right - k, px.bottom, corner);
            c.drawLine(px.right, px.bottom, px.right, px.bottom - k, corner);
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
