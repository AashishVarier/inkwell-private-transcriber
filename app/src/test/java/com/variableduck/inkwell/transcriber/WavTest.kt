package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.audio.Wav
import com.variableduck.inkwell.transcriber.audio.WavReader
import com.variableduck.inkwell.transcriber.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class WavTest {
    private fun tempWav() = File.createTempFile("inkwell", ".wav").apply { deleteOnExit() }

    private fun pcm(samples: ShortArray): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            b[2 * i] = (s.toInt() and 0xFF).toByte()
            b[2 * i + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun writeThenReadBack() {
        val f = tempWav()
        val samples = ShortArray(16_000) { (it % 2000 - 1000).toShort() }
        WavWriter(f).use { w -> pcm(samples).let { w.write(it, it.size) } }
        assertEquals(44L + 32_000, f.length())
        WavReader(f).use { r ->
            assertEquals(16_000L, r.totalSamples)
            val out = ShortArray(100)
            assertEquals(100, r.read(500, 100, out))
            for (i in 0 until 100) assertEquals(samples[500 + i], out[i])
            // Reading past the end returns only what exists.
            assertEquals(10, r.read(15_990, 100, out))
        }
    }

    @Test
    fun repairFixesHeaderAfterCrash() {
        val f = tempWav()
        // Simulate a crash: header says 0 bytes, file holds 1 s of audio plus an odd byte.
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(Wav.header(0))
            raf.write(ByteArray(32_001))
        }
        val bytes = Wav.repair(f)
        assertEquals(32_000L, bytes)
        assertEquals(44L + 32_000, f.length())
        WavReader(f).use { assertEquals(16_000L, it.totalSamples) }
        assertEquals(1000L, Wav.durationMs(bytes))
    }

    @Test
    fun readerTrustsFileLengthWhenHeaderIsStale() {
        val f = tempWav()
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(Wav.header(0)) // never finalised
            raf.write(ByteArray(6_400))
        }
        WavReader(f).use { assertEquals(3_200L, it.totalSamples) }
    }

    @Test
    fun repairOfTruncatedHeaderGivesEmptyValidFile() {
        val f = tempWav()
        f.writeBytes(ByteArray(10))
        assertEquals(0L, Wav.repair(f))
        WavReader(f).use { assertEquals(0L, it.totalSamples) }
    }
}
