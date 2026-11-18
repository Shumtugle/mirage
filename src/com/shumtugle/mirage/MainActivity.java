package com.shumtugle.mirage;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A camera with presets. It always shoots on the main rear module and opens
 * at the default preset. The whole interface is built in code: a title with
 * the zoom, the viewfinder card, the preset pills, a row of round controls
 * around the shutter, and two tabs at the bottom.
 */
public final class MainActivity extends Activity implements CameraEngine.Listener {
    static final String VERSION = "0.2";

    private static final int REQ_PERMS = 1;
    private static final String PREF_GRID = "grid";
    private static final String PREF_LAST = "last";

    private PresetStore store;
    private CameraEngine engine;
    private Preset active;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout column;
    private ImageView ambient;
    private TextureView preview;
    private Overlay overlay;
    private LinearLayout presetRow;
    private TextView zoomTitle;
    private TextView subtitle;
    private TextView exposureChip;
    private TextView evCaption;
    private TextView wbCaption;
    private WbIcon wbIcon;
    private Thumb thumb;

    private Uri lastUri;
    private OrientationEventListener orientation;
    private boolean resumed;
    private boolean opened;
    private boolean askedPermission;
    /** Shrinks the control row on narrow screens; 1 on a screen 424 dp wide or more. */
    private float k = 1f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Palette.load(this);
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        k = Math.min(1f, (widthDp - 8f) / 416f);
        prefs = getSharedPreferences("mirage", MODE_PRIVATE);

        store = new PresetStore(this);
        engine = new CameraEngine(this, this);
        active = store.getDefault();
        engine.applyPreset(active);   // the very first frame is already at the default zoom

        buildUi();
        restoreLastShot();

