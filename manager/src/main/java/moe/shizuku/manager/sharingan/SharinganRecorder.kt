package moe.shizuku.manager.sharingan

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The ⏺ record loop: 1 frame/second through the service shell, hash-based
 * change skipping, 5-minute hard cap (battery rule, PLAN_aMiNo2.md §8).
 *
 * Honest counters only: framesCaptured counts real screencap successes,
 * framesChanged counts frames whose on-device md5 differs from the previous.
 */
object SharinganRecorder {

    private const val INTERVAL_MS = 1_000L
    private const val MAX_RUN_MS = 5 * 60 * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastHash: String? = null

    val recording: Boolean get() = job?.isActive == true

    /** Start recording. Returns false (and sets state.lastError) when refused. */
    fun start(context: Context): Boolean {
        if (recording) return true
        if (!moe.shizuku.manager.terminal.linux.ShizukuExec.available()) {
            SharinganState.update { it.copy(lastError = "shizuku_service_unavailable") }
            return false
        }
        SharinganState.update { it.copy(lastError = null) }
        val traceId = TraceStore.start(context)
        lastHash = null
        SharinganState.update {
            it.copy(recording = true, framesCaptured = 0, framesChanged = 0, lastTraceId = traceId)
        }
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed >= MAX_RUN_MS) break   // battery hard cap
                    val frame = ScreenCapture.capture()
                    if (frame == null) {
                        SharinganState.update { it.copy(lastError = "capture_failed") }
                    } else {
                        SharinganState.update { it.copy(framesCaptured = it.framesCaptured + 1) }
                        if (frame.hash != lastHash) {
                            lastHash = frame.hash
                            val text = ScreenCapture.screenText()
                            TraceStore.append(
                                context, traceId,
                                tSec = elapsed / 1000.0,
                                frame = frame,
                                text = text
                            )
                            SharinganState.update { it.copy(framesChanged = it.framesChanged + 1) }
                            ScreenCapture.pruneOld()
                        }
                    }
                    delay(INTERVAL_MS)
                }
                // timed out naturally → close the trace honestly
                if (recording) stop(context, autoTimedOut = true)
            } catch (_: Throwable) {
                SharinganState.update { it.copy(lastError = "recorder_crashed") }
                stop(context, autoTimedOut = false)
            }
        }
        return true
    }

    fun stop(context: Context, autoTimedOut: Boolean = false) {
        val id = SharinganState.state.value.lastTraceId
        job?.cancel()
        job = null
        val closed = if (id != null && TraceStore.exists(context, id)) TraceStore.stop(context, id) else null
        SharinganState.update { it.copy(recording = false, lastTraceId = closed ?: it.lastTraceId) }
    }
}
