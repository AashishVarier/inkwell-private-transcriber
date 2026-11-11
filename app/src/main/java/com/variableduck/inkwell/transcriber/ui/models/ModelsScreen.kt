package com.variableduck.inkwell.transcriber.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.variableduck.inkwell.transcriber.AppContainer
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.data.Languages
import com.variableduck.inkwell.transcriber.models.DownloadState
import com.variableduck.inkwell.transcriber.models.ModelItem
import com.variableduck.inkwell.transcriber.ui.Navigator
import com.variableduck.inkwell.transcriber.ui.common.SectionHeader
import com.variableduck.inkwell.transcriber.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ModelsViewModel(private val c: AppContainer) : ViewModel() {
    val items = c.models.items
    val settings = c.settings.state
    val importing = c.models.importing

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun download(id: String) {
        if (c.models.dir.usableSpace < (items.value.firstOrNull { it.id == id }?.downloadSize ?: 0) + 50L * 1024 * 1024) {
            _messages.trySend("Not enough free storage for this model.")
            return
        }
        c.models.startDownload(id)
    }

    fun cancel(id: String) = c.models.cancelDownload(id)
    fun discard(id: String) = c.models.discardPartial(id)
    fun setActive(id: String) = c.models.setActive(id)
    fun setLanguage(code: String) = c.settings.setLanguage(code)

    fun delete(id: String) {
        viewModelScope.launch { c.models.delete(id)?.let { _messages.trySend(it) } }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val error = c.models.import(uri)
            _messages.trySend(error ?: "Model imported and checked")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(nav: Navigator) {
    val app = LocalContext.current.container
    val vm: ModelsViewModel = viewModel { ModelsViewModel(app) }
    val items by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<ModelItem?>(null) }
    var pickLanguage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = nav::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("Language") },
                    supportingContent = {
                        Text("${Languages.name(settings.language)} · used by multilingual models; English-only models always use English")
                    },
                    modifier = Modifier.clickable { pickLanguage = true },
                )
                HorizontalDivider()
            }
            item { SectionHeader("Download") }
            items(items.filter { !it.imported }, key = { it.id }) { item ->
                ModelCard(
                    item = item,
                    active = item.id == settings.activeModelId,
                    onSelect = { vm.setActive(item.id) },
                    onDownload = { vm.download(item.id) },
                    onCancel = { vm.cancel(item.id) },
                    onDiscard = { vm.discard(item.id) },
                    onDelete = { confirmDelete = item },
                )
            }
            item { SectionHeader("Your models") }
            items(items.filter { it.imported }, key = { it.id }) { item ->
                ModelCard(
                    item = item,
                    active = item.id == settings.activeModelId,
                    onSelect = { vm.setActive(item.id) },
                    onDownload = {},
                    onCancel = {},
                    onDiscard = {},
                    onDelete = { confirmDelete = item },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !importing,
                    ) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Importing and checking…")
                        } else {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import model (.bin)")
                        }
                    }
                    Text(
                        "Any whisper.cpp GGML model file. It's copied into Inkwell and test-loaded before it's added.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    "Downloads come from huggingface.co/ggerganov/whisper.cpp and start only when you tap " +
                        "Download. This is the only time Inkwell uses the internet; recording and " +
                        "transcription always stay on this phone.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${item.displayName}?") },
            text = {
                Text(
                    "This frees ${Format.bytes(item.bytesOnDisk)}." +
                        if (item.imported) " You'd need the file again to re-import it." else " You can download it again later.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(item.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (pickLanguage) {
        AlertDialog(
            onDismissRequest = { pickLanguage = false },
            title = { Text("Language") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    val options = listOf(Languages.AUTO to Languages.name(Languages.AUTO)) + Languages.all
                    items(options, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setLanguage(code); pickLanguage = false }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.language == code, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ModelCard(
    item: ModelItem,
    active: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = active, onClick = onSelect, enabled = item.installed)
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(item.languageLabel) })
            }
            Column(Modifier.padding(start = 48.dp)) {
                item.guidance?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                when (val d = item.download) {
                    is DownloadState.Running -> {
                        LinearProgressIndicator(
                            progress = { if (d.total > 0) d.downloaded.toFloat() / d.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Format.bytes(d.downloaded)} of ${Format.bytes(d.total)}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onCancel) { Text("Cancel") }
                        }
                    }
                    DownloadState.Verifying -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.Partial -> {
                        LinearProgressIndicator(
                            progress = { if (d.total > 0) d.downloaded.toFloat() / d.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            d.error ?: "Paused at ${Format.bytes(d.downloaded)} of ${Format.bytes(d.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onDownload) { Text("Resume") }
                            TextButton(onClick = onDiscard) { Text("Discard") }
                        }
                    }
                    else -> {
                        if (d is DownloadState.Failed) {
                            Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (item.installed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (active) "In use · " else "") + "Uses ${Format.bytes(item.bytesOnDisk)}",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onDelete) { Text("Delete") }
                            }
                        } else {
                            FilledTonalButton(onClick = onDownload) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Download · ${Format.bytes(item.downloadSize ?: 0)}")
                            }
                        }
                    }
                }
            }
        }
    }
}
