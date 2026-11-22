package com.shumtugle.mirage;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The shutter: a ring that shimmers like air over a hot road, slowly turning,
 * a dark gap, and a disc that sinks when pressed. The disc is paper-white for
 * photos and red for video; while recording it becomes a rounded square, the
 * sign for stop, and the ring turns faster.
 */
final class Shutter extends View {
    private static final long SLOW_MS = 14000;
    private static final long FAST_MS = 2400;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private final Matrix turn = new Matrix();
    private SweepGradient shimmer;
    private ValueAnimator spin;
    private float angle;
    private boolean down;
    private boolean video;
    private boolean recording;

    Shutter(Context c) {
        super(c);
        gap.setColor(0xFF17130F);
        setClickable(true);
        setContentDescription("Shutter");
    }

    void setVideo(boolean v) {
        video = v;
        setContentDescription(video ? "Record" : "Take a photo");
        invalidate();
    }

    void setRecording(boolean r) {
        if (recording == r) return;
        recording = r;
        setContentDescription(r ? "Stop recording" : (video ? "Record" : "Take a photo"));
        startSpin();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shimmer = new SweepGradient(w / 2f, h / 2f,
                new int[]{0xFFFFC46B, 0xFFE0464F, 0xFF9C6ADE, 0xFF4FB3D9, 0xFFFFC46B}, null);
        ring.setShader(shimmer);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) spin.cancel();
        spin = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startSpin();
        } else if (spin != null) {
            spin.cancel();
            spin = null;
        }
    }

    /** One full turn of the ring, over and over; quicker while a clip records. */
    private void startSpin() {
        if (spin != null) spin.cancel();
        if (!isAttachedToWindow()) return;
        final float from = angle;
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(recording ? FAST_MS : SLOW_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                angle = (from + (Float) a.getAnimatedValue()) % 360f;
                invalidate();
            }
        });
        spin.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f;
        if (shimmer != null) {
            turn.setRotate(angle, cx, cy);
            shimmer.setLocalMatrix(turn);
        }
        c.drawCircle(cx, cy, r, ring);
        c.drawCircle(cx, cy, r * 0.93f, gap);
        disc.setColor(video ? Palette.ACCENT : Palette.INK);
        if (video && recording) {
            float h = r * 0.34f;
            square.set(cx - h, cy - h, cx + h, cy + h);
            c.drawRoundRect(square, h * 0.3f, h * 0.3f, disc);
        } else {
            float k = 0.76f;
            c.drawCircle(cx, cy, r * (down ? k - 0.05f : k), disc);
        }
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
