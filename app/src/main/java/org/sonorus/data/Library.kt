package org.sonorus.data

import org.sonorus.data.download.DownloadStore
import org.sonorus.data.download.Offline
import org.sonorus.data.model.AlbumResponse
import org.sonorus.data.model.AlbumsResponse
import org.sonorus.data.model.ArtistResponse
import org.sonorus.data.model.ArtistsResponse
import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.GenreResponse
import org.sonorus.data.model.GenresResponse
import org.sonorus.data.model.HomeResponse
import org.sonorus.data.model.LyricsResponse
import org.sonorus.data.model.PlaylistResponse
import org.sonorus.data.model.SearchResponse
import org.sonorus.data.model.ShuffleResponse
import org.sonorus.data.model.StarsResponse
import org.sonorus.data.model.TracksResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where a screen gets its data - the server while there is one, the downloads
 * while there is not.
 *
 * Every screen asks this instead of the API, which is the whole trick: nothing
 * above this line knows whether the phone is online, so there is no second set
 * of offline screens to keep in step with the first. Offline the answers are
 * the same shapes, derived from what is on the phone (see [Offline]).
 *
 * "Offline" is three things at once, and any of them is enough:
 *
 *  - the user's own switch, the way Spotify has one;
 *  - no network at all, read before the first request rather than after a
 *    timeout - this is the case that has to be right in a plane;
 *  - a network that is there but a server that did not answer.
 */
class Library(
    private val api: SonorusApi,
    val store: DownloadStore,
    private val connectivity: Connectivity,
    private val settings: Settings,
    scope: CoroutineScope,
) {

    private val _manual = MutableStateFlow(settings.offlineMode)
    private val _unreachable = MutableStateFlow(false)

    /**
     * Written by hand rather than combined out of the three sources, and that is
     * not a stylistic choice: a derived flow updates on its own coroutine, so
     * right after [markUnreachable] the old value would still be readable - and
     * the caller that just caught a dead server would ask the dead server again.
     * That race is an uncaught `ConnectException` on the main thread, which is
     * to say a crash on exactly the start this feature exists for.
     */
    private val _offline = MutableStateFlow(settings.offlineMode || !connectivity.online.value)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    val manualOffline: StateFlow<Boolean> = _manual
    val online: StateFlow<Boolean> = connectivity.online

    init {
        scope.launch { connectivity.online.collect { recompute() } }
    }

    private fun recompute() {
        _offline.value = _manual.value || !connectivity.online.value || _unreachable.value
    }

    /** There is something stored to fall back on. */
    val hasSnapshot: Boolean get() = store.snapshot.account != null

    fun setManualOffline(on: Boolean) {
        settings.offlineMode = on
        _manual.value = on
        if (!on) _unreachable.value = false
        recompute()
    }

    /** The server did not answer. Cleared as soon as one does, or the network returns. */
    fun markUnreachable() {
        _unreachable.value = true
        recompute()
    }

    fun markReachable() {
        _unreachable.value = false
        recompute()
    }

    private fun gone(what: String): Nothing =
        throw ApiException("offline", "$what ist nicht heruntergeladen.")

    // --- Reads ----------------------------------------------------------------

    suspend fun bootstrap(): Bootstrap =
        if (offline.value) Offline.bootstrap(store.snapshot)
        // Stored on every success: who is logged in, what the site is called and
        // how the player is set cannot be asked for once the server is gone.
        else api.bootstrap().also { store.rememberAccount(it) }

    suspend fun home(): HomeResponse =
        if (offline.value) Offline.home(store.snapshot) else api.home()

    suspend fun tracks(q: String = "", sort: String = "title", dir: String = "asc", limit: Int = 0): TracksResponse =
        if (offline.value) Offline.tracks(store.snapshot, q, sort, dir)
        else api.tracks(q = q, sort = sort, dir = dir, limit = limit)

    suspend fun artists(q: String = ""): ArtistsResponse =
        if (offline.value) Offline.artists(store.snapshot, q) else api.artists(q)

    suspend fun artist(id: Int): ArtistResponse =
        if (offline.value) Offline.artist(store.snapshot, id) ?: gone("Dieser Interpret")
        else api.artist(id)

    suspend fun albums(q: String = "", sort: String = "title", dir: String = "asc"): AlbumsResponse =
        if (offline.value) Offline.albums(store.snapshot, q, sort, dir) else api.albums(q, sort, dir)

    suspend fun album(id: Int): AlbumResponse =
        if (offline.value) Offline.album(store.snapshot, id) ?: gone("Dieses Album")
        else api.album(id)

    suspend fun genres(): GenresResponse =
        if (offline.value) Offline.genres(store.snapshot) else api.genres()

    suspend fun genre(ids: List<Int>): GenreResponse =
        if (offline.value) Offline.genre(store.snapshot, ids) else api.genre(ids)

    suspend fun stars(values: List<Int>): StarsResponse =
        if (offline.value) Offline.stars(store.snapshot, values) else api.stars(values)

    suspend fun playlist(id: Int): PlaylistResponse =
        if (offline.value) Offline.playlist(store.snapshot, id) ?: gone("Diese Playlist")
        else api.playlist(id)

    suspend fun search(q: String): SearchResponse =
        if (offline.value) Offline.search(store.snapshot, q) else api.search(q)

    suspend fun shuffle(limit: Int = 300, unrated: Boolean = false): ShuffleResponse =
        if (offline.value) Offline.shuffle(store.snapshot, limit, unrated) else api.shuffle(limit, unrated)

    suspend fun lyrics(id: Int): LyricsResponse =
        if (offline.value) Offline.lyrics(store.snapshot, id) else api.lyrics(id)

    // --- Artwork --------------------------------------------------------------

    /**
     * Where a cover comes from. A downloaded one wins even while online: it is
     * already here, and the whole point of a download is that nothing has to be
     * fetched twice.
     */
    fun coverUrl(path: String?): String? =
        store.coverOf(path)?.let { "file://${it.absolutePath}" } ?: api.coverUrl(path)
}
