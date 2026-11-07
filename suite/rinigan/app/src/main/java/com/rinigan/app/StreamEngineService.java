package com.rinigan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.rinigan.app.ui.MainActivity;

/**
 * v6.3 — STREAM ENGINE SERVICE (the link display environment host).
 *
 * Runs in the Rinigan APP process (NOT the target app). Renders the user's
 * link via {@link LinkRenderer} and serves the resulting NV21 frames to every
 * hooked target app over an ABSTRACT NAMESPACE unix socket
 * ("rinigan.relay"). Abstract sockets need NO permissions — this is what
 * makes the whole design work even when the target app has no INTERNET
 * permission (the classic LSPatch black-screen cause).
 *
 * Wire protocol (all ints little-endian via DataOutputStream.writeInt):
 *   handshake : MAGIC (0x52474E33)  VERSION (3)
 *   frame     : MAGIC  W  H  SEQ  LEN  payload[NV21]
 *
 * Never-black guarantee: the renderer emits branded placeholder frames while
 * loading / retrying / on error, so clients always receive pixels.
 *
 * Status is published through {@link #status()} for the in-app Diagnostics
 * card (start/stop, live preview, frame counters, hook events).
 */
public class StreamEngineService extends Service {

    /** Must match IpcContract.RELAY_SOCKET_NAME (duplicated to avoid loading hook classes). */
    public static final String SOCKET_NAME = "rinigan.relay";
    public static final int RELAY_MAGIC = 0x52474E33; // "RNG3"
    public static final int RELAY_VERSION = 3;

    private static final String CHANNEL_ID = "rinigan_engine_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final long FRAME_INTERVAL_MS = 66; // ~15 fps
    private static final long CONFIG_POLL_MS = 3000;
    private static final int PREVIEW_W = 216;

    // ======================== Status singleton (UI + provider) ========================

    public static final class Status {
        public volatile boolean running = false;
        public volatile String url = "";
        public volatile String mode = "auto";
        public volatile String state = "idle";
        public volatile String detail = "";
        public volatile String lastError = "";
        public volatile int framesServed = 0;
        public volatile int clients = 0;
        public volatile int frameW = 0;
        public volatile int frameH = 0;
        public volatile long startedAt = 0;
        public volatile long lastFrameAt = 0;
        public volatile Bitmap preview = null;
        /** Latest hook-side events reported through the provider (newest first). */
        public final CopyOnWriteArrayList<String> hookEvents = new CopyOnWriteArrayList<>();
    }

    private static final Status STATUS = new Status();

    public static Status status() {
        return STATUS;
    }

    /** Adds a hook-side event (called by VideoProvider from binder threads). */
    public static void addHookEvent(String pkg, String type, String detail) {
        String line = time() + "  " + pkg + "  " + type
                + (detail != null && !detail.isEmpty() ? "  " + detail : "");
        STATUS.hookEvents.add(0, line);
        while (STATUS.hookEvents.size() > 20) {
            STATUS.hookEvents.remove(STATUS.hookEvents.size() - 1);
        }
        LogUtil.log("[HookEvent]" + line);
    }

    private static String time() {
        return android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                .toString();
    }

    // ======================== Start / stop helpers ========================

