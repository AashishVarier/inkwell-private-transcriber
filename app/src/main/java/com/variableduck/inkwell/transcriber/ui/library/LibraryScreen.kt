package com.variableduck.inkwell.transcriber.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteNames
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.record.RecorderPhase
import com.variableduck.inkwell.transcriber.record.RecorderState
import com.variableduck.inkwell.transcriber.record.RecordingService
import com.variableduck.inkwell.transcriber.share.ExportFormat
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionState
import com.variableduck.inkwell.transcriber.ui.Navigator
import com.variableduck.inkwell.transcriber.ui.Routes
import com.variableduck.inkwell.transcriber.ui.common.DeleteDialog
import com.variableduck.inkwell.transcriber.ui.common.LevelMeter
import com.variableduck.inkwell.transcriber.ui.common.SectionHeader
import com.variableduck.inkwell.transcriber.ui.common.deleteOptionLabel
import com.variableduck.inkwell.transcriber.ui.common.isOptionAvailable
import com.variableduck.inkwell.transcriber.ui.common.statusLabel
import com.variableduck.inkwell.transcriber.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.container
    val vm: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val transcription by vm.transcription.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Pair<List<String>, DeleteWhat?>?>(null) }
    var showLowStorageStart by remember { mutableStateOf(false) }
    var showExportAll by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.TXT) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(recorder.error) {
        recorder.error?.let {
            vm.clearRecorderError()
            snackbar.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }
    // Drop selections of notes that no longer exist.
    LaunchedEffect(notes) {
        val ids = notes.map { it.id }.toSet()
        if (!ids.containsAll(selection)) selection = selection intersect ids
    }

    BackHandler(enabled = selecting) {
        selecting = false
        selection = emptySet()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            if (vm.freeBytes() < RecordingService.LOW_STORAGE_BYTES) showLowStorageStart = true
            else vm.startRecording(context)
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(
                    "Inkwell needs the microphone to record.",
                    actionLabel = "Settings",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(appSettingsIntent(context.packageName))
                }
            }
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? -> if (uri != null) vm.exportAll(context, uri, exportFormat) }

    fun startRecording() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        when {
            allGranted || micGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> {
                if (vm.freeBytes() < RecordingService.LOW_STORAGE_BYTES) showLowStorageStart = true
                else vm.startRecording(context)
            }
            else -> permissionLauncher.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selecting = false; selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Done selecting")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selection = if (selection.size == notes.size) emptySet() else notes.map { it.id }.toSet()
                        }) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
                        IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = { if (vm.canDelete()) deleteTarget = selection.toList() to null },
                        ) { Icon(Icons.Default.Delete, contentDescription = "Delete selected") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Inkwell") },
                    actions = {
                        IconButton(onClick = { nav.open(Routes.MODELS) }) {
                            Icon(Icons.Default.Tune, contentDescription = "Models")
                        }
                        IconButton(onClick = { nav.open(Routes.SETTINGS) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Export all as .zip") },
                                    enabled = notes.any { it.hasText },
                                    onClick = { menuOpen = false; showExportAll = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Select notes") },
                                    enabled = notes.isNotEmpty(),
                                    onClick = { menuOpen = false; selecting = true },
                                )
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (activeModel == null) {
                item { NoModelCard(onOpenModels = { nav.open(Routes.MODELS) }) }
            }
            item {
                RecorderPanel(
                    state = recorder,
                    onRecord = ::startRecording,
                    onPause = { vm.pauseRecording(context) },
                    onResume = { vm.resumeRecording(context) },
                    onStop = { vm.stopRecording(context) },
                )
            }
            if (transcription.running) {
                item { TranscriptionCard(transcription, onCancel = { vm.cancelTranscription(context) }) }
            }
            item {
                SectionHeader(if (notes.isEmpty()) "Notes" else "Notes (${notes.size})")
            }
            if (notes.isEmpty()) {
                item {
                    Text(
                        "Your recordings and transcripts will appear here. Everything stays on this phone.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(notes, key = { it.id }) { entry ->
                NoteRow(
                    entry = entry,
                    selecting = selecting,
                    selected = entry.id in selection,
                    onClick = {
                        if (selecting) {
                            selection = if (entry.id in selection) selection - entry.id else selection + entry.id
                        } else {
                            nav.open(Routes.note(entry.id))
                        }
                    },
                    onDelete = { what -> if (vm.canDelete()) deleteTarget = listOf(entry.id) to what },
                    onSelectMultiple = {
                        selecting = true
                        selection = selection + entry.id
                    },
                )
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }

    deleteTarget?.let { (ids, initial) ->
        val entries = notes.filter { it.id in ids }
        if (entries.isEmpty()) {
            deleteTarget = null
        } else {
            DeleteDialog(
                entries = entries,
                initial = initial,
                onConfirm = { what ->
                    deleteTarget = null
                    vm.delete(ids, what)
                    selecting = false
                    selection = emptySet()
                },
                onDismiss = { deleteTarget = null },
            )
        }
    }

    if (showLowStorageStart) {
        AlertDialog(
            onDismissRequest = { showLowStorageStart = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Storage is low") },
            text = {
                Text(
                    "Only ${Format.bytes(vm.freeBytes())} free. One hour of audio uses about 115 MB. " +
                        "Recording will stop and save automatically if storage runs out.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLowStorageStart = false; vm.startRecording(context) }) {
                    Text("Record anyway")
                }
            },
            dismissButton = { TextButton(onClick = { showLowStorageStart = false }) { Text("Cancel") } },
        )
    }

    if (showExportAll) {
        AlertDialog(
            onDismissRequest = { showExportAll = false },
            title = { Text("Export all transcripts") },
            text = {
                Column {
                    Text("All transcripts go into one .zip, each named by its recording time.")
                    Spacer(Modifier.height(8.dp))
                    ExportFormat.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = exportFormat == f, onClick = { exportFormat = f })
                            Text(if (f == ExportFormat.TXT) "Plain text (.txt)" else "Markdown (.md)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportAll = false
                    zipLauncher.launch(ShareExport.zipName())
                }) { Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = { showExportAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoModelCard(onOpenModels: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Add a transcription model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Inkwell transcribes on this phone. Download Whisper Tiny (78 MB) once, or import " +
                    "your own model. You can record now and transcribe later.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenModels) { Text("Open models") }
        }
    }
}

@Composable
private fun RecorderPanel(
    state: RecorderState,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.isActive) {
                Button(
                    onClick = onRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Record", fontSize = 20.sp)
                }
                Text(
                    "No time limit. Keeps recording with the screen off.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val paused = state.phase == RecorderPhase.PAUSED
                Text(
                    when (state.phase) {
                        RecorderPhase.STARTING -> "Starting…"
                        RecorderPhase.PAUSED -> "Paused"
                        RecorderPhase.STOPPING -> "Saving…"
                        else -> "Recording"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Text(
                    Format.duration(state.elapsedMs),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                )
                LevelMeter(
                    if (paused) 0f else state.level,
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
                if (state.lowStorage) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Low storage: ${Format.bytes(state.freeBytes)} left",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val enabled = state.phase == RecorderPhase.RECORDING || paused
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = if (paused) onResume else onPause, enabled = enabled) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (paused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = onStop,
                        enabled = state.phase != RecorderPhase.STOPPING,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionCard(state: TranscriptionState, onCancel: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp, 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.noteTitle?.let { "Transcribing $it" } ?: "Preparing…",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Format.percent(state.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    entry: NoteEntry,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (DeleteWhat) -> Unit,
    onSelectMultiple: () -> Unit,
) {
    val note = entry.note
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { if (!selecting) menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val timestamp = NoteNames.displayTimestamp(note.createdAt)
                val details = buildList {
                    if (note.title != timestamp) add(timestamp)
                    note.modelName?.let { add(it) }
                }
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val status = listOfNotNull(
                    statusLabel(entry),
                    if (note.recovered) "Recovered" else null,
                ).joinToString(" · ")
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (note.status == NoteStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                Format.duration(note.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DeleteWhat.entries.forEach { what ->
                DropdownMenuItem(
                    text = { Text(deleteOptionLabel(what)) },
                    enabled = isOptionAvailable(listOf(entry), what) && !note.isBusy,
                    onClick = { menu = false; onDelete(what) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Select multiple") }, onClick = { menu = false; onSelectMultiple() })
        }
    }
}

/** Opens this app's system settings page (e.g. to grant a denied permission). */
private fun appSettingsIntent(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
