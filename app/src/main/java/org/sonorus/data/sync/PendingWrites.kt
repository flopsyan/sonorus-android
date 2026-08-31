package org.sonorus.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One thing the user did that the server has not heard about yet.
 *
 * A flat record with a [kind] rather than a sealed hierarchy, and that is not
 * laziness: this file is written by one build of the app and read by the next,
 * exactly like the download index, and kotlinx polymorphism carries class names
 * into the JSON - which R8 is free to rename between two releases. A string and
 * a handful of nullable fields cannot be renamed by anybody.
 *
 * The same reasoning gives every field a default: a record written by an older
 * build is missing whatever was added since, and it has to read as "not set"
 * rather than as a parse error that throws the whole queue away.
 */
@Serializable
data class PendingWrite(
    val seq: Long = 0,
    /**
     * `rating`, `playlistCreate`, `playlistRename`, `playlistDelete`,
     * `playlistMove`, `playlistAdd`, `playlistRemove`, `folderCreate`,
     * `folderRename`, `folderDelete`.
     */
    val kind: String = "",
    val trackId: Int = 0,
    val stars: Int = 0,
    /** Negative while the playlist exists on this phone only. */
    val playlistId: Int = 0,
    /** The playlist *row* a track sits in, which only the server can name. */
    val itemId: Int = 0,
    val name: String = "",
    val folderId: Int? = null,
)

