package com.shumtugle.mirage;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Video from the main rear module at 720p and 120 frames a second, with
 * sound, played back at its real speed. The camera runs a high-speed session
 * when it offers one for that size; otherwise an ordinary recording session
 * at the fastest rate it allows, and the diary says so.
 *
 * The recorder draws from one lasting surface, so the session is built once
 * and each clip only brings a fresh recorder to it.
 *
 * Steady trades speed for a calm picture: an ordinary session at up to 60
 * frames, where the phone's own stabilisation, optical and electronic, may
 * be asked for. The high-speed session does not take those settings.
 */
final class VideoEngine {

    interface Listener {
        void onOpened(Size previewSize, int fps);
        void onSaved(Uri uri, boolean ok);
        void onError(String message);
    }

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int FPS = 120;
    static final float[] ZOOMS = {1f, 1.5f, 2f};

    private final Context context;
    private final Listener listener;
    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Surface recorderSurface;
    private SurfaceTexture texture;
    private CameraCharacteristics chars;
    private Range<Float> zoomRange;
    private Rect activeArray;
    private int sensorOrientation = 90;
    private boolean highSpeed;
    private Range<Integer> previewFps;
    private Range<Integer> recordFps;

    private MediaRecorder recorder;
    private ParcelFileDescriptor out;
    private Uri outUri;
    private File outFile;
    private volatile boolean recording;
    private long started;

    private volatile float zoom = 2f;
    private volatile boolean withSound = true;
    private volatile boolean steady;
    private int[] eisModes = new int[0];
    private int[] oisModes = new int[0];

    VideoEngine(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        manager = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
    }

    void setZoom(float z) {
        zoom = z;
        refresh();
    }

    void setSound(boolean on) {
        withSound = on;
    }

    /** Takes effect at the next open. */
    void setSteady(boolean on) {
        steady = on;
    }

    boolean steady() {
        return steady;
    }

    boolean recording() {
        return recording;
    }

    long elapsedMs() {
        return recording ? System.currentTimeMillis() - started : 0;
    }

    // ------------------------------------------------------------ lifecycle

