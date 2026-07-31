package eu.flopsyan.sonorus.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import eu.flopsyan.sonorus.MainActivity
import eu.flopsyan.sonorus.SonorusApp

/**
 * A foreground service is what buys the native client its two real advantages
 * over the web app: playback that survives the app going to the background, and
 * a system notification Android draws itself - metadata, artwork, a progress
 * bar and prev/next - instead of one the browser decides how much of to grant.
 *
 * The player is the one from [SonorusApp], not a second one: service and UI
 * live in the same process, so the session wraps the player the screens are
 * already driving and the two can never drift apart.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as SonorusApp
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, app.player.exoPlayer)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Swiping the notification away with nothing playing should end the service
     * rather than leave a dead card standing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The player belongs to the application and outlives this service, so
        // only the session is released here.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
