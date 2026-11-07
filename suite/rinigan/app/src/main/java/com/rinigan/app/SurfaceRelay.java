package com.rinigan.app;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/** Surface relay stub. A future version will implement SurfaceTexture relay rendering. */
public class SurfaceRelay {
    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private boolean initialized;

    private SurfaceRelay(Surface outputSurface, String tag) {
        if (outputSurface != null) {
            try {
                inputTexture = new SurfaceTexture(0);
                inputTexture.detachFromGLContext();
                inputSurface = new Surface(inputTexture);
                initialized = true;
            } catch (Exception e) {
                initialized = false;
            }
        }
    }

    public static SurfaceRelay createSafely(Surface output, String tag) {
        if (output == null) return null;
        try {
            return new SurfaceRelay(output, tag);
        } catch (Exception e) {
            return null;
        }
    }

    public static void releaseSafely(SurfaceRelay relay) {
        if (relay != null) {
            if (relay.inputSurface != null) { relay.inputSurface.release(); relay.inputSurface = null; }
            if (relay.inputTexture != null) { relay.inputTexture.release(); relay.inputTexture = null; }
            relay.initialized = false;
        }
    }

    public Surface getInputSurface() { return inputSurface; }
    public void setRotation(int degrees) {}
}