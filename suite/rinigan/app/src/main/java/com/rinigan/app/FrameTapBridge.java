package com.rinigan.app;

import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * v6.2 — PEER-side frame bridge for links the classic MediaExtractor decoder
 * cannot handle: web pages (rendered by {@link WebViewStreamer}) and HLS/DASH
 * playlists (tapped via MediaPlayer + RGBA {@link ImageReader}).
 *
 * WHY THIS EXISTS (exact failure it fixes): in v6.1 the Camera2 reader path
 * (frames the peer receives in a video call) and the Camera1 preview-callback
 * path both ran MediaExtractor on the URL. A web page has no media container —
 * setDataSource() throws, no frames are ever produced, and the peer sees a
 * frozen/black camera even when the local preview worked. HLS was also
 * "preview-only". This bridge produces real frames for both.
 *
 * Output modes:
 *  WRITER — the app's ImageReader surface (Camera2). Frames are queued through
 *           an {@link ImageWriter} in the reader's own format
 *           (YUV_420_888 or JPEG for API 29+).
 *  BUFFER — Camera1 preview callbacks: latest NV21 frame is published to
 *           SharedState.data_buffer (consumed by Camera1Handler.processCallback).
 */
public final class FrameTapBridge {

    private static final String TAG = "[TapBridge]";
    private static final int TAP_FPS = 12;
    private static final long TAP_INTERVAL_MS = 1000 / TAP_FPS;
    private static final int TAP_SRC_W = 720;
    private static final int TAP_SRC_H = 1280;

    private static final Map<String, FrameTapBridge> ACTIVE = new ConcurrentHashMap<>();

    /** Output mode. */
    private enum Mode { WRITER, BUFFER }

    private final String tag;
    private final String url;
    private final Mode mode;
    private final boolean jpegReader;

    // MEDIA path (HLS/DASH/progressive) — MediaPlayer tapped by an RGBA ImageReader
    private HandlerThread mediaThread;
    private Handler mediaHandler;
    private MediaPlayer tapPlayer;
    private ImageReader tapReader;
    private volatile byte[] latestNv21;
    private final AtomicBoolean firstFrame = new AtomicBoolean(false);

    // WRITER mode state
    private ImageWriter writer;
    private Surface readerSurface;

    private volatile boolean stopped = false;
    private Handler pumpHandler;
    private HandlerThread pumpThread;

    private FrameTapBridge(String tag, String url, Mode mode, boolean jpegReader) {
        this.tag = tag;
        this.url = url;
        this.mode = mode;
        this.jpegReader = jpegReader;
    }

    // ======================== Public API ========================

    /**
     * Feeds an app ImageReader surface (Camera2 reader path) with frames for a
     * PAGE or HLS link. Safe to call repeatedly — replaces the previous bridge.
     */
    public static boolean startWriter(String tag, VideoSource src, Surface readerSurface,
                                      boolean jpegFormat) {
        if (tag == null || src == null || !src.isStream() || readerSurface == null
                || !readerSurface.isValid()) {
            return false;
        }
        // v6.3: relay first — frames come from the Stream Engine (LSPatch-safe)
        if (RelayClient.relayEnabled()) {
            boolean initiated = RelayClient.startWriter(tag, src.url, readerSurface,
                    jpegFormat, () -> startWriterInProcess(tag, src, readerSurface, jpegFormat));
            if (initiated) {
                HookDiag.report(SharedState.toast_content, "relay",
                        "reader pipeline relayed to engine (" + tag + ")");
                return true;
            }
        }
        return startWriterInProcess(tag, src, readerSurface, jpegFormat);
    }

    private static boolean startWriterInProcess(String tag, VideoSource src,
                                                Surface readerSurface, boolean jpegFormat) {
        stop(tag);
        FrameTapBridge bridge = new FrameTapBridge(tag, src.url,
                Mode.WRITER, jpegFormat);
        ACTIVE.put(tag, bridge);
        return bridge.begin(readerSurface);
    }

