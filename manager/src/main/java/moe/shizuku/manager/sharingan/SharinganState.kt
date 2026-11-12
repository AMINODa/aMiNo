package moe.shizuku.manager.sharingan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * aMiNo 1.5.0-alpha — Sharingan Live (PLAN_aMiNo2.md §2).
 * Single source of truth for the floating panel: service state, recording
 * state, honest frame counters and the last real error (never invented).
 *
 * aMiNo 2.0 (r1435) — Sharingan Live Vision & Voice additions:
 *  - liveActive/liveFrames/liveObserved : the continuous screen-sharing session
 *  - voiceActive/voiceListening/voiceSpeaking : the live voice loop
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
    val voiceActive: Boolean = false,
    val voiceListening: Boolean = false,
    val voiceSpeaking: Boolean = false
)

object SharinganState {
    private val _state = MutableStateFlow(SharinganUiState())
    val state: StateFlow<SharinganUiState> = _state

    fun update(transform: (SharinganUiState) -> SharinganUiState) {
        _state.value = transform(_state.value)
    }

    /** New run: keep only whether the accessibility service itself is connected.
     *  Live/voice sessions are lifecycle-bound to the service — they reset too
     *  (their own objects stop on onUnbind). */
    fun reset() {
        val up = _state.value.serviceUp
        _state.value = SharinganUiState(serviceUp = up)
    }
}
