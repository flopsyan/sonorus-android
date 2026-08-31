package org.sonorus.data.sync

import org.sonorus.data.model.Playlist
import org.sonorus.data.model.PlaylistFolder
import org.sonorus.data.model.PlaylistTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree as it changes without a server.
 *
 * The point of these is that the phone shows the same thing the server will
 * show once the queue has been sent - an edit that looks different offline and
 * online is an edit somebody will make twice.
 */
class TreeEditsTest {

    private val tree = PlaylistTree(
        folders = listOf(
            PlaylistFolder(
                id = 1,
                name = "Abends",
                playlists = listOf(Playlist(id = 10, name = "Ruhig", folderId = 1)),
            )
        ),
        loose = listOf(Playlist(id = 20, name = "Autofahrt")),
    )

    @Test
    fun `a new playlist lands where it was made`() {
        val loose = TreeEdits.addPlaylist(tree, Playlist(id = -1, name = "Flug"))
        assertEquals(listOf(20, -1), loose.loose.map { it.id })

        val inFolder = TreeEdits.addPlaylist(tree, Playlist(id = -1, name = "Flug", folderId = 1))
        assertEquals(listOf(10, -1), inFolder.folders.single().playlists.map { it.id })
        assertEquals(1, inFolder.loose.size)
    }

    @Test
    fun `renaming reaches into a folder as well`() {
        assertEquals("Leise", TreeEdits.renamePlaylist(tree, 10, "Leise").folders.single().playlists.single().name)
        assertEquals("Bahn", TreeEdits.renamePlaylist(tree, 20, "Bahn").loose.single().name)
    }

    @Test
    fun `deleting takes the list out of wherever it is`() {
        assertTrue(TreeEdits.deletePlaylist(tree, 10).folders.single().playlists.isEmpty())
        assertTrue(TreeEdits.deletePlaylist(tree, 20).loose.isEmpty())
    }

    @Test
    fun `moving a list into a folder takes it out of the top level`() {
        val moved = TreeEdits.movePlaylist(tree, 20, folderId = 1)
        assertEquals(listOf(10, 20), moved.folders.single().playlists.map { it.id })
        assertTrue(moved.loose.isEmpty())
        assertEquals(1, TreeEdits.find(moved, 20)?.folderId)
    }

    @Test
    fun `moving a list out of a folder puts it at the top`() {
        val moved = TreeEdits.movePlaylist(tree, 10, folderId = null)
        assertTrue(moved.folders.single().playlists.isEmpty())
        assertEquals(listOf(20, 10), moved.loose.map { it.id })
        assertNull(TreeEdits.find(moved, 10)?.folderId)
    }

    @Test
    fun `deleting a folder keeps its playlists`() {
        // The server's own behaviour, and the one that is easy to get wrong:
        // the lists move up, they are not deleted with the folder.
        val gone = TreeEdits.deleteFolder(tree, 1)
        assertTrue(gone.folders.isEmpty())
        assertEquals(listOf(20, 10), gone.loose.map { it.id })
        assertNull(gone.loose.first { it.id == 10 }.folderId)
    }

    @Test
    fun `a folder can be renamed and a new one appears`() {
        assertEquals("Nachts", TreeEdits.renameFolder(tree, 1, "Nachts").folders.single().name)
        assertEquals(listOf(1, -2), TreeEdits.addFolder(tree, -2, "Neu").folders.map { it.id })
    }

    @Test
    fun `the sidebar count follows a song in or out`() {
        val counted = TreeEdits.setCount(tree, 20, tracks = 3, duration = 600.0)
        assertEquals(3, TreeEdits.find(counted, 20)?.trackCount)
        assertEquals(600.0, TreeEdits.find(counted, 20)?.duration ?: 0.0, 0.001)
    }

    @Test
    fun `an edit that names nothing changes nothing`() {
        assertEquals(tree, TreeEdits.renamePlaylist(tree, 999, "Nichts"))
        assertEquals(tree, TreeEdits.deletePlaylist(tree, 999))
        assertEquals(tree, TreeEdits.movePlaylist(tree, 999, folderId = 1))
        assertEquals(tree, TreeEdits.deleteFolder(tree, 999))
    }
}
