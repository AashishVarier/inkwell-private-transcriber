package com.variableduck.inkwell.transcriber.record

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecorderPhase { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val noteId: String? = null,
    val elapsedMs: Long = 0,
    /** Input level 0..1 (peak, log scale). */
    val level: Float = 0f,
    val freeBytes: Long = Long.MAX_VALUE,
    val lowStorage: Boolean = false,
    val error: String? = null,
) {
    val isActive: Boolean get() = phase != RecorderPhase.IDLE
}

/** Live recorder state, written by [RecordingService] and observed by the UI. */
object RecordingStatus {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    internal fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)

    fun clearError() = _state.update { it.copy(error = null) }
}
