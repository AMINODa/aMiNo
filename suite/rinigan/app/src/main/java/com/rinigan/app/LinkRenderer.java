package com.rinigan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * v6.3 — THE LINK DISPLAY ENVIRONMENT (runs in the Rinigan app process).
 *
 * ROOT-CAUSE ARCHITECTURE FIX for LSPatch: under LSPatch the module code runs
 * inside the TARGET app's process and inherits ITS permissions. If the target
 * app has no INTERNET permission, or its WebView is restricted, or the OEM
 * (ColorOS/OneUI...) blocks the overlay windows the renderer needs, NOTHING
 * can ever be displayed — the camera stays black no matter how many retry
 * loops run inside that process.
 *
 * FIX: render the link HERE, in our own app process, where we own every
 * permission (INTERNET, cleartext, WebView, EGL) and no OEM window policy
 * applies. The produced NV21 frames are relayed to target apps over an
 * ABSTRACT NAMESPACE unix socket (LocalSocket), which needs NO permissions at
 * all — not even INTERNET.
 *
 * Two rendering paths, chosen by LinkType (or forced by display mode):
 *  MEDIA (.mp4/.m3u8/...) → MediaPlayer → SurfaceTexture → EGL offscreen FBO
 *                           → glReadPixels → NV21  (frame-driven, ≤15 fps)
 *  PAGE  (sites/watch pages) → off-screen WebView, software-drawn at 10 fps
 *                           → Bitmap → NV21
 *
 * ANTI-BLACK-Screen GUARANTEE: the renderer ALWAYS emits frames. While
 * connecting / buffering / retrying it emits a branded placeholder frame with
 * the current state written on it, so the camera shows "Rinigan — Loading
 * link…" instead of a black void, and a real failure is instantly visible.
 */
public final class LinkRenderer {

    private static final String TAG = "[Engine]";
    private static final int MAX_FRAME_DIM = 1080;
    private static final int PAGE_W = 720;
    private static final int PAGE_H = 1280;
    private static final int PAGE_FPS_MS = 100; // 10 fps
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_MS = {1500, 3000, 5000, 8000, 12000};

    /** Callbacks into StreamEngineService. */
    public interface Callback {
        /** A rendered frame (NV21). Called on a renderer thread. */
        void onFrame(byte[] nv21, int w, int h);

        /** Human-readable state for placeholder frames + diagnostics UI. */
        void onState(String state, String detail);
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread glThread;
    private Handler glHandler;

    // ---- MEDIA path state ----
    private MediaPlayer mediaPlayer;
    private GlOffscreen gl;
    private SurfaceTextureSink sink;
    private volatile boolean mediaRendering = false;

    // ---- PAGE path state ----
    private WebView webView;
    private Bitmap pageBitmap;
    private Canvas pageCanvas;
    private Handler pageHandler; // main-looper handler used for the capture timer
    private Runnable pageTicker;

    // ---- shared ----
    private volatile boolean stopped = false;
    private volatile String currentUrl;
    private volatile String displayMode = "auto";
    private volatile boolean inError = false;
    private int attempts = 0;

    // last emitted placeholder state (avoid regenerating identical frames)
    private volatile String placeholderState = null;

    public LinkRenderer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    // ======================== Public API ========================

    /** Starts (or restarts) rendering of the given link. */
    public void start(String url, String mode) {
        stopRenderers();
        stopped = false;
        attempts = 0;
        currentUrl = url;
        displayMode = (mode == null || mode.isEmpty()) ? "auto" : mode;
        emitPlaceholder("loading", "Loading link…");
        boolean page = "page".equals(displayMode) ||
                ("auto".equals(displayMode) && LinkType.isPageUrl(url));
        if (page) {
            LogUtil.log(TAG + "PAGE path → off-screen WebView: " + url);
            startPage(url);
        } else {
            LogUtil.log(TAG + "MEDIA path → MediaPlayer+EGL: " + url);
            startMedia(url);
        }
    }

    /** Stops every renderer (WebView, MediaPlayer, GL) and all timers. */
    public void stop() {
        stopped = true;
        stopRenderers();
        callback.onState("stopped", "Engine stopped");
    }

    /** v6.3 — tears the current renderer down but keeps this instance reusable. */
    public void stopRenderersForRestart() {
        stopRenderers();
        stopped = false;
    }

