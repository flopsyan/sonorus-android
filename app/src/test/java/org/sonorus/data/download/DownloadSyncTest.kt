package org.sonorus.data.download

import org.sonorus.data.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The bug Florian reported on 07.09.: **"1 Song werden nachgeladen" at every
 * start**, after removing a radio play.
 *
 * It could not be reproduced by reading the code, because it is a matter of
 * order rather than of logic. The start reconcile asks the server about one
 * collection at a time; while a radio play's answer is still in flight, the play
 * is removed; the answer then arrives and [DownloadSync.apply] writes the
 * collection back. Its parts are now neither downloaded nor excluded, so every
 * later start plans to fetch them - and with downloads held for Wi-Fi, nothing
 * ever clears the plan.
 *
 * Only the store is real here. What the sync does to the phone goes through
 * [DownloadTarget], and the decision itself sits in [applyReconcile] - both so
 * that this can be driven without an Android context.
 */
class DownloadSyncTest {

    private lateinit var root: File
    private lateinit var store: DownloadStore
    private lateinit var target: Recorder

    /** Remembers what the sync asked for instead of touching a phone. */
    private class Recorder : DownloadTarget {
        val added = mutableListOf<Int>()
        val removed = mutableListOf<Int>()

        override fun add(tracks: List<Track>, manual: Boolean) {
            added += tracks.map { it.id }
        }

        override fun remove(trackId: Int) {
            removed += trackId
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sonorus-sync").toFile()
        store = DownloadStore(root)
        target = Recorder()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun part(id: Int) = Track(id = id, title = "Teil $id", artist = "Sprecher")

    private fun drama(vararg ids: Int) =
        OfflineCollection(kind = "drama", id = 7, name = "1984", trackIds = ids.toList())

    @Test
    fun `a play removed while its answer was in flight is not brought back`() {
        val collection = drama(1, 2, 3)
        store.rememberCollection(collection)

        // The user removes it - the store forgets it, and the parts go.
        store.forgetCollection(collection.key)

        // Now the reconcile that was already under way comes back with what the
        // server says the play holds.
        val change = applyReconcile(store, target, collection, listOf(part(1), part(2), part(3)))

        assertEquals(DownloadSync.Change(), change)
        assertTrue("nothing may be fetched", target.added.isEmpty())
        assertNull("the play must stay forgotten", store.collectionOf("drama", listOf(7)))
    }

    @Test
    fun `a collection the store still knows is reconciled as before`() {
        val collection = drama(1, 2)
        store.rememberCollection(collection)

        val change = applyReconcile(store, target, collection, listOf(part(1), part(2), part(3)))

        assertEquals(listOf(1, 2, 3), target.added)
        assertEquals(3, change.added)
        assertEquals(listOf(1, 2, 3), store.collectionOf("drama", listOf(7))?.trackIds)
    }

    @Test
    fun `an audiobook behaves exactly like a radio play, which was never checked`() {
        val book = OfflineCollection(kind = "book", id = 9, name = "Ein Buch", trackIds = listOf(4, 5))
        store.rememberCollection(book)
        store.forgetCollection(book.key)

        val change = applyReconcile(store, target, book, listOf(part(4), part(5)))

        assertEquals(DownloadSync.Change(), change)
        assertTrue(target.added.isEmpty())
        assertNull(store.collectionOf("book", listOf(9)))
    }
}
