package org.sonorus.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.SeekRail
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
 * Under this the seek rail is dropped, so the transport always survives.
 *
 * Measured rather than guessed: the strip needs 56 dp for the artwork row, 34 dp
 * for rail plus counters and 64 dp for the transport, so 154 dp is the real
 * floor and 160 dp leaves a little air. The first try at 190 dp was too careful -
 * a split dragged all the way down leaves ungefähr 176 dp of content height, so
 * the rail fell away in exactly the case this screen exists for.
 */
private val RAIL_MIN_HEIGHT = 160.dp

/**
 * Whether the app has been squeezed into a strip of a split screen.
 *
 * Both halves of the test are re-read on every configuration change, which is
 * what dragging the divider produces - so this follows the divider rather than
 * being decided once at startup.
 */
@Composable
fun isCompactWindow(): Boolean {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val multiWindow = remember(configuration) { context.activity()?.isInMultiWindowMode == true }
    return multiWindow && configuration.screenHeightDp.dp < COMPACT_MAX_HEIGHT
}

private fun Context.activity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

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
 * Deliberately still: no marquee, no rating, no artwork animation. This is the
 * one screen that is read while driving, so nothing on it moves that does not
 * have to.
 */
@UnstableApi
@Composable
fun MapsMode(vm: AppViewModel) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    val state by vm.player.state.collectAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    val track = state.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (track == null) {
            Text(
                "Nichts in der Wiedergabe.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        val roomForRail = maxHeight >= RAIL_MIN_HEIGHT

        Column(
            Modifier.fillMaxSize(),
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

            if (roomForRail) {
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
            }

            // The same three the Maps panel offers, at the sizes the full player
            // settled on - this is the one place they are hit without looking.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(60.dp)) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        "Zurück",
                        tint = colors.text,
                        modifier = Modifier.size(46.dp),
                    )
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
                    Icon(
                        Icons.Filled.SkipNext,
                        "Weiter",
                        tint = colors.text,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
        }
    }
}
