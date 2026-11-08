package com.rinigan.app;

import java.io.File;

/**
 * v6.1 — Live streaming rework.
 *
 * Describes WHERE the replacement video comes from:
 *  - LOCAL: a video file inside the Camera1 directory (previous behavior)
 *  - STREAM: a remote http(s) link that is PLAYED LIVE over the network.
 *    Nothing is ever downloaded to disk — frames are decoded in real time
 *    and injected in place of the camera feed.
 *
 * All camera hooks (Camera1 / Camera2) resolve their source through
 * {@link HookGuards#getVideoSource()} and use:
 *   {@link #isActive()}      instead of  file.exists()
 *   {@link #mediaRef()}      instead of  file.getAbsolutePath()
 *   {@link #isStream()}      to choose prepareAsync + retry logic
 */
public final class VideoSource {

    /** Placeholder used in logs / toasts for the stream source. */
    public static final String STREAM_TAG = "<live-link>";

    public final boolean stream;
    /** Remote URL when {@link #stream} is true, otherwise null. */
    public final String url;
    /** Local video file when {@link #stream} is false, otherwise the fallback file (may not exist). */
    public final File file;

    private VideoSource(boolean stream, String url, File file) {
        this.stream = stream;
        this.url = url;
        this.file = file;
    }

    /** A live network video source — played directly from the network, never downloaded. */
    public static VideoSource stream(String url, File localFallback) {
        return new VideoSource(true, url, localFallback);
    }

    /** A local video file source (classic mode). */
    public static VideoSource local(File file) {
        return new VideoSource(false, null, file);
    }

    /** True when this source can actually deliver frames. */
    public boolean isActive() {
        if (stream) return url != null && !url.trim().isEmpty();
        return file != null && file.exists() && !file.isDirectory();
    }

    /** True when the source is a live network link. */
    public boolean isStream() {
        return stream && url != null && !url.trim().isEmpty();
    }

    /**
     * The string handed to the media stack:
     * the URL itself for streams, or the absolute file path for local files.
     */
    public String mediaRef() {
        return stream ? url : (file != null ? file.getAbsolutePath() : null);
    }

    /** Short human-readable description for logs. */
    public String describe() {
        return stream ? ("live:" + url) : (file != null ? file.getName() : "none");
    }

    /** Identifier used for resume-position bookkeeping (streams have no positions). */
    public String trackId() {
        return stream ? STREAM_TAG + ":" + url : (file != null ? file.getAbsolutePath() : null);
    }

    @Override
    public String toString() {
        return describe();
    }
}
