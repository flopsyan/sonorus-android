package org.sonorus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.model.ChartPoint
import org.sonorus.data.model.KindTotal
import org.sonorus.data.model.KindTotals
import org.sonorus.data.model.SpokenLibraries
import org.sonorus.data.model.TopEntry
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Periods
import org.sonorus.ui.Range
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num
import java.util.Calendar
import java.util.TimeZone
import org.sonorus.ui.components.ServerOnlyNote
import org.sonorus.ui.LocalOffline

/**
 * The listening statistics.
 *
 * The switch picks how *wide* a period is, the arrows pick which one, and the
 * chart, the readout and all three top lists answer for exactly that period.
 * The two panels at the bottom stay lifetime on purpose - an average over a
 * single selected day would be the same number twice.
 */
@UnstableApi
@Composable
fun StatsScreen(vm: AppViewModel) {
    // The play log lives on the server and nowhere else, so there is no shorter
    // version of this page to draw out of the downloads.
    if (LocalOffline.current) return ServerOnlyNote("Die Statistik")
    val colors = SonorusTheme.colors
    // The listener's day boundaries, not the server's.
    val offset = remember {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
    }
    var range by remember { mutableStateOf(Range.of(vm.prefs.statsRange)) }
    var period by remember { mutableStateOf<String?>(null) }

    val load = rememberLoad("stats", range.key, period) {
        vm.api.stats(offset, range.key, period)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // Changing the width lands on the current period; the arrows walk back
        // from there. The width is remembered on the account, the period is not
        // - a saved day would be yesterday tomorrow.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (r in Range.entries) {
                Chip(r.label, r == range) {
                    range = r
                    period = null
                    vm.saveStatsRange(r.key)
                }
            }
        }

        // One root child, and that is not a detail: [LoadBox] puts what it is
        // given into an `AnimatedContent`, whose slot is a `Box` - so a lambda
        // that hands it ten siblings draws all ten in the same place. Every
        // other screen passes a single list and never notices. This page had
        // the whole statistic stacked on one spot because of it.
        LoadBox(load) { data ->
          Column(Modifier.fillMaxWidth()) {
            val p = data.listening.period
            val current = period ?: p.key

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (range != Range.ALL) {
                    val canBack = !Periods.atEnd(range, current, p.first)
                    IconButton(
                        onClick = { Periods.step(range, current, -1)?.let { period = it } },
                        enabled = canBack,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            "Vorherige Periode",
                            tint = if (canBack) colors.text else colors.textFaint,
                        )
                    }
                }
                Text(
                    Periods.title(range, current),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (range != Range.ALL) {
                    val canForward = !Periods.atEnd(range, current, p.current)
                    IconButton(
                        onClick = { Periods.step(range, current, 1)?.let { period = it } },
                        enabled = canForward,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            "Nächste Periode",
                            tint = if (canForward) colors.text else colors.textFaint,
                        )
                    }
                }
            }

            // The readout for the selected period.
            StatPanel {
                Readout2("Spielzeit", Fmt.durationRack(p.totals.seconds), accent = true)
                Readout2("Wiedergaben", Fmt.number(p.totals.plays))
                Readout2("Songs", Fmt.number(p.totals.tracks))
                Readout2("Interpreten", Fmt.number(p.totals.artists))
                Readout2("Alben", Fmt.number(p.totals.albums))
            }

            Chart(
                points = data.listening.chart,
                range = range,
                periodKey = current,
            )

            KindTable(p.kinds)

            TopList("Meistgehörte Songs", data.listening.top.tracks, vm, subtitleOf = { it.artist })
            TopList("Meistgehörte Interpreten", data.listening.top.artists, vm, round = true) {
                Fmt.plural(it.tracks, "Song", "Songs")
            }
            TopList("Meistgehörte Alben", data.listening.top.albums, vm) { it.artist }
            TopList("Meistgehörtes Gesprochenes", data.listening.top.spoken, vm) { spokenSub(it) }

            // Lifetime, on purpose - see the note above.
            RackLabelText("Durchschnitt", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            StatPanel {
                val a = data.listening.average
                Readout2("Pro Tag", Fmt.durationRack(a.day))
                Readout2("Pro Hörtag", Fmt.durationRack(a.activeDay))
                Readout2("Pro Wiedergabe", Fmt.durationRack(a.play))
                Readout2("Wiedergaben/Tag", "%.1f".format(a.playsPerDay))
                data.listening.totals.bestDay?.let {
                    Readout2("Bester Tag", Fmt.durationRack(it.seconds))
                }
            }
            data.listening.totals.firstPlay?.let {
                Text(
                    "Grundlage sind die ${Fmt.number(data.listening.totals.days)} Tage " +
                        "seit dem ersten Anhören am ${Fmt.date(it)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            RackLabelText("Bibliothek", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            StatPanel {
                Readout2("Songs", Fmt.number(data.library.tracks))
                Readout2("Interpreten", Fmt.number(data.library.artists))
                Readout2("Alben", Fmt.number(data.library.albums))
                Readout2("Singles", Fmt.number(data.library.singles))
                Readout2("Genres", Fmt.number(data.library.genres))
                Readout2("Spielzeit", Fmt.durationRack(data.library.duration))
                Readout2("Größe", Fmt.bytes(data.library.size))
            }

            SpokenPanel(data.spoken)
          }
        }
    }
}

