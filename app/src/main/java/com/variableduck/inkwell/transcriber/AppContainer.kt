package com.variableduck.inkwell.transcriber

import android.content.Context
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.data.Settings
import com.variableduck.inkwell.transcriber.models.ModelRepository
import com.variableduck.inkwell.transcriber.record.RecordingStatus
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionService
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionStatus
import com.variableduck.inkwell.transcriber.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** App-wide singletons. Created once in [InkwellApp]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(appContext)
    val notes = NoteRepository(File(appContext.filesDir, "notes"))
    val models = ModelRepository(appContext, scope, settings)
    val engine = WhisperEngine(appContext)

    init {
        // Repairs recordings/transcriptions interrupted by a crash before anything else runs.
        scope.launch(Dispatchers.IO) { notes.loadAndRecover() }
    }

    /** Why deleting is blocked right now, or null when it's allowed. */
    fun deleteBlockedReason(): String? = when {
        RecordingStatus.state.value.isActive -> "Stop the recording before deleting anything."
        TranscriptionStatus.state.value.running || notes.hasQueued() ->
            "Wait for transcription to finish, or cancel it, before deleting anything."
        else -> null
    }

    /**
     * Queues a note for transcription and starts the service.
     * [restart] discards any existing transcript and starts from the beginning.
     * Returns an error message, or null if queued.
     */
    suspend fun requestTranscription(id: String, restart: Boolean = false): String? {
        if (models.activeModel() == null) return "Download or import a model first (Models screen)."
        val entry = notes.entry(id) ?: return "Note not found"
        if (!entry.hasAudio) return "This note has no recording to transcribe."
        if (entry.note.isBusy) return null
        notes.update(id) {
            it.copy(
                status = NoteStatus.QUEUED,
                queuedAt = System.currentTimeMillis(),
                error = null,
                warning = null,
                doneSamples = if (restart) 0 else it.doneSamples,
                textBytes = if (restart) 0 else it.textBytes,
                progress = if (restart) 0f else it.progress,
            )
        }
        TranscriptionService.start(appContext)
        return null
    }

    /** Resumes queued or interrupted transcriptions (called when the app comes to the foreground). */
    fun resumePendingTranscriptions() {
        scope.launch {
            notes.awaitReady()
            if (notes.nextQueued() != null && !TranscriptionStatus.state.value.running &&
                models.activeModel() != null
            ) {
                TranscriptionService.start(appContext)
            }
        }
    }
}
