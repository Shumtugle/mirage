package com.shumtugle.mirage;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Takes whatever room it is given and places its single child as the largest
 * box of the chosen shape that fits, centred. Lets the viewfinder grow on
 * tall screens and shrink on short ones without being stretched.
 */
final class FitBox extends FrameLayout {
    /** Width over height of the child. */
    private float ratio = 3f / 4f;
    /** Pressed to the top instead of centred. */
    private boolean top;

    FitBox(Context c) {
        super(c);
    }

    void setTop(boolean t) {
        top = t;
        requestLayout();
    }

    void setRatio(float widthOverHeight) {
        ratio = widthOverHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        if (getChildCount() == 0) return;
        w -= getPaddingLeft() + getPaddingRight();
        h -= getPaddingTop() + getPaddingBottom();
        int cw = w, ch = Math.round(w / ratio);
        if (ch > h) {
            ch = h;
            cw = Math.round(h * ratio);
        }
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;
        View child = getChildAt(0);
        int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
        int w = r - l - getPaddingLeft() - getPaddingRight();
        int h = b - t - getPaddingTop() - getPaddingBottom();
        int left = getPaddingLeft() + (w - cw) / 2, y = getPaddingTop() + (top ? 0 : (h - ch) / 2);
        child.layout(left, y, left + cw, y + ch);
    }
}
