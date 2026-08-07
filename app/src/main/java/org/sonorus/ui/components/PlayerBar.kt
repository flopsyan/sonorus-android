package org.sonorus.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonorus.data.model.Track
import org.sonorus.ui.Motion
import org.sonorus.ui.pressable
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.toggled

/**
 * The name the bar's artwork and the full player's artwork are known by.
 *
 * They are not two pictures of the same song, they are **one** picture in two
 * places, and this is what says so: tapping the bar grows this cover into the
 * full screen rather than drawing a second one over it. See `FullPlayer`.
 */
const val PlayerCoverKey = "player-cover"

/**
 * The transport at the bottom edge.
 *
 * The seek bar **is** the top edge of the bar, exactly as in the web app -
 * a signature element there, and the reason the progress line sits flush at the
 * top rather than inside the padding.
 *
 * [coverVisible] is false while the full player is open: the artwork is then
 * being drawn *there*, and the bar has to say so rather than draw its own copy
 * over it - that is what makes the two one picture instead of two.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PlayerBar(
    track: Track,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    coverUrl: String?,
    coverVisible: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    // Where the rail is being held, if it is. While a finger is on it the bar
    // draws that instead of the playhead - the song only follows on release.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val reported = scrub
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = rememberPlayhead(reported, held = scrub != null, trackKey = track.id)

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // The rail is the top edge and can be grabbed anywhere along it. The
        // line stays the hairline it has always been; what grew is the strip
        // around it, which reaches down into the bar's own top padding so a
        // thumb has something to hit. No knob up here: on the very edge of the
        // bar it would be cut in half at the start of a track.
        SeekRail(
            fraction = fraction,
            onScrub = { scrub = it },
            onSeek = onSeek,
            height = 14.dp,
            thickness = 3.dp,
            lineAtTop = true,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .pressable(dip = 0.99f, onClick = onExpand)
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(
                coverUrl,
                Modifier
                    .sharedElementWithCallerManagedVisibility(
                        sharedContentState = rememberSharedContentState(PlayerCoverKey),
                        visible = coverVisible,
                    )
                    .size(44.dp),
                RoundedCornerShape(6.dp),
                track.title,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    // A title too long for the bar scrolls past instead of
                    // ending in an ellipsis: on a phone that is most of them,
                    // and the bar is the only place the running song is named.
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.textDim)
            }
            IconButton(onClick = {
                haptics.toggled(!playing)
                onToggle()
            }) {
                TransportGlyph(playing, tint = colors.accent, size = 30.dp)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.textDim)
            }
        }
    }
}

/**
 * The play/pause symbol, turning over rather than being swapped.
 *
 * It is the most-pressed control in the app, and an icon that simply *is* the
 * other one afterwards gives no sign the press was received at all - which on a
 * stream that takes a moment to start reads as a dropped tap.
 */
@Composable
fun TransportGlyph(playing: Boolean, tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            (fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.7f))
                .togetherWith(fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.7f))
        },
        label = "transport",
    ) { running ->
        Icon(
            if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (running) "Pause" else "Abspielen",
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
