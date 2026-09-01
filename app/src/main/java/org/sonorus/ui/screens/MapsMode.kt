package org.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.model.Track
import org.sonorus.player.PlayerState
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.SeekRail
import org.sonorus.ui.components.Stars
import org.sonorus.ui.components.TransportGlyph
import org.sonorus.ui.components.rememberPlayhead
import org.sonorus.ui.pressable
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num
import org.sonorus.ui.toggled

/**
 * Below this window height the shell has nothing to show.
 *
 * The number separates the three cases that actually occur on a phone, and it is
 * chosen to sit in the gap between them rather than at a round number:
 *
 *  - a split screen dragged as small as it goes is **ungefähr 220 dp** - Android
 *    refuses to make a task much smaller than that,
 *  - a full screen in landscape is **ungefähr 380 dp**,
 *  - an even split is **ungefähr 430 dp**.
 *
 * So 320 dp catches the first and neither of the other two. Landscape is
 * additionally ruled out by the multi-window test in [isCompactWindow], because
 * a smaller phone in landscape could fall under this on its own - and a phone
 * held sideways wants the whole app, not a transport panel.
 */
private val COMPACT_MAX_HEIGHT = 320.dp


/**
 * The floor under which the strip stops fitting and the one-row panel takes over.
 *
 * The strip is three stacked blocks: 56 dp of artwork row, 34 dp of rail plus
 * counters, 64 dp of transport - 154 dp of content, plus 20 dp of its own
 * padding. Under that it does not merely look tight, it **overlaps**: a `Column`
 * asked to arrange more than it has room for hands out negative spacing, which
 * is what Florian saw when he dragged the divider past this point on 2026-08-25
 * ("wenn ich es zu klein mach, wieder falsch anzeigt").
 *
 * So the rule is not "drop a piece and hope", it is **pick the layout that
 * fits**. Two of them, and each is measured against the band it serves.
 */
private val STRIP_MIN_HEIGHT = 175.dp

/** How big the five stars are drawn in the strip - see [STARS_MIN_HEIGHT]. */
private const val STAR_SIZE = 24

/**
 * From here up the strip has room for the rating as well.
 *
 * Florian, on the split he actually uses - roughly a third of the screen: "da
 * hast du platz. ist sehr viel platz". He is right, and the arithmetic says by
 * how much: the strip is 154 dp of content in a band that runs to 320 dp, so it
 * was spreading three blocks over well over half again the height they need.
 *
 * **The number was measured on the device rather than derived** (2026-09-01),
 * and the measurement is the interesting part, because it contradicts what this
 * note used to say about the two halves. Android snaps the divider to fixed
 * ratios; the "one third" snap hands out a **255 dp window** either way, and what
 * `systemBarsPadding` then takes off is not the same on both sides:
 *
 * | half | inset it pays | content height |
 * |---|---|---|
 * | top | the status bar, 52 dp | ungefähr 202 dp |
 * | bottom | the gesture bar, 24 dp | ungefähr 231 dp |
 *
 * So the **bottom half is the roomier one**, not the tighter one. The stars are
 * 24 dp, which puts the four blocks at 195 dp including the padding, and 200 dp
 * is the smallest line that clears the tighter of the two halves - so the rating
 * appears at a third whichever half Sonorus is in. Being 3 dp above the floor is
 * the point rather than an oversight: a threshold set for comfort would clear
 * only one half, and the same split would then draw two different screens.
 *
 * **Shuffle and repeat are deliberately not behind this.** They cost no height
 * at all: they went into the transport row, which is 64 dp tall whether it holds
 * three controls or five, and which had the width to spare.
 */
private val STARS_MIN_HEIGHT = 200.dp

/**
 * Under this width the two mode buttons are left out again.
 *
 * Five controls are 272 dp of button. A split screen on a phone is the full
 * width and has 411 dp of it, but a freeform window or a foldable's cover screen
 * need not - and the one thing that must never happen here is the transport
 * being squeezed, which is the failure of 2026-08-25 in the other direction.
 */
private val MODES_MIN_WIDTH = 330.dp

