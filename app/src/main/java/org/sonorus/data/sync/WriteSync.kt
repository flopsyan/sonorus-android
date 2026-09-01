package org.sonorus.data.sync

import org.sonorus.data.ApiException
import org.sonorus.data.SonorusApi
import org.sonorus.data.download.DownloadStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends what was edited offline, once there is a server again. Plays go their
 * own way, through [org.sonorus.data.PlayLog].
 *
 * In the order it happened, one at a time, oldest first - which matters more
 * than it looks: creating a playlist and putting six songs into it is seven
 * writes that only mean anything in that order, and the six adds name a list
 * that did not exist when they were written.
 *
 * Two kinds of failure, and telling them apart is the whole robustness of this:
 *
 *  - **The server said no.** A playlist somebody deleted in the browser, a
 *    track that is gone. The write is dropped and the queue moves on, because a
 *    write that can never succeed would block every later one for good.
 *  - **The server did not answer.** Then nothing is dropped and the flush stops
 *    where it is; the next connection picks it up at the same place.
 *
 * That distinction is the same one [org.sonorus.data.Library] makes for reads,
 * and for the same reason: an error the server produced proves the server is
 * there.
 */
class WriteSync(
    private val api: SonorusApi,
    private val pending: PendingWrites,
    private val store: DownloadStore,
) {

    /** What one flush did. [stopped] means the connection went, not the queue. */
    data class Result(val sent: Int = 0, val dropped: Int = 0, val stopped: Boolean = false) {
        val any: Boolean get() = sent > 0 || dropped > 0
    }

    /** One flush at a time: two would send the same write twice. */
    private val running = Mutex()

    suspend fun flush(): Result = running.withLock {
        var sent = 0
        var dropped = 0
        while (true) {
            val write = pending.first() ?: break
            try {
                send(write)
                pending.done(write.seq)
                sent++
            } catch (error: ApiException) {
                // `not_json` and `bad_url` are not the server talking - a captive
                // portal or a wrong address answered instead.
                if (error.code == "not_json" || error.code == "bad_url") {
                    return@withLock Result(sent, dropped, stopped = true)
                }
                pending.done(write.seq)
                dropped++
            } catch (error: Throwable) {
                return@withLock Result(sent, dropped, stopped = true)
            }
        }
        Result(sent, dropped)
    }

    private suspend fun send(write: PendingWrite) {
        when (write.kind) {
            "rating" -> api.rate(write.trackId, write.stars)
            "progress" -> api.setProgress(write.trackId, write.position, write.completed)

            "playlistCreate" -> {
                val tree = api.createPlaylist(write.name, realFolder(write.folderId))
                val created = (tree.tree.loose + tree.tree.folders.flatMap { it.playlists })
                    .filter { it.name == write.name }
                    .maxByOrNull { it.id }
                if (created != null && write.playlistId < 0) {
                    pending.remapPlaylist(write.playlistId, created.id)
                    store.remapCollection("playlist", write.playlistId, created.id, created.name)
                }
            }

            "playlistRename" -> api.renamePlaylist(write.playlistId, write.name)
            "playlistMove" -> api.movePlaylist(write.playlistId, realFolder(write.folderId))
            "playlistDelete" -> api.deletePlaylist(write.playlistId)
            "playlistAdd" -> api.addToPlaylist(write.playlistId, listOf(write.trackId))

            // A row the server never had is nothing to remove. It cannot
            // normally get this far - an add and a remove of the same song
            // cancel in the queue - but a queue carried over from an older
            // build might, and asking the server to delete item 0 would be a
            // 404 that reads like a real failure.
            "playlistRemove" -> if (write.itemId > 0) {
                api.removeFromPlaylist(write.playlistId, write.itemId)
            }

            "folderCreate" -> {
                val tree = api.createFolder(write.name)
                val created = tree.tree.folders.filter { it.name == write.name }.maxByOrNull { it.id }
                val local = write.folderId
                if (created != null && local != null && local < 0) pending.remapFolder(local, created.id)
            }

            "folderRename" -> write.folderId?.let { api.renameFolder(it, write.name) }
            "folderDelete" -> write.folderId?.let { api.deleteFolder(it) }
        }
    }

    /**
     * A folder id that is still local names a folder this queue creates a few
     * writes later. It cannot be sent, and null - the top level - is the
     * honest place to put the list until the folder is really there.
     */
    private fun realFolder(folderId: Int?): Int? = folderId?.takeIf { it > 0 }
}
