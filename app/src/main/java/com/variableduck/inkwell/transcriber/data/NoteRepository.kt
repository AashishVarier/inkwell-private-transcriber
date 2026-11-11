package com.variableduck.inkwell.transcriber.data

import android.util.Log
import com.variableduck.inkwell.transcriber.audio.Wav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Notes live in one private folder (app internal storage, never backed up):
 * `<id>.wav` (audio), `<id>.txt` (transcript) and `<id>.json` (metadata).
 */
class NoteRepository(val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()

    private val _notes = MutableStateFlow<List<NoteEntry>>(emptyList())
    val notes: StateFlow<List<NoteEntry>> = _notes.asStateFlow()

    fun audioFile(id: String) = File(dir, "$id.wav")
    fun textFile(id: String) = File(dir, "$id.txt")
    private fun metaFile(id: String) = File(dir, "$id.json")

    /**
     * Loads every note and repairs anything left over from a crash: a recording that was in
     * progress gets its WAV header fixed so it can be transcribed, and an interrupted
     * transcription goes back in the queue to resume from its checkpoint.
     * Called once when the process starts, before any recording or transcription can run.
     */
    suspend fun loadAndRecover() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            // Leftover temp files from interrupted atomic writes.
            dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            val loaded = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f ->
                    try {
                        json.decodeFromString(Note.serializer(), f.readText())
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unreadable note ${f.name}", e)
                        null
                    }
                }
                .map { recover(it) }
            _notes.value = loaded.map { entryFor(it) }.sortedByDescending { it.note.createdAt }
        }
        ready.complete(Unit)
    }

    private fun recover(note: Note): Note = when (note.status) {
        NoteStatus.RECORDING -> {
            val audio = audioFile(note.id)
            val recovered = if (audio.exists()) {
                val bytes = try {
                    Wav.repair(audio)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't repair ${audio.name}", e)
                    0L
                }
                note.copy(
                    status = NoteStatus.RECORDED,
                    durationMs = Wav.durationMs(bytes),
                    recovered = true,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                note.copy(status = NoteStatus.RECORDED, recovered = true)
            }
            Log.i(TAG, "Recovered interrupted recording ${note.id}")
            writeMeta(recovered)
            recovered
        }
        NoteStatus.TRANSCRIBING -> {
            val requeued = note.copy(status = NoteStatus.QUEUED)
            writeMeta(requeued)
            requeued
        }
        else -> note
    }

    suspend fun awaitReady() = ready.await()

    fun entry(id: String): NoteEntry? = _notes.value.firstOrNull { it.id == id }

    suspend fun get(id: String): Note? {
        awaitReady()
        return entry(id)?.note
    }

    /** Creates a new note for a recording starting at [startedAt]. */
    suspend fun createForRecording(startedAt: Long): Note = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val base = NoteNames.baseName(startedAt)
            val id = NoteNames.unique(base) { candidate ->
                metaFile(candidate).exists() || audioFile(candidate).exists() || textFile(candidate).exists()
            }
            val note = Note(
                id = id,
                title = NoteNames.displayTimestamp(startedAt),
                createdAt = startedAt,
                status = NoteStatus.RECORDING,
                updatedAt = System.currentTimeMillis(),
            )
            writeMeta(note)
            publish(note)
            note
        }
    }

    /** Applies [transform] to a note and saves it. Returns the new note, or null if gone. */
    suspend fun update(id: String, transform: (Note) -> Note): Note? = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val current = entry(id)?.note ?: readMeta(id) ?: return@withLock null
            val next = transform(current).copy(updatedAt = System.currentTimeMillis())
            writeMeta(next)
            publish(next)
            next
        }
    }

    /** Re-reads file sizes for a note (after the services append text or audio). */
    suspend fun refreshFiles(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            entry(id)?.note?.let { publish(it) }
        }
    }

    fun readText(id: String): String {
        val f = textFile(id)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    /** Saves edited transcript text. */
    suspend fun saveText(id: String, text: String) = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            val note = entry(id)?.note ?: return@withLock
            check(!note.isBusy) { "This note is busy" }
            atomicWrite(textFile(id), text.toByteArray(Charsets.UTF_8))
            // If a transcription was interrupted part-way, continue after the edited text.
            val next = note.copy(
                textBytes = if (note.hasPartialTranscript) textFile(id).length() else note.textBytes,
                status = if (note.status == NoteStatus.RECORDED && !audioFile(id).exists()) NoteStatus.DONE else note.status,
                updatedAt = System.currentTimeMillis(),
            )
            writeMeta(next)
            publish(next)
        }
    }

    /**
     * Prepares the transcript file for a (re)transcription run and returns the checkpoint to
     * resume from. Text after the last checkpoint (from a chunk that was cut off by a crash)
     * is dropped so nothing is duplicated.
     */
    suspend fun prepareTranscript(id: String, restart: Boolean): Note? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val note = entry(id)?.note ?: return@withLock null
            val text = textFile(id)
            val resume = !restart && note.doneSamples > 0 && text.exists() && text.length() >= note.textBytes
            val next = if (resume) {
                RandomAccessFile(text, "rw").use { it.setLength(note.textBytes) }
                note
            } else {
                FileOutputStream(text, false).use { it.fd.sync() }
                note.copy(doneSamples = 0, textBytes = 0, progress = 0f)
            }
            writeMeta(next)
            publish(next)
            next
        }
    }

    /** Appends one chunk of transcript and records the checkpoint, crash-safely. */
    suspend fun appendChunk(id: String, chunk: String, doneSamples: Long, progress: Float): Note? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val note = entry(id)?.note ?: return@withLock null
                val text = textFile(id)
                if (chunk.isNotEmpty()) {
                    FileOutputStream(text, true).use { out ->
                        out.write(chunk.toByteArray(Charsets.UTF_8))
                        out.fd.sync()
                    }
                }
                val next = note.copy(
                    doneSamples = doneSamples,
                    textBytes = text.length(),
                    progress = progress,
                    updatedAt = System.currentTimeMillis(),
                )
                writeMeta(next)
                publish(next)
                next
            }
        }

    /** Last characters of the transcript, used as the prompt for the next chunk. */
    fun textTail(id: String, maxChars: Int): String {
        val f = textFile(id)
        if (!f.exists() || f.length() == 0L) return ""
        val bytesToRead = minOf(f.length(), (maxChars * 4L).coerceAtLeast(64))
        val buf = ByteArray(bytesToRead.toInt())
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(f.length() - bytesToRead)
            raf.readFully(buf)
        }
        return String(buf, Charsets.UTF_8).trimStart('�').takeLast(maxChars)
    }

    /** Oldest interrupted or queued note, in order of request. */
    suspend fun nextQueued(): Note? {
        awaitReady()
        return _notes.value.map { it.note }
            .filter { it.status == NoteStatus.QUEUED || it.status == NoteStatus.TRANSCRIBING }
            .minWithOrNull(compareBy<Note> { if (it.status == NoteStatus.TRANSCRIBING) 0 else 1 }.thenBy { it.queuedAt })
    }

    fun hasQueued(): Boolean = _notes.value.any { it.note.status == NoteStatus.QUEUED }

    // ---- Deleting ----------------------------------------------------------------------

    enum class DeleteWhat { AUDIO, TEXT, BOTH }

    /** Deletes the chosen files for each note. A note left with neither audio nor text is removed. */
    suspend fun delete(ids: Collection<String>, what: DeleteWhat) = withContext(Dispatchers.IO) {
        awaitReady()
        mutex.withLock {
            for (id in ids) {
                val note = entry(id)?.note ?: continue
                check(!note.isBusy) { "Note ${note.title} is busy" }
                if (what != DeleteWhat.TEXT) audioFile(id).delete()
                if (what != DeleteWhat.AUDIO) textFile(id).delete()
                val hasAudio = audioFile(id).exists()
                val hasText = textFile(id).exists()
                if (!hasAudio && !hasText) {
                    metaFile(id).delete()
                    _notes.update { list -> list.filterNot { it.id == id } }
                    continue
                }
                val next = when (what) {
                    DeleteWhat.AUDIO -> note.copy(status = NoteStatus.DONE, error = null)
                    DeleteWhat.TEXT -> note.copy(
                        status = NoteStatus.RECORDED,
                        doneSamples = 0,
                        textBytes = 0,
                        progress = 0f,
                        modelName = null,
                        error = null,
                    )
                    DeleteWhat.BOTH -> note
                }.copy(updatedAt = System.currentTimeMillis())
                writeMeta(next)
                publish(next)
            }
        }
    }

    // ---- Internals -----------------------------------------------------------------------

    private fun entryFor(note: Note): NoteEntry {
        val audio = audioFile(note.id)
        val text = textFile(note.id)
        return NoteEntry(
            note = note,
            hasAudio = audio.exists(),
            audioBytes = if (audio.exists()) audio.length() else 0,
            hasText = text.exists() && text.length() > 0,
            textBytes = if (text.exists()) text.length() else 0,
        )
    }

    private fun publish(note: Note) {
        val entry = entryFor(note)
        _notes.update { list ->
            (list.filterNot { it.id == note.id } + entry).sortedByDescending { it.note.createdAt }
        }
    }

    private fun readMeta(id: String): Note? = metaFile(id).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(Note.serializer(), it.readText()) }.getOrNull()
    }

    private fun writeMeta(note: Note) {
        atomicWrite(metaFile(note.id), json.encodeToString(Note.serializer(), note).toByteArray())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Couldn't save ${target.name}" }
        }
    }

    companion object {
        private const val TAG = "NoteRepository"
    }
}