@Serializable
data class PendingState(
    val version: Int = 0,
    /** The next sequence number, so order survives a restart. */
    val next: Long = 1,
    /** The next local id to hand out. Counts *down* - see [PendingWrites.localId]. */
    val nextLocal: Int = -1,
    val writes: List<PendingWrite> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * What was *edited* offline, in the order it was done, waiting for a server.
 *
 * Plays are not in here. They have a log of their own ([org.sonorus.data.PlayLog]),
 * because they are not edits: they are not worth showing as "waiting", they need
 * a bound rather than a queue that grows, and they may be thrown away when the
 * downloads are.
 *
 * The file is written whole and renamed, like the download index, for the same
 * reason: a process killed mid-write must leave the previous queue or the new
 * one, never half of one. It is deliberately its own file and not part of the
 * index - throwing the downloads away says nothing about a rating that has not
 * been sent yet.
 *
 * Two rules keep the queue honest, and both are about not sending nonsense:
 *
 *  - **A second rating of the same song replaces the first.** The server only
 *    ever sees the last value anyway, and a queue that grows by one entry per
 *    tap on a star row would send a dozen requests to set one number.
 *  - **An add and a remove of the same song cancel each other out.** Putting a
 *    song into a playlist offline and taking it out again is a thing that never
 *    happened, and it *has* to collapse: the removal would carry no item id,
 *    because the row it names has never existed on the server.
 */
class PendingWrites(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val temp = File(file.path + ".tmp")
    private val lock = Any()

    @Volatile
    private var state: PendingState = load()

    val writes: List<PendingWrite> get() = state.writes
    val count: Int get() = state.writes.size
    val isEmpty: Boolean get() = state.writes.isEmpty()

    /** How many edits are waiting, for the line in Einstellungen. */
    val edits: Int get() = state.writes.size

    // --- Adding ---------------------------------------------------------------

    /**
     * An id for something that exists on this phone only.
     *
     * Negative, and counting down: a playlist created offline has to be
     * referable - songs are added to it, it is opened, it may even be renamed -
     * before the server has ever heard of it. Negative ids cannot collide with
     * the server's, which is what makes the swap on the way out safe.
     */
    fun localId(): Int = synchronized(lock) {
        val id = state.nextLocal
        write(state.copy(nextLocal = id - 1))
        id
    }

    fun rate(trackId: Int, stars: Int) = add(
        PendingWrite(kind = "rating", trackId = trackId, stars = stars),
        // Only the last value is worth sending.
        replaces = { it.kind == "rating" && it.trackId == trackId },
    )

    fun createPlaylist(localId: Int, name: String, folderId: Int?) =
        add(PendingWrite(kind = "playlistCreate", playlistId = localId, name = name, folderId = folderId))

    fun renamePlaylist(playlistId: Int, name: String) = add(
        PendingWrite(kind = "playlistRename", playlistId = playlistId, name = name),
        replaces = { it.kind == "playlistRename" && it.playlistId == playlistId },
    )

    fun movePlaylist(playlistId: Int, folderId: Int?) = add(
        PendingWrite(kind = "playlistMove", playlistId = playlistId, folderId = folderId),
        replaces = { it.kind == "playlistMove" && it.playlistId == playlistId },
    )

    /**
     * Deleting a playlist drops everything still queued for it: renaming a list
     * that is about to be deleted, or filling one, is work the server would do
     * and undo. A list that only ever existed here disappears completely.
     */
    fun deletePlaylist(playlistId: Int) = synchronized(lock) {
        val rest = state.writes.filterNot { it.playlistId == playlistId && it.kind.startsWith("playlist") }
        val local = playlistId < 0
        write(
            state.copy(
                writes = if (local) rest
                else rest + PendingWrite(seq = state.next, kind = "playlistDelete", playlistId = playlistId),
                next = if (local) state.next else state.next + 1,
            )
        )
    }

    /**
     * A song into a playlist. If the same song is queued to come *out* of that
     * playlist, the two cancel instead of both being sent.
     */
    fun addToPlaylist(playlistId: Int, trackId: Int): Boolean = cancelOrAdd(
        opposite = { it.kind == "playlistRemove" && it.playlistId == playlistId && it.trackId == trackId },
        entry = PendingWrite(kind = "playlistAdd", playlistId = playlistId, trackId = trackId),
    )

    /** [itemId] is the server's row for this song, or 0 if it was added offline. */
    fun removeFromPlaylist(playlistId: Int, trackId: Int, itemId: Int): Boolean = cancelOrAdd(
        opposite = { it.kind == "playlistAdd" && it.playlistId == playlistId && it.trackId == trackId },
        entry = PendingWrite(
            kind = "playlistRemove",
            playlistId = playlistId,
            trackId = trackId,
            itemId = itemId,
        ),
    )

    fun createFolder(localId: Int, name: String) =
        add(PendingWrite(kind = "folderCreate", folderId = localId, name = name))

    fun renameFolder(folderId: Int, name: String) = add(
        PendingWrite(kind = "folderRename", folderId = folderId, name = name),
        replaces = { it.kind == "folderRename" && it.folderId == folderId },
    )

    fun deleteFolder(folderId: Int) = synchronized(lock) {
        val rest = state.writes.filterNot { it.folderId == folderId && it.kind.startsWith("folder") }
        val local = folderId < 0
        write(
            state.copy(
                writes = if (local) rest
                else rest + PendingWrite(seq = state.next, kind = "folderDelete", folderId = folderId),
                next = if (local) state.next else state.next + 1,
            )
        )
    }

    // --- Working it off -------------------------------------------------------

    /** The oldest write that has not been sent, or null when there is nothing. */
    fun first(): PendingWrite? = state.writes.minByOrNull { it.seq }

    fun done(seq: Long) = synchronized(lock) {
        write(state.copy(writes = state.writes.filterNot { it.seq == seq }))
    }

    /**
     * The server has given the thing a real id. Everything still queued that
     * named the local one now names the real one.
     *
     * This is the whole reason local ids are negative: a playlist created and
     * filled in a plane is one create followed by ten adds, and the ten adds
     * were written before anybody could know what the list would be called.
     */
    fun remapPlaylist(local: Int, real: Int) = synchronized(lock) {
        write(
            state.copy(
                writes = state.writes.map {
                    if (it.playlistId == local) it.copy(playlistId = real) else it
                }
            )
        )
    }

    fun remapFolder(local: Int, real: Int) = synchronized(lock) {
        write(
            state.copy(
                writes = state.writes.map {
                    if (it.folderId == local) it.copy(folderId = real) else it
                }
            )
        )
    }

    fun clear() = synchronized(lock) { write(state.copy(writes = emptyList())) }

    // --- Internals ------------------------------------------------------------

    /** Answers the sequence number the write got, which a play needs to correct it. */
    private fun add(entry: PendingWrite, replaces: ((PendingWrite) -> Boolean)? = null): Long =
        synchronized(lock) {
            val seq = state.next
            val kept = if (replaces == null) state.writes else state.writes.filterNot(replaces)
            write(state.copy(writes = kept + entry.copy(seq = seq), next = seq + 1))
            seq
        }

    /** Answers false when the two cancelled each other out and nothing was queued. */
    private fun cancelOrAdd(opposite: (PendingWrite) -> Boolean, entry: PendingWrite): Boolean =
        synchronized(lock) {
            val undo = state.writes.lastOrNull(opposite)
            if (undo != null) {
                write(state.copy(writes = state.writes.filterNot { it.seq == undo.seq }))
                false
            } else {
                write(state.copy(writes = state.writes + entry.copy(seq = state.next), next = state.next + 1))
                true
            }
        }

    private fun write(next: PendingState) {
        val stamped = next.copy(version = PendingState.VERSION)
        state = stamped
        runCatching {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString(PendingState.serializer(), stamped))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun load(): PendingState =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            ?.let { runCatching { json.decodeFromString(PendingState.serializer(), it) }.getOrNull() }
            ?.takeIf { it.version == PendingState.VERSION }
            ?: PendingState()
}
