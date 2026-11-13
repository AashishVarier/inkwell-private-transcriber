package com.variableduck.inkwell.transcriber.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.variableduck.inkwell.transcriber.R
import com.variableduck.inkwell.transcriber.audio.Wav
import com.variableduck.inkwell.transcriber.audio.WavWriter
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.util.Format
import com.variableduck.inkwell.transcriber.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.log10

/**
 * Records 16 kHz mono 16-bit PCM straight into a WAV file, inside a microphone foreground
 * service, so recording continues with the screen off or the app in the background.
 * There is no time limit; memory use is constant.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    private var finishing = false
    private var stopPending = false
    private var lowStorageNotified = false
    private var noteTitle: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> recorder?.setPaused(true)
            ACTION_RESUME -> recorder?.setPaused(false)
            ACTION_STOP -> recorder?.requestStop() ?: run {
                if (RecordingStatus.state.value.phase == RecorderPhase.STARTING) stopPending = true else stopSelf()
            }
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null || RecordingStatus.state.value.isActive) {
            // Already recording (e.g. a double tap). Every startForegroundService() call must
            // still be answered with startForeground().
            try {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ID_RECORDING,
                    buildNotification(RecordingStatus.state.value),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't refresh foreground state", e)
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            RecordingStatus.update { RecorderState(error = "Microphone permission is needed to record.") }
            stopSelf()
            return
        }
        RecordingStatus.update { RecorderState(phase = RecorderPhase.STARTING) }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_RECORDING,
                buildNotification(RecordingStatus.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start foreground recording", e)
            RecordingStatus.update { RecorderState(error = "Couldn't start recording: ${e.message}") }
            stopSelf()
            return
        }

        val startedAt = System.currentTimeMillis()
        scope.launch {
            val note = try {
                container.notes.createForRecording(startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. storage is completely full: report it instead of crashing.
                Log.e(TAG, "Couldn't create the note", e)
                withContext(Dispatchers.Main) {
                    RecordingStatus.update { RecorderState(error = "Couldn't start recording: ${e.message}") }
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            noteTitle = note.title
            val file = container.notes.audioFile(note.id)
            withContext(Dispatchers.Main) {
                val r = Recorder(note.id, file)
                recorder = r
                RecordingStatus.update { it.copy(noteId = note.id) }
                r.start()
                if (stopPending) r.requestStop()
            }
        }
    }

    /** Called on the main thread once the recorder thread has ended and the WAV is closed. */
    private fun onRecorderFinished(r: Recorder) {
        if (finishing) return
        finishing = true
        RecordingStatus.update { it.copy(phase = RecorderPhase.STOPPING) }
        // App scope, not the service scope: finalising must complete even if the service is
        // being destroyed.
        container.scope.launch(Dispatchers.IO) {
            val notes = container.notes
            val bytes = r.dataBytes
            // The WAV is already closed. If the note's details can't be written (storage full),
            // say so instead of crashing; startup recovery finishes the note next launch.
            val saveError = try {
                if (bytes == 0L) {
                    // Nothing was captured at all: don't leave an empty note behind.
                    notes.update(r.noteId) { it.copy(status = NoteStatus.RECORDED) }
                    notes.delete(listOf(r.noteId), NoteRepository.DeleteWhat.BOTH)
                } else {
                    // Saved and ready; the user starts transcription from the note when they want it.
                    notes.update(r.noteId) {
                        it.copy(status = NoteStatus.RECORDED, durationMs = Wav.durationMs(bytes), error = null)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't finish saving note ${r.noteId}", e)
                "Inkwell couldn't finish saving this recording's details (storage may be full). " +
                    "It will be fixed the next time Inkwell opens."
            }
            (r.stopReason ?: saveError)?.let { reason ->
                Notifications.alert(this@RecordingService, Notifications.ID_STORAGE, "Recording stopped", reason)
            }
            withContext(Dispatchers.Main) {
                RecordingStatus.update { RecorderState(error = r.error ?: saveError) }
                recorder = null
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // If the system destroys us mid-recording, stop cleanly; the WAV header is never more
        // than ~2 s behind, and startup recovery finalises the note if the process dies.
        recorder?.requestStop()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notification ------------------------------------------------------------------

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(state: RecorderState): Notification {
        val paused = state.phase == RecorderPhase.PAUSED
        val text = buildString {
            append(
                when (state.phase) {
                    RecorderPhase.STARTING -> "Starting…"
                    RecorderPhase.PAUSED -> "Paused at ${Format.duration(state.elapsedMs)}"
                    RecorderPhase.STOPPING -> "Saving…"
                    else -> "Recording $noteTitle"
                },
            )
            if (state.lowStorage) append(" · Low storage: ${Format.bytes(state.freeBytes)} left")
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_inkwell)
            .setContentTitle(if (paused) "Recording paused" else "Inkwell is recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this))
            .setShowWhen(!paused && state.phase == RecorderPhase.RECORDING)
            .setUsesChronometer(!paused && state.phase == RecorderPhase.RECORDING)
            .setWhen(System.currentTimeMillis() - state.elapsedMs)
            .apply {
                if (state.phase == RecorderPhase.RECORDING) {
                    addAction(0, "Pause", actionIntent(ACTION_PAUSE, 1))
                } else if (paused) {
                    addAction(0, "Resume", actionIntent(ACTION_RESUME, 2))
                }
                addAction(0, "Stop", actionIntent(ACTION_STOP, 3))
            }
            .build()
    }

    private fun refreshNotification() {
        if (finishing) return
        try {
            if (Notifications.canPost(this)) {
                NotificationManagerCompat.from(this)
                    .notify(Notifications.ID_RECORDING, buildNotification(RecordingStatus.state.value))
            }
        } catch (_: SecurityException) {
        }
    }

    // ---- Recorder thread -----------------------------------------------------------------

    private inner class Recorder(val noteId: String, private val file: File) : Thread("inkwell-recorder") {
        private val lock = Object()

        @Volatile private var stopRequested = false
        @Volatile private var paused = false

        @Volatile var dataBytes: Long = 0
            private set
        @Volatile var error: String? = null
            private set
        /** Set when recording stopped by itself (e.g. storage full) rather than by the user. */
        @Volatile var stopReason: String? = null
            private set

        fun requestStop() {
            stopRequested = true
            synchronized(lock) { lock.notifyAll() }
        }

        fun setPaused(value: Boolean) {
            paused = value
            synchronized(lock) { lock.notifyAll() }
        }

        @SuppressLint("MissingPermission") // checked in start()
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuffer = AudioRecord.getMinBufferSize(
                Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val smallBufferBytes = maxOf(minBuffer * 4, Wav.BYTES_PER_SECOND)
            // 4 s of headroom, so a slow flush to storage (syncHeader) can't overflow the
            // microphone buffer and silently drop audio.
            val bufferBytes = maxOf(smallBufferBytes, Wav.BYTES_PER_SECOND * 4)
            fun openRecord(bytes: Int) = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Wav.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytes,
            )
            var record: AudioRecord? = null
            var writer: WavWriter? = null
            try {
                writer = WavWriter(file)
                record = openRecord(bufferBytes)
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    // In case a device refuses the larger buffer, fall back to the previous size.
                    record.release()
                    record = openRecord(smallBufferBytes)
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    error = "The microphone couldn't be opened."
                    return
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error = "The microphone is in use by another app."
                    return
                }
                publish(RecorderPhase.RECORDING, 0f)

                val buffer = ByteArray(Wav.BYTES_PER_SECOND / 10) // 100 ms
                var lastHeaderSync = SystemClock.elapsedRealtime()
                var lastStorageCheck = 0L
                while (!stopRequested) {
                    if (paused) {
                        record.stop()
                        writer.syncHeader()
                        publish(RecorderPhase.PAUSED, 0f)
                        main.post { refreshNotification() }
                        synchronized(lock) {
                            while (paused && !stopRequested) lock.wait()
                        }
                        if (stopRequested) break
                        record.startRecording()
                        publish(RecorderPhase.RECORDING, 0f)
                        main.post { refreshNotification() }
                        continue
                    }

                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        error = "Microphone error ($n)."
                        break
                    }
                    if (n == 0) continue
                    writer.write(buffer, n)
                    dataBytes = writer.dataBytes
                    publish(RecorderPhase.RECORDING, peakLevel(buffer, n))

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHeaderSync >= 2_000) {
                        writer.syncHeader()
                        lastHeaderSync = now
                    }
                    if (now - lastStorageCheck >= 5_000) {
                        lastStorageCheck = now
                        if (!checkStorage()) break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Write failed", e)
                error = "Couldn't write the recording: ${e.message}"
                stopReason = "Recording stopped because the audio couldn't be written " +
                    "(storage may be full). Everything up to that point was saved."
            } catch (e: Exception) {
                Log.e(TAG, "Recorder failed", e)
                error = "Recording failed: ${e.message}"
            } finally {
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                record?.release()
                try {
                    writer?.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Couldn't finalise WAV", e)
                }
                writer?.let { dataBytes = it.dataBytes }
                main.post { onRecorderFinished(this) }
            }
        }

        /** Returns false when recording must stop because storage is nearly full. */
        private fun checkStorage(): Boolean {
            val free = file.parentFile?.usableSpace ?: Long.MAX_VALUE
            val low = free < LOW_STORAGE_BYTES
            RecordingStatus.update { it.copy(freeBytes = free, lowStorage = low) }
            if (low && !lowStorageNotified) {
                lowStorageNotified = true
                Notifications.alert(
                    this@RecordingService,
                    Notifications.ID_STORAGE,
                    "Storage is running low",
                    "Only ${Format.bytes(free)} left. Recording continues, but will stop and " +
                        "save automatically if storage runs out.",
                )
                main.post { refreshNotification() }
            }
            if (free < CRITICAL_STORAGE_BYTES) {
                stopReason = "Recording stopped and saved because storage is almost full " +
                    "(${Format.bytes(free)} left)."
                return false
            }
            return true
        }

        private fun publish(phase: RecorderPhase, level: Float) {
            val elapsed = Wav.durationMs(dataBytes)
            RecordingStatus.update { it.copy(phase = phase, elapsedMs = elapsed, level = level) }
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        const val LOW_STORAGE_BYTES = 200L * 1024 * 1024
        const val CRITICAL_STORAGE_BYTES = 30L * 1024 * 1024

        private const val ACTION_START = "inkwell.record.START"
        private const val ACTION_PAUSE = "inkwell.record.PAUSE"
        private const val ACTION_RESUME = "inkwell.record.RESUME"
        private const val ACTION_STOP = "inkwell.record.STOP"

        /** Starts recording. Call from the foreground UI after RECORD_AUDIO is granted. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)
        fun stop(context: Context) = command(context, ACTION_STOP)

        private fun command(context: Context, action: String) {
            if (!RecordingStatus.state.value.isActive) return
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }

        /** Peak level of a PCM16 buffer mapped from -60..0 dBFS to 0..1. */
        fun peakLevel(buffer: ByteArray, length: Int): Float {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                val a = abs(s.toShort().toInt())
                if (a > peak) peak = a
                i += 2
            }
            if (peak == 0) return 0f
            val db = 20 * log10(peak / 32768.0)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
