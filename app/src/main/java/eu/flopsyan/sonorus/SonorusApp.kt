package eu.flopsyan.sonorus

import android.app.Application
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.Connectivity
import eu.flopsyan.sonorus.data.Library
import eu.flopsyan.sonorus.data.Session
import eu.flopsyan.sonorus.data.Settings
import eu.flopsyan.sonorus.data.SonorusApi
import eu.flopsyan.sonorus.data.download.DownloadStore
import eu.flopsyan.sonorus.data.download.Downloads
import eu.flopsyan.sonorus.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Holds the things the whole app shares. They are plain singletons rather than
 * an injection framework - the player has to be reachable from both the UI and
 * [eu.flopsyan.sonorus.player.PlaybackService] (which run in the same process),
 * which a scoped container would only make harder.
 *
 * The order below is the dependency order, and it is the whole wiring: nothing
 * here reaches back up. The downloads sit between the API and the player,
 * because the player asks them for a file before it asks the server for a
 * stream.
 */
@UnstableApi
class SonorusApp : Application() {

    lateinit var session: Session
        private set
    lateinit var api: SonorusApi
        private set
    lateinit var settings: Settings
        private set
    lateinit var connectivity: Connectivity
        private set
    lateinit var store: DownloadStore
        private set
    lateinit var library: Library
        private set
    lateinit var downloads: Downloads
        private set
    lateinit var player: PlayerController
        private set

    /** Outlives every screen, which is what the download queue needs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = Session(this)
        api = SonorusApi(session)
        settings = Settings(this)
        connectivity = Connectivity(this)
        // Internal storage, not the shared music folder: these files belong to
        // the app, are removed with it, and need no storage permission - which
        // is also what keeps a cold start free of a permission dialog.
        store = DownloadStore(File(filesDir, "offline"))
        library = Library(api, store, connectivity, settings, scope)
        downloads = Downloads(this, api, store, connectivity, settings)
        player = PlayerController(this, api, library)
    }

    companion object {
        lateinit var instance: SonorusApp
            private set
    }
}
