package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.transcribe.ChunkPlanner
import com.variableduck.inkwell.transcriber.transcribe.TextCleaner
import com.variableduck.inkwell.transcriber.whisper.CpuInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingTest {
    private val rate = 16_000

    @Test
    fun cutsAtTheQuietestMomentNearTheEnd() {
        val window = ShortArray(ChunkPlanner.MAX_CHUNK_SAMPLES) { 3000 }
        // A pause between 27.0 s and 27.3 s.
        for (i in 27 * rate until 27 * rate + rate * 3 / 10) window[i] = 0
        val len = ChunkPlanner.chunkLength(window, window.size, isLast = false)
        assertTrue("cut at $len", len in 27 * rate..27 * rate + rate * 3 / 10)
    }

    @Test
    fun lastChunkUsesEverything() {
        val window = ShortArray(ChunkPlanner.MAX_CHUNK_SAMPLES) { 3000 }
        assertEquals(12_345, ChunkPlanner.chunkLength(window, 12_345, isLast = true))
        assertEquals(window.size, ChunkPlanner.chunkLength(window, window.size, isLast = true))
    }

    @Test
    fun cutNeverShorterThanSearchWindowAllows() {
        val window = ShortArray(ChunkPlanner.MAX_CHUNK_SAMPLES) { 3000 }
        val len = ChunkPlanner.chunkLength(window, window.size, isLast = false)
        assertTrue(len >= ChunkPlanner.MAX_CHUNK_SAMPLES - ChunkPlanner.SEARCH_SAMPLES)
        assertTrue(len <= ChunkPlanner.MAX_CHUNK_SAMPLES)
    }

    @Test
    fun skipsLongLeadingSilenceButKeepsAShortMargin() {
        val w = ShortArray(30 * rate)
        for (i in 10 * rate until w.size) w[i] = 3000
        assertEquals(10 * rate - ChunkPlanner.LEAD_MARGIN_SAMPLES, ChunkPlanner.leadingSilence(w, w.size))
        // Under a second of leading silence is left alone.
        val short = ShortArray(rate) { if (it > rate / 2) 3000 else 0 }
        assertEquals(0, ChunkPlanner.leadingSilence(short, short.size))
        // All silent: skip everything.
        assertEquals(500, ChunkPlanner.leadingSilence(ShortArray(500), 500))
    }

    @Test
    fun silenceDetectionAndPadding() {
        assertTrue(ChunkPlanner.isSilent(ShortArray(1000) { 20 }, 1000))
        assertFalse(ChunkPlanner.isSilent(ShortArray(1000) { if (it == 500) 5000 else 0 }, 1000))
        val f = ChunkPlanner.toFloat(ShortArray(100) { 16384 }, 100)
        assertEquals(ChunkPlanner.MIN_WHISPER_SAMPLES, f.size)
        assertEquals(0.5f, f[0], 1e-6f)
        assertEquals(0f, f[f.size - 1], 0f)
    }

    @Test
    fun removesNonSpeechTagsButKeepsPunctuation() {
        assertEquals(
            "Hello, world. It's \"fine\": really; yes!",
            TextCleaner.clean(" [BLANK_AUDIO] Hello, world. (music) It's \"fine\": really; yes! ♪ ", removeTags = true),
        )
        assertEquals("[BLANK_AUDIO] Hello.", TextCleaner.clean(" [BLANK_AUDIO]  Hello. ", removeTags = false))
        // Ordinary parenthetical dictation survives.
        assertEquals("She left (finally) at noon.", TextCleaner.clean("She left (finally) at noon.", true))
    }

    @Test
    fun joiningAndPromptTail() {
        assertEquals("Next.", TextCleaner.joinPiece("", "Next."))
        assertEquals(" Next.", TextCleaner.joinPiece("end.", "Next."))
        assertEquals("Next.", TextCleaner.joinPiece("end. ", "Next."))
        assertEquals("", TextCleaner.joinPiece("end.", ""))
        val long = "word ".repeat(100).trim()
        val tail = TextCleaner.promptTail(long, 50)
        assertTrue(tail.length <= 50)
        assertTrue(tail.startsWith("word"))
    }

    @Test
    fun performanceCoreCounting() {
        assertEquals(4, CpuInfo.countPerformanceCores(listOf(1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_400_000, 2_400_000, 2_400_000, 3_000_000)))
        assertEquals(4, CpuInfo.countPerformanceCores(List(4) { 2_000_000L }))
        assertEquals(8, CpuInfo.countPerformanceCores(List(12) { 2_000_000L }))
        assertEquals(null, CpuInfo.countPerformanceCores(emptyList()))
    }
}
