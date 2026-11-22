package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Brightness as a rail along the viewfinder's edge: a fine line and a glass
 * knob with a small sun that rides up for brighter and down for darker. It
 * stays faint while brightness is the camera's own and lights up when the
 * hand has moved it. When the rail itself is touched it is a slider, and a
 * double tap on it gives brightness back to the camera.
 */
final class Rail extends View {
    interface Listener {
        /** The finger has travelled this many thirds of a stop since it came down; up is positive. */
        void moved(int thirds);

        void released();

        void reset();
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rays = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Glass knob;
    private final RectF box = new RectF();
    private final float d;
    /** -1 at the bottom, 0 in the middle, 1 at the top. */
    private float value;
    private String label = "";
    private boolean lit;
    private boolean touchable = true;
    private Listener listener;
    private float downY;
    private int sent;
    private boolean held;
    private boolean travelled;
    private long lastTapAt;

    Rail(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        knob = new Glass(999f, Math.max(1f, d), 1.6f);
        sun.setColor(Palette.ACCENT_TEXT);
        rays.setColor(Palette.ACCENT_TEXT);
        rays.setStrokeWidth(1.3f * d);
        rays.setStrokeCap(Paint.Cap.ROUND);
        words.setColor(Palette.ACCENT_TEXT);
        words.setTypeface(Palette.mono);
        words.setTextSize(12.5f * c.getResources().getDisplayMetrics().scaledDensity);
        words.setTextAlign(Paint.Align.RIGHT);
        words.setShadowLayer(6f * d, 0, d, 0x99000000);
        setContentDescription("Brightness");
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Whether the rail answers a finger, or only shows. */
    void setTouchable(boolean t) {
        touchable = t;
    }

    /**
     * @param share where the knob stands, -1..1
     * @param text  the value as words, shown beside the knob while it is lit
     * @param on    lit when the hand has moved brightness
     */
    void show(float share, String text, boolean on) {
        value = Math.max(-1f, Math.min(1f, share));
        label = text == null ? "" : text;
        lit = on;
        setAlpha(lit || held ? 1f : 0.4f);
        invalidate();
    }

    private float knobR() {
        return 15f * d;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        line.setShader(new LinearGradient(0, knobR(), 0, h - knobR(),
                Palette.alpha(Palette.INK, 0.7f), Palette.alpha(Palette.INK, 0.15f), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = knobR();
        float cx = w - r - 2 * d;
        float top = r, bottom = h - r;
        box.set(cx - d, top, cx + d, bottom);
        c.drawRoundRect(box, d, d, line);
        float cy = top + (bottom - top) * (1f - (value + 1f) / 2f);
        knob.setBounds(Math.round(cx - r), Math.round(cy - r), Math.round(cx + r), Math.round(cy + r));
        knob.draw(c);
        float s = r * 0.32f;
        c.drawCircle(cx, cy, s, sun);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * s * 1.6f, cy + sin * s * 1.6f, cx + cos * s * 2.2f, cy + sin * s * 2.2f, rays);
        }
        if ((lit || held) && label.length() > 0) {
            c.drawText(label, cx - r - 8 * d, cy - (words.descent() + words.ascent()) / 2f, words);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!touchable) return false;
        float third = 28f * d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Only the strip along the edge is the rail; the rest of the view lets touches through.
                if (e.getX() < getWidth() - 48f * d) return false;
                downY = e.getY();
                travelled = false;
                sent = 0;
                held = true;
                setAlpha(1f);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int want = (int) ((downY - e.getY()) / third);
                if (want != 0) travelled = true;
                if (want != sent && listener != null) {
                    listener.moved(want);
                }
                sent = want;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                held = false;
                setAlpha(lit ? 1f : 0.4f);
                if (listener != null) listener.released();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !travelled) {
                    long now = e.getEventTime();
                    if (now - lastTapAt < 320 && listener != null) {
                        listener.reset();
                        lastTapAt = 0;
                    } else {
                        lastTapAt = now;
                    }
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
