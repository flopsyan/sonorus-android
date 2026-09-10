package org.sonorus.data.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import androidx.media3.common.util.UnstableApi
import org.sonorus.SonorusApp

/**
 * Keeps downloads going with the phone locked, on Android 14 and up.
 *
 * A user-initiated data transfer job instead of a dataSync foreground service:
 * that service is cut off after six hours in the background and cannot be started
 * again from there, while the system stops a job when the network goes and starts
 * it again on its own once the network is back.
 */
@UnstableApi
class DownloadJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val downloads = (application as SonorusApp).downloads
        var unmetered: Boolean? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Detach, not remove: a job stopped by a lost network leaves a queue that still has to say so.
            setNotification(
                params,
                DownloadNotification.ID,
                DownloadNotification.build(this, downloads.state.value),
                JOB_END_NOTIFICATION_POLICY_DETACH,
            )
            unmetered = getSystemService(JobScheduler::class.java)?.getPendingJob(ID)
                ?.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        running = Running(this, params, unmetered)
        downloads.onJobStarted()
        return true
    }

    // OkHttp follows the default network by itself; the override only stops the platform warning on every handover.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onStopJob(params: JobParameters): Boolean {
        running = null
        (application as SonorusApp).downloads.onJobStopped()
        return true
    }

    private class Running(val job: DownloadJob, val params: JobParameters, val unmetered: Boolean?)

    companion object {
        private const val ID = 4711
        private const val BACKOFF_MS = 60_000L

        @Volatile
        private var running: Running? = null

        val isRunning: Boolean get() = running != null

        /** Only while the app is visible - the system refuses a user-initiated job otherwise. Answers whether a job holds the queue. */
        fun ensure(context: Context, unmetered: Boolean, force: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val now = running
            // Scheduling the same id again stops the job that is running.
            if (now != null && !force && (now.unmetered == null || now.unmetered == unmetered)) return true
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val info = JobInfo.Builder(ID, ComponentName(context, DownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(if (unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                // Linear: after a night without a server the next try is half an hour away, not five hours.
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()
            return runCatching { scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        }

        fun finish(context: Context, reschedule: Boolean) {
            val now = running
            running = null
            when {
                now != null -> now.job.jobFinished(now.params, reschedule)
                !reschedule -> context.getSystemService(JobScheduler::class.java)?.cancel(ID)
            }
        }
    }
}
