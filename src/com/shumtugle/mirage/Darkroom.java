package com.shumtugle.mirage;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The last shot and its variants, one at a time: swipe to the next, keep the
 * ones worth keeping, or hand one to the photo editor to finish by hand.
 */
public final class Darkroom extends Activity implements Developer.Listener {

    private Developer developer;
    private List<Developer.Variant> roll;
    private int index;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout column;
    private TextView title;
    private TextView subtitle;
    private ImageView picture;
    private LinearLayout dots;
    private TextView keep;
    private int shown = -1;
    private String shownPath;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Palette.load(this);
        developer = Developer.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Palette.BG);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(28), dp(10), dp(24), dp(14));
        title = new TextView(this);
        title.setTypeface(Palette.display);
        title.setTextColor(Palette.INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setIncludeFontPadding(false);
        head.addView(title);
        subtitle = text(13.5f, Palette.MUTED);
        subtitle.setPadding(0, dp(6), 0, 0);
        head.addView(subtitle);
        column.addView(head);

        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setPadding(dp(16), 0, dp(16), 0);
        final GestureDetector swipes = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) < Math.abs(vy)) return false;
                step(vx < 0 ? 1 : -1);
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                step(e.getX() > picture.getWidth() / 2f ? 1 : -1);
                return true;
            }
        });
        picture.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return swipes.onTouchEvent(e);
            }
        });
        column.addView(picture, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(14);
        column.addView(dots, dl);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        keep = pill("Keep", true);
        keep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { keepShown(false); }
        });
        TextView edit = pill("Edit", false);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { keepShown(true); }
        });
        TextView close = pill("Close", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(keep, pillParams());
        bar.addView(edit, pillParams());
        bar.addView(close, pillParams());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(16);
        bl.bottomMargin = dp(10);
        column.addView(bar, bl);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int t, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    t = s.top;
                    b = s.bottom;
                } else {
                    t = in.getSystemWindowInsetTop();
                    b = in.getSystemWindowInsetBottom();
                }
                column.setPadding(0, t, 0, b);
                return in;
            }
        });
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        developer.setListener(this);
        onRoll();
    }

    @Override
    protected void onPause() {
        developer.setListener(null);
        super.onPause();
    }

    // ------------------------------------------------------------ developer

    @Override public void onSaved(Uri uri, Bitmap thumb) { }

    @Override public void onFailed() { }

    @Override
    public void onRoll() {
        roll = developer.roll();
        if (roll.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(roll.size() - 1, index));
        show();
    }

    // --------------------------------------------------------------- screen

    private void step(int by) {
        if (roll == null || roll.isEmpty()) return;
        int next = Math.max(0, Math.min(roll.size() - 1, index + by));
        if (next == index) return;
        index = next;
        show();
    }

    private void show() {
        final Developer.Variant v = roll.get(index);
        title.setText(v.name);
        StringBuilder s = new StringBuilder(String.format(Locale.US, "%d of %d", index + 1, roll.size()));
        if (!v.ready) s.append(" \u00b7 developing");
        else if (developer.developing()) s.append(" \u00b7 more on the way");
        subtitle.setText(s);

        boolean kept = v.kept != null;
        keep.setText(kept ? "Kept" : "Keep");
        keep.setAlpha(kept ? 0.6f : 1f);
        keep.setEnabled(!kept && v.ready);

        dots.removeAllViews();
        for (int i = 0; i < roll.size(); i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == index ? Palette.BRASS : Palette.LINE);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.leftMargin = dp(5);
            lp.rightMargin = dp(5);
            dots.addView(d, lp);
        }

        if (!v.ready || v.file == null) {
            picture.setImageDrawable(null);
            shown = -1;
            shownPath = null;
            return;
        }
        final String path = v.file.getPath();
        if (index == shown && path.equals(shownPath)) return;
        shown = index;
        shownPath = path;
        final int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap b = decode(path, target);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (path.equals(shownPath)) picture.setImageBitmap(b);
                    }
                });
            }
        }).start();
    }

    private static Bitmap decode(String path, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        // The shot itself may still lean on its tag; the variants are already upright.
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            int r;
            switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: r = 90; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: r = 180; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: r = 270; break;
                default: r = 0; break;
            }
            if (r != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(r);
                Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (t != b) b.recycle();
                b = t;
            }
        } catch (Exception ignored) {
            // shown as it is
        }
        return b;
    }

    /** Keeps the picture on screen; with edit, opens it in the photo editor afterwards. */
    private void keepShown(final boolean edit) {
        if (roll == null || roll.isEmpty()) return;
        final Developer.Variant v = roll.get(index);
        if (!v.ready) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final Uri uri = developer.keep(v);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (uri == null) {
                            Toast.makeText(Darkroom.this, "Not saved", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        show();
                        if (edit) openEditor(uri);
                    }
                });
            }
        }).start();
    }

    private void openEditor(Uri uri) {
        Intent i = new Intent(Intent.ACTION_EDIT);
        i.setDataAndType(uri, "image/jpeg");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Edit with"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No editor here", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Palette.body);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private TextView pill(String label, boolean strong) {
        TextView t = text(16.5f, Palette.INK);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(25));
        g.setColor(strong ? Palette.RAISED : Palette.alpha(Palette.SURFACE, 0.6f));
        g.setStroke(strong ? dp(1.5f) : dp(1), strong ? Palette.BRASS : Palette.LINE);
        t.setBackground(g);
        if (strong) t.setTypeface(Palette.bodyStrong);
        return t;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(50));
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
