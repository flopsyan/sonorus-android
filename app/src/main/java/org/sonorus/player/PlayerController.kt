package org.sonorus.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.sonorus.data.Library
import org.sonorus.data.PlayLog
import org.sonorus.data.model.Chapter
import org.sonorus.data.Quality
import org.sonorus.data.QualityPolicy
import org.sonorus.data.Settings
import org.sonorus.data.Shuffle
import org.sonorus.data.sync.PendingWrites
import org.sonorus.data.SonorusApi
import org.sonorus.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the UI draws. */
data class PlayerState(
    val queue: List<Track> = emptyList(),
    /** Indices into [queue] - the order playback actually follows. */
    val order: List<Int> = emptyList(),
    val pos: Int = -1,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    /** `off`, `all` or `one`. */
    val repeat: String = "off",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Where the queue came from, e.g. "Album: Kid A". */
    val source: String = "",
    /**
     * The *route* the queue was started from - `albums/3`, `stars/5,4`,
     * `downloads`. The label above says it in words for a human; this one is
     * for comparing, so that a list can tell whether the song it is drawing is
     * playing from *it* or from somewhere else entirely.
     *
     * Empty for a queue nothing named a list for: one built by hand out of
     * single tracks belongs to no page, so every row marks itself as playing
     * from elsewhere. That is correct rather than a gap.
     */
    val sourceKey: String = "",
) {
    val current: Track? get() = order.getOrNull(pos)?.let { queue.getOrNull(it) }
    val hasNext: Boolean get() = repeat != "off" || pos < order.size - 1
    val hasPrevious: Boolean get() = order.isNotEmpty()
    /** The real upcoming order, which is what shuffling once up front buys. */
    val upcoming: List<Track> get() = order.drop(pos + 1).mapNotNull { queue.getOrNull(it) }
}

/**
 * The player.
 *
 * The queue model is taken from `public/js/player.js` rather than from
 * ExoPlayer's own shuffle, and that is deliberate: two lists, [PlayerState.queue]
 * (what you added) and [PlayerState.order] (indices into it, the order playback
 * follows). Shuffling **rewrites `order` once** instead of picking a random
 * track at each transition, which is the only way the queue panel can show the
 * real upcoming order - a requirement carried over from the web app. ExoPlayer
 * is therefore always handed a plain, already-ordered playlist with its own
 * shuffle switched off.
 *
 * What rewrites it is [Shuffle], not `shuffled()`: a plain permutation is
 * correct and still puts the same interpret next to itself often enough that the
 * shuffle sounds stuck on one artist.
 *
 * Repeat is ExoPlayer's, because the two models agree there.
 *
 * **There is deliberately no volume here.** The account carries a `volume` from
 * the web app, where an in-page slider is the only one there is, and applying it
 * on a phone was a bug with no way out: it multiplies whatever the hardware keys
 * already set, and the app shows no slider to undo it with. A slider left low in
 * the browser - perfectly reasonable on a desktop that makes the loudness up in
 * its own mixer - then played every song on the phone at that fraction with the
 * volume already against the stop, in the car as much as over Bluetooth. On
 * Android the loudness belongs to the system, so the player runs at unity and
 * the pref is left to the client it belongs to; [org.sonorus.ui.AppViewModel]
 * writes it back untouched.
 */
