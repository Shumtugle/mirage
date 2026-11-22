package com.shumtugle.mirage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The darkroom behind the shutter. A shot is finished first \u2014 the frame cut
 * out when the camera could not cut it itself \u2014 and saved to the camera
 * folder at once. Then, on the same quiet thread, it is developed into a few
 * variants with the photo editor's own light and geometry: corrected light,
 * a brighter one, the horizon levelled, and light with the verticals stood up.
 * Every variant is saved beside the shot as soon as it is ready; nothing waits
 * for a hand. A copy stays in the app's cache for looking through, until the
 * next shot.
 */
final class Developer {

    interface Listener {
        /** The shot itself is in the camera folder. */
        void onSaved(Uri uri, Bitmap thumb);
        /** The roll changed: a variant is ready, or there will be none. */
        void onRoll();
        void onFailed();
    }

    /** One picture of the roll. */
    static final class Variant {
        final String name;
        final String suffix;
        File file;
        Uri kept;
        boolean ready;

        Variant(String name, String suffix) {
            this.name = name;
            this.suffix = suffix;
        }
    }

    /** The light stops taken from the editor's automatic drum: plain correction, then brighter. */
    private static final int FIX = 2;
    private static final int BRIGHT = 4;
    /** Long side of the picture the light and the lines are read from. */
    private static final int READING = 640;

    private static Developer instance;

    private final Context context;
    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Variant> roll = new ArrayList<Variant>();
    private Listener listener;
    private boolean developing;
    /** Whether a shot is developed into variants at all, or only saved as it is. */
    private volatile boolean variants = true;

    static synchronized Developer get(Context c) {
        if (instance == null) instance = new Developer(c.getApplicationContext());
        return instance;
    }

    private Developer(Context c) {
        context = c;
        dir = new File(c.getCacheDir(), "darkroom");
    }

    void setVariants(boolean on) {
        variants = on;
    }

