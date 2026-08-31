package org.sonorus.data.download

import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.Genre
import org.sonorus.data.model.PlaylistTree
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

    /**
     * The last look at the account, in a file of its own.
     *
     * It used to ride along in the index, and that was the bug behind the worst
     * failure this feature can have: a cold start with no network showed the
     * **login screen**. Three ways led there, and all three were the same
     * mistake - the proof that somebody is logged in was being thrown away with
     * data that had nothing to do with it. "Alle Downloads entfernen" wiped the
     * index and the account with it; an index that failed to parse fell back to
     * an empty snapshot and took the account down too; and a [OfflineSnapshot]
     * VERSION bump discarded the whole file on the next app update.
     *
     * Kept apart, none of the three can happen: the downloads are a fact about
     * this phone's storage and may be thrown away freely, while the account is
     * the answer to "is anybody logged in", which only logging out may change.
     */
    private val accountFile = File(root, "account.json")
    private val accountTemp = File(root, "account.json.tmp")

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

    /** The index entry of a downloaded track, or null if it is not here. */
    fun entryOf(trackId: Int): DownloadedTrack? = byId[trackId]

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

    /**
     * What a collection held when the server was last asked - and, for a
     * playlist, the order somebody chose.
     *
     * Matched on [OfflineCollection.key] rather than on the plain id, or the
     * genre page `/genres/3,7` and the page `/genres/3` would overwrite each
     * other's baseline.
     */
    fun rememberCollection(collection: OfflineCollection) = update { s ->
        s.copy(playlists = s.playlists.filterNot { it.key == collection.key } + collection)
    }

    /** The collections this phone is keeping in step with the server. */
    val collections: List<OfflineCollection> get() = state.playlists

    fun collectionOf(kind: String, ids: List<Int>): OfflineCollection? {
        val key = OfflineCollection(kind = kind, id = ids.firstOrNull() ?: 0, ids = ids).key
        return state.playlists.firstOrNull { it.key == key }
    }

    /** This collection is no longer downloaded as a whole. */
    fun forgetCollection(key: String) = update { s ->
        s.copy(playlists = s.playlists.filterNot { it.key == key })
    }

    /**
     * Songs somebody asked for on their own. They are held by nothing else, so
     * without this they would be the first thing a reconcile deleted.
     */
    fun rememberManual(ids: List<Int>) = update { s ->
        val fresh = ids.filterNot { it in s.manual }
        if (fresh.isEmpty()) s else s.copy(manual = s.manual + fresh)
    }

    /**
     * Whether anything still wants [trackId] on this phone: a collection that
     * holds it, or the fact that somebody once fetched it by hand.
     *
     * [exceptKey] is the collection that is letting go of it right now, and it
     * must not count itself.
     */
    fun isHeld(trackId: Int, exceptKey: String? = null): Boolean {
        val s = state
        if (trackId in s.manual) return true
        return s.playlists.any { it.key != exceptKey && trackId in it.trackIds }
    }

    /** The same question for a whole list at once - what a reconcile needs. */
    fun heldBy(exceptKey: String? = null): Set<Int> {
        val s = state
        return s.manual.toSet() + s.playlists.filter { it.key != exceptKey }.flatMap { it.trackIds }
    }

    /**
     * "I deleted this one on purpose" - remembered so the collection it belongs
     * to does not fetch it back on the next sync. See [OfflineSnapshot.excluded].
     */
    fun exclude(trackIds: List<Int>) = update { s ->
        val fresh = trackIds.filterNot { it in s.excluded }
        if (fresh.isEmpty()) s else s.copy(excluded = s.excluded + fresh)
    }

    /**
     * A collection that lived on this phone only has a real id now.
     *
     * The songs stay exactly where they are - only the name of the box changes,
     * which is what keeps a playlist created in a plane from turning into a
     * second, empty list the moment it is synced.
     */
    fun remapCollection(kind: String, local: Int, real: Int, name: String) = update { s ->
        val old = s.playlists.firstOrNull { it.kind == kind && it.id == local } ?: return@update s
        s.copy(
            playlists = s.playlists.filterNot { it.key == old.key } +
                old.copy(id = real, ids = emptyList(), name = name.ifEmpty { old.name }),
        )
    }

    /**
     * A song into, or out of, a playlist that lives on this phone.
     *
     * The collection's list is the baseline the next reconcile diffs against,
     * so an edit made offline has to be written into it: once the queued write
     * reaches the server, the server holds the same list, and the sync then
     * has nothing to do - which is the correct answer.
     */
    fun addToCollection(playlistId: Int, trackId: Int, name: String = "") = update { s ->
        val old = s.playlists.firstOrNull { it.kind == "playlist" && it.id == playlistId }
        val next = (old ?: OfflineCollection(kind = "playlist", id = playlistId, name = name))
            .let { it.copy(trackIds = it.trackIds.filterNot { id -> id == trackId } + trackId) }
        s.copy(playlists = s.playlists.filterNot { it.key == next.key } + next)
    }

    fun removeFromCollection(playlistId: Int, trackId: Int) = update { s ->
        val old = s.playlists.firstOrNull { it.kind == "playlist" && it.id == playlistId }
            ?: return@update s
        s.copy(
            playlists = s.playlists.filterNot { it.key == old.key } +
                old.copy(trackIds = old.trackIds.filterNot { it == trackId }),
        )
    }

    /**
     * The playlist tree as this phone last saw it, changed by hand.
     *
     * Offline this is what the sidebar draws, so a list renamed or deleted
     * without a server has to be changed here too - otherwise the app would
     * accept the edit and go on showing the old name until the next bootstrap.
     */
    fun updateTree(block: (PlaylistTree) -> PlaylistTree) {
        synchronized(lock) {
            val account = state.account ?: return
            val next = account.copy(playlists = block(account.playlists))
            writeAccount(next)
            publish(state.copy(account = next))
        }
    }

    /** Asking for these songs again takes back the exclusion. */
    fun unexclude(trackIds: List<Int>) = update { s ->
        if (trackIds.none { it in s.excluded }) s
        else s.copy(excluded = s.excluded.filterNot { it in trackIds })
    }

    fun rememberGenres(genres: List<Genre>) = update { s -> s.copy(genres = genres) }

    /**
     * The last look at the account. Stored on every successful bootstrap,
     * because who is logged in and how the player is set cannot be asked for
     * once the server is gone - and the shell needs both before it draws.
     *
     * Written to [accountFile] rather than into the index; see the note there.
     */
    fun rememberAccount(bootstrap: Bootstrap) {
        synchronized(lock) {
            writeAccount(bootstrap)
            publish(state.copy(account = bootstrap))
        }
    }

    /** Logging out. The only thing that may take the account away. */
    fun forgetAccount() {
        synchronized(lock) {
            accountFile.delete()
            publish(state.copy(account = null))
        }
    }

    /**
     * Drops one song and its file. The artwork stays - other songs share it.
     *
     * **The collections keep their track lists**, which is the opposite of what
     * this used to do. They are the baseline the next reconcile diffs against,
     * and a baseline edited from this side would read as "the server dropped
     * this song" - so the sync would delete it a second time, or fetch it back
     * because the server still has it. A collection is only forgotten once
     * nothing of it is left on the phone at all.
     */
    fun remove(trackId: Int) = update { s ->
        s.tracks.firstOrNull { it.track.id == trackId }?.let { File(audioDir, it.file).delete() }
        val kept = s.tracks.filterNot { it.track.id == trackId }
        val have = kept.map { it.track.id }.toSet()
        s.copy(
            tracks = kept,
            manual = s.manual.filterNot { it == trackId },
            playlists = s.playlists.filter { c -> c.trackIds.any { it in have } },
        )
    }

    /** The rating somebody gave offline, written onto the row this phone holds. */
    fun applyRating(trackId: Int, stars: Int) = update { s ->
        val entry = s.tracks.firstOrNull { it.track.id == trackId } ?: return@update s
        if (entry.track.stars == stars) return@update s
        s.copy(tracks = s.tracks.map {
            if (it.track.id == trackId) it.copy(track = it.track.copy(stars = stars)) else it
        })
    }

    /**
     * Every downloaded file goes, and the index with it.
     *
     * The account snapshot deliberately **stays**. Throwing away the songs on
     * this phone says nothing about who is logged in, and taking the login with
     * them is how a phone with no network ended up at the login screen after
     * nothing worse than "Alle Downloads entfernen".
     */
    fun clear() {
        synchronized(lock) {
            audioDir.deleteRecursively()
            coverDir.deleteRecursively()
            audioDir.mkdirs()
            coverDir.mkdirs()
            write(OfflineSnapshot(account = state.account))
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

    /**
     * Whole file, then rename: a kill mid-write cannot leave half an index.
     *
     * The account is stripped before writing. It lives in [accountFile] and
     * having it in both would let a stale copy in the index win on the next
     * load.
     */
    private fun write(next: OfflineSnapshot) {
        root.mkdirs()
        val stamped = next.copy(version = OfflineSnapshot.VERSION)
        tempFile.writeText(json.encodeToString(OfflineSnapshot.serializer(), stamped.copy(account = null)))
        if (!tempFile.renameTo(indexFile)) {
            // Some filesystems refuse to rename onto an existing file.
            indexFile.delete()
            tempFile.renameTo(indexFile)
        }
        publish(stamped)
    }

    private fun writeAccount(account: Bootstrap) {
        root.mkdirs()
        runCatching {
            accountTemp.writeText(json.encodeToString(Bootstrap.serializer(), account))
            if (!accountTemp.renameTo(accountFile)) {
                accountFile.delete()
                accountTemp.renameTo(accountFile)
            }
        }
    }

    /**
     * Reads both files, and reads them **independently**.
     *
     * A failure on one must not cost the other: an index that cannot be parsed
     * leaves an empty download list and a phone that is still logged in, which
     * is exactly what the situation is.
     */
    private fun load() {
        val index = runCatching { indexFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?.let { runCatching { json.decodeFromString(OfflineSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { it.version == OfflineSnapshot.VERSION }
            ?: OfflineSnapshot()

        val account = runCatching { accountFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?.let { runCatching { json.decodeFromString(Bootstrap.serializer(), it) }.getOrNull() }
            // An index written before the account moved out still carries one.
            // Taken over rather than dropped, so an update does not log anybody
            // out of their downloads.
            ?: index.account

        publish(index.copy(account = account))
        if (account != null && !accountFile.isFile) writeAccount(account)
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
