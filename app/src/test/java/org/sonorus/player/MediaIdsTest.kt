package org.sonorus.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The addresses Android Auto sends back.
 *
 * The car returns nothing but the id of the row that was tapped, so every
 * mistake here is silent: the wrong song plays, or the right song plays alone
 * instead of the album it sits in. These are the cases that decide it.
 */
class MediaIdsTest {

    @Test
    fun `a leaf carries the list it was listed in`() {
        val id = MediaIds.track(MediaIds.album(7), 312)
        assertEquals("albums/7|312", id)
        assertEquals("albums/7" to 312, MediaIds.parse(id))
    }

    @Test
    fun `a node is not a leaf`() {
        assertNull(MediaIds.parse(MediaIds.ROOT))
        assertNull(MediaIds.parse(MediaIds.album(7)))
        assertNull(MediaIds.parse(MediaIds.stars(0)))
    }

    @Test
    fun `a leaf without a number is not a leaf either`() {
        assertNull(MediaIds.parse("albums/7|"))
        assertNull(MediaIds.parse("albums/7|abc"))
        assertNull(MediaIds.parse("|12"))
    }

    @Test
    fun `every list a song can be listed in comes back whole`() {
        val lists = listOf(
            MediaIds.SHUFFLE,
            MediaIds.RECENT,
            MediaIds.TRACKS,
            MediaIds.SEARCH,
            MediaIds.artist(3),
            MediaIds.album(7),
            MediaIds.genre(12),
            MediaIds.playlist(4),
            MediaIds.stars(0),
            MediaIds.stars(5),
        )
        for (list in lists) {
            assertEquals(list to 99, MediaIds.parse(MediaIds.track(list, 99)))
        }
    }

    @Test
    fun `a node splits into its section and its number`() {
        assertEquals(MediaIds.ALBUMS, MediaIds.sectionOf(MediaIds.album(7)))
        assertEquals(7, MediaIds.numberOf(MediaIds.album(7)))
        assertEquals(MediaIds.FOLDERS, MediaIds.sectionOf(MediaIds.folder(2)))
        // "Nicht bewertet" is rating 0, and 0 is a number like any other.
        assertEquals(MediaIds.STARS, MediaIds.sectionOf(MediaIds.stars(0)))
        assertEquals(0, MediaIds.numberOf(MediaIds.stars(0)))
    }

    @Test
    fun `a node without a number is its own section`() {
        assertEquals(MediaIds.TRACKS, MediaIds.sectionOf(MediaIds.TRACKS))
        assertNull(MediaIds.numberOf(MediaIds.TRACKS))
        assertEquals(MediaIds.ROOT, MediaIds.sectionOf(MediaIds.ROOT))
    }
}