    boolean variants() {
        return variants;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The current roll, the shot itself first. */
    synchronized List<Variant> roll() {
        return new ArrayList<Variant>(roll);
    }

    synchronized boolean developing() {
        return developing;
    }

    void develop(final CameraEngine.Shot shot) {
        worker.execute(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    process(shot);
                    log("DEVELOP done in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (OutOfMemoryError e) {
                    Journal.error(context, "develop, out of memory (heap " + heap() + ")", e);
                    finishRoll();
                } catch (RuntimeException e) {
                    Journal.error(context, "develop", e);
                    finishRoll();
                }
            }
        });
    }

    private void log(String text) {
        Journal.line(context, text);
    }

    private static String heap() {
        Runtime r = Runtime.getRuntime();
        return ((r.totalMemory() - r.freeMemory()) >> 20) + " of " + (r.maxMemory() >> 20) + " MB";
    }

    private void process(CameraEngine.Shot shot) {
        // 1. The shot itself, cut to the frame when the camera could not.
        byte[] original = shot.jpeg;
        Bitmap full = null;
        log("DEVELOP shot of " + shot.jpeg.length + " bytes, tag turns it " + Saver.exifRotation(shot.jpeg)
                + " degrees, lens " + Math.round(shot.lens35) + " mm, heap " + heap());
        if (shot.cut != null) {
            full = Saver.upright(shot.jpeg, 1);
            log("CUT after the shot: picture " + (full == null ? "not decoded" : full.getWidth() + "x" + full.getHeight())
                    + ", frame " + shot.cut.toShortString());
            if (full != null) {
                Bitmap cut = cut(full, shot.cut);
                if (cut != full) full.recycle();
                full = cut;
                original = encode(full);
            }
        }
        String name = Saver.name(null);
        final Uri uri = Saver.save(context, name, original);
        log("SAVED " + name + " -> " + uri);
        if (uri == null) {
            main.post(new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onFailed();
                }
            });
            if (full != null) full.recycle();
            return;
        }
        final Bitmap thumb = Saver.thumbnail(original);
        String stem = name.substring(0, name.length() - 4);

        // 2. A fresh roll: the shot, then the variants still to come.
        synchronized (this) {
            clearRoll();
            Variant asShot = new Variant("As shot", null);
            asShot.kept = uri;
            asShot.ready = true;
            asShot.file = writeCache(stem + ".jpg", original);
            roll.add(asShot);
            developing = true;
        }
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) {
                    listener.onSaved(uri, thumb);
                    listener.onRoll();
                }
            }
        });

        if (!variants) {
            log("VARIANTS off: the shot alone");
            if (full != null) full.recycle();
            finishRoll();
            return;
        }

        // 3. The readings, from a small copy.
        if (full == null) full = Saver.upright(original, 1);
        if (full == null) {
            finishRoll();
            return;
        }
        int w = full.getWidth(), h = full.getHeight();
        log("PICTURE " + w + "x" + h + " upright, heap " + heap());
        double k = Math.min(1.0, READING / (double) Math.max(w, h));
        Bitmap small = Bitmap.createScaledBitmap(full, Math.max(8, (int) Math.round(w * k)),
                Math.max(8, (int) Math.round(h * k)), true);
        Upright.Answer lines = readLines(small, shot.lens35);
        Light fix = new Light();
        fix.auto(small, FIX);
        Light bright = new Light();
        bright.auto(small, BRIGHT);
        small.recycle();
        log(String.format(java.util.Locale.US,
                "READ lines: %d segments, level %s, upright %s, square %s; light fix br %.1f co %.1f ga %.1f te %.1f, "
                        + "bright br %.1f co %.1f ga %.1f te %.1f",
                lines.segments, lines.level != null, lines.upright != null, lines.square != null,
                fix.br, fix.co, fix.ga, fix.te, bright.br, bright.co, bright.ga, bright.te));

        Pose level = null;
        if (lines.level != null) {
            level = new Pose();
            level.lens = shot.lens35;
            level.auto = lines.level;
        }
        Pose stood = null;
        double[] standing = lines.upright != null ? lines.upright : lines.level;
        if (standing != null) {
            stood = new Pose();
            stood.lens = shot.lens35;
            stood.auto = standing;
        }

        // 4. The variants, one at a time, each written out and let go before the next.
        produce(full, stem, "Fix", "fix", fix, null);
        produce(full, stem, "Bright", "bright", bright, null);
        if (level != null) produce(full, stem, "Level", "level", null, level);
        if (stood != null) {
            produce(full, stem, lines.upright != null ? "Fix + upright" : "Fix + level", "upright", fix, stood);
        }
        full.recycle();
        finishRoll();
    }

    private void produce(Bitmap full, String stem, String label, String suffix, Light light, Pose pose) {
        final Variant v = new Variant(label, suffix);
        synchronized (this) {
            roll.add(v);
        }
        notifyRoll();
        long t0 = System.currentTimeMillis();
        Bitmap out = null;
        try {
            Bitmap lit = full;
            if (light != null && !light.plain()) {
                lit = full.copy(Bitmap.Config.ARGB_8888, true);
                light.onto(lit, lit);
            }
            out = lit;
            if (pose != null && !pose.plain()) {
                Pose.Laid laid = pose.lay(lit.getWidth(), lit.getHeight());
                if (laid == null) {
                    log("VARIANT " + label + ": the turn is too steep, skipped");
                    if (lit != full) lit.recycle();
                    synchronized (this) {
                        roll.remove(v);
                    }
                    notifyRoll();
                    return;
                }
                out = lay(lit, laid);
                if (lit != full) lit.recycle();
            }
            byte[] bytes = encode(out);
            String fileName = stem + "_" + suffix + ".jpg";
            v.file = writeCache(fileName, bytes);
            v.kept = Saver.save(context, fileName, bytes);
            log("VARIANT " + label + ": " + out.getWidth() + "x" + out.getHeight() + ", " + bytes.length + " bytes, "
                    + (System.currentTimeMillis() - t0) + " ms -> " + v.kept);
        } finally {
            if (out != null && out != full) out.recycle();
        }
        synchronized (this) {
            v.ready = v.file != null && v.kept != null;
            if (!v.ready) roll.remove(v);
        }
        notifyRoll();
    }

    /** The picture redrawn through the pose's matrix into its frame. */
    private static Bitmap lay(Bitmap src, Pose.Laid laid) {
        int ow = Math.max(1, (int) Math.round(laid.w));
        int oh = Math.max(1, (int) Math.round(laid.h));
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float[] v = new float[9];
        for (int i = 0; i < 9; i++) v[i] = (float) laid.m[i];
        Matrix m = new Matrix();
        m.setValues(v);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static Upright.Answer readLines(Bitmap small, double lens35) {
        try {
            int w = small.getWidth(), h = small.getHeight();
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            float[] grey = new float[w * h];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                grey[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
            Pose lensOnly = new Pose();
            lensOnly.lens = lens35;
            return Upright.read(grey, w, h, lensOnly.focal(w, h));
        } catch (RuntimeException e) {
            return new Upright.Answer();
        }
    }

    /** A rectangle of a picture, given as shares of its sides. */
    private static Bitmap cut(Bitmap b, RectF r) {
        int x = Math.max(0, Math.round(r.left * b.getWidth()));
        int y = Math.max(0, Math.round(r.top * b.getHeight()));
        int w = Math.min(b.getWidth() - x, Math.round(r.width() * b.getWidth()));
        int h = Math.min(b.getHeight() - y, Math.round(r.height() * b.getHeight()));
        if (w <= 0 || h <= 0) return b;
        return Bitmap.createBitmap(b, x, y, w, h);
    }

    private static byte[] encode(Bitmap b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 << 20);
        b.compress(Bitmap.CompressFormat.JPEG, 95, out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------- the roll

    /** Takes a picture of the roll out of the camera folder and off the roll. */
    boolean discard(Variant v) {
        if (v.kept != null && !Saver.delete(context, v.kept)) return false;
        synchronized (this) {
            roll.remove(v);
        }
        if (v.file != null) v.file.delete();
        return true;
    }

    private void clearRoll() {
        roll.clear();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private File writeCache(String name, byte[] bytes) {
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            return f;
        } catch (IOException e) {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
            }
            f.delete();
            return null;
        }
    }

    private void finishRoll() {
        synchronized (this) {
            developing = false;
        }
        notifyRoll();
    }

    private void notifyRoll() {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onRoll();
            }
        });
    }
}
