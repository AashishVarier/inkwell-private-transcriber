package com.variableduck.inkwell.transcriber.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.inkwell.transcriber.AppContainer
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.models.ModelItem
import com.variableduck.inkwell.transcriber.record.RecordingService
import com.variableduck.inkwell.transcriber.record.RecordingStatus
import com.variableduck.inkwell.transcriber.share.ExportFormat
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionService
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val c: AppContainer) : ViewModel() {

    val notes: StateFlow<List<NoteEntry>> = c.notes.notes
    val recorder = RecordingStatus.state
    val transcription = TranscriptionStatus.state

    val activeModel: StateFlow<ModelItem?> = combine(c.models.items, c.settings.state) { items, s ->
        items.firstOrNull { it.id == s.activeModelId && it.installed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, c.models.activeModel())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun message(text: String) {
        _messages.trySend(text)
    }

    fun freeBytes(): Long = c.notes.dir.usableSpace

    fun startRecording(context: Context) = RecordingService.start(context)
    fun pauseRecording(context: Context) = RecordingService.pause(context)
    fun resumeRecording(context: Context) = RecordingService.resume(context)
    fun stopRecording(context: Context) = RecordingService.stop(context)
    fun cancelTranscription(context: Context) = TranscriptionService.cancelCurrent(context)

    fun clearRecorderError() = RecordingStatus.clearError()

    fun transcribe(id: String) {
        viewModelScope.launch { c.requestTranscription(id)?.let { message(it) } }
    }

    /** Returns false (and explains why) if deleting is blocked right now. */
    fun canDelete(): Boolean {
        val reason = c.deleteBlockedReason() ?: return true
        message(reason)
        return false
    }

    fun delete(ids: Collection<String>, what: DeleteWhat) {
        if (!canDelete()) return
        viewModelScope.launch {
            try {
                c.notes.delete(ids, what)
                message(if (ids.size == 1) "Deleted" else "Deleted from ${ids.size} notes")
            } catch (e: Exception) {
                message(e.message ?: "Couldn't delete")
            }
        }
    }

    fun exportAll(context: Context, uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            try {
                val n = ShareExport.exportAll(context, uri, notes.value, c.notes, format)
                message("Exported $n transcript${if (n == 1) "" else "s"}")
            } catch (e: Exception) {
                message("Export failed: ${e.message}")
            }
        }
    }
}
