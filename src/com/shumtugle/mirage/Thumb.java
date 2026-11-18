package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** The last photo, cut to a circle inside a brass ring with a soft halo. */
final class Thumb extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;

    Thumb(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Palette.BRASS);
        halo.setColor(Palette.alpha(Palette.BRASS, 0.12f));
        empty.setColor(Palette.RAISED);
        setClickable(true);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        if (b != null) {
            image.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            image.setShader(null);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f;
        c.drawCircle(cx, cy, r, halo);
        float rr = r - 4 * d;
        ring.setStrokeWidth(2 * d);
        float inner = rr - 4 * d;
        if (bitmap != null) {
            // centre-crop the photo into the inner circle
            float scale = 2 * inner / Math.min(bitmap.getWidth(), bitmap.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - bitmap.getWidth() * scale / 2f, cy - bitmap.getHeight() * scale / 2f);
            image.getShader().setLocalMatrix(m);
            c.drawCircle(cx, cy, inner, image);
        } else {
            c.drawCircle(cx, cy, inner, empty);
        }
        c.drawCircle(cx, cy, rr, ring);
    }
}
