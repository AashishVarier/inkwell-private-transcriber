package com.variableduck.inkwell.transcriber.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.variableduck.inkwell.transcriber.MainActivity
import com.variableduck.inkwell.transcriber.R

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_TRANSCRIPTION = "transcription"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_RECORDING = 1
    const val ID_TRANSCRIPTION = 2
    const val ID_STORAGE = 3
    const val ID_TRANSCRIPT_READY = 4

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Inkwell is recording"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_TRANSCRIPTION, "Transcription", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of on-device transcription"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, "Warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Low storage and other problems that need attention"
                },
            ),
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Posts a one-off notification (if notifications are allowed). */
    fun alert(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL_ALERTS) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_inkwell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
