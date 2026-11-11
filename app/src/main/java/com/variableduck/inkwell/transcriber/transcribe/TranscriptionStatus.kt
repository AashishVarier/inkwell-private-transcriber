package com.variableduck.inkwell.transcriber.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TranscriptionState(
    val running: Boolean = false,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val modelId: String? = null,
    val progress: Float = 0f,
)

/** Live transcription state, written by [TranscriptionService] and observed by the UI. */
object TranscriptionStatus {
    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    internal fun update(transform: (TranscriptionState) -> TranscriptionState) = _state.update(transform)
}
