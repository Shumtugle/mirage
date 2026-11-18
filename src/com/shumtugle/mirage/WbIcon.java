package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** White balance as a ringed button: the ring and a small drawn sign for each mode. */
final class WbIcon extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private int mode;

    WbIcon(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(Palette.ACCENT_TEXT);
        fill.setColor(Palette.alpha(Palette.SURFACE, 0.6f));
        text.setColor(Palette.ACCENT_TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Palette.bodyStrong);
        setClickable(true);
    }

    void setMode(int m) {
        mode = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        float d = getResources().getDisplayMetrics().density;
        stroke.setStrokeWidth(2 * d);
        c.drawCircle(cx, cy, r - d, fill);
        c.drawCircle(cx, cy, r - 2 * d, stroke);
        float s = r * 0.42f;   // half size of the sign
        switch (mode) {
            case 1:   // daylight: a sun
                c.drawCircle(cx, cy, s * 0.42f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float x1 = cx + (float) Math.cos(a) * s * 0.68f, y1 = cy + (float) Math.sin(a) * s * 0.68f;
                    float x2 = cx + (float) Math.cos(a) * s, y2 = cy + (float) Math.sin(a) * s;
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
                break;
            case 2:   // cloudy: a cloud
                path.reset();
                box.set(cx - s, cy - s * 0.1f, cx - s * 0.2f, cy + s * 0.6f);
                path.addArc(box, 90, 180);
                box.set(cx - s * 0.55f, cy - s * 0.65f, cx + s * 0.35f, cy + s * 0.25f);
                path.arcTo(box, 190, 150);
                box.set(cx + s * 0.05f, cy - s * 0.35f, cx + s, cy + s * 0.6f);
                path.arcTo(box, 250, 200);
                path.close();
                c.drawPath(path, stroke);
                break;
            case 3:   // incandescent: a bulb
                c.drawCircle(cx, cy - s * 0.25f, s * 0.55f, stroke);
                c.drawLine(cx - s * 0.25f, cy + s * 0.5f, cx + s * 0.25f, cy + s * 0.5f, stroke);
                c.drawLine(cx - s * 0.2f, cy + s * 0.78f, cx + s * 0.2f, cy + s * 0.78f, stroke);
                break;
            case 4:   // fluorescent: a tube
                box.set(cx - s, cy - s * 0.18f, cx + s, cy + s * 0.18f);
                c.drawRoundRect(box, s * 0.18f, s * 0.18f, stroke);
                for (int i = -1; i <= 1; i++) {
                    c.drawLine(cx + i * s * 0.6f, cy - s * 0.45f, cx + i * s * 0.6f, cy - s * 0.75f, stroke);
                    c.drawLine(cx + i * s * 0.6f, cy + s * 0.45f, cx + i * s * 0.6f, cy + s * 0.75f, stroke);
                }
                break;
            default:  // auto
                text.setTextSize(s * 1.3f);
                c.drawText("A", cx, cy - (text.descent() + text.ascent()) / 2f, text);
                break;
        }
    }
}
