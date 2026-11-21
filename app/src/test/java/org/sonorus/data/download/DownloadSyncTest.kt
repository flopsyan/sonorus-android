package org.sonorus.data.download

import org.sonorus.data.model.Track
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

    // --- One rating, without asking the server for the whole selection ----------

    private fun stars(vararg ids: Int) =
        OfflineCollection(kind = "stars", id = 5, name = "5, 4, 3, Nicht bewertet", ids = listOf(5, 4, 3, 0), trackIds = ids.toList())

    /** A song that lies on the phone, the way a finished download left it. */
    private fun downloaded(id: Int) {
        val file = store.targetOf(id, "mp3").apply { writeBytes(ByteArray(8)) }
        store.put(DownloadedTrack(track = part(id), file = file.name, bytes = 8))
    }

    @Test
    fun `a song rated out of a downloaded star selection leaves it, and goes unless something holds it`() {
        store.rememberCollection(stars(1, 2, 3))
        downloaded(1)
        downloaded(2)
        store.rememberManual(listOf(2))

        assertFalse(applyRated(store, target, trackId = 1, stars = 2))
        assertFalse(applyRated(store, target, trackId = 2, stars = 1))

        assertEquals(listOf(3), store.collectionOf("stars", listOf(5, 4, 3, 0))?.trackIds)
        assertEquals("the hand download stays", listOf(1), target.removed)
    }

    @Test
    fun `a song rated into the selection joins it and is fetched if it is not here`() {
        store.rememberCollection(stars(1))
        downloaded(2)

        assertTrue("not on the phone: fetch it", applyRated(store, target, trackId = 3, stars = 4))
        assertFalse("already here", applyRated(store, target, trackId = 2, stars = 0))

        assertEquals(listOf(1, 3, 2), store.collectionOf("stars", listOf(5, 4, 3, 0))?.trackIds)
        assertTrue(target.removed.isEmpty())
    }

    @Test
    fun `a rating inside the selection changes nothing, and one deleted by hand stays deleted`() {
        store.rememberCollection(stars(1, 2))
        downloaded(1)
        store.exclude(listOf(2))
        val before = store.snapshot

        assertFalse(applyRated(store, target, trackId = 1, stars = 5))
        assertFalse("excluded by hand", applyRated(store, target, trackId = 2, stars = 3))

        assertTrue("no write for nothing", before === store.snapshot)
        assertTrue(target.removed.isEmpty())
    }

    @Test
    fun `a rating touches no other kind of collection`() {
        store.rememberCollection(OfflineCollection(kind = "playlist", id = 5, trackIds = listOf(1)))
        downloaded(1)

        assertFalse(applyRated(store, target, trackId = 1, stars = 1))

        assertEquals(listOf(1), store.collectionOf("playlist", listOf(5))?.trackIds)
        assertTrue(target.removed.isEmpty())
    }

    // --- An index that fell behind the phone -------------------------------------
    // 0.31.1 could lose index writes without a word, so the file may be older than
    // what lies in the audio folder. The start reconcile has to mend that.

    @Test
    fun `a download the index lost is fetched again, and nothing it still lists is deleted`() {
        store.rememberCollection(stars(1, 2))
        downloaded(1)
        // Song 2 finished, but its entry never reached the file.
        store.targetOf(2, "mp3").writeBytes(ByteArray(8))
        val stale = DownloadStore(root)

        val change = applyReconcile(stale, target, stale.collections.single(), listOf(part(1), part(2)))

        assertEquals(listOf(2), target.added)
        assertTrue(target.removed.isEmpty())
        assertEquals(DownloadSync.Change(added = 1), change)
    }

    @Test
    fun `a baseline older than the phone deletes only what really left and is held by nothing`() {
        // The file still says 1, 2, 3; song 3 left the selection since, song 4 joined and was fetched.
        store.rememberCollection(stars(1, 2, 3))
        downloaded(1)
        downloaded(2)
        downloaded(3)
        store.rememberManual(listOf(2))
        val stale = DownloadStore(root)
        store.targetOf(4, "mp3").writeBytes(ByteArray(8))

        applyReconcile(stale, target, stale.collections.single(), listOf(part(1), part(4)))

        assertEquals("the one the index never heard of", listOf(4), target.added)
        assertEquals("3 left and nothing holds it; 2 was fetched by hand", listOf(3), target.removed)
    }
}
