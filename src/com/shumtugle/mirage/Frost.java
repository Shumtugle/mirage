package com.shumtugle.mirage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The frost under a pane of glass: a few pixels of the picture behind the
 * pane, stretched smooth over the pane's width and only as far down as the
 * picture reaches. As a background it never decides the pane's size.
 */
final class Frost extends Drawable {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private Bitmap bitmap;
    private int height;

    void set(Bitmap b, int reach) {
        bitmap = b;
        height = reach;
        invalidateSelf();
    }

    void clear() {
        bitmap = null;
        height = 0;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        if (bitmap == null || height <= 0) return;
        Rect b = getBounds();
        dst.set(b.left, b.top, b.right, Math.min(b.bottom, b.top + height));
        c.drawBitmap(bitmap, null, dst, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
