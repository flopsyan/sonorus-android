package org.sonorus.data

import org.sonorus.data.model.Track
import kotlin.random.Random

/**
 * The shuffle, as the web app deals it.
 *
 * This is the Kotlin side of `public/js/shuffle.js` in the server repo. The two
 * cannot share code, so they share the algorithm and this note; a change to one
 * belongs in the other.
 *
 * ## Why a correct shuffle needed fixing at all
 *
 * Nothing was broken in the maths. `List.shuffled()` is Fisher-Yates and gives a
 * uniform permutation, and the server's `ORDER BY RANDOM()` is a uniform sample.
 * What they are not is what "shuffled" is expected to *sound* like:
 *
 * **A fair permutation clumps.** Songs by the same interpret land next to each
 * other far more often than anyone expects - the same effect that makes people
 * call true random "not random". On a pool of 300 songs, half of them by one
 * interpret, a plain shuffle leaves about 76 places where that name follows
 * itself, in runs of a dozen. Nothing is wrong with it; it simply sounds like
 * the shuffle is stuck on one artist.
 *
 * The draw stays per song, so a random run keeps sounding like the library
 * actually is. Only the order changes, and the idea is the one Spotify described
 * in 2014: do not order the songs, order the *interprets*, each laid out evenly
 * across the whole list.
 *
 * For a list with one interpret in it - an album, one artist's page - every song
 * is in the same group and this is a plain shuffle again. It costs nothing where
 * there is nothing to spread.
 */
object Shuffle {

    /**
     * How far [separate] looks for a song to trade places with.
     *
     * A repeat that cannot be settled inside fifty songs is one where that
     * interpret owns most of the list, and then no order avoids it - so the
     * search stops rather than walking the whole tail for nothing.
     */
    private const val REACH = 50

    /** Two spellings of the same name are the same interpret for the spread. */
    private fun normalise(key: String?) = key.orEmpty().trim().lowercase()

    /** What interpret a track belongs to. */
    fun artistOf(track: Track): String = track.artist

    /**
     * A shuffle that spreads each interpret over the whole list instead of only
     * permuting it.
     *
     * [keyOf] says what an item's interpret is - the items may be tracks or
     * positions into a queue, which is why this never reaches into them.
     *
     * [avoid] is the interpret the list must not *open* with. The one repeat the
     * spread cannot see is the song already in front of the list: the track that
     * was tapped stays first, and its own name coming straight after it is
     * exactly what this is here to prevent.
     */
    fun <T> spread(
        items: List<T>,
        avoid: String? = null,
        random: Random = Random,
        keyOf: (T) -> String,
    ): List<T> {
        if (items.size < 3) return items.shuffled(random)

        val groups = LinkedHashMap<String, MutableList<T>>()
        for (item in items) groups.getOrPut(normalise(keyOf(item))) { mutableListOf() }.add(item)

        val order = dealIntoSlots(groups.values.map { it.shuffled(random) }, items.size, random)
        val keys = MutableList(order.size) { normalise(keyOf(order[it])) }
        separate(order, keys)

        val head = normalise(avoid)
        if (head.isNotEmpty() && keys[0] == head) {
            val other = keys.indexOfFirst { it != head }
            if (other > 0) order.swap(0, other)
        }
        return order
    }

    /**
     * Lays every interpret out over the whole list, biggest first.
     *
     * Biggest first is the whole trick. Giving each interpret an even spacing but
     * an *independent* random starting point spreads each of them correctly and
     * still lets two of them land on the same spot: with one name owning half the
     * list, about a third of its slots collide and the repeats only fall from 76
     * to 54.
     *
     * Handing out the slots settles that by construction. The interpret with the
     * most songs picks while every slot is still free, so it takes every second
     * one and can no longer follow itself at all; everyone else fills in around
     * it. A song whose slot is taken moves to the next free one along, which is
     * close enough that the even spread survives. The starting point stays
     * random, so the same library deals a different order every time.
     */
    private fun <T> dealIntoSlots(groups: List<List<T>>, total: Int, random: Random): MutableList<T> {
        val slots = arrayOfNulls<Any?>(total)
        // Shuffled first, then sorted by size: `sortedByDescending` is stable, so
        // interprets who own the same number of songs still come in a random
        // order rather than in whatever order the library listed them.
        val ordered = groups.shuffled(random).sortedByDescending { it.size }

        for (group in ordered) {
            val step = total.toDouble() / group.size
            val phase = random.nextDouble() * step
            for (i in group.indices) {
                var at = ((phase + i * step).toInt()) % total
                while (slots[at] != null) at = (at + 1) % total
                slots[at] = group[i]
            }
        }
        @Suppress("UNCHECKED_CAST")
        return MutableList(total) { slots[it] as T }
    }

    /**
     * How many of the two joins around position [i] are a repeat. The measure
     * [separate] works on: a swap is worth making when it leaves fewer of these
     * behind than it found.
     */
    private fun joinCost(keys: List<String>, i: Int): Int {
        var cost = 0
        if (i > 0 && keys[i] == keys[i - 1]) cost++
        if (i < keys.size - 1 && keys[i] == keys[i + 1]) cost++
        return cost
    }

    /**
     * Trades the last neighbouring repeats away, one pass, in place.
     *
     * The slot deal leaves few of them - they are what the "next free slot along"
     * rule produces when two interprets want the same place. A song sitting next
     * to its own name trades places with the nearest later song that is happier
     * there. Only swaps that really lower the count are kept, so the pass can
     * never make the list worse.
     */
    private fun <T> separate(order: MutableList<T>, keys: MutableList<String>) {
        for (i in 1 until order.size) {
            if (keys[i] != keys[i - 1]) continue
            val until = minOf(order.size, i + 1 + REACH)
            // From i + 2, so the two positions never share a neighbour and the
            // cost of each can be read on its own.
            for (j in i + 2 until until) {
                val before = joinCost(keys, i) + joinCost(keys, j)
                keys.swap(i, j)
                if (joinCost(keys, i) + joinCost(keys, j) < before) {
                    order.swap(i, j)
                    break
                }
                keys.swap(i, j)
            }
        }
    }

    private fun <T> MutableList<T>.swap(a: Int, b: Int) {
        val held = this[a]
        this[a] = this[b]
        this[b] = held
    }
}