    // ======================== MEDIA path ========================

    private void startMedia(final String url) {
        inError = false;
        glThread = new HandlerThread("Rinigan-EngineGL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(() -> openMediaOnGlThread(url));
    }

    private void openMediaOnGlThread(final String url) {
        if (stopped) return;
        try {
            if (gl == null) {
                gl = GlOffscreen.create();
                if (gl == null) {
                    LogUtil.log(TAG + "EGL offscreen unavailable — cannot decode to pixels");
                    renderFailure("GL unavailable in app process");
                    return;
                }
            }
            if (sink == null) {
                sink = new SurfaceTextureSink(gl);
            }
            sink.reset(gl);

            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                try {
                    mediaPlayer.reset();
                } catch (Throwable ignored) {
                }
            }
            mediaPlayer.setSurface(sink.surface);
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            mediaPlayer.setAudioAttributes(attrs);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Rinigan/6.3");
            try {
                mediaPlayer.setDataSource(context, android.net.Uri.parse(url), headers);
            } catch (Throwable headerFail) {
                mediaPlayer.setDataSource(url);
            }
            mediaPlayer.setLooping(true); // VOD loops; no-op for live
            mediaPlayer.setVolume(0f, 0f); // engine is silent — target app owns audio
            mediaPlayer.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (w > 0 && h > 0 && sink != null) sink.requestSize(w, h);
                LogUtil.log(TAG + "video size " + w + "x" + h);
            });
            mediaPlayer.setOnPreparedListener(mp -> {
                if (stopped) return;
                try {
                    mp.start();
                    mediaRendering = true;
                    attempts = 0;
                    callback.onState("playing", "Link is playing");
                    LogUtil.log(TAG + "MEDIA playing: " + url);
                    armStallWatch();
                } catch (Throwable t) {
                    LogUtil.log(TAG + "start failed: " + t);
                    scheduleMediaRetry("start-failed");
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                LogUtil.log(TAG + "media error what=" + what + " extra=" + extra);
                scheduleMediaRetry("error-" + what);
                return true;
            });
            mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    callback.onState("buffering", "Buffering…");
                    armStallWatch();
                } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    callback.onState("playing", "Link is playing");
                }
                return false;
            });
            mediaPlayer.prepareAsync();
            callback.onState("connecting", "Connecting to link…");
        } catch (Throwable t) {
            LogUtil.log(TAG + "media open failed: " + t);
            scheduleMediaRetry("open-exception");
        }
    }

    private Runnable stallRunnable;

    private void armStallWatch() {
        if (glHandler == null) return;
        if (stallRunnable != null) glHandler.removeCallbacks(stallRunnable);
        stallRunnable = () -> {
            if (stopped) return;
            try {
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    LogUtil.log(TAG + "stall watchdog fired");
                    scheduleMediaRetry("stall");
                }
            } catch (Throwable t) {
                scheduleMediaRetry("stall-err");
            }
        };
        glHandler.postDelayed(stallRunnable, 15000);
    }

    private void scheduleMediaRetry(final String reason) {
        if (stopped) return;
        mediaRendering = false;
        if (glHandler == null) return;
        int next = attempts + 1;
        attempts = next;
        if (next > MAX_ATTEMPTS) {
            renderFailure("Link failed after " + (next - 1) + " attempts (" + reason + ")");
            return;
        }
        long delay = BACKOFF_MS[Math.min(next - 1, BACKOFF_MS.length - 1)];
        callback.onState("retrying", "Retry " + next + "/" + MAX_ATTEMPTS + " (" + reason + ")");
        LogUtil.log(TAG + "retry " + next + "/" + MAX_ATTEMPTS + " in " + delay + "ms (" + reason + ")");
        glHandler.postDelayed(() -> {
            if (stopped) return;
            releaseMediaPlayer();
            openMediaOnGlThread(currentUrl);
        }, delay);
    }

    private void renderFailure(String detail) {
        inError = true;
        callback.onState("error", detail);
        emitPlaceholder("error", detail);
        // slow reconnect every 30s so a transient outage self-heals
        if (glHandler != null) {
            glHandler.postDelayed(() -> {
                if (stopped) return;
                attempts = 0;
                releaseMediaPlayer();
                emitPlaceholder("loading", "Loading link…");
                openMediaOnGlThread(currentUrl);
            }, 30000);
        }
    }

    // ---- frame pump: SurfaceTexture → GL → NV21 → callback ----

    private void pumpMediaFrame() {
        if (stopped || sink == null || gl == null) return;
        try {
            int[] dims = sink.drawAndRead(); // updateTexImage → FBO → glReadPixels
            if (dims == null) return;
            int w = dims[0];
            int h = dims[1];
            byte[] nv21 = Nv21Converter.rgbaBufferToNv21(gl.pixelBuffer(), w, h);
            if (nv21 != null) callback.onFrame(nv21, w, h);
        } catch (Throwable t) {
            LogUtil.log(TAG + "pump error (non-fatal): " + t);
        }
    }

    private void releaseMediaPlayer() {
        mediaRendering = false;
        if (stallRunnable != null && glHandler != null) {
            glHandler.removeCallbacks(stallRunnable);
            stallRunnable = null;
        }
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Throwable ignored) {
        }
        mediaPlayer = null;
    }

    // ======================== PAGE path ========================

    private void startPage(final String url) {
        main.post(() -> {
            if (stopped) return;
            try {
                WebView.enableSlowWholeDocumentDraw();
                stopPage();
                pageBitmap = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888);
                pageCanvas = new Canvas(pageBitmap);
                webView = new WebView(context);
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                android.webkit.WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                s.setLoadWithOverviewMode(true);
                s.setUseWideViewPort(true);
                s.setBlockNetworkImage(false);
                s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        LogUtil.log(TAG + "page finished: " + u);
                        callback.onState("page_loaded", "Page loaded — rendering");
                        mutePageAudio();
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode,
                                                String description, String failingUrl) {
                        LogUtil.log(TAG + "page error " + errorCode + ": " + description);
                        if (failingUrl != null && failingUrl.equals(currentUrl)) {
                            callback.onState("page_error", "Page error " + errorCode);
                        }
                    }
                });
                webView.measure(
                        View.MeasureSpec.makeMeasureSpec(PAGE_W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(PAGE_H, View.MeasureSpec.EXACTLY));
                webView.layout(0, 0, PAGE_W, PAGE_H);
                webView.loadUrl(url);
                callback.onState("loading", "Loading page…");
                LogUtil.log(TAG + "WebView loading: " + url);

                // capture ticker — 10 fps software draw
                pageTicker = new Runnable() {
                    @Override
                    public void run() {
                        if (stopped) return;
                        try {
                            if (webView != null && pageBitmap != null) {
                                pageBitmap.eraseColor(0xFF101318);
                                webView.draw(pageCanvas);
                                byte[] nv21 = Nv21Converter.bitmapToNv21(pageBitmap);
                                if (nv21 != null) callback.onFrame(nv21, PAGE_W, PAGE_H);
                            }
                        } catch (Throwable t) {
                            LogUtil.log(TAG + "page capture error (non-fatal): " + t);
                        }
                        if (pageHandler != null) pageHandler.postDelayed(this, PAGE_FPS_MS);
                    }
                };
                pageHandler = main;
                main.postDelayed(pageTicker, PAGE_FPS_MS);
                // periodic audio mute (pages must never leak sound into the call)
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (stopped) return;
                        mutePageAudio();
                        if (main != null) main.postDelayed(this, 10000);
                    }
                }, 10000);
            } catch (Throwable t) {
                LogUtil.log(TAG + "WebView start failed: " + t);
                callback.onState("error", "WebView failed: " + t.getMessage());
                emitPlaceholder("error", "WebView failed");
            }
        });
    }

    private void mutePageAudio() {
        try {
            if (webView != null) {
                webView.evaluateJavascript(
                        "try{document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;});}catch(e){}",
                        null);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopPage() {
        try {
            if (pageTicker != null && pageHandler != null) {
                pageHandler.removeCallbacks(pageTicker);
                pageTicker = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.removeAllViews();
                webView.destroy();
            }
        } catch (Throwable ignored) {
        }
        webView = null;
        if (pageBitmap != null) {
            pageBitmap.recycle();
            pageBitmap = null;
        }
        pageCanvas = null;
    }

    // ======================== Placeholder frames (never black) ========================

    /**
     * Emits a branded frame carrying the current engine state so the camera
     * NEVER shows a black void: while loading, buffering, retrying or after a
     * failure the target app sees "Rinigan — <state>" instead.
     */
    public void emitPlaceholder(String state, String detail) {
        placeholderState = state;
        try {
            int w = PAGE_W;
            int h = PAGE_H;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h,
                    0xFF141A2E, 0xFF05070C, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setColor(0xFF7C9CFF);
            p.setTextSize(72);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("Rinigan", w / 2f, h / 2f - 90, p);
            p.setColor(0xFFB9C4E0);
            p.setTextSize(44);
            String text = (detail == null || detail.isEmpty()) ? state : detail;
            if (text.length() > 42) text = text.substring(0, 42) + "…";
            c.drawText(text, w / 2f, h / 2f + 10, p);
            p.setTextSize(30);
            p.setColor(0xFF5A6480);
            c.drawText("Link display environment", w / 2f, h / 2f + 80, p);
            byte[] nv21 = Nv21Converter.bitmapToNv21(bmp);
            bmp.recycle();
            if (nv21 != null) callback.onFrame(nv21, w, h);
        } catch (Throwable t) {
            LogUtil.log(TAG + "placeholder failed: " + t);
        }
    }

    // ======================== Teardown ========================

    private void stopRenderers() {
        // GL path
        try {
            if (glHandler != null) {
                glHandler.post(() -> {
                    releaseMediaPlayer();
                    try {
                        if (sink != null) sink.release();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (gl != null) gl.release();
                    } catch (Throwable ignored) {
                    }
                    sink = null;
                    gl = null;
                });
            }
        } catch (Throwable ignored) {
        }
        HandlerThread t = glThread;
        if (t != null) {
            t.quitSafely();
        }
        glThread = null;
        glHandler = null;
        // page path (on main thread)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopPage();
        } else {
            main.post(this::stopPage);
        }
        placeholderState = null;
    }

    // ======================== GL offscreen helper ========================

    /** EGL context + 1×1 pbuffer + FBO that holds the readable RGBA framebuffer. */
    private static final class GlOffscreen {
        private EGLDisplay display;
        private EGLContext eglContext;
        private EGLSurface pbuffer;
        private int fbo;
        private int colorTex;
        private int oesTex;
        private int program;
        private int aPosition;
        private int aTextureCoord;
        private int uSTMatrix;
        private int fboW = 720;
        private int fboH = 1280;
        private ByteBuffer pixels;
        private FloatBuffer verts;
        private FloatBuffer texs;
        private final float[] stMatrix = new float[16];

        static GlOffscreen create() {
            try {
                GlOffscreen g = new GlOffscreen();
                if (!g.init()) return null;
                return g;
            } catch (Throwable t) {
                LogUtil.log("[Engine]GlOffscreen init failed: " + t);
                return null;
            }
        }

        private boolean init() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return false;
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false;
            int[] cfgAttribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, num, 0)
                    || num[0] == 0) return false;
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(display, configs[0],
                    EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false;
            int[] pbAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttribs, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE) return false;
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) return false;

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, GLHelper.loadShader(GLES20.GL_VERTEX_SHADER, GLHelper.VERTEX_SHADER));
            GLES20.glAttachShader(program, GLHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, GLHelper.FRAGMENT_SHADER));
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) return false;
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");

            // vertices with negated Y — compensates glReadPixels' bottom-up rows
            verts = GLHelper.createFloatBuffer(new float[]{
                    -1f, 1f,
                    1f, 1f,
                    -1f, -1f,
                    1f, -1f});
            texs = GLHelper.createFloatBuffer(GLHelper.TEX_COORDS);
            return true;
        }

        /** Allocates/resizes the FBO color attachment. Must run on the GL thread. */
        private void resizeLocked(int w, int h) {
            fboW = w;
            fboH = h;
            if (fbo != 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                fbo = 0;
            }
            if (colorTex != 0) {
                GLES20.glDeleteTextures(1, new int[]{colorTex}, 0);
                colorTex = 0;
            }
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            colorTex = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            fbo = fb[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, colorTex, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            LogUtil.log("[Engine]GL readback surface " + w + "x" + h);
        }

        int fboWidth() { return fboW; }

        int fboHeight() { return fboH; }

        ByteBuffer pixelBuffer() { return pixels; }

        void release() {
            try {
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                if (colorTex != 0) GLES20.glDeleteTextures(1, new int[]{colorTex}, 0);
                if (oesTex != 0) GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
                if (program != 0) GLES20.glDeleteProgram(program);
            } catch (Throwable ignored) {
            }
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
                    EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** SurfaceTexture sink owned by the GL thread; produces frames into the FBO. */
    private final class SurfaceTextureSink {
        private final GlOffscreen glRef;
        private android.graphics.SurfaceTexture texture;
        private Surface surface;
        private int wantedW = 720;
        private int wantedH = 1280;
        private boolean sized = false;
        private volatile boolean pending = false;
        private final float[] stMatrix = new float[16];

        SurfaceTextureSink(GlOffscreen glRef) {
            this.glRef = glRef;
        }

        void reset(GlOffscreen g) {
            // called on GL thread before each open; forces FBO re-size on next frame
            sized = false;
        }

        Surface getSurface() {
            return surface;
        }

        void requestSize(int w, int h) {
            int[] capped = capSize(w, h);
            wantedW = capped[0];
            wantedH = capped[1];
            if (texture != null) {
                texture.setDefaultBufferSize(wantedW, wantedH);
                sized = false; // force FBO resize on next frame
            }
        }

        private void ensureCreated() {
            if (texture != null) return;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            glRef.oesTex = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, glRef.oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            texture = new android.graphics.SurfaceTexture(glRef.oesTex);
            texture.setDefaultBufferSize(wantedW, wantedH);
            texture.setOnFrameAvailableListener(st -> {
                pending = true;
                if (glHandler != null) glHandler.post(LinkRenderer.this::pumpMediaFrame);
            }, new Handler(glThread.getLooper()));
            surface = new Surface(texture);
            LogUtil.log("[Engine]SurfaceTexture sink created " + wantedW + "x" + wantedH);
        }

        /** updateTexImage → draw → glReadPixels. Returns {w,h} or null. */
        int[] drawAndRead() {
            if (!pending || texture == null) return null;
            pending = false;
            try {
                if (!sized) {
                    ensureCreated();
                    glRef.resizeLocked(wantedW, wantedH);
                    sized = true;
                }
                texture.updateTexImage();
                texture.getTransformMatrix(stMatrix);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glRef.fbo);
                GLES20.glViewport(0, 0, glRef.fboW, glRef.fboH);
                GLES20.glUseProgram(glRef.program);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, glRef.oesTex);
                GLES20.glUniformMatrix4fv(glRef.uSTMatrix, 1, false, stMatrix, 0);
                GLES20.glEnableVertexAttribArray(glRef.aPosition);
                GLES20.glVertexAttribPointer(glRef.aPosition, 2, GLES20.GL_FLOAT, false, 8, glRef.verts);
                GLES20.glEnableVertexAttribArray(glRef.aTextureCoord);
                GLES20.glVertexAttribPointer(glRef.aTextureCoord, 2, GLES20.GL_FLOAT, false, 8, glRef.texs);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(glRef.aPosition);
                GLES20.glDisableVertexAttribArray(glRef.aTextureCoord);
                GLES20.glReadPixels(0, 0, glRef.fboW, glRef.fboH,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, glRef.pixelBuffer());
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return new int[]{glRef.fboW, glRef.fboH};
            } catch (Throwable t) {
                LogUtil.log("[Engine]drawAndRead failed: " + t);
                return null;
            }
        }

        void release() {
            try {
                if (surface != null) surface.release();
            } catch (Throwable ignored) {
            }
            try {
                if (texture != null) texture.release();
            } catch (Throwable ignored) {
            }
            surface = null;
            texture = null;
        }
    }

    /** Caps a source size so the longest edge ≤ MAX_FRAME_DIM (keeps aspect). */
    static int[] capSize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{720, 1280};
        int max = Math.max(w, h);
        if (max <= MAX_FRAME_DIM) return new int[]{w, h};
        float scale = MAX_FRAME_DIM / (float) max;
        return new int[]{Math.max(2, Math.round(w * scale)), Math.max(2, Math.round(h * scale))};
    }
}
