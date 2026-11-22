package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** The last photo, cut to a rounded square under a fine glass edge. */
final class Thumb extends View {
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap bitmap;

    Thumb(Context c) {
        super(c);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Palette.alpha(Palette.INK, 0.35f));
        empty.setColor(Palette.alpha(Palette.INK, 0.08f));
        setClickable(true);
        setContentDescription("Last shot");
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        image.setShader(b != null ? new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float s = Math.min(getWidth(), getHeight());
        float left = (getWidth() - s) / 2f, top = (getHeight() - s) / 2f;
        float r = 16f * d;
        box.set(left, top, left + s, top + s);
        if (bitmap != null) {
            // centre-crop the photo into the square
            float scale = s / Math.min(bitmap.getWidth(), bitmap.getHeight());
            m.setScale(scale, scale);
            m.postTranslate(left + s / 2f - bitmap.getWidth() * scale / 2f, top + s / 2f - bitmap.getHeight() * scale / 2f);
            image.getShader().setLocalMatrix(m);
            c.drawRoundRect(box, r, r, image);
        } else {
            c.drawRoundRect(box, r, r, empty);
        }
        edge.setStrokeWidth(Math.max(1f, d));
        float h = edge.getStrokeWidth() / 2f;
        box.inset(h, h);
        c.drawRoundRect(box, r, r, edge);
    }
}
