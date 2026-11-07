package com.rinigan.app;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * v6.2 — THE LINK DISPLAY ENVIRONMENT (بيئة عرض الرابط كما هو).
 *
 * Renders ANY link AS-IS — a website, a watch page, a live broadcast page — into
 * the camera pipeline. This is the exact root-cause fix for "the link shows
 * nothing when the camera opens": MediaPlayer/MediaExtractor can only stream
 * direct media files, so web pages could never appear. From v6.2 every link
 * gets a real browser display environment instead.
 *
 * Two rendering paths (never black):
 *
 *  PATH A — hardware (best): a {@link VirtualDisplay} renders onto the camera
 *      surface; a {@link Presentation} window hosts a real WebView (JS enabled,
 *      autoplay allowed, mixed content allowed). Inline HTML5 video players
 *      (live streams embedded in pages) play INSIDE the page and their frames
 *      land in the camera. 1080x1920 @ 240dpi virtual screen.
 *
 *  PATH B — software fallback (always works): an off-screen WebView is drawn
 *      with View.draw() into a bitmap ~10 times per second, then either posted
 *      onto the target Surface (preview mode) or converted to NV21 and handed
 *      to a {@link FrameSink} (tap mode, used by {@link FrameTapBridge} for
 *      the peer side of video calls).
 *
 * If EVERY path fails the caller receives onTotalFailure() and swaps to the
 * local fallback video — the camera never stays black.
 */
public final class WebViewStreamer {

    private static final String TAG = "[WebViewEnv]";

    /** Virtual display geometry for the rendered page. */
    private static final int ENV_W = 1080;
    private static final int ENV_H = 1920;
    private static final int ENV_DPI = 240;

    /** Software draw interval (~10 fps). */
    private static final long DRAW_INTERVAL_MS = 100;

    /** Time allowed for Path A to prove itself before falling back to Path B. */
    private static final long PATH_A_GRACE_MS = 12000;

    /** Receives software-rendered NV21 frames (tap mode). */
    public interface FrameSink {
        void onFrame(byte[] nv21, int width, int height);
    }

    private static final Map<String, WebViewStreamer> ACTIVE = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final String tag;
    private final String url;
    private final boolean unmute;

    // Path A state
    private DisplayManager displayManager;
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private WebView webEnvView;

    // Path B state
    private WebView softView;
    private Bitmap softBitmap;
    private Canvas softCanvas;
    private volatile boolean converting = false;
    private java.util.concurrent.ExecutorService convertPool;

    // shared
    private HandlerThread drawThread;
    private Handler drawHandler;
    private final SurfaceTarget surfaceTarget;   // preview mode
    private final FrameSink sink;               // tap mode
    private final int tapW;
    private final int tapH;
    private final Runnable onTotalFailure;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private volatile boolean pathBActive = false;
    private volatile long startedAtMs;

    private WebViewStreamer(String tag, String url, boolean unmute,
                            SurfaceTarget surfaceTarget, FrameSink sink,
                            int tapW, int tapH, Runnable onTotalFailure) {
        this.tag = tag;
        this.url = url;
        this.unmute = unmute;
        this.surfaceTarget = surfaceTarget;
        this.sink = sink;
        this.tapW = tapW;
        this.tapH = tapH;
        this.onTotalFailure = onTotalFailure;
    }

    // ======================== Public API ========================

    /**
     * Renders {@code url} onto {@code target} (a wrapped camera surface from
     * TransformApplicator). Path A → Path B → onTotalFailure.
     */
    public static boolean startPreview(String tag, String url, android.view.Surface target,
                                       boolean unmute, Runnable onTotalFailure) {
        if (tag == null || url == null || target == null || !target.isValid()) return false;
        stop(tag);
        WebViewStreamer env = new WebViewStreamer(tag, url, unmute, new SurfaceTarget(target),
                null, 0, 0, onTotalFailure);
        ACTIVE.put(tag, env);
        env.begin();
        return true;
    }

