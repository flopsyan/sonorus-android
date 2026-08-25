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
import kotlinx.coroutines.delay
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
 *
 * The third one is the one that has to be able to **undo itself**. See
 * [markUnreachable].
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
        scope.launch { watchServer() }
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

    /**
     * The server did not answer.
     *
     * This used to be a one-way door, and it is why the app would sometimes sit
     * there saying "Offline - du hörst deine Downloads" with four bars of
     * signal until it was closed and opened again. Nothing cleared it:
     * `recompute` only re-reads the radio, and the radio had never changed - the
     * *server* had hiccuped, or one request had timed out. So a single failure
     * put the app offline for the rest of its run.
     *
     * Two things undo it now, and neither of them is the user. Any request that
     * succeeds clears it ([reachableAfter]), and while it is set [watchServer]
     * asks the server again by itself.
     */
    fun markUnreachable() {
        if (_unreachable.value) return
        _unreachable.value = true
        recompute()
    }

    fun markReachable() {
        if (!_unreachable.value) return
        _unreachable.value = false
        recompute()
    }

    /**
     * Asks the server again while the app believes it is gone.
     *
     * Only while there is a radio, and only while the user has not switched
     * offline by hand: a phone in a plane must make no requests at all, and one
     * switched offline on purpose is not waiting to be overruled.
     *
     * The request is a bootstrap because that is the one the app needs next
     * anyway - a successful probe leaves the account snapshot fresh rather than
     * just a flag flipped.
     */
    private suspend fun watchServer() {
        while (true) {
            delay(PROBE_INTERVAL_MS)
            if (!_unreachable.value) continue
            if (_manual.value || !connectivity.online.value) continue
            runCatching { api.bootstrap() }.onSuccess {
                store.rememberAccount(it)
                markReachable()
            }
        }
    }

    /**
     * Runs an online read and keeps the reachability flag honest out of ordinary
     * traffic, so no caller has to remember to.
     *
     * The distinction that matters: **an error the server itself produced proves
     * the server is there.** A 404 for a track that has since been deleted is
     * not a dead server, and treating it as one would drop the whole app into
     * offline mode over one bad id. A transport failure - no DNS, no route, a
     * timeout - is the opposite, and so is an answer that did not come from
     * Sonorus at all (`not_json`, which is a captive portal or a proxy having
     * answered instead).
     */
    private suspend fun <T> reachableAfter(block: suspend () -> T): T {
        try {
            val result = block()
            markReachable()
            return result
        } catch (error: Throwable) {
            val fromServer =
                error is ApiException && error.code != "not_json" && error.code != "bad_url"
            if (fromServer) markReachable() else markUnreachable()
            throw error
        }
    }

    private fun gone(what: String): Nothing =
        throw ApiException("offline", "$what ist nicht heruntergeladen.")

    // --- Reads ----------------------------------------------------------------

    suspend fun bootstrap(): Bootstrap =
        if (offline.value) Offline.bootstrap(store.snapshot)
        // Stored on every success: who is logged in, what the site is called and
        // how the player is set cannot be asked for once the server is gone.
        else reachableAfter { api.bootstrap().also { store.rememberAccount(it) } }

    suspend fun home(): HomeResponse =
        if (offline.value) Offline.home(store.snapshot) else reachableAfter { api.home() }

    suspend fun tracks(q: String = "", sort: String = "title", dir: String = "asc", limit: Int = 0): TracksResponse =
        if (offline.value) Offline.tracks(store.snapshot, q, sort, dir)
        else reachableAfter { api.tracks(q = q, sort = sort, dir = dir, limit = limit) }

    suspend fun artists(q: String = ""): ArtistsResponse =
        if (offline.value) Offline.artists(store.snapshot, q) else reachableAfter { api.artists(q) }

    suspend fun artist(id: Int): ArtistResponse =
        if (offline.value) Offline.artist(store.snapshot, id) ?: gone("Dieser Interpret")
        else reachableAfter { api.artist(id) }

    suspend fun albums(q: String = "", sort: String = "title", dir: String = "asc"): AlbumsResponse =
        if (offline.value) Offline.albums(store.snapshot, q, sort, dir)
        else reachableAfter { api.albums(q, sort, dir) }

    suspend fun album(id: Int): AlbumResponse =
        if (offline.value) Offline.album(store.snapshot, id) ?: gone("Dieses Album")
        else reachableAfter { api.album(id) }

    suspend fun genres(): GenresResponse =
        if (offline.value) Offline.genres(store.snapshot) else reachableAfter { api.genres() }

    suspend fun genre(ids: List<Int>): GenreResponse =
        if (offline.value) Offline.genre(store.snapshot, ids) else reachableAfter { api.genre(ids) }

    suspend fun stars(values: List<Int>): StarsResponse =
        if (offline.value) Offline.stars(store.snapshot, values) else reachableAfter { api.stars(values) }

    suspend fun playlist(id: Int): PlaylistResponse =
        if (offline.value) Offline.playlist(store.snapshot, id) ?: gone("Diese Playlist")
        else reachableAfter { api.playlist(id) }

    suspend fun search(q: String): SearchResponse =
        if (offline.value) Offline.search(store.snapshot, q) else reachableAfter { api.search(q) }

    suspend fun shuffle(limit: Int = 300, unrated: Boolean = false): ShuffleResponse =
        if (offline.value) Offline.shuffle(store.snapshot, limit, unrated)
        else reachableAfter { api.shuffle(limit, unrated) }

    suspend fun lyrics(id: Int): LyricsResponse =
        if (offline.value) Offline.lyrics(store.snapshot, id) else reachableAfter { api.lyrics(id) }

    /** Named songs in the order they were asked for - how a queue is rebuilt. */
    suspend fun tracksByIds(ids: List<Int>): TracksResponse =
        if (offline.value) Offline.tracksByIds(store.snapshot, ids)
        else reachableAfter { api.tracksByIds(ids) }

    // --- Artwork --------------------------------------------------------------

    /**
     * Where a cover comes from. A downloaded one wins even while online: it is
     * already here, and the whole point of a download is that nothing has to be
     * fetched twice.
     */
    fun coverUrl(path: String?): String? =
        store.coverOf(path)?.let { "file://${it.absolutePath}" } ?: api.coverUrl(path)

    private companion object {
        /**
         * Long enough to cost nothing on a phone that really has no server, and
         * short enough that walking back into coverage feels immediate rather
         * than needing the app restarted.
         */
        const val PROBE_INTERVAL_MS = 20_000L
    }
}