    @SuppressLint("MissingPermission")
    void open(SurfaceTexture st) {
        if (thread != null) return;
        texture = st;
        thread = new HandlerThread("video");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            String id = null;
            for (String cid : manager.getCameraIdList()) {
                Integer f = manager.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                    id = cid;
                    break;
                }
            }
            if (id == null) {
                fail("No rear camera");
                return;
            }
            chars = manager.getCameraCharacteristics(id);
            if (Build.VERSION.SDK_INT >= 30) zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            int[] eis = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            eisModes = eis == null ? new int[0] : eis;
            oisModes = ois == null ? new int[0] : ois;
            Journal.line(context, "VIDEO stabilisation offered: electronic " + Arrays.toString(eisModes)
                    + ", optical " + Arrays.toString(oisModes) + "; steady asked " + steady);
            chooseRates();
            manager.openCamera(id, deviceCallback, handler);
        } catch (Exception e) {
            Journal.error(context, "video open", e);
            fail(e.getMessage());
        }
    }

    void close() {
        if (thread == null) return;
        if (recording) stopOnThread();
        final CameraCaptureSession s = session;
        final CameraDevice d = device;
        final Surface ps = previewSurface;
        final Surface rs = recorderSurface;
        final MediaRecorder r = recorder;
        session = null;
        device = null;
        previewSurface = null;
        recorderSurface = null;
        recorder = null;
        handler.post(new Runnable() {
            @Override public void run() {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                try { if (d != null) d.close(); } catch (Exception ignored) { }
                if (r != null) r.release();
                if (ps != null) ps.release();
                if (rs != null) rs.release();
            }
        });
        thread.quitSafely();
        thread = null;
        handler = null;
    }

    /** High speed when the camera offers it at 720p, else the fastest ordinary rate up to 120. */
    private void chooseRates() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        highSpeed = false;
        previewFps = null;
        recordFps = null;
        StringBuilder s = new StringBuilder("VIDEO capabilities: high speed ");
        boolean hsCap = caps != null && contains(caps,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
        s.append(hsCap);
        if (hsCap && map != null) {
            s.append("\nhigh-speed sizes ").append(Arrays.toString(map.getHighSpeedVideoSizes()));
            for (Size sz : map.getHighSpeedVideoSizes()) {
                s.append("\n ").append(sz).append(": ").append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(sz)));
                if (!steady && sz.getWidth() == WIDTH && sz.getHeight() == HEIGHT) {
                    for (Range<Integer> r : map.getHighSpeedVideoFpsRangesFor(sz)) {
                        if (r.getUpper() == FPS) {
                            highSpeed = true;
                            if (r.getLower() == FPS) recordFps = r;
                            else if (previewFps == null || r.getLower() < previewFps.getLower()) previewFps = r;
                        }
                    }
                }
            }
        }
        if (highSpeed) {
            if (recordFps == null) recordFps = new Range<Integer>(FPS, FPS);
            if (previewFps == null) previewFps = recordFps;
        } else {
            Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> best = new Range<Integer>(30, 30);
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    int cap = steady ? 60 : FPS;
                    if (r.getLower().equals(r.getUpper()) && r.getUpper() > best.getUpper() && r.getUpper() <= cap) best = r;
                }
                s.append("\nordinary fps ranges ").append(Arrays.toString(ranges));
            }
            previewFps = best;
            recordFps = best;
        }
        s.append(String.format(Locale.US, "\nchosen: %s session, preview %s, record %s",
                highSpeed ? "high-speed" : "ordinary", previewFps, recordFps));
        Journal.line(context, s.toString());
    }

    int fps() {
        return recordFps == null ? 30 : recordFps.getUpper();
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            device = d;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice d) {
            Journal.line(context, "VIDEO device disconnected");
            d.close();
            device = null;
        }
        @Override public void onError(CameraDevice d, int error) {
            Journal.line(context, "VIDEO device error " + error);
            d.close();
            device = null;
            fail("Camera error " + error);
        }
    };

    @SuppressWarnings("deprecation")
    private void createSession() {
        createSession(true);
    }

    /**
     * Builds the session from its own local pieces, so that a close arriving
     * from the screen in the middle cannot pull a surface out from under it.
     * The preview buffer is set to the video size right before it is used; the
     * screen may have stretched it to its own size since. A size refusal is
     * tried once more after a breath.
     */
    @SuppressWarnings("deprecation")
    private void createSession(final boolean mayRetry) {
        final CameraDevice d = device;
        final SurfaceTexture st = texture;
        if (d == null || st == null) return;
        Surface preview = null;
        Surface rec = null;
        try {
            st.setDefaultBufferSize(WIDTH, HEIGHT);
            preview = new Surface(st);
            rec = MediaCodec.createPersistentInputSurface();
            // The lasting surface takes its size from a recorder prepared once; this one never records.
            File dummy = new File(context.getCacheDir(), "warmup.mp4");
            MediaRecorder warm = newRecorder(false, rec);
            warm.setOutputFile(dummy.getPath());
            warm.prepare();
            warm.release();
            dummy.delete();
            if (device != d) {   // closed while we were building
                preview.release();
                rec.release();
                return;
            }
            previewSurface = preview;
            recorderSurface = rec;
            st.setDefaultBufferSize(WIDTH, HEIGHT);
            List<Surface> outs = Arrays.asList(preview, rec);
            if (highSpeed) {
                d.createConstrainedHighSpeedCaptureSession(outs, sessionCallback, handler);
            } else {
                d.createCaptureSession(outs, sessionCallback, handler);
            }
        } catch (IllegalArgumentException e) {
            Journal.error(context, "video session", e);
            if (preview != null) preview.release();
            if (rec != null) rec.release();
            previewSurface = null;
            recorderSurface = null;
            final Handler h = handler;
            if (mayRetry && h != null && device == d) {
                Journal.line(context, "VIDEO session: trying once more");
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (device == d) createSession(false);
                    }
                }, 300);
            } else {
                fail("Video not ready");
            }
        } catch (Exception e) {
            Journal.error(context, "video session", e);
            if (preview != null) preview.release();
            if (rec != null) rec.release();
            fail("Video not ready");
        }
    }

    /** The size the preview buffer must keep while video is open. */
    static void holdBufferSize(SurfaceTexture st) {
        st.setDefaultBufferSize(WIDTH, HEIGHT);
    }

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession s) {
            if (device == null) {
                s.close();
                return;
            }
            session = s;
            try {
                repeat(false);
                final int f = fps();
                main.post(new Runnable() {
                    @Override public void run() {
                        listener.onOpened(new Size(WIDTH, HEIGHT), f);
                    }
                });
            } catch (Exception e) {
                Journal.error(context, "video preview", e);
                fail("Video not ready");
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            Journal.line(context, "VIDEO session configure failed (" + (highSpeed ? "high-speed" : "ordinary") + ")");
            fail("Video not ready");
        }
    };

    /** The running request, to the preview alone or to the preview and the recorder. */
    private void repeat(boolean toRecorder) throws CameraAccessException {
        if (session == null || device == null) return;
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(previewSurface);
        if (toRecorder) b.addTarget(recorderSurface);
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, toRecorder ? recordFps : previewFps);
        if (!highSpeed) {
            // Stabilisation, only where the ordinary session can take it.
            if (steady) {
                if (Build.VERSION.SDK_INT >= 33 && contains(eisModes,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
                    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
                } else if (contains(eisModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                }
            }
            if (contains(oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
            }
        }
        float z = Math.max(1f, zoom);
        if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.min(z, zoomRange.getUpper()));
        } else if (activeArray != null) {
            int w = Math.round(activeArray.width() / z), h = Math.round(activeArray.height() / z);
            int l = (activeArray.width() - w) / 2, t = (activeArray.height() - h) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(l, t, l + w, t + h));
        }
        if (highSpeed) {
            CameraConstrainedHighSpeedCaptureSession hs = (CameraConstrainedHighSpeedCaptureSession) session;
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), toRecorder ? resultLog : null, handler);
        } else {
            session.setRepeatingRequest(b.build(), toRecorder ? resultLog : null, handler);
        }
    }

    private boolean loggedResult;

    /** One line from the first recorded frame: what the camera really does while recording. */
    private final CameraCaptureSession.CaptureCallback resultLog = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            if (loggedResult) return;
            loggedResult = true;
            StringBuilder b = new StringBuilder("VIDEO frame: exposure ")
                    .append(res.get(CaptureResult.SENSOR_EXPOSURE_TIME)).append(" ns, ISO ")
                    .append(res.get(CaptureResult.SENSOR_SENSITIVITY)).append(", fps range ")
                    .append(res.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)).append(", crop ")
                    .append(res.get(CaptureResult.SCALER_CROP_REGION))
                    .append(", electronic stabilisation ").append(res.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
                    .append(", optical ").append(res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE));
            if (Build.VERSION.SDK_INT >= 30) b.append(", zoom ratio ").append(res.get(CaptureResult.CONTROL_ZOOM_RATIO));
            if (Build.VERSION.SDK_INT >= 29) {
                String phys = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                if (phys != null) b.append(", lens in use ").append(phys);
            }
            Journal.line(context, b.toString());
        }
    };

    private void refresh() {
        final Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    repeat(recording);
                } catch (Exception ignored) {
                    // closing
                }
            }
        });
    }

    // ------------------------------------------------------------ recording

    private MediaRecorder newRecorder(boolean sound) throws Exception {
        return newRecorder(sound, recorderSurface);
    }

    private MediaRecorder newRecorder(boolean sound, Surface input) throws Exception {
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        if (sound) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        r.setVideoSize(WIDTH, HEIGHT);
        r.setVideoFrameRate(fps());
        r.setVideoEncodingBitRate(fps() >= 120 ? 28_000_000 : 12_000_000);
        if (sound) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(48000);
            r.setAudioChannels(2);
            r.setAudioEncodingBitRate(192000);
        }
        r.setInputSurface(input);
        return r;
    }

    /** Starts a clip; rotation is how the phone is held, in degrees, as for photos. */
    void start(final int deviceRotation) {
        final Handler h = handler;
        if (h == null || recording) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (session == null || recording) return;
                String name = "VID_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new java.util.Date()) + ".mp4";
                try {
                    boolean sound = withSound;
                    recorder = newRecorder(sound);
                    recorder.setOrientationHint((sensorOrientation + deviceRotation) % 360);
                    if (!openOutput(name)) throw new IllegalStateException("no place for the clip");
                    if (out != null) recorder.setOutputFile(out.getFileDescriptor());
                    else recorder.setOutputFile(outFile.getPath());
                    recorder.prepare();
                    loggedResult = false;
                    repeat(true);
                    recorder.start();
                    started = System.currentTimeMillis();
                    recording = true;
                    Journal.line(context, String.format(Locale.US,
                            "RECORD start %s: %dx%d at %d fps, %s, zoom %.1f, sound %s, turn %d",
                            name, WIDTH, HEIGHT, fps(), highSpeed ? "high-speed" : "ordinary", zoom, sound,
                            (sensorOrientation + deviceRotation) % 360));
                } catch (Exception e) {
                    Journal.error(context, "record start", e);
                    abandon();
                    fail("Recording did not start");
                }
            }
        });
    }

    void stop() {
        final Handler h = handler;
        if (h == null || !recording) return;
        h.post(new Runnable() {
            @Override public void run() {
                stopOnThread();
            }
        });
    }

    private void stopOnThread() {
        if (!recording) return;
        recording = false;
        long ms = System.currentTimeMillis() - started;
        boolean ok = true;
        // First stop feeding the recorder, then let it close the file.
        try {
            repeat(false);
        } catch (Exception ignored) {
            // closing
        }
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;   // too short to hold a single frame
            Journal.error(context, "record stop", e);
        }
        try {
            recorder.release();
        } catch (RuntimeException ignored) {
            // already gone
        }
        recorder = null;
        final Uri uri = finishOutput(ok);
        Journal.line(context, "RECORD stop after " + ms + " ms, " + (ok ? "saved " + uri : "nothing kept"));
        final boolean saved = ok && uri != null;
        main.post(new Runnable() {
            @Override public void run() {
                listener.onSaved(uri, saved);
            }
        });
    }

    /** A place for the clip in the camera folder, on the phone or on the card. */
    @SuppressWarnings("deprecation")
    private boolean openOutput(String name) throws Exception {
        out = null;
        outUri = null;
        outFile = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + Saver.FOLDER);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            String volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
            if (Saver.wantsCard(context) && Saver.cardVolume(context) != null) volume = Saver.cardVolume(context);
            outUri = context.getContentResolver().insert(MediaStore.Video.Media.getContentUri(volume), v);
            if (outUri == null) return false;
            out = context.getContentResolver().openFileDescriptor(outUri, "w");
            return out != null;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), Saver.FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        outFile = new File(dir, name);
        return true;
    }

    private Uri finishOutput(boolean keep) {
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
            // closed
        }
        out = null;
        ContentResolver cr = context.getContentResolver();
        if (outUri != null) {
            Uri u = outUri;
            outUri = null;
            if (!keep) {
                cr.delete(u, null, null);
                return null;
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
            return u;
        }
        if (outFile != null) {
            File f = outFile;
            outFile = null;
            if (!keep) {
                f.delete();
                return null;
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.DATA, f.getPath());
            try {
                Uri u = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (u != null) return u;
            } catch (Exception ignored) {
                // fall through
            }
            return Uri.fromFile(f);
        }
        return null;
    }

    private void abandon() {
        recording = false;
        if (recorder != null) {
            try { recorder.release(); } catch (RuntimeException ignored) { }
            recorder = null;
        }
        finishOutput(false);
        try {
            repeat(false);
        } catch (Exception ignored) {
            // closing
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

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    static List<String> zoomNames() {
        List<String> n = new ArrayList<String>();
        for (float z : ZOOMS) n.add(z == (int) z ? (int) z + "\u00d7" : String.format(Locale.US, "%.1f\u00d7", z));
        return n;
    }
}
