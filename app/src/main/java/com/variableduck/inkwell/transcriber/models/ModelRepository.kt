package com.variableduck.inkwell.transcriber.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.variableduck.inkwell.transcriber.data.Settings
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionStatus
import com.variableduck.inkwell.transcriber.whisper.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    data object None : DownloadState
    data class Running(val downloaded: Long, val total: Long) : DownloadState
    data object Verifying : DownloadState
    /** A `.part` file exists; the download can be resumed. [error] is set if it failed. */
    data class Partial(val downloaded: Long, val total: Long, val error: String?) : DownloadState
    /** Failed with nothing to resume. */
    data class Failed(val error: String) : DownloadState
}

data class ModelItem(
    /** File name inside the models folder. */
    val id: String,
    val title: String,
    val multilingual: Boolean,
    val imported: Boolean,
    val installed: Boolean,
    /** Bytes used on disk (model file plus any partial download). */
    val bytesOnDisk: Long,
    /** Download size for catalog models. */
    val downloadSize: Long?,
    val guidance: String?,
    val download: DownloadState,
) {
    val languageLabel: String get() = if (multilingual) "Multilingual" else "English-only"

    /** e.g. "Base (English-only)"; imported models keep their file name. */
    val displayName: String get() = if (imported) title else "$title ($languageLabel)"
}

@Serializable
private data class ImportedModel(
    val id: String,
    val title: String,
    val multilingual: Boolean,
    val importedAt: Long,
)

class ModelRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val importedIndex = File(dir, "imported.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val importMutex = Mutex()

    private val imported = MutableStateFlow(loadImported())
    private val downloads = MutableStateFlow(scanPartials())
    private val filesVersion = MutableStateFlow(0)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val downloaders = ConcurrentHashMap<String, ModelDownloader>()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    val items: StateFlow<List<ModelItem>> = combine(imported, downloads, filesVersion) { imp, dl, _ ->
        buildItems(imp, dl)
    }.stateIn(scope, SharingStarted.Eagerly, buildItems(imported.value, downloads.value))

    init {
        dir.listFiles { f -> f.name.endsWith(".importing") }?.forEach { it.delete() }
        ensureActiveModel()
    }

    fun file(id: String) = File(dir, id)
    private fun partFile(id: String) = File(dir, "$id.part")
    fun isInstalled(id: String?) = id != null && file(id).isFile

    /** The model transcription will use, or null if none is installed. */
    fun activeModel(): ModelItem? {
        val id = settings.value.activeModelId
        return buildItems(imported.value, downloads.value).firstOrNull { it.id == id && it.installed }
    }

    fun setActive(id: String) {
        if (isInstalled(id)) settings.setActiveModel(id)
    }

    /** Keeps the active-model setting pointing at an installed model (default model first). */
    fun ensureActiveModel() {
        if (isInstalled(settings.value.activeModelId)) return
        val installed = buildItems(imported.value, downloads.value).filter { it.installed }
        val pick = installed.firstOrNull { it.id == ModelCatalog.DEFAULT_MODEL } ?: installed.firstOrNull()
        settings.setActiveModel(pick?.id)
    }

    // ---- Downloads ---------------------------------------------------------------------

    fun startDownload(id: String) {
        val model = ModelCatalog.find(id) ?: return
        if (jobs[id]?.isActive == true || isInstalled(id)) return
        val downloader = ModelDownloader()
        downloaders[id] = downloader
        setState(id, DownloadState.Running(partFile(id).length(), model.sizeBytes))
        jobs[id] = scope.launch(Dispatchers.IO) {
            try {
                downloader.download(
                    model = model,
                    partFile = partFile(id),
                    finalFile = file(id),
                    onProgress = { done, total -> setState(id, DownloadState.Running(done, total)) },
                    onVerifying = { setState(id, DownloadState.Verifying) },
                )
                setState(id, DownloadState.None)
                filesVersion.update { it + 1 }
                ensureActiveModel()
            } catch (e: CancellationException) {
                setState(id, partialOrNone(id, model, null))
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of $id failed", e)
                val message = e.message ?: "Download failed"
                setState(id, partialOrNone(id, model, message).let {
                    if (it == DownloadState.None) DownloadState.Failed(message) else it
                })
            } finally {
                jobs.remove(id)
                downloaders.remove(id)
            }
        }
    }

    fun cancelDownload(id: String) {
        jobs[id]?.cancel()
        downloaders[id]?.cancelActive()
    }

    fun discardPartial(id: String) {
        cancelDownload(id)
        scope.launch(Dispatchers.IO) {
            jobs[id]?.join()
            partFile(id).delete()
            setState(id, DownloadState.None)
        }
    }

    // ---- Delete --------------------------------------------------------------------------

    /** Deletes a model. Returns an error message if it can't be deleted right now. */
    suspend fun delete(id: String): String? = withContext(Dispatchers.IO) {
        val status = TranscriptionStatus.state.value
        if (status.running && status.modelId == id) {
            return@withContext "This model is being used by a transcription. Stop it first."
        }
        cancelDownload(id)
        jobs[id]?.join()
        file(id).delete()
        partFile(id).delete()
        imported.update { list -> list.filterNot { it.id == id } }
        saveImported()
        setState(id, DownloadState.None)
        filesVersion.update { it + 1 }
        ensureActiveModel()
        null
    }

    // ---- Import --------------------------------------------------------------------------

    /**
     * Copies a GGML model chosen with the system file picker into app storage and checks that
     * whisper.cpp can load it before adding it to the list. Returns an error message or null.
     */
    suspend fun import(uri: Uri): String? = importMutex.withLock {
        withContext(Dispatchers.IO) {
            _importing.value = true
            val tmp = File(dir, "import-${System.currentTimeMillis()}.importing")
            try {
                val resolver = context.contentResolver
                var displayName = "model.bin"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { displayName = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                if (!displayName.endsWith(".bin", ignoreCase = true)) {
                    return@withContext "Choose a GGML model file ending in .bin"
                }
                if (size > 0 && dir.usableSpace < size + 100L * 1024 * 1024) {
                    return@withContext "Not enough free storage to import this model."
                }

                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out, 256 * 1024)
                        out.fd.sync()
                    }
                } ?: return@withContext "Couldn't open the file."

                if (!hasGgmlMagic(tmp)) {
                    return@withContext "That file isn't a GGML whisper model."
                }
                val probe = WhisperEngine.probe(context, tmp)
                    ?: return@withContext "whisper.cpp couldn't load that model."

                val baseTitle = displayName.removeSuffix(".bin").removeSuffix(".BIN")
                val safe = baseTitle.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "model" }
                var id = "user-$safe.bin"
                var n = 2
                while (file(id).exists()) id = "user-$safe-${n++}.bin"
                if (!tmp.renameTo(file(id))) return@withContext "Couldn't save the model."

                imported.update { it + ImportedModel(id, baseTitle, probe.multilingual, System.currentTimeMillis()) }
                saveImported()
                filesVersion.update { it + 1 }
                ensureActiveModel()
                null
            } catch (e: Exception) {
                Log.w(TAG, "Import failed", e)
                e.message ?: "Import failed"
            } finally {
                tmp.delete()
                _importing.value = false
            }
        }
    }

    /** whisper.cpp GGML files start with the 32-bit magic 0x67676d6c ("ggml"), little-endian. */
    private fun hasGgmlMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val b = ByteArray(4)
        file.inputStream().use { if (it.read(b) != 4) return false }
        val magic = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        return magic == 0x67676d6cL
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun buildItems(imp: List<ImportedModel>, dl: Map<String, DownloadState>): List<ModelItem> {
        val catalog = ModelCatalog.models.map { m ->
            val f = file(m.fileName)
            val part = partFile(m.fileName)
            ModelItem(
                id = m.fileName,
                title = m.title,
                multilingual = m.multilingual,
                imported = false,
                installed = f.isFile,
                bytesOnDisk = (if (f.isFile) f.length() else 0) + (if (part.isFile) part.length() else 0),
                downloadSize = m.sizeBytes,
                guidance = m.guidance,
                download = dl[m.fileName] ?: DownloadState.None,
            )
        }
        val user = imp.filter { file(it.id).isFile }.map { m ->
            ModelItem(
                id = m.id,
                title = m.title,
                multilingual = m.multilingual,
                imported = true,
                installed = true,
                bytesOnDisk = file(m.id).length(),
                downloadSize = null,
                guidance = "Imported",
                download = DownloadState.None,
            )
        }
        return catalog + user
    }

    private fun setState(id: String, state: DownloadState) {
        downloads.update { it + (id to state) }
    }

    private fun partialOrNone(id: String, model: CatalogModel, error: String?): DownloadState {
        val part = partFile(id)
        return if (part.isFile && part.length() > 0) {
            DownloadState.Partial(part.length(), model.sizeBytes, error)
        } else {
            DownloadState.None
        }
    }

    private fun scanPartials(): Map<String, DownloadState> =
        ModelCatalog.models.associate { m ->
            m.fileName to partialOrNone(m.fileName, m, null)
        }

    private fun loadImported(): List<ImportedModel> = try {
        if (importedIndex.isFile) {
            json.decodeFromString(ListSerializer(ImportedModel.serializer()), importedIndex.readText())
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read imported model list", e)
        emptyList()
    }

    private fun saveImported() {
        val tmp = File(dir, "imported.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(ImportedModel.serializer()), imported.value))
        if (!tmp.renameTo(importedIndex)) {
            importedIndex.delete()
            tmp.renameTo(importedIndex)
        }
    }

    companion object {
        private const val TAG = "ModelRepository"
    }
}
