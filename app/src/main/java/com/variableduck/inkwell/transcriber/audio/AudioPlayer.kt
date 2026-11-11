package com.variableduck.inkwell.transcriber.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * Plays a note's recording with the platform MediaPlayer. Playback pauses when another app
 * takes audio focus (e.g. a call) or headphones are unplugged, so a private recording never
 * suddenly plays out of the speaker. Every method must be called on the main thread.
 */
class AudioPlayer(context: Context, private val scope: CoroutineScope) {

    data class State(
        val playing: Boolean = false,
        val positionMs: Long = 0,
        /** 0 until the recording has been opened. */
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) pause() }, Handler(Looper.getMainLooper()))
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pause()
    }
    private var noisyRegistered = false

    private var player: MediaPlayer? = null
    private var loadedFile: File? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun toggle(file: File) {
        if (_state.value.playing) pause() else play(file)
    }

    fun play(file: File) {
        val p = open(file) ?: return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _state.update { it.copy(error = "Can't play right now: another app is using audio.") }
            return
        }
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        p.start()
        _state.update { it.copy(playing = true, error = null) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                player?.let { mp -> _state.update { it.copy(positionMs = mp.currentPosition.toLong()) } }
                delay(200)
            }
        }
    }

    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        stopped()
    }

    /** Moves to [positionMs], opening the recording if needed (without starting playback). */
    fun seekTo(file: File, positionMs: Long) {
        val p = open(file) ?: return
        val target = positionMs.coerceIn(0, p.duration.toLong().coerceAtLeast(0))
        p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _state.update { it.copy(positionMs = target) }
    }

    fun skip(file: File, deltaMs: Long) = seekTo(file, _state.value.positionMs + deltaMs)

    /** Stops playback and frees the player. */
    fun release() {
        if (_state.value.playing) stopped()
        ticker?.cancel()
        player?.release()
        player = null
        loadedFile = null
        _state.value = State()
    }

    private fun open(file: File): MediaPlayer? {
        player?.let { if (loadedFile == file) return it }
        release()
        return try {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                FileInputStream(file).use { setDataSource(it.fd) }
                prepare()
                setOnCompletionListener {
                    stopped()
                    seekTo(0)
                    _state.update { s -> s.copy(positionMs = 0) }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Playback error $what/$extra")
                    release()
                    _state.value = State(error = "Playback stopped because of an error.")
                    true
                }
            }.also {
                player = it
                loadedFile = file
                _state.value = State(durationMs = it.duration.toLong())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't open ${file.name}", e)
            _state.value = State(error = "This recording can't be played.")
            null
        }
    }

    /** Bookkeeping when playback stops for any reason. */
    private fun stopped() {
        ticker?.cancel()
        ticker = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (noisyRegistered) {
            appContext.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        _state.update { it.copy(playing = false, positionMs = player?.currentPosition?.toLong() ?: it.positionMs) }
    }

    private companion object {
        const val TAG = "AudioPlayer"
    }
}
