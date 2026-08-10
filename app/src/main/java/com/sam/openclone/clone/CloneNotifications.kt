package com.sam.openclone.clone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sam.openclone.MainActivity
import com.sam.openclone.R

/**
 * Notifications for clone progress and outcome.
 *
 * Progress sits on its own low-importance channel so an ongoing copy never
 * makes a sound, while results and the install prompt go out at default
 * importance because they need the user to see them.
 */
internal object CloneNotifications {

    const val PROGRESS_ID = 1
    private const val RESULT_ID = 2
    private const val ACTION_ID = 3

    private const val CHANNEL_PROGRESS = "clone_progress"
    private const val CHANNEL_RESULT = "clone_result"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    fun progress(context: Context, label: String, fraction: Float): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_clone)
            .setContentTitle(context.getString(R.string.notification_cloning, label))
            .setProgress(1000, (fraction * 1000).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun result(context: Context, title: String, text: String) {
        notify(
            context, RESULT_ID,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_clone)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build()
        )
    }

    /**
     * Surfaces the system installer prompt when it could not be shown directly.
     *
     * Reached whenever the clone finishes while the app is in the background,
     * where starting the prompt activity is not permitted.
     */
    fun installPrompt(context: Context, label: String, confirmation: Intent) {
        val pending = PendingIntent.getActivity(
            context,
            ACTION_ID,
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context, ACTION_ID,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_clone)
                .setContentTitle(context.getString(R.string.notification_ready, label))
                .setContentText(context.getString(R.string.notification_ready_body))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, false)
                .build()
        )
    }

    fun cancelInstallPrompt(context: Context) {
        NotificationManagerCompat.from(context).cancel(ACTION_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            // Permission is checked above; the SecurityException path only
            // exists for a revoke racing this call.
            runCatching { manager.notify(id, notification) }
        }
    }
}
