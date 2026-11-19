package com.shumtugle.mirage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Drives the main rear camera module through Camera2.
 *
 * The viewfinder shows a little more than the photo: the view is zoomed to
 * three quarters of the photo's zoom, and the photo is a frame inside it that
 * the hand moves. When the camera can crop anywhere on its sensor, the frame
 * is asked of the sensor itself and loses nothing; when it can only crop
 * about the centre, the whole view is taken and the frame is cut afterwards.
 *
 * All camera calls run on a private background thread; listener callbacks
 * are delivered on the main thread.
 */
final class CameraEngine {

    interface Listener {
        void onOpened(Size previewSize);
        void onExposure(long exposureNs, int iso, float aperture);
        /** A shot has arrived: its bytes and how to finish it. */
        void onShot(Shot shot);
        void onError(String message);
    }

    /** One photo as the camera handed it over. */
    static final class Shot {
        byte[] jpeg;
        /** Degrees the pixels must still be turned clockwise to stand upright, when the camera left that to the file. */
        int rotation;
        /** Where the frame lies in the upright picture, 0..1, or null when the camera already cut it. */
        RectF cut;
        /** The lens in 35 mm terms, at the zoom of the finished photo. */
        double lens35;
    }

    /** The view shows this share of the photo's zoom, so a little more than the photo around it. */
    static final float VIEW_SHARE = 0.75f;

    static final int[] WB_MODES = {
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    };
    static final String[] WB_LABELS = {"Auto", "Daylight", "Cloudy", "Lamp", "Fluorescent"};

    private final Listener listener;
    private final CameraManager manager;
    private final Context context;
    private int lastAfState = -1;
    private long shotStarted;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private CaptureRequest.Builder previewBuilder;
    private Surface previewSurface;
    private SurfaceTexture texture;

    private CameraCharacteristics chars;
    private Range<Float> zoomRange;      // present when the device supports zoom ratio (API 30+)
    private Rect activeArray;
    private float maxDigitalZoom = 1f;
    private Range<Integer> evRange;
    private Rational evStep;
    private int sensorOrientation = 90;
    private int[] afModes = new int[0];
    private int[] noiseModes = new int[0];
    private int[] edgeModes = new int[0];
    private int maxAfRegions;
    private int maxAeRegions;
    private boolean freeformCrop;
    private double lens35 = 26.0;
    private Size previewSize;
    private Size jpegSize;
    private String info = "";

    // Requested state. Written from the UI thread, read on the camera thread.
    private volatile float zoom = 2f;
    private volatile float ev = 0f;
    private volatile int wb = 0;
    private volatile int deviceRotation = 0;
    /** Centre of the frame in the view, 0..1. */
    private volatile float frameX = 0.5f;
    private volatile float frameY = 0.5f;
    /** Point of focus in the view, 0..1; negative when the camera chooses for itself. */
    private volatile float focusX = -1f;
    private volatile float focusY = -1f;
    private volatile boolean locked;

    private long lastExposurePost;
    /** Settings of the shot on its way, kept until its bytes arrive. */
    private final List<Shot> pending = new ArrayList<Shot>();

