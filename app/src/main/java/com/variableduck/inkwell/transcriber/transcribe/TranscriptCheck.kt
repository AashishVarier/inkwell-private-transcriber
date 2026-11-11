package com.variableduck.inkwell.transcriber.transcribe

import com.variableduck.inkwell.transcriber.data.Languages

/**
 * Decides whether a finished transcription is usable, and if not, what to suggest.
 * The usual cause of an empty transcript is speech in a language the model doesn't know:
 * English-only models write "(speaking in foreign language)" for it, which is then removed.
 */
sealed interface TranscriptCheck {
    /** Transcript looks fine. */
    data object Ok : TranscriptCheck

    /** Transcript has text, but parts were left out. Shown with the transcript. */
    data class Incomplete(val message: String) : TranscriptCheck

    /** Nothing usable came out. The note is marked as failed so it can be tried again. */
    data class Empty(val message: String) : TranscriptCheck

    companion object {
        fun evaluate(
            hasWords: Boolean,
            run: ChunkedTranscriber.Result,
            modelName: String,
            multilingual: Boolean,
            language: String,
        ): TranscriptCheck {
            val foreign = run.foreignChunks > 0
            val fixedLanguage = language != Languages.AUTO
            val expected = if (multilingual) Languages.name(language) else "English"
            return when {
                foreign && (!multilingual || fixedLanguage) -> {
                    val fix = if (multilingual) {
                        "Set Language to Auto-detect on the Models screen"
                    } else {
                        "Choose a multilingual model"
                    }
                    val reason = if (multilingual) {
                        "the Language setting is $expected"
                    } else {
                        "$modelName only understands English"
                    }
                    if (hasWords) {
                        Incomplete(
                            "Parts of this recording don't seem to be in $expected, so they were left out " +
                                "or may be wrong, because $reason. $fix, then transcribe again.",
                        )
                    } else {
                        Empty(
                            "Nothing was transcribed. This recording doesn't seem to be in $expected, " +
                                "and $reason. $fix, then try again.",
                        )
                    }
                }
                hasWords && run.repetitiveChunks > 0 -> Incomplete(
                    "Some parts may be wrong: $modelName repeated the same sentence over and over, which " +
                        "usually means it couldn't understand the speech. " +
                        if (multilingual) {
                            "Check the Language setting, or choose a larger model, then transcribe again."
                        } else {
                            "If parts aren't in English, choose a multilingual model; otherwise a larger " +
                                "model may do better. Then transcribe again."
                        },
                )
                hasWords -> Ok
                run.speechChunks == 0 -> Empty("No speech was found in this recording.")
                multilingual -> Empty(
                    "No words were recognised with $modelName. Check the Language setting, or choose " +
                        "a larger model, then try again.",
                )
                else -> Empty(
                    "No words were recognised with $modelName. If this recording isn't in English, " +
                        "choose a multilingual model; otherwise a larger model may do better. Then try again.",
                )
            }
        }
    }
}
