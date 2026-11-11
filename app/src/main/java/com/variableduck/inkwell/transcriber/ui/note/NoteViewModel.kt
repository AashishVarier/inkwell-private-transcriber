package com.variableduck.inkwell.transcriber.ui.note

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.inkwell.transcriber.AppContainer
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteNames
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.share.ExportFormat
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteViewModel(private val c: AppContainer, val id: String) : ViewModel() {

    val entry: StateFlow<NoteEntry?> = c.notes.notes
        .map { list -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.notes.entry(id))

    var editor by mutableStateOf(TextFieldValue(""))
        private set
    var dirty by mutableStateOf(false)
        private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        // Reload from disk whenever the transcript changes there (e.g. a chunk was appended),
        // unless the user has unsaved edits.
        viewModelScope.launch {
            entry.map { it?.textBytes to it?.note?.updatedAt }
                .distinctUntilChanged()
                .collect { if (!dirty) reloadText() }
        }
    }

    private suspend fun reloadText() {
        val text = withContext(Dispatchers.IO) { c.notes.readText(id) }
        if (text != editor.text) {
            editor = TextFieldValue(text, TextRange(text.length))
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }

    val currentText: String get() = editor.text

    fun onEdit(value: TextFieldValue) {
        if (entry.value?.note?.isBusy == true) return
        if (value.text != editor.text) dirty = true
        editor = value
    }

    /** Saves edits; runs in the app scope so it completes even when leaving the screen. */
    fun saveIfDirty(showMessage: Boolean = false) {
        if (!dirty) return
        val text = editor.text
        dirty = false
        c.scope.launch {
            try {
                c.notes.saveText(id, text)
                if (showMessage) message("Saved")
            } catch (e: Exception) {
                dirty = true
                message("Couldn't save: ${e.message}")
            }
        }
    }

    fun rename(title: String) {
        val note = entry.value?.note ?: return
        val clean = title.trim().ifEmpty { NoteNames.displayTimestamp(note.createdAt) }
        viewModelScope.launch { c.notes.update(id) { it.copy(title = clean) } }
    }

    fun transcribe(restart: Boolean) {
        saveIfDirty()
        viewModelScope.launch { c.requestTranscription(id, restart)?.let { message(it) } }
    }

    fun cancelTranscription(context: Context) {
        when (entry.value?.note?.status) {
            NoteStatus.TRANSCRIBING -> TranscriptionService.cancelCurrent(context)
            NoteStatus.QUEUED -> viewModelScope.launch {
                c.notes.update(id) { if (it.status == NoteStatus.QUEUED) it.copy(status = NoteStatus.RECORDED) else it }
            }
            else -> Unit
        }
    }

    fun canDelete(): Boolean {
        val reason = c.deleteBlockedReason() ?: return true
        message(reason)
        return false
    }

    fun delete(what: DeleteWhat) {
        if (!canDelete()) return
        dirty = false
        viewModelScope.launch {
            try {
                c.notes.delete(listOf(id), what)
                if (what == DeleteWhat.TEXT) {
                    editor = TextFieldValue("")
                }
                message(
                    when (what) {
                        DeleteWhat.AUDIO -> "Recording deleted"
                        DeleteWhat.TEXT -> "Transcript deleted"
                        DeleteWhat.BOTH -> "Note deleted"
                    },
                )
            } catch (e: Exception) {
                message(e.message ?: "Couldn't delete")
            }
        }
    }

    fun shareText(context: Context) {
        val note = entry.value?.note ?: return
        if (currentText.isBlank()) return message("There's no text to share yet.")
        ShareExport.shareText(context, note, currentText)
    }

    fun shareFile(context: Context) {
        val note = entry.value?.note ?: return
        if (currentText.isBlank()) return message("There's no text to share yet.")
        ShareExport.shareFile(context, note, currentText)
    }

    fun copy(context: Context) {
        val note = entry.value?.note ?: return
        if (currentText.isBlank()) return message("There's no text to copy yet.")
        ShareExport.copyToClipboard(context, note.title, currentText)
        // Android 13+ shows its own confirmation.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) message("Copied")
    }

    fun export(context: Context, uri: Uri, format: ExportFormat) {
        val note = entry.value?.note ?: return
        val content = ShareExport.render(note, currentText, format)
        viewModelScope.launch {
            try {
                ShareExport.exportTo(context, uri, content)
                message("Exported ${ShareExport.fileName(note, format)}")
            } catch (e: Exception) {
                message("Export failed: ${e.message}")
            }
        }
    }
}
