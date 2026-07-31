package eu.flopsyan.sonorus.ui

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * How the client walks the statistics periods.
 *
 * The server only ever answers for *one* period; stepping to its neighbour and
 * knowing what a period is made of happens here. This mirrors `RANGES` in
 * `public/js/views.js`, including the one rule that matters most:
 *
 * **[slots] is what fills the quiet hours and days.** The query only returns
 * slots that actually have plays, so without this two bars a month apart would
 * stand side by side as if they were neighbours.
 *
 * Keys are built from their parts rather than parsed as a date string, because
 * `2026-07-25` read as an instant is UTC midnight and lands on the 24th west of
 * Greenwich.
 */
enum class Range(val key: String, val label: String) {
    DAY("day", "Tage"),
    WEEK("week", "Wochen"),
    MONTH("month", "Monate"),
    YEAR("year", "Jahre"),
    ALL("all", "Gesamt");

    companion object {
        fun of(key: String): Range = entries.firstOrNull { it.key == key } ?: DAY
    }
}

private val german = Locale.GERMANY
private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
private val MONTHS = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)

/** `YYYY-MM-DD` or `YYYY-MM` into a real date, built from the parts. */
private fun keyDate(key: String): LocalDate {
    val p = key.split("-")
    val year = p.getOrNull(0)?.toIntOrNull() ?: return LocalDate.now()
    val month = p.getOrNull(1)?.toIntOrNull() ?: 1
    val day = p.getOrNull(2)?.toIntOrNull() ?: 1
    return runCatching { LocalDate.of(year, month, day) }.getOrElse { LocalDate.now() }
}

private fun iso(d: LocalDate) = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
private fun dayLabel(key: String): String {
    val d = keyDate(key)
    return "${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]} ${d.year}"
}
private fun monthTitle(key: String): String {
    val p = key.split("-")
    val month = p.getOrNull(1)?.toIntOrNull() ?: return key
    return "${MONTHS[month - 1]} ${p[0]}"
}

object Periods {

    /** The heading over the selected period. */
    fun title(range: Range, key: String): String = when (range) {
        Range.DAY -> {
            val d = keyDate(key)
            val weekday = WEEKDAYS[d.dayOfWeek.value - 1]
            "$weekday, ${dayLabel(key)}"
        }
        Range.WEEK -> {
            val start = keyDate(key)
            val end = start.plusDays(6)
            val week = start.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
            "KW $week · ${start.dayOfMonth}. ${MONTHS[start.monthValue - 1].take(3)} - ${dayLabel(iso(end))}"
        }
        Range.MONTH -> monthTitle(key)
        Range.YEAR -> key
        Range.ALL -> "Seit dem ersten Anhören"
    }

    /**
     * The neighbouring period, or null for `all` - every period at once has no
     * neighbour to step to, which is also what hides the arrows.
     */
    fun step(range: Range, key: String, by: Long): String? = when (range) {
        Range.DAY -> iso(keyDate(key).plusDays(by))
        Range.WEEK -> iso(keyDate(key).plusWeeks(by))
        // Stepping from the first of the month, so a 31st cannot skip a short one.
        Range.MONTH -> keyDate(key).withDayOfMonth(1).plusMonths(by).let {
            "%04d-%02d".format(it.year, it.monthValue)
        }
        Range.YEAR -> ((key.toIntOrNull() ?: LocalDate.now().year) + by).toString()
        Range.ALL -> null
    }

    /** Every slot the chart shows, including the ones with nothing in them. */
    fun slots(range: Range, key: String, rows: List<String>): List<String> = when (range) {
        Range.DAY -> (0..23).map { "%02d".format(it) }
        Range.WEEK -> (0..6).map { iso(keyDate(key).plusDays(it.toLong())) }
        Range.MONTH -> {
            val ym = runCatching {
                YearMonth.of(key.split("-")[0].toInt(), key.split("-")[1].toInt())
            }.getOrElse { YearMonth.now() }
            (1..ym.lengthOfMonth()).map { "$key-%02d".format(it) }
        }
        Range.YEAR -> (1..12).map { "$key-%02d".format(it) }
        // From the first year something was played to the one running now.
        Range.ALL -> {
            val from = rows.firstOrNull()?.toIntOrNull()
            if (from == null) emptyList()
            else (from..maxOf(from, LocalDate.now().year)).map { it.toString() }
        }
    }

    /** The short label under a bar. */
    fun slotLabel(range: Range, key: String): String = when (range) {
        Range.DAY -> key.toInt().toString()
        Range.WEEK -> "${WEEKDAYS[keyDate(key).dayOfWeek.value - 1]} ${key.substring(8)}."
        Range.MONTH -> "${key.substring(8).toInt()}."
        Range.YEAR -> MONTHS[key.substring(5, 7).toInt() - 1].take(3)
        Range.ALL -> key
    }

    /** True while the arrow may not step further in that direction. */
    fun atEnd(range: Range, key: String, boundary: String): Boolean =
        range == Range.ALL || key == boundary || boundary.isEmpty()
}