    /**
     * Renders {@code url} off-screen at {@code w}x{@code h} and streams NV21 frames
     * to {@code sink} (software path only — used for the peer side of calls).
     */
    public static boolean startTap(String tag, String url, int w, int h,
                                   boolean unmute, FrameSink sink) {
        if (tag == null || url == null || sink == null || w <= 0 || h <= 0) return false;
        stop(tag);
        WebViewStreamer env = new WebViewStreamer(tag, url, unmute, null, sink, w, h, null);
        ACTIVE.put(tag, env);
        env.begin();
        return true;
    }

    public static void stop(String tag) {
        if (tag == null) return;
        WebViewStreamer env = ACTIVE.remove(tag);
        if (env != null) env.teardown();
    }

    public static void stopAll() {
        for (String tag : ACTIVE.keySet().toArray(new String[0])) {
            stop(tag);
        }
    }

    /** Diagnostics: how many display environments are alive. */
    public static int activeCount() {
        return ACTIVE.size();
    }

    // ======================== Lifecycle ========================

    private void begin() {
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        log("display env starting for " + tag + " → " + url);
        toast("Rinigan: opening link environment…");
        MAIN.post(() -> {
            if (ACTIVE.get(tag) != this) return;
            if (surfaceTarget != null) {
                tryPathA();
            } else {
                // tap mode: software only (needs NV21 we can read back)
                startPathB();
            }
        });
    }

    // ======================== PATH A — hardware (VirtualDisplay + Presentation) ========================

    private void tryPathA() {
        try {
            Context ctx = SharedState.toast_content;
            if (ctx == null) {
                log("no host context → Path B");
                startPathB();
                return;
            }
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                log("no DisplayManager → Path B");
                startPathB();
                return;
            }
            virtualDisplay = displayManager.createVirtualDisplay(
                    "Rinigan-" + tag, ENV_W, ENV_H, ENV_DPI,
                    surfaceTarget.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                log("VirtualDisplay unavailable → Path B");
                startPathB();
                return;
            }
            Display display = virtualDisplay.getDisplay();

            MAIN.post(() -> {
                if (ACTIVE.get(tag) != this) return;
                try {
                    final WebView wv = buildWebView(ctx);
                    webEnvView = wv;
                    presentation = new Presentation(ctx, display) {
                        @Override
                        protected void onCreate(android.os.Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            android.widget.FrameLayout root = new android.widget.FrameLayout(getContext());
                            root.setBackgroundColor(Color.BLACK);
                            root.addView(wv, new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            setContentView(root);
                        }
                    };
                    // Presentation sets its own system window type internally;
                    // no manual window-type tweak needed (TYPE_PRESENTATION is
                    // not in the public SDK).
                    try {
                        presentation.show();
                    } catch (Throwable showFail) {
                        log("Presentation.show() failed → Path B: " + showFail);
                        startPathB();
                        return;
                    }
                    wv.loadUrl(url, headers());
                    log("PATH A live (hardware page rendering)");
                    running.set(true);
                    toast("Rinigan: link environment active");
                    armPathAWatchdog();
                    scheduleMuteLoop();
                } catch (Throwable t) {
                    log("Path A failed → Path B: " + t);
                    startPathB();
                }
            });
        } catch (Throwable t) {
            log("Path A setup failed → Path B: " + t);
            startPathB();
        }
    }

    /** If Path A never actually renders (silent failure), escalate to Path B. */
    private void armPathAWatchdog() {
        MAIN.postDelayed(() -> {
            if (ACTIVE.get(tag) != this || finished.get()) return;
            if (!pathBActive && surfaceTarget != null) {
                // Heuristic: a healthy Path A leaves the target surface valid and
                // the presentation showing. If the presentation was dismissed or
                // the surface died, escalate.
                boolean presShowing = false;
                try {
                    presShowing = presentation != null && presentation.isShowing();
                } catch (Throwable ignored) {
                }
                boolean surfaceOk = surfaceTarget.surface != null && surfaceTarget.surface.isValid();
                if (!presShowing || !surfaceOk) {
                    log("Path A watchdog: presentation=" + presShowing + " surface=" + surfaceOk + " → Path B");
                    startPathB();
                } else {
                    log("Path A watchdog: healthy");
                }
            }
        }, PATH_A_GRACE_MS);
    }

