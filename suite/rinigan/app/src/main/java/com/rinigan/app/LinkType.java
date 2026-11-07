package com.rinigan.app;

import android.net.Uri;

import java.util.Locale;

/**
 * v6.2 — Classifies a user-provided link so the camera pipeline can pick the
 * correct DISPLAY ENVIRONMENT for it (the root-cause fix for "link shows
 * nothing when the camera opens").
 *
 * Two families:
 *  MEDIA — a direct media resource a media stack can stream:
 *      .mp4 .m4v .mkv .mov .webm .m3u8 .mpd .ts .m2ts .3gp .avi .flv .mpg .mpeg .ogv .aac .mp3 .m4a .wav
 *  PAGE  — everything else: websites, YouTube/Facebook/TikTok watch pages,
 *      portals, short links, links without a media extension at all.
 *
 * A PAGE link can NEVER be played by MediaPlayer or MediaExtractor — that was
 * the exact reason the v6.1 camera stayed black. PAGE links are rendered by
 * {@link WebViewStreamer} (a real browser display environment) instead.
 */
public final class LinkType {

    private LinkType() {}

    /** Direct-media extensions (checked against the path's last segment). */
    private static final String[] MEDIA_EXTENSIONS = {
            ".mp4", ".m4v", ".mkv", ".mov", ".webm", ".m3u8", ".mpd",
            ".ts", ".m2ts", ".3gp", ".avi", ".flv", ".mpg", ".mpeg", ".ogv",
            ".aac", ".mp3", ".m4a", ".wav"
    };

    /** True when the URL points at a direct media stream (file/HLS/DASH). */
    public static boolean isMediaUrl(String url) {
        return classify(url) == Kind.MEDIA;
    }

    /** True when the URL is a web PAGE (site/watch page/anything non-media). */
    public static boolean isPageUrl(String url) {
        return classify(url) == Kind.PAGE;
    }

    /** True when the URL is an HLS/DASH playlist (streamable, but not by MediaExtractor). */
    public static boolean isHlsUrl(String url) {
        String seg = lastSegment(url);
        return seg.endsWith(".m3u8") || seg.endsWith(".mpd");
    }

    public static Kind classify(String url) {
        if (url == null) return Kind.PAGE;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return Kind.PAGE;
        String lower = trimmed.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // no scheme at all — treat as a page; WebView may still handle it
            return Kind.PAGE;
        }
        String seg = lastSegment(trimmed);
        for (String ext : MEDIA_EXTENSIONS) {
            if (seg.endsWith(ext)) return Kind.MEDIA;
        }
        return Kind.PAGE;
    }

    /** Last path segment with query/fragment stripped, lowercased. */
    private static String lastSegment(String url) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath();
            if (path == null || path.isEmpty()) return "";
            int slash = path.lastIndexOf('/');
            String seg = (slash >= 0) ? path.substring(slash + 1) : path;
            return seg.toLowerCase(Locale.US);
        } catch (Throwable t) {
            return "";
        }
    }

    public enum Kind { MEDIA, PAGE }
}
