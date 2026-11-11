package com.variableduck.inkwell.transcriber.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** 16 kHz mono 16-bit PCM WAV helpers. */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2
    const val HEADER_SIZE = 44
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

    private const val MAX_U32 = 0xFFFF_FFFFL

    /** Canonical 44-byte PCM header. Sizes are clamped to 4 GiB (about 37 hours of audio). */
    fun header(dataBytes: Long): ByteArray {
        val b = ByteArray(HEADER_SIZE)
        fun str(off: Int, s: String) = s.forEachIndexed { i, c -> b[off + i] = c.code.toByte() }
        fun u32(off: Int, v: Long) {
            val x = v.coerceIn(0, MAX_U32)
            b[off] = (x and 0xFF).toByte()
            b[off + 1] = (x shr 8 and 0xFF).toByte()
            b[off + 2] = (x shr 16 and 0xFF).toByte()
            b[off + 3] = (x shr 24 and 0xFF).toByte()
        }
        fun u16(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = (v shr 8 and 0xFF).toByte()
        }
        str(0, "RIFF")
        u32(4, 36 + dataBytes)
        str(8, "WAVE")
        str(12, "fmt ")
        u32(16, 16)
        u16(20, 1) // PCM
        u16(22, CHANNELS)
        u32(24, SAMPLE_RATE.toLong())
        u32(28, BYTES_PER_SECOND.toLong())
        u16(32, CHANNELS * BYTES_PER_SAMPLE)
        u16(34, BITS_PER_SAMPLE)
        str(36, "data")
        u32(40, dataBytes)
        return b
    }

    fun durationMs(dataBytes: Long): Long = dataBytes * 1000 / BYTES_PER_SECOND

    /**
     * Makes a WAV written by [WavWriter] valid after a crash: rewrites the size fields from the
     * real file length and drops a trailing odd byte. Returns the audio length in bytes.
     */
    fun repair(file: File): Long {
        RandomAccessFile(file, "rw").use { raf ->
            var length = raf.length()
            if (length < HEADER_SIZE) {
                raf.setLength(0)
                raf.write(header(0))
                return 0
            }
            var dataBytes = length - HEADER_SIZE
            if (dataBytes % BYTES_PER_SAMPLE != 0L) {
                dataBytes -= 1
                length -= 1
                raf.setLength(length)
            }
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.fd.sync()
            return dataBytes
        }
    }

    internal fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    internal fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}

/**
 * Streams PCM straight to disk; nothing is held in memory. The header is rewritten with the
 * current size every time [syncHeader] is called, so a crash loses at most the last moments.
 */
class WavWriter(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw")

    var dataBytes: Long = 0
        private set

    init {
        raf.setLength(0)
        raf.write(Wav.header(0))
    }

    fun write(buffer: ByteArray, length: Int) {
        raf.write(buffer, 0, length)
        dataBytes += length
    }

    /** Updates the size fields in the header and flushes to storage. */
    fun syncHeader() {
        val end = raf.filePointer
        raf.seek(0)
        raf.write(Wav.header(dataBytes), 0, Wav.HEADER_SIZE)
        raf.seek(end)
        raf.fd.sync()
    }

    override fun close() {
        try {
            syncHeader()
        } finally {
            raf.close()
        }
    }
}

class UnsupportedWavException(message: String) : IOException(message)

/** Random access to the samples of a 16 kHz mono 16-bit WAV, read from disk on demand. */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val dataOffset: Long
    val totalSamples: Long
    private var byteBuffer = ByteArray(0)

    init {
        try {
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) throw UnsupportedWavException("Not a WAV file")

            var pos = 12L
            var foundFmt = false
            var offset = -1L
            var declared = 0L
            val chunk = ByteArray(8)
            val fileLength = raf.length()
            while (pos + 8 <= fileLength) {
                raf.seek(pos)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = Wav.readU32(chunk, 4)
                if (id == "fmt ") {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    val format = Wav.readU16(fmt, 0)
                    val channels = Wav.readU16(fmt, 2)
                    val rate = Wav.readU32(fmt, 4)
                    val bits = Wav.readU16(fmt, 14)
                    if (format != 1 || channels != 1 || rate != Wav.SAMPLE_RATE.toLong() || bits != 16) {
                        throw UnsupportedWavException("Expected 16 kHz mono 16-bit PCM")
                    }
                    foundFmt = true
                } else if (id == "data") {
                    offset = pos + 8
                    declared = size
                    break
                }
                pos += 8 + size + (size and 1)
            }
            if (!foundFmt || offset < 0) throw UnsupportedWavException("WAV has no audio data")
            val available = (fileLength - offset).coerceAtLeast(0)
            // A declared size of 0 or 0xFFFFFFFF (or past the end) means the header wasn't
            // finalised; trust the file length instead.
            val dataBytes = if (declared in 1..available && declared != 0xFFFF_FFFFL) declared else available
            dataOffset = offset
            totalSamples = dataBytes / Wav.BYTES_PER_SAMPLE
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    /** Reads up to [count] samples starting at [startSample] into [out]. Returns samples read. */
    fun read(startSample: Long, count: Int, out: ShortArray): Int {
        val n = minOf(count.toLong(), totalSamples - startSample, out.size.toLong()).toInt()
        if (n <= 0) return 0
        val bytes = n * Wav.BYTES_PER_SAMPLE
        if (byteBuffer.size < bytes) byteBuffer = ByteArray(bytes)
        raf.seek(dataOffset + startSample * Wav.BYTES_PER_SAMPLE)
        raf.readFully(byteBuffer, 0, bytes)
        for (i in 0 until n) {
            out[i] = ((byteBuffer[2 * i].toInt() and 0xFF) or (byteBuffer[2 * i + 1].toInt() shl 8)).toShort()
        }
        return n
    }

    override fun close() = raf.close()
}
