package org.sonorus.data.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import org.sonorus.SonorusApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the process up while downloads run, on Android 13 and older.
 *
 * Android 14 and up use [DownloadJob]. The notification itself is kept up to
 * date by [Downloads]; this only has to be in the foreground.
 */
@UnstableApi
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val downloads = (application as SonorusApp).downloads
        // Once per service: collecting in every start stacked a collector per start.
        scope.launch {
            downloads.state.collect { if (!it.busy) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a foreground service a few seconds to show its notification.
        try {
            ServiceCompat.startForeground(
                this,
                DownloadNotification.ID,
                DownloadNotification.build(this, (application as SonorusApp).downloads.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) {
            // Android 15 and up refuse once the six-hour dataSync budget is spent; crashing would not download anything either.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 and up: the six hours are over, and a service that does not stop within seconds crashes the app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
