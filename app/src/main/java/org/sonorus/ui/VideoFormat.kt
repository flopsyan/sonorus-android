package org.sonorus.ui

import org.sonorus.data.model.AudioInfo
import org.sonorus.data.model.SubtitleInfo
import java.util.Locale

/** Words for the video pages; the same ones `public/js/video-format.js` uses. */
object VideoFmt {

    // ffprobe hands out ISO 639-2, subtitle files ISO 639-1; Locale names both,
    // but "ger" only through its short form.
    private val LONG_TO_SHORT = mapOf(
        "ger" to "de", "deu" to "de", "eng" to "en", "fre" to "fr", "fra" to "fr", "spa" to "es",
        "ita" to "it", "jpn" to "ja", "hin" to "hi", "dut" to "nl", "nld" to "nl", "chi" to "zh",
        "zho" to "zh", "cze" to "cs", "ces" to "cs", "gre" to "el", "ell" to "el", "rum" to "ro",
        "ron" to "ro", "may" to "ms", "msa" to "ms", "nob" to "nb",
    )

    private val GERMAN = Locale.GERMAN

    fun langName(code: String?): String {
        val raw = code.orEmpty().lowercase()
        if (raw.isEmpty() || raw == "und" || raw == "zxx" || raw == "mul") return ""
        val short = LONG_TO_SHORT[raw] ?: raw
        val name = Locale.forLanguageTag(short).getDisplayLanguage(GERMAN)
        return if (name.isNotEmpty() && name.lowercase() != short) name else raw.uppercase()
    }

    private val CODECS = mapOf(
        "aac" to "AAC", "ac3" to "Dolby Digital", "eac3" to "Dolby Digital+", "dts" to "DTS",
        "truehd" to "TrueHD", "mp3" to "MP3", "opus" to "Opus", "flac" to "FLAC", "vorbis" to "Vorbis",
    )
    private val CHANNELS = mapOf(1 to "Mono", 2 to "Stereo", 6 to "5.1", 8 to "7.1")

    // Release groups write their domain into every track title; that is not a name.
    private val JUNK = Regex("""\.[a-z]{2,6}\b|www|https?:""", RegexOption.IGNORE_CASE)
    private fun usefulTitle(t: String) = t.isNotEmpty() && t.length < 40 && !JUNK.containsMatchIn(t)

    fun audioLabel(a: AudioInfo): Pair<String, String> {
        val main = listOfNotNull(langName(a.lang).ifEmpty { "Unbekannt" }, a.title.takeIf(::usefulTitle))
        val tech = listOf(
            CODECS[a.codec] ?: a.codec.uppercase(),
            CHANNELS[a.channels] ?: if (a.channels > 0) "${a.channels} Kanäle" else "",
        ).filter { it.isNotEmpty() }
        return main.joinToString(" · ") to tech.joinToString(" ")
    }

    fun subtitleLabel(s: SubtitleInfo): String {
        val parts = mutableListOf(langName(s.lang).ifEmpty { "Unbekannt" })
        if (s.forced) parts += "erzwungen"
        if (s.sdh) parts += "für Hörgeschädigte"
        if (usefulTitle(s.title) && !s.forced) parts += s.title
        return parts.joinToString(" · ")
    }

    /** "FSK 16" for a German rating, the plain rating for anything else. */
    fun certLabel(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val parts = value.split(":")
        if (parts.size < 2) return value
        return if (parts[0] == "DE") "FSK ${parts[1]}" else parts[1]
    }

    /** "S2 · E5", "S2 · E5-6", "Special". */
    fun episodeCode(season: Int?, episode: Int?, episodeEnd: Int?): String = when {
        season == 0 -> if (episode != null) "Special $episode" else "Special"
        episode == null -> "S${season ?: ""}"
        else -> "S$season · E$episode${if (episodeEnd != null) "-$episodeEnd" else ""}"
    }

    fun resolutionLabel(height: Int?, width: Int?): String {
        val h = height ?: 0
        val w = width ?: 0
        return when {
            h <= 0 -> ""
            h >= 1500 || w >= 3000 -> "4K"
            h >= 1000 || w >= 1900 -> "1080p"
            h >= 700 || w >= 1260 -> "720p"
            else -> "${h}p"
        }
    }

    /** "1 Std. 38 Min.", the web's `durationLong`. */
    fun durationLong(seconds: Double): String {
        val total = (seconds / 60).toInt().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> "$h Std. $m Min."
            h > 0 -> "$h Std."
            else -> "$m Min."
        }
    }

    /** "1:23:45" or "23:45" for the player's clock. */
    fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