    /** Starts the engine service if not already running (any caller, incl. provider). */
    public static void ensureRunning(Context context) {
        if (STATUS.running) return;
        try {
            Intent i = new Intent(context, StreamEngineService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            LogUtil.log("[Engine]ensureRunning → startForegroundService");
        } catch (Throwable t) {
            LogUtil.log("[Engine]ensureRunning failed: " + t);
        }
    }

    /** Stops the engine (UI button). */
    public static void stopEngine(Context context) {
        try {
            context.stopService(new Intent(context, StreamEngineService.class));
        } catch (Throwable ignored) {
        }
    }

    // ======================== Service plumbing ========================

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinkRenderer renderer;
    private FrameServer frameServer;
    private ConfigManager config;

    private volatile String activeUrl = "";
    private volatile String activeMode = "auto";
    private volatile Frame currentFrame = null;
    private long lastSeq = 0;
    private long lastPreviewAt = 0;
    private Runnable configPoller;

    private static final class Frame {
        final byte[] nv21;
        final int w;
        final int h;
        final long seq;

        Frame(byte[] nv21, int w, int h, long seq) {
            this.nv21 = nv21;
            this.w = w;
            this.h = h;
            this.seq = seq;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        STATUS.running = true;
        STATUS.startedAt = System.currentTimeMillis();
        LogUtil.log("[Engine]service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        main.post(this::startEngine);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopEngineInternals();
        STATUS.running = false;
        STATUS.state = "stopped";
        LogUtil.log("[Engine]service destroyed");
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "Link Display Environment", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Rinigan — Link Environment")
                .setContentText("Rendering your link for the virtual camera")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    // ======================== Engine lifecycle ========================

    private void startEngine() {
        if (frameServer != null) return; // already running
        try {
            config = new ConfigManager();
            config.setContext(this);
            config.setSkipProviderReload(true);
            config.reload();

            activeUrl = safeTrim(config.getString(ConfigManager.KEY_STREAM_URL, ""));
            activeMode = safeTrim(config.getString(ConfigManager.KEY_STREAM_DISPLAY_MODE, "auto"));
            if (activeMode.isEmpty()) activeMode = "auto";
            STATUS.url = activeUrl;
            STATUS.mode = activeMode;

            boolean hasLink = !activeUrl.isEmpty()
                    && ConfigManager.MEDIA_SOURCE_STREAM.equals(
                    config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, "local"));

            // frame server starts regardless — placeholders explain what to do
            frameServer = new FrameServer();
            frameServer.start();

            renderer = new LinkRenderer(this, new LinkRenderer.Callback() {
                @Override
                public void onFrame(byte[] nv21, int w, int h) {
                    publishFrame(nv21, w, h);
                }

                @Override
                public void onState(String state, String detail) {
                    STATUS.state = state;
                    STATUS.detail = detail == null ? "" : detail;
                    if ("error".equals(state) && detail != null) STATUS.lastError = detail;
                    LogUtil.log("[Engine]state=" + state + (detail != null ? " (" + detail + ")" : ""));
                }
            });

            if (hasLink) {
                LogUtil.log("[Engine]starting renderer: " + activeUrl + " mode=" + activeMode);
                renderer.start(activeUrl, activeMode);
            } else {
                LogUtil.log("[Engine]no stream link selected — serving placeholder");
                STATUS.state = "no_link";
                STATUS.detail = "Add a link in Manage → Add Link";
                renderer.emitPlaceholder("no_link", "Add a link in Manage → Add Link");
            }

            // config poller — picks up link/mode changes live
            configPoller = new Runnable() {
                @Override
                public void run() {
                    if (frameServer == null) return;
                    try {
                        config.reload();
                        String u = safeTrim(config.getString(ConfigManager.KEY_STREAM_URL, ""));
                        String m = safeTrim(config.getString(ConfigManager.KEY_STREAM_DISPLAY_MODE, "auto"));
                        if (m.isEmpty()) m = "auto";
                        boolean streamMode = ConfigManager.MEDIA_SOURCE_STREAM.equals(
                                config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, "local"));
                        String effective = streamMode ? u : "";
                        if (!effective.equals(activeUrl) || !m.equals(activeMode)) {
                            LogUtil.log("[Engine]config changed → restart renderer");
                            activeUrl = effective;
                            activeMode = m;
                            STATUS.url = effective;
                            STATUS.mode = m;
                            if (renderer != null) renderer.stopRenderersForRestart();
                            if (!effective.isEmpty()) {
                                renderer.start(effective, m);
                            } else {
                                STATUS.state = "no_link";
                                STATUS.detail = "Add a link in Manage → Add Link";
                                renderer.emitPlaceholder("no_link", "Add a link in Manage → Add Link");
                            }
                        }
                    } catch (Throwable t) {
                        LogUtil.log("[Engine]config poll error (non-fatal): " + t);
                    }
                    main.postDelayed(this, CONFIG_POLL_MS);
                }
            };
            main.postDelayed(configPoller, CONFIG_POLL_MS);
        } catch (Throwable t) {
            LogUtil.log("[Engine]startEngine failed: " + t);
            STATUS.lastError = String.valueOf(t);
        }
    }

    private void stopEngineInternals() {
        try {
            if (configPoller != null) main.removeCallbacks(configPoller);
        } catch (Throwable ignored) {
        }
        configPoller = null;
        try {
            if (renderer != null) renderer.stop();
        } catch (Throwable ignored) {
        }
        renderer = null;
        try {
            if (frameServer != null) frameServer.stop();
        } catch (Throwable ignored) {
        }
        frameServer = null;
        currentFrame = null;
    }

    private void publishFrame(byte[] nv21, int w, int h) {
        if (nv21 == null || w <= 0 || h <= 0) return;
        currentFrame = new Frame(nv21, w, h, ++lastSeq);
        STATUS.framesServed++;
        STATUS.frameW = w;
        STATUS.frameH = h;
        STATUS.lastFrameAt = System.currentTimeMillis();
        // cheap UI preview every ~700ms
        long now = STATUS.lastFrameAt;
        if (now - lastPreviewAt > 700) {
            lastPreviewAt = now;
            updatePreview(nv21, w, h);
        }
    }

    private void updatePreview(byte[] nv21, int w, int h) {
        try {
            Bitmap src = Nv21Converter.nv21ToBitmap(nv21, w, h);
            if (src == null) return;
            int pw = PREVIEW_W;
            int ph = Math.max(1, Math.round(PREVIEW_W * (float) h / w));
            Bitmap scaled = Bitmap.createScaledBitmap(src, pw, ph, true);
            Bitmap old = STATUS.preview;
            STATUS.preview = scaled;
            if (old != null && !old.isRecycled() && old != scaled) old.recycle();
            if (src != scaled) src.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    // ======================== Frame server ========================

    /**
     * Accepts connections from hooked target apps and pushes the latest frame
     * at ~15 fps. Abstract namespace → NO permission needed on either side.
     */
    private final class FrameServer {
        private LocalServerSocket server;
        private Thread acceptThread;
        private final List<Thread> clientThreads = new CopyOnWriteArrayList<>();
        private volatile boolean closing = false;

        void start() {
            try {
                server = new LocalServerSocket(SOCKET_NAME);
            } catch (IOException e) {
                LogUtil.log("[Engine]socket bind failed: " + e);
                STATUS.lastError = "socket bind failed: " + e.getMessage();
                return;
            }
            acceptThread = new Thread(() -> {
                while (!closing && server != null) {
                    try {
                        LocalSocket client = server.accept();
                        serve(client);
                    } catch (Throwable t) {
                        if (!closing) LogUtil.log("[Engine]accept error: " + t);
                        break;
                    }
                }
            }, "Rinigan-EngineAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            LogUtil.log("[Engine]frame server listening on @" + SOCKET_NAME);
        }

        private void serve(final LocalSocket client) {
            Thread ct = new Thread(() -> {
                STATUS.clients++;
                LogUtil.log("[Engine]client connected (clients=" + STATUS.clients + ")");
                OutputStream raw = null;
                DataOutputStream out = null;
                try {
                    client.setSendBufferSize(2 * 1024 * 1024);
                    raw = client.getOutputStream();
                    out = new DataOutputStream(new java.io.BufferedOutputStream(raw, 1 << 16));
                    out.writeInt(RELAY_MAGIC);
                    out.writeInt(RELAY_VERSION);
                    out.flush();
                    long lastSentSeq = -1;
                    long lastSendAt = 0;
                    while (!closing) {
                        Frame f = currentFrame;
                        if (f != null) {
                            // send new frames immediately; repeat the last frame at ~15fps
                            if (f.seq != lastSentSeq) {
                                writeFrame(out, f);
                                lastSentSeq = f.seq;
                                lastSendAt = System.currentTimeMillis();
                            } else {
                                long now = System.currentTimeMillis();
                                if (now - lastSendAt >= FRAME_INTERVAL_MS) {
                                    writeFrame(out, f);
                                    lastSendAt = now;
                                }
                            }
                        }
                        Thread.sleep(12);
                    }
                } catch (InterruptedException | SocketException ignored) {
                } catch (Throwable t) {
                    if (!closing) LogUtil.log("[Engine]client error: " + t);
                } finally {
                    STATUS.clients = Math.max(0, STATUS.clients - 1);
                    try {
                        if (out != null) out.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        client.close();
                    } catch (Throwable ignored) {
                    }
                    clientThreads.remove(Thread.currentThread());
                    LogUtil.log("[Engine]client disconnected (clients=" + STATUS.clients + ")");
                }
            }, "Rinigan-EngineClient");
            ct.setDaemon(true);
            clientThreads.add(ct);
            ct.start();
        }

        private void writeFrame(DataOutputStream out, Frame f) throws IOException {
            out.writeInt(RELAY_MAGIC);
            out.writeInt(f.w);
            out.writeInt(f.h);
            out.writeInt((int) f.seq);
            out.writeInt(f.nv21.length);
            out.write(f.nv21);
            out.flush();
        }

        void stop() {
            closing = true;
            try {
                if (server != null) server.close();
            } catch (Throwable ignored) {
            }
            try {
                if (acceptThread != null) acceptThread.interrupt();
            } catch (Throwable ignored) {
            }
            LogUtil.log("[Engine]frame server stopped");
        }
    }
}
