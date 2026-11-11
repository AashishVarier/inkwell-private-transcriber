package com.variableduck.inkwell.transcriber.data

import android.content.Context
import com.variableduck.inkwell.transcriber.whisper.CpuInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsValues(
    /** File name of the active model in the models folder, or null. */
    val activeModelId: String? = null,
    /** "auto" or a Whisper language code. Only used by multilingual models. */
    val language: String = Languages.AUTO,
    /** Whisper threads; 0 = automatic (number of performance cores). */
    val threads: Int = 0,
    /** Keep the recording after transcription. */
    val keepAudio: Boolean = true,
    /** Remove `[BLANK_AUDIO]` and similar non-speech tags from transcripts. */
    val removeTags: Boolean = true,
) {
    val effectiveThreads: Int
        get() = if (threads > 0) threads else CpuInfo.performanceCores
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsValues> = _state.asStateFlow()
    val value: SettingsValues get() = _state.value

    private fun read() = SettingsValues(
        activeModelId = prefs.getString(KEY_MODEL, null),
        language = prefs.getString(KEY_LANGUAGE, Languages.AUTO) ?: Languages.AUTO,
        threads = prefs.getInt(KEY_THREADS, 0),
        keepAudio = prefs.getBoolean(KEY_KEEP_AUDIO, true),
        removeTags = prefs.getBoolean(KEY_REMOVE_TAGS, true),
    )

    @Synchronized
    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setActiveModel(id: String?) = edit { if (id == null) remove(KEY_MODEL) else putString(KEY_MODEL, id) }
    fun setLanguage(code: String) = edit { putString(KEY_LANGUAGE, code) }
    fun setThreads(threads: Int) = edit { putInt(KEY_THREADS, threads.coerceAtLeast(0)) }
    fun setKeepAudio(keep: Boolean) = edit { putBoolean(KEY_KEEP_AUDIO, keep) }
    fun setRemoveTags(remove: Boolean) = edit { putBoolean(KEY_REMOVE_TAGS, remove) }

    private companion object {
        const val KEY_MODEL = "active_model"
        const val KEY_LANGUAGE = "language"
        const val KEY_THREADS = "threads"
        const val KEY_KEEP_AUDIO = "keep_audio"
        const val KEY_REMOVE_TAGS = "remove_tags"
    }
}

/** Languages Whisper can transcribe (code to English name). */
object Languages {
    const val AUTO = "auto"

    val all: List<Pair<String, String>> = listOf(
        "en" to "English", "zh" to "Chinese", "de" to "German", "es" to "Spanish",
        "ru" to "Russian", "ko" to "Korean", "fr" to "French", "ja" to "Japanese",
        "pt" to "Portuguese", "tr" to "Turkish", "pl" to "Polish", "ca" to "Catalan",
        "nl" to "Dutch", "ar" to "Arabic", "sv" to "Swedish", "it" to "Italian",
        "id" to "Indonesian", "hi" to "Hindi", "fi" to "Finnish", "vi" to "Vietnamese",
        "he" to "Hebrew", "uk" to "Ukrainian", "el" to "Greek", "ms" to "Malay",
        "cs" to "Czech", "ro" to "Romanian", "da" to "Danish", "hu" to "Hungarian",
        "ta" to "Tamil", "no" to "Norwegian", "th" to "Thai", "ur" to "Urdu",
        "hr" to "Croatian", "bg" to "Bulgarian", "lt" to "Lithuanian", "la" to "Latin",
        "mi" to "Maori", "ml" to "Malayalam", "cy" to "Welsh", "sk" to "Slovak",
        "te" to "Telugu", "fa" to "Persian", "lv" to "Latvian", "bn" to "Bengali",
        "sr" to "Serbian", "az" to "Azerbaijani", "sl" to "Slovenian", "kn" to "Kannada",
        "et" to "Estonian", "mk" to "Macedonian", "br" to "Breton", "eu" to "Basque",
        "is" to "Icelandic", "hy" to "Armenian", "ne" to "Nepali", "mn" to "Mongolian",
        "bs" to "Bosnian", "kk" to "Kazakh", "sq" to "Albanian", "sw" to "Swahili",
        "gl" to "Galician", "mr" to "Marathi", "pa" to "Punjabi", "si" to "Sinhala",
        "km" to "Khmer", "sn" to "Shona", "yo" to "Yoruba", "so" to "Somali",
        "af" to "Afrikaans", "oc" to "Occitan", "ka" to "Georgian", "be" to "Belarusian",
        "tg" to "Tajik", "sd" to "Sindhi", "gu" to "Gujarati", "am" to "Amharic",
        "yi" to "Yiddish", "lo" to "Lao", "uz" to "Uzbek", "fo" to "Faroese",
        "ht" to "Haitian Creole", "ps" to "Pashto", "tk" to "Turkmen", "nn" to "Nynorsk",
        "mt" to "Maltese", "sa" to "Sanskrit", "lb" to "Luxembourgish", "my" to "Myanmar",
        "bo" to "Tibetan", "tl" to "Tagalog", "mg" to "Malagasy", "as" to "Assamese",
        "tt" to "Tatar", "haw" to "Hawaiian", "ln" to "Lingala", "ha" to "Hausa",
        "ba" to "Bashkir", "jw" to "Javanese", "su" to "Sundanese", "yue" to "Cantonese",
    ).sortedBy { it.second }

    fun name(code: String): String =
        if (code == AUTO) "Auto-detect" else all.firstOrNull { it.first == code }?.second ?: code
}
