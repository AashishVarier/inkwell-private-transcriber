package com.variableduck.inkwell.transcriber.data

import kotlinx.serialization.Serializable

@Serializable
enum class NoteStatus {
    /** Audio is being recorded right now. */
    RECORDING,

    /** Audio saved, not (fully) transcribed. `doneSamples > 0` means a partial transcript exists. */
    RECORDED,

    /** Waiting for the transcription service. */
    QUEUED,

    /** Being transcribed right now. */
    TRANSCRIBING,

    /** Transcript complete (or text-only after the recording was deleted). */
    DONE,

    /** Transcription failed; see [Note.error]. */
    FAILED,
}

/** Note metadata, stored as `<id>.json` next to `<id>.wav` and `<id>.txt`. */
@Serializable
data class Note(
    /** Shared base file name, e.g. `11-11-2026-14-30-05` (or `…-2` on a clash). */
    val id: String,
    val title: String,
    /** Recording start, epoch millis. */
    val createdAt: Long,
    val durationMs: Long = 0,
    val status: NoteStatus = NoteStatus.RECORDED,
    /** Display name of the model that produced the transcript. */
    val modelName: String? = null,
    val language: String? = null,
    /** Transcription progress 0..1. */
    val progress: Float = 0f,
    /** Checkpoint: audio samples already transcribed into the text file. */
    val doneSamples: Long = 0,
    /** Checkpoint: length of the text file in bytes after the last finished chunk. */
    val textBytes: Long = 0,
    /** True if the app died while recording and the audio was recovered at next launch. */
    val recovered: Boolean = false,
    val error: String? = null,
    /** Set when the transcript finished but parts were left out (e.g. speech in another language). */
    val warning: String? = null,
    val queuedAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isBusy: Boolean
        get() = status == NoteStatus.RECORDING || status == NoteStatus.QUEUED ||
            status == NoteStatus.TRANSCRIBING

    val hasPartialTranscript: Boolean
        get() = doneSamples > 0 && status != NoteStatus.DONE
}

/** A note plus what is on disk for it. */
data class NoteEntry(
    val note: Note,
    val hasAudio: Boolean,
    val audioBytes: Long,
    val hasText: Boolean,
    val textBytes: Long,
) {
    val id: String get() = note.id
}
