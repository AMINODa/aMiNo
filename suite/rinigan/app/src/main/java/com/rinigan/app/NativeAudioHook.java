package com.rinigan.app;

import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Native-layer audio hook — tries to load a .so fallback;
 * if present, native methods override the low-level AudioRecord behavior,
 * otherwise falls back to the Java-layer hook.
 */
public class NativeAudioHook {
    private static boolean nativeAvailable = false;

    public static void init() {
        try {
            System.loadLibrary("native_audio_hook");
            nativeAvailable = true;
            XposedBridge.log("[Rinigan]NativeAudioHook .so loaded, native-layer audio interception active");
        } catch (Throwable t) {
            LogUtil.log("[CS]native audio .so not found, falling back to Java-layer hook: " + t);
        }
    }

    /** Returns whether the native layer is available */
    public static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    /**
     * Sets the audio data path to inject via JNI.
     * Corresponding native function: setAudioSourcePath(JNIEnv*, jclass, jstring)
     */
    public static void setAudioSource(String audioPath) {
        if (nativeAvailable) {
            nativeSetAudioSourcePath(audioPath);
        }
    }

    /** JNI: enable/disable mute */
    public static void setMute(boolean mute) {
        if (nativeAvailable) {
            nativeSetMute(mute);
        }
    }

    /**
     * JNI: update the voice input buffer in real time (video_sync mode only).
     * Called from the Java-layer callback to push decoded PCM data into the native layer.
     */
    public static void pushPcmData(byte[] pcm, int offset, int length) {
        if (nativeAvailable) {
            nativePushPcm(pcm, offset, length);
        }
    }

    // ---- Native method declarations ----
    private static native void nativeSetAudioSourcePath(String path);
    private static native void nativeSetMute(boolean mute);
    private static native void nativePushPcm(byte[] pcm, int offset, int length);
}