/**
 * Whether the app has been squeezed into a strip of a split screen.
 *
 * [height] is the height the app has really been given, measured from the root
 * composable - **not** `Configuration.screenHeightDp`, and that swap is the fix
 * for the whole feature not triggering at all.
 *
 * The old test was `isInMultiWindowMode && screenHeightDp < 320.dp`, and on
 * Florian's phone it answered false in a split screen ungefähr 140 dp tall: the
 * app drew its full library shell - top bar, tab row, transport - into a strip
 * the height of two rows, which is the state in the screenshot from
 * 2026-08-25. Which of the two halves lied was not worth finding out, because
 * neither had to be asked: a composable can measure the box it is being drawn
 * in, and that number cannot be stale, cannot lag a configuration change and
 * does not depend on how a ROM reports multi-window.
 *
 * The multi-window test went with it and is not missed. It was there to keep a
 * phone in landscape out of the compact branch, and the height alone already
 * does that: the shortest landscape window on a phone is ungefähr 360 dp, well
 * clear of the 320 dp line. Anything genuinely under it - a split screen, a
 * freeform window, a foldable's cover screen - wants the strip whether or not
 * Android calls it multi-window.
 */
fun isCompactWindow(height: Dp): Boolean = height < COMPACT_MAX_HEIGHT

/**
 * The player as a strip, for the corner of a split screen.
 *
 * Florian's own idea, and the way around Google Maps refusing to list Sonorus
 * (see the Maps section in the vault note): Maps on top, Sonorus dragged as
 * small as it goes underneath. What the shell would draw at that height - a top
 * bar, a page, six tabs and a transport bar - is a row of cut-off furniture, so
 * at that size the app stops being a library and becomes what it is being used
 * as right then: cover, name, and the three controls that matter at the wheel.
 *
 * It is the **same player**, not a second one - `vm.player` throughout, exactly
 * as the bar and the full screen are. Nothing here can drift out of step, and
 * dragging the divider back restores the shell where it was.
 *
 * Deliberately still: no marquee, no artwork animation, no travelling tints.
 * This is the one screen that is read while driving, so nothing on it moves that
 * does not have to. The stars are the one exception and they earn it - what
 * moves there is the answer to a tap, not something happening by itself.
 */
@UnstableApi
@Composable
fun MapsMode(vm: AppViewModel) {
    val colors = SonorusTheme.colors
    val state by vm.player.state.collectAsState()
    val track = state.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
    ) {
        if (track == null) {
            Text(
                "Nichts in der Wiedergabe.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 10.dp),
            )
            return@BoxWithConstraints
        }
        // The height here is what is really left after the system bars, which is
        // the only number worth deciding on - the window height still counts the
        // status bar the top half of a split has to live under.
        if (maxHeight >= STRIP_MIN_HEIGHT) {
            Strip(
                vm = vm,
                state = state,
                track = track,
                // A rating a spoken track cannot have is left out rather than
                // drawn dead: the server keeps no rating for an episode or a
                // book part, and five stars that do nothing are worse than none.
                stars = maxHeight >= STARS_MIN_HEIGHT && !track.isSpoken,
                modes = maxWidth >= MODES_MIN_WIDTH,
            )
        } else {
            Panel(vm, state, track)
        }
    }
}

/**
 * The roomier of the two: artwork over the rating over a seek rail over the
 * transport.
 *
 * [stars] and [modes] are what the box it is being drawn in can afford, decided
 * in [MapsMode] and not here - so this composable never has to ask how big it
 * is, and the two thresholds sit next to each other where they can be compared.
 */
