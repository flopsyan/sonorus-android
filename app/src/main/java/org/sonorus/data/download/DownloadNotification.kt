package org.sonorus.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.sonorus.MainActivity
import org.sonorus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The one download notification, drawn the same for the service and the job. */
object DownloadNotification {

    const val ID = 4711
    private const val CHANNEL = "downloads"
    private const val NOTIFY_MS = 1_000L

    @Volatile
    private var channelReady = false

    private fun channel(context: Context) {
        if (channelReady) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Songs und Videos, die auf dieses Gerät geladen werden"
                    setShowBadge(false)
                }
            )
        }
        channelReady = true
    }

    fun build(context: Context, state: Downloads.State, video: VideoDownloads.State): Notification {
        channel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A video running is the bigger thing to report; the songs wait behind it in words.
        val showVideo = video.busy && (video.active != null || !state.busy)
        val stalled = if (showVideo) video.stalled else state.stalled
        val songs = if (state.running > 1) "Noch ${state.running} Songs" else "Noch 1 Song"
        val text = stalled ?: when {
            showVideo -> listOfNotNull(video.line, songs.takeIf { state.busy }).joinToString(" · ")
            else -> songs
        }
        val progress = if (showVideo) video.progress else state.progress
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (progress * 100).toInt(), stalled != null || progress <= 0f)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun show(context: Context, state: Downloads.State, video: VideoDownloads.State) {
        context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, state, video))
    }

    /** Draws it for both queues, at most once a second: five updates a second had the system shed them. */
    fun watch(
        context: Context,
        scope: CoroutineScope,
        music: StateFlow<Downloads.State>,
        videos: StateFlow<VideoDownloads.State>,
    ) = scope.launch {
        var shown: Pair<Downloads.State, VideoDownloads.State>? = null
        var at = 0L
        combine(music, videos) { a, b -> a to b }.collect { now ->
            val (s, v) = now
            if (!s.busy && !v.busy) {
                if (shown != null) hide(context)
                shown = null
                return@collect
            }
            val clock = SystemClock.elapsedRealtime()
            val last = shown
            if (last != null && last.first.stalled == s.stalled && last.first.running == s.running &&
                last.second.stalled == v.stalled && last.second.line == v.line && clock - at < NOTIFY_MS
            ) {
                return@collect
            }
            shown = now
            at = clock
            show(context, s, v)
        }
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
