package org.sonorus.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The plays that were heard while the phone could not say so.
 *
 * A play is a row on the server, so for a long time offline simply meant "not
 * counted": a downloaded album heard in a plane left no trace in the statistics
 * at all. Nothing was wrong with the reasoning - a request that can only time
 * out is worth not making - only with the conclusion, because the play does not
 * stop being a fact just because the server cannot be told about it yet.
 *
 * So it is written down here and sent on the next connection. The one thing
 * that makes that honest is the **timestamp**: without it the server stamps a
 * play when it arrives, and a fortnight of holiday listening would all land on
 * the day the phone came home - one enormous day in the charts and two silent
 * weeks. Every entry therefore carries the moment it was really heard, and
 * `recordPlay` on the server takes it (see `src/models/ratings.js`).
 *
 * The file follows the same two rules as [org.sonorus.data.download.DownloadStore]:
 * written whole into a temporary file and renamed over the old one, so a process
 * killed mid-write leaves either the previous list or the new one; and plain
 * JSON rather than a database, because there is one writer, it is a few hundred
 * rows at the very most, and a file that can be pulled off the phone and read
 * is worth a lot when the question is "why is this holiday missing".
 *
 * Its only ties to the outside are a file, a flag and a function - no API
 * object, no Android - which is what lets the whole of it be tested on a plain
 * JVM the way the download index is.
 */
class PlayLog(
    private val file: File,
    offline: StateFlow<Boolean>,
    /** Hands one play to the server. Throws when it did not get there. */
    private val send: suspend (trackId: Int, seconds: Double, playedAt: String) -> Unit,
    scope: CoroutineScope,
) {

    @Serializable
    data class Pending(
        /** Local, and only so a play still running can have its seconds corrected. */
        val id: Long,
        val trackId: Int,
        val seconds: Int,
        /** ISO-8601 in UTC, which is the shape the server parses. */
        val playedAt: String,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val temp = File(file.parentFile, file.name + ".tmp")
    private val lock = Any()

    private var entries: List<Pending> = emptyList()
    private var nextId = 1L

    private val _waiting = MutableStateFlow(0)

    /** How many plays are still waiting to be sent. Zero is the ordinary state. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    init {
        load()
        // A StateFlow hands out its current value first, so this also covers the
        // start that matters most: the app was killed while it was offline and
        // is opened again on Wi-Fi, where "offline turns false" never happens
        // because it was never true in this process.
        scope.launch {
            offline.collect { away -> if (!away) flush() }
        }
    }

    /**
     * Writes down a play that has just passed the threshold, and returns the
     * handle [update] needs. Called instead of `POST /api/plays`, never as well.
     */
    fun record(trackId: Int, seconds: Int): Long = synchronized(lock) {
        val entry = Pending(nextId++, trackId, seconds.coerceAtLeast(0), Instant.now().toString())
        // A bound rather than an unbounded file: something has to give if the
        // phone is offline for months, and the oldest play is the one worth
        // least. Reaching this at all would take some 40 days of continuous
        // listening, so it is a guard and not a working limit.
        entries = (entries + entry).takeLast(MAX_ENTRIES)
        save()
        entry.id
    }

    /**
     * Corrects the seconds of a play still running. The timestamp does not move
     * with it - a play belongs to the moment it started, not to the moment the
     * listener skipped away from it.
     */
    fun update(id: Long, seconds: Int) = synchronized(lock) {
        val at = entries.indexOfFirst { it.id == id }
        if (at < 0) return@synchronized
        if (seconds <= entries[at].seconds) return@synchronized
        entries = entries.toMutableList().also { it[at] = it[at].copy(seconds = seconds) }
        save()
    }

    /**
     * Sends what is waiting, oldest first, and drops each one as the server
     * takes it.
     *
     * It stops at the **first failure** rather than carrying on through the
     * list: a server that refused one request is about to refuse the next
     * twenty, and the entries are worth more on the phone than they are spent
     * against a connection that is not there. Whatever is left is tried again
     * the next time the app comes online.
     */
    suspend fun flush() {
        while (true) {
            val next = synchronized(lock) { entries.firstOrNull() } ?: return
            // No dispatcher hop of its own: `send` is a suspend function and the
            // one behind it already moves to IO (`SonorusApi.call`). Hopping
            // twice only takes the work off whatever scheduler is driving it.
            val sent = runCatching { send(next.trackId, next.seconds.toDouble(), next.playedAt) }
            // A play whose track the server does not know is gone for good -
            // the file was removed from the library while the phone was away.
            // Dropping it is the only thing that keeps the queue from jamming
            // behind one dead row forever.
            val gone = (sent.exceptionOrNull() as? ApiException)?.code == "not_found"
            if (sent.isFailure && !gone) return
            synchronized(lock) {
                entries = entries.filterNot { it.id == next.id }
                save()
            }
        }
    }

    private fun load() = synchronized(lock) {
        entries = runCatching {
            if (file.exists()) json.decodeFromString<List<Pending>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        _waiting.value = entries.size
    }

    /** Whole file, temporary, rename - see the class comment. */
    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString(entries))
            temp.renameTo(file)
        }
        _waiting.value = entries.size
    }

    private companion object {
        const val MAX_ENTRIES = 5000
    }
}
