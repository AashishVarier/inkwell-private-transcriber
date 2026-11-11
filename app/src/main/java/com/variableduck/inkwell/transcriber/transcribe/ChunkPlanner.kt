package com.variableduck.inkwell.transcriber.transcribe

import com.variableduck.inkwell.transcriber.audio.Wav
import kotlin.math.abs

/**
 * Splits long audio into chunks of about 30 s (Whisper's window). Each chunk ends at the
 * quietest moment in its last few seconds, so words are not cut in half and no overlap
 * (and no duplicated text) is needed.
 */
object ChunkPlanner {
    const val MAX_CHUNK_SAMPLES = 30 * Wav.SAMPLE_RATE
    /** How far back from the 30 s mark to look for a pause. */
    const val SEARCH_SAMPLES = 6 * Wav.SAMPLE_RATE
    const val FRAME_SAMPLES = Wav.SAMPLE_RATE / 20 // 50 ms
    /** Whisper needs at least 1 s of input; shorter chunks are padded with silence. */
    const val MIN_WHISPER_SAMPLES = Wav.SAMPLE_RATE + Wav.SAMPLE_RATE / 10

    /** A chunk whose loudest sample is below this (about -46 dBFS) is silence and skipped. */
    const val SILENCE_PEAK = 164

    /**
     * Given [count] samples read from the chunk start, returns how many to transcribe now.
     * If [isLast] (the window reaches the end of the audio) all of it is used.
     */
    fun chunkLength(window: ShortArray, count: Int, isLast: Boolean): Int {
        if (isLast || count < MAX_CHUNK_SAMPLES) return count
        val searchStart = (count - SEARCH_SAMPLES).coerceAtLeast(0)
        var bestEnd = count
        var bestEnergy = Long.MAX_VALUE
        var frameStart = searchStart
        while (frameStart + FRAME_SAMPLES <= count) {
            var energy = 0L
            for (i in frameStart until frameStart + FRAME_SAMPLES) energy += abs(window[i].toInt())
            // "<=" prefers the latest of equally quiet frames, keeping chunks long.
            if (energy <= bestEnergy) {
                bestEnergy = energy
                bestEnd = frameStart + FRAME_SAMPLES / 2
            }
            frameStart += FRAME_SAMPLES / 2
        }
        return bestEnd.coerceIn(1, count)
    }

    fun isSilent(samples: ShortArray, count: Int): Boolean {
        for (i in 0 until count) if (abs(samples[i].toInt()) >= SILENCE_PEAK) return false
        return true
    }

    /** Converts PCM16 to the float format whisper.cpp expects, padding very short chunks. */
    fun toFloat(samples: ShortArray, count: Int): FloatArray {
        val out = FloatArray(maxOf(count, MIN_WHISPER_SAMPLES))
        for (i in 0 until count) out[i] = samples[i] / 32768f
        return out
    }
}

object TextCleaner {
    // [BLANK_AUDIO], [MUSIC], [ Silence ], [inaudible] ...
    private val bracketTags = Regex("""\[[^\]\n]{1,40}]""")
    // (music), (silence), *laughs* ... only well-known non-speech words, so real
    // parenthetical text in dictation is kept.
    private val nonSpeech = Regex(
        """[(*]\s*(?:music|silence|blank[ _]audio|noise|background noise|applause|laughter|laughs|""" +
            """laughing|inaudible|static|wind|no speech|speaking in foreign language|foreign language|""" +
            """sighs|coughs|clears throat|beep|music playing)\s*[)*]""",
        RegexOption.IGNORE_CASE,
    )
    private val musicSymbols = Regex("[♪♫♬♩]+")
    private val spaces = Regex("[ \\t]{2,}")

    /** Tidies one chunk of Whisper output. Punctuation and capitalisation are left as they are. */
    fun clean(raw: String, removeTags: Boolean): String {
        var t = raw
        if (removeTags) {
            t = bracketTags.replace(t, " ")
            t = nonSpeech.replace(t, " ")
            t = musicSymbols.replace(t, " ")
        }
        return spaces.replace(t, " ").trim()
    }

    /** Text to append after [previousTail] so chunks join with exactly one space. */
    fun joinPiece(previousTail: String, chunk: String): String {
        if (chunk.isEmpty()) return ""
        return if (previousTail.isEmpty() || previousTail.last().isWhitespace()) chunk else " $chunk"
    }

    /**
     * The end of the previous text, used as Whisper's initial prompt so the next chunk
     * continues the sentence (and keeps names/spelling consistent). Starts at a word boundary.
     */
    fun promptTail(text: String, maxChars: Int = 200): String {
        if (text.length <= maxChars) return text.trim()
        val tail = text.takeLast(maxChars)
        val firstSpace = tail.indexOf(' ')
        return (if (firstSpace in 0 until tail.length - 1) tail.substring(firstSpace + 1) else tail).trim()
    }
}
