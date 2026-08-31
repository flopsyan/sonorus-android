package org.sonorus.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a file is deleted.
 *
 * This is the piece of the download sync worth being paranoid about: everything
 * else costs a wasted request, and this one costs music somebody wanted. The
 * cases below are Florian's own, in his words:
 *
 *  - a song added to a downloaded playlist is fetched,
 *  - a song taken out of it goes,
 *  - **unless it also sits in a downloaded album**, and then it stays.
 */
class ReconcileTest {

    @Test
    fun `a song added to the collection is fetched`() {
        val plan = Reconcile.plan(
            previous = listOf(1, 2),
            current = listOf(1, 2, 3),
            downloaded = setOf(1, 2),
        )
        assertEquals(listOf(3), plan.add)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `a song taken out of the collection is deleted`() {
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3),
            current = listOf(1, 2),
            downloaded = setOf(1, 2, 3),
        )
        assertEquals(listOf(3), plan.delete)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `a song another downloaded collection holds is not deleted`() {
        // The case Florian named: the song is in a downloaded album as well.
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3),
            current = listOf(1, 2),
            downloaded = setOf(1, 2, 3),
            heldElsewhere = setOf(3),
        )
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `a song somebody fetched by hand is not deleted either`() {
        // `heldElsewhere` carries the manual list too - a song downloaded from
        // its own menu is held by nothing else, and would otherwise be the
        // first thing a reconcile threw away.
        val plan = Reconcile.plan(
            previous = listOf(7),
            current = emptyList(),
            downloaded = setOf(7),
            heldElsewhere = setOf(7),
        )
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `an empty answer takes the whole collection with it`() {
        // Not a wish, a warning. This is what "the server says the collection
        // is empty" has to mean, and it is why DownloadSync.fetch answers null
        // rather than an empty list for a kind it does not know: a kind a screen
        // registers but the sync has never heard of would arrive here and wipe
        // every song it holds.
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3),
            current = emptyList(),
            downloaded = setOf(1, 2, 3),
        )
        assertEquals(listOf(1, 2, 3), plan.delete)
    }

    @Test
    fun `a song that is not on the phone is nothing to delete`() {
        val plan = Reconcile.plan(
            previous = listOf(1, 2),
            current = listOf(1),
            downloaded = setOf(1),
        )
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `what an interrupted run never got to is picked up`() {
        // Not only what is *new*: the collection holds five songs, three
        // arrived before the Wi-Fi went, and the other two are still owed.
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3, 4, 5),
            current = listOf(1, 2, 3, 4, 5),
            downloaded = setOf(1, 2, 3),
        )
        assertEquals(listOf(4, 5), plan.add)
    }

    @Test
    fun `a song deleted by hand stays deleted`() {
        // Without the exclusion this is the loop that makes "Download
        // entfernen" look broken: the collection holds it, the phone does not,
        // so the next sync fetches it back.
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3),
            current = listOf(1, 2, 3),
            downloaded = setOf(1, 2),
            excluded = setOf(3),
        )
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `a collection that did not move is left alone`() {
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3),
            current = listOf(1, 2, 3),
            downloaded = setOf(1, 2, 3),
        )
        assertTrue(plan.add.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `both halves at once, and each song only once`() {
        val plan = Reconcile.plan(
            previous = listOf(1, 2, 3, 3),
            current = listOf(2, 3, 4, 4),
            downloaded = setOf(1, 2, 3),
        )
        assertEquals(listOf(4), plan.add)
        assertEquals(listOf(1), plan.delete)
    }

    @Test
    fun `an empty collection lets go of everything it held`() {
        // Emptying a playlist in the browser really does mean the songs go.
        val plan = Reconcile.plan(
            previous = listOf(1, 2),
            current = emptyList(),
            downloaded = setOf(1, 2),
        )
        assertEquals(listOf(1, 2), plan.delete)
    }
}