    // ======================== PATH B — software rendering ========================

    private void startPathB() {
        if (finished.get() || ACTIVE.get(tag) != this) return;
        pathBActive = true;
        MAIN.post(() -> {
            if (ACTIVE.get(tag) != this) return;
            try {
                releasePathA();
                Context ctx = SharedState.toast_content;
                if (ctx == null) {
                    totalFailure("no host context for WebView");
                    return;
                }
                final WebView wv = buildWebView(ctx);
                softView = wv;

                int drawW = (tapW > 0) ? tapW : Math.min(ENV_W, 720);
                int drawH = (tapH > 0) ? tapH : Math.min(ENV_H, 1280);
                // honor the requested aspect for tap mode; preview keeps page in a
                // portrait 720x1280 canvas (transform engine shapes it per app)
                softBitmap = Bitmap.createBitmap(drawW, drawH, Bitmap.Config.ARGB_8888);
                softCanvas = new Canvas(softBitmap);

                MAIN.post(() -> {
                    if (ACTIVE.get(tag) != this) return;
                    try {
                        wv.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(drawW, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(drawH, android.view.View.MeasureSpec.EXACTLY));
                        wv.layout(0, 0, drawW, drawH);
                        wv.loadUrl(url, headers());
                    } catch (Throwable t) {
                        totalFailure("soft WebView load failed: " + t);
                    }
                });

                drawThread = new HandlerThread("Rinigan-WebDraw");
                drawThread.start();
                drawHandler = new Handler(drawThread.getLooper());
                convertPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Rinigan-WebConvert");
                    t.setDaemon(true);
                    return t;
                });
                drawHandler.post(this::drawTick);
                log("PATH B live (software rendering " + drawW + "x" + drawH + ")");
                running.set(true);
                toast("Rinigan: link environment active (compat mode)");
                scheduleMuteLoop();
            } catch (Throwable t) {
                totalFailure("Path B init failed: " + t);
            }
        });
    }

    private void drawTick() {
        if (ACTIVE.get(tag) != this || finished.get()) return;
        final WebView wv = softView;
        if (wv == null || softBitmap == null || softCanvas == null) {
            scheduleNext();
            return;
        }
        boolean drew = false;
        try {
            wv.draw(softCanvas);
            drew = true;
        } catch (Throwable t) {
            log("draw failed: " + t);
        }
        if (drew) {
            if (surfaceTarget != null) {
                postBitmapToSurface();
            } else if (sink != null && !converting) {
                converting = true;
                final Bitmap frame = softBitmap;
                final int fw = frame.getWidth();
                final int fh = frame.getHeight();
                convertPool.execute(() -> {
                    try {
                        byte[] nv21 = Nv21Converter.bitmapToNv21(frame);
                        if (nv21 != null) sink.onFrame(nv21, fw, fh);
                    } catch (Throwable t) {
                        log("convert failed: " + t);
                    } finally {
                        converting = false;
                    }
                });
            }
        }
        scheduleNext();
    }

    private void postBitmapToSurface() {
        android.view.Surface s = surfaceTarget.surface;
        if (s == null || !s.isValid()) {
            totalFailure("target surface lost");
            return;
        }
        Canvas sc = null;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                sc = s.lockHardwareCanvas();
            }
        } catch (Throwable hardFail) {
            sc = null;
        }
        try {
            if (sc == null) sc = s.lockCanvas(null);
            if (sc != null) {
                sc.drawColor(Color.BLACK);
                sc.drawBitmap(softBitmap, 0, 0, null);
            }
        } catch (Throwable t) {
            log("surface post failed: " + t);
        } finally {
            try {
                if (sc != null) s.unlockCanvasAndPost(sc);
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleNext() {
        if (drawHandler != null && !finished.get() && ACTIVE.get(tag) == this) {
            drawHandler.postDelayed(this::drawTick, DRAW_INTERVAL_MS);
        }
    }

    // ======================== WebView factory ========================

    private WebView buildWebView(Context ctx) {
        WebView wv = new WebView(ctx);
        WebSettings s = wv.getSettings();
        try {
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false); // autoplay live streams
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setSupportZoom(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setSupportMultipleWindows(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setUserAgentString("Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
                    + "; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        } catch (Throwable t) {
            log("settings (partial): " + t);
        }
        try {
            CookieManager.getInstance().setAcceptCookie(true);
        } catch (Throwable ignored) {
        }
        wv.setBackgroundColor(Color.BLACK);
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                log("page loaded: " + loadedUrl);
                applyMutePolicy(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // stay inside the environment, follow the link as-is
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                // A failed resource never kills the environment — Chromium still
                // renders the error page, so frames keep flowing (never black).
                log("resource error (non-fatal): " + request.getUrl());
            }
        });
        wv.setWebChromeClient(new WebChromeClient());
        return wv;
    }

    /** Best-effort audio muting so page sound never leaks into the user's device. */
    private void applyMutePolicy(WebView view) {
        if (unmute) return; // user explicitly wants the page sound
        try {
            view.evaluateJavascript(
                    "try{document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.volume=0;});}"
                            + "catch(e){}", null);
        } catch (Throwable ignored) {
        }
    }

    /** Re-asserts the mute policy periodically (dynamic players appear after load). */
    private void scheduleMuteLoop() {
        if (unmute) return;
        MAIN.postDelayed(() -> {
            if (ACTIVE.get(tag) != this || finished.get()) return;
            WebView v = (pathBActive ? softView : webEnvView);
            if (v != null) applyMutePolicy(v);
            scheduleMuteLoop();
        }, 5000);
    }

    // ======================== Teardown ========================

    private void releasePathA() {
        try {
            if (presentation != null) {
                try {
                    presentation.dismiss();
                } catch (Throwable ignored) {
                }
                presentation = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (webEnvView != null) {
                MAIN.post(() -> {
                    try {
                        webEnvView.stopLoading();
                        webEnvView.destroy();
                    } catch (Throwable ignored) {
                    }
                });
                webEnvView = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void teardown() {
        if (finished.getAndSet(true)) return;
        running.set(false);
        releasePathA();
        try {
            if (drawHandler != null) drawHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        try {
            if (softView != null) {
                final WebView v = softView;
                MAIN.post(() -> {
                    try {
                        v.stopLoading();
                        v.destroy();
                    } catch (Throwable ignored) {
                    }
                });
                softView = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (convertPool != null) convertPool.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            if (drawThread != null) drawThread.quitSafely();
        } catch (Throwable ignored) {
        }
        softBitmap = null;
        softCanvas = null;
        log("display env stopped: " + tag);
    }

    private void totalFailure(String why) {
        log("TOTAL FAILURE for " + tag + ": " + why);
        toast("Rinigan: link environment failed — falling back");
        Runnable cb = onTotalFailure;
        teardown();
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable t) {
                log("failure callback threw: " + t);
            }
        }
    }

    // ======================== Helpers ========================

    private Map<String, String> headers() {
        Map<String, String> h = new java.util.HashMap<>();
        h.put("X-Requested-With", "");
        return h;
    }

    private void toast(String msg) {
        try {
            Context ctx = SharedState.toast_content;
            if (ctx != null) {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void log(String msg) {
        try {
            XposedBridge.log(TAG + "[" + tag + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Thin holder so Surface can be referenced uniformly. */
    private static final class SurfaceTarget {
        final android.view.Surface surface;
        SurfaceTarget(android.view.Surface surface) {
            this.surface = surface;
        }
    }
}
