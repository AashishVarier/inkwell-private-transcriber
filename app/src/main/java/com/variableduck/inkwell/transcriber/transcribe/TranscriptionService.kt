package com.variableduck.inkwell.transcriber.transcribe

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.variableduck.inkwell.transcriber.R
import com.variableduck.inkwell.transcriber.audio.Wav
import com.variableduck.inkwell.transcriber.container
import com.variableduck.inkwell.transcriber.data.Note
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.data.NoteStatus
import com.variableduck.inkwell.transcriber.models.ModelItem
import com.variableduck.inkwell.transcriber.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transcribes queued notes one after another in a foreground service with a partial wake lock.
 *
 * The WAV is read from disk in ~30 s chunks (never loaded whole). Each chunk's text is appended
 * to the transcript file as soon as it is ready, together with a checkpoint, so text appears as
 * it goes and an interrupted job resumes where it stopped.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unexpected transcription error", e) },
    )
    private var queueJob: Job? = null
    @Volatile private var noteJob: Job? = null
    @Volatile private var timedOut = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelCurrent()
                if (queueJob?.isActive != true) stopSelf()
            }
            else -> {
                // Every startForegroundService() must be answered with startForeground().
                if (!goForeground()) return START_NOT_STICKY
                if (queueJob?.isActive != true) {
                    timedOut = false
                    queueJob = scope.launch { runQueue() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRANSCRIPTION,
            buildNotification(TranscriptionStatus.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't start transcription in the foreground; will resume when the app is opened", e)
        stopSelf()
        false
    }

    private fun cancelCurrent() {
        container.engine.requestAbort()
        noteJob?.cancel()
    }

    /** Android 15+: dataSync services get about 6 hours a day. Save the checkpoint and stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached; pausing transcription")
        timedOut = true
        cancelCurrent()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelCurrent()
        queueJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        val c = container
        acquireWakeLock()
        TranscriptionStatus.update { it.copy(running = true) }
        try {
            while (!timedOut) {
                val note = c.notes.nextQueued() ?: break
                val model = c.models.activeModel()
                if (model == null) {
                    c.notes.update(note.id) {
                        it.copy(status = NoteStatus.FAILED, error = "No model installed. Download or import one in Models.")
                    }
                    continue
                }
                val job = scope.launch { transcribe(note, model) }
                noteJob = job
                job.join()
                noteJob = null
            }
        } finally {
            withContext(NonCancellable) {
                c.engine.release()
                releaseWakeLock()
                withContext(Dispatchers.Main) {
                    // Decided on the main thread, where start commands arrive, so a note queued
                    // while this run was finishing is not left waiting.
                    if (!timedOut && scope.isActive && c.notes.hasQueued() && c.models.activeModel() != null) {
                        queueJob = scope.launch { runQueue() }
                    } else {
                        TranscriptionStatus.update { TranscriptionState() }
                        ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun transcribe(queued: Note, model: ModelItem) {
        val c = container
        val notes = c.notes
        val id = queued.id
        val settings = c.settings.value
        TranscriptionStatus.update {
            TranscriptionState(
                running = true, noteId = id, noteTitle = queued.title, modelId = model.id,
                modelName = model.displayName, stage = TranscriptionStage.LOADING_MODEL, progress = queued.progress,
            )
        }
        updateNotification(force = true)
        acquireWakeLock()
        try {
            if (!notes.audioFile(id).exists()) {
                notes.update(id) { it.copy(status = NoteStatus.FAILED, error = "The recording no longer exists.") }
                return
            }
            c.engine.clearAbort()
            c.engine.load(c.models.file(model.id))
            val language = if (c.engine.isMultilingual) settings.language else "en"
            notes.update(id) {
                it.copy(
                    status = NoteStatus.TRANSCRIBING, modelName = model.displayName, language = language,
                    error = null, warning = null,
                )
            }
            TranscriptionStatus.update { it.copy(stage = TranscriptionStage.TRANSCRIBING) }
            updateNotification(force = true)

            // Whisper is silent while it works on a chunk, so progress is estimated from how
            // long earlier chunks took and published a few times a second.
            val speedKey = "${model.id}/${settings.effectiveThreads}"
            val speeds = getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE)
            val estimator = ProgressEstimator(
                speeds.getFloat(speedKey, 0f).takeIf { it > 0f }?.toDouble()
                    ?: ProgressEstimator.DEFAULT_GUESS_MS_PER_AUDIO_SECOND,
            )
            val ticker = scope.launch {
                while (true) {
                    publish(estimator)
                    delay(TICK_MS)
                }
            }
            val result = try {
                ChunkedTranscriber(notes).run(
                    id = id,
                    removeTags = settings.removeTags,
                    transcribeChunk = { samples, prompt ->
                        c.engine.transcribe(samples, settings.effectiveThreads, language, prompt)
                    },
                    listener = estimator,
                )
            } finally {
                ticker.cancel()
                // Remembered so the next run's first estimate fits this phone.
                estimator.msPerAudioSecond?.let { speeds.edit { putFloat(speedKey, it.toFloat()) } }
            }
            publish(estimator)
            TranscriptionStatus.update { it.copy(stage = TranscriptionStage.FINISHING) }
            updateNotification(force = true)

            val check = TranscriptCheck.evaluate(
                hasWords = withContext(Dispatchers.IO) { TextCleaner.hasWords(notes.readText(id)) },
                run = result,
                modelName = model.displayName,
                multilingual = c.engine.isMultilingual,
                language = language,
            )
            val keepAudio = c.settings.value.keepAudio
            val title = when (check) {
                is TranscriptCheck.Empty -> {
                    // Nothing usable: start from the beginning next time. The recording is always
                    // kept here, whatever the keep-audio setting, so it can be tried again.
                    notes.update(id) {
                        it.copy(status = NoteStatus.FAILED, error = check.message, progress = 0f, doneSamples = 0, textBytes = 0)
                    }
                    "Nothing was transcribed"
                }
                is TranscriptCheck.Incomplete -> {
                    // Parts are missing: keep the recording so it can be transcribed again.
                    val warning = check.message + if (keepAudio) "" else " The recording has been kept for that."
                    notes.update(id) {
                        it.copy(status = NoteStatus.DONE, progress = 1f, error = null, warning = warning, doneSamples = 0)
                    }
                    "Transcript ready, with parts left out"
                }
                TranscriptCheck.Ok -> {
                    notes.update(id) { it.copy(status = NoteStatus.DONE, progress = 1f, error = null, doneSamples = 0) }
                    if (!keepAudio) notes.delete(listOf(id), NoteRepository.DeleteWhat.AUDIO)
                    "Transcript ready"
                }
            }
            Notifications.alert(
                this, Notifications.ID_TRANSCRIPT_READY, title, queued.title, Notifications.CHANNEL_TRANSCRIPTION,
            )
        } catch (e: Exception) {
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    // Keep the checkpoint. After a system time-out, queue it to resume
                    // automatically; after the user's Cancel, leave it ready to resume by hand.
                    notes.update(id) {
                        it.copy(status = if (timedOut) NoteStatus.QUEUED else NoteStatus.RECORDED)
                    }
                }
                throw e as? CancellationException ?: CancellationException("Cancelled")
            }
            Log.e(TAG, "Transcription of $id failed", e)
            notes.update(id) { it.copy(status = NoteStatus.FAILED, error = e.message ?: "Transcription failed") }
        } finally {
            c.engine.clearAbort()
        }
    }

    private fun publish(estimator: ProgressEstimator) {
        val total = estimator.totalSamples
        if (total <= 0) return
        TranscriptionStatus.update {
            it.copy(
                stage = TranscriptionStage.TRANSCRIBING,
                progress = estimator.progress(),
                positionMs = estimator.position() * 1000 / Wav.SAMPLE_RATE,
                totalMs = total * 1000 / Wav.SAMPLE_RATE,
                remainingMs = estimator.remainingMs(),
            )
        }
        updateNotification()
    }

    // ---- Notification / wake lock ---------------------------------------------------------

    private fun buildNotification(state: TranscriptionState): Notification {
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        val cancel = PendingIntent.getService(
            this,
            10,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_stat_inkwell)
            .setContentTitle(state.noteTitle?.let { "Transcribing $it" } ?: "Preparing transcription…")
            .setContentText(state.summary)
            .setProgress(100, percent, state.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this))
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 1_000) return
        lastNotificationAt = now
        if (!Notifications.canPost(this)) return
        try {
            NotificationManagerCompat.from(this)
                .notify(Notifications.ID_TRANSCRIPTION, buildNotification(TranscriptionStatus.state.value))
        } catch (_: SecurityException) {
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "inkwell:transcription")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Re-acquired for every note, so a long queue keeps the CPU awake throughout.
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val ACTION_START = "inkwell.transcribe.START"
        private const val ACTION_CANCEL = "inkwell.transcribe.CANCEL"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        private const val TICK_MS = 250L

        /** Measured speed per model and thread count (ms of work per second of audio). */
        private const val SPEED_PREFS = "transcription_speed"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TranscriptionService::class.java).setAction(ACTION_START),
                )
            } catch (e: Exception) {
                // Not allowed from the background on Android 12+: the note stays queued and
                // resumes the next time the app is opened.
                Log.w(TAG, "Couldn't start transcription service now", e)
            }
        }

        /** Cancels the note currently being transcribed (its partial text is kept). */
        fun cancelCurrent(context: Context) {
            if (!TranscriptionStatus.state.value.running) return
            try {
                context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't cancel", e)
            }
        }
    }
}