@UnstableApi
class PlayerController(
    context: Context,
    private val api: SonorusApi,
    private val library: Library,
    private val settings: Settings,
    /** Whether the original may be asked for on this connection - see [QualityPolicy]. */
    private val quality: QualityPolicy,
    private val playLog: PlayLog,
    /** Where a position written without a server waits for one. */
    private val pending: PendingWrites,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Positions in [PlayerState.queue] that were really played, oldest first. */
    private val history = ArrayDeque<Int>()

    /** How many times in a row playback has been rescued - see [onPlayerError]. */
    private var recoveries = 0

    /** Called when a rating or a play is written, so the UI can refresh. */
    var onPlayCounted: (() -> Unit)? = null

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            // Two kinds of source, one factory. `DefaultDataSource` reads
            // `file://` itself and hands everything else to the base factory -
            // which has to be the OkHttp client the API uses, because the stream
            // endpoint needs the session cookie and the stock data source would
            // send none and get a 401 on every track.
            //
            // Handing OkHttp the file URIs instead would break offline playback
            // outright: it speaks HTTP and nothing else. This one line is what
            // lets a downloaded song play with no network at all.
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, OkHttpDataSource.Factory(api.client))
            )
        )
        // Without this the player is built with `CONTENT_TYPE_UNKNOWN` and asks
        // for no audio focus at all, and both cost loudness. A phone and a car
        // put a stream on their music path - and through whatever loudness and
        // EQ processing sits on it - by what the stream says it is, so a song
        // that says nothing plays dry and audibly under every other app while
        // the device is already at maximum. Focus is the other half: it is what
        // makes a call, a navigation prompt or another player interrupt this one
        // instead of talking over it, and what makes this one duck rather than
        // fight.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            shuffleModeEnabled = false
            addListener(PlayerListener())
        }

    // --- Play counting --------------------------------------------------------
    // The statistics count time actually listened, so pausing, skipping ahead
    // and leaving early all have to show up - the track length alone would never
    // tell.

    private var playCounted = false
    private var playId: Int? = null

    /** The handle of a play written to [PlayLog] because the phone was offline. */
    private var pendingPlay: Long? = null

    // --- Spoken word ----------------------------------------------------------
    // Where the listener is in an episode or a book part, and which chapter of a
    // book that second falls in. Both are what make a 50-hour file usable: one
    // so it can be left and come back to, the other so the skip buttons and the
    // notification have something to move between.

    /** The track whose position is being reported, and the last value sent. */
    private var progressTrack: Int? = null
    private var progressSent = 0.0

    /** The chapters of the running title, and which one the playhead is in. */
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()
    private var chapterBook: Int? = null

    private val _chapterAt = MutableStateFlow(-1)

    /** Index into [chapters] of the one being heard, or -1. */
    val chapterAt: StateFlow<Int> = _chapterAt.asStateFlow()

    /**
     * Hands the player the chapters of the book that is playing. Called by the
     * view model when the running track belongs to a different book - the same
     * "load it once per book" the lyrics follow per song.
     */
    fun setChapters(bookId: Int?, list: List<Chapter>) {
        chapterBook = bookId
        _chapters.value = list
        _chapterAt.value = -1
        followChapter()
    }

    /** The chapters that lie inside the running *file*, in order. */
    private fun chaptersHere(): List<Chapter> {
        val track = _state.value.current ?: return emptyList()
        if (track.audiobookId != chapterBook) return emptyList()
        val at = _state.value.order.getOrNull(_state.value.pos) ?: return emptyList()
        val partIndex = _state.value.order.indexOf(at)
        return _chapters.value.filter { it.part == partIndex }
    }

    private fun followChapter() {
        val here = chaptersHere()
        if (here.isEmpty()) {
            if (_chapterAt.value != -1) _chapterAt.value = -1
            return
        }
        val at = exoPlayer.currentPosition / 1000.0
        var found = here.first()
        for (chapter in here) {
            if (chapter.start <= at + 0.01 || chapter.offset <= at + 0.01) found = chapter else break
        }
        if (_chapterAt.value != found.index) _chapterAt.value = found.index
    }

    /** The chapter being heard, or null. */
    fun currentChapter(): Chapter? =
        _chapters.value.firstOrNull { it.index == _chapterAt.value }

    /**
     * One chapter back or forward, and whether there was one to move to.
     *
     * Back puts the playhead at the start of the chapter first and only leaves
     * it on a second press inside [RESTART_AFTER_MS] - the same rule "back" already
     * follows for a track, and the one a listener wants from a book: the usual
     * reason to press it is having missed the last minute.
     */
    private fun skipChapter(forward: Boolean): Boolean {
        val here = chaptersHere()
        if (here.isEmpty()) return false
        val current = currentChapter() ?: return false
        val at = here.indexOfFirst { it.index == current.index }
        if (at < 0) return false
        val into = exoPlayer.currentPosition / 1000.0 - current.offset

        if (!forward) {
            if (into > RESTART_AFTER_MS / 1000.0) {
                exoPlayer.seekTo((current.offset * 1000).toLong())
                return true
            }
            if (at == 0) return false
            exoPlayer.seekTo((here[at - 1].offset * 1000).toLong())
            return true
        }
        if (at + 1 >= here.size) return false
        exoPlayer.seekTo((here[at + 1].offset * 1000).toLong())
        return true
    }

    /**
     * Sends where the listener is, for an episode or a book part only.
     *
     * "Close enough to the end" is `min(30, duration * 5%)` and not a flat 30
     * seconds - the web app learned that the hard way: against a 70-minute
     * episode 30 s is nothing, against a 40-second book part it calls everything
     * past second ten finished. Seeking back out of the tail un-finishes it,
     * which is why the track is still followed after it was marked complete.
     */
    private fun reportProgress(force: Boolean = false) {
        val track = _state.value.current ?: return
        if (!track.isSpoken) return
        val position = exoPlayer.currentPosition / 1000.0
        val duration = exoPlayer.duration.takeIf { it > 0 }?.div(1000.0) ?: track.duration
        if (duration <= 0) return
        if (!force && progressTrack == track.id && kotlin.math.abs(position - progressSent) < PROGRESS_EVERY) return

        progressTrack = track.id
        progressSent = position
        val tail = minOf(30.0, duration * 0.05)
        val done = position >= duration - tail

        // Written onto the download first, and while online as well as offline.
        // A downloaded book that forgets its place the moment the network goes
        // is the whole reason to download one undone, and the row has to be
        // right *before* the connection is lost rather than from the next sync.
        library.store.applyProgress(track.id, position, done)
        if (library.offline.value) {
            pending.progress(track.id, position, done)
            return
        }
        scope.launch { runCatching { api.setProgress(track.id, position, done) } }
    }
    private var listened = 0.0
    private var lastTick = -1.0
    private var reported = 0
    private var ticker: Job? = null

    /**
     * Spotify's rule, and the one the web client uses: a track counts after 30
     * seconds of real playback. A track shorter than that can never reach it,
     * so for those a third of the length is the mark.
     */
    private fun countThreshold(durationSeconds: Double): Double =
        if (durationSeconds < COUNT_AFTER) durationSeconds / 3.0 else COUNT_AFTER

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val playing = exoPlayer.isPlaying
        val positionSec = exoPlayer.currentPosition / 1000.0
        _state.value = _state.value.copy(
            positionMs = exoPlayer.currentPosition,
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0,
            playing = playing,
        )
        if (!playing) {
            lastTick = -1.0
            return
        }

        // The playhead as it moves, so closing the app and opening it again
        // comes back to the second the song was left at rather than to its
        // start. Every few seconds is enough: being wrong costs a handful of
        // seconds of music, where writing on every tick would touch storage
        // twice a second for the rest of the song. The comparison is absolute
        // on purpose - a jump *backwards* has to be written down too, or the
        // app reopens further along than it was left.
        if (abs(positionSec - savedPositionMs / 1000.0) >= SAVE_POSITION_EVERY) saveQueue()

        // Only a step that looks like ordinary playback counts. A larger one is
        // a seek, and seeking past 30 seconds must not count as having heard it.
        if (lastTick >= 0) {
            val step = positionSec - lastTick
            if (step > 0 && step < MAX_STEP) listened += step
        }
        lastTick = positionSec

        // Where the listener is in an episode or a book, and which chapter that
        // is. Both return at once unless something actually changed.
        reportProgress()
        followChapter()

        val track = _state.value.current ?: return
        val duration = track.duration
        if (!playCounted && duration > 0 && listened >= countThreshold(duration)) {
            playCounted = true
            reported = listened.roundToInt()
            val id = track.id
            val seconds = listened
            // Offline there is nobody to tell, and a request per song that can
            // only time out is worth not making - so the play is written down
            // instead and goes out with the next connection. It carries the
            // moment it happened, because the server would otherwise stamp it
            // on arrival and file a fortnight of holiday under one day.
            if (library.offline.value) {
                pendingPlay = playLog.record(id, reported)
            } else {
                scope.launch {
                    runCatching { api.startPlay(id, seconds) }
                        .onSuccess {
                            playId = it.playId
                            onPlayCounted?.invoke()
                        }
                }
            }
        } else if ((playId != null || pendingPlay != null) && listened - reported >= REPORT_EVERY) {
            reportListening()
        }
    }

    /**
     * Sends the running total for this play, or corrects it on the phone while
     * the play is one that has not been sent yet.
     */
    private fun reportListening() {
        val total = listened.roundToInt()
        if (total <= reported) return
        val pending = pendingPlay
        val id = playId
        reported = total
        when {
            pending != null -> playLog.update(pending, total)
            id != null -> scope.launch { runCatching { api.updatePlay(id, total.toDouble()) } }
        }
    }

    /**
     * Flushes the current play and starts counting fresh. Playing the same song
     * again is a second play, so this also runs on repeat-one and on both
     * restart paths of [previous].
     */
    private fun resetListening() {
        reportListening()
        playCounted = false
        playId = null
        pendingPlay = null
        listened = 0.0
        lastTick = -1.0
        reported = 0
    }

    // --- Persistence ----------------------------------------------------------
    // Coming back to what was playing is what the web app has had all along
    // (`public/js/player.js`), and the app had none of it: every start opened on
    // an empty player. The queue is a fact about this phone, so it lives in
    // SharedPreferences next to the two download switches and not on the
    // account - the same line the web app draws by keeping it in localStorage.

    private val store = Json { ignoreUnknownKeys = true }

    /**
     * False until [restoreQueue] has had its turn.
     *
     * Nothing may be written before then, or the empty player every start opens
     * with would overwrite the queue it is about to be given - and a start that
     * never gets past the login screen would throw it away for good.
     */
    private var persisting = false

    /** Where the playhead stood when it was last written down. */
    private var savedPositionMs = 0L

    init {
        // Every queue change ends up in the state, so one collector catches all
        // of them - rather than a save() sprinkled through the ten methods that
        // move the queue, which is the shape the web app has and the one that
        // is easy to forget in the eleventh.
        scope.launch {
            _state
                .map { QueueKey(it.queue.map(Track::id), it.order, it.pos, it.source, it.sourceKey) }
                .distinctUntilChanged()
                .collect { saveQueue() }
        }
    }

    /**
     * Writes the queue down. Public because leaving the app is a moment worth
     * catching too, and only the activity knows about that one.
     */
    fun saveQueue() {
        if (!persisting) return
        val state = _state.value
        savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0)
        settings.playerQueue = if (state.queue.isEmpty()) null else store.encodeToString(
            SavedQueue.serializer(),
            SavedQueue(
                ids = state.queue.map { it.id },
                order = state.order,
                pos = state.pos,
                source = state.source,
                sourceKey = state.sourceKey,
                positionMs = savedPositionMs,
            ),
        )
    }

    /**
     * Puts the last queue back, **cued and not playing**. Spotify sets the song
     * up and waits for the button; a phone that starts singing because it was
     * unlocked is a different thing entirely.
     *
     * Runs once per app start, after the account is known, and is a no-op from
     * the second call on. Offline the songs come out of the download snapshot,
     * which is exactly the case the queue is worth keeping for.
     *
     * The stored `order` is a list of positions into the *old* queue, so a
     * single song that has disappeared from the library since shifts every
     * position behind it onto a different one. It is therefore only usable when
     * every id came back - otherwise the queue is rebuilt in plain order, which
     * loses the shuffle but never plays the wrong song. Same rule as the web.
     */
    suspend fun restoreQueue() {
        if (persisting) return
        val stored = runCatching {
            settings.playerQueue?.let { store.decodeFromString(SavedQueue.serializer(), it) }
        }.getOrNull()
        try {
            if (stored == null || stored.ids.isEmpty()) return
            val tracks = runCatching { library.tracksByIds(stored.ids).tracks }.getOrNull().orEmpty()
            if (tracks.isEmpty()) return

            val complete = tracks.size == stored.ids.size
            val order = stored.order.takeIf {
                complete && it.size == tracks.size &&
                    it.all { i -> i in tracks.indices } && it.distinct().size == it.size
            } ?: tracks.indices.toList()
            val pos = stored.pos.coerceIn(0, order.size - 1)

            _state.value = _state.value.copy(
                queue = tracks,
                order = order,
                pos = pos,
                source = stored.source,
                sourceKey = stored.sourceKey,
                positionMs = stored.positionMs,
            )
            savedPositionMs = stored.positionMs
            // Deliberately no `playWhenReady`: this cues the song up, it does
            // not start it.
            pushPlaylist(order.map { tracks[it] }, pos, stored.positionMs)
        } finally {
            // Even a restore that found nothing has had its turn, or nothing
            // played afterwards would ever be written down either.
            persisting = true
        }
    }

    // --- Queue ----------------------------------------------------------------

    /** A missing file has no business in the queue - it cannot be played. */
    private fun playable(tracks: List<Track>) = tracks.filter { !it.missing }

    /** A queue in the order playback will follow, and where it starts. */
    data class Queued(val tracks: List<Track>, val startIndex: Int)

    /**
     * Plays a list from [startIndex]. The index is remapped onto the filtered
     * list, or clicking row 5 of a list with a missing row 2 would start the
     * wrong song.
     */
    /**
     * `startAtSeconds` is for spoken word: an episode or a book part is opened
     * *where it was left*, and a chapter tapped in a list is opened at its own
     * offset. Handing it to `setMediaItems` rather than seeking afterwards is
     * what stops the file being opened at zero and jumped a moment later.
     */
    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
        source: String = "",
        sourceKey: String = "",
        startAtSeconds: Double = -1.0,
    ) {
        val queued = adoptQueue(tracks, startIndex, source, sourceKey)
        if (queued.tracks.isEmpty()) return
        val at = if (startAtSeconds >= 0) startAtSeconds
        else queued.tracks.getOrNull(queued.startIndex)?.resumeAt ?: 0.0
        pushPlaylist(queued.tracks, queued.startIndex, (at * 1000).toLong())
        exoPlayer.playWhenReady = true
    }

    /**
     * A collection put on from its "Zufällig" button.
     *
     * Nothing was tapped here, so no song has earned the front of the queue -
     * and that is the whole point: [adoptQueue] keeps the index it is given in
     * front while shuffling, so a fixed zero opened every random run of a genre
     * or an artist with the same song, the first row of the list. Only the rest
     * was random. The opener is drawn like every other position instead.
     *
     * Drawn from what can actually be played rather than from [tracks]: a
     * missing file is dropped from the queue, and drawing one would fall back to
     * the front of the list - exactly the song this is here to avoid.
     */
    fun shuffleTracks(tracks: List<Track>, source: String = "", sourceKey: String = "") {
        if (!_state.value.shuffle) setShuffle(true)
        val list = playable(tracks)
        if (list.isEmpty()) return
        playTracks(list, list.indices.random(), source, sourceKey)
    }

    /**
     * The play button of a collection: this list, from the top or dealt out,
     * according to whether shuffle is **already** armed.
     *
     * The distinction from [shuffleTracks] is the whole of Spotify's rule and
     * the reason this exists. Shuffle there is a switch, not a verb: throwing it
     * arms the next thing you play and starts nothing by itself. Sonorus had it
     * as a second play button, so the one control that is supposed to be
     * harmless to try started the music every time it was touched.
     */
    fun playCollection(tracks: List<Track>, source: String = "", sourceKey: String = "") {
        val list = playable(tracks)
        if (list.isEmpty()) return
        // Shuffled, no song has earned the front of the queue - a fixed zero
        // would open every random run of an album with its first track and only
        // shuffle the rest.
        val start = if (_state.value.shuffle) list.indices.random() else 0
        playTracks(list, start, source, sourceKey)
    }

    /**
     * The same queue, but for a caller that hands the songs to ExoPlayer itself:
     * Android Auto, where the session sets the playlist the moment a row is
     * tapped. Only the app's own view of the queue is written here - pushing the
     * playlist as well would build it twice and prepare the player twice.
     *
     * The order it returns is the one that must be handed over: shuffle is a
     * rewritten order in this app, not a mode of ExoPlayer's, and the car has to
     * play the same order the phone would.
     */
    fun adoptQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        source: String = "",
        sourceKey: String = "",
    ): Queued {
        val wanted = tracks.getOrNull(startIndex)
        val list = playable(tracks)
        if (list.isEmpty()) return Queued(emptyList(), 0)
        val start = wanted?.let { w -> list.indexOfFirst { it.id == w.id }.takeIf { it >= 0 } } ?: 0

        history.clear()
        val shuffle = _state.value.shuffle
        val order = if (shuffle) {
            // Keep the track that was clicked, spread everything else behind it -
            // and not with its own name first, which is the one repeat the spread
            // cannot see for itself. See [Shuffle].
            val rest = Shuffle.spread(
                items = list.indices.filter { it != start },
                avoid = Shuffle.artistOf(list[start]),
            ) { Shuffle.artistOf(list[it]) }
            listOf(start) + rest
        } else {
            list.indices.toList()
        }
        val pos = if (shuffle) 0 else start

        _state.value = _state.value.copy(
            queue = list, order = order, pos = pos, source = source, sourceKey = sourceKey,
        )
        resetListening()
        startTicker()
        return Queued(order.map { list[it] }, pos)
    }

    fun enqueue(tracks: List<Track>) {
        val list = playable(tracks)
        if (list.isEmpty()) return
        val state = _state.value
        val queue = state.queue + list
        val added = list.indices.map { state.queue.size + it }
        val order = state.order + added
        _state.value = state.copy(queue = queue, order = order)
        for (track in list) exoPlayer.addMediaItem(mediaItem(track))
        if (state.pos < 0) {
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            // Nothing named a list here: the queue was built by hand out of
            // single tracks, so it belongs to no page and every row marks
            // itself as playing from somewhere else. Which is what happened.
            _state.value = _state.value.copy(pos = 0, sourceKey = "")
            startTicker()
        }
    }

    /** Puts tracks right behind the running one. */
    fun playNext(tracks: List<Track>) {
        val list = playable(tracks)
        if (list.isEmpty()) return
        val state = _state.value
        if (state.pos < 0) return playTracks(list)
        val queue = state.queue + list
        val added = list.indices.map { state.queue.size + it }
        val order = state.order.toMutableList().apply { addAll(state.pos + 1, added) }
        _state.value = state.copy(queue = queue, order = order)
        list.forEachIndexed { i, track ->
            exoPlayer.addMediaItem(exoPlayer.currentMediaItemIndex + 1 + i, mediaItem(track))
        }
    }

    fun clearQueue() {
        resetListening()
        history.clear()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = _state.value.copy(
            queue = emptyList(), order = emptyList(), pos = -1,
            playing = false, source = "", sourceKey = "", positionMs = 0, durationMs = 0,
        )
        ticker?.cancel()
        ticker = null
    }

    /** Removes one entry of the play order without disturbing the rest. */
    fun removeFromQueue(orderIndex: Int) {
        val state = _state.value
        if (orderIndex !in state.order.indices || orderIndex == state.pos) return
        val order = state.order.toMutableList().apply { removeAt(orderIndex) }
        val pos = if (orderIndex < state.pos) state.pos - 1 else state.pos
        _state.value = state.copy(order = order, pos = pos)
        exoPlayer.removeMediaItem(orderIndex)
    }

    /**
     * Moves one entry of the play order, leaving the running track where it is.
     *
     * Only what is still to come can move: a move that landed on or before the
     * current position would push the running song into a past it has already
     * left, and the panel offers no handle there for the same reason. [from] and
     * [to] read as they do in `moveInQueue` in the web app - the entry is taken
     * out first and put back at [to] in the shortened list, which is what
     * `Player.moveMediaItem` does too, so the two lists stay in step.
     *
     * The position is read back off the new order rather than adjusted, because
     * the order *is* the answer to where the running track ended up.
     */
    fun moveInQueue(from: Int, to: Int) {
        val state = _state.value
        if (from == to || from !in state.order.indices || to !in state.order.indices) return
        if (from == state.pos || to <= state.pos) return
        val current = state.order.getOrNull(state.pos) ?: return
        val order = state.order.toMutableList().apply { add(to, removeAt(from)) }
        _state.value = state.copy(order = order, pos = order.indexOf(current))
        // Nothing around the running item is re-opened by this, so the sound
        // carries on - unlike a fresh setMediaItems, see [rearrangeAround].
        exoPlayer.moveMediaItem(from, to)
    }

    // --- Transport ------------------------------------------------------------

    fun play() {
        exoPlayer.playWhenReady = true
        startTicker()
    }

    fun pause() {
        exoPlayer.playWhenReady = false
        reportListening()
        // Nothing moves after this, so the last position would otherwise stay
        // up to SAVE_POSITION_EVERY seconds stale for as long as it is paused.
        saveQueue()
    }

    fun toggle() = if (exoPlayer.isPlaying) pause() else play()

    fun next() {
        val state = _state.value
        if (state.order.isEmpty()) return
        // Inside a book "next" means one chapter, not one file - there is only
        // ever the one file, so the old meaning had nothing to do. Running out
        // of chapters falls through to the queue, so a book of several parts
        // carries on into the next file instead of stopping dead.
        if (skipChapter(forward = true)) {
            resetListening()
            return
        }
        pushHistory()
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        } else if (state.repeat == "all") {
            // Wrapping around deals a fresh order while shuffled.
            if (state.shuffle) reshuffleFromStart() else exoPlayer.seekTo(0, 0)
        }
    }

    /**
     * Back either starts the track over or goes back to what played before, and
     * what "before" means depends on the mode. Shuffled it comes from the
     * history, because the queue re-deals its order when it wraps and the track
     * that played is anywhere but one position back. Without shuffle the play
     * order *is* the order on screen, so "before" is one step down it.
     *
     * The history is emptied whenever the order is re-dealt by hand, so right
     * after shuffle was switched on there is nothing behind the running song and
     * back can only start it over. That is deliberate: the songs before it were
     * picked in an order that no longer exists.
     *
     * [restartFirst] is the classic "first press restarts the song" rule, and
     * only the transport button keeps it. A wipe right over the artwork sets it
     * false and always leaves for the song before - a wipe is a page turn, and a
     * page turn that sometimes only scrolls the page back to the top reads as
     * the gesture having been missed. Spotify draws the same line.
     */
    fun previous(restartFirst: Boolean = true) {
        val state = _state.value
        if (state.order.isEmpty()) return

        // More than three seconds in, the first press starts the track over. The
        // second press is then inside those three seconds and goes back for
        // real.
        // One chapter back, for the same reason "next" moves one forward.
        if (skipChapter(forward = false)) {
            resetListening()
            return
        }

        if (restartFirst && exoPlayer.currentPosition > RESTART_AFTER_MS) {
            resetListening()
            exoPlayer.seekTo(0)
            return
        }

        val target = if (state.shuffle) popHistory(state.order) else state.pos - 1
        if (target == null || target < 0) {
            resetListening()
            exoPlayer.seekTo(0)
            return
        }
        resetListening()
        exoPlayer.seekTo(target, 0)
    }

    fun jumpTo(orderIndex: Int) {
        val state = _state.value
        if (orderIndex !in state.order.indices) return
        pushHistory()
        resetListening()
        exoPlayer.seekTo(orderIndex, 0)
        exoPlayer.playWhenReady = true
        startTicker()
    }

    /**
     * Reopens the running track at the quality that is set now.
     *
     * Only ever for a **streamed** song: a download is the file it is, and
     * nothing here re-fetches one. `rearrangeAround` cannot help - the URI has
     * changed, so the item really does have to be built again - but the position
     * and whether it was playing are carried over, so the switch costs the
     * buffer and nothing else.
     */
    fun reopenAtCurrentQuality() {
        val track = _state.value.current ?: return
        if (library.store.fileOf(track.id) != null) return
        val at = exoPlayer.currentPosition.coerceAtLeast(0)
        val wasPlaying = exoPlayer.playWhenReady
        val index = exoPlayer.currentMediaItemIndex
        exoPlayer.replaceMediaItem(index, mediaItem(track))
        exoPlayer.seekTo(index, at)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    fun seekTo(ms: Long) {
        exoPlayer.seekTo(ms)
        // The rail is let go and drawn from the state again in the same frame,
        // so the new position has to be in it already - waiting for the next
        // tick lets the playhead snap back for half a second first.
        _state.value = _state.value.copy(positionMs = ms)
        // A seek is not listening; the counter carries on from the new spot.
        lastTick = -1.0
    }

    // --- Modes ----------------------------------------------------------------

    fun setShuffle(on: Boolean) {
        val state = _state.value
        if (state.order.isEmpty()) {
            _state.value = state.copy(shuffle = on)
            if (!on) history.clear()
            return
        }
        val current = state.order[state.pos]
        val order: List<Int>
        val pos: Int
        if (on) {
            val rest = Shuffle.spread(
                items = state.order.filter { it != current },
                avoid = Shuffle.artistOf(state.queue[current]),
            ) { Shuffle.artistOf(state.queue[it]) }
            order = listOf(current) + rest
            pos = 0
        } else {
            // Back to the order the tracks were added in - sorted from what is
            // in the play order, because rebuilding from `queue` would put
            // removed tracks back in.
            order = state.order.sorted()
            pos = order.indexOf(current)
        }
        // Either way the order has just been re-dealt, so the path walked before
        // it leads nowhere any more: "back" would look up a track that now sits
        // somewhere else entirely, jump there, and carry on with a completely
        // different set of songs behind it. Switching shuffle therefore starts a
        // fresh path - back restarts the running song until it has really moved
        // on, and after ten songs it walks those ten.
        history.clear()
        _state.value = state.copy(order = order, pos = pos, shuffle = on)
        rearrangeAround(order.map { state.queue[it] }, pos)
    }

    private fun reshuffleFromStart() {
        val state = _state.value
        // A new round must not open with the interpret the last one ended on.
        val last = state.order.getOrNull(state.pos)?.let { Shuffle.artistOf(state.queue[it]) }
        val order = Shuffle.spread(state.order, avoid = last) { Shuffle.artistOf(state.queue[it]) }
        _state.value = state.copy(order = order, pos = 0)
        pushPlaylist(order.map { state.queue[it] }, 0, 0)
        exoPlayer.playWhenReady = true
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeat) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        setRepeat(next)
    }

    fun setRepeat(mode: String) {
        _state.value = _state.value.copy(repeat = mode)
        exoPlayer.repeatMode = when (mode) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // --- Internals ------------------------------------------------------------

    private fun pushHistory() {
        val state = _state.value
        val current = state.order.getOrNull(state.pos) ?: return
        history.addLast(current)
        if (history.size > HISTORY_MAX) history.removeFirst()
    }

    /** The last played track still in the play order, as an index into it. */
    private fun popHistory(order: List<Int>): Int? {
        while (history.isNotEmpty()) {
            val at = order.indexOf(history.removeLast())
            if (at >= 0) return at
        }
        return null
    }

    /**
     * A downloaded song is played from the phone, always - not only when there
     * is no network. That is what makes a download worth having on a mobile
     * connection: the same song, and not a byte of data for it.
     */
    fun mediaItem(track: Track): MediaItem {
        val local = library.store.fileOf(track.id)
        return MediaItem.Builder()
            .setUri(
                local?.let { Uri.fromFile(it) }
                    // Streamed at whatever this phone is set to. A download is
                    // never re-fetched for it: what is here is here, and the
                    // point of a download is that nothing is fetched twice.
                    // The policy, not the plain setting: on mobile data with
                    // "Lossless nur über WLAN" on, this is Opus even though the
                    // setting says original.
                    ?: api.streamUrl(track.id, quality.qualityNow()).toUri()
            )
            .setMediaId(track.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album.ifEmpty { null })
                    // The downloaded cover for the same reason: the notification
                    // draws artwork, and offline there is nothing to fetch.
                    .setArtworkUri(library.coverUrl(track.cover)?.toUri())
                    .setTrackNumber(track.trackNo)
                    .setReleaseYear(track.year)
                    .build()
            )
            .build()
    }

    /**
     * What [track] is really being heard as right now.
     *
     * Three cases, and they genuinely differ. A downloaded song is whatever it
     * was fetched as, which the index remembers - changing the setting later
     * does not reach back and change a file already on the phone. A streamed one
     * is what the server would serve for the current setting, worked out by the
     * same rule the server uses. And a song that is not playing at all has no
     * answer, so it gets the setting's own name.
     */
    fun servedQuality(track: Track): Quality {
        library.store.entryOf(track.id)?.let { return Quality.of(it.quality) }
        return Quality.served(track, quality.qualityNow())
    }

    private fun pushPlaylist(tracks: List<Track>, index: Int, positionMs: Long) {
        exoPlayer.setMediaItems(tracks.map(::mediaItem), index, positionMs)
        exoPlayer.prepare()
    }

    /**
     * Lays a new play order over the running track without touching it.
     *
     * [pushPlaylist] would be the short way, but `setMediaItems` throws the
     * whole playlist away and builds it again - including the item playing right
     * now, which is then re-opened and buffered from scratch. That is a tenth of
     * a second of silence in the middle of a song, and on a phone it is exactly
     * the kind of gap you hear. Everything *around* the current item is replaced
     * instead: ExoPlayer keeps the one it is playing and the audio never stops.
     *
     * [tracks] is the full new order and [index] the place the running track
     * takes in it.
     */
    private fun rearrangeAround(tracks: List<Track>, index: Int) {
        val at = exoPlayer.currentMediaItemIndex
        val count = exoPlayer.mediaItemCount
        // Nothing to keep - only reachable if the playlist was never pushed.
        if (count == 0 || at >= count) return pushPlaylist(tracks, index, 0)
        exoPlayer.removeMediaItems(at + 1, count)
        exoPlayer.removeMediaItems(0, at)
        // Only the running track is left, and it sits at 0.
        if (index > 0) exoPlayer.addMediaItems(0, tracks.take(index).map(::mediaItem))
        exoPlayer.addMediaItems(tracks.drop(index + 1).map(::mediaItem))
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The part that is being left keeps its position, or a book of
            // several files would forget the boundary it just crossed.
            reportProgress(force = true)
            progressTrack = null
            progressSent = 0.0
            // Repeat-one plays the same row again, which is a second play.
            resetListening()
            val index = exoPlayer.currentMediaItemIndex
            // The next part of a book carries its own position, and an automatic
            // advance would otherwise start it at zero - the one place a book of
            // several files could still lose where the listener was.
            val next = _state.value.let { it.queue.getOrNull(it.order.getOrNull(index) ?: -1) }
            if (next != null && next.isSpoken && next.resumeAt > 1.0 && exoPlayer.currentPosition < 1000) {
                exoPlayer.seekTo((next.resumeAt * 1000).toLong())
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // An automatic advance still belongs in the history, or "back"
                // after a run of songs would have nothing to walk.
                val state = _state.value
                state.order.getOrNull(state.pos)?.let {
                    history.addLast(it)
                    if (history.size > HISTORY_MAX) history.removeFirst()
                }
            }
            _state.value = _state.value.copy(pos = index)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
            if (isPlaying) startTicker() else reportListening()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) reportListening()
            if (playbackState == Player.STATE_READY) {
                recoveries = 0
                _state.value = _state.value.copy(
                    durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0,
                )
            }
        }

        /**
         * The network went away mid-song.
         *
         * This used to surface as `Unable to resolve host "sonorus.flopsyan.eu"`
         * in the media notification, which is both ugly and useless: the phone
         * knows perfectly well that it has downloads, and Spotify in the same
         * situation simply carries on with them. So does this now.
         *
         * Three steps, in order. Tell the library the server is gone, so every
         * screen switches over and the banner appears rather than a list of
         * songs that cannot be opened. Then rebuild the queue out of what is
         * actually on the phone. Then keep playing where it makes sense to.
         *
         * A player error is **consumed** here rather than left standing:
         * ExoPlayer stays in its error state until something prepares it again,
         * and media3 shows that state in the notification. Recovering is what
         * takes it back off the lock screen.
         */
        override fun onPlayerError(error: PlaybackException) {
            if (!isNetworkError(error)) return
            library.markUnreachable()

            // A guard against the obvious loop: recovering into another track
            // that also cannot be played would try forever. STATE_READY resets
            // it, so an ordinary drop-out costs one recovery and no more.
            if (recoveries >= MAX_RECOVERIES) {
                exoPlayer.playWhenReady = false
                return
            }
            recoveries += 1
            recoverOffline()
        }
    }

    private fun isNetworkError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> true
        else -> false
    }

    /**
     * Carries on with what is on the phone after the network went.
     *
     * The running song keeps playing if it was downloaded - the same file, from
     * the second it stopped at. If it was being streamed, the queue moves on to
     * the next song that *is* here, because a queue that stops on the first
     * thing it cannot fetch is a queue that has ended.
     *
     * With nothing downloaded there is nothing to do but stop quietly. That is
     * not a failure to report: the phone has no network and no files, and a
     * message saying so is the banner the shell is already showing.
     */
    private fun recoverOffline() {
        val state = _state.value
        val here = state.order.filter { library.store.fileOf(state.queue[it].id) != null }
        if (here.isEmpty()) {
            exoPlayer.playWhenReady = false
            return
        }

        val current = state.order.getOrNull(state.pos)
        val keepCurrent = current != null && current in here
        val at = if (keepCurrent) here.indexOf(current) else {
            // The next downloaded song *after* where the queue stood, so the run
            // carries on forwards rather than jumping back to the top.
            here.indexOfFirst { state.order.indexOf(it) > state.pos }.takeIf { it >= 0 } ?: 0
        }
        val positionMs = if (keepCurrent) exoPlayer.currentPosition.coerceAtLeast(0) else 0

        _state.value = state.copy(order = here, pos = at)
        pushPlaylist(here.map { state.queue[it] }, at, positionMs)
        exoPlayer.playWhenReady = true
    }

    fun release() {
        reportListening()
        saveQueue()
        ticker?.cancel()
        exoPlayer.release()
    }

    private companion object {
        const val COUNT_AFTER = 30.0
        const val REPORT_EVERY = 20
        /** Seconds between two position reports for spoken word. */
        const val PROGRESS_EVERY = 10.0
        const val RESTART_AFTER_MS = 3_000L
        const val HISTORY_MAX = 100
        const val TICK_MS = 500L
        /** A step this large is a seek, not playback. */
        const val MAX_STEP = 2.0
        /** Seconds of playback between two writes of the playhead. */
        const val SAVE_POSITION_EVERY = 5.0

        /**
         * One rescue per drop-out. More than that means the songs it is falling
         * back on cannot be played either, and trying again would be a loop.
         */
        const val MAX_RECOVERIES = 1
    }
}

/**
 * The queue as it is put away between two runs of the app.
 *
 * Track *ids* rather than tracks: a song can be renamed, re-rated or removed
 * from the library while the app is closed, and coming back with a stale copy
 * of it would be worse than asking for it again. The whole thing is one JSON
 * string in [Settings.playerQueue].
 */
@Serializable
private data class SavedQueue(
    val ids: List<Int> = emptyList(),
    val order: List<Int> = emptyList(),
    val pos: Int = -1,
    val source: String = "",
    val sourceKey: String = "",
    val positionMs: Long = 0,
)

/**
 * What has to change before the queue is worth writing down again. The playhead
 * is deliberately not in it: it moves twice a second and has its own rule.
 */
private data class QueueKey(
    val ids: List<Int>,
    val order: List<Int>,
    val pos: Int,
    val source: String,
    val sourceKey: String,
)
