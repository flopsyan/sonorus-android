package org.sonorus.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.sonorus.MainActivity
import org.sonorus.R

/** The one download notification, drawn the same for the service and the job. */
object DownloadNotification {

    const val ID = 4711
    private const val CHANNEL = "downloads"

    @Volatile
    private var channelReady = false

    private fun channel(context: Context) {
        if (channelReady) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Songs, die auf dieses Gerät geladen werden"
                    setShowBadge(false)
                }
            )
        }
        channelReady = true
    }

    fun build(context: Context, state: Downloads.State): Notification {
        channel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stalled = state.stalled
        val text = stalled ?: if (state.running > 1) "Noch ${state.running} Songs" else "Noch 1 Song"
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (state.progress * 100).toInt(), stalled != null || state.progress <= 0f)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun show(context: Context, state: Downloads.State) {
        context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, state))
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