    /**
     * Feeds {@link SharedState#data_buffer} (Camera1 preview-callback path) with
     * frames for a PAGE or HLS link, sized to the app's preview buffer.
     */
    public static boolean startBuffer(String tag, VideoSource src, int width, int height) {
        if (tag == null || src == null || !src.isStream() || width <= 0 || height <= 0) {
            return false;
        }
        // v6.3: relay first — frames come from the Stream Engine (LSPatch-safe)
        if (RelayClient.relayEnabled()) {
            boolean initiated = RelayClient.startBuffer(tag, src.url, width, height,
                    () -> startBufferInProcess(tag, src, width, height));
            if (initiated) {
                HookDiag.report(SharedState.toast_content, "relay",
                        "preview-callback pipeline relayed to engine (" + tag + ")");
                return true;
            }
        }
        return startBufferInProcess(tag, src, width, height);
    }

    private static boolean startBufferInProcess(String tag, VideoSource src, int width, int height) {
        stop(tag);
        FrameTapBridge bridge = new FrameTapBridge(tag, src.url, Mode.BUFFER, false);
        ACTIVE.put(tag, bridge);
        return bridge.beginBuffer(width, height);
    }

    public static void stop(String tag) {
        if (tag == null) return;
        FrameTapBridge b = ACTIVE.remove(tag);
        if (b != null) b.teardown();
    }

