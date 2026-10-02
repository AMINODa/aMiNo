package moe.shizuku.manager.sharingan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * aMiNo 1.5.0-alpha — Sharingan Live (PLAN_aMiNo2.md §2).
 * Single source of truth for the floating panel: service state, recording
 * state, honest frame counters and the last real error (never invented).
 */
data class SharinganUiState(
    val serviceUp: Boolean = false,
    val panelVisible: Boolean = false,
    val recording: Boolean = false,
    val framesCaptured: Int = 0,
    val framesChanged: Int = 0,
    val lastTraceId: String? = null,
    val lastError: String? = null
)

object SharinganState {
    private val _state = MutableStateFlow(SharinganUiState())
    val state: StateFlow<SharinganUiState> = _state

    fun update(transform: (SharinganUiState) -> SharinganUiState) {
        _state.value = transform(_state.value)
    }

    /** New run: keep only whether the accessibility service itself is connected. */
    fun reset() {
        val up = _state.value.serviceUp
        _state.value = SharinganUiState(serviceUp = up)
    }
}
