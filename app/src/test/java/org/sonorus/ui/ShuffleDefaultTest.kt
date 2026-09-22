package org.sonorus.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table Florian dictated on 2026-09-22, pinned.
 *
 * It is one boolean per page and nothing computes it, so what this guards is not
 * an algorithm but the answer itself: a page silently changing sides is exactly
 * the kind of thing nobody notices until the wrong list plays in the wrong
 * order. The last test is the one that matters most - a page added later has to
 * be given a side deliberately rather than inheriting whichever one is first.
 */
class ShuffleDefaultTest {

    @Test
    fun `a selection somebody made is shuffled`() {
        assertTrue(ShuffleDefault.PLAYLIST.armed)
        assertTrue(ShuffleDefault.STARS.armed)
        assertTrue(ShuffleDefault.ARTIST_STARS.armed)
        assertTrue(ShuffleDefault.GENRE.armed)
    }

    @Test
    fun `a running order somebody else decided is not`() {
        assertFalse(ShuffleDefault.ALBUM.armed)
        assertFalse(ShuffleDefault.ARTIST.armed)
        assertFalse(ShuffleDefault.SINGLES.armed)
    }

    @Test
    fun `every page has a side`() {
        assertTrue(ShuffleDefault.entries.size == 7)
    }
}
