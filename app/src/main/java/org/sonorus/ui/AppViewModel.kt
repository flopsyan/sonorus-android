package org.sonorus.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import org.sonorus.SonorusApp
import org.sonorus.data.ApiException
import org.sonorus.data.Library
import org.sonorus.data.Quality
import org.sonorus.data.SonorusApi
import org.sonorus.data.formatLabel
import org.sonorus.data.download.DownloadSync
import org.sonorus.data.download.Downloads
import org.sonorus.data.download.OfflineCollection
import org.sonorus.data.sync.PendingWrites
import org.sonorus.data.sync.TreeEdits
import org.sonorus.data.sync.WriteSync
import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.Lyrics
import org.sonorus.data.model.Playlist
import org.sonorus.data.model.Prefs
import org.sonorus.data.model.SortPref
import org.sonorus.data.model.Track
import org.sonorus.data.model.TreeResponse
import org.sonorus.player.PlayerController
import org.sonorus.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** Where the app is before it can show anything. */
sealed interface AppPhase {
    data object Starting : AppPhase
    data class NeedsLogin(val message: String = "") : AppPhase

    /**
     * Logged in, no server, and nothing downloaded to fall back on.
     *
     * Its own phase rather than [NeedsLogin], because it is not a login problem
     * and the login form cannot fix it. Showing that form here is the single
     * worst thing this app can do - it reads as "you have been logged out" on a
     * phone that is merely out of coverage, and it was the one failure Florian
     * called out as "darf niemals passieren".
     */
    data class OfflineEmpty(val message: String = "") : AppPhase

    data class Ready(val bootstrap: Bootstrap) : AppPhase
}

