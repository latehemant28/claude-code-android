package com.example.hinglishpdf.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.hinglishpdf.MainActivity
import com.example.hinglishpdf.ui.status.ErrorGuide

object Notifications {
    const val PROGRESS_ID = 1
    private const val RESULT_ID = 2
    private const val CHANNEL_PROGRESS = "translation_progress"
    private const val CHANNEL_RESULT = "translation_result"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Translation progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows which page is being translated"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Finished translations", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * The persistent "Translating page 45 of 300..." notification, with a Pause button.
     * Manipulation Matrix: does this materially improve the user's work? Yes:
     * Android requires it for background work, and it shows progress and a
     * way to stop, silently (no sound, no repeats).
     */
    fun progress(context: Context, title: String, text: String, done: Int, total: Int): Notification {
        val pause = PendingIntent.getService(
            context, 1,
            Intent(context, TranslationService::class.java).setAction(TranslationService.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            .build()
    }

    fun update(context: Context, notification: Notification) = post(context, PROGRESS_ID, notification)

    /**
     * The book is ready: tapping opens the file.
     * Manipulation Matrix: does this materially improve the user's work? Yes:
     * it delivers the result the user asked for, once.
     */
    fun finished(context: Context, title: String, fileName: String, uri: Uri, mimeType: String) {
        val open = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$title is translated")
            .setContentText("Saved to Downloads: $fileName")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
        post(context, RESULT_ID, notification)
    }

    /**
     * The book needs the user: told as guidance (what happened, what to do),
     * opening the app where the button to do it is.
     * Manipulation Matrix: does this materially improve the user's work? Yes:
     * the translation has stopped and only the user can unblock it.
     */
    fun failed(context: Context, title: String, message: String) {
        val guide = ErrorGuide.of(message)
        val text = guide.explanation
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$title: ${guide.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, RESULT_ID, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, id: Int, notification: Notification) {
        // Without the permission (Android 13+, denied) translation still runs; it is just silent.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33
        ) {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }
}
