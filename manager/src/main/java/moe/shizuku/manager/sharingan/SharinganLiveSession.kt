package moe.shizuku.manager.sharingan

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * aMiNo 2.0 — Sharingan LIVE SESSION (r1435): continuous screen sharing,
 * the "Gemini Live for your screen" experience with the Sharingan eye.
 *
 * While active, the session watches the screen at a fixed cadence through the
 * proven shell path (`screencap` + md5 change detection — the same truth model
 * as SharinganRecorder) and:
 *  - keeps a ring of REAL visual observations (timestamp + activity + short
 *    OCR excerpt) that is injected into EVERY agent round via
 *    [contextBlock] — the model literally cannot forget what it watched;
 *  - keeps the newest changed frame in SharinganVisionHub.latestLive so any
 *    round during the session can see the CURRENT screen as an actual image;
 *  - surfaces state honestly (frames/changes counters in SharinganState).
 *
 * Battery rule (PLAN_aMiNo2.md §8): a hard session cap — after MAX_SESSION_MS
 * the session stops itself and says so (never a silent death).
 * The OCR text stays a fallback; the image is the primary perception.
 */
object SharinganLiveSession {

    private const val TAG = "SharinganLive"
    private const val INTERVAL_MS = 2_500L          // one watch tick
    private const val MAX_SESSION_MS = 10 * 60_000L // 10-minute hard cap
    private const val MAX_OBSERVATIONS = 12         // ring size
    private const val OCR_EXCERPT = 260             // chars per observation

    data class Observation(
        val tMs: Long,          // ms since session start
        val activity: String?,  // foreground activity line (real dumpsys)
        val text: String?       // real uiautomator text excerpt
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var startedAt: Long = 0

    /** Newest-first ring of real observations (thread-safe via synchronized). */
    private val observations = ArrayDeque<Observation>()
    private var framesCaptured: Int = 0
    private var framesChanged: Int = 0

    val active: Boolean get() = job?.isActive == true

    fun start(context: Context): Boolean {
        if (active) return true
        if (!moe.shizuku.manager.terminal.linux.ShizukuExec.available()) {
            SharinganState.update { it.copy(lastError = "shizuku_service_unavailable") }
            return false
        }
        // fresh session memory
        synchronized(observations) { observations.clear() }
        framesCaptured = 0
        framesChanged = 0
        startedAt = System.currentTimeMillis()
        SharinganVisionHub.clear()
        SharinganState.update {
            it.copy(lastError = null, liveActive = true, liveFrames = 0, liveObserved = 0)
        }
        job = scope.launch {
            var lastHash: String? = null
            try {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed >= MAX_SESSION_MS) break   // battery hard cap
                    val frame = ScreenCapture.capture()
                    if (frame == null) {
                        SharinganState.update { it.copy(lastError = "live_capture_failed") }
                    } else {
                        framesCaptured++
                        SharinganState.update { it.copy(liveFrames = framesCaptured) }
                        if (frame.hash != lastHash) {
                            lastHash = frame.hash
                            framesChanged++
                            val text = ScreenCapture.screenText(maxChars = OCR_EXCERPT + 400)
                            val obs = Observation(
                                tMs = elapsed,
                                activity = frame.activity,
                                text = text?.take(OCR_EXCERPT)
                            )
                            synchronized(observations) {
                                observations.addFirst(obs)
                                while (observations.size > MAX_OBSERVATIONS) observations.removeLast()
                            }
                            SharinganState.update { it.copy(liveObserved = framesChanged) }
                            // stage the image for the model's next round (non-consuming slot)
                            val b64 = readImageForLive(frame.pngPath)
                            if (b64 != null) {
                                SharinganVisionHub.latestLive = SharinganVisionHub.StagedImage(
                                    base64 = b64,
                                    activity = frame.activity,
                                    frameHash = frame.hash,
                                    capturedAtMs = System.currentTimeMillis(),
                                    source = "live_session"
                                )
                            }
                        }
                    }
                    delay(INTERVAL_MS)
                }
                // natural cap expiry → stop honestly
                if (active) stop(context, autoTimedOut = true)
            } catch (_: Throwable) {
                SharinganState.update { it.copy(lastError = "live_session_crashed") }
                stop(context)
            }
        }
        Log.i(TAG, "live session started")
        return true
    }

    fun stop(context: Context, autoTimedOut: Boolean = false) {
        val wasActive = active
        job?.cancel()
        job = null
        SharinganVisionHub.clear()
        SharinganState.update {
            it.copy(
                liveActive = false,
                lastError = if (autoTimedOut) "live_session_auto_stopped" else null
            )
        }
        if (wasActive) Log.i(TAG, "live session stopped (autoTimedOut=$autoTimedOut, frames=$framesCaptured, changed=$framesChanged)")
    }

    /** Number of real observations currently in the ring. */
    fun observationCount(): Int = synchronized(observations) { observations.size }

    /**
     * [SHARINGAN LIVE — SESSION] block for the system prompt — built ONLY from
     * real captured observations (TRACE TRUTH). Injected every round while the
     * session runs; the newest frame is also available as an actual image via
     * SharinganVisionHub.consumeForNextRound().
     */
    fun contextBlock(): String {
        if (!active) return ""
        val list = synchronized(observations) { observations.toList() }
        val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
        val sb = StringBuilder()
        sb.appendLine("[SHARINGAN LIVE — ACTIVE SESSION] the user is sharing their screen LIVE " +
            "(${elapsedSec}s, $framesCaptured frames captured, $framesChanged visual changes):")
        if (list.isEmpty()) {
            sb.appendLine("no visual changes captured yet — the screen has been static since the session started.")
        } else {
            list.forEach { o ->
                sb.appendLine("- t=+${"%.1f".format(o.tMs / 1000.0)}s activity=${o.activity ?: "?"}")
                if (!o.text.isNullOrBlank()) sb.appendLine("  screen_text: \"${o.text}\"")
            }
        }
        sb.appendLine("The NEWEST frame is attached as an actual IMAGE in this round — analyze it visually. " +
            "This context may be STALE by the time you act: verify with screen_read/screen_look before UI actions. " +
            "Never invent screen content not shown here.")
        return sb.toString().trim()
    }

    private suspend fun readImageForLive(path: String): String? =
        SharinganVisionHub.readPngBase64(path)
}
