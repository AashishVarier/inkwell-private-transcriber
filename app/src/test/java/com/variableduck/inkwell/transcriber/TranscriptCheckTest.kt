package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.transcribe.ChunkedTranscriber
import com.variableduck.inkwell.transcriber.transcribe.TextCleaner
import com.variableduck.inkwell.transcriber.transcribe.TranscriptCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCheckTest {

    @Test
    fun detectsAndRemovesForeignLanguageTags() {
        // Outputs seen from tiny.en on Malayalam and German speech, plus common variants.
        listOf(
            " (speaking in foreign language)",
            " (singing in foreign language)",
            " [FOREIGN LANGUAGE]",
            "(speaks foreign language)",
            "*foreign language*",
            "[Non-English speech]",
        ).forEach { raw ->
            assertTrue(raw, TextCleaner.mentionsForeignLanguage(raw))
            assertEquals(raw, "", TextCleaner.clean(raw, removeTags = true))
            assertFalse(raw, TextCleaner.hasWords(raw))
        }
        assertFalse(TextCleaner.mentionsForeignLanguage("I studied a foreign language (French) at school."))
        assertEquals(
            "I studied a foreign language (French) at school.",
            TextCleaner.clean("I studied a foreign language (French) at school.", removeTags = true),
        )
        assertFalse(TextCleaner.hasWords(" [Music] [BLANK_AUDIO] ♪ "))
        assertTrue(TextCleaner.hasWords("[Music] Hello."))
    }

    @Test
    fun detectsRepetitionLoops() {
        // tiny.en on Malayalam speech.
        assertTrue(
            TextCleaner.looksRepetitive(
                " We will be able to do this. We will be able to do this. We will be able to do this." +
                    " We will be able to do this.",
            ),
        )
        // Normal dictation, including a repeated short phrase and a sentence said twice.
        assertFalse(TextCleaner.looksRepetitive("Yes. Yes. Yes. No. Chapter one. It was late."))
        assertFalse(TextCleaner.looksRepetitive("Let me say that again. Let me say that again. Then I left."))
        assertFalse(TextCleaner.looksRepetitive(""))
    }

    private fun check(
        hasWords: Boolean,
        speech: Int,
        foreign: Int,
        multilingual: Boolean = false,
        language: String = "en",
        repetitive: Int = 0,
    ) = TranscriptCheck.evaluate(
        hasWords = hasWords,
        run = ChunkedTranscriber.Result(speechChunks = speech, foreignChunks = foreign, repetitiveChunks = repetitive),
        modelName = if (multilingual) "Tiny (Multilingual)" else "Tiny (English-only)",
        multilingual = multilingual,
        language = language,
    )

    @Test
    fun suggestsAnotherModelWhenNothingUsableCameOut() {
        assertEquals(TranscriptCheck.Ok, check(hasWords = true, speech = 4, foreign = 0))

        // English-only model, non-English speech: nothing at all, or parts left out.
        val empty = check(hasWords = false, speech = 4, foreign = 3)
        assertTrue(empty is TranscriptCheck.Empty)
        assertTrue((empty as TranscriptCheck.Empty).message.contains("multilingual model"))
        val partly = check(hasWords = true, speech = 4, foreign = 1)
        assertTrue(partly is TranscriptCheck.Incomplete)
        assertTrue((partly as TranscriptCheck.Incomplete).message.contains("multilingual model"))

        // Multilingual model fixed to one language: point at the Language setting instead.
        val fixed = check(hasWords = false, speech = 2, foreign = 2, multilingual = true, language = "de")
        assertTrue((fixed as TranscriptCheck.Empty).message.contains("German"))
        assertTrue(fixed.message.contains("Auto-detect"))

        // Speech but no words (e.g. only [Music] or [BLANK_AUDIO]): still suggest another model.
        val noWords = check(hasWords = false, speech = 2, foreign = 0)
        assertTrue((noWords as TranscriptCheck.Empty).message.contains("multilingual model"))

        // Text came out, but the model looped on a sentence somewhere.
        val looped = check(hasWords = true, speech = 4, foreign = 0, repetitive = 1)
        assertTrue((looped as TranscriptCheck.Incomplete).message.contains("multilingual model"))

        // A silent recording is not the model's fault.
        val silent = check(hasWords = false, speech = 0, foreign = 0)
        assertEquals(TranscriptCheck.Empty("No speech was found in this recording."), silent)
    }
}
