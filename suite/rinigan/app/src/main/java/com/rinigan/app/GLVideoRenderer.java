package com.rinigan.app;

import android.view.Surface;

/** GL video renderer stub. A future version will implement OpenGL rotation rendering. */
public class GLVideoRenderer {
    private Surface inputSurface;
    private boolean initialized;

    private GLVideoRenderer(Surface outputSurface, String tag) {
        // stub implementation: use outputSurface directly
        this.inputSurface = outputSurface;
        this.initialized = (outputSurface != null);
    }

    public static GLVideoRenderer createSafely(Surface output, String tag) {
        if (output == null) return null;
        try {
            return new GLVideoRenderer(output, tag);
        } catch (Exception e) {
            LogUtil.log("[CS]GLVideoRenderer creation failed: " + e);
            return null;
        }
    }

    public static void releaseSafely(GLVideoRenderer renderer) {
        if (renderer != null) {
            try {
                renderer.inputSurface = null;
                renderer.initialized = false;
            } catch (Exception ignored) {}
        }
    }

    public Surface getInputSurface() { return inputSurface; }
    public boolean isInitialized() { return initialized; }
    public void setRotation(int degrees) {}

    public android.graphics.Bitmap captureFrameWithRotation(int w, int h, int rotation) {
        return null;
    }
}