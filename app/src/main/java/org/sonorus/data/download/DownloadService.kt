package org.sonorus.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import org.sonorus.MainActivity
import org.sonorus.R
import org.sonorus.SonorusApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the process up while downloads run.
 *
 * Without it Android is free to kill the app the moment it goes to the
 * background - which is exactly what happens when a download of an album is
 * started and the phone goes into a pocket. The notification is the price of
 * that, and it is also the only place the progress is visible while the app is
 * not on screen.
 *
 * It owns nothing: the queue lives in [Downloads] and outlives every start and
 * stop of this service.
 */
@UnstableApi
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a foreground service five seconds to show its
        // notification, so this happens before anything else.
        ServiceCompat.startForeground(
            this,
            ID,
            notification(Downloads.State()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val downloads = (application as SonorusApp).downloads
        scope.launch {
            downloads.state.collect { state ->
                if (!state.busy) {
                    stopSelf()
                    return@collect
                }
                getSystemService(NotificationManager::class.java)
                    ?.notify(ID, notification(state))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Songs, die auf dieses Gerät geladen werden"
                setShowBadge(false)
            }
        )
    }

    private fun notification(state: Downloads.State): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val left = state.running
        val text = when {
            state.waiting -> "Wartet auf WLAN"
            left > 1 -> "Noch $left Songs"
            else -> "Noch 1 Song"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (state.progress * 100).toInt(), state.waiting || state.progress <= 0f)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val ID = 4711
    }
}
