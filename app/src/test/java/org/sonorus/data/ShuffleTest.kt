package org.sonorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What "shuffled" has to sound like.
 *
 * The old shuffle was a correct one and still felt stuck on one artist, so the
 * thing worth asserting is not that the order is random - `shuffled()` was
 * already that - but that the same name stops following itself. The numbers in
 * these tests are the ones that made the complaint: a pool where one interpret
 * owns half the songs.
 */
class ShuffleTest {

    /** A pool of [n] songs where the first [big] of them are all one interpret. */
    private fun pool(n: Int, big: Int): List<String> =
        List(n) { if (it < big) "Big" else "Small ${it % 30}" }

    private fun spread(items: List<String>, seed: Int = 1, avoid: String? = null) =
        Shuffle.spread(items, avoid = avoid, random = Random(seed)) { it }

    /** How often the same interpret follows itself. */
    private fun repeats(order: List<String>) =
        order.indices.count { it > 0 && order[it] == order[it - 1] }

    /** The longest stretch of one interpret in a row. */
    private fun longestRun(order: List<String>): Int {
        var best = 1
        var run = 1
        for (i in 1 until order.size) {
            run = if (order[i] == order[i - 1]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    @Test
    fun `the interpret that owns half the pool never follows itself`() {
        for (seed in 1..20) {
            val order = spread(pool(300, 150), seed)
            assertEquals("seed $seed", 0, repeats(order))
            assertEquals("seed $seed", 1, longestRun(order))
        }
    }

    @Test
    fun `an ordinary library comes out clean too`() {
        val library = buildList {
            repeat(60) { artist ->
                val songs = if (artist < 5) 20 else if (artist < 20) 6 else 2
                repeat(songs) { add("Artist $artist") }
            }
        }
        for (seed in 1..20) assertEquals("seed $seed", 0, repeats(spread(library, seed)))
    }

    @Test
    fun `past half the pool it lands on the minimum instead of giving up`() {
        // 200 of 300 by one name: at least 100 of them must follow themselves,
        // whatever the order. What must not happen is runs of thirty.
        for (seed in 1..10) {
            val order = spread(pool(300, 200), seed)
            assertTrue("seed $seed: ${repeats(order)}", repeats(order) <= 105)
            assertTrue("seed $seed: run ${longestRun(order)}", longestRun(order) <= 2)
        }
    }

    @Test
    fun `nothing is lost, duplicated or invented`() {
        val items = List(300) { "track $it" }
        val order = spread(items)
        assertEquals(items.size, order.size)
        assertEquals(items.toSet(), order.toSet())
    }

    @Test
    fun `a single interpret is still shuffled, not left in place`() {
        // An album has one name on every row, so there is nothing to spread - but
        // "Zufällig" on it still has to change the order.
        val album = List(20) { "track $it" }
        val order = Shuffle.spread(album, random = Random(7)) { "One" }
        assertEquals(album.toSet(), order.toSet())
        assertTrue(order != album)
    }

    @Test
    fun `the list does not open with the interpret it was told to avoid`() {
        // What the caller does with this: the tapped track stays in front of the
        // list, so the deal behind it must not start with that same name.
        for (seed in 1..50) {
            val order = spread(pool(300, 150), seed, avoid = "Big")
            assertTrue("seed $seed opened with ${order[0]}", order[0] != "Big")
        }
    }

    @Test
    fun `the same pool deals a different order every time`() {
        val items = List(60) { "track $it" }
        val openers = (1..30).map { seed ->
            Shuffle.spread(items, random = Random(seed)) { "Artist ${it.substringAfter(' ').toInt() % 10}" }[0]
        }
        assertTrue("only ${openers.toSet().size} distinct openers", openers.toSet().size > 15)
    }

    @Test
    fun `lists too short to spread do not throw`() {
        assertEquals(emptyList<String>(), spread(emptyList()))
        assertEquals(listOf("one"), spread(listOf("one")))
        assertEquals(setOf("one", "two"), spread(listOf("one", "two")).toSet())
    }

    @Test
    fun `songs without an interpret are one group, not one each`() {
        // Empty tags must not crash, and must not each count as their own name.
        val order = Shuffle.spread(List(50) { "track $it" }, random = Random(3)) { "" }
        assertEquals(50, order.size)
    }
}
