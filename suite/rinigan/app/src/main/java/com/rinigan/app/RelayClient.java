package com.rinigan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * v6.3 — RELAY CLIENT (hook side, runs inside the TARGET app process).
 *
 * The LSPatch-proof half of the pipeline. Connects to the Stream Engine
 * (running in the Rinigan app process) over an abstract-namespace unix
 * socket — no INTERNET permission, no WebView, no MediaPlayer, no windows
 * are needed in the target app at all. Received NV21 frames are pushed into
 * whichever surface/buffer the camera pipeline hands us.
 *
 * Three modes:
 *  SURFACE — draws frames onto a (GL-relay-wrapped) camera surface via Canvas.
 *  BUFFER  — publishes scaled frames into SharedState.data_buffer for the
 *            Camera1 onPreviewFrame path.
 *  WRITER  — queues frames through an ImageWriter into a Camera2 ImageReader.
 *
 * Resilience chain (never black, never stuck):
 *   connect fail → ask app to start engine (provider) → retry 1s/2s/4s/8s
 *   → still failing → onPermanentFailure → in-process v6.2 pipeline →
 *   local video fallback (all pre-existing).
 */
public final class RelayClient {

    private static final String TAG = "[Relay]";
    private static final long[] CONNECT_BACKOFF_MS = {1000, 2000, 4000, 8000};
    private static final int MAX_CONNECT_TRIES = 4;

    private static final Map<String, RelayClient> ACTIVE = new ConcurrentHashMap<>();

    /** Modes. */
    private enum Mode { SURFACE, BUFFER, WRITER }

    private final String tag;
    private final String url;
    private final Mode mode;
    private final Runnable onPermanentFailure;

    // SURFACE
    private Surface surface;
    // WRITER
    private android.media.ImageWriter writer;
    private boolean jpegWriter;

    // BUFFER
    private int bufW;
    private int bufH;

    private volatile boolean stopped = false;
    private Thread worker;
    private static HandlerThread retryThread;
    private static Handler retryHandler;

    private RelayClient(String tag, String url, Mode mode, Runnable onPermanentFailure) {
        this.tag = tag;
        this.url = url;
        this.mode = mode;
        this.onPermanentFailure = onPermanentFailure;
    }

    // ======================== Public API ========================

    /**
     * Starts feeding {@code target} with engine frames. Returns false when the
     * relay feature is refused outright (then caller falls back in-process).
     *
     * @param onPermanentFailure invoked after all connect attempts failed; the
     *                           caller rebuilds the classic in-process pipeline.
     */
    public static boolean startSurface(String tag, String url, Surface target,
                                       Runnable onPermanentFailure) {
        if (tag == null || url == null || url.isEmpty()
                || target == null || !target.isValid()) return false;
        stop(tag);
        RelayClient c = new RelayClient(tag, url, Mode.SURFACE, onPermanentFailure);
        c.surface = target;
        ACTIVE.put(tag, c);
        c.begin();
        return true;
    }

    /** Feeds SharedState.data_buffer (Camera1 preview callbacks). */
    public static boolean startBuffer(String tag, String url, int width, int height) {
        return startBuffer(tag, url, width, height, null);
    }

    /** Feeds SharedState.data_buffer with an in-process fallback callback. */
    public static boolean startBuffer(String tag, String url, int width, int height,
                                      Runnable onPermanentFailure) {
        if (tag == null || url == null || url.isEmpty() || width <= 0 || height <= 0) return false;
        stop(tag);
        RelayClient c = new RelayClient(tag, url, Mode.BUFFER, onPermanentFailure);
        c.bufW = width;
        c.bufH = height;
        ACTIVE.put(tag, c);
        c.begin();
        return true;
    }

    /** Feeds a Camera2 ImageReader surface through an ImageWriter. */
    public static boolean startWriter(String tag, String url, Surface readerSurface,
                                      boolean jpegFormat, Runnable onPermanentFailure) {
        if (tag == null || url == null || url.isEmpty()
                || readerSurface == null || !readerSurface.isValid()) return false;
        if (jpegFormat && Build.VERSION.SDK_INT < 29) return false;
        stop(tag);
        RelayClient c = new RelayClient(tag, url, Mode.WRITER, onPermanentFailure);
        c.surface = readerSurface;
        c.jpegWriter = jpegFormat;
        ACTIVE.put(tag, c);
        c.begin();
        return true;
    }

