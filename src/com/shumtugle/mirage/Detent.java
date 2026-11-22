package com.shumtugle.mirage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * A glass pill with a ball in it and three places for the ball. Left and
 * middle are two states the ball stays in; the right place is on a spring:
 * the ball can be pushed there, does not stay, and the push itself is the
 * action. The ball can be dragged, or the pill tapped where the ball should go.
 */
final class Detent extends View {
    static final int LEFT = 0;
    static final int MIDDLE = 1;

    interface Listener {
        void settled(int place);

        void sprung();
    }

    private final Glass track;
    private final Paint ball = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Listener listener;
    private final String[] words;
    private int place;
    /** Where the ball is drawn, 0 at the left place, 1 at the middle, 2 at the right. */
    private float at;
    private float downX;
    private float downAt;
    private boolean dragged;
    private ValueAnimator moving;

    /**
     * @param words what the left and the middle place mean, for those who listen to the screen
     */
    Detent(Context c, int startPlace, String[] words, Listener l) {
        super(c);
        listener = l;
        this.words = words;
        place = startPlace == LEFT ? LEFT : MIDDLE;
        at = place;
        float d = c.getResources().getDisplayMetrics().density;
        track = new Glass(999f, Math.max(1f, d), 1.1f);
        mark.setColor(Palette.alpha(Palette.INK, 0.55f));
        groove.setColor(Palette.alpha(Palette.BG, 0.45f));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, d * 0.8f));
        rim.setColor(Palette.alpha(Palette.INK, 0.18f));
        gear.setStyle(Paint.Style.STROKE);
        gear.setStrokeWidth(1.4f * d);
        gear.setStrokeCap(Paint.Cap.ROUND);
        gear.setColor(Palette.alpha(Palette.INK, 0.7f));
        setClickable(true);
        setFocusable(true);
        describe();
    }

    int place() {
        return place;
    }

    private void describe() {
        setContentDescription(words[place == LEFT ? 0 : 1]);
    }

    /** The groove: inset from the pill's edge, darker, with a fine line around it. */
    private float inset() {
        return getHeight() * 0.2f;
    }

    private float grooveHalf() {
        return getHeight() / 2f - inset();
    }

    /** The ball's centre at the left place. */
    private float x0() {
        return inset() + grooveHalf();
    }

    private float step() {
        return (getWidth() - 2 * x0()) / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        float w = getWidth();
        float r = h / 2f;
        track.setBounds(0, 0, Math.round(w), Math.round(h));
        track.draw(c);
        float g = inset();
        float gh = grooveHalf();
        box.set(g, g, w - g, h - g);
        c.drawRoundRect(box, gh, gh, groove);
        c.drawRoundRect(box, gh, gh, rim);
        float s = step();
        float x0 = x0();
        // The two resting places as small dots; the spring end as the settings mark.
        float dot = h * 0.045f;
        c.drawCircle(x0, r, dot, mark);
        c.drawCircle(x0 + s, r, dot, mark);
        drawGear(c, x0 + 2 * s, r, gh * 0.62f);
        float bx = x0 + at * s;
        float br = gh * 0.74f;
        ball.setShader(new RadialGradient(bx - br * 0.35f, r - br * 0.4f, br * 1.4f,
                lighter(Palette.ACCENT_TEXT), Palette.ACCENT_TEXT, Shader.TileMode.CLAMP));
        c.drawCircle(bx, r, br, ball);
    }

    /** A small cog: a ring, a hub and six teeth. */
    private void drawGear(Canvas c, float cx, float cy, float r) {
        c.drawCircle(cx, cy, r * 0.62f, gear);
        c.drawCircle(cx, cy, r * 0.22f, gear);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 0.62f, cy + sin * r * 0.62f, cx + cos * r, cy + sin * r, gear);
        }
    }

    private static int lighter(int color) {
        int a = color >>> 24;
        int red = Math.min(255, ((color >> 16) & 0xFF) + 60);
        int green = Math.min(255, ((color >> 8) & 0xFF) + 60);
        int blue = Math.min(255, (color & 0xFF) + 60);
        return (a << 24) | (red << 16) | (green << 8) | blue;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float s = step();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (moving != null) moving.cancel();
                downX = e.getX();
                downAt = at;
                dragged = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > s * 0.15f) dragged = true;
                if (dragged) {
                    at = Math.max(0, Math.min(2, downAt + (e.getX() - downX) / s));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float target = dragged ? at : (e.getX() - x0()) / s;
                if (target > 1.5f) {
                    spring();
                } else {
                    settle(target < 0.5f ? LEFT : MIDDLE);
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                slide(place, false, null);
                return true;
            default:
                return true;
        }
    }

    /** The ball runs to the right end, the action fires, and the ball comes back to where it stood. */
    private void spring() {
        slide(2, false, new Runnable() {
            @Override public void run() {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.sprung();
                slide(place, true, null);
            }
        });
    }

    private void settle(int p) {
        boolean changed = p != place;
        place = p;
        describe();
        slide(p, false, null);
        if (changed) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.settled(p);
        }
    }

    private void slide(float to, boolean bounce, final Runnable then) {
        if (moving != null) moving.cancel();
        moving = ValueAnimator.ofFloat(at, to);
        moving.setDuration(bounce ? 320 : 160);
        moving.setInterpolator(bounce ? new OvershootInterpolator(2.2f) : new DecelerateInterpolator());
        moving.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                at = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        if (then != null) {
            moving.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override public void onAnimationCancel(Animator a) {
                    cancelled = true;
                }

                @Override public void onAnimationEnd(Animator a) {
                    if (!cancelled) then.run();
                }
            });
        }
        moving.start();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
