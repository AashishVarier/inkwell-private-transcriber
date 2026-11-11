package com.variableduck.inkwell.transcriber.transcribe

import com.variableduck.inkwell.transcriber.audio.Wav
import kotlin.math.roundToLong

/**
 * Smooth progress for a transcription. Whisper reports nothing while it works on a ~30 s chunk,
 * which can take a minute with a large model, so the position inside the current chunk is
 * estimated from how long earlier chunks took. It moves at the expected speed up to
 * [LINEAR_UNTIL] of the chunk, then keeps creeping ever more slowly towards [MAX_CHUNK_FRACTION]
 * so it never looks frozen when a chunk takes longer than expected. It reaches the chunk's end
 * only when the chunk is really done, and progress never goes backwards.
 * Thread-safe: fed by the transcription loop, read by a UI ticker.
 */
class ProgressEstimator(
    /** Compute time per second of audio to assume until a chunk has been timed. */
    private val guessMsPerAudioSecond: Double = DEFAULT_GUESS_MS_PER_AUDIO_SECOND,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ChunkedTranscriber.Listener {

    private var total = 0L
    private var done = 0L
    private var chunkStart = -1L // -1 when Whisper isn't working on a chunk
    private var chunkLength = 0
    private var chunkStartedAt = 0L
    private var timedMs = 0L
    private var timedSamples = 0L
    private var lastProgress = 0f

    /** Measured compute time per second of audio, or null until a chunk has been timed. */
    @get:Synchronized
    val msPerAudioSecond: Double?
        get() = if (timedSamples > 0) timedMs * Wav.SAMPLE_RATE.toDouble() / timedSamples else null

    @get:Synchronized
    val totalSamples: Long get() = total

    @Synchronized
    override fun started(doneSamples: Long, totalSamples: Long) {
        total = totalSamples
        done = doneSamples
        chunkStart = -1
    }

    @Synchronized
    override fun chunkStarted(startSample: Long, length: Int) {
        chunkStart = startSample
        chunkLength = length
        chunkStartedAt = clock()
    }

    @Synchronized
    override fun advanced(doneSamples: Long, transcribed: Boolean) {
        if (transcribed && chunkStart >= 0) {
            timedMs += clock() - chunkStartedAt
            timedSamples += chunkLength
        }
        done = doneSamples
        chunkStart = -1
    }

    /** Estimated audio position reached, in samples. */
    @Synchronized
    fun position(): Long {
        if (chunkStart < 0) return done
        val rate = msPerAudioSecond ?: guessMsPerAudioSecond
        val expectedMs = rate * chunkLength / Wav.SAMPLE_RATE
        val r = if (expectedMs <= 0) 0.0 else ((clock() - chunkStartedAt) / expectedMs).coerceAtLeast(0.0)
        val fraction = if (r <= LINEAR_UNTIL) {
            r
        } else {
            LINEAR_UNTIL + (MAX_CHUNK_FRACTION - LINEAR_UNTIL) * (1 - 1 / (1 + 2 * (r - LINEAR_UNTIL)))
        }
        return chunkStart + (fraction * chunkLength).toLong()
    }

    /** Progress 0..1; never decreases. */
    @Synchronized
    fun progress(): Float {
        if (total <= 0) return lastProgress
        lastProgress = maxOf(lastProgress, (position().toFloat() / total).coerceIn(0f, 1f))
        return lastProgress
    }

    /** Estimated time left, or null until a chunk has been timed on this phone. */
    @Synchronized
    fun remainingMs(): Long? {
        val rate = msPerAudioSecond ?: return null
        return ((total - position()).coerceAtLeast(0) * rate / Wav.SAMPLE_RATE).roundToLong()
    }

    companion object {
        const val LINEAR_UNTIL = 0.75
        const val MAX_CHUNK_FRACTION = 0.97
        /** Twice as fast as real time: between Tiny and Small on a typical phone. */
        const val DEFAULT_GUESS_MS_PER_AUDIO_SECOND = 500.0
    }
}
