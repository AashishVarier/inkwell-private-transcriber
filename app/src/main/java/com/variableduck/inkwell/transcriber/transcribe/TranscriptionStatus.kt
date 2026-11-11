package com.variableduck.inkwell.transcriber.transcribe

import com.variableduck.inkwell.transcriber.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TranscriptionStage {
    /** Loading the native library and the model file. */
    LOADING_MODEL,

    /** Whisper is working through the audio. */
    TRANSCRIBING,

    /** Checking the result and saving. */
    FINISHING,
}

data class TranscriptionState(
    val running: Boolean = false,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val stage: TranscriptionStage = TranscriptionStage.LOADING_MODEL,
    /** 0..1, moves smoothly (see [ProgressEstimator]). */
    val progress: Float = 0f,
    /** Estimated point reached in the recording. */
    val positionMs: Long = 0,
    val totalMs: Long = 0,
    /** Estimated time left, or null until it can be estimated. */
    val remainingMs: Long? = null,
) {
    /** True while there is no meaningful percentage to show. */
    val indeterminate: Boolean
        get() = noteId == null || stage != TranscriptionStage.TRANSCRIBING

    /** One line describing where the transcription is, e.g. "45% · 2:41 of 6:00 · about 3 min left". */
    val summary: String
        get() = when {
            noteId == null -> "Preparing…"
            stage == TranscriptionStage.LOADING_MODEL -> modelName?.let { "Loading $it…" } ?: "Loading the model…"
            stage == TranscriptionStage.FINISHING -> "Finishing…"
            else -> buildString {
                append(Format.percent(progress))
                if (totalMs > 0) append(" · ${Format.duration(positionMs)} of ${Format.duration(totalMs)}")
                remainingMs?.let { append(" · ${Format.remaining(it)}") }
            }
        }
}

/** Live transcription state, written by [TranscriptionService] and observed by the UI. */
object TranscriptionStatus {
    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    internal fun update(transform: (TranscriptionState) -> TranscriptionState) = _state.update(transform)
}