/**
 * The selected period, split by the library it was listened to in.
 *
 * Every library keeps its row even when it was silent - that is what says it is
 * counted on this page at all, and a row left out would say nothing. There is
 * no bar here on purpose: the top lists on this screen have none either, and a
 * meter next to a percentage on a phone is the same number drawn twice.
 */
@Composable
private fun KindTable(kinds: KindTotals) {
    val colors = SonorusTheme.colors
    RackLabelText("Spielzeit", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    StatPanel {
        KindRow("Musik", kinds.music, kinds.total.seconds)
        KindRow("Podcasts", kinds.podcast, kinds.total.seconds)
        KindRow("Hörbücher", kinds.book, kinds.total.seconds)
        KindRow("Hörspiele", kinds.drama, kinds.total.seconds)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        KindRow("Gesamt", kinds.total, 0.0, accent = true)
    }
}

@Composable
private fun KindRow(label: String, value: KindTotal, total: Double, accent: Boolean = false) {
    val colors = SonorusTheme.colors
    // Quiet libraries step back rather than disappear.
    val dim = value.seconds <= 0.0 && !accent
    val share = if (total > 0.0) Math.round(value.seconds / total * 100).toInt() else -1
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim) colors.textFaint else colors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (share >= 0) {
            Text("$share %", style = num(11.sp), color = colors.textFaint)
        }
        Text(
            Fmt.plural(value.plays, "Wiedergabe", "Wiedergaben"),
            style = num(11.sp),
            color = colors.textFaint,
        )
        Text(
            Fmt.durationRack(value.seconds),
            style = num(if (accent) 15.sp else 13.sp),
            color = when {
                accent -> colors.accent
                dim -> colors.textFaint
                else -> colors.text
            },
        )
    }
}

/**
 * What the three spoken libraries hold, the way the Bibliothek panel above says
 * it for the music. One row each rather than three panels: they answer the same
 * question in different words, and a podcast has Folgen where a book has Teile.
 */
@Composable
private fun SpokenPanel(spoken: SpokenLibraries) {
    RackLabelText("Gesprochenes", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    StatPanel {
        SpokenRow(
            "Podcasts",
            listOf(
                Fmt.plural(spoken.podcasts.shows, "Show", "Shows"),
                Fmt.plural(spoken.podcasts.episodes, "Folge", "Folgen"),
                "${spoken.podcasts.unplayed} ungehört",
            ),
            spoken.podcasts.duration,
        )
        SpokenRow(
            "Hörbücher",
            listOf(
                Fmt.plural(spoken.books.books, "Buch", "Bücher"),
                Fmt.plural(spoken.books.authors, "Autor", "Autoren"),
                "${spoken.books.open} offen",
            ),
            spoken.books.duration,
        )
        SpokenRow(
            "Hörspiele",
            listOf(
                Fmt.plural(spoken.dramas.books, "Hörspiel", "Hörspiele"),
                Fmt.plural(spoken.dramas.authors, "Autor", "Autoren"),
                "${spoken.dramas.open} offen",
            ),
            spoken.dramas.duration,
        )
    }
}

@Composable
private fun SpokenRow(label: String, parts: List<String>, seconds: Double) {
    val colors = SonorusTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Fmt.durationRack(seconds), style = num(13.sp), color = colors.text)
    }
}

