package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.models.ModelHeader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Imported model headers that would make whisper.cpp abort are rejected before it reads them. */
class ModelHeaderTest {

    /** Defaults are the header of ggml-tiny.en.bin (f16). */
    private fun header(
        magic: Int = 0x67676d6c,
        nVocab: Int = 51864,
        nAudioCtx: Int = 1500,
        nAudioState: Int = 384,
        nAudioHead: Int = 6,
        nAudioLayer: Int = 4,
        nTextCtx: Int = 448,
        nTextState: Int = 384,
        nTextHead: Int = 6,
        nTextLayer: Int = 4,
        nMels: Int = 80,
        ftype: Int = 1,
    ): ByteArray = ByteBuffer.allocate(ModelHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(magic)
        listOf(nVocab, nAudioCtx, nAudioState, nAudioHead, nAudioLayer, nTextCtx, nTextState, nTextHead, nTextLayer, nMels)
            .forEach { putInt(it) }
        putInt(ftype)
    }.array()

    @Test
    fun acceptsRealModelHeaders() {
        assertTrue(ModelHeader.isSane(header())) // tiny.en, f16
        assertTrue(ModelHeader.isSane(header(ftype = 0))) // f32
        assertTrue(ModelHeader.isSane(header(ftype = 2009))) // q5_1, quantization version 2
        assertTrue(
            ModelHeader.isSane( // large-v3-turbo, q5_0
                header(
                    nVocab = 51866, nAudioState = 1280, nAudioHead = 20, nAudioLayer = 32,
                    nTextState = 1280, nTextHead = 20, nTextLayer = 4, nMels = 128, ftype = 1008,
                ),
            ),
        )
    }

    @Test
    fun rejectsWeightTypesThatAbortWhisperCpp() {
        for (ftype in listOf(4, 5, 6, 29, 999, -1, 1004)) {
            assertFalse("ftype $ftype", ModelHeader.isSane(header(ftype = ftype)))
        }
    }

    @Test
    fun rejectsNonsenseSizes() {
        assertFalse(ModelHeader.isSane(header(nAudioLayer = 0)))
        assertFalse(ModelHeader.isSane(header(nAudioState = -384)))
        assertFalse(ModelHeader.isSane(header(nVocab = Int.MAX_VALUE)))
    }

    @Test
    fun rejectsOtherFormatsAndShortFiles() {
        assertFalse(ModelHeader.isSane(header(magic = 0x46554747))) // "GGUF"
        assertFalse(ModelHeader.isSane(header().copyOf(20)))
        val short = File.createTempFile("model", ".bin").apply {
            writeBytes(ByteArray(10))
            deleteOnExit()
        }
        assertFalse(ModelHeader.isSane(short))
    }

    @Test
    fun acceptsTheTestModelsShippedWithWhisperCpp() {
        // Small vocab-only files from the whisper.cpp submodule, with genuine model headers.
        val files = File("src/main/cpp/whisper.cpp/models")
            .listFiles { f -> f.name.matches(Regex("""for-tests-ggml-(tiny|base|small|medium|large).*\.bin""")) }
            .orEmpty()
        assumeTrue("whisper.cpp submodule isn't checked out", files.isNotEmpty())
        for (f in files) assertTrue(f.name, ModelHeader.isSane(f))
    }
}
