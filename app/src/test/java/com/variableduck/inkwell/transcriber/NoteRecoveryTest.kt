package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.audio.Wav
import com.variableduck.inkwell.transcriber.data.Note
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Startup recovery must not crash when the repaired metadata can't be saved (storage full). */
class NoteRecoveryTest {
    private val json = Json { encodeDefaults = true }

    private fun writeMeta(dir: File, note: Note) =
        File(dir, "${note.id}.json").writeText(json.encodeToString(Note.serializer(), note))

    /** What the app leaves behind when it dies mid-recording: status RECORDING, header not final. */
    private fun interruptedRecording(dir: File, id: String) {
        writeMeta(dir, Note(id = id, title = id, createdAt = 1_000L, status = NoteStatus.RECORDING))
        File(dir, "$id.wav").writeBytes(Wav.header(0) + ByteArray(Wav.BYTES_PER_SECOND * 2))
    }

    /**
     * Stand-in for a full disk: a non-empty directory where the temporary metadata file has to
     * go can't be cleaned up or written, even when the tests run as root.
     */
    private fun blockMetadataWrites(dir: File, id: String) {
        File(dir, "$id.json.tmp").apply {
            mkdirs()
            File(this, "blocker").writeText("x")
        }
    }

    @Test
    fun interruptedRecordingIsRecoveredEvenIfItCantBeSaved() {
        val dir = Files.createTempDirectory("inkwell-recover").toFile()
        interruptedRecording(dir, "a")
        blockMetadataWrites(dir, "a")

        val repo = NoteRepository(dir)
        runBlocking { repo.loadAndRecover() } // threw (and crashed the app) before the fix

        assertTrue(repo.loaded.value)
        val note = runBlocking { repo.get("a") }!!
        assertEquals(NoteStatus.RECORDED, note.status)
        assertTrue(note.recovered)
        assertEquals(2_000L, note.durationMs)
        // Not saved, so recovery simply runs again at the next launch.
        assertTrue(File(dir, "a.json").readText().contains("\"RECORDING\""))

        // Once there is space again, the next launch saves the recovered note.
        File(dir, "a.json.tmp").deleteRecursively()
        val next = NoteRepository(dir)
        runBlocking { next.loadAndRecover() }
        assertEquals(NoteStatus.RECORDED, runBlocking { next.get("a") }!!.status)
        assertFalse(File(dir, "a.json").readText().contains("\"RECORDING\""))
    }

    @Test
    fun interruptedTranscriptionIsRequeuedEvenIfItCantBeSaved() {
        val dir = Files.createTempDirectory("inkwell-requeue").toFile()
        writeMeta(dir, Note(id = "b", title = "b", createdAt = 1_000L, status = NoteStatus.TRANSCRIBING, doneSamples = 160))
        blockMetadataWrites(dir, "b")

        val repo = NoteRepository(dir)
        runBlocking { repo.loadAndRecover() }

        val note = runBlocking { repo.get("b") }!!
        assertEquals(NoteStatus.QUEUED, note.status)
        assertEquals(160L, note.doneSamples)
    }

    @Test
    fun loadedTurnsTrueOnlyAfterNotesAreRead() {
        val repo = NoteRepository(Files.createTempDirectory("inkwell-loaded").toFile())
        assertFalse(repo.loaded.value)
        runBlocking { repo.loadAndRecover() }
        assertTrue(repo.loaded.value)
    }
}
