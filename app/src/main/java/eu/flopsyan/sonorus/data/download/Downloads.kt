package eu.flopsyan.sonorus.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import eu.flopsyan.sonorus.data.Connectivity
import eu.flopsyan.sonorus.data.Settings
import eu.flopsyan.sonorus.data.SonorusApi
import eu.flopsyan.sonorus.data.model.Playlist
import eu.flopsyan.sonorus.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a single song is doing. */
enum class DownloadStatus { NONE, QUEUED, RUNNING, DONE, FAILED }

/**
 * The download queue.
 *
 * One song at a time, on purpose: a phone on a train gets more out of finishing
 * one file than out of three half files, and a single writer is what lets the
 * index be a plain file. The queue is worked by one coroutine, and a
 * [DownloadService] holds the process up while it runs - without it Android is
 * free to kill the app the moment it goes to the background, which is precisely
 * when a long download is left alone.
 *
 * Every download is resumable: it writes `<id>.part` and asks for the rest with
 * a `Range` header, which the server answers because `res.sendFile` sets
 * `acceptRanges`. The file only becomes an entry in the index once its last
 * byte is there.
 */
class Downloads(
    private val context: Context,
    private val api: SonorusApi,
    val store: DownloadStore,
    private val connectivity: Connectivity,
    private val settings: Settings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** What the screens draw. */
    data class State(
        /** Track ids that are completely on this phone. */
        val done: Set<Int> = emptySet(),
        val queued: List<Int> = emptyList(),
        val active: Int? = null,
        /** The running song's title - the downloads screen has nothing else to name it by. */
        val activeTitle: String = "",
        /** How far the running download is, 0 until its size is known. */
        val progress: Float = 0f,
        val failed: Map<Int, String> = emptyMap(),
        /** The queue is standing still because there is no connection it may use. */
        val waiting: Boolean = false,
        val bytes: Long = 0,
    ) {
        val busy: Boolean get() = active != null || queued.isNotEmpty()
        val running: Int get() = queued.size + if (active != null) 1 else 0

        fun statusOf(trackId: Int): DownloadStatus = when {
            trackId in done -> DownloadStatus.DONE
            trackId == active -> DownloadStatus.RUNNING
            trackId in queued -> DownloadStatus.QUEUED
            trackId in failed -> DownloadStatus.FAILED
            else -> DownloadStatus.NONE
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _wifiOnly = MutableStateFlow(settings.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** The queue itself. Guarded by its own lock - the worker is not the only writer. */
    private val pending = ArrayDeque<Track>()
    private val failed = mutableMapOf<Int, String>()

    @Volatile
    private var active: Track? = null

    @Volatile
    private var progress = 0f

    private var worker: Job? = null
    private var current: Job? = null

    init {
        // Files can vanish under the index - a phone that ran out of space, or
        // an app whose storage was cleared. An entry promising a file that is
        // not there is exactly the failure this feature exists to prevent.
        scope.launch { store.prune() }
        publish()
        // A queue held back by "Wi-Fi only" has to start by itself once the
        // phone is on Wi-Fi, or the setting would simply look broken.
        scope.launch {
            combine(connectivity.online, connectivity.unmetered, _wifiOnly) { online, unmetered, only ->
                online && (!only || unmetered)
            }.distinctUntilChanged().collect { allowed ->
                if (allowed) start() else publish()
            }
        }
    }

    fun setWifiOnly(on: Boolean) {
        settings.wifiOnly = on
        _wifiOnly.value = on
    }

    // --- Asking for downloads -------------------------------------------------

    /** Queues whatever is not here yet. A missing file is never queued. */
    fun add(tracks: List<Track>) {
        synchronized(pending) {
            for (track in tracks) {
                if (track.missing) continue
                if (store.isDownloaded(track.id)) continue
                if (track.id == active?.id) continue
                if (pending.any { it.id == track.id }) continue
                failed.remove(track.id)
                pending.addLast(track)
            }
        }
        publish()
        start()
    }

    /**
     * A whole playlist. The order is stored with it: an album can be rebuilt
     * from its songs, but the order somebody put a playlist in is written
     * nowhere else, so offline it would be lost.
     */
    fun addPlaylist(playlist: Playlist, tracks: List<Track>) {
        store.rememberCollection(
            OfflineCollection(
                kind = "playlist",
                id = playlist.id,
                name = playlist.name,
                trackIds = tracks.map { it.id },
            )
        )
        add(tracks)
    }

    /** Takes a song back off the phone. */
    fun remove(trackId: Int) {
        cancel(trackId)
        scope.launch {
            store.remove(trackId)
            publish()
        }
    }

    fun removeAll(trackIds: List<Int>) {
        for (id in trackIds) cancel(id)
        scope.launch {
            for (id in trackIds) store.remove(id)
            publish()
        }
    }

    /** Stops a download that has not finished. What is written stays, and resumes. */
    fun cancel(trackId: Int) {
        synchronized(pending) {
            pending.removeAll { it.id == trackId }
            failed.remove(trackId)
        }
        if (active?.id == trackId) current?.cancel()
        publish()
    }

    fun cancelAll() {
        synchronized(pending) {
            pending.clear()
            failed.clear()
        }
        worker?.cancel()
        current?.cancel()
        active = null
        publish()
        stopService()
    }

    /** Everything off the phone, index and files. */
    fun clear() {
        cancelAll()
        scope.launch {
            store.clear()
            publish()
        }
    }

    // --- The worker -----------------------------------------------------------

    private fun allowed(): Boolean =
        connectivity.online.value && (!_wifiOnly.value || connectivity.unmetered.value)

    /** Whether a download queued now would start rather than wait. */
    val allowedNow: Boolean get() = allowed()

    private fun start() {
        synchronized(pending) { if (pending.isEmpty()) return }
        if (worker?.isActive == true) return
        if (!allowed()) {
            publish()
            return
        }
        startService()
        worker = scope.launch {
            // The genre list is the one thing offline cannot derive with the
            // server's own ids, so it rides along with every batch.
            runCatching { api.genres() }.onSuccess { store.rememberGenres(it.genres) }

            while (true) {
                if (!allowed()) break
                val next = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                active = next
                progress = 0f
                publish()

                var error: Throwable? = null
                val job = launch { runCatching { fetch(next) }.onFailure { error = it } }
                current = job
                job.join()
                current = null

                if (!job.isCancelled) {
                    error?.let { synchronized(pending) { failed[next.id] = it.message ?: "Download fehlgeschlagen." } }
                }
                active = null
                progress = 0f
                publish()
            }
            active = null
            publish()
            stopService()
        }
    }

    /**
     * One song: the audio first, then its artwork and its words. Only the audio
     * decides whether the download counted - a cover that did not arrive costs
     * a grey plate, a lyric that did not arrive costs a button.
     */
    private suspend fun fetch(track: Track) = withContext(Dispatchers.IO) {
        val part = File(store.audioDir, "${track.id}.part")
        val contentType = stream(track, part)
        val extension = DownloadStore.extensionFor(track, contentType)
        val target = store.targetOf(track.id, extension)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Die Datei konnte nicht abgelegt werden.")

        track.cover?.takeIf { it.isNotEmpty() && store.coverOf(it) == null }?.let { path ->
            runCatching { cover(path) }
        }
        val lyrics = if (track.hasLyrics) runCatching { api.lyrics(track.id).lyrics }.getOrNull() else null

        store.put(
            DownloadedTrack(
                track = track,
                file = target.name,
                bytes = target.length(),
                at = STAMP.format(Instant.now()),
                lyrics = lyrics,
            )
        )
        publish()
    }

    /**
     * Streams the file into [part], resuming what is already there, and answers
     * with the content type the server named.
     *
     * A 401 means the session ran out mid-download; the credentials are still
     * here, so it logs in again and asks once more rather than failing.
     */
    private suspend fun stream(track: Track, part: File, retry: Boolean = true): String? {
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            .url(api.streamUrl(track.id))
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .get()
            .build()

        val call = api.client.newCall(request)
        return call.execute().use { response ->
            if (response.code == 401 && retry) {
                api.relogin()
                return stream(track, part, retry = false)
            }
            // 416 means the part file is at least as long as the whole file -
            // a leftover from a file that has since changed. Start over.
            if (response.code == 416) {
                part.delete()
                return stream(track, part, retry = false)
            }
            if (!response.isSuccessful) {
                throw IOException("Der Server antwortet mit HTTP ${response.code}.")
            }
            val body = response.body
            val append = response.code == 206 && have > 0
            val expected = body.contentLength().takeIf { it >= 0 }?.plus(if (append) have else 0L) ?: 0L

            var written = if (append) have else 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (expected > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastReport > REPORT_MS) {
                                lastReport = now
                                progress = (written.toFloat() / expected).coerceIn(0f, 1f)
                                publish()
                            }
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            // The one check that makes an entry in the index a promise: a file
            // cut short by a dropped connection must not count as downloaded.
            if (expected > 0 && written != expected) {
                throw IOException("Die Datei kam unvollständig an (${written} von ${expected} Bytes).")
            }
            response.header("Content-Type")
        }
    }

    private suspend fun cover(path: String) = withContext(Dispatchers.IO) {
        val url = api.coverUrl(path) ?: return@withContext
        val request = Request.Builder().url(url).get().build()
        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            val body = response.body
            val target = File(store.coverDir, DownloadStore.coverName(path))
            val temp = File(target.path + ".part")
            temp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            target.delete()
            if (temp.renameTo(target)) store.rememberCover(path)
        }
    }

    // --- The service that keeps the process alive -----------------------------

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    private fun stopService() {
        runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
    }

    private fun publish() {
        val queued = synchronized(pending) { pending.map { it.id } }
        val failures = synchronized(pending) { failed.toMap() }
        _state.value = State(
            done = store.snapshot.tracks.map { it.track.id }.toSet(),
            queued = queued,
            active = active?.id,
            activeTitle = active?.title.orEmpty(),
            progress = progress,
            failed = failures,
            waiting = queued.isNotEmpty() && !allowed(),
            bytes = store.bytes,
        )
    }

    private companion object {
        const val REPORT_MS = 200L
        /** The way the server writes a timestamp: UTC, `YYYY-MM-DD HH:MM:SS`. */
        val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
