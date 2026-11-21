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
public final class MainActivity extends Activity implements CameraEngine.Listener, Developer.Listener {
    static final String VERSION = "0.4.7";

    private static final int REQ_PERMS = 1;
    private static final String PREF_GRID = "grid";
    private static final String PREF_LAST = "last";
    private static final String PREF_FRAME_X = "frame_x";
    private static final String PREF_FRAME_Y = "frame_y";

    private PresetStore store;
    private CameraEngine engine;
    private Developer developer;
    private Preset active;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout column;
    private ImageView ambient;
    private TextureView preview;
    /** Note the preview's transform once in the diary: before a clip and while it records. */
    private boolean matrixPreview;
    private boolean matrixRecord;
    private Overlay overlay;
    private LinearLayout presetRow;
    private TextView zoomTitle;
    private TextView subtitle;
    private TextView exposureChip;
    private TextView evCaption;
    private TextView wbCaption;
    private TextView minusButton;
    private TextView plusButton;
    private Shutter shutterButton;
    private FitBox fit;
    private LinearLayout nav;

    /** Photo or video; video has its own engine and its own zooms. */
    private boolean video;
    private VideoEngine videoEngine;
    private float videoZoom = 2f;
    private int videoFps = VideoEngine.FPS;
    private int deviceRotation;
    private boolean lastIsVideo;
    private boolean askedAudio;
    private static final int REQ_AUDIO = 2;
    private static final String PREF_MODE = "mode";
    private static final String PREF_VIDEO_ZOOM = "video_zoom";
    private static final String PREF_STEADY = "steady";
    private static final String PREF_LAST_VIDEO = "last_video";
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
        watchForCrash();
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        k = Math.min(1f, (widthDp - 8f) / 416f);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Journal.line(this, String.format(Locale.US,
                "START Mirage %s on %s %s, Android %s (API %d); screen %dx%d px, density %.3f, %.0f dp wide, row scale %.3f",
                VERSION, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                dm.widthPixels, dm.heightPixels, dm.density, widthDp, k));
        prefs = getSharedPreferences("mirage", MODE_PRIVATE);

        store = new PresetStore(this);
        engine = new CameraEngine(this, this);
        developer = Developer.get(this);
        videoEngine = new VideoEngine(this, videoListener);
        video = "video".equals(prefs.getString(PREF_MODE, "photo"));
        videoZoom = prefs.getFloat(PREF_VIDEO_ZOOM, 2f);
        videoEngine.setZoom(videoZoom);
        videoEngine.setSteady(prefs.getBoolean(PREF_STEADY, false));
        lastIsVideo = prefs.getBoolean(PREF_LAST_VIDEO, false);
        engine.setFrame(prefs.getFloat(PREF_FRAME_X, 0.5f), prefs.getFloat(PREF_FRAME_Y, 0.5f));
        active = store.getDefault();
        engine.applyPreset(active);   // the very first frame is already at the default zoom

        buildUi();
        if (video) {
            wbIcon.setAlpha(0.35f);
            wbCaption.setAlpha(0.35f);
        }
        restoreLastShot();

