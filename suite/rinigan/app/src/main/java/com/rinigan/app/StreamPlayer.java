package com.rinigan.app;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * v6.1 — Live link streaming engine (NO download, NO black screen).
 *
 * Opens a {@link MediaPlayer} on a remote http(s) link (or a local file) so the
 * video plays LIVE, in real time, exactly where the camera feed used to be.
 *
 * Anti-black-screen measures, in order:
 *  1. prepareAsync() for streams — never blocks the host app's camera thread (no ANR,
 *     no frozen surface while the network connects).
 *  2. User-Agent header — many CDNs reject requests without one.
 *  3. onVideoSizeChanged → TransformApplicator.reportStreamDimensions() — the GL relays
 *     resize their SurfaceTexture buffers the moment the real resolution is known, so
 *     the very first frame is already correctly shaped.
 *  4. Watchdog — if playback has not actually started within the configured timeout
 *     (KEY_STREAM_TIMEOUT_MS), the attempt is converted into a retry.
 *  5. Auto-reconnect (KEY_STREAM_AUTO_RECONNECT) — up to 5 attempts with exponential
 *     backoff (1s, 2s, 4s, 8s, 15s) on any error / stall.
 *  6. Local fallback (KEY_STREAM_LOCAL_FALLBACK) — when every attempt fails, the last
 *     resort is the local video, so the surface never stays permanently black.
 *
 * All timers run on a single shared background thread; MediaPlayer callbacks arrive
 * on the player's application loop, so every player interaction is posted to the
 * same background handler to respect MediaPlayer's thread-safety rules.
 */
public final class StreamPlayer {

    private StreamPlayer() {}

