package com.variableduck.inkwell.transcriber.whisper

import android.content.Context

/** Raw JNI bindings to whisper.cpp (see src/main/cpp/inkwell_jni.cpp). */
object WhisperLib {

    @Volatile
    private var systemInfo: String? = null

    /**
     * Loads the native libraries and the best ggml CPU backend for this phone.
     * Safe to call repeatedly; returns whisper.cpp's system info string.
     */
    @Synchronized
    fun ensureLoaded(context: Context): String {
        systemInfo?.let { return it }
        System.loadLibrary("inkwell")
        val info = nativeInit(context.applicationInfo.nativeLibraryDir)
        systemInfo = info
        return info
    }

    @JvmStatic external fun nativeInit(nativeLibDir: String): String
    @JvmStatic external fun nativeDeviceCount(): Int
    @JvmStatic external fun nativeInitContext(modelPath: String): Long
    @JvmStatic external fun nativeFreeContext(ctx: Long)
    @JvmStatic external fun nativeIsMultilingual(ctx: Long): Boolean
    @JvmStatic external fun nativeSetAbort(abort: Boolean)
    @JvmStatic external fun nativeTranscribe(
        ctx: Long,
        audio: FloatArray,
        threads: Int,
        language: String,
        prompt: String,
    ): Int
    @JvmStatic external fun nativeGetText(ctx: Long): ByteArray
    @JvmStatic external fun nativeVersion(): String
}
