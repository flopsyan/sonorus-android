package eu.flopsyan.sonorus

import android.app.Application
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.Session
import eu.flopsyan.sonorus.data.SonorusApi
import eu.flopsyan.sonorus.player.PlayerController

/**
 * Holds the three things the whole app shares. They are plain singletons rather
 * than an injection framework - there are three of them, and the player has to
 * be reachable from both the UI and [eu.flopsyan.sonorus.player.PlaybackService]
 * (which run in the same process), which a scoped container would only make
 * harder.
 */
@UnstableApi
class SonorusApp : Application() {

    lateinit var session: Session
        private set
    lateinit var api: SonorusApi
        private set
    lateinit var player: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = Session(this)
        api = SonorusApi(session)
        player = PlayerController(this, api)
    }

    companion object {
        lateinit var instance: SonorusApp
            private set
    }
}
