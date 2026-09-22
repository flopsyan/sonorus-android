package org.sonorus.data.download

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Request
import org.sonorus.data.Connectivity
import org.sonorus.data.Settings
import org.sonorus.data.SonorusApi
import org.sonorus.data.model.Cue
import org.sonorus.data.model.DownloadTicket
import org.sonorus.player.VideoCaps
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Taking films and episodes along.
 *
 * Its own queue rather than a kind of [Downloads]: that one is built around
 * songs (track ids, a bitrate estimate, collections kept in step), and a video
 * has questions of its own - the server may have to prepare a copy first, which
 * can take as long as the film, and the subtitles and pictures come separately.
 * What it shares with the songs is what keeps a download alive with the screen
 * off: the job or service in [DownloadHold], a wake lock, the one notification,
 * and the "Nur über WLAN" switch.
 */
@UnstableApi
class VideoDownloads(
    private val context: Context,
    private val api: SonorusApi,
    private val store: DownloadStore,
    private val connectivity: Connectivity,
    private val settings: Settings,
    /** The songs' "Nur über WLAN", which is one switch for every download. */
    private val wifiOnly: StateFlow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** One film or episode to fetch, with what its page knew and the player does not. */
    @Serializable
    data class Item(
        val videoId: Int = 0,
        val label: String = "",
        val overview: String = "",
        val airDate: String = "",
    )

    enum class Phase { PREPARING, LOADING }

    data class State(
        val done: Set<Int> = emptySet(),
        val queued: List<Int> = emptyList(),
        val active: Int? = null,
        val activeLabel: String = "",
        val phase: Phase = Phase.LOADING,
        val progress: Float = 0f,
        val failed: Map<Int, String> = emptyMap(),
        val waiting: Boolean = false,
        val wifiOnly: Boolean = false,
        val retrying: Boolean = false,
        val bytes: Long = 0,
    ) {
        val busy: Boolean get() = active != null || queued.isNotEmpty()

        val stalled: String?
            get() = when {
                waiting -> if (wifiOnly) "Wartet auf WLAN" else "Wartet auf eine Verbindung"
                retrying -> "Server nicht erreichbar - neuer Versuch gleich"
                else -> null
            }

        /** What the notification says about the video in flight. */
        val line: String?
            get() = active?.let {
                val percent = (progress * 100).toInt()
                if (phase == Phase.PREPARING) "$activeLabel: wird vorbereitet ($percent %)" else "$activeLabel ($percent %)"
            }

        fun statusOf(id: Int): DownloadStatus = when {
            id in done -> DownloadStatus.DONE
            id == active -> DownloadStatus.RUNNING
            id in queued -> DownloadStatus.QUEUED
            id in failed -> DownloadStatus.FAILED
            else -> DownloadStatus.NONE
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the song queue still needs the job or service this one would let go of. */
    var otherBusy: () -> Boolean = { false }

    private val pending = ArrayDeque<Item>()
    private val failed = mutableMapOf<Int, String>()

    @Volatile private var active: Item? = null
    @Volatile private var phase = Phase.LOADING
    @Volatile private var progress = 0f
    @Volatile private var retrying = false
    @Volatile private var call: Call? = null
    @Volatile private var ticket: DownloadTicket? = null

    private var worker: Job? = null
    private var current: Job? = null
    private var savedQueue: List<Int> = emptyList()

    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        synchronized(pending) {
            for (item in store.loadVideoQueue()) {
                if (!store.isVideoDownloaded(item.videoId) && pending.none { it.videoId == item.videoId }) {
                    pending.addLast(item)
                }
            }
            savedQueue = pending.map { it.videoId }
        }
        publish()
        scope.launch {
            combine(connectivity.online, connectivity.unmetered, wifiOnly) { online, unmetered, only ->
                online && (!only || unmetered)
            }.distinctUntilChanged().collect { allowed -> if (allowed) start() else publish() }
        }
    }

    // --- What the screens call --------------------------------------------------

    fun add(items: List<Item>) {
        synchronized(pending) {
            for (item in items) {
                if (store.isVideoDownloaded(item.videoId)) continue
                if (item.videoId == active?.videoId || pending.any { it.videoId == item.videoId }) continue
                failed.remove(item.videoId)
                pending.addLast(item)
            }
        }
        publish()
        start()
    }

    /** Stops a download that is waiting or running, and tells the server to drop what it prepared. */
    fun cancel(videoId: Int) {
        synchronized(pending) {
            pending.removeAll { it.videoId == videoId }
            failed.remove(videoId)
        }
        if (active?.videoId == videoId) {
            ticket?.key?.let { key -> scope.launch { runCatching { api.releaseVideoDownload(videoId, key) } } }
            current?.cancel()
            call?.cancel()
        }
        scope.launch { partsOf(videoId).forEach { it.delete() } }
        if (synchronized(pending) { pending.isEmpty() } && active == null) release()
        publish()
    }

    fun remove(videoId: Int) {
        cancel(videoId)
        scope.launch {
            store.removeVideo(videoId)
            publish()
        }
    }

    fun cancelAll() {
        val ids = synchronized(pending) { pending.map { it.videoId } + listOfNotNull(active?.videoId) }
        ids.forEach(::cancel)
    }

    fun onAppVisible() {
        if (synchronized(pending) { pending.isEmpty() }) return
        DownloadHold.hold(context, unmetered = wifiOnly.value, visibleNow = true)
        start()
    }

    fun onJobStarted() {
        if (synchronized(pending) { pending.isEmpty() } && active == null) release() else start()
    }

    /** The system took the job back: the video in flight goes back to the front and resumes from its part. */
    fun onJobStopped() {
        synchronized(pending) {
            active?.let { item -> if (pending.none { it.videoId == item.videoId }) pending.addFirst(item) }
            active = null
        }
        worker?.cancel()
        current?.cancel()
        call?.cancel()
        progress = 0f
        publish()
    }

    // --- The worker ------------------------------------------------------------------

    private fun allowed(): Boolean =
        connectivity.online.value && (!wifiOnly.value || connectivity.unmetered.value)

    private fun start() {
        synchronized(pending) { if (pending.isEmpty()) return }
        DownloadHold.hold(context, unmetered = wifiOnly.value)
        if (worker?.isActive == true) return
        if (!allowed()) {
            publish()
            return
        }
        val previous = worker
        worker = scope.launch {
            previous?.join()
            var fruitless = 0
            try {
                while (true) {
                    if (!allowed()) break
                    val next = synchronized(pending) { pending.removeFirstOrNull()?.also { active = it } } ?: break
                    holdCpu()
                    phase = Phase.LOADING
                    progress = 0f
                    publish()

                    var error: Throwable? = null
                    val job = launch { runCatching { fetch(next) }.onFailure { error = it } }
                    current = job
                    job.join()
                    current = null
                    ticket = null

                    val failure = error
                    if (!job.isCancelled && failure != null && DownloadRetry.isTransport(failure) && fruitless < MAX_RETRIES) {
                        fruitless++
                        synchronized(pending) {
                            pending.addFirst(next)
                            active = null
                        }
                        retrying = true
                        publish()
                        try {
                            delay(DownloadRetry.delayFor(fruitless))
                        } finally {
                            retrying = false
                        }
                        continue
                    }
                    fruitless = 0
                    if (!job.isCancelled && failure != null && failure !is CancellationException) {
                        synchronized(pending) { failed[next.videoId] = failure.message ?: "Download fehlgeschlagen." }
                    }
                    active = null
                    progress = 0f
                    publish()
                }
                active = null
                publish()
                if (synchronized(pending) { pending.isEmpty() }) release()
            } finally {
                releaseCpu()
            }
        }
    }

    private suspend fun fetch(item: Item) = withContext(Dispatchers.IO) {
        val id = item.videoId
        val info = api.playerInfo(id).video
        val quality = settings.videoDownloadQuality.value

        // The server may have to prepare a copy first; asking again is polling.
        var answer = api.videoDownload(id, quality, VideoCaps.json)
        ticket = answer
        while (!answer.ready) {
            answer.error?.let { throw DownloadLocalFailure("Der Server konnte das Video nicht vorbereiten: $it") }
            phase = Phase.PREPARING
            progress = answer.progress.toFloat()
            publish()
            delay(PREPARE_POLL_MS)
            answer = api.videoDownload(id, quality, VideoCaps.json)
            ticket = answer
        }
        val url = answer.url ?: throw IOException("Der Server hat keine Adresse für den Download genannt.")
        phase = Phase.LOADING
        progress = 0f
        publish()

        // A part belongs to one copy: another quality must not be appended to it.
        val part = File(store.videoDir, "$id.${answer.key ?: "file"}.part")
        partsOf(id).filter { it != part }.forEach { it.delete() }
        stream(api.absolute(url), part)
        val target = store.videoTarget(id, answer.ext.ifEmpty { "mp4" })
        target.delete()
        if (!part.renameTo(target)) throw DownloadLocalFailure("Die Datei konnte nicht abgelegt werden.")
        answer.key?.let { key -> runCatching { api.releaseVideoDownload(id, key) } }

        val cues = subtitles(id, info.subtitles.filter { it.supported }.map { it.key })
        store.saveCues(id, cues)
        listOfNotNull(info.title.poster, info.title.backdrop, info.title.logo, info.still)
            .filter { store.coverOf(it) == null }
            .forEach { runCatching { artwork(it) } }

        // A prepared copy carries only the one audio track the server picked.
        val audio = if (answer.kind == "file") info.audio else info.audio.filter { it.index == answer.audio }
        store.putVideo(
            DownloadedVideo(
                info = info.copy(audio = audio),
                overview = item.overview,
                airDate = item.airDate,
                file = target.name,
                bytes = target.length(),
                quality = quality,
                kind = answer.kind,
                audio = answer.audio,
                subtitles = cues.keys.toList(),
                at = STAMP.format(Instant.now()),
                position = info.progress.position,
                completed = info.progress.completed,
            )
        )
        synchronized(pending) { failed.remove(id) }
        publish()
    }

    private fun partsOf(id: Int): List<File> =
        store.videoDir.listFiles { f -> f.name.startsWith("$id.") && f.name.endsWith(".part") }?.toList().orEmpty()

    private suspend fun stream(url: String, part: File, retry: Boolean = true) {
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            .url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .get()
            .build()
        val call = api.client.newCall(request)
        this.call = call
        call.execute().use { response ->
            if (response.code == 401 && retry) {
                api.relogin()
                return stream(url, part, retry = false)
            }
            if (response.code == 416) {
                part.delete()
                return stream(url, part, retry = false)
            }
            if (!response.isSuccessful) throw DownloadRetry.httpFailure(response.code)
            val body = response.body
            val append = response.code == 206 && have > 0
            val expected = body.contentLength().takeIf { it >= 0 }?.plus(if (append) have else 0L) ?: 0L
            var written = if (append) have else 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (expected > 0 && now - lastReport > REPORT_MS) {
                            lastReport = now
                            progress = (written.toFloat() / expected).coerceIn(0f, 1f)
                            publish()
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (expected > 0 && written != expected) {
                throw IOException("Die Datei kam unvollständig an (${written} von ${expected} Bytes).")
            }
        }
    }

    /** Embedded tracks are read out of the file on first ask; the server answers "pending" until then. */
    private suspend fun subtitles(id: Int, keys: List<String>): Map<String, List<Cue>> {
        val out = mutableMapOf<String, List<Cue>>()
        for (key in keys) {
            for (attempt in 0 until SUBTITLE_TRIES) {
                val answer = runCatching { api.subtitleCues(id, key) }.getOrNull() ?: break
                if (!answer.pending) {
                    if (answer.cues.isNotEmpty()) out[key] = answer.cues
                    break
                }
                delay(SUBTITLE_POLL_MS)
            }
        }
        return out
    }

    private suspend fun artwork(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(api.absolute(path)).get().build()
        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            val target = File(store.coverDir, DownloadStore.coverName(path))
            val temp = File(target.path + ".part")
            temp.outputStream().use { out -> response.body.byteStream().use { it.copyTo(out) } }
            target.delete()
            if (temp.renameTo(target)) store.rememberCover(path)
        }
    }

    // --- Keeping alive -------------------------------------------------------------

    /** The same reason the song queue holds one: with the screen off, a read in flight simply stops. */
    private fun holdCpu() {
        val lock = wakeLock ?: runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return
        runCatching { lock.acquire(WAKE_LIMIT_MS) }
    }

    private fun releaseCpu() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private fun release() {
        if (!otherBusy()) DownloadHold.release(context)
    }

    private fun publish() {
        val queued = synchronized(pending) { pending.map { it.videoId } }
        val running = active
        _state.value = State(
            done = store.snapshot.videos.map { it.id }.toSet(),
            queued = queued,
            active = running?.videoId,
            activeLabel = running?.label.orEmpty(),
            phase = phase,
            progress = progress,
            failed = synchronized(pending) { failed.toMap() },
            waiting = queued.isNotEmpty() && !allowed(),
            wifiOnly = wifiOnly.value,
            retrying = retrying,
            bytes = store.videoBytes,
        )
        val items = synchronized(pending) { listOfNotNull(running) + pending }
        val ids = items.map { it.videoId }
        if (ids != savedQueue) {
            savedQueue = ids
            runCatching { store.saveVideoQueue(items) }
        }
    }

    private companion object {
        const val REPORT_MS = 500L
        const val PREPARE_POLL_MS = 5_000L
        const val SUBTITLE_POLL_MS = 3_000L
        /** Three minutes per embedded track; a longer wait is a track to do without. */
        const val SUBTITLE_TRIES = 60
        const val MAX_RETRIES = 8
        const val WAKE_TAG = "sonorus:videos"
        /** A preparation plus a film can outlast the songs' two hours; renewed for every video. */
        const val WAKE_LIMIT_MS = 4 * 60 * 60 * 1000L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
