package eu.flopsyan.sonorus.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import eu.flopsyan.sonorus.data.Library
import eu.flopsyan.sonorus.data.Shuffle
import eu.flopsyan.sonorus.data.SonorusApi
import eu.flopsyan.sonorus.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri
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
    val volume: Float = 1f,
    val muted: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Where the queue came from, e.g. "Album: Kid A". */
    val source: String = "",
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
 */
@UnstableApi
class PlayerController(
    context: Context,
    private val api: SonorusApi,
    private val library: Library,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Positions in [PlayerState.queue] that were really played, oldest first. */
    private val history = ArrayDeque<Int>()

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

        // Only a step that looks like ordinary playback counts. A larger one is
        // a seek, and seeking past 30 seconds must not count as having heard it.
        if (lastTick >= 0) {
            val step = positionSec - lastTick
            if (step > 0 && step < MAX_STEP) listened += step
        }
        lastTick = positionSec

        val track = _state.value.current ?: return
        val duration = track.duration
        // A play is written on the server, so offline there is nothing to write
        // and no point in a request per song that can only time out. What was
        // heard in a plane is not counted - the server timestamps a play when it
        // arrives, so sending it later would file it under the wrong day.
        if (library.offline.value) return
        if (!playCounted && duration > 0 && listened >= countThreshold(duration)) {
            playCounted = true
            reported = listened.roundToInt()
            val id = track.id
            val seconds = listened
            scope.launch {
                runCatching { api.startPlay(id, seconds) }
                    .onSuccess {
                        playId = it.playId
                        onPlayCounted?.invoke()
                    }
            }
        } else if (playId != null && listened - reported >= REPORT_EVERY) {
            reportListening()
        }
    }

    /** Sends the running total for this play. */
    private fun reportListening() {
        val id = playId ?: return
        val total = listened.roundToInt()
        if (total <= reported) return
        reported = total
        scope.launch { runCatching { api.updatePlay(id, total.toDouble()) } }
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
        listened = 0.0
        lastTick = -1.0
        reported = 0
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
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, source: String = "") {
        val queued = adoptQueue(tracks, startIndex, source)
        if (queued.tracks.isEmpty()) return
        pushPlaylist(queued.tracks, queued.startIndex, 0)
        exoPlayer.playWhenReady = true
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
    fun adoptQueue(tracks: List<Track>, startIndex: Int = 0, source: String = ""): Queued {
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

        _state.value = _state.value.copy(queue = list, order = order, pos = pos, source = source)
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
            _state.value = _state.value.copy(pos = 0)
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
            playing = false, source = "", positionMs = 0, durationMs = 0,
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

    // --- Transport ------------------------------------------------------------

    fun play() {
        exoPlayer.playWhenReady = true
        startTicker()
    }

    fun pause() {
        exoPlayer.playWhenReady = false
        reportListening()
    }

    fun toggle() = if (exoPlayer.isPlaying) pause() else play()

    fun next() {
        val state = _state.value
        if (state.order.isEmpty()) return
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
     */
    fun previous() {
        val state = _state.value
        if (state.order.isEmpty()) return

        // The classic rule: more than three seconds in, the first press starts
        // the track over. The second press is then inside those three seconds
        // and goes back for real.
        if (exoPlayer.currentPosition > RESTART_AFTER_MS) {
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

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = v, muted = false)
        exoPlayer.volume = v
    }

    fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
        exoPlayer.volume = if (muted) 0f else _state.value.volume
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
            .setUri(local?.let { Uri.fromFile(it) } ?: api.streamUrl(track.id).toUri())
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
            // Repeat-one plays the same row again, which is a second play.
            resetListening()
            val index = exoPlayer.currentMediaItemIndex
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
                _state.value = _state.value.copy(
                    durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0,
                )
            }
        }
    }

    fun release() {
        reportListening()
        ticker?.cancel()
        exoPlayer.release()
    }

    private companion object {
        const val COUNT_AFTER = 30.0
        const val REPORT_EVERY = 20
        const val RESTART_AFTER_MS = 3_000L
        const val HISTORY_MAX = 100
        const val TICK_MS = 500L
        /** A step this large is a seek, not playback. */
        const val MAX_STEP = 2.0
    }
}
