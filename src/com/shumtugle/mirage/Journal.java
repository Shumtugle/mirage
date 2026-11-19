package com.shumtugle.mirage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A plain diary of what the camera saw and did, one file a day in the
 * phone's documents folder, so that a session can be read back afterwards
 * without any tools. Lines are written on a quiet thread of their own; a
 * failure to write is ignored, the camera never waits for its diary.
 */
final class Journal {
    private static Journal instance;

    private final Context context;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private String day;
    private Uri uri;
    private File file;

    static synchronized Journal get(Context c) {
        if (instance == null) instance = new Journal(c.getApplicationContext());
        return instance;
    }

    /** Shorthand for a line from anywhere that has a context. */
    static void line(Context c, String text) {
        get(c).write(text);
    }

    static void error(Context c, String what, Throwable t) {
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        get(c).write("ERROR " + what + ": " + s.toString().trim());
    }

    private Journal(Context c) {
        context = c;
    }

    void write(final String text) {
        final String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        writer.execute(new Runnable() {
            @Override public void run() {
                append(stamp + "  " + text.replace("\n", "\n              ") + "\n");
            }
        });
    }

    /** Writes at once, for the last words before the process goes. */
    void writeNow(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        append(stamp + "  " + text.replace("\n", "\n              ") + "\n");
    }

    private synchronized void append(String text) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!today.equals(day)) {
                day = today;
                uri = null;
                file = null;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                if (uri == null) uri = find("mirage-" + day + ".txt");
                if (uri == null) return;
                appendTo(uri, bytes);
            } else {
                if (file == null) file = legacyFile("mirage-" + day + ".txt");
                if (file == null) return;
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
        } catch (Exception ignored) {
            uri = null;
            file = null;
        }
    }

    /**
     * Appends to a file of the media store. Not every phone opens such a file
     * for appending; there the old text is read and written back with the new.
     */
    private void appendTo(Uri target, byte[] bytes) throws java.io.IOException {
        ContentResolver cr = context.getContentResolver();
        OutputStream out = null;
        try {
            out = cr.openOutputStream(target, "wa");
        } catch (Exception e) {
            out = null;
        }
        if (out == null) {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = cr.openInputStream(target);
            if (in != null) {
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
                } finally {
                    in.close();
                }
            }
            all.write(bytes);
            bytes = all.toByteArray();
            out = cr.openOutputStream(target, "wt");
            if (out == null) return;
        }
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    /** The day's file in the documents folder, made when it is not there yet. */
    private Uri find(String name) {
        ContentResolver cr = context.getContentResolver();
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String folder = Environment.DIRECTORY_DOCUMENTS + "/" + Saver.FOLDER + "/";
        Cursor c = cr.query(files, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{name, folder}, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return Uri.withAppendedPath(files, String.valueOf(c.getLong(0)));
                }
            } finally {
                c.close();
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
        return cr.insert(files, v);
    }

    @SuppressWarnings("deprecation")
    private static File legacyFile(String name) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), Saver.FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return new File(dir, name);
    }
}
