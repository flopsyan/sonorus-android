package eu.flopsyan.sonorus.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

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
 */
@Composable
fun SeekRail(
    fraction: Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    thickness: Dp = 3.dp,
    rounded: Boolean = false,
    knob: Dp = 0.dp,
    lineAtTop: Boolean = false,
) {
    val colors = SonorusTheme.colors
    // The gesture outlives every recomposition, so it must not keep calling the
    // callbacks it was born with.
    val scrubTo by rememberUpdatedState(onScrub)
    val seekTo by rememberUpdatedState(onSeek)
    var held by remember { mutableStateOf(false) }
    // The rail thickens under the finger the way the web one does. On the bar,
    // which has no knob, that is the only sign that the grab took at all.
    val line by animateDpAsState(if (held) thickness + 2.dp else thickness, label = "rail")
    val dot by animateDpAsState(if (held) knob * 1.3f else knob, label = "knob")

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
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
                    if (ended) seekTo(at)
                    scrubTo(null)
                }
            },
        contentAlignment = if (lineAtTop) Alignment.TopCenter else Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(line)
                .then(if (rounded) Modifier.clip(RoundedCornerShape(line / 2)) else Modifier)
                .background(colors.surface3)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(colors.accent)
            )
        }
        // The knob rides the end of the played part. A box as wide as that part,
        // with the dot on its right edge, puts it there without measuring
        // anything - and it hangs outside the rounded line, which would clip it.
        if (dot > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(fraction)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = dot / 2)
                        .requiredSize(dot)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
            }
        }
    }
}
