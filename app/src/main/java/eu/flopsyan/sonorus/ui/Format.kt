package eu.flopsyan.sonorus.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The German output rules, mirrored from `public/js/format.js`.
 *
 * The one rule worth stating: **durations always round down, never up.** An
 * average that rounds up claims time that was not listened to, and rounding
 * minutes is what once turned 1:59:45 into "1 Std. 60 Min.". Under a minute
 * they count seconds instead of claiming zero.
 */
object Fmt {

    private val german = Locale.GERMANY
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance(german)

    /** m:ss, or h:mm:ss once a track passes an hour. */
    fun duration(seconds: Double): String {
        val total = maxOf(0L, seconds.roundToLong())
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Long form for headers: "3 Std. 14 Min.". */
    fun durationLong(seconds: Double): String {
        val total = maxOf(0L, seconds.roundToLong())
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h Std. $m Min."
            h > 0 -> "$h Std."
            m > 0 -> "$m Min."
            else -> "$total Sek."
        }
    }

    /** Compact playtime for the front-panel readout: "1:45 Std.", "312:04 Std.". */
    fun durationRack(seconds: Double): String {
        val total = maxOf(0L, seconds.roundToLong())
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h == 0L && m == 0L -> "$total Sek."
            h == 0L -> "$m Min."
            else -> "%d:%02d Std.".format(h, m)
        }
    }

    fun number(value: Number): String = numbers.format(value)

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var size = value / 1024.0
        var i = 0
        while (size >= 1024 && i < units.size - 1) {
            size /= 1024
            i++
        }
        return if (size < 10) "%.1f %s".format(german, size, units[i])
        else "%.0f %s".format(german, floor(size), units[i])
    }

    /** "12 Songs" / "1 Song" - counts in headers read badly without this. */
    fun plural(count: Int, one: String, many: String): String =
        "${number(count)} ${if (count == 1) one else many}"

    private val months = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )

    /**
     * A release date is stored as exactly as it is known: `2015`, `2015-05` or
     * `2015-05-17` - the length of the string *is* the precision. Built from
     * the parts rather than through a Date, which would shift the day by one in
     * any timezone west of UTC.
     */
    fun releaseDate(value: String): String {
        val parts = value.split("-")
        val year = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return ""
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return year
        val name = months.getOrNull(month - 1) ?: return year
        val day = parts.getOrNull(2)?.toIntOrNull() ?: return "$name $year"
        return "$day. $name $year"
    }

    /** The same date the way the edit dialog takes it: 17.05.2015, 05.2015, 2015. */
    fun releaseDateInput(value: String): String {
        val parts = value.split("-")
        val year = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return ""
        val month = parts.getOrNull(1) ?: return year
        val day = parts.getOrNull(2) ?: return "$month.$year"
        return "$day.$month.$year"
    }

    /** The year is all a grid, a card or a list has room for. */
    fun year(releaseDate: String, year: Int?): String =
        releaseDate.take(4).takeIf { it.length == 4 } ?: year?.toString() ?: ""

    private val dayFormat = DateTimeFormatter.ofPattern("d. MMMM yyyy", german)
    private val stampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", german)

    /** SQLite writes "YYYY-MM-DD HH:MM:SS" in UTC. */
    private fun parse(value: String): Instant? = runCatching {
        Instant.parse(value.replace(" ", "T") + if (value.endsWith("Z")) "" else "Z")
    }.getOrNull()

    fun date(value: String?): String {
        val instant = value?.takeIf { it.isNotEmpty() }?.let(::parse) ?: return ""
        return dayFormat.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun dateTime(value: String?): String {
        val instant = value?.takeIf { it.isNotEmpty() }?.let(::parse) ?: return ""
        return stampFormat.format(instant.atZone(ZoneId.systemDefault()))
    }
}
