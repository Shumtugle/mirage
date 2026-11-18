package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Drawn over the viewfinder: the rule-of-thirds grid, when it is on. */
final class Overlay extends View {
    private final Paint line = new Paint();
    private boolean grid = true;

    Overlay(Context c) {
        super(c);
        line.setColor(Palette.alpha(Palette.INK, 0.42f));
        line.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.6f));
    }

    void setGrid(boolean on) {
        grid = on;
        invalidate();
    }

    boolean grid() {
        return grid;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!grid) return;
        float w = getWidth(), h = getHeight();
        for (int i = 1; i <= 2; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, line);
        }
    }
}
