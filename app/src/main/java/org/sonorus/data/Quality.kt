package org.sonorus.data

import org.sonorus.data.model.Track

/**
 * How large a song is allowed to arrive.
 *
 * Two answers and no ladder in between: the file as it lies in the music folder,
 * or a copy small enough for a mobile connection. Every step between the two is
 * a setting nobody ever moves, so there is none.
 *
 * The choice belongs to **this phone** and not to the account - see [Settings].
 * A phone on a train and a browser on the LAN are the same login and want
 * opposite things, and the one place the setting has to be readable is exactly
 * the one where the server cannot be asked.
 *
 * Streaming and downloading are set apart, because they are different questions.
 * Streaming spends data every time a song plays; a download spends it once and
 * then costs storage forever. Somebody may well want the small copy on the road
 * and the original on the shelf.
 */
enum class Quality(val wire: String, val label: String, val short: String) {
    /** Whatever lies in the music folder. Usually FLAC, but not always. */
    ORIGINAL("original", "Original", "Original"),
    OPUS128("opus128", "Opus 128 kbps", "Opus 128"),
    ;

    companion object {
        fun of(wire: String?): Quality =
            entries.firstOrNull { it.wire == wire } ?: ORIGINAL

        /**
         * The bitrate a profile targets, in bits per second. Only the small one
         * has a target at all - the original is whatever the file is.
         */
        private const val OPUS_BITS = 128_000

        /**
         * The same margin the server keeps, and it has to stay the same number:
         * both sides work out what is really served, and a client that disagrees
         * would draw a format the speaker is not playing.
         */
        private const val UPWARD_MARGIN = 1.1

        /**
         * What [track] is **really** served as when [wanted] is asked for.
         *
         * This mirrors `willTranscode` in the server's `src/lib/transcode.js`,
         * and deliberately so: the app has the codec, the bitrate and the
         * lossless flag of every track already, so the format under the
         * transport can be right without a request per song. The server says the
         * same thing in `X-Sonorus-Quality`, which is what a download reads -
         * there the answer is in hand anyway.
         *
         * Lossless always shrinks. Lossy only when it is clearly above the
         * target: a 128k MP3 turned into a 128k Opus is smaller by nothing worth
         * having and worse by a generation of loss.
         */
        fun served(track: Track, wanted: Quality): Quality {
            if (wanted == ORIGINAL) return ORIGINAL
            if (track.lossless) return wanted
            val source = bitrateOf(track)
            if (source <= 0) return ORIGINAL
            return if (source > OPUS_BITS * UPWARD_MARGIN) wanted else ORIGINAL
        }

        /**
         * A track's bitrate, falling back to what its length and its codec say
         * nothing about - the row carries one for almost everything, and a null
         * must not read as "low" or every such file would stay on the original.
         */
        private fun bitrateOf(track: Track): Int = track.bitrate ?: 0
    }
}

/**
 * The format a track is really heard in, in the handful of characters the
 * indicator under the transport has room for.
 *
 * The codec strings come from `music-metadata` on the server and are written for
 * a parser rather than for a phone screen - "MPEG 1 Layer 3", "MPEG-4/AAC". They
 * are shortened here to the name the format is actually called by.
 */
fun formatLabel(track: Track, served: Quality): String =
    if (served != Quality.ORIGINAL) served.short else shortCodec(track.codec)

fun shortCodec(codec: String): String {
    val raw = codec.trim()
    if (raw.isEmpty()) return "Original"
    val upper = raw.uppercase()
    return when {
        "LAYER 3" in upper || upper.startsWith("MP3") -> "MP3"
        "LAYER 2" in upper -> "MP2"
        "ALAC" in upper -> "ALAC"
        "AAC" in upper -> "AAC"
        "OPUS" in upper -> "Opus"
        "VORBIS" in upper -> "Vorbis"
        "FLAC" in upper -> "FLAC"
        upper.startsWith("PCM") -> "WAV"
        "WMA" in upper -> "WMA"
        "MONKEY" in upper || upper.startsWith("APE") -> "APE"
        "WAVPACK" in upper -> "WavPack"
        // Anything unexpected keeps its own name rather than being dropped - a
        // format nobody thought of is still worth saying out loud.
        else -> raw.substringBefore('/').substringBefore(' ')
    }
}
