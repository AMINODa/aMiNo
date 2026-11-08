package com.rinigan.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Camera1Handler {

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookSetPreviewTexture(lpparam);
        hookStartPreview(lpparam);
        hookSetPreviewDisplay(lpparam);
        hookSetPreviewCallbackWithBuffer(lpparam);
        hookAddCallbackBuffer(lpparam);
        hookSetPreviewCallback(lpparam);
        hookSetOneShotPreviewCallback(lpparam);
        hookTakePicture(lpparam);
    }

    // ======================== setPreviewTexture ========================
    private static void hookSetPreviewTexture(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
                SurfaceTexture.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                VideoSource src = HookGuards.getVideoSource();
                if (src.isActive()) {
                    if (HookGuards.isDisabled()) return;
                    if (SharedState.is_hooked) {
                        SharedState.is_hooked = false;
                        return;
                    }
                    if (param.args[0] == null) return;
                    if (param.args[0].equals(SharedState.c1_fake_texture)) return;

                    if (SharedState.origin_preview_camera != null &&
                            SharedState.origin_preview_camera.equals(param.thisObject)) {
                        param.args[0] = SharedState.fake_SurfaceTexture;
                        XposedBridge.log("[Rinigan]duplicate detected " + SharedState.origin_preview_camera.toString());
                        return;
                    } else {
                        XposedBridge.log("[Rinigan]Creating preview");
                    }

                    SharedState.origin_preview_camera = (Camera) param.thisObject;
                    SharedState.mSurfacetexture = (SurfaceTexture) param.args[0];
                    if (SharedState.fake_SurfaceTexture == null) {
                        SharedState.fake_SurfaceTexture = new SurfaceTexture(10);
                    } else {
                        SharedState.fake_SurfaceTexture.release();
                        SharedState.fake_SurfaceTexture = new SurfaceTexture(10);
                    }
                    param.args[0] = SharedState.fake_SurfaceTexture;
                } else {
                    SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content,
                                    "No replacement video found\n" + lpparam.packageName + "Current path: " + SharedState.video_path,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[Rinigan][toast]" + ee.toString());
                        }
                    }
                }
            }
        });
    }

    // ======================== startPreview ========================
    private static void hookStartPreview(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview",
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                VideoSource src = HookGuards.getVideoSource();
                SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                if (!src.isActive()) {
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content,
                                    "No replacement video found\n" + lpparam.packageName + "Current path: " + SharedState.video_path,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[Rinigan][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (HookGuards.isDisabled()) return;

                SharedState.is_someone_playing = false;
                XposedBridge.log("[Rinigan]Starting preview (" + src.describe() + ")");
                SharedState.start_preview_camera = (Camera) param.thisObject;
                // v6.3: diagnostics — the target app actually opened its camera
                HookDiag.report(SharedState.toast_content, "camera_opened",
                        "Camera1 preview started (" + src.describe() + ")");

                // record current source for notification controls / resume bookkeeping
                SharedState.currentVideoPath = src.mediaRef();

                boolean playSound = HookGuards.shouldPlaySound();

                // SurfaceHolder player
                if (SharedState.ori_holder != null) {
                    if (SharedState.mplayer1 == null) {
                        SharedState.mplayer1 = new MediaPlayer();
                    } else {
                        StreamPlayer.cancel(SharedState.mplayer1);
                        SharedState.mplayer1.release();
                        SharedState.mplayer1 = null;
                        SharedState.mplayer1 = new MediaPlayer();
                    }
                    if (!SharedState.ori_holder.getSurface().isValid() || SharedState.ori_holder == null) {
                        return;
                    }
                    Surface target = TransformApplicator.wrapSurface(
                            SharedState.ori_holder.getSurface(), "c1-holder",
                            src.mediaRef(), SharedState.toast_content, null);
                    boolean effectiveSound = playSound && !SharedState.is_someone_playing;
                    SharedState.is_someone_playing = effectiveSound;
                    // v6.1: local file OR live network link — StreamPlayer owns reset+surface+volume
                    StreamPlayer.open(SharedState.mplayer1, src, effectiveSound ? 1f : 0f, "c1-holder", target);
                }

                // SurfaceTexture player
                if (SharedState.mSurfacetexture != null) {
                    if (SharedState.mSurface == null) {
                        SharedState.mSurface = new Surface(SharedState.mSurfacetexture);
                    } else {
                        SharedState.mSurface.release();
                        SharedState.mSurface = new Surface(SharedState.mSurfacetexture);
                    }
                    if (SharedState.mMediaPlayer == null) {
                        SharedState.mMediaPlayer = new MediaPlayer();
                    } else {
                        // v6.2 fix: cancel pending stream retries BEFORE release —
                        // without this, watchdog retries kept running against a released
                        // player and spuriously triggered the local-fallback swap.
                        StreamPlayer.cancel(SharedState.mMediaPlayer);
                        SharedState.mMediaPlayer.release();
                        SharedState.mMediaPlayer = new MediaPlayer();
                    }
                    Surface target = TransformApplicator.wrapSurface(
                            SharedState.mSurface, "c1-tex",
                            src.mediaRef(), SharedState.toast_content, null);
                    boolean effectiveSound = playSound && !SharedState.is_someone_playing;
                    SharedState.is_someone_playing = effectiveSound;
                    StreamPlayer.open(SharedState.mMediaPlayer, src, effectiveSound ? 1f : 0f, "c1-tex", target);
                }
            }
        });
    }

    // ======================== setPreviewDisplay ========================
    private static void hookSetPreviewDisplay(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay",
                SurfaceHolder.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]Adding SurfaceView preview");
                VideoSource src = HookGuards.getVideoSource();
                SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                if (!src.isActive()) {
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content,
                                    "No replacement video found\n" + lpparam.packageName + "Current path: " + SharedState.video_path,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[Rinigan][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (HookGuards.isDisabled()) return;

                SharedState.mcamera1 = (Camera) param.thisObject;
                SharedState.ori_holder = (SurfaceHolder) param.args[0];

                if (SharedState.c1_fake_texture == null) {
                    SharedState.c1_fake_texture = new SurfaceTexture(11);
                } else {
                    SharedState.c1_fake_texture.release();
                    SharedState.c1_fake_texture = null;
                    SharedState.c1_fake_texture = new SurfaceTexture(11);
                }
                if (SharedState.c1_fake_surface == null) {
                    SharedState.c1_fake_surface = new Surface(SharedState.c1_fake_texture);
                } else {
                    SharedState.c1_fake_surface.release();
                    SharedState.c1_fake_surface = null;
                    SharedState.c1_fake_surface = new Surface(SharedState.c1_fake_texture);
                }
                SharedState.is_hooked = true;
                SharedState.mcamera1.setPreviewTexture(SharedState.c1_fake_texture);
                param.setResult(null);
            }
        });
    }

    // ======================== setPreviewCallbackWithBuffer ========================
    private static void hookSetPreviewCallbackWithBuffer(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    processCallback(param);
                }
            }
        });
    }

    // ======================== addCallbackBuffer ========================
    private static void hookAddCallbackBuffer(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
                byte[].class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    param.args[0] = new byte[((byte[]) param.args[0]).length];
                }
            }
        });
    }

    // ======================== setPreviewCallback ========================
    private static void hookSetPreviewCallback(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback",
                Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    processCallback(param);
                }
            }
        });
    }

    // ======================== setOneShotPreviewCallback ========================
    private static void hookSetOneShotPreviewCallback(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setOneShotPreviewCallback",
                Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    processCallback(param);
                }
            }
        });
    }

    // ======================== takePicture ========================
    private static void hookTakePicture(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture",
                Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedBridge.log("[Rinigan]takePicture (4 args)");
                if (param.args[1] != null) {
                    processAShotYUV(param);
                }
                if (param.args[3] != null) {
                    processAShotJPEG(param, 3);
                }
            }
        });
    }

    // ======================== processCallback ========================
    private static void processCallback(XC_MethodHook.MethodHookParam param) {
        Class preview_cb_class = param.args[0].getClass();
        int need_stop = 0;
        if (HookGuards.isDisabled()) {
            need_stop = 1;
        }
        VideoSource src = HookGuards.getVideoSource();
        SharedState.need_to_show_toast = HookGuards.shouldShowToast();
        if (!src.isActive()) {
            if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                try {
                    Toast.makeText(SharedState.toast_content,
                            "No replacement video found\n" + SharedState.toast_content.getPackageName() + "Current path: " + SharedState.video_path,
                            Toast.LENGTH_SHORT).show();
                } catch (Exception ee) {
                    XposedBridge.log("[Rinigan][toast]" + ee);
                }
            }
            need_stop = 1;
        }
        final int finalNeed_stop = need_stop;
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
                byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                Camera localcam = (android.hardware.Camera) paramd.args[1];
                if (localcam.equals(SharedState.camera_onPreviewFrame)) {
                    // v6.2: bounded wait — a missing frame must NEVER freeze the host app
                    awaitFirstFrame(5000);
                    TransformApplicator.transformNv21IntoPreview(SharedState.data_buffer,
                            SharedState.currentVideoPath,
                            (byte[]) paramd.args[0], SharedState.mwidth, SharedState.mhight,
                            SharedState.toast_content, null);
                } else {
                    SharedState.camera_callback_calss = preview_cb_class;
                    SharedState.camera_onPreviewFrame = (android.hardware.Camera) paramd.args[1];
                    SharedState.mwidth = SharedState.camera_onPreviewFrame.getParameters().getPreviewSize().width;
                    SharedState.mhight = SharedState.camera_onPreviewFrame.getParameters().getPreviewSize().height;
                    int frame_Rate = SharedState.camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                    XposedBridge.log("[Rinigan]Frame preview callback init, width: " + SharedState.mwidth +
                            " height: " + SharedState.mhight + " fps: " + frame_Rate);
                    SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content, "Preview detected\nwidth: " + SharedState.mwidth +
                                    "\nheight: " + SharedState.mhight + "\n" + "Video resolution must exactly match this", Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[Rinigan][toast]" + ee.toString());
                        }
                    }
                    if (finalNeed_stop == 1) return;

                    // v6.2: web-page/HLS links cannot be MediaExtractor-decoded — the
                    // tap bridge renders them (WebView / MediaPlayer) into NV21 frames
                    // delivered through SharedState.data_buffer instead.
                    // v6.3: with relay mode ON every stream source goes through the
                    // bridge → the engine renders it in the Rinigan app process
                    // (works even when the target app has no INTERNET permission).
                    boolean needsTapBridge = src.isStream() && (RelayClient.relayEnabled()
                            || LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
                    if (needsTapBridge) {
                        if (SharedState.hw_decode_obj != null) {
                            SharedState.hw_decode_obj.stopDecode();
                            SharedState.hw_decode_obj = null;
                        }
                        if (SharedState.mwidth > 0 && SharedState.mhight > 0) {
                            FrameTapBridge.startBuffer("c1-cb", src,
                                    SharedState.mwidth, SharedState.mhight);
                        }
                    } else {
                        FrameTapBridge.stop("c1-cb");
                        if (SharedState.hw_decode_obj != null) {
                            SharedState.hw_decode_obj.stopDecode();
                        }
                        SharedState.hw_decode_obj = new VideoToFrames();
                        SharedState.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                        SharedState.currentVideoPath = src.mediaRef();
                        // decode() accepts a live http(s) URL as well as a local path
                        SharedState.hw_decode_obj.decode(src.mediaRef());
                    }
                    awaitFirstFrame(5000);
                    TransformApplicator.transformNv21IntoPreview(SharedState.data_buffer,
                            SharedState.currentVideoPath,
                            (byte[]) paramd.args[0], SharedState.mwidth, SharedState.mhight,
                            SharedState.toast_content, null);
                }
            }
        });
    }

    // ======================== processAShotYUV ========================
    private static void processAShotYUV(XC_MethodHook.MethodHookParam param) {
        try {
            XposedBridge.log("[Rinigan]picture YUV detected: " + param.args[1].toString());
        } catch (Exception eee) {
            XposedBridge.log("[Rinigan]" + eee);
        }
        Class callback = param.args[1].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken",
                byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    SharedState.onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    SharedState.onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[Rinigan]YUV picture callback init, width: " + SharedState.onemwidth +
                            "height: " + SharedState.onemhight + " corresponding class: " + loaclcam.toString());
                    SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content, "Picture detected\nwidth: " + SharedState.onemwidth +
                                    "\nheight: " + SharedState.onemhight + "\nFormat: YUV_420_888", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            XposedBridge.log("[Rinigan][toast]" + e.toString());
                        }
                    }
                    if (HookGuards.isDisabled()) return;
                    SharedState.input = getYUVByBitmap(getBMP(SharedState.video_path + "1000.bmp"));
                    paramd.args[0] = SharedState.input;
                } catch (Exception ee) {
                    XposedBridge.log("[Rinigan]" + ee.toString());
                }
            }
        });
    }

    // ======================== processAShotJPEG ========================
    private static void processAShotJPEG(XC_MethodHook.MethodHookParam param, int index) {
        try {
            XposedBridge.log("[Rinigan]Second JPEG: " + param.args[index].toString());
        } catch (Exception eee) {
            XposedBridge.log("[Rinigan]" + eee);
        }
        Class callback = param.args[index].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken",
                byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    SharedState.onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    SharedState.onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[Rinigan]JPEG picture callback init, width: " + SharedState.onemwidth +
                            "height: " + SharedState.onemhight + " corresponding class: " + loaclcam.toString());
                    SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content, "Picture detected\nwidth: " + SharedState.onemwidth +
                                    "\nheight: " + SharedState.onemhight + "\nFormat: JPEG", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            XposedBridge.log("[Rinigan][toast]" + e.toString());
                        }
                    }
                    if (HookGuards.isDisabled()) return;
                    Bitmap pict = getBMP(SharedState.video_path + "1000.bmp");
                    ByteArrayOutputStream temp_array = new ByteArrayOutputStream();
                    pict.compress(Bitmap.CompressFormat.JPEG, 100, temp_array);
                    byte[] jpeg_data = temp_array.toByteArray();
                    paramd.args[0] = jpeg_data;
                } catch (Exception ee) {
                    XposedBridge.log("[Rinigan]" + ee.toString());
                }
            }
        });
    }

    // ======================== Utility methods ========================

    /**
     * v6.2 — bounded wait for the first NV21 frame. The legacy code spun forever
     * on {@code while (data_buffer == null)}; if the source never produced a frame
     * (dead link, unsupported container) the host app's camera thread hung. A
     * bounded wait degrades to the real camera frame instead of freezing.
     */
    private static boolean awaitFirstFrame(long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (SharedState.data_buffer == null
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return SharedState.data_buffer != null;
    }

    private static Bitmap getBMP(String file) throws Throwable {
        return BitmapFactory.decodeFile(file);
    }

    private static byte[] rgb2YCbCr420(int[] pixels, int width, int height) {
        int len = width * height;
        byte[] yuv = new byte[len * 3 / 2];
        int y, u, v;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = (pixels[i * width + j]) & 0x00FFFFFF;
                int r = rgb & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = (rgb >> 16) & 0xFF;
                y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                y = y < 16 ? 16 : (Math.min(y, 255));
                u = u < 0 ? 0 : (Math.min(u, 255));
                v = v < 0 ? 0 : (Math.min(v, 255));
                yuv[i * width + j] = (byte) y;
                yuv[len + (i >> 1) * width + (j & ~1)] = (byte) u;
                yuv[len + +(i >> 1) * width + (j & ~1) + 1] = (byte) v;
            }
        }
        return yuv;
    }

    private static byte[] getYUVByBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        int[] pixels = new int[size];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return rgb2YCbCr420(pixels, width, height);
    }

    // ======================== Hot-swap: reload players when source changes ========================
    public static void reloadVideo() {
        HookGuards.getConfig().forceReload();
        if (HookGuards.isDisabled()) {
            stopAllPlayers();
            return;
        }
        reloadWithSource(HookGuards.getVideoSource(), HookGuards.shouldPlaySound());
    }

    /**
     * v6.1: last-resort local fallback for a dead live link (StreamPlayer calls this).
     * Rebuilds the pipeline on the local video without touching config.
     */
    public static void swapToLocal(VideoSource localSrc, boolean playSound) {
        reloadWithSource(localSrc, playSound);
    }

    /** Core hot-swap: rebuilds every active player/decoder on the given source. */
    private static void reloadWithSource(VideoSource src, boolean playSound) {
        if (src == null || !src.isActive()) {
            XposedBridge.log("[Rinigan]Camera1 hot-swap: source inactive, keeping current players");
            return;
        }
        String newRef = src.mediaRef();
        XposedBridge.log("[Rinigan]Camera1 hot-swap source → " + src.describe());

        // === Resume playback logic (local files only — live links always start now) ===
        String oldPath = SharedState.currentVideoPath;
        SharedState.currentVideoPath = newRef;
        long resumePos = 0;
        if (!src.isStream()) {
            if (oldPath != null && !oldPath.equals(newRef) && SharedState.hw_decode_obj != null) {
                long oldPos = SharedState.hw_decode_obj.getCurrentPositionMs();
                if (oldPos > 0) SharedState.videoPositions.put(oldPath, oldPos);
            }
            Long saved = SharedState.videoPositions.get(newRef);
            resumePos = (saved != null) ? saved : 0;
        }
        // ======================

        // MediaPlayer (SurfaceTexture preview) — rebuilt if nulled by stopAllPlayers
        if (SharedState.mSurfacetexture != null) {
            if (SharedState.mMediaPlayer != null) StreamPlayer.cancel(SharedState.mMediaPlayer);
            if (SharedState.mMediaPlayer == null) {
                SharedState.mMediaPlayer = new MediaPlayer();
            }
            if (SharedState.mSurface == null) {
                SharedState.mSurface = new Surface(SharedState.mSurfacetexture);
            }
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.mSurface, "c1-tex", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.mMediaPlayer, src, playSound ? 1f : 0f, "c1-tex", target);
        }

        // mplayer1 (SurfaceHolder preview) — rebuilt if nulled by stopAllPlayers
        if (SharedState.ori_holder != null) {
            if (SharedState.mplayer1 != null) StreamPlayer.cancel(SharedState.mplayer1);
            if (SharedState.mplayer1 == null) {
                SharedState.mplayer1 = new MediaPlayer();
            }
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.ori_holder.getSurface(), "c1-holder", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.mplayer1, src, playSound ? 1f : 0f, "c1-holder", target);
        }

        // decoder — rebuilt if nulled by stopAllPlayers
        if (SharedState.camera_onPreviewFrame != null) {
            try {
                // v6.2: pages/HLS go through the tap bridge (NV21 buffer mode)
                boolean needsTapBridge = src.isStream()
                        && (LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
                if (needsTapBridge) {
                    if (SharedState.hw_decode_obj != null) {
                        SharedState.hw_decode_obj.stopDecode();
                        SharedState.hw_decode_obj = null;
                    }
                    if (SharedState.mwidth > 0 && SharedState.mhight > 0) {
                        FrameTapBridge.startBuffer("c1-cb", src,
                                SharedState.mwidth, SharedState.mhight);
                    }
                } else {
                    FrameTapBridge.stop("c1-cb");
                    if (SharedState.hw_decode_obj == null) {
                        SharedState.hw_decode_obj = new VideoToFrames();
                        SharedState.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                    } else {
                        SharedState.hw_decode_obj.stopDecode();
                        SharedState.hw_decode_obj = new VideoToFrames();
                        SharedState.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                    }
                    if (resumePos > 0) SharedState.hw_decode_obj.seekTo(resumePos);
                    if (SharedState.playPaused) SharedState.hw_decode_obj.setPaused(true);
                    SharedState.hw_decode_obj.decode(newRef);
                }
            } catch (Throwable t) {
                XposedBridge.log("[Rinigan]Camera1 hot-swap hw_decode failed: " + t);
            }
        }

        XposedBridge.log("[Rinigan]Camera1 hot-swap finished");
    }

    // ======================== Sound toggle: dynamic volume update ========================
    public static void updateSoundVolume() {
        boolean playSound = HookGuards.shouldPlaySound();
        float vol = playSound ? 1f : 0f;
        XposedBridge.log("[Rinigan]Camera1 volume update → " + (playSound ? "ON" : "OFF"));

        if (SharedState.mMediaPlayer != null) {
            SharedState.mMediaPlayer.setVolume(vol, vol);
        }
        if (SharedState.mplayer1 != null) {
            SharedState.mplayer1.setVolume(vol, vol);
        }
    }

    // ======================== Stop all players (called when module is disabled) ========================
    public static void stopAllPlayers() {
        XposedBridge.log("[Rinigan]Camera1 stopping all players/decoders");
        StreamPlayer.cancelAll();
        TransformApplicator.releaseAll();
        // v6.2: stop this camera family's link display environments & bridges
        WebViewStreamer.stop("c1-tex");
        WebViewStreamer.stop("c1-holder");
        FrameTapBridge.stop("c1-cb");
        // v6.3: stop relayed frame clients too
        RelayClient.stop("c1-tex");
        RelayClient.stop("c1-holder");
        RelayClient.stop("c1-cb");
        try {
            if (SharedState.mMediaPlayer != null) {
                SharedState.mMediaPlayer.stop();
                SharedState.mMediaPlayer.reset();
                SharedState.mMediaPlayer.release();
                SharedState.mMediaPlayer = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera1 stop mMediaPlayer: " + e);
        }
        try {
            if (SharedState.mplayer1 != null) {
                SharedState.mplayer1.stop();
                SharedState.mplayer1.reset();
                SharedState.mplayer1.release();
                SharedState.mplayer1 = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera1 stop mplayer1: " + e);
        }
        try {
            if (SharedState.hw_decode_obj != null) {
                SharedState.hw_decode_obj.stopDecode();
                SharedState.hw_decode_obj = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera1 stop hw_decode: " + e);
        }
    }
}