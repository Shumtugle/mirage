package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** The shutter: a brass ring, a dark gap, a cinnabar disc that sinks when pressed. */
final class Shutter extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean down;

    Shutter(Context c) {
        super(c);
        gap.setColor(Palette.BG);
        setClickable(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        ring.setShader(new SweepGradient(cx, cy,
                new int[]{0xFFF1CF86, 0xFFB07A2C, 0xFFF6DEA2, 0xFFA06D24, 0xFFF1CF86}, null));
        disc.setShader(new RadialGradient(cx - r * 0.15f, cy - r * 0.25f, r,
                new int[]{0xFFD65A46, Palette.ACCENT, 0xFF8E2A1E}, new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f;
        c.drawCircle(cx, cy, r, ring);
        c.drawCircle(cx, cy, r * 0.89f, gap);
        c.drawCircle(cx, cy, r * (down ? 0.74f : 0.78f), disc);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                invalidate();
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
