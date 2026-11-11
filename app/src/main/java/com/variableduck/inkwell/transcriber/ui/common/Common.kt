package com.variableduck.inkwell.transcriber.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionState
import com.variableduck.inkwell.transcriber.util.Format

fun deleteOptionLabel(what: DeleteWhat) = when (what) {
    DeleteWhat.AUDIO -> "Delete recording only"
    DeleteWhat.TEXT -> "Delete transcript only"
    DeleteWhat.BOTH -> "Delete recording and transcript"
}

fun isOptionAvailable(entries: List<NoteEntry>, what: DeleteWhat) = when (what) {
    DeleteWhat.AUDIO -> entries.any { it.hasAudio }
    DeleteWhat.TEXT -> entries.any { it.hasText }
    DeleteWhat.BOTH -> entries.isNotEmpty()
}

/** Plain-language description of exactly what a delete will remove. */
fun describeDelete(entries: List<NoteEntry>, what: DeleteWhat): String {
    val single = entries.size == 1
    val name = if (single) "“${entries[0].note.title}”" else "${entries.size} notes"
    val withAudio = entries.filter { it.hasAudio }
    val withText = entries.filter { it.hasText }
    val audioSize = Format.bytes(withAudio.sumOf { it.audioBytes })
    val body = when (what) {
        DeleteWhat.AUDIO -> {
            val wholeNotes = entries.count { it.hasAudio && !it.hasText }
            buildString {
                if (single) append("The recording of $name ($audioSize) will be deleted. The transcript is kept.")
                else append("Recordings from ${withAudio.size} of $name ($audioSize) will be deleted. Transcripts are kept.")
                if (wholeNotes > 0) {
                    append(
                        if (single) " This note has no transcript, so the whole note will be removed."
                        else " $wholeNotes of them have no transcript and will be removed entirely.",
                    )
                }
            }
        }
        DeleteWhat.TEXT -> {
            val wholeNotes = entries.count { it.hasText && !it.hasAudio }
            buildString {
                if (single) append("The transcript of $name will be deleted. The recording is kept and can be transcribed again.")
                else append("Transcripts of ${withText.size} of $name will be deleted. Recordings are kept and can be transcribed again.")
                if (wholeNotes > 0) {
                    append(
                        if (single) " This note has no recording, so the whole note will be removed."
                        else " $wholeNotes of them have no recording and will be removed entirely.",
                    )
                }
            }
        }
        DeleteWhat.BOTH -> buildString {
            append("$name will be deleted completely")
            val parts = mutableListOf<String>()
            if (withAudio.isNotEmpty()) parts += if (single) "the recording ($audioSize)" else "${withAudio.size} recordings ($audioSize)"
            if (withText.isNotEmpty()) parts += if (single) "the transcript" else "${withText.size} transcripts"
            if (parts.isNotEmpty()) append(": ").append(parts.joinToString(" and "))
            append(".")
        }
    }
    return "$body This can't be undone."
}

/**
 * Confirmation for deleting one or more notes. Lists the three options; [initial] preselects one.
 */
@Composable
fun DeleteDialog(
    entries: List<NoteEntry>,
    initial: DeleteWhat?,
    onConfirm: (DeleteWhat) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstAvailable = DeleteWhat.entries.first { isOptionAvailable(entries, it) }
    var choice by remember {
        mutableStateOf(initial?.takeIf { isOptionAvailable(entries, it) } ?: firstAvailable)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entries.size == 1) "Delete note?" else "Delete ${entries.size} notes?") },
        text = {
            Column {
                DeleteWhat.entries.forEach { what ->
                    val enabled = isOptionAvailable(entries, what)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = choice == what,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { choice = what },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == what, onClick = null, enabled = enabled)
                        Text(
                            deleteOptionLabel(what),
                            Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
                Text(
                    describeDelete(entries, choice),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(choice) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Short status shown for a note, or null when there's nothing to say. [live] is the running
 * transcription, whose progress moves more smoothly than the saved one.
 */
fun statusLabel(entry: NoteEntry, live: TranscriptionState? = null): String? {
    val note = entry.note
    val progress = live?.takeIf { it.noteId == note.id && !it.indeterminate }?.progress ?: note.progress
    return when (note.status) {
        NoteStatus.RECORDING -> "Recording…"
        NoteStatus.QUEUED -> when {
            live?.running == true && live.noteId == note.id -> "Loading model…"
            note.doneSamples > 0 -> "Waiting to resume (${Format.percent(note.progress)})"
            else -> "Waiting to transcribe"
        }
        NoteStatus.TRANSCRIBING -> "Transcribing ${Format.percent(progress)}"
        NoteStatus.FAILED -> "Transcription failed"
        NoteStatus.RECORDED ->
            if (note.hasPartialTranscript) "Partly transcribed (${Format.percent(note.progress)})" else "Not transcribed"
        NoteStatus.DONE -> when {
            note.warning != null -> "Parts left out"
            !entry.hasAudio -> "Text only"
            else -> null
        }
    }
}

/** Where a running transcription is: one summary line and a progress bar that keeps moving. */
@Composable
fun TranscriptionProgress(state: TranscriptionState, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(state.progress, label = "transcription progress")
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Always spinning, so it's clear work is going on even when the estimate is slow.
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                state.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.indeterminate) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Simple horizontal input level meter. */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val color = when {
        level > 0.93f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .background(color),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

val SpacedBy8 = Arrangement.spacedBy(8.dp)
