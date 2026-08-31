package org.sonorus.data.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What was done without a server, waiting for one.
 *
 * The promise here is the mirror of the download index's: **what is in this
 * file really happened, and it happens on the server exactly once.** So the
 * tests are about order, about not sending nonsense, and about surviving a
 * phone that was closed in between.
 */
class PendingWritesTest {

    private lateinit var root: File
    private lateinit var queue: PendingWrites

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sonorus-pending").toFile()
        queue = PendingWrites(File(root, "pending.json"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A second queue over the same file is what a restart is. */
    private fun reopen() = PendingWrites(File(root, "pending.json"))

    @Test
    fun `writes come back in the order they were made`() {
        queue.rate(1, 5)
        queue.addToPlaylist(7, 2)
        queue.rate(3, 4)

        val kinds = reopen().writes.sortedBy { it.seq }.map { it.kind }
        assertEquals(listOf("rating", "playlistAdd", "rating"), kinds)
    }

    @Test
    fun `a second rating of the same song replaces the first`() {
        queue.rate(1, 3)
        queue.rate(1, 5)
        queue.rate(2, 1)

        val ratings = queue.writes.filter { it.kind == "rating" }
        assertEquals(2, ratings.size)
        assertEquals(5, ratings.first { it.trackId == 1 }.stars)
    }

    @Test
    fun `adding a song and taking it out again is nothing at all`() {
        // It has to collapse rather than be sent as two writes: the removal
        // names a playlist row that has never existed on the server.
        assertTrue(queue.addToPlaylist(7, 2))
        assertFalse(queue.removeFromPlaylist(7, 2, itemId = 0))

        assertTrue(queue.writes.none { it.playlistId == 7 })
    }

    @Test
    fun `taking a song out and putting it back is nothing either`() {
        assertTrue(queue.removeFromPlaylist(7, 2, itemId = 44))
        assertFalse(queue.addToPlaylist(7, 2))

        assertTrue(queue.writes.isEmpty())
    }

    @Test
    fun `the same song in two playlists is two writes`() {
        queue.addToPlaylist(7, 2)
        queue.addToPlaylist(8, 2)

        assertEquals(2, queue.writes.count { it.kind == "playlistAdd" })
    }

    @Test
    fun `local ids count down and never repeat, not even after a restart`() {
        val first = queue.localId()
        val second = queue.localId()
        val third = reopen().localId()

        assertEquals(-1, first)
        assertEquals(-2, second)
        assertEquals(-3, third)
    }

    @Test
    fun `a playlist made offline and filled is one create and its adds`() {
        val local = queue.localId()
        queue.createPlaylist(local, "Flug", folderId = null)
        queue.addToPlaylist(local, 1)
        queue.addToPlaylist(local, 2)

        // What the server answers with once the create really goes out.
        queue.remapPlaylist(local, 42)

        val writes = queue.writes.sortedBy { it.seq }
        assertEquals(listOf(42, 42, 42), writes.map { it.playlistId })
        assertEquals("playlistCreate", writes.first().kind)
    }

    @Test
    fun `deleting a playlist that never left the phone takes its writes with it`() {
        val local = queue.localId()
        queue.createPlaylist(local, "Flug", folderId = null)
        queue.addToPlaylist(local, 1)

        queue.deletePlaylist(local)

        // Nothing at all is sent: the server never heard of this list.
        assertTrue(queue.writes.isEmpty())
    }

    @Test
    fun `deleting a real playlist drops its other writes and keeps the delete`() {
        queue.renamePlaylist(12, "Anders")
        queue.addToPlaylist(12, 3)

        queue.deletePlaylist(12)

        assertEquals(listOf("playlistDelete"), queue.writes.map { it.kind })
    }

    @Test
    fun `what waits is what the user changed`() {
        queue.rate(1, 5)
        queue.addToPlaylist(7, 2)

        // Plays are deliberately not in here - they have their own log, and a
        // number climbing while you simply listen would read as something stuck.
        assertEquals(2, queue.edits)
        assertEquals(2, queue.count)
    }

    @Test
    fun `what has been sent is gone, the rest keeps its order`() {
        queue.rate(1, 5)
        queue.addToPlaylist(7, 2)

        val oldest = queue.first()!!
        assertEquals("rating", oldest.kind)
        queue.done(oldest.seq)
        assertEquals(listOf("playlistAdd"), queue.writes.map { it.kind })

        queue.done(queue.first()!!.seq)
        assertTrue(reopen().isEmpty)
    }

    @Test
    fun `a file from another version is ignored rather than half read`() {
        File(root, "pending.json").writeText("""{"version":99,"writes":[{"seq":1,"kind":"rating"}]}""")
        assertTrue(reopen().isEmpty)
    }

    @Test
    fun `nonsense in the file costs the queue, not the app`() {
        File(root, "pending.json").writeText("{ this is not json")
        val fresh = reopen()
        assertTrue(fresh.isEmpty)
        assertNull(fresh.first())
        // And it still works afterwards.
        fresh.rate(1, 4)
        assertEquals(1, fresh.count)
    }
}