    private static final String TAG = "[Stream]";
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 15000};

    private static HandlerThread timerThread;
    private static Handler timerHandler;

    /** Per-player-attempt state, keyed by the MediaPlayer identity hash. */
    private static final Map<Integer, Attempt> ATTEMPTS = new ConcurrentHashMap<>();

    private static final class Attempt {
        final String tag;
        final VideoSource source;
        final boolean playSound;
        final float volume;
        final Surface surface; // pre-wrapped target surface (re-applied after every reset)
        volatile boolean cancelled = false;
        volatile boolean prepared = false;
        volatile boolean started = false;
        volatile Runnable watchRunnable;

        Attempt(String tag, VideoSource source, boolean playSound, float volume, Surface surface) {
            this.tag = tag;
            this.source = source;
            this.playSound = playSound;
            this.volume = volume;
            this.surface = surface;
        }
    }

    private static synchronized Handler handler() {
        if (timerHandler == null) {
            timerThread = new HandlerThread("Rinigan-StreamWatch");
            timerThread.start();
            timerHandler = new Handler(timerThread.getLooper());
        }
        return timerHandler;
    }

    // ======================== Public API ========================

    /**
     * Opens {@code player} on {@code source}.
     *
     * IMPORTANT ordering guarantee (anti-black-screen): this method OWNS the
     * reset → setSurface → setVolume sequence. MediaPlayer.reset() silently
     * clears the attached Surface and volume, so callers must NOT set them
     * beforehand — instead they pass the (already wrapped) target Surface and
     * the desired volume here, and they are re-applied after every reset,
     * including on every automatic reconnect.
     *
     * @param volume  0..1 (0 = muted virtual camera, 1 = play video sound)
     * @param surface target surface from TransformApplicator.wrapSurface(...) (may be null)
     */
    public static void open(final MediaPlayer player, final VideoSource source,
                            final float volume, final String tag, final Surface surface) {
        open(player, source, volume, tag, surface, true);
    }

    /**
     * v6.3 core — with {@code allowRelay} the frame source is the Stream Engine
     * running in the Rinigan APP process (LSPatch-safe: zero network/rendering
     * inside the target). When the relay cannot be reached the classic
     * in-process pipeline below takes over automatically.
     */
    private static void open(final MediaPlayer player, final VideoSource source,
                             final float volume, final String tag, final Surface surface,
                             final boolean allowRelay) {
        if (player == null || source == null || !source.isActive()) return;

        // ---- v6.3: cross-process relay FIRST (LSPatch root-cause fix) ----
        // The target app only needs a valid Surface; the engine (our app) does
        // ALL networking + rendering. http links, HLS and web pages all work
        // even when the target app has no INTERNET permission at all.
        if (allowRelay && source.isStream() && RelayClient.relayEnabled()) {
            StreamPlayer.cancel(player); // this player stays idle — engine owns rendering
            WebViewStreamer.stop(tag);
            XposedBridge.log(TAG + "[" + tag + "]RELAY path → Stream Engine: " + source.mediaRef());
            boolean initiated = RelayClient.startSurface(tag, source.url, surface, () -> {
                XposedBridge.log(TAG + "[" + tag + "]relay failed permanently → in-process pipeline");
                open(player, source, volume, tag, surface, false);
            });
            if (initiated) {
                HookDiag.report(SharedState.toast_content, "relay",
                        "surface pipeline relayed to engine (" + tag + ")");
                return;
            }
            XposedBridge.log(TAG + "[" + tag + "]relay refused → in-process pipeline");
        }

        // ---- v6.2: PAGE links (websites, watch pages) — the EXACT root cause of the
        // black camera in v6.1. MediaPlayer cannot play a web page; the link must be
        // rendered in a real display environment ({@link WebViewStreamer}) instead.
        if (source.isStream() && LinkType.isPageUrl(source.url)) {
            XposedBridge.log(TAG + "[" + tag + "]PAGE link → display environment: " + source.mediaRef());
            StreamPlayer.cancel(player); // this player stays idle; env owns rendering
            Runnable onFailure = () -> {
                XposedBridge.log(TAG + "[" + tag + "]page env failed → local fallback");
                if (!fallbackToLocal(source, volume > 0f, tag) && WebViewStreamer.activeCount() == 0) {
                    XposedBridge.log(TAG + "[" + tag + "]no local fallback — retrying page env in 15s");
                    handler().postDelayed(() -> {
                        if (ATTEMPTS.containsKey(System.identityHashCode(player))) return;
                        WebViewStreamer.startPreview(tag, source.url, surface, volume > 0f,
                                () -> fallbackToLocal(source, volume > 0f, tag));
                    }, 15000);
                }
            };
            boolean started = WebViewStreamer.startPreview(tag, source.url, surface,
                    volume > 0f, onFailure);
            if (!started) onFailure.run();
            return;
        }

        if (!source.isStream()) {
            // ---- classic local playback ----
            ATTEMPTS.remove(System.identityHashCode(player));
            // v6.2: switching away from a page source — kill this tag's display env
            WebViewStreamer.stop(tag);
            try {
                try {
                    player.reset();
                } catch (Throwable ignored) {
                }
                if (surface != null && surface.isValid()) {
                    player.setSurface(surface);
                }
                player.setDataSource(source.mediaRef());
                player.setAudioAttributes(audioAttrs());
                player.setLooping(true);
                player.setVolume(volume, volume);
                player.setOnPreparedListener(mp -> {
                    try {
                        mp.start();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "[" + tag + "]local start failed: " + t);
                    }
                });
                player.prepare();
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[" + tag + "]local open failed: " + t);
            }
            return;
        }

        // ---- LIVE streaming ----
        // v6.2: switching away from a page source — kill this tag's display env
        WebViewStreamer.stop(tag);
        final int id = System.identityHashCode(player);
        final Attempt attempt = new Attempt(tag, source, true, volume, surface);
        ATTEMPTS.put(id, attempt);
        XposedBridge.log(TAG + "[" + tag + "]LIVE open: " + source.mediaRef());
        prepareStream(player, attempt, 0);
    }

    /** Cancels watchdog/retry state for a player that is about to be released. */
    public static void cancel(MediaPlayer player) {
        if (player == null) return;
        Attempt a = ATTEMPTS.remove(System.identityHashCode(player));
        if (a != null) {
            a.cancelled = true;
            unscheduleWatch(a);
        }
    }

    /** Cancels every pending retry/watchdog (module disabled / process teardown). */
    public static void cancelAll() {
        for (Attempt a : ATTEMPTS.values()) {
            a.cancelled = true;
            unscheduleWatch(a);
        }
        ATTEMPTS.clear();
    }

    // ======================== Internals ========================

    private static void prepareStream(final MediaPlayer player, final Attempt attempt, final int attemptNo) {
        if (attempt.cancelled) return;
        final int id = System.identityHashCode(player);
        handler().post(() -> {
            if (attempt.cancelled || ATTEMPTS.get(id) != attempt) return;
            try {
                try {
                    player.reset();
                } catch (Throwable ignored) {
                }
                // re-apply surface + volume after EVERY reset (incl. reconnects)
                if (attempt.surface != null && attempt.surface.isValid()) {
                    player.setSurface(attempt.surface);
                }
                player.setAudioAttributes(audioAttrs());
                // MediaPlayer has no (String, Map) overload — headers require the
                // (Context, Uri, Map) form with the host app's context.
                android.content.Context ctx = SharedState.toast_content;
                if (ctx != null) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Rinigan/6.1");
                    player.setDataSource(ctx, android.net.Uri.parse(attempt.source.mediaRef()), headers);
                } else {
                    player.setDataSource(attempt.source.mediaRef());
                }
                player.setLooping(true); // no-op for live sources; loops VOD links
                player.setVolume(attempt.volume, attempt.volume);
                player.setOnPreparedListener(mp -> {
                    attempt.prepared = true;
                    unscheduleWatch(attempt);
                    try {
                        mp.start();
                        attempt.started = true;
                        XposedBridge.log(TAG + "[" + attempt.tag + "]LIVE playing (attempt " + (attemptNo + 1) + ")");
                        armStallWatch(player, attempt, attemptNo);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "[" + attempt.tag + "]start failed: " + t);
                        scheduleRetry(player, attempt, attemptNo, "start-failed");
                    }
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    XposedBridge.log(TAG + "[" + attempt.tag + "]error what=" + what + " extra=" + extra);
                    scheduleRetry(player, attempt, attemptNo, "error-" + what);
                    return true; // handled — prevents host app crashes
                });
                player.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        XposedBridge.log(TAG + "[" + attempt.tag + "]buffering…");
                        armStallWatch(player, attempt, attemptNo);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        XposedBridge.log(TAG + "[" + attempt.tag + "]buffering done");
                    }
                    return false;
                });
                player.setOnVideoSizeChangedListener((mp, w, h) -> {
                    if (w > 0 && h > 0) {
                        XposedBridge.log(TAG + "[" + attempt.tag + "]video size " + w + "x" + h);
                        TransformApplicator.reportStreamDimensions(attempt.source.mediaRef(), w, h);
                    }
                });
                player.prepareAsync();

                // watchdog: prepared but never started (bad URL / network black hole).
                // v6.2 fix: a SLOW network used to trigger an endless reset loop because
                // the watchdog fired while prepareAsync was still in flight. Now:
                //  - not prepared yet  → retry (connection dead/stuck)
                //  - prepared, not started → 5s extra grace, then retry
                long timeoutMs = streamTimeoutMs();
                Runnable watch = () -> {
                    if (attempt.cancelled || ATTEMPTS.get(id) != attempt) return;
                    if (attempt.started) return;
                    if (attempt.prepared) {
                        XposedBridge.log(TAG + "[" + attempt.tag + "]watchdog: prepared but not started, +5s grace");
                        handler().postDelayed(() -> {
                            if (attempt.cancelled || ATTEMPTS.get(id) != attempt) return;
                            if (!attempt.started) {
                                scheduleRetry(player, attempt, attemptNo, "prepared-not-started");
                            }
                        }, 5000);
                        return;
                    }
                    XposedBridge.log(TAG + "[" + attempt.tag + "]watchdog: no playback after "
                            + timeoutMs + "ms (prepared=" + attempt.prepared + ")");
                    scheduleRetry(player, attempt, attemptNo, "timeout");
                };
                attempt.watchRunnable = watch;
                handler().postDelayed(watch, timeoutMs + 2000);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[" + attempt.tag + "]prepareAsync failed: " + t);
                scheduleRetry(player, attempt, attemptNo, "exception");
            }
        });
    }

    /** Arms a re-check that turns long stalls (buffering that never ends) into retries. */
    private static void armStallWatch(final MediaPlayer player, final Attempt attempt, final int attemptNo) {
        unscheduleWatch(attempt);
        long timeoutMs = streamTimeoutMs();
        final int id = System.identityHashCode(player);
        Runnable watch = () -> {
            if (attempt.cancelled || ATTEMPTS.get(id) != attempt) return;
            try {
                if (!player.isPlaying()) {
                    XposedBridge.log(TAG + "[" + attempt.tag + "]stall watchdog fired");
                    scheduleRetry(player, attempt, attemptNo, "stall");
                }
            } catch (Throwable t) {
                scheduleRetry(player, attempt, attemptNo, "stall-err");
            }
        };
        attempt.watchRunnable = watch;
        handler().postDelayed(watch, timeoutMs);
    }

    private static void unscheduleWatch(Attempt attempt) {
        Runnable r = attempt.watchRunnable;
        if (r != null) {
            handler().removeCallbacks(r);
            attempt.watchRunnable = null;
        }
    }

    private static void unschedule(Attempt attempt) {
        unscheduleWatch(attempt);
    }

    private static void scheduleRetry(final MediaPlayer player, final Attempt attempt,
                                      final int attemptNo, final String reason) {
        if (attempt.cancelled) return;
        final int id = System.identityHashCode(player);
        if (ATTEMPTS.get(id) != attempt) return;

        int next = attemptNo + 1;
        boolean reconnectAllowed = streamAutoReconnect();

        if (next >= MAX_ATTEMPTS || !reconnectAllowed) {
            XposedBridge.log(TAG + "[" + attempt.tag + "]giving up after " + attemptNo + " attempt(s) (" + reason + ")");
            ATTEMPTS.remove(id);
            boolean fellBack = fallbackToLocal(attempt);
            if (!fellBack && reconnectAllowed) {
                // v6.1: no local fallback available → keep the live link alive with
                // slow reconnects every 30s so a temporary network loss never leaves
                // a permanently black camera.
                XposedBridge.log(TAG + "[" + attempt.tag + "]scheduling slow reconnect in 30s");
                handler().postDelayed(() -> {
                    if (attempt.cancelled || ATTEMPTS.containsKey(id)) return;
                    ATTEMPTS.put(id, attempt);
                    prepareStream(player, attempt, 0);
                }, 30000);
            }
            return;
        }

        long delay = BACKOFF_MS[Math.min(next - 1, BACKOFF_MS.length - 1)];
        XposedBridge.log(TAG + "[" + attempt.tag + "]retry " + next + "/" + (MAX_ATTEMPTS - 1)
                + " in " + delay + "ms (" + reason + ")");
        handler().postDelayed(() -> {
            if (attempt.cancelled || ATTEMPTS.get(id) != attempt) return;
            prepareStream(player, attempt, next);
        }, delay);
    }

    /**
     * Last resort when a live link cannot play: switch the surface path to the local
     * video (if the user enabled local fallback). This guarantees the app never shows
     * a permanently black camera.
     */
    private static boolean fallbackToLocal(final Attempt attempt) {
        return fallbackToLocal(attempt.source, attempt.playSound, attempt.tag);
    }

    /**
     * v6.2 — shared fallback used by both MediaPlayer attempts and the page
     * display environment: rebuild the pipeline on the local video file.
     */
    private static boolean fallbackToLocal(VideoSource source, boolean playSound, String tag) {
        try {
            ConfigManager config = HookGuards.getConfig();
            if (!config.getBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, true)) {
                XposedBridge.log(TAG + "[" + tag + "]local fallback disabled by user");
                return false;
            }
            File local = source.file;
            if (local == null || !local.exists()) {
                XposedBridge.log(TAG + "[" + tag + "]no local fallback video available");
                return false;
            }
            XposedBridge.log(TAG + "[" + tag + "]fallback → local video " + local.getName());
            // Route through the normal hot-swap so the whole pipeline rebuilds on the
            // local source and all relays keep working.
            VideoSource localSrc = VideoSource.local(local);
            if (tag != null && tag.startsWith("c1")) {
                Camera1Handler.swapToLocal(localSrc, playSound);
            } else {
                Camera2SessionHook.swapToLocal(localSrc, playSound);
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + tag + "]fallback failed: " + t);
            return false;
        }
    }

    private static AudioAttributes audioAttrs() {
        AudioAttributes.Builder b = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);
        return b.build();
    }

    // ======================== Config helpers ========================

    private static boolean streamAutoReconnect() {
        try {
            return HookGuards.getConfig().getBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static long streamTimeoutMs() {
        try {
            long v = HookGuards.getConfig().getLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, 8000L);
            if (v < 3000) v = 3000;
            if (v > 60000) v = 60000;
            return v;
        } catch (Throwable t) {
            return 8000L;
        }
    }
}
