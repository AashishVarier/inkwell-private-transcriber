package com.variableduck.inkwell.transcriber.transcribe

import com.variableduck.inkwell.transcriber.audio.WavReader
import com.variableduck.inkwell.transcriber.data.NoteRepository
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The long-audio transcription loop, independent of Android so it can be tested on the JVM.
 *
 * Reads the note's WAV from disk ~30 s at a time, transcribes each chunk with the tail of the
 * text so far as the prompt, and appends the result with a checkpoint. Resumes from the last
 * checkpoint if the note was interrupted before.
 */
class ChunkedTranscriber(private val notes: NoteRepository) {

    /** What this run saw, used to explain an empty or incomplete transcript. */
    data class Result(
        /** Chunks with sound that were given to Whisper. */
        val speechChunks: Int = 0,
        /** Chunks where Whisper said the speech was in a language it doesn't transcribe. */
        val foreignChunks: Int = 0,
        /** Chunks where Whisper repeated one sentence over and over (it didn't understand). */
        val repetitiveChunks: Int = 0,
    )

    /**
     * @param transcribeChunk runs Whisper on 16 kHz float samples with the given prompt.
     * @param onProgress called after each chunk with progress 0..1.
     */
    suspend fun run(
        id: String,
        removeTags: Boolean,
        transcribeChunk: suspend (samples: FloatArray, prompt: String) -> String,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val prepared = notes.prepareTranscript(id, restart = false) ?: return Result()
        var speechChunks = 0
        var foreignChunks = 0
        var repetitiveChunks = 0
        WavReader(notes.audioFile(id)).use { reader ->
            val total = reader.totalSamples
            var position = prepared.doneSamples.coerceIn(0, total)
            val window = ShortArray(ChunkPlanner.MAX_CHUNK_SAMPLES)
            while (position < total) {
                coroutineContext.ensureActive()
                val count = reader.read(position, window.size, window)
                if (count <= 0) break

                val silence = ChunkPlanner.leadingSilence(window, count)
                if (silence > 0) {
                    // Skip the pause and read a fresh window starting just before the speech.
                    position += silence
                    val progress = position.toFloat() / total
                    notes.appendChunk(id, "", position, progress)
                    onProgress(progress)
                    continue
                }

                val isLast = position + count >= total
                val length = ChunkPlanner.chunkLength(window, count, isLast)

                var piece = ""
                if (!ChunkPlanner.isSilent(window, length)) {
                    val prompt = TextCleaner.promptTail(notes.textTail(id, PROMPT_READ_CHARS))
                    val raw = transcribeChunk(ChunkPlanner.toFloat(window, length), prompt)
                    coroutineContext.ensureActive()
                    speechChunks++
                    if (TextCleaner.mentionsForeignLanguage(raw)) foreignChunks++
                    if (TextCleaner.looksRepetitive(raw)) repetitiveChunks++
                    val cleaned = TextCleaner.clean(raw, removeTags)
                    piece = TextCleaner.joinPiece(notes.textTail(id, 1), cleaned)
                }
                position += length
                val progress = if (total > 0) position.toFloat() / total else 1f
                notes.appendChunk(id, piece, position, progress)
                onProgress(progress)
            }
        }
        return Result(speechChunks, foreignChunks, repetitiveChunks)
    }

    private companion object {
        const val PROMPT_READ_CHARS = 400
    }
}
