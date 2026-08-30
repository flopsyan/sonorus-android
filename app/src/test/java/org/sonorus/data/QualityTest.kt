package org.sonorus.data

import org.sonorus.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what a song is really served as.
 *
 * It exists twice on purpose - here and in `willTranscode` in the server's
 * `src/lib/transcode.js` - because the app has the codec and the lossless flag
 * of every track already, and asking the server what it would do with each one
 * would be a request per row of every list. The price of that is that the two
 * can drift, and a client that draws a format the speaker is not playing is
 * worse than one that draws nothing. So the rule is pinned here: **lossless
 * shrinks, lossy never does.** Change one side and this fails.
 */
class QualityTest {

    private fun track(bitrate: Int?, lossless: Boolean = false, codec: String = "") =
        Track(id = 1, bitrate = bitrate, lossless = lossless, codec = codec)

    @Test
    fun `the original is always the original`() {
        assertEquals(Quality.ORIGINAL, Quality.served(track(900_000, lossless = true), Quality.ORIGINAL))
        assertEquals(Quality.ORIGINAL, Quality.served(track(320_000), Quality.ORIGINAL))
    }

    @Test
    fun `lossless always shrinks, whatever it weighs`() {
        assertEquals(Quality.OPUS128, Quality.served(track(900_000, lossless = true), Quality.OPUS128))
        // A quiet FLAC can compress below the target and is still lossless: the
        // flag decides, not the number, or the smallest files in the library
        // would be the ones served whole.
        assertEquals(Quality.OPUS128, Quality.served(track(90_000, lossless = true), Quality.OPUS128))
    }

    @Test
    fun `a lossy file is never re-encoded, however large it is`() {
        // The case Florian named on 2026-08-30: podcast episodes at 160-320 kbps
        // were being turned into Opus 128 on the phone. Smaller on paper, and a
        // second generation of lossy loss on a file that was small enough.
        assertEquals(Quality.ORIGINAL, Quality.served(track(320_000), Quality.OPUS128))
        assertEquals(Quality.ORIGINAL, Quality.served(track(192_000), Quality.OPUS128))
        assertEquals(Quality.ORIGINAL, Quality.served(track(128_000), Quality.OPUS128))
        assertEquals(Quality.ORIGINAL, Quality.served(track(64_000), Quality.OPUS128))
    }

    @Test
    fun `the bitrate does not enter into it any more`() {
        // Both of these were the old rule's two sides of the 140_800 line. The
        // flag decides now, and the number decides nothing at all.
        assertEquals(Quality.ORIGINAL, Quality.served(track(140_800), Quality.OPUS128))
        assertEquals(Quality.ORIGINAL, Quality.served(track(140_801), Quality.OPUS128))
        assertEquals(Quality.OPUS128, Quality.served(track(140_800, lossless = true), Quality.OPUS128))
    }

    @Test
    fun `a file whose bitrate is unknown keeps its own format`() {
        // A missing bitrate used to be the awkward case. It is not a case at
        // all now - a lossy file stays whatever it says about itself.
        assertEquals(Quality.ORIGINAL, Quality.served(track(null), Quality.OPUS128))
        assertEquals(Quality.OPUS128, Quality.served(track(null, lossless = true), Quality.OPUS128))
    }

    @Test
    fun `the wire names round-trip, and anything unknown is the original`() {
        assertEquals(Quality.OPUS128, Quality.of("opus128"))
        assertEquals(Quality.ORIGINAL, Quality.of("original"))
        assertEquals(Quality.ORIGINAL, Quality.of(null))
        assertEquals(Quality.ORIGINAL, Quality.of("opus192"))
    }

    @Test
    fun `the label is what the format is called, not what the tag reader calls it`() {
        assertEquals("MP3", formatLabel(track(320_000, codec = "MPEG 1 Layer 3"), Quality.ORIGINAL))
        assertEquals("AAC", formatLabel(track(256_000, codec = "MPEG-4/AAC"), Quality.ORIGINAL))
        assertEquals("FLAC", formatLabel(track(900_000, lossless = true, codec = "FLAC"), Quality.ORIGINAL))
        assertEquals("WAV", formatLabel(track(1_411_200, lossless = true, codec = "PCM"), Quality.ORIGINAL))
        assertEquals("Opus 128", formatLabel(track(900_000, lossless = true, codec = "FLAC"), Quality.OPUS128))
        // The label follows what is served, so a lossy file keeps its own name
        // even while the phone is set to the smaller quality.
        assertEquals("MP3", formatLabel(track(320_000, codec = "MPEG 1 Layer 3"), Quality.served(track(320_000), Quality.OPUS128)))
        // A codec nobody thought of keeps its own name rather than disappearing.
        assertEquals("Speex", formatLabel(track(64_000, codec = "Speex"), Quality.ORIGINAL))
        assertEquals("Original", formatLabel(track(64_000, codec = ""), Quality.ORIGINAL))
    }
}
