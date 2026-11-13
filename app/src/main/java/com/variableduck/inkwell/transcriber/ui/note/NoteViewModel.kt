package com.variableduck.inkwell.transcriber.ui.note

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.inkwell.transcriber.AppContainer
import com.variableduck.inkwell.transcriber.audio.AudioPlayer
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteNames
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.models.ModelItem
import com.variableduck.inkwell.transcriber.share.ExportFormat
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** The model a new transcription will use, or null if none is installed. */
    val activeModel: StateFlow<ModelItem?> = combine(c.models.items, c.settings.state) { items, s ->
        items.firstOrNull { it.id == s.activeModelId && it.installed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, c.models.activeModel())

    /** Plays this note's recording. */
    val player = AudioPlayer(c.appContext, viewModelScope)
    private val audio get() = c.notes.audioFile(id)

    fun togglePlayback() = player.toggle(audio)
    fun seekPlayback(positionMs: Long) = player.seekTo(audio, positionMs)
    fun skipPlayback(deltaMs: Long) = player.skip(audio, deltaMs)

    override fun onCleared() {
        player.release()
    }

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

    /**
     * Runs work that must finish even when the user leaves the note (which clears this
     * ViewModel), on the main thread like viewModelScope.
     */
    private fun launchInApp(block: suspend CoroutineScope.() -> Unit): Job =
        c.scope.launch(Dispatchers.Main.immediate, block = block)

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
        launchInApp { c.notes.update(id) { it.copy(title = clean) } }
    }

    fun transcribe(restart: Boolean) {
        val pending = if (dirty) editor.text else null
        dirty = false
        launchInApp {
            saveEditsThenQueue(
                pending = pending,
                save = { c.notes.saveText(id, it) },
                queue = { c.requestTranscription(id, restart) },
                onSaveFailed = { dirty = true },
            )?.let { message(it) }
        }
    }

    fun dismissWarning() {
        launchInApp { c.notes.update(id) { it.copy(warning = null) } }
    }

    fun cancelTranscription(context: Context) {
        when (entry.value?.note?.status) {
            NoteStatus.TRANSCRIBING -> TranscriptionService.cancelCurrent(context)
            NoteStatus.QUEUED -> launchInApp {
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
        launchInApp {
            try {
                c.notes.delete(listOf(id), what)
                if (what != DeleteWhat.AUDIO) {
                    // Don't leave a shared copy of a deleted transcript behind.
                    withContext(Dispatchers.IO) { ShareExport.forget(c.appContext.cacheDir, listOf(id)) }
                }
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
        try {
            ShareExport.shareText(context, note, currentText)
        } catch (e: RuntimeException) {
            // A very long transcript doesn't fit in an Intent (TransactionTooLargeException).
            Log.w(TAG, "Couldn't share as text", e)
            message("This transcript is too long to share as text, so it's shared as a .txt file.")
            shareFile(context)
        }
    }

    fun shareFile(context: Context) {
        val note = entry.value?.note ?: return
        if (currentText.isBlank()) return message("There's no text to share yet.")
        ShareExport.shareFile(context, note, currentText)
    }

    fun copy(context: Context) {
        val note = entry.value?.note ?: return
        if (currentText.isBlank()) return message("There's no text to copy yet.")
        try {
            ShareExport.copyToClipboard(context, note.title, currentText)
        } catch (e: RuntimeException) {
            // A very long transcript doesn't fit on the clipboard (TransactionTooLargeException).
            Log.w(TAG, "Couldn't copy", e)
            return message("This transcript is too long to copy. Share it as a .txt file or export it instead.")
        }
        // Android 13+ shows its own confirmation.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) message("Copied")
    }

    fun export(context: Context, uri: Uri, format: ExportFormat) {
        val note = entry.value?.note ?: return
        val content = ShareExport.render(note, currentText, format)
        launchInApp {
            try {
                ShareExport.exportTo(context, uri, content)
                message("Exported ${ShareExport.fileName(note, format)}")
            } catch (e: Exception) {
                message("Export failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "NoteViewModel"
    }
}

/**
 * Saves [pending] edits (if any) and only then queues the transcription. Queueing first makes
 * the note busy, so the save would be rejected and the stale editor text could later be saved
 * over the new transcript. Returns the message to show, or null.
 */
internal suspend fun saveEditsThenQueue(
    pending: String?,
    save: suspend (String) -> Unit,
    queue: suspend () -> String?,
    onSaveFailed: () -> Unit,
): String? {
    if (pending != null) {
        try {
            save(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onSaveFailed()
            return "Couldn't save your edits, so transcription wasn't started: ${e.message}"
        }
    }
    return queue()
}
