package com.variableduck.inkwell.transcriber.whisper

import android.util.Log
import java.io.File

/**
 * Counts the phone's performance cores, used as the default whisper thread count.
 * Adapted from whisper.cpp/examples/whisper.android WhisperCpuConfig.kt (MIT).
 */
object CpuInfo {
    private const val TAG = "CpuInfo"
    const val FALLBACK_THREADS = 4

    val coreCount: Int get() = Runtime.getRuntime().availableProcessors()

    /** Performance-core count, or [FALLBACK_THREADS] if it can't be determined. */
    val performanceCores: Int by lazy {
        try {
            val freqs = (0 until coreCount).mapNotNull { cpu ->
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            val result = countPerformanceCores(freqs)
            Log.i(TAG, "max freqs=$freqs -> performance cores=$result")
            result ?: FALLBACK_THREADS
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read CPU frequencies", e)
            FALLBACK_THREADS
        }
    }

    /**
     * Cores clocked above the slowest cluster are performance cores. If every core runs at the
     * same speed there is no little cluster, so all of them count (capped at 8).
     */
    fun countPerformanceCores(maxFreqs: List<Long>): Int? {
        if (maxFreqs.isEmpty()) return null
        val min = maxFreqs.min()
        val fast = maxFreqs.count { it > min }
        return (if (fast == 0) maxFreqs.size else fast).coerceIn(1, 8)
    }
}
