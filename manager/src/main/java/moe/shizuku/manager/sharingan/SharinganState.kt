package moe.shizuku.manager.sharingan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * aMiNo 1.5.0-alpha — Sharingan Live (PLAN_aMiNo2.md §2).
 * Single source of truth for the floating panel: service state, honest frame
 * counters and the last real error (never invented).
 *
 * aMiNo 2.0 (r1435) — liveActive/liveFrames/liveObserved : the broadcast
 * aMiNo 2.0 (r1436) — livePhase/liveCalls/liveProactive/liveLatencyMs/micLevel
 *
 * aMiNo 2.0 (r1437) — LIVE FIRST rework:
 *  - liveSource : "projection" | "shell" | null — WHERE frames really come
 *    from (honest source display in the dock/panel)
 *  - liveStatic : true when the screen produced no updates for 8s+ (the old
 *    frozen "0 frames" counter read as a bug — now it is honest truth)
 *  - reset() now PRESERVES live/voice fields: the broadcast lives in
 *    LiveBroadcastService (FGS), so an accessibility-service rebind must
 *    never wipe the session truth (r1436's service rebind killed the session).
 */
data class SharinganUiState(
    val serviceUp: Boolean = false,
    val panelVisible: Boolean = false,
    val recording: Boolean = false,
    val framesCaptured: Int = 0,
    val framesChanged: Int = 0,
    val lastTraceId: String? = null,
    val lastError: String? = null,
    val liveActive: Boolean = false,
    val liveFrames: Int = 0,
    val liveObserved: Int = 0,
    val liveStatic: Boolean = false,
    val liveSource: String? = null,
    val livePhase: String = "watching",
    val liveCalls: Int = 0,
    val liveProactive: Int = 0,
    val liveLatencyMs: Long = 0,
    val micLevel: Float = 0f,
    val voiceActive: Boolean = false,
    val voiceListening: Boolean = false,
    val voiceSpeaking: Boolean = false
)

object SharinganState {
    private val _state = MutableStateFlow(SharinganUiState())
    val state: StateFlow<SharinganUiState> = _state

    /**
     * r1437-expert-fix: ATOMIC read-modify-write. The old
     * `_state.value = transform(_state.value)` raced between the engine IO
     * threads, TTS binder callbacks and the main thread — a lost update
     * could re-assert liveActive=true AFTER stop() set it false, leaving the
     * UI stuck on LIVE ON forever. MutableStateFlow.update is CAS-atomic.
     */
    fun update(transform: (SharinganUiState) -> SharinganUiState) {
        _state.update(transform)
    }

    /** The accessibility service reconnected/disconnected: only ITS fields
     *  reset. The broadcast session belongs to LiveBroadcastService (FGS) —
     *  its truth survives (r1437). */
    fun reset() {
        val s = _state.value
        _state.value = s.copy(
            serviceUp = false,
            panelVisible = false,
            recording = false,
            framesCaptured = 0,
            framesChanged = 0,
            lastTraceId = null
            // lastError preserved — it may be the live session's honest note
        )
    }
}