    public static void stop(String tag) {
        if (tag == null) return;
        RelayClient c = ACTIVE.remove(tag);
        if (c != null) c.teardown();
    }

    public static void stopAll() {
        for (String t : ACTIVE.keySet().toArray(new String[0])) {
            stop(t);
        }
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** True when relay mode is enabled in the config (default ON). */
    public static boolean relayEnabled() {
        try {
            return HookGuards.getConfig().getBoolean(ConfigManager.KEY_RELAY_MODE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    // ======================== Connection loop ========================

    private void begin() {
        worker = new Thread(this::runLoop, "Rinigan-Relay-" + tag);
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        int attempt = 0;
        while (!stopped && attempt < MAX_CONNECT_TRIES) {
            if (attempt == 0) {
                // first failure may mean the app process was killed — revive it
                HookDiag.ensureEngine(appContext());
            }
            try {
                LocalSocket socket = new LocalSocket();
                LocalSocketAddress addr = new LocalSocketAddress(
                        IpcContract.RELAY_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT);
                if (Build.VERSION.SDK_INT >= 21) {
                    socket.connect(addr, 3000);
                } else {
                    socket.connect(addr);
                }
                log("connected to engine (attempt " + (attempt + 1) + ")");
                HookDiag.report(appContext(), "relay",
                        "connected to link environment (target=" + tag + ")");
                attempt = 0; // reset — we are streaming again
                readFrames(socket);
                log("engine stream ended");
            } catch (Throwable t) {
                if (stopped) return;
                log("connect/read failed: " + t);
            }
            if (stopped) return;
            attempt++;
            long delay = CONNECT_BACKOFF_MS[Math.min(attempt - 1, CONNECT_BACKOFF_MS.length - 1)];
            log("reconnect in " + delay + "ms (attempt " + attempt + "/" + MAX_CONNECT_TRIES + ")");
            HookDiag.report(appContext(), "relay",
                    "engine unreachable, retry " + attempt + "/" + MAX_CONNECT_TRIES);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!stopped) {
            log("engine unreachable after " + MAX_CONNECT_TRIES + " attempts → in-process fallback");
            HookDiag.report(appContext(), "fallback",
                    "link environment unreachable — falling back to in-process pipeline");
            if (onPermanentFailure != null) {
                try {
                    retry(() -> {
                        if (!stopped) onPermanentFailure.run();
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Reads the handshake + frames until EOF or teardown. */
    private void readFrames(LocalSocket socket) throws IOException {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 1 << 16));
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != IpcContract.RELAY_MAGIC) {
            throw new IOException("bad handshake magic 0x" + Integer.toHexString(magic));
        }
        log("handshake ok (v" + version + ")");
        onConnected();

        long lastReport = 0;
        int frames = 0;
        while (!stopped) {
            magic = in.readInt();
            if (magic != IpcContract.RELAY_MAGIC) {
                throw new IOException("frame sync lost (magic 0x"
                        + Integer.toHexString(magic) + ")");
            }
            int w = in.readInt();
            int h = in.readInt();
            in.readInt(); // seq (informational)
            int len = in.readInt();
            if (w <= 0 || h <= 0 || len <= 0 || len > 64 * 1024 * 1024) {
                throw new IOException("bad frame header " + w + "x" + h + " len=" + len);
            }
            byte[] nv21 = new byte[len];
            in.readFully(nv21);
            frames++;
            deliver(nv21, w, h);
            long now = System.currentTimeMillis();
            if (now - lastReport > 5000) {
                lastReport = now;
                HookDiag.report(appContext(), "relay",
                        "streaming — " + frames + " frames received into " + tag);
                TransformApplicator.reportStreamDimensions(url, w, h);
            }
        }
    }

    // ======================== Delivery per mode ========================

    private boolean connectedOnce = false;

    private void onConnected() {
        connectedOnce = true;
    }

    private void deliver(byte[] nv21, int w, int h) {
        try {
            switch (mode) {
                case SURFACE:
                    drawToSurface(nv21, w, h);
                    break;
                case BUFFER:
                    byte[] scaled = Nv21Converter.nv21Scale(nv21, w, h, bufW, bufH);
                    if (scaled != null) {
                        SharedState.data_buffer = scaled;
                        TransformApplicator.reportStreamDimensions(url, bufW, bufH);
                    }
                    break;
                case WRITER:
                    prepareWriterIfNeeded();
                    pumpWriter(nv21, w, h);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            log("deliver error (non-fatal): " + t);
        }
    }

    /** SURFACE mode: NV21 → Bitmap → Canvas post (the WebViewStreamer-proven path). */
    private void drawToSurface(byte[] nv21, int w, int h) {
        Surface target = surface;
        if (target == null || !target.isValid()) return;
        Bitmap bmp = Nv21Converter.nv21ToBitmap(nv21, w, h);
        if (bmp == null) return;
        Canvas c = null;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                c = target.lockHardwareCanvas();
            }
            if (c == null) c = target.lockCanvas(null);
            if (c != null) {
                c.drawBitmap(bmp, 0, 0, null);
            }
        } catch (Throwable t) {
            log("canvas draw failed: " + t);
        } finally {
            if (c != null) {
                try {
                    target.unlockCanvasAndPost(c);
                } catch (Throwable ignored) {
                }
            }
        }
        bmp.recycle();
    }

    /** WRITER mode: mirrors FrameTapBridge.pumpWriter with relayed frames. */
    private void pumpWriter(byte[] nv21, int w, int h) {
        android.media.ImageWriter wtr = writer;
        if (wtr == null) return;
        try {
            android.media.Image img = wtr.dequeueInputImage();
            int dw = img.getWidth();
            int dh = img.getHeight();
            if (jpegWriter) {
                byte[] jpeg = Nv21Converter.nv21ToJpeg(
                        Nv21Converter.nv21Scale(nv21, w, h, dw, dh), dw, dh, 90);
                if (jpeg != null) {
                    img.getPlanes()[0].getBuffer().put(jpeg);
                    wtr.queueInputImage(img);
                } else {
                    img.close();
                }
            } else {
                byte[] scaled = Nv21Converter.nv21Scale(nv21, w, h, dw, dh);
                if (scaled != null) {
                    Nv21Converter.fillYuvImage(img, scaled);
                    wtr.queueInputImage(img);
                } else {
                    img.close();
                }
            }
        } catch (Throwable t) {
            log("writer pump error (non-fatal): " + t);
        }
    }

    // ======================== Teardown / helpers ========================

    private void teardown() {
        stopped = true;
        try {
            if (worker != null) worker.interrupt();
        } catch (Throwable ignored) {
        }
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (Throwable ignored) {
        }
        surface = null;
        log("relay stopped: " + tag);
    }

    private void onWriterReady() {
        // no-op (writer is created lazily in prepareWriterIfNeeded)
    }

    private boolean ensureWriter() {
        if (writer != null || mode != Mode.WRITER) return writer != null;
        try {
            if (jpegWriter) {
                writer = android.media.ImageWriter.newInstance(surface, 3,
                        android.graphics.ImageFormat.JPEG);
            } else {
                writer = android.media.ImageWriter.newInstance(surface, 3);
            }
            return true;
        } catch (Throwable t) {
            log("ImageWriter creation failed: " + t);
            return false;
        }
    }

    /** Lazily creates the ImageWriter before the first WRITER frame. */
    private void prepareWriterIfNeeded() {
        if (mode == Mode.WRITER && writer == null) ensureWriter();
    }

    private Context appContext() {
        return SharedState.toast_content;
    }

    private void retry(Runnable r) {
        synchronized (RelayClient.class) {
            if (retryHandler == null) {
                retryThread = new HandlerThread("Rinigan-RelayRetry");
                retryThread.start();
                retryHandler = new Handler(retryThread.getLooper());
            }
        }
        retryHandler.postDelayed(r, 50);
    }

    private void log(String msg) {
        try {
            XposedBridge.log(TAG + "[" + tag + "] " + msg);
        } catch (Throwable ignored) {
        }
        LogUtil.log(TAG + "[" + tag + "] " + msg);
    }
}
