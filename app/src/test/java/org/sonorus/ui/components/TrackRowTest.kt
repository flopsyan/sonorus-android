package org.sonorus.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number column of a track list.
 *
 * Florian's report: "bei vierstelligen Zahlen wird es am Handy abgeschnitten.
 * Bei Song 1340 steht dann halt die 0 eine Zeile weiter unten." The column was
 * a flat 26 dp, which holds three monospace digits at 12 sp and not four.
 */
class TrackRowTest {

    @Test
    fun `short lists keep the narrow column`() {
        // An album or an artist page - nothing should look different there.
        assertEquals(26.dp, indexColumnWidth(12))
        assertEquals(26.dp, indexColumnWidth(999))
    }

    @Test
    fun `four digits get their own room`() {
        assertEquals(34.dp, indexColumnWidth(1340))
        assertEquals(34.dp, indexColumnWidth(9999))
    }

    @Test
    fun `five digits are the last step`() {
        assertEquals(42.dp, indexColumnWidth(10000))
        assertEquals(42.dp, indexColumnWidth(99999))
    }

    @Test
    fun `a library past five digits is not made wider still`() {
        // 99999 songs is far past what this is built for, and the row has other
        // things to show. The number is clipped there rather than broken.
        assertEquals(42.dp, indexColumnWidth(1_000_000))
    }

    @Test
    fun `an empty list asks for nothing unusual`() {
        assertEquals(26.dp, indexColumnWidth(0))
        assertTrue(indexColumnWidth(1) == INDEX_WIDTH_SHORT)
    }
}
