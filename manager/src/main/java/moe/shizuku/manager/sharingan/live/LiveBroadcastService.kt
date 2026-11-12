package moe.shizuku.manager.sharingan.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.sharingan.SharinganState
import moe.shizuku.manager.sharingan.SharinganVoice
import moe.shizuku.manager.ui.AgentHomeActivity
import moe.shizuku.manager.terminal.linux.ShizukuExec

/**
 * aMiNo 2.0 (r1437) — LIVE BROADCAST SERVICE: the foreground service that
 * OWNS the broadcast session.
 *
 * WHY THIS EXISTS (the r1436 field failure, user verbatim: «زر live لايعمل
 * و يختفي خلال ثواني»): the old broadcast was bare coroutines inside the
 * accessibility service process — no foreground service, so the system
 * killed/rebound it and everything (engine, overlay, voice) vanished with
 * it. Now the session lives in a real FGS:
 *
 *  - FOREGROUND SERVICE TYPE mediaProjection (+ microphone when granted):
 *    the persistent notification IS the broadcast (Gemini's screen-share
 *    notification) — the process cannot be silently reaped while it runs;
 *  - the broadcast NO LONGER requires the wireless-ADB shell: frames come
 *    from MediaProjection (primary) or the shell (fallback) — see
 *    LiveScreenSource;
 *  - END action in the notification + the dock ⏹ both route HERE;
 *  - the accessibility service rebind no longer kills the session (the
 *    dock self-heals from this state instead).
 *
 * FLOW (Android 13/14-correct): LiveConsentActivity (system consent dialog)
 * → startForegroundService(extras) → startForeground(type) FIRST →
 * getMediaProjection(resultCode, data) → VirtualDisplay. The shell fallback
 * starts with type specialUse (declared property) when projection is denied.
 */
class LiveBroadcastService : Service() {

    companion object {
        private const val TAG = "LiveBroadcast"
        private const val CHANNEL = "sharingan_live"
        private const val NOTIF_ID = 3100
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_MIC = "mic"
        const val EXTRA_SHELL_FALLBACK = "shell_fallback"

        @Volatile var running: Boolean = false
            private set

        fun micGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** Primary path — the user just approved the system projection dialog. */
        fun startProjection(context: Context, resultCode: Int, resultData: Intent, mic: Boolean) {
            val i = Intent(context, LiveBroadcastService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_MIC, mic)
            ContextCompat.startForegroundService(context, i)
        }

        /** Fallback path — projection denied but the shell is up: honest real frames. */
        fun startShellFallback(context: Context, mic: Boolean) {
            val i = Intent(context, LiveBroadcastService::class.java)
                .putExtra(EXTRA_SHELL_FALLBACK, true)
                .putExtra(EXTRA_MIC, mic)
            ContextCompat.startForegroundService(context, i)
        }

        /** One stop entry for the dock ⏹, the notification action and the panel. */
        fun stopBroadcast(context: Context) {
            context.stopService(Intent(context, LiveBroadcastService::class.java))
        }
    }

    private var source: LiveScreenSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shellFallback = intent?.getBooleanExtra(EXTRA_SHELL_FALLBACK, false) ?: false
        val mic = intent?.getBooleanExtra(EXTRA_MIC, micGranted(this)) ?: micGranted(this)

        if (running) {
            Log.w(TAG, "start while already running — stopping previous session first")
            teardown()
        }

        // ── 1. FOREGROUND FIRST (Android 13/14 rule) ────────────────────────
        val typeBits = when {
            shellFallback ->
                if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                         ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            mic -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                   ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForegroundCompat(buildNotification(shellFallback), typeBits)

        // ── 2. build the screen source ──────────────────────────────────────
        val src: LiveScreenSource = if (shellFallback) {
            SharinganState.update { it.copy(liveSource = "shell", lastError = null) }
            LiveScreenSource.ShellSource()
        } else {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val rc = intent!!.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (rc == Int.MIN_VALUE || data == null) throw IllegalStateException("no consent extras")
                val mp = mpm.getMediaProjection(rc, data)   // AFTER startForeground — the 14+ rule
                    ?: throw IllegalStateException("projection token rejected")
                SharinganState.update { it.copy(liveSource = "projection", lastError = null) }
                LiveScreenSource.ProjectionSource(applicationContext, mp)
            } catch (t: Throwable) {
                Log.w(TAG, "projection start failed: ${t.message}")
                if (ShizukuExec.available()) {
                    SharinganState.update { it.copy(liveSource = "shell", lastError = null) }
                    LiveScreenSource.ShellSource()
                } else {
                    SharinganState.update {
                        it.copy(liveSource = null, lastError = "live_no_source_projection_denied_shell_down")
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        source = src

        // ── 3. the engine + the voice loop (one tap = watching + hearing) ───
        val engineOk = SharinganLiveEngine.start(applicationContext, src)
        if (!engineOk) {
            stopSelf()
            return START_NOT_STICKY
        }
        // r1437-expert-fix: show the Gemini-style dock NOW — on a normal start
        // the a11y service never rebinds, so the self-heal path alone meant
        // the dock NEVER appeared (the opposite version of the r1436
        // "it disappeared" complaint).
        moe.shizuku.manager.sharingan.SharinganAccessibilityService.instance?.let {
            runCatching { moe.shizuku.manager.sharingan.live.LiveOverlay.show(it) }
        }
        if (mic && android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            runCatching { SharinganVoice.start(this) }
                .onFailure { Log.w(TAG, "voice start failed: ${it.message}") }
        } else {
            SharinganState.update { it.copy(lastError = null) }
            Log.i(TAG, "live starts vision-only (mic=$mic available=${android.speech.SpeechRecognizer.isRecognitionAvailable(this)})")
        }
        running = true
        Log.i(TAG, "broadcast FGS up (source=${src.kind} mic=$mic)")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!running && source == null) return
        running = false
        runCatching { SharinganVoice.stop(this) }
        runCatching { SharinganLiveEngine.stop(this) }
        runCatching { source?.close() }
        source = null
        runCatching { moe.shizuku.manager.sharingan.live.LiveOverlay.hide() }
        SharinganState.update { it.copy(liveActive = false, liveSource = null, livePhase = SharinganLiveEngine.PHASE_WATCHING) }
        Log.i(TAG, "broadcast FGS torn down")
    }

    // ── notification (the broadcast's persistent identity) ─────────────────

    private fun startForegroundCompat(notif: Notification, typeBits: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, typeBits)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(shellFallback: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL,
                    getString(R.string.sharingan_notif_live_channel),
                    NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }

        val stopPi = PendingIntent.getBroadcast(
            this, 3101,
            Intent(this, LiveStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openPi = PendingIntent.getActivity(
            this, 3102,
            Intent(this, AgentHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(0xFFE53935.toInt())
            .setContentTitle(getString(R.string.sharingan_notif_live_title))
            .setContentText(getString(
                if (shellFallback) R.string.sharingan_notif_live_shell else R.string.sharingan_notif_live_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.sharingan_notif_live_stop), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