@UnstableApi
@Composable
private fun Strip(
    vm: AppViewModel,
    state: PlayerState,
    track: Track,
    stars: Boolean,
    modes: Boolean,
) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    var scrub by remember { mutableStateOf<Float?>(null) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(vm.coverUrl(track.cover), Modifier.size(56.dp), RoundedCornerShape(8.dp), track.title)
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        track.artist.takeIf { it.isNotEmpty() },
                        track.album.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (stars) {
            // The rating the view model knows, not the one the queue was filled
            // with: the queue holds the row as it was when it was added, so its
            // own stars go stale the moment one is given anywhere else.
            val given = vm.starsOf(track)
            Stars(
                given,
                Modifier.align(Alignment.CenterHorizontally),
                size = STAR_SIZE,
            ) { value -> vm.rate(track.id, value, given) }
        }

        val reported = scrub
            ?: if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else 0f
        val fraction = rememberPlayhead(reported, held = scrub != null, trackKey = track.id)
        Column(Modifier.fillMaxWidth()) {
            SeekRail(
                fraction = fraction,
                onScrub = { scrub = it },
                onSeek = { f ->
                    if (state.durationMs > 0) vm.player.seekTo((state.durationMs * f).toLong())
                },
                height = 18.dp,
                thickness = 4.dp,
                rounded = true,
                knob = 9.dp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    Fmt.duration((fraction * state.durationMs / 1000).toDouble()),
                    style = num(11.sp),
                    color = colors.textDim,
                )
                Text(
                    Fmt.duration(state.durationMs / 1000.0),
                    style = num(11.sp),
                    color = colors.textDim,
                )
            }
        }

        // The three the Maps panel offers, at the sizes the full player settled
        // on - this is the one place they are hit without looking - with the two
        // modes outside them where the full player puts them too.
        //
        // Shuffle and repeat cost nothing here: the row is as tall as its
        // tallest child either way, and the width was going spare. That is why
        // they have no threshold of their own while the stars do.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (modes) {
                IconButton(
                    onClick = {
                        haptics.toggled(!state.shuffle)
                        vm.player.setShuffle(!state.shuffle)
                        vm.savePlayerPrefs()
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        "Zufall",
                        tint = if (state.shuffle) colors.accent else colors.textDim,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(60.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.text, modifier = Modifier.size(46.dp))
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .pressable(dip = 0.94f) {
                        haptics.toggled(!state.playing)
                        vm.player.toggle()
                    },
                contentAlignment = Alignment.Center,
            ) {
                TransportGlyph(state.playing, tint = colors.accentInk, size = 32.dp)
            }
            IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(60.dp)) {
                Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.text, modifier = Modifier.size(46.dp))
            }
            if (modes) {
                IconButton(
                    onClick = {
                        haptics.toggled(state.repeat == "off")
                        vm.player.cycleRepeat()
                        vm.savePlayerPrefs()
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    // Off and all wear the same glyph and only the tint tells
                    // them apart; one is a symbol of its own. No crossfade
                    // between them here - see the note on this file.
                    Icon(
                        if (state.repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Wiederholen",
                        tint = if (state.repeat != "off") colors.accent else colors.textDim,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/**
 * Everything on one line, for a window dragged down to a sliver.
 *
 * This is the Maps panel's own shape, and it is the shape because at this height
 * there is room for exactly one row: what is playing, and the three controls.
 * Everything the strip carries that is *nice* rather than necessary is gone -
 * the album, the counters, and the seek rail as something to drag.
 *
 * The progress is still there, as a hairline across the very top. It is drawn,
 * not draggable: a scrub target three pixels tall between two transport buttons
 * is a mis-tap waiting to happen, and this is the screen that gets used at the
 * wheel.
 *
 * It stays legible down to ungefähr 56 dp, which is below anything Android will
 * actually hand out for a split - so this is the floor and there is no third
 * layout under it.
 *
 * **Shuffle, repeat and the rating deliberately stop at the strip.** Two more
 * 34 dp buttons on this row leave ungefähr 100 dp for the title and the artist,
 * which is ten characters - and what this layout exists to protect is exactly
 * the row not being squeezed. Anything worth having at this size is already on
 * it.
 */
@UnstableApi
@Composable
private fun Panel(vm: AppViewModel, state: PlayerState, track: Track) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(colors.surface)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(colors.accent)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(vm.coverUrl(track.cover), Modifier.size(40.dp), RoundedCornerShape(6.dp), track.title)
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.artist.isNotEmpty()) {
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.text, modifier = Modifier.size(32.dp))
            }
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .pressable(dip = 0.94f) {
                        haptics.toggled(!state.playing)
                        vm.player.toggle()
                    },
                contentAlignment = Alignment.Center,
            ) {
                TransportGlyph(state.playing, tint = colors.accentInk, size = 22.dp)
            }
            IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.text, modifier = Modifier.size(32.dp))
            }
        }
    }
}
