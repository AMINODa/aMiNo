package com.rinigan.app;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class Camera2SessionHook {

    // ======================== processCamera2Init ========================
    public static void processCamera2Init(final Class hooked_class) {
        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                SharedState.need_recreate = true;
                createVirtualSurface();

                // release old player resources
                if (SharedState.c2_player != null) {
                    StreamPlayer.cancel(SharedState.c2_player);
                    SharedState.c2_player.stop();
                    SharedState.c2_player.reset();
                    SharedState.c2_player.release();
                    SharedState.c2_player = null;
                }
                // v6.2: stop this camera family's link display environments & bridges
                WebViewStreamer.stop("c2-p1");
                WebViewStreamer.stop("c2-p2");
                FrameTapBridge.stop("c2-r1");
                FrameTapBridge.stop("c2-r2");
                // v6.3: diagnostics — the target app actually opened its camera
                HookDiag.report(SharedState.toast_content, "camera_opened",
                        "Camera2 device opened");
                if (SharedState.c2_hw_decode_obj_1 != null) {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = null;
                }
                if (SharedState.c2_hw_decode_obj != null) {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = null;
                }
                if (SharedState.c2_player_1 != null) {
                    StreamPlayer.cancel(SharedState.c2_player_1);
                    SharedState.c2_player_1.stop();
                    SharedState.c2_player_1.reset();
                    SharedState.c2_player_1.release();
                    SharedState.c2_player_1 = null;
                }
                SharedState.c2_preview_Surfcae_1 = null;
                SharedState.c2_reader_Surfcae_1 = null;
                SharedState.c2_reader_Surfcae = null;
                SharedState.c2_preview_Surfcae = null;
                SharedState.is_first_hook_build = true;
                SharedState.currentVideoPath = null; // fix: clear path when camera reopens so decoders are rebuilt in build()
                XposedBridge.log("[Rinigan]Opening camera C2");

                VideoSource src = HookGuards.getVideoSource();
                SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                if (!src.isActive()) {
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content,
                                    "No replacement video found\n" + SharedState.toast_content.getPackageName() +
                                    "Current path: " + SharedState.video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[Rinigan][toast]" + ee.toString());
                        }
                    }
                    return;
                }

                // Hook CameraDevice.createCaptureSession (legacy 3-arg)
                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession",
                        List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        if (paramd.args[0] != null) {
                            XposedBridge.log("[Rinigan]createCaptureSession creating capture, original: " + paramd.args[0].toString() +
                                    "Virtual: " + SharedState.c2_virtual_surface.toString());
                            paramd.args[0] = Arrays.asList(SharedState.c2_virtual_surface);
                            if (paramd.args[1] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });

                // Hook CameraDevice.createCaptureSessionByOutputConfigurations (API 24+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                            "createCaptureSessionByOutputConfigurations", List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                SharedState.outputConfiguration = new OutputConfiguration(SharedState.c2_virtual_surface);
                                param.args[0] = Arrays.asList(SharedState.outputConfiguration);
                                XposedBridge.log("[Rinigan]executed createCaptureSessionByOutputConfigurations-144777");
                                if (param.args[1] != null) {
                                    processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                                }
                            }
                        }
                    });
                }

                // Hook CameraDevice.createConstrainedHighSpeedCaptureSession
                XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                        "createConstrainedHighSpeedCaptureSession", List.class,
                        CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        if (param.args[0] != null) {
                            param.args[0] = Arrays.asList(SharedState.c2_virtual_surface);
                            XposedBridge.log("[Rinigan]executed createConstrainedHighSpeedCaptureSession -5484987");
                            if (param.args[1] != null) {
                                processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                            }
                        }
                    }
                });

                // Hook CameraDevice.createReprocessableCaptureSession (API 23+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                            "createReprocessableCaptureSession", InputConfiguration.class, List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                param.args[1] = Arrays.asList(SharedState.c2_virtual_surface);
                                XposedBridge.log("[Rinigan]executed createReprocessableCaptureSession ");
                                if (param.args[2] != null) {
                                    processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }

                // Hook CameraDevice.createReprocessableCaptureSessionByConfigurations (API 24+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                            "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class,
                            List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                SharedState.outputConfiguration = new OutputConfiguration(SharedState.c2_virtual_surface);
                                param.args[0] = Arrays.asList(SharedState.outputConfiguration);
                                XposedBridge.log("[Rinigan]executed createReprocessableCaptureSessionByConfigurations");
                                if (param.args[2] != null) {
                                    processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }

                // Hook CameraDevice.createCaptureSession with SessionConfiguration (API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession",
                            SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                XposedBridge.log("[Rinigan]executed createCaptureSession -5484987");
                                SharedState.sessionConfiguration = (SessionConfiguration) param.args[0];
                                SharedState.outputConfiguration = new OutputConfiguration(SharedState.c2_virtual_surface);
                                SharedState.fake_sessionConfiguration = new SessionConfiguration(
                                        SharedState.sessionConfiguration.getSessionType(),
                                        Arrays.asList(SharedState.outputConfiguration),
                                        SharedState.sessionConfiguration.getExecutor(),
                                        SharedState.sessionConfiguration.getStateCallback());
                                param.args[0] = SharedState.fake_sessionConfiguration;
                                processCamera2SessionCallback(SharedState.sessionConfiguration.getStateCallback());
                            }
                        }
                    });
                }
            }
        });

        // Hook StateCallback.onError
        XposedHelpers.findAndHookMethod(hooked_class, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]Camera error onError: " + (int) param.args[1]);
            }
        });

        // Hook StateCallback.onDisconnected
        XposedHelpers.findAndHookMethod(hooked_class, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]Camera disconnected onDisconnected: ");
            }
        });
    }

    // ======================== createVirtualSurface ========================
    private static Surface createVirtualSurface() {
        if (SharedState.need_recreate) {
            if (SharedState.c2_virtual_surfaceTexture != null) {
                SharedState.c2_virtual_surfaceTexture.release();
                SharedState.c2_virtual_surfaceTexture = null;
            }
            if (SharedState.c2_virtual_surface != null) {
                SharedState.c2_virtual_surface.release();
                SharedState.c2_virtual_surface = null;
            }
            SharedState.c2_virtual_surfaceTexture = new SurfaceTexture(15);
            SharedState.c2_virtual_surface = new Surface(SharedState.c2_virtual_surfaceTexture);
            SharedState.need_recreate = false;
        } else {
            if (SharedState.c2_virtual_surface == null) {
                SharedState.need_recreate = true;
                SharedState.c2_virtual_surface = createVirtualSurface();
            }
        }
        XposedBridge.log("[Rinigan][surface-rebuild]" + SharedState.c2_virtual_surface.toString());
        return SharedState.c2_virtual_surface;
    }

    // ======================== processCamera2Play ========================
    public static void processCamera2Play() {
        VideoSource src = HookGuards.getVideoSource();
        if (!src.isActive()) return;
        String newRef = src.mediaRef();
        boolean playSound = HookGuards.shouldPlaySound();

        // === Resume playback: check for a saved playback position (local files only) ===
        String oldPath = SharedState.currentVideoPath;
        SharedState.currentVideoPath = newRef;
        long resumePos = 0;
        if (src.isStream()) {
            // live link → always start at the live edge
            resumePos = 0;
        } else {
            if (newRef.equals(oldPath)) {
                // same video continues → do not reset position
                return;
            }
            // video changed: save old position, check if the new video has a saved one
            if (oldPath != null && SharedState.c2_hw_decode_obj != null) {
                long oldPos = SharedState.c2_hw_decode_obj.getCurrentPositionMs();
                if (oldPos > 0) SharedState.videoPositions.put(oldPath, oldPos);
            }
            Long saved = SharedState.videoPositions.get(newRef);
            resumePos = (saved != null) ? saved : 0;
        }
        // ======================
        XposedBridge.log("[Rinigan]Camera2 play source → " + src.describe());

        // Reader Surface 1
        if (SharedState.c2_reader_Surfcae != null) {
            // v6.2: pages/HLS cannot be MediaExtractor-decoded — the tap bridge
            // produces the peer's frames (WebView rendering / MediaPlayer tap).
            // v6.3: with relay mode ON every stream source goes through the bridge.
            boolean needsTapBridge = src.isStream() && (RelayClient.relayEnabled()
                    || LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
            if (needsTapBridge) {
                if (SharedState.c2_hw_decode_obj != null) {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = null;
                }
                FrameTapBridge.startWriter("c2-r1", src, SharedState.c2_reader_Surfcae,
                        SharedState.imageReaderFormat == 256);
            } else {
                FrameTapBridge.stop("c2-r1");
                if (SharedState.c2_hw_decode_obj != null) {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = null;
                }
                SharedState.c2_hw_decode_obj = new VideoToFrames();
                try {
                    if (SharedState.imageReaderFormat == 256) {
                        SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    SharedState.c2_hw_decode_obj.set_surfcae(TransformApplicator.wrapSurface(
                            SharedState.c2_reader_Surfcae, "c2-r1", newRef,
                            SharedState.toast_content, null));
                    if (resumePos > 0) SharedState.c2_hw_decode_obj.seekTo(resumePos);
                    if (SharedState.playPaused) SharedState.c2_hw_decode_obj.setPaused(true);
                    // v6.1: newRef may be a live http(s) URL — MediaExtractor streams it directly
                    SharedState.c2_hw_decode_obj.decode(newRef);
                } catch (Throwable throwable) {
                    XposedBridge.log("[Rinigan]" + throwable);
                }
            }
        }

        // Reader Surface 2
        if (SharedState.c2_reader_Surfcae_1 != null) {
            boolean needsTapBridge2 = src.isStream() && (RelayClient.relayEnabled()
                    || LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
            if (needsTapBridge2) {
                if (SharedState.c2_hw_decode_obj_1 != null) {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = null;
                }
                FrameTapBridge.startWriter("c2-r2", src, SharedState.c2_reader_Surfcae_1,
                        SharedState.imageReaderFormat == 256);
            } else {
                FrameTapBridge.stop("c2-r2");
                if (SharedState.c2_hw_decode_obj_1 != null) {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = null;
                }
                SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
                try {
                    if (SharedState.imageReaderFormat == 256) {
                        SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    SharedState.c2_hw_decode_obj_1.set_surfcae(TransformApplicator.wrapSurface(
                            SharedState.c2_reader_Surfcae_1, "c2-r2", newRef,
                            SharedState.toast_content, null));
                    if (resumePos > 0) SharedState.c2_hw_decode_obj_1.seekTo(resumePos);
                    if (SharedState.playPaused) SharedState.c2_hw_decode_obj_1.setPaused(true);
                    SharedState.c2_hw_decode_obj_1.decode(newRef);
                } catch (Throwable throwable) {
                    XposedBridge.log("[Rinigan]" + throwable);
                }
            }
        }

        // Preview Surface 1
        if (SharedState.c2_preview_Surfcae != null) {
            if (SharedState.c2_player != null) StreamPlayer.cancel(SharedState.c2_player);
            SharedState.c2_player = new MediaPlayer();
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.c2_preview_Surfcae, "c2-p1", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.c2_player, src, playSound ? 1f : 0f, "c2-p1", target);
        }

        // Preview Surface 2
        if (SharedState.c2_preview_Surfcae_1 != null) {
            if (SharedState.c2_player_1 != null) StreamPlayer.cancel(SharedState.c2_player_1);
            SharedState.c2_player_1 = new MediaPlayer();
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.c2_preview_Surfcae_1, "c2-p2", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.c2_player_1, src, playSound ? 1f : 0f, "c2-p2", target);
        }
        XposedBridge.log("[Rinigan]Camera2 processing fully executed");
    }

    // ======================== processCamera2SessionCallback ========================
    private static void processCamera2SessionCallback(CameraCaptureSession.StateCallback callback_class) {
        if (callback_class == null) return;

        XposedHelpers.findAndHookMethod(callback_class.getClass(), "onConfigureFailed",
                CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]onConfigureFailed: " + param.args[0].toString());
            }
        });

        XposedHelpers.findAndHookMethod(callback_class.getClass(), "onConfigured",
                CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]onConfigured: " + param.args[0].toString());
            }
        });

        XposedHelpers.findAndHookMethod(callback_class.getClass(), "onClosed",
                CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[Rinigan]onClosed: " + param.args[0].toString());
            }
        });
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
            XposedBridge.log("[Rinigan]Camera2 hot-swap: source inactive, keeping current players");
            return;
        }
        String newRef = src.mediaRef();
        XposedBridge.log("[Rinigan]Hot-swap source → " + src.describe() + " sound=" + playSound);

        // === Resume playback logic (local files only — live links always start now) ===
        String oldPath = SharedState.currentVideoPath;
        SharedState.currentVideoPath = newRef;
        long resumePos = 0;
        if (!src.isStream()) {
            if (oldPath != null && !oldPath.equals(newRef) && SharedState.c2_hw_decode_obj != null) {
                long oldPos = SharedState.c2_hw_decode_obj.getCurrentPositionMs();
                if (oldPos > 0) SharedState.videoPositions.put(oldPath, oldPos);
            }
            Long saved = SharedState.videoPositions.get(newRef);
            resumePos = (saved != null) ? saved : 0;
        }
        // ======================
        SharedState.seekPositionMs = resumePos;
        // ======================

        // Reader Surface 1 — rebuilt if nulled by stopAllPlayers
        if (SharedState.c2_reader_Surfcae != null) {
            // v6.2: pages/HLS go through the tap bridge (peer frames for every link type)
            // v6.3: with relay mode ON every stream source goes through the bridge.
            boolean needsTapBridge = src.isStream() && (RelayClient.relayEnabled()
                    || LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
            if (needsTapBridge) {
                if (SharedState.c2_hw_decode_obj != null) {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = null;
                }
                FrameTapBridge.startWriter("c2-r1", src, SharedState.c2_reader_Surfcae,
                        SharedState.imageReaderFormat == 256);
            } else {
                FrameTapBridge.stop("c2-r1");
                try {
                    if (SharedState.c2_hw_decode_obj == null) {
                        SharedState.c2_hw_decode_obj = new VideoToFrames();
                    } else {
                        SharedState.c2_hw_decode_obj.stopDecode();
                        SharedState.c2_hw_decode_obj = new VideoToFrames();
                    }
                    if (SharedState.imageReaderFormat == 256) {
                        SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    SharedState.c2_hw_decode_obj.set_surfcae(TransformApplicator.wrapSurface(
                            SharedState.c2_reader_Surfcae, "c2-r1", newRef,
                            SharedState.toast_content, null));
                    if (resumePos > 0) SharedState.c2_hw_decode_obj.seekTo(resumePos);
                    if (SharedState.playPaused) SharedState.c2_hw_decode_obj.setPaused(true);
                    SharedState.c2_hw_decode_obj.decode(newRef);
                } catch (Throwable t) {
                    XposedBridge.log("[Rinigan]Hot-swap reader1 failed: " + t);
                }
            }
        }

        // Reader Surface 2
        if (SharedState.c2_reader_Surfcae_1 != null) {
            boolean needsTapBridge2 = src.isStream() && (RelayClient.relayEnabled()
                    || LinkType.isPageUrl(src.url) || LinkType.isHlsUrl(src.url));
            if (needsTapBridge2) {
                if (SharedState.c2_hw_decode_obj_1 != null) {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = null;
                }
                FrameTapBridge.startWriter("c2-r2", src, SharedState.c2_reader_Surfcae_1,
                        SharedState.imageReaderFormat == 256);
            } else {
                FrameTapBridge.stop("c2-r2");
                try {
                    if (SharedState.c2_hw_decode_obj_1 == null) {
                        SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
                    } else {
                        SharedState.c2_hw_decode_obj_1.stopDecode();
                        SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
                    }
                    if (SharedState.imageReaderFormat == 256) {
                        SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    SharedState.c2_hw_decode_obj_1.set_surfcae(TransformApplicator.wrapSurface(
                            SharedState.c2_reader_Surfcae_1, "c2-r2", newRef,
                            SharedState.toast_content, null));
                    if (resumePos > 0) SharedState.c2_hw_decode_obj_1.seekTo(resumePos);
                    if (SharedState.playPaused) SharedState.c2_hw_decode_obj_1.setPaused(true);
                    SharedState.c2_hw_decode_obj_1.decode(newRef);
                } catch (Throwable t) {
                    XposedBridge.log("[Rinigan]Hot-swap reader2 failed: " + t);
                }
            }
        }

        // Preview Surface 1 — rebuilt if nulled by stopAllPlayers
        if (SharedState.c2_preview_Surfcae != null) {
            if (SharedState.c2_player != null) StreamPlayer.cancel(SharedState.c2_player);
            if (SharedState.c2_player == null) {
                SharedState.c2_player = new MediaPlayer();
            }
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.c2_preview_Surfcae, "c2-p1", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.c2_player, src, playSound ? 1f : 0f, "c2-p1", target);
        }

        // Preview Surface 2
        if (SharedState.c2_preview_Surfcae_1 != null) {
            if (SharedState.c2_player_1 != null) StreamPlayer.cancel(SharedState.c2_player_1);
            if (SharedState.c2_player_1 == null) {
                SharedState.c2_player_1 = new MediaPlayer();
            }
            Surface target = TransformApplicator.wrapSurface(
                    SharedState.c2_preview_Surfcae_1, "c2-p2", newRef,
                    SharedState.toast_content, null);
            StreamPlayer.open(SharedState.c2_player_1, src, playSound ? 1f : 0f, "c2-p2", target);
        }

        XposedBridge.log("[Rinigan]Hot-swap finished");
    }

    // ======================== Sound toggle: dynamic volume update ========================
    public static void updateSoundVolume() {
        boolean playSound = HookGuards.shouldPlaySound();
        float vol = playSound ? 1f : 0f;
        XposedBridge.log("[Rinigan]Volume update → " + (playSound ? "ON" : "OFF"));

        if (SharedState.c2_player != null) {
            SharedState.c2_player.setVolume(vol, vol);
        }
        if (SharedState.c2_player_1 != null) {
            SharedState.c2_player_1.setVolume(vol, vol);
        }
    }

    // ======================== Stop all players (called when module is disabled) ========================
    public static void stopAllPlayers() {
        XposedBridge.log("[Rinigan]Camera2 stopping all players/decoders");
        StreamPlayer.cancelAll();
        TransformApplicator.releaseAll();
        // v6.2: stop this camera family's link display environments & bridges
        WebViewStreamer.stop("c2-p1");
        WebViewStreamer.stop("c2-p2");
        FrameTapBridge.stop("c2-r1");
        FrameTapBridge.stop("c2-r2");
        // v6.3: stop relayed frame clients too
        RelayClient.stop("c2-p1");
        RelayClient.stop("c2-p2");
        RelayClient.stop("c2-r1");
        RelayClient.stop("c2-r2");
        try {
            if (SharedState.c2_player != null) {
                SharedState.c2_player.stop();
                SharedState.c2_player.reset();
                SharedState.c2_player.release();
                SharedState.c2_player = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera2 stop c2_player: " + e);
        }
        try {
            if (SharedState.c2_player_1 != null) {
                SharedState.c2_player_1.stop();
                SharedState.c2_player_1.reset();
                SharedState.c2_player_1.release();
                SharedState.c2_player_1 = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera2 stop c2_player_1: " + e);
        }
        try {
            if (SharedState.c2_hw_decode_obj != null) {
                SharedState.c2_hw_decode_obj.stopDecode();
                SharedState.c2_hw_decode_obj = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera2 stop hw_decode1: " + e);
        }
        try {
            if (SharedState.c2_hw_decode_obj_1 != null) {
                SharedState.c2_hw_decode_obj_1.stopDecode();
                SharedState.c2_hw_decode_obj_1 = null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Rinigan]Camera2 stop hw_decode2: " + e);
        }
    }
}