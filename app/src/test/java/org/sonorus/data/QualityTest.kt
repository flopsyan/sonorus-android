package org.sonorus.data

import org.sonorus.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what a song is really served as.
 *
 * It exists twice on purpose - here and in `willTranscode` in the server's
 * `src/lib/transcode.js` - because the app has the codec, the bitrate and the
 * lossless flag of every track already, and asking the server what it would do
 * with each one would be a request per row of every list. The price of that is
 * that the two can drift, and a client that draws a format the speaker is not
 * playing is worse than one that draws nothing. So the numbers are pinned here:
 * **128 kbps target, 1.1 margin.** Change one side and this fails.
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
    fun `a lossy file well above the target shrinks`() {
        assertEquals(Quality.OPUS128, Quality.served(track(320_000), Quality.OPUS128))
        assertEquals(Quality.OPUS128, Quality.served(track(192_000), Quality.OPUS128))
    }

    @Test
    fun `a 128k MP3 is handed over untouched`() {
        // The case Florian named: re-encoding this saves nothing worth having
        // and costs a generation of loss.
        assertEquals(Quality.ORIGINAL, Quality.served(track(128_000), Quality.OPUS128))
    }

    @Test
    fun `anything below the target is left alone`() {
        assertEquals(Quality.ORIGINAL, Quality.served(track(96_000), Quality.OPUS128))
        assertEquals(Quality.ORIGINAL, Quality.served(track(64_000), Quality.OPUS128))
    }

    @Test
    fun `the margin sits where the server puts it`() {
        // 128k times 1.1 is 140_800: just under stays, just over goes.
        assertEquals(Quality.ORIGINAL, Quality.served(track(140_800), Quality.OPUS128))
        assertEquals(Quality.OPUS128, Quality.served(track(140_801), Quality.OPUS128))
    }

    @Test
    fun `a file whose bitrate is unknown keeps its own format`() {
        // Guessing here would cost quality for nothing, so a null reads as "do
        // not touch" rather than as "low".
        assertEquals(Quality.ORIGINAL, Quality.served(track(null), Quality.OPUS128))
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
        // A codec nobody thought of keeps its own name rather than disappearing.
        assertEquals("Speex", formatLabel(track(64_000, codec = "Speex"), Quality.ORIGINAL))
        assertEquals("Original", formatLabel(track(64_000, codec = ""), Quality.ORIGINAL))
    }
}
