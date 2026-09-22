package org.sonorus.data.download

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi

/**
 * The one foreground job (Android 14 and up) or service (older) that keeps
 * downloads alive with the screen off. Songs and videos share it, so neither
 * queue may let go of it while the other still runs - that is what
 * [Downloads.otherBusy] and [VideoDownloads.otherBusy] are for.
 */
@UnstableApi
internal object DownloadHold {

    private val useJob = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** A job on Android 14 and up, which may only be scheduled while the app is on screen; a service below. */
    fun hold(context: Context, unmetered: Boolean, force: Boolean = false, visibleNow: Boolean = visible()) {
        if (useJob) {
            if (!visibleNow || DownloadJob.ensure(context, unmetered = unmetered, force = force)) return
            // A job the system refused still leaves the foreground service, with its six hours.
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    fun release(context: Context) {
        if (useJob) {
            DownloadJob.finish(context, reschedule = false)
            // The job leaves its notification behind on purpose, see [DownloadJob].
            DownloadNotification.hide(context)
        }
        runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
    }

    fun visible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