        orientation = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                engine.setDeviceRotation(((o + 45) / 90 * 90) % 360);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        orientation.enable();
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(missing, REQ_PERMS);
            }
            return;
        }
        if (preview.isAvailable()) engine.open(preview.getSurfaceTexture());
        // Otherwise the texture listener opens the camera once the surface exists.
    }

    @Override
    protected void onPause() {
        resumed = false;
        opened = false;
        main.removeCallbacks(ambientTick);
        orientation.disable();
        engine.close();
        super.onPause();
    }

    /** Camera always; storage only on Android 9 and older, where photos are plain files. */
    private String[] missingPermissions() {
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return need.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show();
            finish();
        }
        // On success onResume runs again and opens the camera.
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Palette.BG);

        // Ambient light: the viewfinder itself, shrunk to a few pixels and spread over the screen.
        ambient = new ImageView(this);
        ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ambient.setAlpha(0.5f);
        root.addView(ambient, matchParent());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                Palette.alpha(Palette.BG, 0.55f), Palette.alpha(Palette.BG, 0.35f),
                Palette.alpha(Palette.BG, 0.82f), Palette.alpha(Palette.BG, 0.95f)}));
        root.addView(shade, matchParent());

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, matchParent());

        column.addView(buildHeader());

        FitBox fit = new FitBox(this);
        fit.setPadding(dp(16), 0, dp(16), 0);
        fit.addView(buildViewfinder());
        column.addView(fit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER);
        presetRow.setPadding(dp(16), 0, dp(16), 0);
        scroll.addView(presetRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(16);
        column.addView(scroll, sl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(14);
        column.addView(buildControls(), cl);

        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        nl.bottomMargin = dp(8);
        column.addView(buildNav(), nl);

        // The window draws behind the system bars: the column steps clear of them.
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
        // Draw behind the system bars on every version, not only where the system insists.
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
        refreshPresets();
        updateLabels();
    }

    private View buildHeader() {
        FrameLayout head = new FrameLayout(this);
        head.setPadding(dp(28), dp(10), dp(24), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        zoomTitle = new TextView(this);
        zoomTitle.setTypeface(Palette.display);
        zoomTitle.setTextColor(Palette.INK);
        zoomTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        zoomTitle.setIncludeFontPadding(false);
        texts.addView(zoomTitle);
        subtitle = text(13.5f, Palette.MUTED);
        subtitle.setPadding(0, dp(6), 0, 0);
        texts.addView(subtitle);
        head.addView(texts, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        // The app's own sign is the way into its settings and its facts.
        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(oval(Palette.alpha(Palette.RAISED, 0.75f)));
        logo.setElevation(dp(6));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        logo.addView(icon, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        logo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showInfo(); }
        });
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END | Gravity.TOP);
        ll.topMargin = dp(4);
        head.addView(logo, ll);
        return head;
    }

    private View buildViewfinder() {
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundColor(Color.BLACK);
        final float radius = dp(30);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(dp(10));

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (resumed && missingPermissions().length == 0) engine.open(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        card.addView(preview, matchParent());

        overlay = new Overlay(this);
        overlay.setGrid(prefs.getBoolean(PREF_GRID, true));
        card.addView(overlay, matchParent());
        attachGestures(card);

        exposureChip = text(12.5f, Palette.MUTED);
        exposureChip.setPadding(dp(13), dp(7), dp(13), dp(7));
        exposureChip.setBackground(rounded(Palette.alpha(Palette.BG, 0.55f), dp(16)));
        exposureChip.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        el.leftMargin = dp(16);
        el.bottomMargin = dp(16);
        card.addView(exposureChip, el);
        return card;
    }

    private View buildControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(12 * k), 0, dp(12 * k), 0);

        thumb = new Thumb(this);
        thumb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLast(); }
        });
        row.addView(cell(thumb, 80, 14, text(12.5f, Palette.MUTED), "Last"));
        row.addView(spacer());

        TextView minus = roundButton("\u2212");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.stepEv(-1); updateLabels(); }
        });
        row.addView(cell(minus, 58, 25, null, null));
        row.addView(spacer());

        Shutter shutter = new Shutter(this);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.capture(); }
        });
        evCaption = text(12.5f, Palette.MUTED);
        row.addView(cell(shutter, 108, 0, evCaption, null));
        row.addView(spacer());

        TextView plus = roundButton("+");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.stepEv(1); updateLabels(); }
        });
        row.addView(cell(plus, 58, 25, null, null));
        row.addView(spacer());

        wbIcon = new WbIcon(this);
        wbIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.cycleWb(); updateLabels(); }
        });
        wbCaption = text(12.5f, Palette.ACCENT_TEXT);
        row.addView(cell(wbIcon, 72, 18, wbCaption, null));
        return row;
    }

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        TextView photo = navPill("Photo", true);
        TextView album = navPill("Album", false);
        album.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAlbum(); }
        });
        nav.addView(photo, new LinearLayout.LayoutParams(dp(130), dp(50)));
        nav.addView(album, new LinearLayout.LayoutParams(dp(130), dp(50)));
        return nav;
    }

    /**
     * On the viewfinder: pinch to zoom, double tap for the default preset,
     * press and hold to show or hide the thirds grid.
     */
    private void attachGestures(View target) {
        final ScaleGestureDetector pinch = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        engine.setZoom(engine.getZoom() * d.getScaleFactor());
                        updateLabels();
                        return true;
                    }
                });
        final GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                select(store.getDefault());
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (pinch.isInProgress()) return;
                boolean on = !overlay.grid();
                overlay.setGrid(on);
                prefs.edit().putBoolean(PREF_GRID, on).apply();
                overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        });
        target.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                pinch.onTouchEvent(e);
                taps.onTouchEvent(e);
                return true;
            }
        });
    }

    private void refreshPresets() {
        presetRow.removeAllViews();
        for (final Preset p : store.list) {
            boolean on = p == active;
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(20), 0, dp(20), 0);
            pill.setMinimumWidth(dp(78));
            GradientDrawable bg = rounded(on ? Palette.RAISED : Palette.alpha(Palette.SURFACE, 0.6f), dp(23));
            bg.setStroke(on ? dp(1.5f) : dp(1), on ? Palette.BRASS : Palette.LINE);
            pill.setBackground(bg);
            TextView name = text(17, Palette.INK);
            name.setText(p.name);
            if (on) name.setTypeface(Palette.bodyStrong);
            pill.addView(name);
            if (p.isDefault) {
                View dot = new View(this);
                dot.setBackground(oval(Palette.BRASS));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(6), dp(6));
                dl.leftMargin = dp(8);
                pill.addView(dot, dl);
            }
            pill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(p); }
            });
            pill.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { presetMenu(p); return true; }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
            lp.rightMargin = dp(10);
            presetRow.addView(pill, lp);
        }
        TextView add = text(22, Palette.MUTED);
        add.setText("+");
        add.setGravity(Gravity.CENTER);
        GradientDrawable abg = oval(Palette.alpha(Palette.SURFACE, 0.6f));
        abg.setStroke(dp(1), Palette.LINE);
        add.setBackground(abg);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addFromCurrent(); }
        });
        presetRow.addView(add, new LinearLayout.LayoutParams(dp(46), dp(46)));
    }

    private void select(Preset p) {
        active = p;
        engine.applyPreset(p);
        refreshPresets();
        updateLabels();
    }

    private void presetMenu(final Preset p) {
        String[] items = store.list.size() > 1
                ? new String[]{"Save current here", "Make default", "Rename", "Delete"}
                : new String[]{"Save current here", "Make default", "Rename"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                p.zoom = engine.getZoom();
                                p.ev = engine.getEv();
                                p.wb = engine.getWb();
                                store.save();
                                active = p;
                                break;
                            case 1:
                                store.setDefault(p);
                                break;
                            case 2:
                                rename(p);
                                return;
                            case 3:
                                boolean wasActive = active == p;
                                store.remove(p);
                                if (wasActive) {
                                    select(store.getDefault());
                                    return;
                                }
                                break;
                            default:
                                break;
                        }
                        refreshPresets();
                    }
                })
                .show();
    }

    private void addFromCurrent() {
        Preset p = new Preset(String.format(Locale.US, "%.1f\u00d7", engine.getZoom()),
                engine.getZoom(), engine.getEv(), engine.getWb(), false);
        store.list.add(p);
        store.save();
        active = p;
        refreshPresets();
        rename(p);
    }

    private void rename(final Preset p) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(p.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.length() > 0) {
                            p.name = name;
                            store.save();
                            refreshPresets();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateLabels() {
        SpannableStringBuilder z = new SpannableStringBuilder(String.format(Locale.US, "%.1f", engine.getZoom()));
        int start = z.length();
        z.append("\u00d7");
        z.setSpan(new RelativeSizeSpan(0.75f), start, z.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        z.setSpan(new ForegroundColorSpan(Palette.MUTED), start, z.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        zoomTitle.setText(z);

        String wb = CameraEngine.WB_LABELS[engine.getWb()];
        SpannableStringBuilder s = new SpannableStringBuilder("Main lens");
        int mp = engine.photoMegapixels();
        if (mp > 0) {
            s.append(" \u00b7 ");
            int a = s.length();
            s.append(mp + " MP");
            s.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        s.append(" \u00b7 ").append(wb).append(" \u00b7 ").append(evText());
        subtitle.setText(s);

        SpannableStringBuilder e = new SpannableStringBuilder(evText());
        int cut = e.length() - 3;   // the number in ink, the unit muted
        e.setSpan(new ForegroundColorSpan(Palette.INK), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        evCaption.setText(e);

        wbIcon.setMode(engine.getWb());
        wbCaption.setText(wb);
    }

    private String evText() {
        float ev = engine.getEv();
        if (Math.abs(ev) < 0.05f) return "\u00b10 EV";
        return String.format(Locale.US, "%+.1f EV", ev);
    }

    // ------------------------------------------------------- last shot, album

    private void restoreLastShot() {
        String s = prefs.getString(PREF_LAST, null);
        if (s == null) return;
        final Uri uri = Uri.parse(s);
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap b = loadThumb(uri);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (b == null) {
                            prefs.edit().remove(PREF_LAST).apply();
                            return;
                        }
                        lastUri = uri;
                        thumb.setBitmap(b);
                    }
                });
            }
        }).start();
    }

    /** Reads a small, upright copy of a saved photo; null if it is gone. */
    private Bitmap loadThumb(Uri uri) {
        try {
            byte[] jpeg = readAll(uri);
            if (jpeg == null) return null;
            int rotation = 0;
            InputStream in = getContentResolver().openInputStream(uri);
            if (in != null) {
                try {
                    ExifInterface exif = new ExifInterface(in);
                    switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                        case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                        case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                        default: break;
                    }
                } finally {
                    in.close();
                }
            }
            return CameraEngine.thumbnail(jpeg, rotation);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readAll(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void openLast() {
        if (lastUri == null) {
            openAlbum();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri, "image/jpeg");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(i);
    }

    private void openAlbum() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        start(i);
    }

    private void start(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to show photos", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Cannot open this photo", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ info sheet

    /** What the camera gives this app, in plain lines, on the app's own card. */
    private void showInfo() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(22), dp(24), dp(18));
        box.setBackground(rounded(Palette.SURFACE, dp(28)));

        TextView title = new TextView(this);
        title.setTypeface(Palette.display);
        title.setTextColor(Palette.INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setText("This camera");
        box.addView(title);

        TextView body = text(14.5f, Palette.MUTED);
        String info = engine.info();
        body.setText(info.length() > 0 ? info : "The camera is not open yet.");
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(12), 0, dp(12));
        body.setTextIsSelectable(true);
        box.addView(body);

        TextView grid = text(14.5f, Palette.MUTED);
        grid.setText("Hold the viewfinder to show or hide the thirds grid.");
        box.addView(grid);

        TextView version = text(12.5f, Palette.HINT);
        version.setText("Mirage " + VERSION);
        version.setPadding(0, dp(14), 0, dp(10));
        box.addView(version);

        TextView close = navPill("Close", true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(130), dp(48));
        cl.gravity = Gravity.END;
        box.addView(close, cl);

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(32),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    // ------------------------------------------------------ engine callbacks

    @Override
    public void onOpened(Size previewSize) {
        opened = true;
        updateLabels();
        main.removeCallbacks(ambientTick);
        main.postDelayed(ambientTick, 300);
    }

    @Override
    public void onExposure(long exposureNs, int iso, float aperture) {
        if (exposureNs <= 0) return;
        String shutter;
        if (exposureNs >= 500000000L) {
            shutter = String.format(Locale.US, "%.1fs", exposureNs / 1e9);
        } else {
            shutter = "1/" + Math.round(1e9 / exposureNs);
        }
        SpannableStringBuilder s = new SpannableStringBuilder(shutter);
        s.setSpan(new ForegroundColorSpan(Palette.INK), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (iso > 0) s.append(" \u00b7 ISO ").append(String.valueOf(iso));
        if (aperture > 0) s.append(String.format(Locale.US, " \u00b7 f/%.1f", aperture));
        exposureChip.setText(s);
        exposureChip.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSaved(boolean ok, Uri uri, Bitmap b) {
        if (ok) {
            preview.setAlpha(0.3f);
            preview.animate().alpha(1f).setDuration(200).start();
            lastUri = uri;
            if (uri != null) prefs.edit().putString(PREF_LAST, uri.toString()).apply();
            if (b != null) thumb.setBitmap(b);
        } else {
            Toast.makeText(this, "Not saved", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Refreshes the ambient light from the viewfinder a little over once a second. */
    private final Runnable ambientTick = new Runnable() {
        @Override public void run() {
            if (!opened || !preview.isAvailable()) return;
            Bitmap tiny = preview.getBitmap(18, 24);
            if (tiny != null) {
                BitmapDrawable d = new BitmapDrawable(getResources(), tiny);
                d.setFilterBitmap(true);
                ambient.setImageDrawable(d);
            }
            main.postDelayed(this, 800);
        }
    };

    // ------------------------------------------------------------ hardware keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (event.getRepeatCount() == 0) engine.capture();
                return true;
            case KeyEvent.KEYCODE_FOCUS:
                return true;   // half-press of a shutter key; focus is continuous anyway
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A square control with an optional caption, nudged down so all centres share one line. */
    private View cell(View control, int sizeDp, int topDp, TextView caption, String captionText) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp * k), dp(sizeDp * k));
        lp.topMargin = dp(topDp * k);
        c.addView(control, lp);
        if (caption != null) {
            if (captionText != null) caption.setText(captionText);
            caption.setPadding(0, dp(8), 0, 0);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine(true);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.addView(caption, new LinearLayout.LayoutParams(dp(Math.max(sizeDp, 84) * k),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return c;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Palette.body);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private TextView roundButton(String label) {
        TextView b = text(26, Palette.INK);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setBackground(oval(Palette.RAISED));
        b.setElevation(dp(5));
        return b;
    }

    private TextView navPill(String label, boolean on) {
        TextView t = text(16.5f, on ? Palette.INK : Palette.MUTED);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        if (on) {
            t.setTypeface(Palette.bodyStrong);
            t.setBackground(rounded(Palette.RAISED, dp(25)));
        }
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
