package com.rinigan.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.view.Surface;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade used by the camera hooks. Single entry point for:
 *
 *  - resolving the active {@link TransformProfile} for the calling application
 *    (CallingAppDetector + AppProfileManager),
 *  - wrapping target Surfaces with a {@link GlFrameRelay} so every frame passes through
 *    the OpenGL transformation matrix before reaching the app,
 *  - transforming raw NV21 preview buffers for the Camera1 onPreviewFrame path,
 *  - caching video dimensions so the matrix math knows the true source size.
 *
 * Design guarantees:
 *  - Every method fails soft: on any problem the original surface/buffer behavior is kept.
 *  - wrapSurface() is idempotent per output Surface (registry keyed by identity).
 *  - releaseAll() is called from stopAllPlayers()/hot-swap to avoid relay leaks.
 */
public final class TransformApplicator {

    private TransformApplicator() {
    }

    // relay registry: output surface -> relay (identity-based)
    private static final Map<Surface, GlFrameRelay> RELAYS = new ConcurrentHashMap<>();

    // resolved state for the current process
    private static volatile String activePackage = null;
    private static volatile TransformProfile activeProfile = null;
    private static volatile boolean profileResolved = false;

    // video dimension cache: path -> {w, h}
    private static final Map<String, int[]> VIDEO_DIMS = new ConcurrentHashMap<>();

    // ======================== Profile resolution ========================

    /**
     * Resolves (and caches) the effective profile for the process that requested the camera.
     *
     * @param context     hooked app context (may be null)
     * @param hookPackage package from LoadPackageParam (may be null)
     */
    public static TransformProfile resolveProfile(Context context, String hookPackage) {
        if (profileResolved && activeProfile != null) {
            TransformProfile p = activeProfile;
            // honor global disable switch
            if (!engineEnabled()) {
                return TransformProfile.universalDefault(); // identity profile (rot 0, FIT, no flips)
            }
            return p;
        }
        synchronized (TransformApplicator.class) {
            if (profileResolved && activeProfile != null) return activeProfile;
            String pkg = CallingAppDetector.detect(context, hookPackage);
            activePackage = pkg;
            TransformProfile p = AppProfileManager.getInstance().getProfile(pkg);
            if (p == null || !p.enabled) p = TransformProfile.universalDefault();
            activeProfile = p;
            profileResolved = true;
            LogUtil.log("[Transform]resolved profile for '" + pkg + "' → " + p);
            return p;
        }
    }

    /** Package detected for the current process (diagnostics). */
    public static String activePackage() {
        return activePackage != null ? activePackage : "";
    }

