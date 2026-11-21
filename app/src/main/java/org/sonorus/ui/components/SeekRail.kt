package org.sonorus.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.sonorus.ui.Fmt
import org.sonorus.ui.landed
import org.sonorus.ui.theme.SonorusTheme
import kotlin.math.abs

/**
 * How often [PlayerController] writes the position, in milliseconds.
 *
 * Kept in step with `PlayerController.TICK_MS` by hand: the two are separate
 * decisions that happen to want the same number, and the rail only needs to know
 * how long it has to cover before the next one arrives.
 */
private const val TICK_MS = 500

/** A step this big is not playback - a seek, or the next song starting. */
private const val JUMP = 0.05f

/** No step is shorter than a frame. */
private const val FRAME_MS = 16L

/**
 * The playhead as something that moves rather than something that steps.
 *
 * The player reports its position twice a second, so the rail was redrawn twice
 * a second and stood still in between. On a three minute song that is a bar
 * ticking forward in visible jerks - small, but it is on screen the entire time
 * anything is playing, which makes it the one piece of stillness the eye has the
 * longest to notice.
 *
 * So the rail walks to each new reading over exactly the time until the next one
 * and arrives just as it lands. A *jump* is not walked: a seek, or the next song
 * starting from zero, snaps - sliding backwards through a whole song would be a
 * lie about what happened. Neither is a rail being held, which belongs to the
 * finger and must not lag behind it.
 *
 * It walks **a pixel at a time**, and the answer is a state to be read where the
 * rail is drawn. As an animation read by the player it redrew the whole screen on
 * every frame for as long as a song ran, to move a line a twentieth of a pixel.
 */
@Composable
fun rememberPlayhead(fraction: Float, held: Boolean, trackKey: Any?, durationMs: Long): FloatState {
    val playhead = remember { mutableFloatStateOf(0f) }
    // No rail is wider than the window, so this is the finest step one can show.
    val width = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
    val step = (durationMs / width).coerceAtLeast(FRAME_MS)
    LaunchedEffect(trackKey) { playhead.floatValue = 0f }
    LaunchedEffect(fraction, held) {
        val from = playhead.floatValue
        if (held || fraction == from || abs(fraction - from) > JUMP || step >= TICK_MS) {
            playhead.floatValue = fraction
            return@LaunchedEffect
        }
        val start = System.nanoTime()
        do {
            delay(step)
            val walked = ((System.nanoTime() - start) / 1e6f / TICK_MS).coerceAtMost(1f)
            playhead.floatValue = from + (fraction - from) * walked
        } while (walked < 1f)
    }
    return playhead
}

/**
 * The seek rail, in both the places the player draws one.
 *
 * The gesture is the web app's, taken over as it stands: **the press itself
 * already moves the playhead, the finger drags it from there, and the song only
 * jumps when the finger comes off.** Seeking on every move instead has the
 * player re-open the stream over and over, and the drag stutters to a halt -
 * which is exactly why the web rail paints while it is held and seeks on
 * release.
 *
 * A tap is that same gesture without the middle part, so it seeks too and
 * nothing had to be added for it. `detectHorizontalDragGestures`, which this
 * replaces, could do neither properly: it hands over nothing until the finger
 * has travelled past the touch slop, so a press that lands on the spot is
 * thrown away and a drag starts a slop's width off.
 *
 * Where the playhead is drawn while the rail is held stays the caller's, because
 * the full player prints the same figure as the elapsed time beside it:
 * [onScrub] reports the held fraction, and `null` once the finger is gone.
 *
 * [height] is the strip that can be grabbed, [thickness] the line that is drawn
 * in it - a thumb needs far more to aim at than the hairline the design wants.
 * [buffered] is how far the player has loaded, drawn in [bufferColor].
 *
 * [fraction] is asked while drawing, so a moving playhead repaints the line and
 * nothing around it.
 */
@Composable
fun SeekRail(
    fraction: () -> Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    thickness: Dp = 3.dp,
    rounded: Boolean = false,
    knob: Dp = 0.dp,
    lineAtTop: Boolean = false,
    buffered: Float = 0f,
    trackColor: Color = SonorusTheme.colors.surface3,
    bufferColor: Color = Color.Transparent,
    /** False draws the rail and takes no finger. */
    enabled: Boolean = true,
) {
    val colors = SonorusTheme.colors
    // The gesture outlives every recomposition, so it must not keep calling the
    // callbacks it was born with.
    val scrubTo by rememberUpdatedState(onScrub)
    val seekTo by rememberUpdatedState(onSeek)
    val haptics = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    // The rail thickens under the finger the way the web one does. On the bar,
    // which has no knob, that is the only sign that the grab took at all.
    val line by animateDpAsState(if (held) thickness + 2.dp else thickness, label = "rail")
    val dot by animateDpAsState(if (held) knob * 1.3f else knob, label = "knob")

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            // The knob rides the end of the played part, drawn here because the
            // rounded line below would clip it.
            .drawWithContent {
                drawContent()
                if (dot > 0.dp) {
                    val at = Offset(size.width * fraction().coerceIn(0f, 1f), size.height / 2)
                    drawCircle(colors.accent, radius = dot.toPx() / 2, center = at)
                }
            }
            .then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Claims the finger, so nothing behind the rail takes the
                    // same press for a tap of its own.
                    down.consume()
                    held = true
                    var at = (down.position.x / size.width).coerceIn(0f, 1f)
                    scrubTo(at)
                    val ended = horizontalDrag(down.id) { change ->
                        at = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubTo(at)
                        change.consume()
                    }
                    held = false
                    // A drag another handler took over never ended where the
                    // finger left it, so it is not a seek.
                    if (ended) {
                        // The one confirmation a seek gets: the song jumps
                        // silently, so nothing else says the grab took.
                        haptics.landed()
                        seekTo(at)
                    }
                    scrubTo(null)
                }
            }),
        contentAlignment = if (lineAtTop) Alignment.TopCenter else Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(line)
                .then(if (rounded) Modifier.clip(RoundedCornerShape(line / 2)) else Modifier)
                .background(trackColor)
                .drawBehind {
                    drawRect(bufferColor, size = Size(size.width * buffered.coerceIn(0f, 1f), size.height))
                    drawRect(colors.accent, size = Size(size.width * fraction().coerceIn(0f, 1f), size.height))
                }
        )
    }
}

/**
 * The elapsed time under a rail. Its own composable, so the walking playhead
 * redraws this figure once a second and not the player around it.
 */
@Composable
fun Elapsed(playhead: FloatState, durationMs: Long, style: TextStyle) {
    val text by remember(playhead, durationMs) {
        derivedStateOf { Fmt.duration((playhead.floatValue * durationMs / 1000).toDouble()) }
    }
    Text(text, style = style, color = SonorusTheme.colors.textDim)
}
