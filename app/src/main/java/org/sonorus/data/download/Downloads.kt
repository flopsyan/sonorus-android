package org.sonorus.data.download

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.sonorus.data.Connectivity
import org.sonorus.data.Quality
import org.sonorus.data.Settings
import org.sonorus.data.SonorusApi
import org.sonorus.data.model.Track
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
import okhttp3.Call
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
 * [DownloadJob] - a [DownloadService] before Android 14 - holds the process up
 * while it runs; without it Android is free to kill the app the moment it goes
 * to the background, which is precisely when a long download is left alone.
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
) : DownloadTarget {

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
        /** The server could not be reached just now; the same song is tried again shortly. */
        val retrying: Boolean = false,
        /** Too many tries in a row came to nothing, so the system decides when to go on. */
        val paused: Boolean = false,
        val wifiOnly: Boolean = false,
        val bytes: Long = 0,
        /**
         * How far the whole run is, **by size and not by count**.
         *
         * Three of ten songs that happen to be the three long ones are not
         * thirty per cent of the work, and a bar that says so is a bar that
         * stalls near the end. The estimate is `track.duration * bitrate`
         * because the real size is only known once the server has answered for
         * that song - so it is approximate on purpose, and only for the ring.
         *
         * Null while nothing is running.
         */
        val batchDoneBytes: Long = 0,
        val batchTotalBytes: Long = 0,
    ) {
        val busy: Boolean get() = active != null || queued.isNotEmpty()
        val running: Int get() = queued.size + if (active != null) 1 else 0

        /** Why nothing is moving, or null while a song is on its way. */
        val stalled: String?
            get() = when {
                waiting -> if (wifiOnly) "Wartet auf WLAN" else "Wartet auf eine Verbindung"
                retrying -> "Server nicht erreichbar - neuer Versuch gleich"
                paused -> "Pausiert - geht von selbst weiter"
                else -> null
            }

        /** 0 to 1 across the whole run, or null when nothing is running. */
        val batchProgress: Float?
            get() = if (!busy || batchTotalBytes <= 0) null
            else (batchDoneBytes.toFloat() / batchTotalBytes).coerceIn(0f, 1f)

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

    /**
     * The songs this **run** has put on the phone, and what the run is expected
     * to weigh.
     *
     * Both exist for the cancel button. Tapping "Herunterladen" again stops the
     * run and takes back what it has fetched so far - and only what *it*
     * fetched. A song that was already on the phone before the run started, or
     * that came from a different album last week, is not this run's to delete,
     * and deleting it would be the kind of surprise a download button must never
     * spring. So the ids are collected as they finish rather than derived
     * afterwards from what happens to be in the index.
     */
    private val runDownloaded = mutableSetOf<Int>()

    @Volatile
    private var runTotalBytes = 0L

    @Volatile
    private var runDoneBytes = 0L

    @Volatile
    private var active: Track? = null

    @Volatile
    private var progress = 0f

    /** The bytes already written of the running song, for the batch total. */
    @Volatile
    private var activeBytes = 0L

    @Volatile
    private var activeExpected = 0L

    private var worker: Job? = null
    private var current: Job? = null

    /** The request in flight. Cancelling the coroutine alone leaves it blocked in `read()` for up to 30 s. */
    @Volatile
    private var call: Call? = null

    /** Tries in a row that brought not a single new byte, and when that streak began. */
    private var fruitless = 0
    private var stalledSince = 0L

    /** See [DownloadRetry.MAX_CUT_SHORT]; counted for the song in [cutShortId] only. */
    private var cutShort = 0
    private var cutShortId = -1

    @Volatile
    private var retrying = false

    @Volatile
    private var paused = false

    private val useJob = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The ids last written to the queue file, so a progress tick does not write it again. */
    private var savedQueue: List<Int> = emptyList()

    /**
     * Holds the CPU while songs are being fetched.
     *
     * The foreground service keeps the *process* alive, which is a different
     * promise: with the screen off the device suspends anyway, and a download
     * sitting in `read()` is simply not running any more. It then dies of the
     * 30-second read timeout, which is exactly what "I locked my phone and the
     * downloads stopped" looks like - and why it happened once in four tries
     * rather than every time, because it needs the device to really go to
     * sleep. Playback never showed it: ExoPlayer holds a wake lock of its own.
     *
     * Not reference counted, released in the worker's `finally`, and acquired
     * with a limit per song so a crash between the two cannot leave the CPU
     * held for the rest of the day.
     */
    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    private fun holdCpu() {
        val lock = wakeLock ?: runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply {
                setReferenceCounted(false)
            }
        }.getOrNull()?.also { wakeLock = it } ?: return
        // Acquiring again refreshes the limit, which is what makes one call per
        // song enough for a run of any length.
        runCatching { lock.acquire(WAKE_LIMIT_MS) }
    }

    private fun releaseCpu() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    init {
        // Files can vanish under the index - a phone that ran out of space, or
        // an app whose storage was cleared. An entry promising a file that is
        // not there is exactly the failure this feature exists to prevent.
        scope.launch { store.prune() }
        // The queue outlives the process: the system may start the job again long after it died.
        synchronized(pending) {
            for (track in store.loadQueue()) {
                if (store.isDownloaded(track.id) || pending.any { it.id == track.id }) continue
                pending.addLast(track)
                runTotalBytes += estimate(track)
            }
            savedQueue = pending.map { it.id }
        }
        publish()
        drawNotification()
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
        // The job carries the network it waits for, so it is scheduled again with the new one.
        if (synchronized(pending) { pending.isNotEmpty() } || active != null) hold(force = true)
        publish()
    }

    /** The app is on screen - the one moment a user-initiated job may be scheduled. */
    fun onAppVisible() {
        if (synchronized(pending) { pending.isEmpty() }) return
        hold(visibleNow = true)
        start()
    }

    // --- Asking for downloads -------------------------------------------------

    /**
     * Queues whatever is not here yet. A missing file is never queued.
     *
     * [manual] says nobody but the user asked for these songs - a single track
     * from its menu, or a page that is not kept in sync. Such a song is held by
     * nothing else, so it is written down as its own reason to stay; without
     * that, the first reconcile that saw it leave a playlist would delete a
     * download the user had fetched deliberately.
     */
    override fun add(tracks: List<Track>, manual: Boolean) {
        val wanted = tracks.filterNot { it.missing }.map { it.id }
        // Asking for a song again is the one way to take back "I deleted this
        // one on purpose", whether it is asked for on its own or as part of the
        // collection it sits in.
        store.unexclude(wanted)
        if (manual) store.rememberManual(wanted)
        synchronized(pending) {
            for (track in tracks) {
                if (track.missing) continue
                if (store.isDownloaded(track.id)) continue
                if (track.id == active?.id) continue
                if (pending.any { it.id == track.id }) continue
                failed.remove(track.id)
                pending.addLast(track)
                runTotalBytes += estimate(track)
            }
        }
        publish()
        start()
    }

    /**
     * Roughly what [track] will weigh once it is here, in bytes.
     *
     * The real size is only known when the server answers for that one song, and
     * the ring has to be drawn before that. Length times bitrate is close enough
     * for a bar and wrong in a way nobody can see: the point of weighting by size
     * at all is that three long songs are not thirty per cent of ten, and that
     * holds however approximate each estimate is.
     */
    private fun estimate(track: Track): Long {
        val quality = Quality.served(track, settings.downloadQuality.value)
        val bits = if (quality == Quality.OPUS128) OPUS_BITS else (track.bitrate ?: FALLBACK_BITS)
        return (track.duration * bits / 8).toLong().coerceAtLeast(1)
    }

    /**
     * A whole collection - a playlist, an album, a genre or a star list.
     *
     * What is stored with it is two things at once: a playlist's order, which
     * nothing in a track says, and the list of songs the server had at this
     * moment, which every later reconcile is a diff against. The songs are
     * **not** marked manual - they are here because the collection is, and they
     * go again when it lets go of them.
     */
    fun addCollection(collection: OfflineCollection, tracks: List<Track>) {
        val here = tracks.filterNot { it.missing }
        store.rememberCollection(collection.copy(trackIds = here.map { it.id }))
        add(here, manual = false)
    }

    /**
     * Gives a whole collection back: it is no longer downloaded, and every song
     * of it goes **unless something else still holds it**.
     *
     * That last half is the rule Florian asked for: a song that sits in this
     * playlist and in a downloaded album as well stays when the playlist lets
     * go, because the album has not. Answers how many files really went, so the
     * confirmation can say it.
     */
    fun removeCollection(collection: OfflineCollection): Int {
        val going = collection.trackIds.filter { !store.isHeld(it, exceptKey = collection.key) }
        for (id in going) cancel(id)
        store.forgetCollection(collection.key)
        scope.launch {
            for (id in going) store.remove(id)
            publish()
        }
        return going.size
    }

    /** Takes a song back off the phone. */
    override fun remove(trackId: Int) {
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
    /**
     * Stop fetching this song, and stop meaning it.
     *
     * The exclusion is the point. A song that belongs to a downloaded playlist
     * is under a standing order, so a cancelled download without it is a
     * download the next reconcile starts again - at the next app start, over
     * and over. Asking for the song again clears the exclusion, which is what
     * `add` does, so this takes nothing back permanently.
     */
    fun cancel(trackId: Int) {
        synchronized(pending) {
            pending.removeAll { it.id == trackId }
            failed.remove(trackId)
        }
        if (active?.id == trackId) {
            current?.cancel()
            call?.cancel()
        }
        store.exclude(listOf(trackId))
        // The last one out turns the light off: with nothing waiting there is
        // nothing for the service to say, and it may be waiting rather than
        // running - in which case no worker will end and stop it.
        val empty = synchronized(pending) { pending.isEmpty() } && active == null
        if (empty) release()
        publish()
    }

    fun cancelAll() {
        val going = synchronized(pending) {
            val ids = pending.map { it.id } + listOfNotNull(active?.id)
            pending.clear()
            failed.clear()
            runDownloaded.clear()
            runTotalBytes = 0
            runDoneBytes = 0
            ids
        }
        worker?.cancel()
        current?.cancel()
        call?.cancel()
        active = null
        // Every one of them was cancelled by hand - see [cancel].
        if (going.isNotEmpty()) store.exclude(going)
        publish()
        release()
    }

    /**
     * Stops the running download and takes back what **this run** fetched.
     *
     * The whole point of the "only this run" rule: a half-downloaded album is
     * not something worth keeping, but the songs that were already on the phone
     * before the button was tapped are, and so is everything downloaded last
     * week. Both would be indistinguishable an hour later, so the run keeps its
     * own list ([runDownloaded]) rather than working it out afterwards.
     *
     * Answers how many songs were removed, so the confirmation can say it.
     */
    fun cancelRun(): Int {
        var going = emptyList<Int>()
        val fetched = synchronized(pending) {
            going = pending.map { it.id } + listOfNotNull(active?.id)
            pending.clear()
            failed.clear()
            runDownloaded.toList().also { runDownloaded.clear() }
        }
        worker?.cancel()
        current?.cancel()
        call?.cancel()
        active = null
        runTotalBytes = 0
        runDoneBytes = 0
        scope.launch {
            for (id in fetched) store.remove(id)
            // What was given back and what never arrived are both "no thank
            // you", so both are excluded - or the next reconcile fetches them
            // again on behalf of the collection they belong to.
            store.exclude(fetched + going)
            // The part file of the song that was interrupted is this run's too,
            // and leaving it would have the next download resume into a file
            // nobody asked for any more.
            store.audioDir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
            publish()
        }
        publish()
        release()
        return fetched.size
    }

    /** How many songs a cancel would take back right now. */
    val runCount: Int get() = synchronized(pending) { runDownloaded.size }

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
        // Before the connection is even asked about. A queue that is waiting for
        // WLAN is still a queue, and the service is what says so on screen and
        // what keeps the process alive to say it - stopping it here is how the
        // notification came to disappear the moment a phone went into a pocket
        // and its WLAN slept. It also has to be started while the app is still
        // on screen: a foreground service may not be started from the
        // background, so one started only when the network comes back would be
        // refused and the queue would run unprotected and unseen.
        hold()
        if (worker?.isActive == true) return
        if (!allowed()) {
            publish()
            return
        }
        val previous = worker
        worker = scope.launch {
            // A stopped run may still be unwinding: two writers on one part file corrupt it, and its finally would drop our wake lock.
            previous?.join()
            paused = false
            try {
                // The genre list is the one thing offline cannot derive with the
                // server's own ids, so it rides along with every batch.
                runCatching { api.genres() }.onSuccess { store.rememberGenres(it.genres) }

                while (true) {
                    if (!allowed()) break
                    // Taken and marked active in one step, so the saved queue never loses it in between.
                    val next = synchronized(pending) { pending.removeFirstOrNull()?.also { active = it } } ?: break
                    // Before the song, not once before the run: acquiring again
                    // refreshes the limit, so a long queue never outlives its lock.
                    holdCpu()
                    progress = 0f
                    publish()

                    val before = partLength(next)
                    var error: Throwable? = null
                    val job = launch { runCatching { fetch(next) }.onFailure { error = it } }
                    current = job
                    job.join()
                    current = null

                    val failure = error
                    val progressed = partLength(next) > before
                    if (progressed) {
                        if (cutShortId != next.id) {
                            cutShortId = next.id
                            cutShort = 0
                        }
                        cutShort++
                    }
                    if (!job.isCancelled && failure != null && DownloadRetry.isTransport(failure) &&
                        cutShort < DownloadRetry.MAX_CUT_SHORT
                    ) {
                        // The connection failed, not the song: it goes back to the front and waits.
                        fruitless = if (progressed) 1 else fruitless + 1
                        if (fruitless == 1) stalledSince = SystemClock.elapsedRealtime()
                        synchronized(pending) {
                            pending.addFirst(next)
                            active = null
                        }
                        activeBytes = 0
                        activeExpected = 0
                        progress = 0f
                        if (SystemClock.elapsedRealtime() - stalledSince >= DownloadRetry.GIVE_UP_AFTER_MS) {
                            if (DownloadJob.isRunning) {
                                paused = true
                                publish()
                                break
                            }
                            // Nothing would restart the queue from here, so it keeps trying and lets the CPU sleep between tries.
                            releaseCpu()
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
                    if (!job.isCancelled) {
                        failure?.let { synchronized(pending) { failed[next.id] = it.message ?: "Download fehlgeschlagen." } }
                    }
                    // Whether it arrived or failed, this song is behind the run now -
                    // a bar that stops at a file the server refused would never
                    // finish. The estimate is used rather than the real size so the
                    // total it is measured against stays the one it started with.
                    runDoneBytes += estimate(next)
                    active = null
                    activeBytes = 0
                    activeExpected = 0
                    progress = 0f
                    publish()
                }
                active = null
                // The run is over: a fresh tap starts a fresh one, and a cancel
                // after this point has nothing of its own left to take back.
                val empty = synchronized(pending) {
                    if (pending.isEmpty()) {
                        runDownloaded.clear()
                        runTotalBytes = 0
                        runDoneBytes = 0
                        true
                    } else {
                        false
                    }
                }
                publish()
                // Only when there is nothing left. The loop above also ends when
                // the connection goes, and the songs still queued behind it are
                // what the notification is for.
                if (empty) release() else if (paused) DownloadJob.finish(context, reschedule = true)
            } finally {
                // Also on cancellation, which is the path a stopped run takes -
                // a lock left held there would cost battery for nothing.
                releaseCpu()
            }
        }
    }

    /**
     * One song: the audio first, then its artwork and its words. Only the audio
     * decides whether the download counted - a cover that did not arrive costs
     * a grey plate, a lyric that did not arrive costs a button.
     */
    private suspend fun fetch(track: Track) = withContext(Dispatchers.IO) {
        val part = File(store.audioDir, "${track.id}.part")
        val answer = stream(track, part)
        // The server names the container it actually served in its own header;
        // the content type only says `audio/ogg`, which an Opus copy shares with
        // a Vorbis one. Purely cosmetic - the player sniffs the content either
        // way - but a folder of `4711.opus` can be read and one of `4711.ogg`
        // has to be guessed at.
        val extension = answer.format?.takeIf { it.isNotEmpty() }
            ?: DownloadStore.extensionFor(track, answer.contentType)
        val target = store.targetOf(track.id, extension)
        target.delete()
        if (!part.renameTo(target)) throw DownloadLocalFailure("Die Datei konnte nicht abgelegt werden.")

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
                // What the server said it served, not what was asked for: a
                // 128k MP3 asked for as Opus comes back untouched, and the
                // player has to be able to say so.
                quality = answer.quality,
            )
        )
        synchronized(pending) { runDownloaded += track.id }
        publish()
    }

    /** What one finished stream turned out to be. */
    private class Fetched(val contentType: String?, val quality: String, val format: String?)

    /**
     * Streams the file into [part], resuming what is already there, and answers
     * with the content type the server named.
     *
     * A 401 means the session ran out mid-download; the credentials are still
     * here, so it logs in again and asks once more rather than failing.
     */
    private suspend fun stream(track: Track, part: File, retry: Boolean = true): Fetched {
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            // The quality is read here rather than passed down from the queue on
            // purpose: it is the setting as it stands when the file is actually
            // fetched, so changing it mid-run applies to what is still to come.
            .url(api.streamUrl(track.id, settings.downloadQuality.value))
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .get()
            .build()

        val call = api.client.newCall(request)
        this.call = call
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
                throw DownloadRetry.httpFailure(response.code)
            }
            val body = response.body
            val append = response.code == 206 && have > 0
            val expected = body.contentLength().takeIf { it >= 0 }?.plus(if (append) have else 0L) ?: 0L

            var written = if (append) have else 0L
            var lastReport = 0L
            activeExpected = expected
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        activeBytes = written
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
            Fetched(
                contentType = response.header("Content-Type"),
                quality = response.header("X-Sonorus-Quality") ?: Quality.ORIGINAL.wire,
                format = response.header("X-Sonorus-Format"),
            )
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

    // --- What keeps the process alive -----------------------------------------

    /** A job on Android 14 and up, which may only be scheduled while the app is on screen; a service below. */
    private fun hold(force: Boolean = false, visibleNow: Boolean = visible()) {
        if (useJob) {
            if (!visibleNow || DownloadJob.ensure(context, unmetered = _wifiOnly.value, force = force)) return
            // A job the system refused still leaves the foreground service, with its six hours.
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    private fun release() {
        if (useJob) {
            DownloadJob.finish(context, reschedule = false)
            // The job leaves its notification behind on purpose, see [DownloadJob].
            DownloadNotification.hide(context)
        }
        runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
    }

    private fun visible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** The system started the job, so the queue may run with the phone locked. */
    fun onJobStarted() {
        if (synchronized(pending) { pending.isEmpty() } && active == null) release() else start()
    }

    /** The system took the job back. The song in flight goes back to the front and resumes from its part file. */
    fun onJobStopped() {
        synchronized(pending) {
            active?.let { song -> if (pending.none { it.id == song.id }) pending.addFirst(song) }
            active = null
        }
        worker?.cancel()
        current?.cancel()
        call?.cancel()
        activeBytes = 0
        activeExpected = 0
        progress = 0f
        if (allowed()) paused = true
        publish()
    }

    private fun partLength(track: Track): Long = File(store.audioDir, "${track.id}.part").length()

    /** At most once a second: five updates a second had the system shed them. */
    private fun drawNotification() = scope.launch {
        var shown: State? = null
        var at = 0L
        state.collect { s ->
            if (!s.busy) {
                if (shown != null) DownloadNotification.hide(context)
                shown = null
                return@collect
            }
            val now = SystemClock.elapsedRealtime()
            val last = shown
            if (last != null && last.stalled == s.stalled && last.running == s.running && now - at < NOTIFY_MS) {
                return@collect
            }
            shown = s
            at = now
            DownloadNotification.show(context, s)
        }
    }

    private fun publish() {
        val queued = synchronized(pending) { pending.map { it.id } }
        val failures = synchronized(pending) { failed.toMap() }
        val running = active
        // The song in flight counts its real bytes against its own estimate, so
        // the ring moves *inside* a track rather than only stepping between
        // them. Capped at the estimate, or a file that turned out larger than
        // guessed would push the whole run past its own total.
        val inFlight = if (running == null || activeExpected <= 0) 0L
        else (activeBytes.toDouble() / activeExpected * estimate(running)).toLong()
        _state.value = State(
            done = store.snapshot.tracks.map { it.track.id }.toSet(),
            queued = queued,
            active = running?.id,
            activeTitle = running?.title.orEmpty(),
            progress = progress,
            failed = failures,
            waiting = queued.isNotEmpty() && !allowed(),
            retrying = retrying,
            paused = paused && running == null,
            wifiOnly = _wifiOnly.value,
            bytes = store.bytes,
            batchDoneBytes = runDoneBytes + inFlight,
            batchTotalBytes = runTotalBytes,
        )
        saveQueue()
    }

    private fun saveQueue() {
        val tracks = synchronized(pending) {
            val all = listOfNotNull(active) + pending
            val ids = all.map { it.id }
            if (ids == savedQueue) return
            savedQueue = ids
            all
        }
        runCatching { store.saveQueue(tracks) }
    }

    private companion object {
        const val REPORT_MS = 200L

        const val NOTIFY_MS = 1_000L

        const val WAKE_TAG = "sonorus:downloads"

        /**
         * The most one song may hold the CPU. Long enough for a two-hour
         * audiobook file on a bad connection, short enough that a lock leaked
         * by a crash is gone before the battery is.
         */
        const val WAKE_LIMIT_MS = 2 * 60 * 60 * 1000L

        /** The one profile's target, for the size estimate. */
        const val OPUS_BITS = 128_000

        /** What a track without a bitrate is assumed to weigh: ungefähr a FLAC. */
        const val FALLBACK_BITS = 900_000
        /** The way the server writes a timestamp: UTC, `YYYY-MM-DD HH:MM:SS`. */
        val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
