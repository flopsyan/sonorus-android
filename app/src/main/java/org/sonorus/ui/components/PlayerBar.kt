package org.sonorus.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonorus.data.model.Chapter
import org.sonorus.data.model.Track
import org.sonorus.player.SKIP_MS
import org.sonorus.ui.Motion
import org.sonorus.ui.pressable
import org.sonorus.ui.nowLines
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.toggled
import org.sonorus.ui.armed

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
 * The progress line **is** the top edge of the bar, exactly as in the web app -
 * a signature element there, and the reason it sits flush at the top rather than
 * inside the padding. It only shows: a thumb reaching for the bar landed on it
 * and moved the song too often, so seeking is the full player's alone.
 *
 * A sideways wipe over the title steps to the next or the previous song, the
 * same gesture the full player's artwork takes.
 *
 * [coverVisible] is false while the full player is open: the artwork is then
 * being drawn *there*, and the bar has to say so rather than draw its own copy
 * over it - that is what makes the two one picture instead of two.
 *
 * Spoken word gets the two fifteen-second skips in place of prev/next, the same
 * swap the full player makes - the bar is the control that is actually reached
 * for while a book plays, so it was the one place the old buttons were still
 * stepping to the next *file* in the middle of a chapter.
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
    /** The chapter being heard, for a book. Null for everything else. */
    chapter: Chapter? = null,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    /** False skips the three-second restart rule, which a wipe means to. */
    onPrevious: (restartFirst: Boolean) -> Unit,
    /** A jump of this many milliseconds from where the playhead is. */
    onSkip: (Long) -> Unit,
    onExpand: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    val reported = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = rememberPlayhead(reported, held = false, trackKey = track.id)
    // How far the title is wiped sideways. Zero unless a wipe is running.
    val wipe = remember { Animatable(0f) }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            // The strip the line sits in opens the player like the rest of the bar.
            .pressable(dip = 0.99f, onClick = onExpand)
    ) {
        SeekRail(
            fraction = fraction,
            onScrub = {},
            onSeek = {},
            height = 14.dp,
            thickness = 3.dp,
            lineAtTop = true,
            enabled = false,
        )

        Row(
            Modifier
                .fillMaxWidth()
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
            Column(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        titleWipe(
                            wipe = wipe,
                            onArmed = { haptics.armed() },
                            onNext = { next() },
                            onPrevious = { previous(false) },
                        )
                    }
                    .offset { IntOffset(wipe.value.roundToInt(), 0) }
                    .graphicsLayer { alpha = 1f - 0.7f * (abs(wipe.value) / size.width).coerceIn(0f, 1f) }
            ) {
                // A book names its chapter and the book where a song names its
                // title and its interpret - see [nowLines].
                val lines = nowLines(track, chapter)
                Text(
                    lines.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    // A title too long for the bar scrolls past instead of
                    // ending in an ellipsis: on a phone that is most of them,
                    // and the bar is the only place the running song is named.
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    listOfNotNull(
                        lines.artist.takeIf { it.isNotEmpty() },
                        lines.album.takeIf { it.isNotEmpty() && track.audiobookId != null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The glyph is drawn a little larger than the 24 dp arrows it
            // replaces: it has to carry a two-digit number inside its opening,
            // and at the arrows' size that number is no longer readable.
            if (track.isSpoken) {
                SkipButton(
                    forward = false,
                    onClick = { onSkip(-SKIP_MS) },
                    button = 48.dp,
                    glyph = 28.dp,
                    number = 8.sp,
                    tint = colors.textDim,
                )
            } else {
                IconButton(onClick = { onPrevious(true) }) {
                    Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.textDim)
                }
            }
            IconButton(onClick = {
                haptics.toggled(!playing)
                onToggle()
            }) {
                TransportGlyph(playing, tint = colors.accent, size = 30.dp)
            }
            if (track.isSpoken) {
                SkipButton(
                    forward = true,
                    onClick = { onSkip(SKIP_MS) },
                    button = 48.dp,
                    glyph = 28.dp,
                    number = 8.sp,
                    tint = colors.textDim,
                )
            } else {
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.textDim)
                }
            }
        }
    }
}

/**
 * Wipe the title sideways for the next or the previous song, as on the full
 * player's artwork: left is forward, a third of the width arms it, and a wipe
 * that stops short slides back. Only a sideways drag is taken, so a tap still
 * reaches the bar and opens the player.
 */
private suspend fun PointerInputScope.titleWipe(
    wipe: Animatable<Float, AnimationVector1D>,
    onArmed: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) = coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            dx = over
        } ?: return@awaitEachGesture
        val width = size.width.toFloat()
        val skipAfter = width / 3f
        var armed = false
        launch { wipe.snapTo(dx) }
        val ended = horizontalDrag(start.id) { change ->
            dx += change.positionChange().x
            change.consume()
            launch { wipe.snapTo(dx) }
            val far = abs(dx) >= skipAfter
            if (far != armed) {
                armed = far
                if (far) onArmed()
            }
        }
        val step = when {
            !ended -> 0
            dx <= -skipAfter -> 1
            dx >= skipAfter -> -1
            else -> 0
        }
        launch {
            if (step == 0) {
                wipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                return@launch
            }
            // Out the way it was wiped, then the new title comes in from the other side.
            wipe.animateTo(-step * width, tween(Motion.Quick))
            if (step > 0) onNext() else onPrevious()
            wipe.snapTo(step * width)
            wipe.animateTo(0f, tween(Motion.Standard, easing = Motion.Decelerate))
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
