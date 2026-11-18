package com.shumtugle.mirage;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A camera with presets. It always shoots on the main rear module and
 * opens at the default preset. The whole interface is built in code.
 */
public final class MainActivity extends Activity implements CameraEngine.Listener {
    static final String VERSION = "0.1";

    private static final int REQ_PERMS = 1;
    private static final int INK = Color.rgb(0xE8, 0xE2, 0xD6);
    private static final int ACCENT = Color.rgb(0xE0, 0xA8, 0x4A);

    private PresetStore store;
    private CameraEngine engine;
    private Preset active;

    private FrameLayout root;
    private TextureView preview;
    private LinearLayout panel;
    private LinearLayout presetRow;
    private TextView zoomLabel;
    private TextView evLabel;
    private TextView wbButton;

    private OrientationEventListener orientation;
    private boolean resumed;
    private boolean askedPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new PresetStore(this);
        engine = new CameraEngine(this, this);
        active = store.getDefault();
        engine.applyPreset(active);   // the very first frame is already at the default zoom

        buildUi();

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
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (resumed && missingPermissions().length == 0) engine.open(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        attachGestures();

        zoomLabel = label(22);
        root.addView(zoomLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x99000000);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(presetRow);
        panel.addView(scroll);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(14), 0, 0);

        TextView evMinus = roundButton("\u2212", 44);
        evMinus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.stepEv(-1); updateLabels(); }
        });
        evLabel = label(14);
        evLabel.setMinWidth(dp(72));
        evLabel.setGravity(Gravity.CENTER);
        TextView evPlus = roundButton("+", 44);
        evPlus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.stepEv(1); updateLabels(); }
        });

        TextView shutter = new TextView(this);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(INK);
        sd.setStroke(dp(4), ACCENT);
        shutter.setBackground(sd);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.capture(); }
        });

        wbButton = roundButton("AWB", 56);
        wbButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        wbButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { engine.cycleWb(); updateLabels(); }
        });

        controls.addView(evMinus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        controls.addView(evLabel);
        controls.addView(evPlus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        controls.addView(wbButton, new LinearLayout.LayoutParams(dp(56), dp(56)));
        panel.addView(controls);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // The window draws behind the system bars: the label and the panel step clear of them.
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
                FrameLayout.LayoutParams zl = (FrameLayout.LayoutParams) zoomLabel.getLayoutParams();
                zl.topMargin = t + dp(16);
                zoomLabel.setLayoutParams(zl);
                panel.setPadding(dp(12), dp(10), dp(12), b + dp(16));
                return in;
            }
        });

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        refreshPresets();
        updateLabels();
    }

    /** Pinch to zoom; double tap returns to the default preset. */
    private void attachGestures() {
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
        });
        preview.setOnTouchListener(new View.OnTouchListener() {
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
            TextView chip = new TextView(this);
            chip.setText(p.isDefault ? p.name + " \u2022" : p.name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            boolean on = p == active;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), on ? ACCENT : INK);
            bg.setColor(on ? ACCENT : Color.TRANSPARENT);
            chip.setBackground(bg);
            chip.setTextColor(on ? Color.BLACK : INK);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(p); }
            });
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { presetMenu(p); return true; }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            presetRow.addView(chip, lp);
        }
        TextView add = new TextView(this);
        add.setText("+");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        add.setTextColor(INK);
        add.setPadding(dp(16), dp(4), dp(16), dp(4));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addFromCurrent(); }
        });
        presetRow.addView(add);
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        zoomLabel.setText(String.format(Locale.US, "%.1f\u00d7", engine.getZoom()));
        evLabel.setText(String.format(Locale.US, "%+.1f EV", engine.getEv()));
        wbButton.setText(CameraEngine.WB_LABELS[engine.getWb()]);
    }

    // ------------------------------------------------------ engine callbacks

    @Override
    public void onOpened(Size previewSize) {
        // Preview buffers are landscape; in portrait the view is tall by the same ratio.
        int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = w * previewSize.getWidth() / previewSize.getHeight();
        preview.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        updateLabels();
    }

    @Override
    public void onSaved(boolean ok) {
        if (ok) {
            preview.setAlpha(0.3f);
            preview.animate().alpha(1f).setDuration(200).start();
        } else {
            Toast.makeText(this, "Not saved", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

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

    private TextView label(int sp) {
        TextView t = new TextView(this);
        t.setTextColor(INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        return t;
    }

    private TextView roundButton(String text, int sizeDp) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(INK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1), INK);
        b.setBackground(bg);
        b.setMinWidth(dp(sizeDp));
        b.setMinHeight(dp(sizeDp));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
