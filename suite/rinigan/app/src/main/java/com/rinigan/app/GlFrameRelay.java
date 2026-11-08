package com.rinigan.app;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL frame relay: MediaPlayer / MediaCodec render the replacement video into an
 * intermediate SurfaceTexture owned by this relay; every arriving frame is re-drawn
 * through a shader pass that applies the per-app transformation matrix
 * (rotation * flip * fit/crop/stretch) and then lands on the real application surface.
 *
 * Pipeline:
 *   Media source -> [input SurfaceTexture (OES)] -> uSTMatrix (decoder)
 *               -> uRotMatrix = FrameTransformer.buildGlVertexMatrix(...) -> [output Surface]
 *
 * All EGL/GL work happens on a private HandlerThread. Output size is queried from EGL
 * after surface creation, so the matrix math always matches the actual app buffer.
 */
public class GlFrameRelay implements SurfaceTexture.OnFrameAvailableListener {

    private static final int INIT_TIMEOUT_MS = 2500;

    private final String tag;
    private final Surface outputSurface;

    private HandlerThread glThread;
    private Handler glHandler;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private int texId = -1;
    private int program = -1;
    private int locPosition;
    private int locTexCoord;
    private int locSTMatrix;
    private int locRotMatrix;

    private final float[] stMatrix = new float[16];
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private volatile boolean released = false;
    private volatile boolean initOk = false;

    // transform inputs (hot-updatable)
    private volatile TransformProfile profile;
    private volatile int srcW;
    private volatile int srcH;
    private volatile int outW;
    private volatile int outH;

    private GlFrameRelay(Surface outputSurface, TransformProfile profile,
                         int srcW, int srcH, String tag) {
        this.outputSurface = outputSurface;
        this.profile = profile;
        this.srcW = srcW > 0 ? srcW : 1080;
        this.srcH = srcH > 0 ? srcH : 1920;
        this.tag = tag;
    }

    /**
     * Creates a relay synchronously (GL init happens on the relay thread and the caller
     * waits for it) so the returned input Surface can be handed to MediaPlayer/decoder
     * immediately. Returns null when GL initialization fails - callers fall back to
     * rendering directly into the output surface.
     */
    public static GlFrameRelay createSafely(Surface output, TransformProfile profile,
                                            int srcW, int srcH, String tag) {
        if (output == null || !output.isValid()) return null;
        final GlFrameRelay relay = new GlFrameRelay(output, profile, srcW, srcH, tag);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        try {
            relay.glThread = new HandlerThread("Rinigan-GL-" + tag);
            relay.glThread.start();
            relay.glHandler = new Handler(relay.glThread.getLooper());
            relay.glHandler.post(() -> {
                try {
                    ok.set(relay.initGl());
                } catch (Throwable t) {
                    LogUtil.log("[Transform][" + tag + "]GL init crashed: " + t);
                    ok.set(false);
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !ok.get()) {
                LogUtil.log("[Transform][" + tag + "]GL init failed/timeout, falling back");
                relay.release();
                return null;
            }
            relay.initOk = true;
            LogUtil.log("[Transform][" + tag + "]GL relay ready, out=" + relay.outW + "x" + relay.outH);
            return relay;
        } catch (Throwable t) {
            LogUtil.log("[Transform][" + tag + "]relay creation failed: " + t);
            relay.release();
            return null;
        }
    }

    // ======================== GL lifecycle ========================

    private boolean initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID (constant not exposed by all android.jar versions)
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            // retry without RECORDABLE (some odd drivers)
            int[] fallback = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            if (!EGL14.eglChooseConfig(eglDisplay, fallback, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                return false;
            }
        }

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false;

        int[] surfAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false;

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false;

        int[] dim = new int[1];
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, dim, 0)) {
            outW = Math.max(1, dim[0]);
        }
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, dim, 0)) {
            outH = Math.max(1, dim[0]);
        }

        // shaders
        program = GLES20.glCreateProgram();
        int vs = GLHelper.loadShader(GLES20.GL_VERTEX_SHADER, GLHelper.VERTEX_SHADER);
        int fs = GLHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, GLHelper.FRAGMENT_SHADER);
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            LogUtil.log("[Transform][" + tag + "]program link failed: " + GLES20.glGetProgramInfoLog(program));
            return false;
        }
        locPosition = GLES20.glGetAttribLocation(program, "aPosition");
        locTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
        locSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");
        locRotMatrix = GLES20.glGetUniformLocation(program, "uRotMatrix");

        // OES texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        texId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        inputTexture = new SurfaceTexture(texId);
        inputTexture.setDefaultBufferSize(srcW, srcH);
        inputSurface = new Surface(inputTexture);
        inputTexture.setOnFrameAvailableListener(this, glHandler);
        return true;
    }

    // ======================== Frame rendering ========================

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (released) return;
        if (framePending.compareAndSet(false, true)) {
            glHandler.post(this::renderFrame);
        }
    }

    private void renderFrame() {
        framePending.set(false);
        if (released || !initOk) return;
        try {
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(stMatrix);

            float[] rot = FrameTransformer.buildGlVertexMatrix(srcW, srcH, outW, outH, profile);

            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            java.nio.FloatBuffer vertexBuf = GLHelper.createFloatBuffer(GLHelper.VERTICES);
            java.nio.FloatBuffer texBuf = GLHelper.createFloatBuffer(GLHelper.TEX_COORDS);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);

            GLES20.glEnableVertexAttribArray(locPosition);
            GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, 8, vertexBuf);
            GLES20.glEnableVertexAttribArray(locTexCoord);
            GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, 8, texBuf);

            GLES20.glUniformMatrix4fv(locSTMatrix, 1, false, stMatrix, 0);
            GLES20.glUniformMatrix4fv(locRotMatrix, 1, false, rot, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(locPosition);
            GLES20.glDisableVertexAttribArray(locTexCoord);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        } catch (Throwable t) {
            LogUtil.log("[Transform][" + tag + "]render failed: " + t);
        }
    }

    // ======================== Public API ========================

    /** The surface that replaces the app surface for MediaPlayer / MediaCodec. */
    public Surface getInputSurface() {
        return inputSurface;
    }

    /** Hot-updates the transform (when the user edits profiles while a call is running). */
    public void updateTransform(TransformProfile newProfile, int newSrcW, int newSrcH) {
        if (newProfile != null) this.profile = newProfile;
        if (newSrcW > 0 && newSrcH > 0) {
            this.srcW = newSrcW;
            this.srcH = newSrcH;
            if (inputTexture != null) {
                try {
                    inputTexture.setDefaultBufferSize(newSrcW, newSrcH);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void release() {
        if (released) return;
        released = true;
        try {
            if (glHandler != null) {
                final CountDownLatch latch = new CountDownLatch(1);
                glHandler.post(() -> {
                    try {
                        if (inputTexture != null) {
                            inputTexture.setOnFrameAvailableListener(null);
                            inputTexture.release();
                            inputTexture = null;
                        }
                        if (inputSurface != null) {
                            inputSurface.release();
                            inputSurface = null;
                        }
                        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                            EGL14.eglMakeCurrent(eglDisplay,
                                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                            }
                            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                                EGL14.eglDestroyContext(eglDisplay, eglContext);
                            }
                            EGL14.eglTerminate(eglDisplay);
                        }
                        if (program >= 0) {
                            GLES20.glDeleteProgram(program);
                            program = -1;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(800, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (glThread != null) {
                glThread.quitSafely();
                glThread = null;
            }
        } catch (Throwable ignored) {
        }
        LogUtil.log("[Transform][" + tag + "]relay released");
    }
}
