package com.shumtugle.mirage;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
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
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Drives the main rear camera module through Camera2.
 * All camera calls run on a private background thread; listener
 * callbacks are delivered on the main thread.
 */
final class CameraEngine {

    interface Listener {
        void onOpened(Size previewSize);
        void onExposure(long exposureNs, int iso, float aperture);
        void onSaved(boolean ok, Uri uri, Bitmap thumb);
        void onError(String message);
    }

    static final String FOLDER = "Mirage";

    static final int[] WB_MODES = {
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    };
    static final String[] WB_LABELS = {"Auto", "Daylight", "Cloudy", "Lamp", "Fluorescent"};

    private final Context context;
    private final Listener listener;
    private final CameraManager manager;
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
    private Size previewSize;
    private Size jpegSize;

    // Requested state. Written from the UI thread, read on the camera thread.
    private volatile float zoom = 1f;
    private volatile float ev = 0f;
    private volatile int wb = 0;
    private volatile int deviceRotation = 0;
    private volatile int lastJpegRotation = 0;
    private long lastExposurePost;
    private String info = "";

    CameraEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
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

    float getZoom() { return clampZoom(zoom); }
    float getEv() { return ev; }
    int getWb() { return wb; }

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
            chars = manager.getCameraCharacteristics(id);
            readCapabilities();
            chooseSizes();
            describe(id);
            manager.openCamera(id, deviceCallback, handler);
        } catch (CameraAccessException e) {
            fail(e.getMessage());
        } catch (SecurityException e) {
            fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
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
        int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        afModes = af == null ? new int[0] : af;
    }

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

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            device = d;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice d) {
            d.close();
            device = null;
        }
        @Override public void onError(CameraDevice d, int error) {
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
            fail("Session failed");
        }
    };

    /** Reports what the camera actually chose for the preview, a few times a second. */
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
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

    private void applyControls(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, WB_MODES[wb]);
        if (evRange != null) {
            int steps = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), currentEvSteps()));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
        }
        float z = getZoom();
        if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else if (activeArray != null) {
            // Older path: crop the centre of the sensor.
            int w = Math.round(activeArray.width() / z);
            int h = Math.round(activeArray.height() / z);
            int left = (activeArray.width() - w) / 2;
            int top = (activeArray.height() - h) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + w, top + h));
        }
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
                if (session == null || device == null || jpegReader == null) return;
                try {
                    CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(jpegReader.getSurface());
                    applyControls(b);
                    lastJpegRotation = (sensorOrientation + deviceRotation) % 360;
                    b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation);
                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                    session.capture(b.build(), null, h);
                } catch (CameraAccessException e) {
                    saved(null, null);
                } catch (IllegalStateException e) {
                    saved(null, null);
                }
            }
        });
    }

    // --------------------------------------------------------------- saving

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
        Uri uri = save(bytes);
        saved(uri, uri == null ? null : thumbnail(bytes, lastJpegRotation));
    }

    private void saved(final Uri uri, final Bitmap thumb) {
        main.post(new Runnable() {
            @Override public void run() {
                listener.onSaved(uri != null, uri, thumb);
            }
        });
    }

    /** A small upright copy of the photo for the last-shot button. */
    static Bitmap thumbnail(byte[] jpeg, int rotation) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (b == null || rotation == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rotation);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private Uri save(byte[] jpeg) {
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        return Build.VERSION.SDK_INT >= 29 ? saveScoped(name, jpeg) : saveLegacy(name, jpeg);
    }

    /** Android 10 and newer: the media store owns the file, no storage permission needed. */
    private Uri saveScoped(String name, byte[] jpeg) {
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

    /** Android 9 and older: a plain file in the camera folder, then a media scan. */
    @SuppressWarnings("deprecation")
    private Uri saveLegacy(String name, byte[] jpeg) {
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
        // Register the file so galleries see it and other apps can open it by a content address.
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.Media.DATA, f.getPath());
        try {
            return context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            MediaScannerConnection.scanFile(context, new String[]{f.getPath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(f);
        }
    }

    // -------------------------------------------------------------- helpers

    private void fail(String message) {
        final String m = message == null ? "Camera error" : message;
        main.post(new Runnable() {
            @Override public void run() {
                listener.onError(m);
            }
        });
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try { out.close(); } catch (Exception ignored) { }
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }
}
