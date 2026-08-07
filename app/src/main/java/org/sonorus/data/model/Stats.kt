package org.sonorus.data.model

import kotlinx.serialization.Serializable

/**
 * The statistics answer, from `src/models/stats.js`.
 *
 * The page reads the history **one period at a time**: the width says how wide
 * a period is (day / week / month / year / all) and the key says which one. The
 * chart is the breakdown *inside* that period, and the top lists answer for
 * exactly the same period - which is what makes the two say the same thing.
 */
@Serializable
data class StatsResponse(
    val library: LibraryStats = LibraryStats(),
    val listening: Listening = Listening(),
)

@Serializable
data class Listening(
    val totals: StatTotals = StatTotals(),
    val average: StatAverage = StatAverage(),
    val period: StatPeriod = StatPeriod(),
    val chart: List<ChartPoint> = emptyList(),
    val top: TopLists = TopLists(),
)

/** Lifetime numbers - these deliberately ignore the selected period. */
@Serializable
data class StatTotals(
    val plays: Int = 0,
    val seconds: Double = 0.0,
    val firstPlay: String? = null,
    val lastPlay: String? = null,
    val tracks: Int = 0,
    val artists: Int = 0,
    val albums: Int = 0,
    val activeDays: Int = 0,
    /** Days since the first play, quiet ones included. */
    val days: Int = 1,
    val bestDay: BestDay? = null,
)

@Serializable
data class BestDay(val day: String = "", val plays: Int = 0, val seconds: Double = 0.0)

/**
 * Only measured averages, nothing projected. Each one divides time that was
 * really listened to by a span that has really passed - a "pro Jahr" after two
 * days of listening would be a guess, and this page does not guess.
 */
@Serializable
data class StatAverage(
    val day: Double = 0.0,
    val activeDay: Double = 0.0,
    val play: Double = 0.0,
    val playsPerDay: Double = 0.0,
)

/**
 * [first] and [current] are the two ends the navigator may not step past, so
 * greying out an arrow needs no round trip. `all` carries no key at all, which
 * is what hides the arrows entirely.
 */
@Serializable
data class StatPeriod(
    val range: String = "day",
    val key: String = "",
    val first: String = "",
    val current: String = "",
    val totals: PeriodTotals = PeriodTotals(),
)

@Serializable
data class PeriodTotals(
    val plays: Int = 0,
    val seconds: Double = 0.0,
    val tracks: Int = 0,
    val artists: Int = 0,
    val albums: Int = 0,
)

@Serializable
data class ChartPoint(val key: String = "", val plays: Int = 0, val seconds: Double = 0.0)

@Serializable
data class TopLists(
    val tracks: List<TopEntry> = emptyList(),
    val artists: List<TopEntry> = emptyList(),
    val albums: List<TopEntry> = emptyList(),
)

/** One shape for all three lists - an artist puts its name in [title] too. */
@Serializable
data class TopEntry(
    val id: Int = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artistId: Int? = null,
    val albumId: Int? = null,
    val cover: String? = null,
    val plays: Int = 0,
    val seconds: Double = 0.0,
    /** Only on the artist list: how many distinct tracks were played. */
    val tracks: Int = 0,
)
