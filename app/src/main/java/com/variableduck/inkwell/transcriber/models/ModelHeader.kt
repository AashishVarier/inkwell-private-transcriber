package com.variableduck.inkwell.transcriber.models

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks the fixed-size header of a whisper.cpp GGML model before native code reads it.
 *
 * whisper.cpp rejects most broken files cleanly, but an unknown weight type (ftype) or nonsense
 * sizes reach GGML_ASSERT, which aborts the whole app instead of returning an error. Files that
 * pass here still go through whisper.cpp's own checks when they are test-loaded.
 */
object ModelHeader {
    /** Magic, ten size parameters and ftype: eleven little-endian int32 after the magic. */
    const val SIZE = 48

    /** "ggml" as whisper.cpp writes it. */
    private const val MAGIC = 0x67676d6c

    /** Generous bound for every size parameter; the largest real one is n_vocab = 51866. */
    private const val MAX_PARAM = 1 shl 17

    /** ggml stores ftype as quantization version * 1000 + type. */
    private const val QNT_VERSION_FACTOR = 1000

    fun isSane(file: File): Boolean {
        if (file.length() < SIZE) return false
        val header = ByteArray(SIZE)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        return isSane(header)
    }

    fun isSane(header: ByteArray): Boolean {
        if (header.size < SIZE) return false
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != MAGIC) return false
        // n_vocab, n_audio_ctx, n_audio_state, n_audio_head, n_audio_layer,
        // n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels
        for (offset in 4..40 step 4) {
            if (b.getInt(offset) !in 1..MAX_PARAM) return false
        }
        val type = b.getInt(44) % QNT_VERSION_FACTOR
        // The ggml_ftype values that ggml_ftype_to_ggml_type() accepts (4, 5 and 6 abort).
        return type in 0..3 || type in 7..28
    }
}
