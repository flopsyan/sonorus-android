package org.sonorus.data

import org.sonorus.data.download.DownloadStore
import org.sonorus.data.download.Offline
import org.sonorus.data.model.AlbumResponse
import org.sonorus.data.model.AlbumsResponse
import org.sonorus.data.model.ArtistResponse
import org.sonorus.data.model.ArtistsResponse
import org.sonorus.data.model.BookResponse
import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.GenreResponse
import org.sonorus.data.model.GenresResponse
import org.sonorus.data.model.HomeResponse
import org.sonorus.data.model.LyricsResponse
import org.sonorus.data.model.PlaylistResponse
import org.sonorus.data.model.PodcastResponse
import org.sonorus.data.model.PodcastsResponse
import org.sonorus.data.model.SearchResponse
import org.sonorus.data.model.ShuffleResponse
import org.sonorus.data.model.SpokenAuthorResponse
import org.sonorus.data.model.SpokenResponse
import org.sonorus.data.model.StarsResponse
import org.sonorus.data.model.TracksResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * What a thrown error says about **the server**, which is a different question
 * from what it says about the request.
 *
 * Pure and out here so it can be tested: it is the rule the whole offline mode
 * turns on, and getting it wrong is not a wrong error message, it is an app that
 * silently stops using the network. Three kinds of failure, and only one of them
 * is a server that is gone:
 *
 *  - **The server said no.** A 404 for a track that has since been deleted, a
 *    403 for an admin route - it answered, so it is there. Dropping the app
 *    offline over one bad id would be absurd.
 *  - **The server answered and the app could not read the answer.** A field the
 *    server renamed, a type it changed. That is a contract that has drifted, and
 *    the one thing it is *not* is a network that is gone. Counting it as one is
 *    what put the entire app - music, podcasts, everything - into offline mode
 *    the moment Hörspiele was tapped; see `SpokenWireTest`.
 *  - **Nothing answered at all**, or something that is not Sonorus did
 *    (`not_json` is a captive portal or a proxy). Only this one is offline.
 */
internal fun serverAnswered(error: Throwable): Boolean = when (error) {
    is ApiException -> error.code != "not_json" && error.code != "bad_url"
    is SerializationException -> true
    else -> false
}

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
    private val scope: CoroutineScope,
) {

    private val _manual = MutableStateFlow(settings.offlineMode)
    private val _unreachable = MutableStateFlow(false)

    /** The probe that decides whether a failed read was really the server. */
    private var confirming: Job? = null

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
        // A phone switched offline by hand makes no requests at all, so a probe
        // that is still waiting to run is called off rather than left to fire.
        if (on) confirming?.cancel()
        if (!on) _unreachable.value = false
        recompute()
    }

    /**
     * One read failed on the transport - and one failure is not a dead server.
     *
     * This is the difference between the app Florian had and the one he asked
     * for: any single hiccup - a handover between Wi-Fi and mobile, a DNS lookup
     * that lost a second, a hotspot going quiet - flipped the whole library over
     * to the downloads at once, and it did so while the phone plainly had
     * signal. Spotify does not do that, and neither does this now: the failure
     * is a *suspicion*, and it has to be confirmed by a request of the app's
     * own before anything changes on screen.
     *
     * The one case that stays instant is the one that matters most: **no radio
     * at all needs no confirming.** A phone in a plane must not spend seconds
     * pretending it might still get through, and Android already knows the
     * answer. The cold start is instant for the same reason ([_offline] reads
     * the radio before the first request), and so is a boot whose own bootstrap
     * failed - [org.sonorus.ui.AppViewModel] calls [markUnreachable] outright
     * there, because that read *was* the confirmation.
     */
    private fun reportUnreachable() {
        if (_unreachable.value || _manual.value) return
        if (!connectivity.online.value) {
            markUnreachable()
            return
        }
        if (confirming?.isActive == true) return
        confirming = scope.launch {
            // A moment for a handover or a stalled lookup to pass, and then a
            // second opinion. It is a second strike rather than a second wait:
            // the read that failed has already spent its own timeout.
            delay(CONFIRM_DELAY_MS)
            if (_manual.value || _unreachable.value) return@launch
            if (!connectivity.online.value) {
                markUnreachable()
                return@launch
            }
            val alive = try {
                // A bootstrap, the same as [watchServer]: it is the request the
                // app wants next anyway, so a probe that works leaves the
                // account snapshot fresh instead of only clearing a flag.
                store.rememberAccount(api.bootstrap())
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                serverAnswered(error)
            }
            if (alive) markReachable() else markUnreachable()
        }
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
     * traffic, so no caller has to remember to. [serverAnswered] is the rule it
     * applies; a failure that rule cannot vouch for is a suspicion and goes to
     * [reportUnreachable] rather than straight to the banner.
     *
     * **A cancelled read is not a failure at all.** Every screen fetches inside
     * a `LaunchedEffect` (see `rememberLoad`), so leaving a page while its
     * request is in flight cancels it - which is to say that scrolling through
     * the app fast enough throws `CancellationException` in here several times a
     * minute. Counting those as a dead server is what produced the offline
     * banner that appeared out of nowhere on a phone with full signal and only
     * went away when the app was restarted.
     */
    private suspend fun <T> reachableAfter(block: suspend () -> T): T {
        try {
            val result = block()
            markReachable()
            return result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (serverAnswered(error)) markReachable() else reportUnreachable()
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

    // --- Spoken word ----------------------------------------------------------
    // Deliberately without an offline path. The downloads hold songs, and
    // nothing on the phone can answer "which episodes does this show have" - so
    // rather than a screen that is empty and does not say why, these say the
    // one true thing: the server is not there. If spoken word ever becomes
    // downloadable, this is the line that changes.

    private fun onlyOnline(what: String): Nothing =
        throw ApiException("offline", "$what gibt es nur online - offline hörst du deine Downloads.")

    suspend fun podcasts(): PodcastsResponse =
        if (offline.value) onlyOnline("Podcasts") else reachableAfter { api.podcasts() }

    suspend fun podcast(id: Int, sort: String? = null): PodcastResponse =
        if (offline.value) onlyOnline("Podcasts") else reachableAfter { api.podcast(id, sort) }

    suspend fun spoken(base: String): SpokenResponse =
        if (offline.value) onlyOnline(spokenName(base)) else reachableAfter { api.spoken(base) }

    suspend fun spokenAuthor(base: String, id: Int): SpokenAuthorResponse =
        if (offline.value) onlyOnline(spokenName(base))
        else reachableAfter { api.spokenAuthor(base, id) }

    suspend fun book(base: String, id: Int): BookResponse =
        if (offline.value) onlyOnline(spokenName(base)) else reachableAfter { api.book(base, id) }

    private fun spokenName(base: String) =
        if (base == "audiodramas") "Hörspiele" else "Hörbücher"

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

        /**
         * How long a failed read is only a suspicion. Long enough for a handover
         * or a stalled lookup to right itself, short enough that a server that
         * really is gone is admitted to before the user has read the error and
         * reached for the retry button.
         */
        const val CONFIRM_DELAY_MS = 2_000L
    }
}
