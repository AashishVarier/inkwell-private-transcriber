package com.variableduck.inkwell.transcriber.ui.note

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteNames
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.models.ModelItem
import com.variableduck.inkwell.transcriber.share.ExportFormat
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.ui.Navigator
import com.variableduck.inkwell.transcriber.ui.Routes
import com.variableduck.inkwell.transcriber.ui.common.DeleteDialog
import com.variableduck.inkwell.transcriber.ui.common.deleteOptionLabel
import com.variableduck.inkwell.transcriber.ui.common.isOptionAvailable
import com.variableduck.inkwell.transcriber.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(id: String, nav: Navigator) {
    val context = LocalContext.current
    val app = context.container
    val vm: NoteViewModel = viewModel(key = "note-$id") { NoteViewModel(app, id) }
    val entry by vm.entry.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleteChoice by remember { mutableStateOf<DeleteWhat?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Save edits when leaving the screen or when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.saveIfDirty()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val goBack = {
        vm.saveIfDirty()
        nav.back()
    }
    BackHandler { goBack() }

    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) vm.export(context, uri, ExportFormat.TXT)
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) vm.export(context, uri, ExportFormat.MD)
    }

    val current = entry
    // The note was deleted (completely): leave the screen.
    LaunchedEffect(current == null) {
        if (current == null) nav.back()
    }
    if (current == null) return
    val note = current.note

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.dirty) {
                        IconButton(onClick = { vm.saveIfDirty(showMessage = true) }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                    IconButton(onClick = { vm.copy(context) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy text")
                    }
                    Box {
                        IconButton(onClick = { shareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Share as text") },
                                onClick = { shareMenu = false; vm.shareText(context) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share as .txt file") },
                                onClick = { shareMenu = false; vm.shareFile(context) },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(
                                text = { Text("Export as .txt") },
                                enabled = current.hasText,
                                onClick = { menu = false; exportTxt.launch(ShareExport.fileName(note, ExportFormat.TXT)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as .md") },
                                enabled = current.hasText,
                                onClick = { menu = false; exportMd.launch(ShareExport.fileName(note, ExportFormat.MD)) },
                            )
                            if (current.hasAudio && !note.isBusy && (note.status == NoteStatus.DONE || current.hasText)) {
                                DropdownMenuItem(
                                    text = { Text("Transcribe again") },
                                    onClick = { menu = false; confirmRestart = true },
                                )
                            }
                            HorizontalDivider()
                            DeleteWhat.entries.forEach { what ->
                                DropdownMenuItem(
                                    text = { Text(deleteOptionLabel(what)) },
                                    enabled = isOptionAvailable(listOf(current), what) && !note.isBusy,
                                    onClick = {
                                        menu = false
                                        if (vm.canDelete()) deleteChoice = what
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            NoteHeader(current)
            StatusArea(
                entry = current,
                activeModel = activeModel,
                onTranscribe = { vm.transcribe(restart = false) },
                onStartOver = { confirmRestart = true },
                onCancel = { vm.cancelTranscription(context) },
                onChooseModel = { vm.saveIfDirty(); nav.open(Routes.MODELS) },
                onDismissWarning = vm::dismissWarning,
            )
            HorizontalDivider()
            TranscriptArea(vm, current, Modifier.weight(1f))
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(note.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename note") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Files keep their timestamp name (${note.id}).",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(title) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    deleteChoice?.let { initial ->
        DeleteDialog(
            entries = listOf(current),
            initial = initial,
            onConfirm = { what -> deleteChoice = null; vm.delete(what) },
            onDismiss = { deleteChoice = null },
        )
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Transcribe from the beginning?") },
            text = { Text("The current transcript, including any edits, will be replaced.") },
            confirmButton = {
                TextButton(onClick = { confirmRestart = false; vm.transcribe(restart = true) }) { Text("Transcribe") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteHeader(entry: NoteEntry) {
    val note = entry.note
    val parts = buildList {
        add(NoteNames.displayTimestamp(note.createdAt))
        if (note.durationMs > 0) add(Format.duration(note.durationMs))
        note.modelName?.let { add(it) }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val files = buildList {
            if (entry.hasAudio) add("Recording ${Format.bytes(entry.audioBytes)}")
            if (entry.hasText) add("Transcript ${Format.bytes(entry.textBytes)}")
        }
        if (files.isNotEmpty()) {
            Text(
                files.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (note.recovered) {
            Text(
                "Recovered after the app closed unexpectedly while recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusArea(
    entry: NoteEntry,
    activeModel: ModelItem?,
    onTranscribe: () -> Unit,
    onStartOver: () -> Unit,
    onCancel: () -> Unit,
    onChooseModel: () -> Unit,
    onDismissWarning: () -> Unit,
) {
    val note = entry.note
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
    when (note.status) {
        NoteStatus.RECORDING -> Text("Recording in progress…", modifier, color = MaterialTheme.colorScheme.error)
        NoteStatus.QUEUED, NoteStatus.TRANSCRIBING -> Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (note.status == NoteStatus.QUEUED) "Waiting to transcribe…"
                    else "Transcribing on this phone · ${Format.percent(note.progress)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (note.status == NoteStatus.TRANSCRIBING) {
                LinearProgressIndicator(progress = { note.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        NoteStatus.RECORDED, NoteStatus.FAILED -> if (entry.hasAudio) {
            Column(modifier) {
                if (note.status == NoteStatus.FAILED) {
                    Text(
                        note.error ?: "Transcription failed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (note.hasPartialTranscript) {
                    Text(
                        "Partly transcribed (${Format.percent(note.progress)}).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    activeModel?.let { "Model: ${it.displayName}" }
                        ?: "No model installed yet. Download or import one to transcribe.",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeModel != null) {
                        Button(onClick = onTranscribe) {
                            Text(
                                when {
                                    note.hasPartialTranscript -> "Resume"
                                    note.status == NoteStatus.FAILED -> "Try again"
                                    else -> "Transcribe"
                                },
                            )
                        }
                    }
                    OutlinedButton(onClick = onChooseModel) {
                        Text(
                            when {
                                activeModel == null -> "Get a model"
                                note.status == NoteStatus.FAILED -> "Choose model"
                                else -> "Change model"
                            },
                        )
                    }
                    if (note.hasPartialTranscript && activeModel != null) {
                        TextButton(onClick = onStartOver) { Text("Start over") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        NoteStatus.DONE -> note.warning?.let { warning ->
            Column(modifier) {
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.hasAudio) {
                        OutlinedButton(onClick = onChooseModel) { Text("Choose model") }
                        Button(onClick = onStartOver) { Text("Transcribe again") }
                    }
                    TextButton(onClick = onDismissWarning) { Text("Dismiss") }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TranscriptArea(vm: NoteViewModel, entry: NoteEntry, modifier: Modifier) {
    val busy = entry.note.isBusy
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Surface(modifier.fillMaxWidth()) {
        if (busy) {
            // Live view while the transcript is being written: follows the newest text.
            val scroll = rememberScrollState()
            LaunchedEffect(vm.editor.text.length) { scroll.animateScrollTo(scroll.maxValue) }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
            ) {
                Text(
                    vm.editor.text.ifEmpty { "Text will appear here as each part is transcribed." },
                    style = if (vm.editor.text.isEmpty()) {
                        textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        textStyle
                    },
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                BasicTextField(
                    value = vm.editor,
                    onValueChange = vm::onEdit,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxSize(),
                    decorationBox = { inner ->
                        if (vm.editor.text.isEmpty()) {
                            Text(
                                if (entry.hasAudio) "No transcript yet." else "No text.",
                                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}
