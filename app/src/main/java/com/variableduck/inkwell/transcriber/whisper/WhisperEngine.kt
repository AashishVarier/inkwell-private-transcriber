package com.variableduck.inkwell.transcriber.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class ModelLoadException(message: String) : IOException(message)

/**
 * Owns the loaded whisper context. All native calls on it run on one dedicated thread
 * because a whisper context is not thread-safe.
 */
class WhisperEngine(private val context: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whisper").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var ctx: Long = 0
    private var loadedPath: String? = null

    var isMultilingual: Boolean = false
        private set

    /** Loads [model] unless it is already loaded. */
    suspend fun load(model: File) = withContext(dispatcher) {
        if (ctx != 0L && loadedPath == model.absolutePath) return@withContext
        freeInternal()
        ensureNative(context)
        val handle = WhisperLib.nativeInitContext(model.absolutePath)
        if (handle == 0L) throw ModelLoadException("Couldn't load model ${model.name}")
        ctx = handle
        loadedPath = model.absolutePath
        isMultilingual = WhisperLib.nativeIsMultilingual(handle)
    }

    /** Transcribes one chunk of 16 kHz mono audio and returns the raw text. */
    suspend fun transcribe(samples: FloatArray, threads: Int, language: String, prompt: String): String =
        withContext(dispatcher) {
            check(ctx != 0L) { "No model loaded" }
            val rc = WhisperLib.nativeTranscribe(ctx, samples, threads, language, prompt)
            if (rc != 0) throw IOException("Transcription failed (whisper error $rc)")
            String(WhisperLib.nativeGetText(ctx), Charsets.UTF_8)
        }

    /** Stops a transcription that is running on the native side as soon as possible. */
    fun requestAbort() = WhisperLib.nativeSetAbort(true)

    fun clearAbort() = WhisperLib.nativeSetAbort(false)

    suspend fun release() = withContext(dispatcher) { freeInternal() }

    private fun freeInternal() {
        if (ctx != 0L) {
            WhisperLib.nativeFreeContext(ctx)
            ctx = 0
            loadedPath = null
        }
    }

    companion object {
        /** Loads the native library, or throws a readable error on unsupported (non-arm64) devices. */
        fun ensureNative(context: Context): String = try {
            WhisperLib.ensureLoaded(context)
        } catch (e: UnsatisfiedLinkError) {
            throw ModelLoadException("This device isn't supported (Inkwell needs a 64-bit ARM phone).")
        }

        data class Probe(val multilingual: Boolean)

        /**
         * Checks that [file] is a loadable GGML whisper model, using a separate temporary context.
         * Returns null if it doesn't load.
         */
        suspend fun probe(context: Context, file: File): Probe? = withContext(Dispatchers.IO) {
            ensureNative(context)
            val handle = WhisperLib.nativeInitContext(file.absolutePath)
            if (handle == 0L) return@withContext null
            try {
                Probe(multilingual = WhisperLib.nativeIsMultilingual(handle))
            } finally {
                WhisperLib.nativeFreeContext(handle)
            }
        }
    }
}