// One list holds three libraries, so every row says which one it is. The author
// follows where there is one - a show has none.
private fun spokenSub(entry: TopEntry): String {
    val word = when (entry.kind) {
        "podcast" -> "Podcast"
        "book" -> "Hörbuch"
        "drama" -> "Hörspiel"
        else -> "Gesprochenes"
    }
    return if (entry.artist.isBlank()) word else "$word · ${entry.artist}"
}

@Composable
private fun StatPanel(content: @Composable () -> Unit) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Readout2(label: String, value: String, accent: Boolean = false) {
    val colors = SonorusTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
        Text(
            value,
            style = num(if (accent) 16.sp else 14.sp),
            color = if (accent) colors.accent else colors.text,
        )
    }
}

/**
 * The breakdown inside the selected period. Plain boxes - no chart library, and
 * the numbers are printed rather than hidden behind a tap: a value you only see
 * after touching it is a value you do not see.
 */
@Composable
private fun Chart(points: List<ChartPoint>, range: Range, periodKey: String) {
    val colors = SonorusTheme.colors
    val byKey = points.associateBy { it.key }
    val slots = Periods.slots(range, periodKey, points.map { it.key })
    if (slots.isEmpty()) return

    val values = slots.map { byKey[it]?.seconds ?: 0.0 }
    val max = values.maxOrNull() ?: 0.0
    if (max <= 0.0) {
        Text(
            "In diesem Zeitraum wurde nichts gehört.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        )
        return
    }

    // From 14 bars up the columns narrow, so a month fits in fewer swipes.
    val columnWidth = if (slots.size >= 14) 30.dp else 46.dp

    // A day has 24 columns and only about a dozen fit, so the chart starts at
    // the first slot that has something in it rather than at midnight - opening
    // the page on an empty stretch of night looks like there is no data at all.
    val scroll = rememberScrollState()
    val firstFilled = values.indexOfFirst { it > 0 }
    val density = LocalDensity.current
    LaunchedEffect(periodKey, slots.size, firstFilled) {
        if (firstFilled > 0) {
            val target = with(density) { (columnWidth + 4.dp).toPx() } * firstFilled
            scroll.scrollTo(target.toInt())
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slots.forEachIndexed { i, key ->
                val point = byKey[key]
                val seconds = values[i]
                Column(
                    Modifier.width(columnWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (seconds > 0) Fmt.durationRack(seconds) else "",
                        style = num(9.sp),
                        color = colors.textDim,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .width(columnWidth - 10.dp)
                            .height((6 + (110 * (seconds / max))).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (seconds > 0) colors.accent else colors.surface2)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Periods.slotLabel(range, key),
                        style = num(9.sp),
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                    Text(
                        if ((point?.plays ?: 0) > 0) "${point?.plays}" else "",
                        style = num(9.sp),
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun TopList(
    label: String,
    entries: List<TopEntry>,
    vm: AppViewModel,
    round: Boolean = false,
    subtitleOf: (TopEntry) -> String,
) {
    if (entries.isEmpty()) return
    val colors = SonorusTheme.colors
    RackLabelText(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        entries.forEachIndexed { i, entry ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${i + 1}", style = num(12.sp), color = colors.textFaint, modifier = Modifier.width(20.dp))
                Cover(
                    vm.coverUrl(entry.cover),
                    Modifier.size(38.dp),
                    if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(6.dp),
                    entry.title,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitleOf(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.durationRack(entry.seconds), style = num(12.sp), color = colors.text)
                    Text(
                        Fmt.plural(entry.plays, "Wiedergabe", "Wiedergaben"),
                        style = num(10.sp),
                        color = colors.textFaint,
                    )
                }
            }
        }
    }
}
