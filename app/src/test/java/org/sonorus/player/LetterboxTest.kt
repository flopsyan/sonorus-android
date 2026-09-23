package org.sonorus.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LetterboxTest {

    private val w = 192
    private val h = 108

    private fun grey(v: Int) = (0xff shl 24) or (v shl 16) or (v shl 8) or v

    /** A frame that is black outside the given picture rectangle. */
    private fun frame(top: Int, bottom: Int, left: Int = 0, right: Int = w - 1, fill: (Int, Int) -> Int = { _, _ -> 128 }) =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (y in top..bottom && x in left..right) grey(fill(x, y)) else grey(0)
        }

    @Test
    fun `bars above and below are found, one row kept for the scaled edge`() {
        val share = Letterbox.measure(frame(14, 93), w, h)!!
        assertEquals(1f, share.width, 0f)
        assertEquals((h - 2 * 13f) / h, share.height, 0.0001f)
    }

    @Test
    fun `a frame without bars is all picture`() {
        assertEquals(PictureShare(1f, 1f), Letterbox.measure(frame(0, h - 1), w, h))
    }

    @Test
    fun `a black or nearly black frame says nothing`() {
        assertNull(Letterbox.measure(frame(0, h - 1) { _, _ -> 20 }, w, h))
        assertNull(Letterbox.measure(frame(50, 57, 90, 101), w, h))
    }

    @Test
    fun `a dark top of the picture does not count as a bar`() {
        val share = Letterbox.measure(frame(14, 93) { _, y -> if (y < 40) 10 else 128 }, w, h)!!
        assertEquals((h - 2 * 13f) / h, share.height, 0.0001f)
    }

    @Test
    fun `nothing until three frames, then the largest picture seen`() {
        val box = Letterbox()
        box.add(frame(20, 87), w, h)
        box.add(frame(0, h - 1) { _, _ -> 5 }, w, h)
        box.add(frame(20, 87), w, h)
        assertNull(box.share)
        box.add(frame(14, 93), w, h)
        assertEquals((h - 2 * 13f) / h, box.share!!.height, 0.0001f)
        box.add(frame(20, 87), w, h)
        assertEquals((h - 2 * 13f) / h, box.share!!.height, 0.0001f)
    }

    @Test
    fun `a sliver of black is not worth a zoom`() {
        val box = Letterbox()
        repeat(3) { box.add(frame(2, h - 3), w, h) }
        assertNotNull(box.share)
        assertEquals(PictureShare(1f, 1f), box.share)
    }

    @Test
    fun `zoom stops where the picture meets the screen`() {
        // A scope film in a 16:9 file on a 20:9 phone: the sides fill, 40 px of black stay.
        val zoom = Letterbox.zoom(2400f, 1080f, 16f / 9f, PictureShare(1f, 800f / 1080f))
        assertEquals(1.25f, zoom, 0.0001f)
        // Plain 16:9 and 4:3 inside 16:9 already touch top and bottom.
        assertEquals(1f, Letterbox.zoom(2400f, 1080f, 16f / 9f, PictureShare(1f, 1f)), 0f)
        assertEquals(1f, Letterbox.zoom(2400f, 1080f, 16f / 9f, PictureShare(0.75f, 1f)), 0f)
        // A picture boxed in on all four sides grows until its height fits.
        assertEquals(1.25f, Letterbox.zoom(2400f, 1080f, 16f / 9f, PictureShare(0.6f, 0.8f)), 0.0001f)
    }
}
