package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.transcribe.ProgressEstimator
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionStage
import com.variableduck.inkwell.transcriber.transcribe.TranscriptionState
import com.variableduck.inkwell.transcriber.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressEstimatorTest {
    private val sr = 16_000L

    @Test
    fun movesWithinAChunkAndLearnsThePhonesSpeed() {
        var now = 0L
        val e = ProgressEstimator(guessMsPerAudioSecond = 500.0, clock = { now })
        e.started(doneSamples = 0, totalSamples = 60 * sr)
        assertNull("no estimate before a chunk is timed", e.remainingMs())

        // First 30 s chunk, guessed at 500 ms per audio second = 15 s.
        e.chunkStarted(0, (30 * sr).toInt())
        now = 7_500
        assertEquals(0.25f, e.progress(), 0.001f)
        // Slower than guessed: keeps creeping, ever more slowly, but never reaches the chunk's end.
        now = 20_000
        val slow = e.progress()
        assertTrue(slow > 0.375f && slow < 0.5f)
        now = 40_000
        assertTrue(e.progress() > slow && e.progress() < 0.5f)
        now = 20_000 // (the clock only matters for the chunk's measured time below)
        e.advanced(30 * sr, transcribed = true)
        assertEquals(0.5f, e.progress(), 0.001f)
        // Measured: 20 s for 30 s of audio.
        assertEquals(20_000.0 / 30, e.msPerAudioSecond!!, 0.001)
        assertEquals(20_000L, e.remainingMs())

        // Second chunk now uses the measured speed: expected 20 s.
        e.chunkStarted(30 * sr, (30 * sr).toInt())
        now = 30_000
        assertEquals(0.75f, e.progress(), 0.001f)
        assertEquals(10_000L, e.remainingMs())
        e.advanced(60 * sr, transcribed = true)
        assertEquals(1f, e.progress(), 0f)
        assertEquals(0L, e.remainingMs())
    }

    @Test
    fun skippedSilenceAdvancesWithoutChangingSpeedAndProgressNeverGoesBack() {
        var now = 0L
        val e = ProgressEstimator(guessMsPerAudioSecond = 1000.0, clock = { now })
        e.started(doneSamples = 10 * sr, totalSamples = 100 * sr) // resumed at 10 %
        assertEquals(0.1f, e.progress(), 0.001f)
        e.advanced(40 * sr, transcribed = false)
        assertEquals(0.4f, e.progress(), 0.001f)
        assertNull(e.msPerAudioSecond)

        e.chunkStarted(40 * sr, (30 * sr).toInt())
        now = 1_000_000 // far longer than expected: just short of the chunk's end
        val capped = e.progress()
        assertTrue(capped > 0.68f && capped < 0.4f + 0.3f * 0.97f)
        // A restart from an earlier point (cancel + resume) must not move the bar backwards.
        e.started(doneSamples = 40 * sr, totalSamples = 100 * sr)
        assertEquals(capped, e.progress(), 0f)
    }

    @Test
    fun summaryLines() {
        val base = TranscriptionState(running = true, noteId = "n", modelName = "Tiny (English-only)")
        assertEquals("Loading Tiny (English-only)…", base.summary)
        assertEquals("Preparing…", TranscriptionState(running = true).summary)
        val running = base.copy(
            stage = TranscriptionStage.TRANSCRIBING, progress = 0.45f,
            positionMs = 161_000, totalMs = 360_000, remainingMs = 150_000,
        )
        assertEquals("45% · 2:41 of 6:00 · about 3 min left", running.summary)
        assertEquals("Finishing…", running.copy(stage = TranscriptionStage.FINISHING).summary)
        assertEquals("less than a minute left", Format.remaining(59_000))
        assertEquals("about 1 h 5 min left", Format.remaining(65 * 60_000L))
    }
}