    /** Per-app engine master switch (cs_config.json "perapp_engine_enabled", default ON). */
    public static boolean engineEnabled() {
        try {
            return !HookGuards.isAppProcess()
                    && !HookGuards.getConfig().getBoolean(ConfigManager.KEY_PERAPP_ENGINE_DISABLED, false);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Clears caches so the next camera session re-resolves profiles (hot config change). */
    public static void invalidateCaches() {
        synchronized (TransformApplicator.class) {
            profileResolved = false;
            activeProfile = null;
        }
        AppProfileManager.getInstance().invalidate();
        CallingAppDetector.clearCache();
    }

    // ======================== Surface wrapping (GL path) ========================

    /**
     * Returns a Surface the media player/decoder should render into. When the engine is
     * active this is the GL relay input; frames appear (transformed) on {@code output}.
     * Falls back to {@code output} itself whenever the engine is off or GL fails.
     *
     * @param output      the real application surface
     * @param tag         short label for logs
     * @param videoPath   replacement video path (source dimensions), may be null
     * @param context     hooked app context, may be null
     * @param hookPackage LoadPackageParam package, may be null
     */
    public static Surface wrapSurface(Surface output, String tag, String videoPath,
                                      Context context, String hookPackage) {
        if (output == null || !output.isValid()) return output;
        try {
            if (!engineEnabled()) {
                releaseRelay(output);
                return output;
            }
            GlFrameRelay existing = RELAYS.get(output);
            if (existing != null) {
                if (existing.getInputSurface() != null && existing.getInputSurface().isValid()) {
                    // hot update profile / source size on the live relay
                    int[] dims = getVideoDimensions(videoPath);
                    existing.updateTransform(
                            resolveProfile(context, hookPackage),
                            dims != null ? dims[0] : 0,
                            dims != null ? dims[1] : 0);
                    return existing.getInputSurface();
                }
                releaseRelay(output);
            }

            int[] dims = getVideoDimensions(videoPath);
            int srcW = dims != null ? dims[0] : 0;
            int srcH = dims != null ? dims[1] : 0;
            TransformProfile profile = resolveProfile(context, hookPackage);
            if (profile.isNoOp() && srcW <= 0) {
                return output; // nothing to change, avoid spawning GL threads pointlessly
            }
            GlFrameRelay relay = GlFrameRelay.createSafely(output, profile, srcW, srcH, tag);
            if (relay == null) {
                LogUtil.log("[Transform][" + tag + "]GL unavailable → passthrough");
                return output;
            }
            RELAYS.put(output, relay);
            LogUtil.log("[Transform][" + tag + "]surface wrapped for '" + activePackage()
                    + "' profile=" + profile);
            return relay.getInputSurface();
        } catch (Throwable t) {
            LogUtil.log("[Transform][" + tag + "]wrap failed → passthrough: " + t);
            return output;
        }
    }

    /** Releases the relay bound to one output surface (if any). */
    public static void releaseRelay(Surface output) {
        if (output == null) return;
        GlFrameRelay relay = RELAYS.remove(output);
        if (relay != null) {
            try {
                relay.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Releases every relay (module disabled / hot-swap / process teardown). */
    public static void releaseAll() {
        for (Surface s : RELAYS.keySet().toArray(new Surface[0])) {
            releaseRelay(s);
        }
        RELAYS.clear();
        LogUtil.log("[Transform]all relays released");
    }

    // ======================== NV21 buffer path ========================

    /**
     * Transforms the decoded video frame into the app's preview buffer.
     * Falls back to a legacy truncated copy when the engine is off or the data is unusable.
     *
     * @param videoPath replacement video path (for source dimensions), may be null
     */
    public static void transformNv21IntoPreview(byte[] videoFrame, String videoPath,
                                                byte[] appBuffer, int appW, int appH,
                                                Context context, String hookPackage) {
        if (videoFrame == null || appBuffer == null || appBuffer.length == 0) return;
        int[] dims = getVideoDimensions(videoPath);
        int srcW = dims != null ? dims[0] : 0;
        int srcH = dims != null ? dims[1] : 0;
        int expected = srcW * srcH * 3 / 2;

        boolean canTransform = engineEnabled()
                && srcW > 0 && srcH > 0
                && videoFrame.length >= expected;

        if (canTransform) {
            TransformProfile profile = resolveProfile(context, hookPackage);
            boolean shapeMatches = (srcW == appW && srcH == appH);
            if (!shapeMatches || !profile.isNoOp()) {
                if (FrameTransformer.transformNv21(videoFrame, srcW, srcH,
                        appBuffer, appW, appH, profile)) {
                    return; // transformed buffer written in place
                }
            } else if (shapeMatches) {
                // identical shape and identity profile → plain copy is exact
                System.arraycopy(videoFrame, 0, appBuffer, 0,
                        Math.min(videoFrame.length, appBuffer.length));
                return;
            }
        }
        // legacy behavior
        System.arraycopy(videoFrame, 0, appBuffer, 0,
                Math.min(videoFrame.length, appBuffer.length));
    }

    // ======================== Video dimensions ========================

    /** URLs (live links) are probed in the background — never on the camera thread. */
    private static final java.util.Set<String> PROBE_PENDING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean isNetworkRef(String path) {
        return path.startsWith("http://") || path.startsWith("https://");
    }

    /**
     * Cached video dimensions. Local paths use MediaMetadataRetriever directly;
     * network links return the cached value if known, otherwise kick off a
     * background probe and return null (callers use safe defaults — the real
     * size arrives via StreamPlayer.onVideoSizeChanged → reportStreamDimensions).
     */
    public static int[] getVideoDimensions(String path) {
        if (path == null || path.isEmpty()) return null;
        int[] cached = VIDEO_DIMS.get(path);
        if (cached != null) return cached;
        if (isNetworkRef(path)) {
            probeNetworkDimensions(path);
            return null; // never block the camera thread on network I/O
        }
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(path);
            int w = Integer.parseInt(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = 0;
            try {
                rotation = Integer.parseInt(
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } catch (Exception ignored) {
            }
            // apply container rotation to get true storage dimensions
            int[] dims = (rotation == 90 || rotation == 270)
                    ? new int[]{h, w} : new int[]{w, h};
            VIDEO_DIMS.put(path, dims);
            LogUtil.log("[Transform]video dims " + path + " → " + dims[0] + "x" + dims[1]);
            return dims;
        } catch (Throwable t) {
            LogUtil.log("[Transform]video dims unknown for " + path + ": " + t);
            return null;
        } finally {
            try {
                if (mmr != null) mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v6.1 — Live size feed from StreamPlayer.onVideoSizeChanged.
     * Updates the dimension cache and hot-resizes every active GL relay so the
     * FIRST frame of a live link is already correctly shaped (no stretch, no black).
     */
    public static void reportStreamDimensions(String mediaRef, int w, int h) {
        try {
            if (mediaRef == null || w <= 0 || h <= 0) return;
            int[] existing = VIDEO_DIMS.get(mediaRef);
            if (existing != null && existing[0] == w && existing[1] == h) return;
            VIDEO_DIMS.put(mediaRef, new int[]{w, h});
            LogUtil.log("[Transform]live dims reported " + w + "x" + h);
            for (GlFrameRelay relay : RELAYS.values()) {
                try {
                    relay.updateTransform(null, w, h);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Background MediaMetadataRetriever probe for network sources. */
    private static void probeNetworkDimensions(final String url) {
        if (!PROBE_PENDING.add(url)) return; // already probing / probed
        Thread probe = new Thread(() -> {
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Rinigan/6.1");
                try {
                    mmr.setDataSource(url, headers);
                } catch (Throwable headerFail) {
                    mmr.setDataSource(url);
                }
                int w = Integer.parseInt(
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int h = Integer.parseInt(
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if (w > 0 && h > 0) {
                    LogUtil.log("[Transform]probed live dims " + w + "x" + h);
                    reportStreamDimensions(url, w, h);
                }
            } catch (Throwable t) {
                LogUtil.log("[Transform]live dims probe failed (non-fatal): " + t);
            } finally {
                try {
                    if (mmr != null) mmr.release();
                } catch (Throwable ignored) {
                }
                PROBE_PENDING.remove(url);
            }
        }, "Rinigan-DimsProbe");
        probe.setDaemon(true);
        probe.start();
    }

    /** Clears the dimension cache (video replaced). */
    public static void clearVideoDims() {
        VIDEO_DIMS.clear();
    }

    /** Snapshot of live relays (diagnostics). */
    public static List<Surface> trackedOutputs() {
        return java.util.Arrays.asList(RELAYS.keySet().toArray(new Surface[0]));
    }
}