    CameraEngine(Context context, Listener listener) {
        this.listener = listener;
        this.context = context.getApplicationContext();
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    // ---------------------------------------------------------------- state

    void applyPreset(Preset p) {
        zoom = p.zoom;
        ev = p.ev;
        wb = p.wb;
        refresh();
    }

    void setZoom(float z) {
        zoom = clampZoom(z);
        refresh();
    }

    void stepEv(int direction) {
        if (evRange == null || evStep == null || evStep.floatValue() == 0f) return;
        int steps = currentEvSteps() + direction;
        steps = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), steps));
        ev = steps * evStep.floatValue();
        refresh();
    }

    void cycleWb() {
        wb = (wb + 1) % WB_MODES.length;
        refresh();
    }

    void setDeviceRotation(int degrees) {
        deviceRotation = degrees;
    }

    /** Moves the frame; the view itself does not move. */
    void setFrame(float x, float y) {
        frameX = x;
        frameY = y;
    }

    /** Focuses and meters on a point of the view; with lock, holds exposure and focus there. */
    void focusAt(float x, float y, boolean lock) {
        focusX = x;
        focusY = y;
        locked = lock;
        MeteringRectangle[] at = spot(x, y);
        log(String.format(Locale.US, "%s at view (%.3f, %.3f) -> region %s; AF auto %s, AF regions %d, AE regions %d",
                lock ? "LOCK" : "FOCUS", x, y, at == null ? "none" : at[0].toString(),
                contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO), maxAfRegions, maxAeRegions));
        final Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (session == null || previewBuilder == null) return;
                try {
                    applyControls(previewBuilder);
                    if (maxAfRegions > 0 || contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                        // Cancel whatever scan is running, then start one on the new point.
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        session.capture(previewBuilder.build(), null, h);
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                        session.capture(previewBuilder.build(), null, h);
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    }
                    session.setRepeatingRequest(previewBuilder.build(), previewCallback, h);
                } catch (CameraAccessException ignored) {
                    // Closing; the next open starts from the camera's own focus.
                } catch (IllegalStateException ignored) {
                    // Same.
                }
            }
        });
    }

    /** Gives focus and exposure back to the camera. */
    void releaseFocus() {
        log("FOCUS released, camera chooses again");
        focusX = -1f;
        focusY = -1f;
        locked = false;
        final Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (session == null || previewBuilder == null) return;
                try {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                    session.capture(previewBuilder.build(), null, h);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    applyControls(previewBuilder);
                    session.setRepeatingRequest(previewBuilder.build(), previewCallback, h);
                } catch (CameraAccessException ignored) {
                    // Closing.
                } catch (IllegalStateException ignored) {
                    // Same.
                }
            }
        });
    }

    boolean focusing() { return focusX >= 0f; }
    boolean locked() { return locked; }
    float getZoom() { return clampZoom(zoom); }
    float getEv() { return ev; }
    int getWb() { return wb; }

    /** The zoom the view shows: three quarters of the photo's, never wider than the main lens. */
    float viewZoom() {
        return Math.max(1f, getZoom() * VIEW_SHARE);
    }

    /** The frame's side as a share of the view's side. */
    float frameShare() {
        return viewZoom() / getZoom();
    }

    float minZoom() {
        // Never below 1.0: below that a multi-module camera switches to the wide-angle module.
        return 1f;
    }

    float maxZoom() {
        if (chars == null) return 10f;
        float hi = zoomRange != null ? zoomRange.getUpper() : maxDigitalZoom;
        return Math.max(1f, hi);
    }

    private float clampZoom(float z) {
        return Math.max(minZoom(), Math.min(maxZoom(), z));
    }

    private int currentEvSteps() {
        if (evStep == null || evStep.floatValue() == 0f) return 0;
        return Math.round(ev / evStep.floatValue());
    }

    // ------------------------------------------------------------ lifecycle

    @SuppressLint("MissingPermission")
    void open(SurfaceTexture surfaceTexture) {
        if (thread != null) return;
        texture = surfaceTexture;
        thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            String id = pickMainRear(manager);
            if (id == null) {
                fail("No rear camera");
                return;
            }
            survey();
            chars = manager.getCameraCharacteristics(id);
            readCapabilities();
            chooseSizes();
            describe(id);
            log("OPEN camera " + id + "\n" + info + String.format(Locale.US,
                    "\nchosen photo %s, preview %s, sensor orientation %d, active array %s, cropping %s",
                    jpegSize, previewSize, sensorOrientation, activeArray, freeformCrop ? "freeform" : "centre only"));
            manager.openCamera(id, deviceCallback, handler);
        } catch (CameraAccessException e) {
            Journal.error(context, "open", e);
            fail(e.getMessage());
        } catch (SecurityException e) {
            Journal.error(context, "open", e);
            fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            Journal.error(context, "open", e);
            fail(e.getMessage());
        }
    }

    /** Every camera of the phone, as it describes itself to an ordinary app. */
    private void survey() {
        try {
            StringBuilder s = new StringBuilder("CAMERAS of this phone");
            for (String cid : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(cid);
                s.append("\n--- camera ").append(cid);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                s.append(" facing ").append(facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK
                        ? "back" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external");
                s.append("\n focal ").append(Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
                s.append(" mm, apertures ").append(Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)));
                s.append("\n sensor ").append(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).append(" mm, pixels ")
                        .append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)).append(", active ")
                        .append(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                        .append(", orientation ").append(c.get(CameraCharacteristics.SENSOR_ORIENTATION));
                s.append("\n capabilities ").append(Arrays.toString(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)));
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                s.append(", hardware level ").append(level);
                if (Build.VERSION.SDK_INT >= 28) s.append("\n physical ").append(c.getPhysicalCameraIds());
                if (Build.VERSION.SDK_INT >= 30) s.append("\n zoom ratio ").append(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
                s.append(", max digital zoom ").append(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
                s.append(", cropping type ").append(c.get(CameraCharacteristics.SCALER_CROPPING_TYPE));
                s.append("\n AF modes ").append(Arrays.toString(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)))
                        .append(", regions AF/AE/AWB ").append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).append('/')
                        .append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)).append('/')
                        .append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB));
                s.append("\n noise modes ").append(Arrays.toString(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)))
                        .append(", edge modes ").append(Arrays.toString(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)));
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) s.append("\n JPEG sizes ").append(firstSizes(map.getOutputSizes(ImageFormat.JPEG)));
                if (Build.VERSION.SDK_INT >= 31) {
                    StreamConfigurationMap max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                    if (max != null) {
                        s.append("\n JPEG sizes at full resolution ").append(firstSizes(max.getOutputSizes(ImageFormat.JPEG)));
                        s.append("\n RAW sizes at full resolution ").append(firstSizes(max.getOutputSizes(ImageFormat.RAW_SENSOR)));
                    }
                    s.append("\n pixel array at full resolution ")
                            .append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION));
                }
            }
            log(s.toString());
        } catch (Exception e) {
            Journal.error(context, "survey", e);
        }
    }

    private static String firstSizes(Size[] sizes) {
        if (sizes == null) return "none";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < sizes.length && i < 6; i++) {
            if (i > 0) s.append(' ');
            s.append(sizes[i]);
        }
        if (sizes.length > 6) s.append(" (+").append(sizes.length - 6).append(')');
        return s.toString();
    }

    private void log(String text) {
        Journal.line(context, text);
    }

    void close() {
        if (thread == null) return;
        final CameraCaptureSession s = session;
        final CameraDevice d = device;
        final ImageReader r = jpegReader;
        final Surface ps = previewSurface;
        session = null;
        device = null;
        jpegReader = null;
        previewSurface = null;
        previewBuilder = null;
        handler.post(new Runnable() {
            @Override public void run() {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                try { if (d != null) d.close(); } catch (Exception ignored) { }
                if (r != null) r.close();
                if (ps != null) ps.release();
            }
        });
        thread.quitSafely();
        thread = null;
        handler = null;
        synchronized (pending) {
            pending.clear();
        }
    }

    /**
     * The main module is the first rear camera the system lists.
     * On multi-module devices that is either the main module itself or a
     * logical camera whose 1.0 zoom maps to the main module; keeping zoom
     * at 1.0 or above keeps us off the wide-angle module.
     */
    private static String pickMainRear(CameraManager m) throws CameraAccessException {
        for (String id : m.getCameraIdList()) {
            Integer facing = m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    private void readCapabilities() {
        zoomRange = null;
        if (Build.VERSION.SDK_INT >= 30) {
            zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        }
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Float md = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        maxDigitalZoom = md == null ? 1f : md;
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;
        afModes = orEmpty(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        noiseModes = orEmpty(chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES));
        edgeModes = orEmpty(chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES));
        Integer af = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer ae = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        maxAfRegions = af == null ? 0 : af;
        maxAeRegions = ae == null ? 0 : ae;
        Integer crop = chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE);
        freeformCrop = crop != null && crop == CameraMetadata.SCALER_CROPPING_TYPE_FREEFORM;
        // The lens in 35 mm terms, for the reading of straight lines.
        float[] focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focals != null && focals.length > 0 && physical != null) {
            double diag = Math.hypot(physical.getWidth(), physical.getHeight());
            if (diag > 0) lens35 = focals[0] * 43.27 / diag;
        }
    }

    private void chooseSizes() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        jpegSize = largest4x3(map.getOutputSizes(ImageFormat.JPEG), Integer.MAX_VALUE, Integer.MAX_VALUE);
        previewSize = largest4x3(map.getOutputSizes(SurfaceTexture.class), 1920, 1440);
    }

    /** Largest 4:3 size within the bounds; falls back to the largest size at all. */
    private static Size largest4x3(Size[] sizes, int maxW, int maxH) {
        Size best = null;
        Size any = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (any == null || area > (long) any.getWidth() * any.getHeight()) any = s;
            if (s.getWidth() * 3 != s.getHeight() * 4) continue;
            if (s.getWidth() > maxW || s.getHeight() > maxH) continue;
            if (best == null || area > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : any;
    }

    // ---------------------------------------------------------------- facts

    /** Plain lines about the camera that was opened: what it gives an ordinary app. */
    String info() {
        return info;
    }

    int photoMegapixels() {
        if (jpegSize == null) return 0;
        return Math.round(jpegSize.getWidth() * (float) jpegSize.getHeight() / 1e6f);
    }

    private void describe(String id) {
        StringBuilder s = new StringBuilder();
        s.append("Camera ").append(id);
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean logical = contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
        s.append(logical ? ", several lenses behind one camera" : ", one lens").append('\n');
        s.append("Photo: ").append(sizeText(jpegSize)).append('\n');
        Size array = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        s.append("Sensor, usual mode: ").append(sizeText(array)).append('\n');
        s.append("Full resolution for this app: ").append(fullResolution(chars)).append('\n');
        if (logical && Build.VERSION.SDK_INT >= 28) {
            for (String pid : chars.getPhysicalCameraIds()) {
                try {
                    CameraCharacteristics pc = manager.getCameraCharacteristics(pid);
                    s.append("  lens ").append(pid).append(": ").append(fullResolution(pc)).append('\n');
                } catch (Exception ignored) {
                    s.append("  lens ").append(pid).append(": hidden").append('\n');
                }
            }
        }
        s.append("Frame anywhere on the sensor: ")
                .append(freeformCrop ? "yes, cut by the camera" : "no, cut after the shot").append('\n');
        s.append("Focus by touch: ").append(maxAfRegions > 0 ? "yes" : "no")
                .append(", exposure by touch: ").append(maxAeRegions > 0 ? "yes" : "no").append('\n');
        s.append(String.format(Locale.US, "Lens: %.0f mm in 35 mm terms", lens35)).append('\n');
        if (zoomRange != null) {
            s.append(String.format(Locale.US, "Zoom: %.1f\u00d7 to %.1f\u00d7", zoomRange.getLower(), zoomRange.getUpper()));
        } else {
            s.append(String.format(Locale.US, "Zoom: crop up to %.1f\u00d7", maxDigitalZoom));
        }
        info = s.toString();
    }

    /** Whether the sensor offers its full, unbinned resolution, and how big that is. */
    private static String fullResolution(CameraCharacteristics c) {
        if (Build.VERSION.SDK_INT < 31) return "not offered (Android 11 and older)";
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (!contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
            return "not offered";
        }
        Size full = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
        return "offered, " + sizeText(full);
    }

    private static String sizeText(Size s) {
        if (s == null) return "unknown";
        return s.getWidth() + "\u00d7" + s.getHeight() + String.format(Locale.US, " (%.0f MP)",
                s.getWidth() * (double) s.getHeight() / 1e6);
    }

    // --------------------------------------------------------------- session

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            device = d;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice d) {
            log("DEVICE disconnected");
            d.close();
            device = null;
        }
        @Override public void onError(CameraDevice d, int error) {
            log("DEVICE error " + error);
            d.close();
            device = null;
            fail("Camera error " + error);
        }
    };

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(texture);
            jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            jpegReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader reader) {
                    onJpeg(reader);
                }
            }, handler);

            if (Build.VERSION.SDK_INT >= 28) {
                final Handler h = handler;
                Executor executor = new Executor() {
                    @Override public void execute(Runnable r) {
                        h.post(r);
                    }
                };
                List<OutputConfiguration> outs = new ArrayList<OutputConfiguration>();
                outs.add(new OutputConfiguration(previewSurface));
                outs.add(new OutputConfiguration(jpegReader.getSurface()));
                device.createCaptureSession(new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outs, executor, sessionCallback));
            } else {
                device.createCaptureSession(
                        Arrays.asList(previewSurface, jpegReader.getSurface()), sessionCallback, handler);
            }
        } catch (CameraAccessException e) {
            fail(e.getMessage());
        } catch (IllegalStateException e) {
            fail(e.getMessage());
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession s) {
            if (device == null) return;
            session = s;
            try {
                previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewBuilder.addTarget(previewSurface);
                applyControls(previewBuilder);
                session.setRepeatingRequest(previewBuilder.build(), previewCallback, handler);
                final Size size = previewSize;
                main.post(new Runnable() {
                    @Override public void run() {
                        listener.onOpened(size);
                    }
                });
            } catch (CameraAccessException e) {
                fail(e.getMessage());
            } catch (IllegalStateException e) {
                fail(e.getMessage());
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            log("SESSION configure failed");
            fail("Session failed");
        }
    };

    /** Reports what the camera actually chose for the preview, a few times a second. */
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            Integer afs = result.get(CaptureResult.CONTROL_AF_STATE);
            if (afs != null && afs != lastAfState && focusX >= 0f) {
                lastAfState = afs;
                log("AF state " + afState(afs) + ", AE state " + result.get(CaptureResult.CONTROL_AE_STATE));
            }
            long now = System.currentTimeMillis();
            if (now - lastExposurePost < 400) return;
            lastExposurePost = now;
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Float ap = result.get(CaptureResult.LENS_APERTURE);
            final long e = exp == null ? 0 : exp;
            final int i = iso == null ? 0 : iso;
            final float a = ap == null ? 0f : ap;
            main.post(new Runnable() {
                @Override public void run() {
                    listener.onExposure(e, i, a);
                }
            });
        }
    };

    // ------------------------------------------------------------- requests

    /** Exposure, colour and focus as requested, and the view's zoom. */
    private void applyControls(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AWB_MODE, WB_MODES[wb]);
        if (evRange != null) {
            int steps = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), currentEvSteps()));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
        }
        b.set(CaptureRequest.CONTROL_AE_LOCK, locked);

        boolean byTouch = focusX >= 0f;
        if (byTouch && contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        } else if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        MeteringRectangle[] spot = byTouch ? spot(focusX, focusY) : null;
        if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, spot);
        if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, spot);

        zoomTo(b, viewZoom(), null);
    }

    /** Zoom about the centre, or, with a crop given, to that crop of the sensor at no zoom. */
    private void zoomTo(CaptureRequest.Builder b, float z, Rect crop) {
        if (crop != null) {
            if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
            }
            b.set(CaptureRequest.SCALER_CROP_REGION, crop);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else if (activeArray != null) {
            b.set(CaptureRequest.SCALER_CROP_REGION, centred(z));
        }
    }

    private Rect centred(float z) {
        int w = Math.round(activeArray.width() / z);
        int h = Math.round(activeArray.height() / z);
        int left = (activeArray.width() - w) / 2;
        int top = (activeArray.height() - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }

    /**
     * A small square of metering about a point of the view. With zoom by
     * ratio the camera reads regions in the zoomed view stretched over the
     * whole sensor; with the older crop, in the sensor itself.
     */
    private MeteringRectangle[] spot(float vx, float vy) {
        return spot(vx, vy, false);
    }

    /**
     * The same square for a request taken at no zoom with a crop of the
     * sensor: the point is carried from the zoomed view out to the whole sensor.
     */
    private MeteringRectangle[] spot(float vx, float vy, boolean unzoomed) {
        if (activeArray == null) return null;
        float[] s = rotate(vx, vy, (360 - sensorOrientation) % 360);
        float sx, sy, span;
        if (unzoomed) {
            float vz = viewZoom();
            sx = (0.5f + (s[0] - 0.5f) / vz) * activeArray.width();
            sy = (0.5f + (s[1] - 0.5f) / vz) * activeArray.height();
            span = 0.1f / vz;
        } else if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
            sx = s[0] * activeArray.width();
            sy = s[1] * activeArray.height();
            span = 0.1f;
        } else {
            Rect c = centred(viewZoom());
            sx = c.left + s[0] * c.width();
            sy = c.top + s[1] * c.height();
            span = 0.1f / viewZoom();
        }
        int hw = Math.max(8, Math.round(activeArray.width() * span / 2f));
        int hh = Math.max(8, Math.round(activeArray.height() * span / 2f));
        int left = clamp(Math.round(sx) - hw, 0, activeArray.width() - 2 * hw);
        int top = clamp(Math.round(sy) - hh, 0, activeArray.height() - 2 * hh);
        return new MeteringRectangle[]{new MeteringRectangle(left, top, 2 * hw, 2 * hh, MeteringRectangle.METERING_WEIGHT_MAX)};
    }

    /** The frame as a rectangle of the view, 0..1. */
    RectF frameInView() {
        float s = frameShare();
        float x = Math.max(s / 2f, Math.min(1f - s / 2f, frameX));
        float y = Math.max(s / 2f, Math.min(1f - s / 2f, frameY));
        return new RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f);
    }

    /** The frame as a crop of the sensor, at no zoom. */
    private Rect frameOnSensor() {
        RectF v = frameInView();
        RectF s = rotate(v, (360 - sensorOrientation) % 360);
        float vz = viewZoom();
        float vw = activeArray.width() / vz;
        float vh = activeArray.height() / vz;
        float ox = (activeArray.width() - vw) / 2f;
        float oy = (activeArray.height() - vh) / 2f;
        Rect r = new Rect(Math.round(ox + s.left * vw), Math.round(oy + s.top * vh),
                Math.round(ox + s.right * vw), Math.round(oy + s.bottom * vh));
        r.intersect(0, 0, activeArray.width(), activeArray.height());
        return r;
    }

    /** Pushes the current state into the running preview. */
    private void refresh() {
        final Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (session == null || previewBuilder == null) return;
                try {
                    applyControls(previewBuilder);
                    session.setRepeatingRequest(previewBuilder.build(), previewCallback, h);
                } catch (CameraAccessException ignored) {
                    // Session is closing; the next open applies the state anyway.
                } catch (IllegalStateException ignored) {
                    // Same.
                }
            }
        });
    }

    void capture() {
        final Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (session == null || device == null || jpegReader == null || activeArray == null) return;
                try {
                    CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(jpegReader.getSurface());
                    applyControls(b);
                    if (contains(noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                    }
                    if (contains(edgeModes, CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
                    }
                    int jpegRotation = (sensorOrientation + deviceRotation) % 360;
                    b.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation);
                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);

                    Shot shot = new Shot();
                    shot.lens35 = lens35 * getZoom();
                    if (frameShare() >= 0.999f) {
                        shot.cut = null;   // the frame is the whole view
                    } else if (freeformCrop) {
                        zoomTo(b, 1f, frameOnSensor());
                        if (focusX >= 0f) {
                            MeteringRectangle[] at = spot(focusX, focusY, true);
                            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, at);
                            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, at);
                        }
                        shot.cut = null;
                    } else {
                        // The camera can only crop about the centre: take the whole view and
                        // cut the frame afterwards, turned the way the photo will stand.
                        shot.cut = rotate(frameInView(), deviceRotation);
                    }
                    synchronized (pending) {
                        pending.add(shot);
                    }
                    log(String.format(Locale.US,
                            "SHOT zoom %.2f, view %.2f, frame share %.3f, frame in view %s, path %s, crop on sensor %s, "
                                    + "cut %s, device %d, jpeg %d, focus %s, lock %s, ev %.2f, wb %s",
                            getZoom(), viewZoom(), frameShare(), frameInView().toShortString(),
                            frameShare() >= 0.999f ? "whole view" : freeformCrop ? "camera cuts" : "cut after",
                            frameShare() < 0.999f && freeformCrop ? frameOnSensor().toShortString() : "-",
                            shot.cut == null ? "-" : shot.cut.toShortString(), deviceRotation, jpegRotation,
                            focusX >= 0f ? String.format(Locale.US, "(%.3f, %.3f)", focusX, focusY) : "camera",
                            locked, ev, WB_LABELS[wb]));
                    shotStarted = System.currentTimeMillis();
                    session.capture(b.build(), stillCallback, h);
                } catch (CameraAccessException e) {
                    Journal.error(context, "capture", e);
                    fail("Not taken");
                } catch (IllegalStateException e) {
                    Journal.error(context, "capture", e);
                    fail("Not taken");
                }
            }
        });
    }

    /** What the camera says it did for the still: the real test of the crop. */
    private final CameraCaptureSession.CaptureCallback stillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            StringBuilder b = new StringBuilder("STILL result: exposure ")
                    .append(res.get(CaptureResult.SENSOR_EXPOSURE_TIME)).append(" ns, ISO ")
                    .append(res.get(CaptureResult.SENSOR_SENSITIVITY)).append(", crop ")
                    .append(res.get(CaptureResult.SCALER_CROP_REGION));
            if (Build.VERSION.SDK_INT >= 30) b.append(", zoom ratio ").append(res.get(CaptureResult.CONTROL_ZOOM_RATIO));
            b.append(", AF ").append(afState(res.get(CaptureResult.CONTROL_AF_STATE)))
                    .append(", AE ").append(res.get(CaptureResult.CONTROL_AE_STATE))
                    .append(", noise ").append(res.get(CaptureResult.NOISE_REDUCTION_MODE))
                    .append(", edge ").append(res.get(CaptureResult.EDGE_MODE));
            if (Build.VERSION.SDK_INT >= 29) {
                String phys = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                if (phys != null) b.append(", lens in use ").append(phys);
            }
            log(b.toString());
        }

        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                              android.hardware.camera2.CaptureFailure f) {
            log("STILL failed, reason " + f.getReason());
        }
    };

    private static String afState(Integer s) {
        if (s == null) return "?";
        switch (s) {
            case CameraMetadata.CONTROL_AF_STATE_INACTIVE: return "inactive";
            case CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN: return "passive scan";
            case CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "passive focused";
            case CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN: return "active scan";
            case CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED: return "focused, locked";
            case CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "not focused, locked";
            case CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "passive unfocused";
            default: return String.valueOf(s);
        }
    }

    private void onJpeg(ImageReader reader) {
        Image img = reader.acquireNextImage();
        if (img == null) return;
        byte[] bytes;
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } finally {
            img.close();
        }
        final Shot shot;
        synchronized (pending) {
            shot = pending.isEmpty() ? new Shot() : pending.remove(0);
        }
        shot.jpeg = bytes;
        log(String.format(Locale.US, "JPEG %d bytes, %d ms after the shutter", bytes.length,
                System.currentTimeMillis() - shotStarted));
        main.post(new Runnable() {
            @Override public void run() {
                listener.onShot(shot);
            }
        });
    }

    // -------------------------------------------------------------- helpers

    /** A point of a unit square turned clockwise by a multiple of 90 degrees. */
    static float[] rotate(float x, float y, int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90: return new float[]{1f - y, x};
            case 180: return new float[]{1f - x, 1f - y};
            case 270: return new float[]{y, 1f - x};
            default: return new float[]{x, y};
        }
    }

    /** A rectangle of a unit square turned clockwise by a multiple of 90 degrees. */
    static RectF rotate(RectF r, int degrees) {
        float[] a = rotate(r.left, r.top, degrees);
        float[] b = rotate(r.right, r.bottom, degrees);
        return new RectF(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
    }

    private void fail(String message) {
        final String m = message == null ? "Camera error" : message;
        main.post(new Runnable() {
            @Override public void run() {
                listener.onError(m);
            }
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }

    private static int[] orEmpty(int[] a) {
        return a == null ? new int[0] : a;
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }
}
