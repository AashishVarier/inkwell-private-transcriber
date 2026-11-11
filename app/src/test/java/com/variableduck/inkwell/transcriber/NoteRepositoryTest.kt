package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.audio.Wav
import com.variableduck.inkwell.transcriber.audio.WavWriter
import com.variableduck.inkwell.transcriber.data.Note
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteRepository.DeleteWhat
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.transcribe.ChunkedTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

class NoteRepositoryTest {
    private lateinit var dir: File
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("inkwell-notes").toFile()
        repo = NoteRepository(dir)
        runBlocking { repo.loadAndRecover() }
    }

    /** Writes [seconds] of a tone with a short pause every 28 s and an optional silent stretch. */
    private fun writeSpeechLikeWav(file: File, seconds: Int, silentFrom: Int = -1, silentTo: Int = -1) {
        WavWriter(file).use { w ->
            val buf = ByteArray(Wav.BYTES_PER_SECOND / 10)
            for (tenth in 0 until seconds * 10) {
                val t = tenth / 10.0
                val silent = (t % 28.0) in 27.0..27.4 || (t >= silentFrom && t < silentTo)
                for (i in 0 until buf.size / 2) {
                    val s = if (silent) 0 else (8000 * sin(2 * PI * 220 * (tenth * 1600 + i) / 16000.0)).toInt()
                    buf[2 * i] = (s and 0xFF).toByte()
                    buf[2 * i + 1] = (s shr 8 and 0xFF).toByte()
                }
                w.write(buf, buf.size)
            }
        }
    }

    private suspend fun newRecordedNote(seconds: Int, silentFrom: Int = -1, silentTo: Int = -1): Note {
        val note = repo.createForRecording(System.currentTimeMillis())
        writeSpeechLikeWav(repo.audioFile(note.id), seconds, silentFrom, silentTo)
        return repo.update(note.id) { it.copy(status = NoteStatus.RECORDED, durationMs = seconds * 1000L) }!!
    }

    @Test
    fun transcribesInChunksWithPromptCarryOverAndSkipsSilence() = runBlocking<Unit> {
        // 100 s: speech 0-28, silence 28-70 (one whole chunk is silent and skipped), speech 70-100.
        val note = newRecordedNote(100, silentFrom = 28, silentTo = 70)
        val prompts = mutableListOf<String>()
        var calls = 0
        var lastProgress = 0f
        ChunkedTranscriber(repo).run(
            id = note.id,
            removeTags = true,
            transcribeChunk = { samples, prompt ->
                assertTrue(samples.size <= 30 * 16_000)
                prompts += prompt
                " [BLANK_AUDIO] Part ${++calls}."
            },
            onProgress = { lastProgress = it },
        )
        val text = repo.readText(note.id)
        assertEquals((1..calls).joinToString(" ") { "Part $it." }, text)
        assertEquals("one silent chunk should be skipped", 3, calls)
        assertEquals("", prompts.first())
        assertEquals("Part 1.", prompts[1])
        assertEquals(1f, lastProgress, 0f)
        val saved = repo.get(note.id)!!
        assertEquals(100L * 16_000, saved.doneSamples)
        assertEquals(text.toByteArray().size.toLong(), saved.textBytes)
    }

    @Test
    fun reportsSpeechTheModelCouldNotTranscribe() = runBlocking<Unit> {
        // What an English-only model returns for speech in another language.
        val note = newRecordedNote(70)
        val result = ChunkedTranscriber(repo).run(note.id, true, transcribeChunk = { _, _ ->
            " (speaking in foreign language)"
        })
        assertEquals(3, result.speechChunks)
        assertEquals(3, result.foreignChunks)
        assertEquals("", repo.readText(note.id))
    }

    @Test
    fun resumesAfterInterruptionWithoutDuplicatesOrPartialText() = runBlocking<Unit> {
        val note = newRecordedNote(120)
        var calls = 0
        val fake: suspend (FloatArray, String) -> String = { _, _ ->
            calls++
            if (calls == 3) throw CancellationException("killed")
            "Part $calls."
        }
        try {
            ChunkedTranscriber(repo).run(note.id, true, fake)
        } catch (_: CancellationException) {
        }
        assertEquals("Part 1. Part 2.", repo.readText(note.id))
        // Simulate a crash in the middle of appending the next chunk.
        repo.textFile(note.id).appendText(" Part 3 half writ")
        repo.update(note.id) { it.copy(status = NoteStatus.QUEUED) }

        calls = 2
        ChunkedTranscriber(repo).run(note.id, true, transcribeChunk = { _, _ -> "Part ${++calls}." })
        assertEquals("Part 1. Part 2. Part 3. Part 4. Part 5.", repo.readText(note.id))
    }

    @Test
    fun recoversRecordingAndTranscriptionInterruptedByCrash() = runBlocking<Unit> {
        val json = Json { encodeDefaults = true }
        // A recording whose header never got updated past 0 bytes.
        val rec = Note(id = "11-11-2026-14-30-05", title = "t", createdAt = 1, status = NoteStatus.RECORDING)
        File(dir, "${rec.id}.json").writeText(json.encodeToString(Note.serializer(), rec))
        RandomAccessFile(File(dir, "${rec.id}.wav"), "rw").use {
            it.write(Wav.header(0))
            it.write(ByteArray(3 * Wav.BYTES_PER_SECOND + 1))
        }
        val tr = Note(id = "11-11-2026-15-00-00", title = "u", createdAt = 2, status = NoteStatus.TRANSCRIBING, doneSamples = 5)
        File(dir, "${tr.id}.json").writeText(json.encodeToString(Note.serializer(), tr))
        File(dir, "${tr.id}.wav").writeBytes(Wav.header(0))

        val fresh = NoteRepository(dir)
        fresh.loadAndRecover()
        val recovered = fresh.get(rec.id)!!
        assertEquals(NoteStatus.RECORDED, recovered.status)
        assertTrue(recovered.recovered)
        assertEquals(3000L, recovered.durationMs)
        assertEquals(NoteStatus.QUEUED, fresh.get(tr.id)!!.status)
        assertEquals(tr.id, fresh.nextQueued()!!.id)
    }

    @Test
    fun deleteOptions() = runBlocking<Unit> {
        val a = newRecordedNote(2)
        repo.textFile(a.id).writeText("hello")
        repo.refreshFiles(a.id)

        repo.delete(listOf(a.id), DeleteWhat.AUDIO)
        assertFalse(repo.audioFile(a.id).exists())
        assertEquals(NoteStatus.DONE, repo.get(a.id)!!.status)
        assertEquals("hello", repo.readText(a.id))

        // Deleting the only remaining part removes the whole note.
        repo.delete(listOf(a.id), DeleteWhat.TEXT)
        assertNull(repo.get(a.id))
        assertFalse(File(dir, "${a.id}.json").exists())

        val b = newRecordedNote(2)
        repo.textFile(b.id).writeText("text")
        repo.delete(listOf(b.id), DeleteWhat.TEXT)
        assertTrue(repo.audioFile(b.id).exists())
        assertEquals(NoteStatus.RECORDED, repo.get(b.id)!!.status)

        repo.delete(listOf(b.id), DeleteWhat.BOTH)
        assertNull(repo.get(b.id))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun sameSecondRecordingsGetDistinctNames() = runBlocking<Unit> {
        val t = System.currentTimeMillis()
        val first = repo.createForRecording(t)
        val second = repo.createForRecording(t)
        assertEquals("${first.id}-2", second.id)
    }
}