    public static void stopAll() {
        for (String tag : ACTIVE.keySet().toArray(new String[0])) {
            stop(tag);
        }
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    // ======================== WRITER mode ========================

    private boolean begin(Surface target) {
        this.readerSurface = target;
        ensurePumpThread();
        try {
            // negotiate writer in the reader's format; JPEG needs the explicit
            // 3-arg newInstance available on API 29+
            if (jpegReader) {
                if (Build.VERSION.SDK_INT >= 29) {
                    writer = ImageWriter.newInstance(target, 3, android.graphics.ImageFormat.JPEG);
                } else {
                    log("JPEG writer needs API 29+ — peer frames unavailable (preview unaffected)");
                    teardown();
                    return false;
                }
            } else {
                writer = ImageWriter.newInstance(target, 3);
            }
        } catch (Throwable t) {
            log("ImageWriter creation failed: " + t);
            teardown();
            return false;
        }
        startSource(() -> pumpHandler.post(this::pumpWriter));
        return true;
    }

    /** Producer loop: publishes latestNv21 into the app's reader (reader-sized). */
    private void pumpWriter() {
        if (stopped || writer == null) return;
        byte[] latest = latestNv21;
        if (latest == null) {
            schedulePump();
            return;
        }
        try {
            Image img = writer.dequeueInputImage();
            int w = img.getWidth();
            int h = img.getHeight();
            if (jpegReader) {
                byte[] jpeg = Nv21Converter.nv21ToJpeg(
                        Nv21Converter.nv21Scale(latest, TAP_SRC_W, TAP_SRC_H, w, h), w, h, 90);
                if (jpeg != null) {
                    img.getPlanes()[0].getBuffer().put(jpeg);
                    writer.queueInputImage(img);
                } else {
                    img.close();
                }
            } else {
                byte[] scaled = Nv21Converter.nv21Scale(latest, TAP_SRC_W, TAP_SRC_H, w, h);
                if (scaled != null) {
                    Nv21Converter.fillYuvImage(img, scaled);
                    writer.queueInputImage(img);
                } else {
                    img.close();
                }
            }
        } catch (Throwable t) {
            log("writer pump error (non-fatal): " + t);
        }
        schedulePump();
    }

    private void schedulePump() {
        if (pumpHandler != null && !stopped) {
            pumpHandler.postDelayed(this::pumpWriter, TAP_INTERVAL_MS);
        }
    }

    // ======================== BUFFER mode (Camera1 callbacks) ========================

    private boolean beginBuffer(int width, int height) {
        ensurePumpThread();
        startSource(() -> pumpHandler.post(() -> pumpBuffer(width, height)));
        return true;
    }

    private void pumpBuffer(int width, int height) {
        if (stopped) return;
        byte[] latest = latestNv21;
        if (latest != null) {
            byte[] scaled = Nv21Converter.nv21Scale(latest, TAP_SRC_W, TAP_SRC_H, width, height);
            if (scaled != null) {
                SharedState.data_buffer = scaled;
                // register true dimensions so TransformApplicator shapes correctly
                TransformApplicator.reportStreamDimensions(url, width, height);
            }
        }
        if (pumpHandler != null && !stopped) {
            pumpHandler.postDelayed(() -> pumpBuffer(width, height), TAP_INTERVAL_MS);
        }
    }

    // ======================== Frame source (shared) ========================

    private void startSource(final Runnable onFirstFrame) {
        boolean page = LinkType.isPageUrl(url);
        if (page) {
            // ---- web page → WebViewStreamer software frames ----
            boolean ok = WebViewStreamer.startTap(tag + "-web", url, TAP_SRC_W, TAP_SRC_H,
                    false, (nv21, w, h) -> {
                        latestNv21 = nv21;
                        if (firstFrame.compareAndSet(false, true)) {
                            log("first page frame " + w + "x" + h);
                            if (onFirstFrame != null) onFirstFrame.run();
                        }
                    });
            if (!ok) log("WebView tap failed to start");
        } else {
            // ---- HLS/DASH/progressive → MediaPlayer tapped by RGBA ImageReader ----
            startMediaPlayerTap(onFirstFrame);
        }
    }

    private void startMediaPlayerTap(final Runnable onFirstFrame) {
        try {
            mediaThread = new HandlerThread("Rinigan-Tap");
            mediaThread.start();
            mediaHandler = new Handler(mediaThread.getLooper());

            final int rw = TAP_SRC_W;
            final int rh = TAP_SRC_H;
            tapReader = ImageReader.newInstance(rw, rh,
                    android.graphics.PixelFormat.RGBA_8888, 3);
            tapReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;
                    byte[] nv21 = Nv21Converter.rgbaImageToNv21(img);
                    if (nv21 != null) {
                        latestNv21 = nv21;
                        if (firstFrame.compareAndSet(false, true)) {
                            log("first tap frame " + rw + "x" + rh);
                            if (onFirstFrame != null) onFirstFrame.run();
                        }
                    }
                } catch (Throwable t) {
                    log("tap image error: " + t);
                } finally {
                    try {
                        if (img != null) img.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, mediaHandler);

            mediaHandler.post(() -> {
                if (stopped) return;
                try {
                    tapPlayer = new MediaPlayer();
                    tapPlayer.setSurface(tapReader.getSurface());
                    android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build();
                    tapPlayer.setAudioAttributes(attrs);
                    Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Rinigan/6.2");
                    android.content.Context ctx = SharedState.toast_content;
                    if (ctx != null) {
                        tapPlayer.setDataSource(ctx, android.net.Uri.parse(url), headers);
                    } else {
                        tapPlayer.setDataSource(url);
                    }
                    tapPlayer.setLooping(true);
                    tapPlayer.setVolume(0f, 0f); // silent tap — preview owns audio
                    tapPlayer.setOnPreparedListener(mp -> {
                        try {
                            mp.start();
                            log("tap player started: " + url);
                        } catch (Throwable t) {
                            log("tap start failed: " + t);
                        }
                    });
                    tapPlayer.setOnErrorListener((mp, what, extra) -> {
                        log("tap player error what=" + what + " extra=" + extra);
                        return true; // handled — writer keeps pumping (frames pause, never crash)
                    });
                    tapPlayer.prepareAsync();
                } catch (Throwable t) {
                    log("tap player open failed: " + t);
                }
            });
        } catch (Throwable t) {
            log("media tap init failed: " + t);
        }
    }

    // ======================== Teardown ========================

    private void teardown() {
        if (stopped) return;
        stopped = true;
        try {
            WebViewStreamer.stop(tag + "-web");
        } catch (Throwable ignored) {
        }
        try {
            if (pumpHandler != null) pumpHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        try {
            if (tapPlayer != null) {
                mediaHandler.post(() -> {
                    try {
                        tapPlayer.stop();
                        tapPlayer.release();
                    } catch (Throwable ignored) {
                    }
                });
                tapPlayer = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (tapReader != null) {
                tapReader.close();
                tapReader = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mediaThread != null) mediaThread.quitSafely();
        } catch (Throwable ignored) {
        }
        try {
            if (pumpThread != null) pumpThread.quitSafely();
        } catch (Throwable ignored) {
        }
        // v6.3: stop any relay client bound to this tag too
        try {
            RelayClient.stop(tag);
        } catch (Throwable ignored) {
        }
        latestNv21 = null;
        log("bridge stopped: " + tag);
    }

    private void log(String msg) {
        try {
            XposedBridge.log(TAG + "[" + tag + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Writer/buffer pump thread accessor (must run before first pump schedule). */
    private void ensurePumpThread() {
        if (pumpThread == null) {
            pumpThread = new HandlerThread("Rinigan-TapPump");
            pumpThread.start();
            pumpHandler = new Handler(pumpThread.getLooper());
        }
    }
}
