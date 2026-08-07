package org.sonorus.data.download

import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.Genre
import org.sonorus.data.model.Track
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The downloads on disk, and the index that says what they are.
 *
 * The index is one JSON file rather than a database, for the same reason the
 * rest of the app has no Room: there is one writer, the whole thing is a few
 * hundred rows, and a file that can be pulled off the phone and read is worth a
 * great deal when the question is "why did this not play in a plane".
 *
 * Two rules make it safe to be a file:
 *
 *  - **It is written whole, into a temporary file, and renamed over the old
 *    one.** A rename is atomic, so a process killed mid-write leaves either the
 *    previous index or the new one, never half of one.
 *  - **An audio file only enters the index once it is completely on disk.** It
 *    is downloaded as `<id>.<ext>.part` and renamed when the last byte has
 *    arrived and its length checks out, so an entry in the index is a promise
 *    that the file behind it can be played.
 *
 * Deliberately free of Android: its only tie to the outside is the directory it
 * is handed, which is what lets all of it be tested on a plain JVM.
 */
class DownloadStore(private val root: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val audioDir = File(root, "audio")
    val coverDir = File(root, "covers")
    private val indexFile = File(root, "library.json")
    private val tempFile = File(root, "library.json.tmp")

    private val lock = Any()

    @Volatile
    private var state: OfflineSnapshot = OfflineSnapshot()

    /** Track id -> its entry, so a list can ask a few hundred times per frame. */
    @Volatile
    private var byId: Map<Int, DownloadedTrack> = emptyMap()

    @Volatile
    private var coverSet: Set<String> = emptySet()

    init {
        audioDir.mkdirs()
        coverDir.mkdirs()
        load()
    }

    val snapshot: OfflineSnapshot get() = state

    // --- Reading --------------------------------------------------------------

    fun isDownloaded(trackId: Int): Boolean = byId.containsKey(trackId)

    val count: Int get() = byId.size

    val bytes: Long get() = state.tracks.sumOf { it.bytes }

    /** The playable file of a downloaded track, or null if it is not here. */
    fun fileOf(trackId: Int): File? {
        val entry = byId[trackId] ?: return null
        return File(audioDir, entry.file).takeIf { it.isFile && it.length() > 0 }
    }

    /** The artwork of a cover path like `/covers/album-3.jpg`, if it was taken along. */
    fun coverOf(path: String?): File? {
        if (path.isNullOrEmpty() || path !in coverSet) return null
        return File(coverDir, coverName(path)).takeIf { it.isFile }
    }

    fun targetOf(trackId: Int, extension: String): File = File(audioDir, "$trackId.$extension")

    // --- Writing --------------------------------------------------------------

    /** Records a finished download. Replaces an older copy of the same song. */
    fun put(entry: DownloadedTrack) = update { s ->
        // A second download with another extension leaves the old file behind.
        s.tracks.firstOrNull { it.track.id == entry.track.id }
            ?.takeIf { it.file != entry.file }
            ?.let { File(audioDir, it.file).delete() }
        s.copy(tracks = s.tracks.filter { it.track.id != entry.track.id } + entry)
    }

    fun rememberCover(path: String) = update { s ->
        if (path in s.covers) s else s.copy(covers = s.covers + path)
    }

    /** The order somebody chose, which nothing in a track could say. */
    fun rememberCollection(collection: OfflineCollection) = update { s ->
        s.copy(playlists = s.playlists.filterNot {
            it.kind == collection.kind && it.id == collection.id
        } + collection)
    }

    fun rememberGenres(genres: List<Genre>) = update { s -> s.copy(genres = genres) }

    /**
     * The last look at the account. Stored on every successful bootstrap,
     * because who is logged in and how the player is set cannot be asked for
     * once the server is gone - and the shell needs both before it draws.
     */
    fun rememberAccount(bootstrap: Bootstrap) = update { s -> s.copy(account = bootstrap) }

    /** Drops one song and its file. The artwork stays - other songs share it. */
    fun remove(trackId: Int) = update { s ->
        s.tracks.firstOrNull { it.track.id == trackId }?.let { File(audioDir, it.file).delete() }
        s.copy(
            tracks = s.tracks.filterNot { it.track.id == trackId },
            playlists = s.playlists.map { c -> c.copy(trackIds = c.trackIds.filterNot { it == trackId }) }
                .filter { it.trackIds.isNotEmpty() },
        )
    }

    /** Everything goes, the account snapshot included - this is a clean slate. */
    fun clear() {
        synchronized(lock) {
            audioDir.deleteRecursively()
            coverDir.deleteRecursively()
            audioDir.mkdirs()
            coverDir.mkdirs()
            write(OfflineSnapshot())
        }
    }

    /**
     * Throws away entries whose file is gone - which is what a phone that ran
     * out of space, or a user who cleared the app's storage, leaves behind.
     * Called once at startup: an index that promises a file that is not there
     * is exactly the failure this feature exists to prevent.
     */
    fun prune(): Int {
        var dropped = 0
        update { s ->
            val kept = s.tracks.filter { File(audioDir, it.file).let { f -> f.isFile && f.length() > 0 } }
            dropped = s.tracks.size - kept.size
            val covers = s.covers.filter { File(coverDir, coverName(it)).isFile }
            if (kept.size == s.tracks.size && covers.size == s.covers.size) s
            else s.copy(tracks = kept, covers = covers)
        }
        return dropped
    }

    // --- Internals ------------------------------------------------------------

    private inline fun update(block: (OfflineSnapshot) -> OfflineSnapshot) {
        synchronized(lock) {
            val next = block(state)
            if (next !== state) write(next)
        }
    }

    /** Whole file, then rename: a kill mid-write cannot leave half an index. */
    private fun write(next: OfflineSnapshot) {
        root.mkdirs()
        val stamped = next.copy(version = OfflineSnapshot.VERSION)
        tempFile.writeText(json.encodeToString(OfflineSnapshot.serializer(), stamped))
        if (!tempFile.renameTo(indexFile)) {
            // Some filesystems refuse to rename onto an existing file.
            indexFile.delete()
            tempFile.renameTo(indexFile)
        }
        publish(stamped)
    }

    private fun load() {
        val text = runCatching { indexFile.takeIf { it.isFile }?.readText() }.getOrNull()
        val read = text
            ?.let { runCatching { json.decodeFromString(OfflineSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { it.version == OfflineSnapshot.VERSION }
            ?: OfflineSnapshot()
        publish(read)
    }

    private fun publish(next: OfflineSnapshot) {
        state = next
        byId = next.tracks.associateBy { it.track.id }
        coverSet = next.covers.toSet()
    }

    companion object {
        /**
         * The file name a cover path gets on disk. Flattened rather than nested,
         * because the server serves one directory and a path separator in a file
         * name is how a download escapes its own folder.
         */
        fun coverName(path: String): String =
            path.trim('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

        /**
         * The extension a downloaded file gets. It is cosmetic - the player
         * sniffs the content either way - but a directory of `4711.flac` is
         * readable and one of `4711.bin` is not.
         */
        fun extensionFor(track: Track, contentType: String?): String {
            val fromType = contentType?.substringBefore(';')?.trim()?.lowercase()?.let { MIME[it] }
            val fromCodec = track.codec.lowercase().replace(Regex("[^a-z0-9]"), "").takeIf { it.isNotEmpty() }
            return fromType ?: fromCodec ?: "audio"
        }

        /** Mirrors `AUDIO_MIME` in the server's `src/routes/api.js`. */
        private val MIME = mapOf(
            "audio/mpeg" to "mp3",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/aiff" to "aiff",
            "audio/x-ms-wma" to "wma",
            "audio/x-monkeys-audio" to "ape",
            "audio/x-wavpack" to "wv",
            "audio/x-musepack" to "mpc",
            "audio/x-dsf" to "dsf",
            "audio/x-dff" to "dff",
        )
    }
}
