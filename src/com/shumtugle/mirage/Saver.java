package com.shumtugle.mirage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Puts photos into the phone's camera folder and reads them back upright. */
final class Saver {
    static final String FOLDER = "Mirage";

    private Saver() {
    }

    /** A name for a new photo; the suffix tells a variant from the shot itself. */
    static String name(String suffix) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return "IMG_" + stamp + (suffix == null ? "" : "_" + suffix) + ".jpg";
    }

    static Uri save(Context context, String name, byte[] jpeg) {
        return Build.VERSION.SDK_INT >= 29 ? saveScoped(context, name, jpeg) : saveLegacy(context, name, jpeg);
    }

    /** Android 10 and newer: the media store owns the file, no storage permission needed. */
    private static Uri saveScoped(Context context, String name, byte[] jpeg) {
        ContentResolver cr = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + FOLDER);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return null;
        OutputStream out = null;
        try {
            out = cr.openOutputStream(uri);
            if (out == null) throw new IllegalStateException();
            out.write(jpeg);
            out.close();
            out = null;
        } catch (Exception e) {
            closeQuietly(out);
            cr.delete(uri, null, null);
            return null;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /** Android 9 and older: a plain file in the camera folder, registered with the media store. */
    @SuppressWarnings("deprecation")
    private static Uri saveLegacy(Context context, String name, byte[] jpeg) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(jpeg);
            out.close();
            out = null;
        } catch (Exception e) {
            closeQuietly(out);
            f.delete();
            return null;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.Media.DATA, f.getPath());
        try {
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) return uri;
        } catch (Exception ignored) {
            // fall through to a plain scan
        }
        MediaScannerConnection.scanFile(context, new String[]{f.getPath()}, new String[]{"image/jpeg"}, null);
        return Uri.fromFile(f);
    }

    /** How far the bytes of a photo must be turned clockwise to stand upright, from its own tag. */
    static int exifRotation(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** The photo decoded and stood upright; sample 1 for full size, 2, 4... for smaller. */
    static Bitmap upright(byte[] jpeg, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = Math.max(1, sample);
        o.inMutable = true;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int r = exifRotation(jpeg);
        if (b == null || r == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(r);
        Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (t != b) b.recycle();
        return t;
    }

    /** A small, upright copy for the last-shot ring. */
    static Bitmap thumbnail(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
        return upright(jpeg, sample);
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try { out.close(); } catch (Exception ignored) { }
    }
}
