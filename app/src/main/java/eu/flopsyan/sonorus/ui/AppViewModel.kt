package eu.flopsyan.sonorus.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.SonorusApp
import eu.flopsyan.sonorus.data.ApiException
import eu.flopsyan.sonorus.data.SonorusApi
import eu.flopsyan.sonorus.data.model.Bootstrap
import eu.flopsyan.sonorus.data.model.Lyrics
import eu.flopsyan.sonorus.data.model.Prefs
import eu.flopsyan.sonorus.data.model.SortPref
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.data.model.TreeResponse
import eu.flopsyan.sonorus.player.PlayerController
import eu.flopsyan.sonorus.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** Where the app is before it can show anything. */
sealed interface AppPhase {
    data object Starting : AppPhase
    data class NeedsLogin(val message: String = "") : AppPhase
    data class Ready(val bootstrap: Bootstrap) : AppPhase
}

/** A one-off message shown in a snackbar, like the web app's toasts. */
data class Toast(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

@UnstableApi
class AppViewModel : ViewModel() {

    private val app: SonorusApp get() = SonorusApp.instance
    val api: SonorusApi get() = app.api
    val player: PlayerController get() = app.player

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
    }

    fun start() {
        viewModelScope.launch {
            if (!app.session.isConfigured) {
                _phase.value = AppPhase.NeedsLogin()
                return@launch
            }
            _phase.value = AppPhase.Starting
            runCatching { api.bootstrap() }
                .onSuccess { applyBootstrap(it) }
                .onFailure { error ->
                    // Only a genuine auth failure sends the user back to the
                    // login form; a server that is merely unreachable would
                    // otherwise look like wrong credentials.
                    val code = (error as? ApiException)?.code
                    if (code == "auth_required" || code == "bad_login") {
                        _phase.value = AppPhase.NeedsLogin(message(error))
                    } else {
                        _phase.value = AppPhase.NeedsLogin(message(error))
                    }
                }
        }
    }

    fun login(server: String, user: String, pass: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatching {
                api.login(server, user, pass)
                api.bootstrap()
            }.onSuccess {
                applyBootstrap(it)
                onDone(true)
            }.onFailure {
                _phase.value = AppPhase.NeedsLogin(message(it))
                onDone(false)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            player.clearQueue()
            api.logout()
            _phase.value = AppPhase.NeedsLogin()
        }
    }

    private fun applyBootstrap(data: Bootstrap) {
        _phase.value = AppPhase.Ready(data)
        // Player settings live on the account so they follow to another device.
        val p = data.prefs.player
        player.setVolume(p.volume)
        player.setMuted(p.muted)
        player.setShuffle(p.shuffle)
        player.setRepeat(p.repeat)
    }

    /** Reloads the sidebar numbers without flipping the screen to a spinner. */
    fun refreshQuietly() {
        viewModelScope.launch {
            runCatching { api.bootstrap() }.onSuccess { _phase.value = AppPhase.Ready(it) }
        }
    }

    // --- Preferences ----------------------------------------------------------
    // `users.prefs` is the answer to every "das soll dauerhaft so bleiben", and
    // it lives on the account rather than on the device.

    val prefs: Prefs get() = bootstrap?.prefs ?: Prefs()

    fun savePlayerPrefs() {
        val s = player.state.value
        val value = json.encodeToJsonElement(
            eu.flopsyan.sonorus.data.model.PlayerPrefs(
                volume = s.volume,
                muted = s.muted,
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
        viewModelScope.launch {
            runCatching { api.rate(trackId, next) }
                .onSuccess {
                    // The server's answer, not `next` - it is the one that counts,
                    // and writing it before the request would have to be undone
                    // again when the request fails.
                    ratings[trackId] = it.stars
                    onDone(it.stars)
                    refreshQuietly()
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
            runCatching { api.shuffle(60, unrated) }
                .onSuccess {
                    if (it.tracks.isEmpty()) {
                        say(if (unrated) "Alles ist bewertet." else "Hier gibt es nichts zum Abspielen.")
                    } else {
                        player.playTracks(it.tracks, 0, if (unrated) "Unbewertete" else "Zufall")
                    }
                }
                .onFailure { say(message(it), true) }
        }
    }

    /** Adds a track to a playlist and refreshes the sidebar counts. */
    fun addToPlaylist(playlistId: Int, trackId: Int, playlistName: String) {
        viewModelScope.launch {
            runCatching { api.addToPlaylist(playlistId, listOf(trackId)) }
                .onSuccess {
                    say("Zu \"$playlistName\" hinzugefügt.")
                    refreshQuietly()
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
            runCatching { api.lyrics(id) }
                // A track change while the answer was on its way must not land
                // on the song that is playing now.
                .onSuccess { if (lyricsFor == id) _lyrics.value = it.lyrics }
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
        _pendingAddCanCreate.value = allowCreate
        _pendingAdd.value = track
    }

    /** The single whose edit dialog is open, if any. */
    private val _editingSingle = MutableStateFlow<Track?>(null)
    val editingSingle: StateFlow<Track?> = _editingSingle.asStateFlow()

    fun editSingle(track: Track) {
        _editingSingle.value = track
    }

    fun closeSingleEditor() {
        _editingSingle.value = null
    }

    fun cancelAdd() {
        _pendingAdd.value = null
    }

    private fun withTree(onDone: () -> Unit = {}, block: suspend () -> TreeResponse) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { answer ->
                    bootstrap?.let { _phase.value = AppPhase.Ready(it.copy(playlists = answer.tree)) }
                    onDone()
                }
                .onFailure { say(message(it), true) }
        }
    }

    fun createPlaylist(name: String, folderId: Int? = null, then: (() -> Unit)? = null) =
        withTree(onDone = { then?.invoke(); say("Playlist \"$name\" angelegt.") }) {
            api.createPlaylist(name, folderId)
        }

    /**
     * Creates a playlist and puts the waiting track straight into it - the one
     * flow where a new list is never wanted empty.
     */
    fun createPlaylistWithTrack(name: String, track: Track) {
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

    fun renamePlaylist(id: Int, name: String, then: () -> Unit = {}) =
        withTree(then) { api.renamePlaylist(id, name) }

    fun pinPlaylist(id: Int, pinned: Boolean, then: () -> Unit = {}) =
        withTree(then) { api.pinPlaylist(id, pinned) }

    fun deletePlaylist(id: Int, then: () -> Unit = {}) =
        withTree({ then(); say("Playlist gelöscht.") }) { api.deletePlaylist(id) }

    fun createFolder(name: String) =
        withTree({ say("Ordner \"$name\" angelegt.") }) { api.createFolder(name) }

    fun renameFolder(id: Int, name: String) = withTree { api.renameFolder(id, name) }

    /** Deleting a folder keeps its playlists - they move to the top level. */
    fun deleteFolder(id: Int) =
        withTree({ say("Ordner gelöscht, die Playlists sind erhalten.") }) { api.deleteFolder(id) }

    fun movePlaylist(id: Int, folderId: Int?) = withTree { api.movePlaylist(id, folderId) }

    fun removeFromPlaylist(playlistId: Int, itemId: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { api.removeFromPlaylist(playlistId, itemId) }
                .onSuccess {
                    onDone()
                    refreshQuietly()
                }
                .onFailure { say(message(it), true) }
        }
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
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Der Server ist nicht erreichbar."
    }
}
