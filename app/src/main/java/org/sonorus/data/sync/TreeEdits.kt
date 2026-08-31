package org.sonorus.data.sync

import org.sonorus.data.model.Playlist
import org.sonorus.data.model.PlaylistFolder
import org.sonorus.data.model.PlaylistTree

/**
 * The playlist tree, changed the way the server would change it.
 *
 * Offline the sidebar is drawn from the tree this phone last saw, so an edit
 * made without a server has to be made here as well - otherwise the app takes
 * the rename, queues it, and goes on showing the old name until the next
 * bootstrap, which reads exactly like the edit was dropped.
 *
 * Pure functions over the tree, so the rules can be proven on a plain JVM: the
 * server's own behaviour is mirrored here, and the one place it is easy to get
 * wrong is deleting a folder - **its playlists are kept and move up to the top
 * level**, they are not deleted with it.
 */
object TreeEdits {

    fun addPlaylist(tree: PlaylistTree, playlist: Playlist): PlaylistTree =
        if (playlist.folderId == null) {
            tree.copy(loose = tree.loose + playlist)
        } else {
            tree.copy(
                folders = tree.folders.map {
                    if (it.id == playlist.folderId) it.copy(playlists = it.playlists + playlist) else it
                }
            )
        }

    fun renamePlaylist(tree: PlaylistTree, id: Int, name: String): PlaylistTree = map(tree) {
        if (it.id == id) it.copy(name = name) else it
    }

    fun deletePlaylist(tree: PlaylistTree, id: Int): PlaylistTree = tree.copy(
        folders = tree.folders.map { f -> f.copy(playlists = f.playlists.filterNot { it.id == id }) },
        loose = tree.loose.filterNot { it.id == id },
    )

    fun movePlaylist(tree: PlaylistTree, id: Int, folderId: Int?): PlaylistTree {
        val playlist = find(tree, id) ?: return tree
        return addPlaylist(deletePlaylist(tree, id), playlist.copy(folderId = folderId))
    }

    /** The count and the length a list shows in the sidebar, after a song moved. */
    fun setCount(tree: PlaylistTree, id: Int, tracks: Int, duration: Double): PlaylistTree = map(tree) {
        if (it.id == id) it.copy(trackCount = tracks, duration = duration) else it
    }

    fun addFolder(tree: PlaylistTree, id: Int, name: String): PlaylistTree =
        tree.copy(folders = tree.folders + PlaylistFolder(id = id, name = name))

    fun renameFolder(tree: PlaylistTree, id: Int, name: String): PlaylistTree =
        tree.copy(folders = tree.folders.map { if (it.id == id) it.copy(name = name) else it })

    /** Deleting a folder keeps its playlists - they move to the top level. */
    fun deleteFolder(tree: PlaylistTree, id: Int): PlaylistTree {
        val folder = tree.folders.firstOrNull { it.id == id } ?: return tree
        return tree.copy(
            folders = tree.folders.filterNot { it.id == id },
            loose = tree.loose + folder.playlists.map { it.copy(folderId = null) },
        )
    }

    fun find(tree: PlaylistTree, id: Int): Playlist? =
        (tree.loose + tree.folders.flatMap { it.playlists }).firstOrNull { it.id == id }

    private fun map(tree: PlaylistTree, block: (Playlist) -> Playlist): PlaylistTree = tree.copy(
        folders = tree.folders.map { f -> f.copy(playlists = f.playlists.map(block)) },
        loose = tree.loose.map(block),
    )
}