/** A one-off message shown in a snackbar, like the web app's toasts. */
data class Toast(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

@UnstableApi
class AppViewModel : ViewModel() {

    private val app: SonorusApp get() = SonorusApp.instance
    val api: SonorusApi get() = app.api
    val player: PlayerController get() = app.player

    /**
     * Where the screens get their data. Not [api] directly: offline the same
     * answers come out of the downloads, and no screen has to know which.
     */
    val lib: Library get() = app.library
    val downloads: Downloads get() = app.downloads

    /** What was done without a server and is waiting to be sent. */
    private val pending: PendingWrites get() = app.pending
    private val writeSync: WriteSync get() = app.writeSync
    private val downloadSync: DownloadSync get() = app.downloadSync

    /**
     * How many offline edits are still waiting, for the line in Einstellungen.
     * Plays are not counted: nobody thinks of listening as an edit, and a number
     * that climbs while you simply listen would read as something being stuck.
     */
    private val _waiting = MutableStateFlow(0)
    val waitingWrites: StateFlow<Int> = _waiting.asStateFlow()

    private fun countWaiting() {
        _waiting.value = pending.edits
    }

    /** True while the app is working out of its downloads. */
    val offline: StateFlow<Boolean> get() = lib.offline

    /** Artwork, from this phone when it is here and from the server otherwise. */
    fun coverUrl(path: String?): String? = lib.coverUrl(path)

    private val json = Json { ignoreUnknownKeys = true }

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.DARK)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    /** The last loaded bootstrap, kept so the sidebar can redraw after a change. */
    val bootstrap: Bootstrap? get() = (_phase.value as? AppPhase.Ready)?.bootstrap

    init {
        start()
        // A finished play changes the star playlists and the home page, so the
        // shell reloads what it shows in the sidebar.
        player.onPlayCounted = { refreshQuietly() }
        // A phone that finds its server again picks up by itself.
        //
        // Watched on `offline` and not on the radio, which is the fix for the
        // banner that used to stay up until the app was restarted: the radio
        // never changed in that case, the *server* had come back, and nothing
        // was listening for that. `Library` now clears its own unreachable flag
        // - from a request that worked or from its own probe - and this is what
        // turns that into a screen that redraws.
        //
        // `drop(1)` skips the value the flow already holds; the start below has
        // just acted on it.
        viewModelScope.launch {
            lib.offline.drop(1).collect { offline ->
                if (!offline) start() else refreshQuietly()
            }
        }

        // The chapters of the book that is playing, fetched once per book - the
        // same "load it once and only when it changed" the lyrics follow per
        // song. The player cannot do this itself: it talks to ExoPlayer and to
        // the API, and which chapters a book has is a library question.
        viewModelScope.launch {
            player.state
                .map { it.current?.takeIf { t -> t.audiobookId != null } }
                .map { it?.audiobookId to (it?.bookKind ?: "") }
                .distinctUntilChanged()
                .collect { (bookId, kind) ->
                    if (bookId == null) {
                        player.setChapters(null, emptyList())
                        return@collect
                    }
                    val base = if (kind == "drama") "audiodramas" else "audiobooks"
                    // A book without its chapters is the book as it was before
                    // they existed: one long bar and its own title. Nothing here
                    // is worth an error message.
                    val chapters = runCatching { lib.book(base, bookId).book.chapters }
                        .getOrDefault(emptyList())
                    player.setChapters(bookId, chapters)
                }
        }
    }

    /**
     * Brings the app up.
     *
     * The order matters and is the point of the whole feature: **a phone with no
     * network never makes a request.** It reads the downloads and is on the
     * library, rather than sitting in a connect timeout and then offering a
     * login form for a server that is not there.
     *
     * The rule this now follows without exception: **while a session is stored,
     * the login screen is never shown.** It used to be shown for a genuine auth
     * failure, and that was too clever by half - a captive portal answering 401
     * to `/login` is indistinguishable from Sonorus doing it, and both ended
     * with the user thrown out on a ferry. A password that really has changed is
     * a rare, recoverable thing with an obvious cure (Abmelden under
     * Einstellungen); being locked out of your own downloads at sea is neither.
     *
     * So there are exactly three outcomes: the library from the server, the
     * library from the downloads, or - only when there is nothing downloaded at
     * all - [AppPhase.OfflineEmpty], which says what is wrong and offers to try
     * again rather than asking for a password nobody got wrong.
     */
    fun start() {
        viewModelScope.launch {
            if (!app.session.isConfigured) {
                _phase.value = AppPhase.NeedsLogin()
                return@launch
            }
            if (lib.offline.value) {
                fallBackToDownloads(reason = if (lib.manualOffline.value) "" else NO_SERVER)
                return@launch
            }
            // Only a cold start shows the spinner. Coming back from offline the
            // shell is already up and playing, and tearing it down for a moment
            // would throw the navigation away and blink the player bar.
            if (_phase.value !is AppPhase.Ready) _phase.value = AppPhase.Starting
            runCatching { lib.bootstrap() }
                .onSuccess {
                    applyBootstrap(it)
                    syncWithServer()
                }
                .onFailure { error ->
                    // `markUnreachable` is what makes the next read take the
                    // downloads instead of the server, and it has to have taken
                    // effect before it runs - see [Library.offline]. `Library`
                    // has usually set it already; a login that has not stored a
                    // snapshot yet is the case where it has not.
                    lib.markUnreachable()
                    fallBackToDownloads(reason = message(error))
                }
        }
    }

    /**
     * Shows the library as it looks without a server, and says why once.
     *
     * [reason] empty means nothing is wrong that the user did not choose - the
     * manual offline switch needs no explanation, the banner in the shell says
     * it already.
     */
    private suspend fun fallBackToDownloads(reason: String) {
        if (!lib.hasSnapshot) {
            _phase.value = AppPhase.OfflineEmpty(reason.ifEmpty { NO_SERVER })
            return
        }
        val wasReady = _phase.value is AppPhase.Ready
        runCatching { applyBootstrap(lib.bootstrap()) }
            .onSuccess { if (!wasReady && reason.isNotEmpty()) say(OFFLINE_TOAST) }
            .onFailure {
                // The snapshot said there was an account and reading it still
                // failed, which leaves nothing to draw. Not a login problem
                // either, so still not the login form.
                _phase.value = AppPhase.OfflineEmpty(message(it))
            }
    }

    /** Tries the server once more from the offline screen or the banner. */
    fun retryConnection() {
        lib.markReachable()
        start()
    }

    fun login(server: String, user: String, pass: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatching {
                api.login(server, user, pass)
                lib.markReachable()
                // Through the library, not the API: this is the answer that gets
                // stored for the first offline start, and a phone that logged in
                // and then lost the network would otherwise have nothing at all
                // to fall back on.
                lib.bootstrap()
            }.onSuccess {
                applyBootstrap(it)
                onDone(true)
            }.onFailure {
                _phase.value = AppPhase.NeedsLogin(message(it))
                onDone(false)
            }
        }
    }

    /**
     * The only thing that may take the stored account away, which is why it also
     * clears it here: [start] hands a phone with a snapshot straight into its
     * downloads and never asks for a password, so a logout that left the
     * snapshot behind would be undone by the next cold start.
     */
    fun logout() {
        viewModelScope.launch {
            player.clearQueue()
            api.logout()
            lib.store.forgetAccount()
            _phase.value = AppPhase.NeedsLogin()
        }
    }

    private fun applyBootstrap(data: Bootstrap) {
        _phase.value = AppPhase.Ready(data)
        // Asked here rather than in `init`, which is the fix for a picker that
        // insisted the server had no ffmpeg: at construction time nobody is
        // logged in yet, so the request came back 401 and the answer - false -
        // was then kept for the rest of the session. This runs after every
        // successful bootstrap, which is exactly when the question can be
        // answered, and it is cheap enough not to need a guard.
        loadQualitySupport()
        // Player settings live on the account so they follow to another device -
        // all but the volume, which is not applied here at all. It is the web
        // app's slider, and on a phone the loudness is the system's: multiplying
        // the two only made every song quiet with no control in the app to undo
        // it. See [org.sonorus.player.PlayerController].
        val p = data.prefs.player
        player.setShuffle(p.shuffle)
        player.setRepeat(p.repeat)
        // And the queue itself, which does not: it is a fact about this phone.
        // After the modes on purpose - shuffle has to be the one it was left on
        // before an order is laid over it. Once per start; the second call is a
        // no-op, and every path into here can be walked more than once.
        viewModelScope.launch { player.restoreQueue() }
    }

    /**
     * Everything that was waiting for a server, now that there is one.
     *
     * Runs after every successful bootstrap, which is both the app's start and
     * the moment it finds its way back - see the collector in `init`. The order
     * is not free: **the edits go up before the downloads are reconciled**, or
     * a song added to a playlist in a plane would be compared against a server
     * that has not heard about it yet and be deleted again on the spot.
     */
    private fun syncWithServer() {
        viewModelScope.launch {
            val sent = runCatching { writeSync.flush() }.getOrNull()
            countWaiting()
            if (sent != null && sent.sent > 0) refreshQuietly()
            if (sent != null && sent.dropped > 0) {
                say(
                    "${Fmt.plural(sent.dropped, "Änderung", "Änderungen")} vom Server " +
                        "abgelehnt - vermutlich gibt es die Playlist nicht mehr.",
                    true,
                )
            }
            val changed = runCatching { downloadSync.reconcileAll() }.getOrNull() ?: return@launch
            if (changed.added > 0 && changed.removed > 0) {
                say("Downloads angeglichen: ${changed.added} dazu, ${changed.removed} entfernt.")
            } else if (changed.added > 0) {
                say("${Fmt.plural(changed.added, "Song", "Songs")} werden nachgeladen.")
            } else if (changed.removed > 0) {
                say("${Fmt.plural(changed.removed, "Download", "Downloads")} entfernt.")
            }
        }
    }

    /**
     * The collection on screen, against the list the screen has just loaded.
     *
     * The cheap half of the sync: no request, because the page already holds
     * what the server says. This is what makes an album or a playlist right the
     * moment it is opened rather than at the next start.
     */
    fun reconcileOnScreen(collection: OfflineCollection?, tracks: List<Track>) {
        if (collection == null || lib.offline.value) return
        viewModelScope.launch {
            val changed = downloadSync.apply(collection, tracks)
            if (changed.added > 0) say("${Fmt.plural(changed.added, "Song", "Songs")} werden nachgeladen.")
            if (changed.removed > 0) say("${Fmt.plural(changed.removed, "Download", "Downloads")} entfernt.")
        }
    }

    /** Reloads the sidebar numbers without flipping the screen to a spinner. */
    fun refreshQuietly() {
        viewModelScope.launch {
            runCatching { lib.bootstrap() }.onSuccess { _phase.value = AppPhase.Ready(it) }
        }
    }

    /**
     * Says no to everything that would have to reach the server, and says why.
     * Every write goes through here: a rating handed out in a plane cannot be
     * kept, and a screen that pretends otherwise is worse than one that does not
     * take it.
     */
    private fun needsServer(): Boolean {
        if (!lib.offline.value) return false
        say("Offline - das geht erst wieder mit Verbindung.", true)
        return true
    }

    // --- Downloads ------------------------------------------------------------

    /**
     * Puts songs on this phone. Anything already here is skipped rather than
     * fetched twice, and a missing file is never queued - it cannot be played
     * online either.
     */
    fun download(tracks: List<Track>) {
        if (needsServer()) return
        val fresh = tracks.filter { !it.missing && !downloads.store.isDownloaded(it.id) }
        if (fresh.isEmpty()) {
            say("Ist schon heruntergeladen.")
            return
        }
        downloads.add(fresh)
        say(
            when {
                !downloads.allowedNow -> "In die Warteschlange - es wartet auf WLAN."
                fresh.size == 1 -> "Wird heruntergeladen."
                else -> "${fresh.size} Songs werden heruntergeladen."
            }
        )
    }

    /**
     * Takes named songs off the phone, and remembers that it was on purpose.
     *
     * The second half is what makes the button stick: a song of a downloaded
     * playlist would otherwise be fetched again by the next reconcile, which is
     * doing what it is for and looks exactly like a bug. Asking for the song
     * again - on its own or with its collection - takes the exclusion back.
     */
    fun removeDownloads(tracks: List<Track>) {
        val ids = tracks.map { it.id }
        downloads.store.exclude(ids)
        downloads.removeAll(ids)
        say(if (tracks.size == 1) "Download entfernt." else "${tracks.size} Downloads entfernt.")
    }

    /** A whole collection onto the phone, and kept in step with the server. */
    fun downloadCollection(collection: OfflineCollection, tracks: List<Track>) {
        if (needsServer()) return
        val fresh = tracks.filterNot { it.missing }
        downloads.addCollection(collection, fresh)
        val missing = fresh.count { !downloads.store.isDownloaded(it.id) }
        say(
            when {
                !downloads.allowedNow -> "In die Warteschlange - es wartet auf WLAN."
                missing == 0 -> "Ist schon heruntergeladen."
                missing == 1 -> "Wird heruntergeladen."
                else -> "$missing Songs werden heruntergeladen."
            }
        )
    }

    /**
     * Gives a collection back. Only the songs nothing else holds really go -
     * one that also sits in a downloaded album or playlist stays.
     */
    fun removeCollection(collection: OfflineCollection) {
        val kept = collection.trackIds.size - downloads.removeCollection(collection)
        say(
            when {
                kept > 0 -> "Download entfernt. ${Fmt.plural(kept, "Song bleibt", "Songs bleiben")} - " +
                    "sie hängen noch woanders drin."
                else -> "Download entfernt."
            }
        )
    }

    fun clearDownloads() {
        downloads.clear()
        say("Alle Downloads entfernt.")
    }

    /**
     * Stops a running download and takes back what it fetched.
     *
     * Only what **this** run fetched - see [Downloads.cancelRun]. Anything that
     * was already on the phone stays, which is the difference between a cancel
     * and a wipe.
     */
    /**
     * Stops one song's download.
     *
     * Nothing is taken back, unlike [cancelDownloadRun]: what has been written
     * stays and the next attempt picks it up from there. One song is a small
     * enough thing that a half of it is worth keeping, and there is no run whose
     * songs would have to be told apart from what was already on the phone.
     */
    fun cancelDownload(track: Track) {
        downloads.cancel(track.id)
        say("Download abgebrochen.")
    }

    fun cancelDownloadRun() {
        val removed = downloads.cancelRun()
        say(
            when (removed) {
                0 -> "Download abgebrochen."
                1 -> "Download abgebrochen, 1 Song wieder entfernt."
                else -> "Download abgebrochen, $removed Songs wieder entfernt."
            }
        )
    }

    // --- Quality --------------------------------------------------------------
    // Per device, not per account: this phone on mobile data and the browser on
    // the LAN are the same login and want opposite things. See [Settings].

    val streamQuality: StateFlow<Quality> get() = app.settings.streamQuality
    val downloadQuality: StateFlow<Quality> get() = app.settings.downloadQuality

    /** What is really asked for, which on mobile data need not be what is set. */
    val servedStreamQuality: StateFlow<Quality> get() = app.quality.streamQuality

    /** Whether the original may be asked for at all on this connection. */
    val losslessAllowed: StateFlow<Boolean> get() = app.quality.losslessAllowed

    val losslessWifiOnly: StateFlow<Boolean> get() = app.settings.losslessWifiOnly

    fun setLosslessWifiOnly(on: Boolean) {
        app.settings.setLosslessWifiOnly(on)
        // The running song keeps its stream on purpose: switching this must not
        // put a gap in the music. It applies to the next track, and to this one
        // as soon as anything else reopens it.
        say(if (on) "Lossless nur noch über WLAN." else "Lossless auch über mobile Daten.")
    }

    fun setDownloadsWifiOnly(on: Boolean) {
        downloads.setWifiOnly(on)
        say(if (on) "Downloads warten auf WLAN." else "Downloads laufen auch über mobile Daten.")
    }

    /**
     * Whether the server can serve anything but the original.
     *
     * Asked once and remembered. An instance without ffmpeg has one quality, and
     * a picker offering a second there would be a switch that does nothing.
     */
    private val _qualityReady = MutableStateFlow(false)
    val qualityReady: StateFlow<Boolean> = _qualityReady.asStateFlow()

    private fun loadQualitySupport() {
        // Offline the question cannot be asked, and the last answer is the best
        // one there is - so it is left alone rather than reset to false.
        if (lib.offline.value) return
        viewModelScope.launch {
            runCatching { api.quality() }.onSuccess { _qualityReady.value = it.ready }
        }
    }

    /**
     * Changing the stream quality applies to the **running song too**, not only
     * to the next one. A setting you have to skip a track to hear is a setting
     * nobody tries out; the position is carried over, so it costs the buffer.
     */
    fun setStreamQuality(quality: Quality) {
        // The one thing the picker may not do: pick the original on a metered
        // connection while it is meant to be Wi-Fi only. Said out loud rather
        // than silently ignored - a switch that does not move and does not
        // explain itself reads as broken.
        if (quality == Quality.ORIGINAL && !losslessAllowed.value) {
            say("Lossless ist auf WLAN beschränkt - mit mobilen Daten bleibt es bei Opus 128.", true)
            return
        }
        app.settings.setStreamQuality(quality)
        player.reopenAtCurrentQuality()
        say("Streaming: ${quality.label}")
    }

    /**
     * The download default. Deliberately does **not** touch anything already on
     * the phone: a song fetched as FLAC stays a FLAC, and this only decides what
     * the next download asks for.
     */
    fun setDownloadQuality(quality: Quality) {
        app.settings.setDownloadQuality(quality)
        say("Downloads: ${quality.label}")
    }

    /** The format [track] is really being heard in, for the player's indicator. */
    fun formatOf(track: Track): String = formatLabel(track, player.servedQuality(track))

    /**
     * Whether the chip under the transport opens its picker, and what it says
     * when it does not.
     *
     * It used to switch on the tap itself. A sheet with the choices in it is
     * what it is now - there are two of them today, and the moment there is a
     * third a toggle would have been the wrong shape all along.
     */
    fun qualityPickerBlocked(): String? {
        if (!_qualityReady.value) return "Dieser Server liefert nur das Original."
        val playing = player.state.value.current
        if (playing != null && downloads.store.fileOf(playing.id) != null) {
            return "Läuft vom Gerät - die Qualität steht mit dem Download fest."
        }
        return null
    }

    /**
     * The user's own offline switch. Turning it off is also the way back after
     * the app fell offline by itself, so it re-reads the library either way.
     */
    fun setOfflineMode(on: Boolean) {
        lib.setManualOffline(on)
        start()
    }

    // --- Preferences ----------------------------------------------------------
    // `users.prefs` is the answer to every "das soll dauerhaft so bleiben", and
    // it lives on the account rather than on the device.

    val prefs: Prefs get() = bootstrap?.prefs ?: Prefs()

    fun savePlayerPrefs() {
        val s = player.state.value
        val value = json.encodeToJsonElement(
            org.sonorus.data.model.PlayerPrefs(
                // Carried over rather than read off the player: this client has
                // no volume of its own, and writing one back would reach across
                // and move the web app's slider.
                volume = prefs.player.volume,
                muted = prefs.player.muted,
                shuffle = s.shuffle,
                repeat = s.repeat,
            )
        )
        viewModelScope.launch { runCatching { api.setPref("player", value) } }
    }

    fun saveSort(key: String, sort: SortPref) {
        viewModelScope.launch { runCatching { api.setPref(key, json.encodeToJsonElement(sort)) } }
        // Keep the local copy in step so a screen reopened right away agrees.
        bootstrap?.let { b ->
            val next = when (key) {
                "albumSort" -> b.prefs.copy(albumSort = sort)
                "trackSort" -> b.prefs.copy(trackSort = sort)
                else -> b.prefs
            }
            _phase.value = AppPhase.Ready(b.copy(prefs = next))
        }
    }

    fun saveStatsRange(range: String) {
        viewModelScope.launch { runCatching { api.setPref("statsRange", JsonPrimitive(range)) } }
        bootstrap?.let { _phase.value = AppPhase.Ready(it.copy(prefs = it.prefs.copy(statsRange = range))) }
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
    }

    /**
     * Arms or disarms shuffle, and remembers it on the account like the player's
     * own switch does.
     *
     * **Starts nothing.** That is the whole point of the change: shuffle is a
     * setting, and the play button is what plays. See
     * [org.sonorus.player.PlayerController.playCollection].
     */
    fun toggleShuffle() {
        player.setShuffle(!player.state.value.shuffle)
        savePlayerPrefs()
    }

    // --- Ratings --------------------------------------------------------------

    /**
     * The ratings handed out on this phone since the screens fetched their data.
     *
     * A [Track] is one immutable row of a server response, and nothing on a
     * screen refetches after a rating - so without this the star the user just
     * gave would be accepted by the server and still be drawn the old way, which
     * looks exactly like nothing happened. Refetching the whole list instead is
     * the wrong trade on a list of several hundred songs, and it would not help
     * the full player at all: its track comes from the playback queue, not from
     * a screen's load.
     *
     * So every place that draws a rating asks [starsOf] instead of reading the
     * row, and the map is what makes the answer current.
     */
    private val ratings = mutableStateMapOf<Int, Int>()

    /** The rating to draw for [track]: what this phone last set, else the row's. */
    fun starsOf(track: Track): Int = ratings[track.id] ?: track.stars

    /**
     * Clicking the rating a track already has clears it, exactly like the web
     * app. [onDone] carries the new value back so a list can redraw its row.
     */
    fun rate(trackId: Int, stars: Int, current: Int, onDone: (Int) -> Unit = {}) {
        val next = if (current == stars) 0 else stars
        // Offline the star is kept rather than refused. It is written onto the
        // row this phone holds - so the star playlists offline are right at
        // once - and queued for the server. Rating a library is done by ear on
        // a sofa or a train, which is exactly where there is no server.
        if (lib.offline.value) {
            pending.rate(trackId, next)
            downloads.store.applyRating(trackId, next)
            ratings[trackId] = next
            onDone(next)
            countWaiting()
            say("Bewertet - wird übertragen, sobald der Server wieder da ist.")
            return
        }
        viewModelScope.launch {
            runCatching { api.rate(trackId, next) }
                .onSuccess {
                    // The server's answer, not `next` - it is the one that counts,
                    // and writing it before the request would have to be undone
                    // again when the request fails.
                    ratings[trackId] = it.stars
                    onDone(it.stars)
                    refreshQuietly()
                    // A star moves a song between the star playlists, so a
                    // downloaded one has to follow. Costs nothing when none is
                    // downloaded, which is the ordinary case.
                    viewModelScope.launch { runCatching { downloadSync.reconcileKind("stars") } }
                }
                .onFailure { say(message(it), true) }
        }
    }

    /**
     * Starts a random run through the library, like the button on the home page.
     *
     * With [unrated] it draws only from what has no star yet, which is the other
     * thing a random run is for: rating a library is a job done by ear, and
     * picking the next unrated song out of a list of a few thousand by hand is
     * the part that makes it stop happening.
     */
    fun shufflePlay(unrated: Boolean = false) {
        viewModelScope.launch {
            runCatching { lib.shuffle(unrated = unrated) }
                .onSuccess {
                    if (it.tracks.isEmpty()) {
                        say(if (unrated) "Alles ist bewertet." else "Hier gibt es nichts zum Abspielen.")
                    } else {
                        player.playTracks(
                            it.tracks,
                            0,
                            if (unrated) "Unbewertete" else "Zufall",
                            // A mix dealt by the server is no list on screen, so
                            // it belongs to the page the button sits on.
                            Routes.HOME,
                        )
                    }
                }
                .onFailure { say(message(it), true) }
        }
    }

    /** Adds a track to a playlist and refreshes the sidebar counts. */
    fun addToPlaylist(playlistId: Int, trackId: Int, playlistName: String) {
        if (lib.offline.value) {
            offlineAdd(playlistId, trackId, playlistName)
            return
        }
        viewModelScope.launch {
            runCatching { api.addToPlaylist(playlistId, listOf(trackId)) }
                .onSuccess {
                    say("Zu \"$playlistName\" hinzugefügt.")
                    refreshQuietly()
                    playlistChanged(playlistId)
                }
                .onFailure { say(message(it), true) }
        }
    }

    // --- Lyrics ---------------------------------------------------------------
    // The words come out of the audio file itself - nothing is looked up
    // anywhere - so a song either carries them or it does not. `hasLyrics` on
    // the track says which, and a track that has none is never asked about.

    private val _lyrics = MutableStateFlow(Lyrics())
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    /** Which track [_lyrics] belongs to, so the same one is not fetched twice. */
    private var lyricsFor: Int? = null

    /**
     * Fetches the lyrics of [track], unless they are the ones already held.
     *
     * Only the full-screen player asks, and only while it is open: nothing else
     * shows them, and a request per track change in the background would be
     * paid for by a phone that never looks.
     */
    fun loadLyrics(track: Track?) {
        if (lyricsFor == track?.id) return
        lyricsFor = track?.id
        _lyrics.value = Lyrics()
        val id = track?.id ?: return
        if (!track.hasLyrics) return
        viewModelScope.launch {
            runCatching { lib.lyrics(id) }
                // A track change while the answer was on its way must not land
                // on the song that is playing now.
                .onSuccess { if (lyricsFor == id) _lyrics.value = it.lyrics }
        }
    }

    /** The write of [setLyricsOffset], so a slider does not send one per pixel. */
    private var offsetWrite: Job? = null

    /**
     * Moves the text of [trackId] against the music, in seconds and positive
     * for later. Clamped to +/-5 and rounded to a tenth here as well as on the
     * server, so the control cannot come to rest between two notches.
     *
     * The lyric held in [lyrics] is corrected at once and the request follows
     * half a second later: the whole point of the control is to be dragged
     * while the song runs, and the line under the light has to move with the
     * finger. The correction is a fact about the file, so it cannot be written
     * from a plane - the value is left alone offline rather than changed
     * locally and lost.
     */
    fun setLyricsOffset(trackId: Int, seconds: Double) {
        if (needsServer()) return
        val value = Math.round(seconds.coerceIn(-5.0, 5.0) * 10) / 10.0
        if (lyricsFor == trackId) _lyrics.value = _lyrics.value.copy(offset = value)
        offsetWrite?.cancel()
        offsetWrite = viewModelScope.launch {
            delay(500)
            runCatching { api.setLyricsOffset(trackId, value) }
                .onFailure { say("Versatz nicht gespeichert.", true) }
        }
    }

    // --- Playlist management --------------------------------------------------
    // Every one of these answers with the whole tree, so the sidebar is redrawn
    // from the server's answer rather than from a guess about what changed.

    /** The track waiting for a playlist to be picked, if any. */
    private val _pendingAdd = MutableStateFlow<Track?>(null)
    val pendingAdd: StateFlow<Track?> = _pendingAdd.asStateFlow()

    /**
     * Whether that picker may also create a list. The plus in the full screen
     * player says no: that button promises one decision - which list - and an
     * entry opening a second dialog on top of it is one step more than that.
     */
    private val _pendingAddCanCreate = MutableStateFlow(true)
    val pendingAddCanCreate: StateFlow<Boolean> = _pendingAddCanCreate.asStateFlow()

    fun askForPlaylist(track: Track, allowCreate: Boolean = true) {
        // No server needed any more: putting a song into a playlist is one of
        // the things that works offline and is sent later.
        _pendingAddCanCreate.value = allowCreate
        _pendingAdd.value = track
    }

    /** The single whose edit dialog is open, if any. */
    private val _editingSingle = MutableStateFlow<Track?>(null)
    val editingSingle: StateFlow<Track?> = _editingSingle.asStateFlow()

    fun editSingle(track: Track) {
        if (needsServer()) return
        _editingSingle.value = track
    }

    fun closeSingleEditor() {
        _editingSingle.value = null
    }

    fun cancelAdd() {
        _pendingAdd.value = null
    }

    private fun withTree(onDone: () -> Unit = {}, block: suspend () -> TreeResponse) {
        if (needsServer()) return
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { answer ->
                    bootstrap?.let { _phase.value = AppPhase.Ready(it.copy(playlists = answer.tree)) }
                    onDone()
                }
                .onFailure { say(message(it), true) }
        }
    }

    fun createPlaylist(name: String, folderId: Int? = null, then: (() -> Unit)? = null) {
        if (lib.offline.value) {
            createLocalPlaylist(name, folderId)
            then?.invoke()
            say("Playlist \"$name\" angelegt - wird nachgetragen.")
            return
        }
        withTree(onDone = { then?.invoke(); say("Playlist \"$name\" angelegt.") }) {
            api.createPlaylist(name, folderId)
        }
    }

    /**
     * A playlist that exists on this phone and nowhere else yet.
     *
     * Its id is negative and handed out by the queue, which is what lets songs
     * be put into it before any server has heard of it - see
     * [org.sonorus.data.sync.PendingWrites.localId]. When the create is finally
     * sent, the real id takes its place everywhere at once.
     */
    private fun createLocalPlaylist(name: String, folderId: Int?): Int {
        val id = pending.localId()
        pending.createPlaylist(id, name, folderId)
        downloads.store.rememberCollection(OfflineCollection(kind = "playlist", id = id, name = name))
        downloads.store.updateTree {
            TreeEdits.addPlaylist(it, Playlist(id = id, name = name, folderId = folderId))
        }
        countWaiting()
        refreshQuietly()
        return id
    }

    /**
     * Creates a playlist and puts the waiting track straight into it - the one
     * flow where a new list is never wanted empty.
     */
    fun createPlaylistWithTrack(name: String, track: Track) {
        if (lib.offline.value) {
            val id = createLocalPlaylist(name, null)
            offlineAdd(id, track.id, name)
            _pendingAdd.value = null
            return
        }
        viewModelScope.launch {
            runCatching {
                val created = api.createPlaylist(name, null)
                val id = (created.tree.loose + created.tree.folders.flatMap { it.playlists })
                    .firstOrNull { it.name == name }?.id
                if (id != null) api.addToPlaylist(id, listOf(track.id))
                created
            }.onSuccess {
                _pendingAdd.value = null
                say("Zu \"$name\" hinzugefügt.")
                refreshQuietly()
            }.onFailure { say(message(it), true) }
        }
    }

    fun renamePlaylist(id: Int, name: String, then: () -> Unit = {}) {
        if (lib.offline.value) {
            pending.renamePlaylist(id, name)
            downloads.store.rememberCollection(
                downloads.store.collectionOf("playlist", listOf(id))?.copy(name = name)
                    ?: OfflineCollection(kind = "playlist", id = id, name = name)
            )
            downloads.store.updateTree { TreeEdits.renamePlaylist(it, id, name) }
            countWaiting()
            refreshQuietly()
            then()
            say("Umbenannt - wird nachgetragen.")
            return
        }
        withTree(then) { api.renamePlaylist(id, name) }
    }

    fun pinPlaylist(id: Int, pinned: Boolean, then: () -> Unit = {}) =
        withTree(then) { api.pinPlaylist(id, pinned) }

    fun deletePlaylist(id: Int, then: () -> Unit = {}) {
        if (lib.offline.value) {
            pending.deletePlaylist(id)
            // The songs stay on the phone unless nothing else wants them - the
            // same rule as removing the download of a collection, because that
            // is what deleting a downloaded playlist is.
            downloads.store.collectionOf("playlist", listOf(id))?.let { downloads.removeCollection(it) }
            downloads.store.updateTree { TreeEdits.deletePlaylist(it, id) }
            countWaiting()
            refreshQuietly()
            then()
            say("Playlist gelöscht - wird nachgetragen.")
            return
        }
        withTree({ then(); say("Playlist gelöscht.") }) { api.deletePlaylist(id) }
    }

    fun createFolder(name: String) {
        if (lib.offline.value) {
            val id = pending.localId()
            pending.createFolder(id, name)
            downloads.store.updateTree { TreeEdits.addFolder(it, id, name) }
            countWaiting()
            refreshQuietly()
            say("Ordner \"$name\" angelegt - wird nachgetragen.")
            return
        }
        withTree({ say("Ordner \"$name\" angelegt.") }) { api.createFolder(name) }
    }

    fun renameFolder(id: Int, name: String) {
        if (lib.offline.value) {
            pending.renameFolder(id, name)
            downloads.store.updateTree { TreeEdits.renameFolder(it, id, name) }
            countWaiting()
            refreshQuietly()
            return
        }
        withTree { api.renameFolder(id, name) }
    }

    /** Deleting a folder keeps its playlists - they move to the top level. */
    fun deleteFolder(id: Int) {
        if (lib.offline.value) {
            pending.deleteFolder(id)
            downloads.store.updateTree { TreeEdits.deleteFolder(it, id) }
            countWaiting()
            refreshQuietly()
            say("Ordner gelöscht, die Playlists sind erhalten - wird nachgetragen.")
            return
        }
        withTree({ say("Ordner gelöscht, die Playlists sind erhalten.") }) { api.deleteFolder(id) }
    }

    fun movePlaylist(id: Int, folderId: Int?) {
        if (lib.offline.value) {
            pending.movePlaylist(id, folderId)
            downloads.store.updateTree { TreeEdits.movePlaylist(it, id, folderId) }
            countWaiting()
            refreshQuietly()
            return
        }
        withTree { api.movePlaylist(id, folderId) }
    }

    /**
     * Takes a song out of a playlist - and, if the playlist is downloaded, off
     * the phone with it.
     *
     * The whole track rather than its item id, because offline there may be no
     * item id at all: a song put into the list in a plane has no row on the
     * server yet.
     */
    fun removeFromPlaylist(playlistId: Int, track: Track, onDone: () -> Unit) {
        if (lib.offline.value) {
            offlineRemoveFromPlaylist(playlistId, track, onDone)
            return
        }
        val itemId = track.itemId?.toInt() ?: return
        viewModelScope.launch {
            runCatching { api.removeFromPlaylist(playlistId, itemId) }
                .onSuccess {
                    onDone()
                    refreshQuietly()
                    // The download follows the playlist: the song goes unless
                    // something else on this phone still holds it.
                    playlistChanged(playlistId)
                }
                .onFailure { say(message(it), true) }
        }
    }

    /** A playlist changed on the server, so its download has to catch up. */
    private fun playlistChanged(playlistId: Int) {
        if (lib.offline.value) return
        if (downloads.store.collectionOf("playlist", listOf(playlistId)) == null) return
        viewModelScope.launch {
            val changed = runCatching { downloadSync.reconcile("playlist", listOf(playlistId)) }
                .getOrNull() ?: return@launch
            if (changed.added > 0) say("${Fmt.plural(changed.added, "Song", "Songs")} werden geladen.")
            if (changed.removed > 0) say("${Fmt.plural(changed.removed, "Download", "Downloads")} entfernt.")
        }
    }

    // --- The same writes, without a server ------------------------------------
    //
    // Every one of these does the same two things: change what this phone shows
    // *now*, and write down what the server has to be told later. Doing only the
    // second would look like the app had swallowed the edit.

    private fun offlineAdd(playlistId: Int, trackId: Int, playlistName: String) {
        val queued = pending.addToPlaylist(playlistId, trackId)
        downloads.store.addToCollection(playlistId, trackId, playlistName)
        applyOfflineCount(playlistId)
        countWaiting()
        say(
            if (queued) "Zu \"$playlistName\" hinzugefügt - wird nachgetragen."
            else "Zu \"$playlistName\" hinzugefügt."
        )
    }

    /**
     * Takes a song out of a playlist without a server.
     *
     * The download goes with it when nothing else holds it - the same rule the
     * online path follows, and the reason it is worth having offline too: the
     * point of taking a song out of a downloaded playlist is usually the space.
     */
    private fun offlineRemoveFromPlaylist(playlistId: Int, track: Track, onDone: () -> Unit) {
        pending.removeFromPlaylist(playlistId, track.id, track.itemId?.toInt() ?: 0)
        downloads.store.removeFromCollection(playlistId, track.id)
        val key = OfflineCollection(kind = "playlist", id = playlistId).key
        if (!downloads.store.isHeld(track.id, exceptKey = key)) downloads.remove(track.id)
        applyOfflineCount(playlistId)
        countWaiting()
        onDone()
        say("Entfernt - wird nachgetragen.")
    }

    /** The sidebar count of a playlist that changed while there was no server. */
    private fun applyOfflineCount(playlistId: Int) {
        val songs = downloads.store.collectionOf("playlist", listOf(playlistId))?.trackIds.orEmpty()
            .mapNotNull { downloads.store.entryOf(it)?.track }
        downloads.store.updateTree {
            TreeEdits.setCount(it, playlistId, songs.size, songs.sumOf { t -> t.duration })
        }
        refreshQuietly()
    }

    // --- Messages -------------------------------------------------------------

    fun say(text: String, isError: Boolean = false) {
        _toast.value = Toast(text, isError)
    }

    fun clearToast() {
        _toast.value = null
    }

    fun message(error: Throwable): String = when (error) {
        is ApiException -> error.message
        else -> error.message?.takeIf { it.isNotBlank() } ?: NO_SERVER
    }

    private companion object {
        const val NO_SERVER = "Der Server ist nicht erreichbar."
        const val OFFLINE_TOAST = "Offline - du hörst deine Downloads."
    }
}