        orientation = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceRotation = ((o + 45) / 90 * 90) % 360;
                engine.setDeviceRotation(deviceRotation);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        developer.setListener(this);
        orientation.enable();
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(missing, REQ_PERMS);
            }
            return;
        }
        if (video) askAudioOnce();
        if (preview.isAvailable()) openCamera(preview.getSurfaceTexture());
        // Otherwise the texture listener opens the camera once the surface exists.
    }

    @Override
    protected void onPause() {
        resumed = false;
        opened = false;
        developer.setListener(null);
        main.removeCallbacks(ambientTick);
        orientation.disable();
        engine.close();
        videoEngine.close();
        stopTimer();
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

    /** The last words of a crash go into the diary before the process ends. */
    private void watchForCrash() {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                java.io.StringWriter s = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(s));
                Journal.get(MainActivity.this).writeNow("CRASH in " + t.getName() + ": " + s.toString().trim());
                if (before != null) before.uncaughtException(t, e);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_AUDIO) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            videoEngine.setSound(ok);
            Journal.line(this, "SOUND for video " + (ok ? "allowed" : "refused, clips will be silent"));
            return;
        }
        if (code != REQ_PERMS) return;
        Journal.line(this, "PERMISSIONS " + java.util.Arrays.toString(perms) + " -> " + java.util.Arrays.toString(results));
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

        fit = new FitBox(this);
        fit.setRatio(video ? 9f / 16f : 3f / 4f);
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
                if (resumed && missingPermissions().length == 0) openCamera(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                // The view resets its buffer to its own size; video must keep its own.
                if (video) VideoEngine.holdBufferSize(st);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (!video || videoEngine == null) return;
                boolean rec = videoEngine.recording();
                if (rec ? matrixRecord : matrixPreview) {
                    if (rec) matrixRecord = false;
                    else matrixPreview = false;
                    logMatrix(st, rec);
                }
            }
        });
        card.addView(preview, matchParent());

        overlay = new Overlay(this);
        overlay.setGrid(prefs.getBoolean(PREF_GRID, true));
        overlay.setFrame(engine.frameInView());
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
            @Override public void onClick(View v) { openDarkroom(); }
        });
        row.addView(cell(thumb, 80, 14, text(12.5f, Palette.MUTED), "Last"));
        row.addView(spacer());

        TextView minus = roundButton("\u2212");
        minusButton = minus;
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepBrightness(-1, "button"); }
        });
        row.addView(cell(minus, 58, 25, null, null));
        row.addView(spacer());

        Shutter shutter = new Shutter(this);
        shutterButton = shutter;
        shutter.setVideo(video);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { press(); }
        });
        evCaption = text(12.5f, Palette.MUTED);
        row.addView(cell(shutter, 108, 0, evCaption, null));
        row.addView(spacer());

        TextView plus = roundButton("+");
        plusButton = plus;
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepBrightness(1, "button"); }
        });
        row.addView(cell(plus, 58, 25, null, null));
        row.addView(spacer());

        wbIcon = new WbIcon(this);
        wbIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!video) { engine.cycleWb(); updateLabels(); } }
        });
        wbCaption = text(12.5f, Palette.ACCENT_TEXT);
        row.addView(cell(wbIcon, 72, 18, wbCaption, null));
        return row;
    }

    private View buildNav() {
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        fillNav();
        return nav;
    }

    private void fillNav() {
        nav.removeAllViews();
        TextView photo = navPill("Photo", !video);
        TextView clip = navPill("Video", video);
        TextView album = navPill("Album", false);
        photo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(false); }
        });
        clip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(true); }
        });
        album.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAlbum(); }
        });
        int w = dp(Math.min(118f, (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density - 24f) / 3f));
        nav.addView(photo, new LinearLayout.LayoutParams(w, dp(50)));
        nav.addView(clip, new LinearLayout.LayoutParams(w, dp(50)));
        nav.addView(album, new LinearLayout.LayoutParams(w, dp(50)));
    }

    // ------------------------------------------------------------ photo / video

    private void openCamera(SurfaceTexture st) {
        if (video) videoEngine.open(st);
        else engine.open(st);
    }

    private void setMode(boolean toVideo) {
        if (toVideo == video || videoEngine.recording()) return;
        engine.close();
        videoEngine.close();
        opened = false;
        video = toVideo;
        prefs.edit().putString(PREF_MODE, video ? "video" : "photo").apply();
        Journal.line(this, "MODE " + (video ? "video" : "photo"));
        fit.setRatio(video ? 9f / 16f : 3f / 4f);
        shutterButton.setVideo(video);
        shutterButton.setRecording(false);
        overlay.setFocus(-1f, -1f, false);
        exposureChip.setVisibility(View.INVISIBLE);
        float dim = video ? 0.35f : 1f;
        wbIcon.setAlpha(dim);
        wbCaption.setAlpha(dim);
        fillNav();
        refreshPresets();
        updateLabels();
        if (video) askAudioOnce();
        if (resumed && missingPermissions().length == 0 && preview.isAvailable()) {
            openCamera(preview.getSurfaceTexture());
        }
    }

    /** Sound is asked for once, the first time video is chosen; without it, clips are silent. */
    private void askAudioOnce() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            videoEngine.setSound(true);
            return;
        }
        videoEngine.setSound(false);
        if (askedAudio) return;
        askedAudio = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    /** The shutter: a photo, or the start and end of a clip. */
    private void press() {
        if (!video) {
            engine.capture();
            return;
        }
        if (videoEngine.starting()) return;
        if (videoEngine.recording()) {
            videoEngine.stop();
            shutterButton.setRecording(false);
            stopTimer();
            setControlsEnabled(true);
        } else {
            matrixRecord = true;
            matrixPreview = true;
            videoEngine.start(deviceRotation);
            shutterButton.setRecording(true);
            setControlsEnabled(false);
            main.postDelayed(timer, 250);
        }
    }

    /**
     * The texture's transform carries the crop and turn the camera asks for;
     * a preview that looks squeezed shows it here as unequal scales.
     */
    private void logMatrix(SurfaceTexture st, boolean rec) {
        float[] m = new float[16];
        st.getTransformMatrix(m);
        Journal.line(this, String.format(Locale.US,
                "PREVIEW %s: view %dx%d, matrix a %.3f b %.3f c %.3f d %.3f tx %.3f ty %.3f, steady %s",
                rec ? "while recording" : "before recording", preview.getWidth(), preview.getHeight(),
                m[0], m[1], m[4], m[5], m[12], m[13], videoEngine.steady()));
    }

    /** The minus and plus buttons: exposure in photo, brightness in video, even while it records. */
    private void stepBrightness(int direction, String how) {
        if (!video) {
            engine.stepEv(direction);
            updateLabels();
            return;
        }
        if (videoEngine.nudgeEv(direction)) {
            brightnessChanged();
            Journal.line(this, String.format(Locale.US, "VIDEO ev %+.2f by %s", videoEngine.ev(), how));
        }
    }

    private void brightnessChanged() {
        if (evCaption != null) evCaption.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateLabels();
        if (videoEngine.recording()) showRecordingTime();
    }

    /** Brightness for the video labels; empty when the camera meters by itself. */
    private String videoEvText() {
        float ev = videoEngine.ev();
        if (Math.abs(ev) < 0.05f) return "";
        return String.format(Locale.US, "%+.1f EV", ev);
    }

    private void setControlsEnabled(boolean on) {
        nav.setAlpha(on ? 1f : 0.4f);
        presetRow.setAlpha(on ? 1f : 0.4f);
    }

    private final Runnable timer = new Runnable() {
        @Override public void run() {
            if (!videoEngine.recording()) {
                if (videoEngine.starting()) {
                    main.postDelayed(this, 150);
                    return;
                }
                updateLabels();
                if (shutterButton != null) shutterButton.setRecording(false);
                setControlsEnabled(true);
                return;
            }
            showRecordingTime();
            main.postDelayed(this, 250);
        }
    };

    private void stopTimer() {
        main.removeCallbacks(timer);
        if (shutterButton != null) shutterButton.setRecording(false);
        if (nav != null) setControlsEnabled(true);
    }

    /** The running time under the shutter, with the brightness when it is not the camera's own. */
    private void showRecordingTime() {
        long s = videoEngine.elapsedMs() / 1000;
        SpannableStringBuilder t = new SpannableStringBuilder(
                String.format(Locale.US, "\u25cf %02d:%02d", s / 60, s % 60));
        t.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setSpan(new ForegroundColorSpan(Palette.INK), 1, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String ev = videoEvText();
        if (ev.length() > 0) {
            int a = t.length();
            t.append(" \u00b7 ").append(ev);
            t.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), a, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        evCaption.setText(t);
    }

    private final VideoEngine.Listener videoListener = new VideoEngine.Listener() {
        @Override public void onOpened(Size previewSize, int fps) {
            opened = true;
            matrixPreview = true;
            videoFps = fps;
            updateLabels();
            main.removeCallbacks(ambientTick);
            main.postDelayed(ambientTick, 300);
        }

        @Override public void onSaved(final Uri uri, boolean ok) {
            stopTimer();
            updateLabels();
            if (!ok || uri == null) {
                Toast.makeText(MainActivity.this, "Clip not saved", Toast.LENGTH_SHORT).show();
                return;
            }
            lastUri = uri;
            lastIsVideo = true;
            prefs.edit().putString(PREF_LAST, uri.toString()).putBoolean(PREF_LAST_VIDEO, true).apply();
            new Thread(new Runnable() {
                @Override public void run() {
                    final Bitmap b = videoFrame(uri);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (b != null) thumb.setBitmap(b);
                        }
                    });
                }
            }).start();
        }

        @Override public void onError(String message) {
            stopTimer();
            MainActivity.this.onError(message);
        }
    };

    /** A still from the start of a clip, for the last-shot ring. */
    private Bitmap videoFrame(Uri uri) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            Bitmap b = r.getFrameAtTime(0);
            if (b == null) return null;
            float k = 256f / Math.max(1, Math.min(b.getWidth(), b.getHeight()));
            if (k >= 1f) return b;
            return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * On the viewfinder: drag the frame, tap to focus and meter there, press
     * and hold to lock focus and exposure there, double tap to bring the frame
     * back to the middle and give focus back to the camera, pinch to zoom.
     * In video the viewfinder is a brightness slider: a finger moving up, as
     * the phone is held, makes it brighter, down darker; a double tap gives
     * brightness back to the camera. It works while a clip records.
     */
    private void attachGestures(final View target) {
        final ScaleGestureDetector pinch = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        engine.setZoom(engine.getZoom() * d.getScaleFactor());
                        updateLabels();
                        return true;
                    }
                });
        final GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                float x = e.getX() / target.getWidth(), y = e.getY() / target.getHeight();
                engine.focusAt(x, y, false);
                overlay.setFocus(x, y, false);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                moveFrame(0.5f, 0.5f);
                saveFrame();
                engine.releaseFocus();
                overlay.setFocus(-1f, -1f, false);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (pinch.isInProgress() || dragging) return;
                float x = e.getX() / target.getWidth(), y = e.getY() / target.getHeight();
                engine.focusAt(x, y, true);
                overlay.setFocus(x, y, true);
                overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        });
        final GestureDetector videoTaps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (videoEngine.resetEv()) {
                    brightnessChanged();
                    Journal.line(MainActivity.this, "VIDEO ev back to the camera's own");
                }
                return true;
            }
        });
        final float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float third = dp(28);
        target.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean inFrame;
            int turn, thirds;
            boolean evMoved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (video) {
                    videoTaps.onTouchEvent(e);
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getX();
                            downY = e.getY();
                            turn = deviceRotation;
                            thirds = 0;
                            evMoved = false;
                            break;
                        case MotionEvent.ACTION_MOVE: {
                            if (e.getPointerCount() > 1) break;
                            float dx = e.getX() - downX, dy = e.getY() - downY;
                            // Up as the phone is held: the screen's top, its left or right edge, or its bottom.
                            float up = turn == 90 ? -dx : turn == 180 ? dy : turn == 270 ? dx : -dy;
                            int want = (int) (up / third);
                            while (thirds != want) {
                                int by = want > thirds ? 1 : -1;
                                thirds += by;
                                if (videoEngine.nudgeEv(by)) {
                                    evMoved = true;
                                    brightnessChanged();
                                }
                            }
                            break;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (evMoved) {
                                Journal.line(MainActivity.this, String.format(Locale.US,
                                        "VIDEO ev %+.2f by a slide", videoEngine.ev()));
                            }
                            evMoved = false;
                            break;
                        default:
                            break;
                    }
                    return true;
                }
                pinch.onTouchEvent(e);
                taps.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        android.graphics.RectF f = engine.frameInView();
                        startX = f.centerX();
                        startY = f.centerY();
                        inFrame = f.contains(downX / v.getWidth(), downY / v.getHeight());
                        dragging = false;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        dragging = false;
                        inFrame = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!inFrame || e.getPointerCount() > 1 || pinch.isInProgress()) break;
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging) moveFrame(startX + dx / v.getWidth(), startY + dy / v.getHeight());
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) saveFrame();
                        dragging = false;
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    private boolean dragging;

    private void moveFrame(float x, float y) {
        engine.setFrame(x, y);
        android.graphics.RectF f = engine.frameInView();
        engine.setFrame(f.centerX(), f.centerY());   // kept inside the view
        overlay.setFrame(f);
    }

    private void saveFrame() {
        android.graphics.RectF f = engine.frameInView();
        Journal.line(this, "FRAME moved to " + f.toShortString());
        prefs.edit().putFloat(PREF_FRAME_X, f.centerX()).putFloat(PREF_FRAME_Y, f.centerY()).apply();
    }

    private void refreshPresets() {
        presetRow.removeAllViews();
        if (video) {
            List<String> names = VideoEngine.zoomNames();
            for (int i = 0; i < VideoEngine.ZOOMS.length; i++) {
                final float z = VideoEngine.ZOOMS[i];
                boolean on = Math.abs(z - videoZoom) < 0.01f;
                TextView pill = text(17, Palette.INK);
                pill.setText(names.get(i));
                pill.setGravity(Gravity.CENTER);
                pill.setPadding(dp(20), 0, dp(20), 0);
                pill.setMinimumWidth(dp(78));
                GradientDrawable bg = rounded(on ? Palette.RAISED : Palette.alpha(Palette.SURFACE, 0.6f), dp(23));
                bg.setStroke(on ? dp(1.5f) : dp(1), on ? Palette.BRASS : Palette.LINE);
                pill.setBackground(bg);
                if (on) pill.setTypeface(Palette.bodyStrong);
                pill.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (videoEngine.recording()) return;
                        videoZoom = z;
                        videoEngine.setZoom(z);
                        prefs.edit().putFloat(PREF_VIDEO_ZOOM, z).apply();
                        Journal.line(MainActivity.this, "VIDEO zoom " + z);
                        refreshPresets();
                        updateLabels();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
                lp.rightMargin = dp(10);
                presetRow.addView(pill, lp);
            }
            // Steady: an ordinary session with the phone's stabilisation, at up to 60 frames.
            final boolean st = videoEngine.steady();
            TextView steadyPill = text(17, st ? Palette.INK : Palette.MUTED);
            steadyPill.setText("Steady");
            steadyPill.setGravity(Gravity.CENTER);
            steadyPill.setPadding(dp(18), 0, dp(18), 0);
            GradientDrawable sbg = rounded(st ? Palette.RAISED : Palette.alpha(Palette.SURFACE, 0.6f), dp(23));
            sbg.setStroke(st ? dp(1.5f) : dp(1), st ? Palette.ACCENT_TEXT : Palette.LINE);
            steadyPill.setBackground(sbg);
            if (st) steadyPill.setTypeface(Palette.bodyStrong);
            steadyPill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (videoEngine.recording() || videoEngine.starting()) return;
                    boolean on = !videoEngine.steady();
                    prefs.edit().putBoolean(PREF_STEADY, on).apply();
                    Journal.line(MainActivity.this, "STEADY " + (on ? "on" : "off"));
                    videoEngine.close();
                    opened = false;
                    videoEngine.setSteady(on);
                    refreshPresets();
                    if (resumed && missingPermissions().length == 0 && preview.isAvailable()) {
                        videoEngine.open(preview.getSurfaceTexture());
                    }
                }
            });
            presetRow.addView(steadyPill, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)));
            return;
        }
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
        Journal.line(this, String.format(Locale.US, "PRESET %s: zoom %.2f, ev %.2f, wb %d", p.name, p.zoom, p.ev, p.wb));
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
        if (video) {
            SpannableStringBuilder vz = new SpannableStringBuilder(String.format(Locale.US, "%.1f", videoZoom));
            int st = vz.length();
            vz.append("\u00d7");
            vz.setSpan(new RelativeSizeSpan(0.75f), st, vz.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            vz.setSpan(new ForegroundColorSpan(Palette.MUTED), st, vz.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            zoomTitle.setText(vz);
            SpannableStringBuilder vs = new SpannableStringBuilder("Video \u00b7 ");
            int a = vs.length();
            vs.append("720p ").append(String.valueOf(videoFps));
            vs.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), a, vs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            vs.append(videoEngine.steady() ? " \u00b7 steady" : " \u00b7 main lens");
            String vev = videoEvText();
            if (vev.length() > 0) {
                vs.append(" \u00b7 ");
                int b = vs.length();
                vs.append(vev);
                vs.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), b, vs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            subtitle.setText(vs);
            if (!videoEngine.recording()) {
                SpannableStringBuilder c = new SpannableStringBuilder("720p \u00b7 " + videoFps);
                if (vev.length() > 0) {
                    c.append(" \u00b7 ");
                    int b = c.length();
                    c.append(vev);
                    c.setSpan(new ForegroundColorSpan(Palette.ACCENT_TEXT), b, c.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                evCaption.setText(c);
            }
            if (overlay != null) overlay.setFrame(new android.graphics.RectF(0f, 0f, 1f, 1f));
            return;
        }
        SpannableStringBuilder z = new SpannableStringBuilder(String.format(Locale.US, "%.1f", engine.getZoom()));
        int start = z.length();
        z.append("\u00d7");
        z.setSpan(new RelativeSizeSpan(0.75f), start, z.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        z.setSpan(new ForegroundColorSpan(Palette.MUTED), start, z.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        zoomTitle.setText(z);

        String wb = CameraEngine.WB_LABELS[engine.getWb()];
        SpannableStringBuilder s = new SpannableStringBuilder(
                String.format(Locale.US, "In a %.1f\u00d7 view", engine.viewZoom()));
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
        if (overlay != null) overlay.setFrame(engine.frameInView());
    }

    private String storageLabel() {
        if (!Saver.wantsCard(this)) return "Photos: phone memory";
        return Saver.cardVolume(this) != null ? "Photos: memory card" : "Photos: card (not in, using phone)";
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
                final Bitmap b = lastIsVideo ? videoFrame(uri) : loadThumb(uri);
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
            return jpeg == null ? null : Saver.thumbnail(jpeg);
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

        final TextView grid = navPill(overlay.grid() ? "Thirds grid: on" : "Thirds grid: off", false);
        GradientDrawable gb = rounded(Palette.alpha(Palette.SURFACE, 0.6f), dp(23));
        gb.setStroke(dp(1), Palette.LINE);
        grid.setBackground(gb);
        grid.setTextColor(Palette.INK);
        grid.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !overlay.grid();
                overlay.setGrid(on);
                prefs.edit().putBoolean(PREF_GRID, on).apply();
                grid.setText(on ? "Thirds grid: on" : "Thirds grid: off");
            }
        });
        box.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        if (Saver.cardVolume(this) != null || Saver.wantsCard(this)) {
            final TextView store = navPill(storageLabel(), false);
            GradientDrawable sb = rounded(Palette.alpha(Palette.SURFACE, 0.6f), dp(23));
            sb.setStroke(dp(1), Palette.LINE);
            store.setBackground(sb);
            store.setTextColor(Palette.INK);
            store.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean card = !Saver.wantsCard(MainActivity.this);
                    prefs.edit().putBoolean(Saver.PREF_CARD, card).apply();
                    Journal.line(MainActivity.this, "STORAGE " + (card ? "card" : "phone")
                            + ", card volume " + Saver.cardVolume(MainActivity.this));
                    store.setText(storageLabel());
                }
            });
            LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
            stl.topMargin = dp(8);
            box.addView(store, stl);
        }
        TextView hint = text(13f, Palette.HINT);
        hint.setText("On the viewfinder: drag the frame, tap to focus, hold to lock focus and exposure, double tap to start over.");
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint);

        TextView version = text(12.5f, Palette.HINT);
        version.setText("Mirage " + VERSION + " \u00b7 diary in Documents/Mirage");
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
        if (exposureNs <= 0 || video) return;
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
    public void onShot(CameraEngine.Shot shot) {
        preview.setAlpha(0.3f);
        preview.animate().alpha(1f).setDuration(200).start();
        developer.develop(shot);
    }

    @Override
    public void onSaved(Uri uri, Bitmap b) {
        lastUri = uri;
        lastIsVideo = false;
        prefs.edit().putBoolean(PREF_LAST_VIDEO, false).apply();
        if (uri != null) prefs.edit().putString(PREF_LAST, uri.toString()).apply();
        if (b != null) thumb.setBitmap(b);
    }

    @Override
    public void onRoll() {
    }

    @Override
    public void onFailed() {
        Toast.makeText(this, "Not saved", Toast.LENGTH_SHORT).show();
    }

    private void openDarkroom() {
        if (lastIsVideo && lastUri != null) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, "video/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            start(i);
            return;
        }
        if (developer.roll().isEmpty()) {
            openLast();
            return;
        }
        startActivity(new Intent(this, Darkroom.class));
    }

    @Override
    public void onError(String message) {
        Journal.line(this, "ERROR shown: " + message);
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
                if (event.getRepeatCount() == 0) press();
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
