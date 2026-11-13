package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.ui.note.saveEditsThenQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Edits made to a partial transcript before Resume / Transcribe again must be saved before the
 * note is queued. Otherwise the save is refused (the note is busy) and the stale editor text can
 * later be saved over the newly transcribed text.
 */
class TranscribeEditsTest {
    private lateinit var repo: NoteRepository
    private lateinit var id: String

    @Before
    fun setUp() = runBlocking {
        repo = NoteRepository(Files.createTempDirectory("inkwell-edits").toFile())
        repo.loadAndRecover()
        id = repo.createForRecording(System.currentTimeMillis()).id
        repo.audioFile(id).writeBytes(ByteArray(64))
        // A partly transcribed note, as left behind by a cancelled run.
        repo.textFile(id).writeText("First part of the")
        repo.update(id) {
            it.copy(status = NoteStatus.RECORDED, doneSamples = 16_000, textBytes = repo.textFile(id).length())
        }
        Unit
    }

    @Test
    fun editsAreSavedBeforeTheNoteIsQueued() = runTest {
        var textWhenQueued: String? = null
        val message = saveEditsThenQueue(
            pending = "First part of the book",
            save = { repo.saveText(id, it) },
            queue = {
                textWhenQueued = repo.readText(id)
                repo.update(id) { it.copy(status = NoteStatus.QUEUED) }
                null
            },
            onSaveFailed = { fail("The save should have worked") },
        )

        assertNull(message)
        assertEquals("First part of the book", textWhenQueued)
        val note = repo.get(id)!!
        assertEquals(NoteStatus.QUEUED, note.status)
        // The resume checkpoint points after the edited text, so new chunks are appended to it.
        assertEquals(repo.textFile(id).length(), note.textBytes)
    }

    @Test
    fun savingAfterTheNoteIsQueuedIsRefused() = runTest {
        // What the old code could do: the queue request won the race, then the save was refused.
        repo.update(id) { it.copy(status = NoteStatus.QUEUED) }
        try {
            repo.saveText(id, "First part of the book")
            fail("Saving a busy note should be refused")
        } catch (_: IllegalStateException) {
        }
        assertEquals("First part of the", repo.readText(id))
    }

    @Test
    fun aFailedSaveDoesNotStartTranscription() = runTest {
        repo.update(id) { it.copy(status = NoteStatus.QUEUED) } // makes the save fail
        var queued = false
        var saveFailed = false
        val message = saveEditsThenQueue(
            pending = "First part of the book",
            save = { repo.saveText(id, it) },
            queue = { queued = true; null },
            onSaveFailed = { saveFailed = true },
        )

        assertFalse(queued)
        assertTrue(saveFailed)
        assertTrue(message!!.startsWith("Couldn't save your edits"))
        assertEquals("First part of the", repo.readText(id))
    }

    @Test
    fun withoutEditsItOnlyQueues() = runTest {
        var saved = false
        val message = saveEditsThenQueue(
            pending = null,
            save = { saved = true },
            queue = { "Download or import a model first (Models screen)." },
            onSaveFailed = { fail("Nothing to save") },
        )

        assertFalse(saved)
        assertEquals("Download or import a model first (Models screen).", message)
    }
}
