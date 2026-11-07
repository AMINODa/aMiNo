package com.rinigan.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable transformation profile for a single (or wildcard) target application.
 *
 * A profile describes how the replacement video frame must be reshaped before it
 * reaches the target application's Surface / SurfaceTexture / preview buffer:
 *
 *  - rotation          : clockwise rotation applied to the source frame (0 / 90 / 180 / 270).
 *  - scaleMode         : how the (rotated) frame is fitted into the output buffer.
 *                          FIT_CENTER  - preserve full frame, letterbox bars if needed.
 *                          CENTER_CROP - fill the output completely, crop overflow (no bars).
 *                          STRETCH     - fill the output completely, distort aspect ratio.
 *  - targetWidth/Height: explicit output resolution override (0x0 = follow the app request).
 *  - flipHorizontal    : mirror left/right (useful for mirrored front-camera streams).
 *  - flipVertical      : mirror top/bottom.
 *  - normalizePortrait : when true and no explicit rotation is set, automatically rotate a
 *                        landscape source by 90 degrees if the app consumes a portrait buffer.
 *  - enabled           : profile switch (a disabled profile falls back to the universal default).
 *
 * Instances are created from JSON (user editable) or from the built-in preset table.
 */
public class TransformProfile {

    public static final String SCALE_FIT_CENTER = "FIT_CENTER";
    public static final String SCALE_CENTER_CROP = "CENTER_CROP";
    public static final String SCALE_STRETCH = "STRETCH";

    public static final String WILDCARD = "*";

    public final String packageName;
    public final int rotation;
    public final String scaleMode;
    public final int targetWidth;
    public final int targetHeight;
    public final boolean flipHorizontal;
    public final boolean flipVertical;
    public final boolean normalizePortrait;
    public final boolean enabled;
    public final boolean builtin;

    private TransformProfile(String packageName, int rotation, String scaleMode,
                             int targetWidth, int targetHeight,
                             boolean flipHorizontal, boolean flipVertical,
                             boolean normalizePortrait, boolean enabled, boolean builtin) {
        this.packageName = packageName;
        this.rotation = normalizeRotation(rotation);
        this.scaleMode = sanitizeScaleMode(scaleMode);
        this.targetWidth = Math.max(0, targetWidth);
        this.targetHeight = Math.max(0, targetHeight);
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.normalizePortrait = normalizePortrait;
        this.enabled = enabled;
        this.builtin = builtin;
    }

    // ======================== Factories ========================

    public static TransformProfile create(String packageName, int rotation, String scaleMode,
                                          int targetWidth, int targetHeight,
                                          boolean flipHorizontal, boolean flipVertical,
                                          boolean normalizePortrait, boolean enabled,
                                          boolean builtin) {
        return new TransformProfile(packageName, rotation, scaleMode, targetWidth, targetHeight,
                flipHorizontal, flipVertical, normalizePortrait, enabled, builtin);
    }

    /** The universal fallback applied to every unclassified application. */
    public static TransformProfile universalDefault() {
        return new TransformProfile(WILDCARD, 0, SCALE_FIT_CENTER, 0, 0,
                false, false, true, true, true);
    }

    // ======================== JSON support ========================

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("packageName", packageName);
            o.put("rotation", rotation);
            o.put("scaleMode", scaleMode);
            if (targetWidth > 0 && targetHeight > 0) {
                o.put("targetResolution", targetWidth + "x" + targetHeight);
            }
            o.put("flipHorizontal", flipHorizontal);
            o.put("flipVertical", flipVertical);
            o.put("normalizePortrait", normalizePortrait);
            o.put("enabled", enabled);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    /** Parses a single profile object. Returns null when the object is not usable. */
    public static TransformProfile fromJson(JSONObject o, boolean builtin) {
        if (o == null) return null;
        String pkg = o.optString("packageName", "");
        if (pkg == null || pkg.trim().isEmpty()) return null;
        int rotation = o.optInt("rotation", 0);
        String scaleMode = o.optString("scaleMode", SCALE_FIT_CENTER);
        int tw = o.optInt("targetWidth", 0);
        int th = o.optInt("targetHeight", 0);
        String res = o.optString("targetResolution", "");
        if (res != null) {
            String[] parts = res.toLowerCase().split("[x*\\u00d7]");
            if (parts.length == 2) {
                try {
                    tw = Integer.parseInt(parts[0].trim());
                    th = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new TransformProfile(pkg.trim(),
                rotation,
                scaleMode,
                tw, th,
                o.optBoolean("flipHorizontal", false),
                o.optBoolean("flipVertical", false),
                o.optBoolean("normalizePortrait", false),
                o.optBoolean("enabled", true),
                builtin);
    }

    // ======================== Helpers ========================

    public static int normalizeRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) r += 360;
        if (r != 0 && r != 90 && r != 180 && r != 270) return 0;
        return r;
    }

    public static String sanitizeScaleMode(String mode) {
        if (SCALE_CENTER_CROP.equals(mode)) return SCALE_CENTER_CROP;
        if (SCALE_STRETCH.equals(mode)) return SCALE_STRETCH;
        return SCALE_FIT_CENTER;
    }

    /** True when this profile would not change the frame in any way. */
    public boolean isNoOp() {
        return rotation == 0 && !flipHorizontal && !flipVertical
                && targetWidth <= 0 && targetHeight <= 0
                && SCALE_FIT_CENTER.equals(scaleMode);
    }

    /** Prefix match: "com.whatsapp" matches "com.whatsapp" and "com.whatsapp.w4b". */
    public boolean matches(String pkg) {
        if (pkg == null) return false;
        if (WILDCARD.equals(packageName)) return true;
        return pkg.equals(packageName) || pkg.startsWith(packageName + ".");
    }

    @Override
    public String toString() {
        return "TransformProfile{" + packageName +
                ", rot=" + rotation +
                ", scale=" + scaleMode +
                ", res=" + (targetWidth > 0 ? targetWidth + "x" + targetHeight : "auto") +
                ", flipH=" + flipHorizontal +
                ", flipV=" + flipVertical +
                ", norm=" + normalizePortrait +
                ", enabled=" + enabled +
                ", builtin=" + builtin + "}";
    }
}
