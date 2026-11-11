package com.variableduck.inkwell.transcriber.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.variableduck.inkwell.transcriber.BuildConfig
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.util.Format
import com.variableduck.inkwell.transcriber.whisper.CpuInfo
import com.variableduck.inkwell.transcriber.whisper.WhisperEngine
import com.variableduck.inkwell.transcriber.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageUsage(val audio: Long, val text: Long, val models: Long, val free: Long)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.state

    private val _storage = MutableStateFlow<StorageUsage?>(null)
    val storage = _storage.asStateFlow()
    private val _engineInfo = MutableStateFlow<String?>(null)
    val engineInfo = _engineInfo.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = c.notes.dir.listFiles().orEmpty()
            _storage.value = StorageUsage(
                audio = files.filter { it.name.endsWith(".wav") }.sumOf { it.length() },
                text = files.filter { it.name.endsWith(".txt") }.sumOf { it.length() },
                models = c.models.dir.listFiles().orEmpty().sumOf { it.length() },
                free = c.notes.dir.usableSpace,
            )
            _engineInfo.value = try {
                val info = WhisperEngine.ensureNative(c.appContext)
                "whisper.cpp ${WhisperLib.nativeVersion()}\n$info"
            } catch (e: Exception) {
                e.message
            }
        }
    }

    fun setThreads(n: Int) = c.settings.setThreads(n)
    fun setKeepAudio(v: Boolean) = c.settings.setKeepAudio(v)
    fun setRemoveTags(v: Boolean) = c.settings.setRemoveTags(v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: com.variableduck.inkwell.transcriber.ui.Navigator) {
    val app = LocalContext.current.container
    val vm: SettingsViewModel = viewModel { SettingsViewModel(app) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val engineInfo by vm.engineInfo.collectAsStateWithLifecycle()
    var threadsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = nav::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text("Keep audio after transcription") },
                supportingContent = {
                    Text(
                        if (settings.keepAudio) "Recordings are kept so they can be transcribed again."
                        else "Recordings are deleted once the transcript is complete.",
                    )
                },
                trailingContent = { Switch(checked = settings.keepAudio, onCheckedChange = vm::setKeepAudio) },
            )
            ListItem(
                headlineContent = { Text("Remove [BLANK_AUDIO] and similar tags") },
                supportingContent = { Text("Strips markers like [BLANK_AUDIO], [MUSIC] and (silence). Punctuation is kept.") },
                trailingContent = { Switch(checked = settings.removeTags, onCheckedChange = vm::setRemoveTags) },
            )
            ListItem(
                headlineContent = { Text("Transcription threads") },
                supportingContent = {
                    Text(
                        if (settings.threads == 0) "Automatic (${CpuInfo.performanceCores}, the number of performance cores)"
                        else "${settings.threads} threads",
                    )
                },
                modifier = Modifier.clickable { threadsDialog = true },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Storage") },
                supportingContent = {
                    val s = storage
                    Text(
                        if (s == null) "…"
                        else "Recordings ${Format.bytes(s.audio)} · Transcripts ${Format.bytes(s.text)} · " +
                            "Models ${Format.bytes(s.models)}\n${Format.bytes(s.free)} free on this phone",
                    )
                },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = {
                    Text(
                        "Audio and text never leave this phone unless you share or export them. " +
                            "Transcription runs entirely on the device. The internet is used only " +
                            "when you tap Download on a model. No analytics, ads or crash reporting. " +
                            "App data is excluded from cloud backups.",
                    )
                },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("About") },
                supportingContent = {
                    Text(
                        "Inkwell: The Private Transcriber ${BuildConfig.VERSION_NAME}\n" +
                            (engineInfo ?: "") +
                            "\n\nwhisper.cpp (MIT) · Whisper model weights (MIT, OpenAI)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }

    if (threadsDialog) {
        val max = CpuInfo.coreCount.coerceAtLeast(1)
        var value by remember {
            mutableFloatStateOf((if (settings.threads == 0) CpuInfo.performanceCores else settings.threads).toFloat())
        }
        AlertDialog(
            onDismissRequest = { threadsDialog = false },
            title = { Text("Transcription threads") },
            text = {
                Column {
                    Text(
                        "More threads is usually faster up to the number of performance cores " +
                            "(${CpuInfo.performanceCores} on this phone).",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = 1f..max.toFloat(),
                            steps = (max - 2).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(value.toInt().toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setThreads(value.toInt()); threadsDialog = false }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { vm.setThreads(0); threadsDialog = false }) { Text("Automatic") }
            },
        )
    }
}
