package org.sonorus.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/**
 * What a tapped title does, in three numbers: how long it waits before it starts
 * moving, how fast it then travels, and **how long it stands at its end before
 * the ellipsis comes back**.
 *
 * The hold is the point of doing this by hand. The end of a name is the half
 * that was hidden, so it is the half worth reading, and it used to be on screen
 * only in passing - the text arrived there and the "..." was back in the same
 * moment.
 */
private const val TITLE_VELOCITY_DP = 45f
private const val TITLE_LEAD_MS = 500L
private const val TITLE_HOLD_MS = 2200L

/**
 * A name too long for the room it has, cut with an ellipsis until it is tapped,
 * and then run through once so it can be read.
 *
 * It stays still until it is asked. It used to scroll for ever, unasked, which
 * made the busiest line on the screen the one nobody had asked to move - and a
 * title that fits is not a tap target at all, so tapping it never does nothing.
 *
 * Driven by hand rather than by `basicMarquee`, which cannot be stopped where
 * the reader needs it: one iteration carries the text a whole content width
 * further, so the end of the name is flush right only in passing and there is no
 * way to hold it there.
 *
 * [resetKey] is what counts as a new name - the track id at every call site, so
 * a title left standing at its end goes back to its ellipsis when the song
 * changes under it. The text is a key too: a book keeps its track id across a
 * chapter change but not its title.
 */
@Composable
fun RunningTitle(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    var running by remember(resetKey, text) { mutableStateOf(false) }
    // Not keyed, unlike the rest: this is the width of the box, which has
    // nothing to do with which song is in it. Keyed, it would be set back to 0
    // on every change of song and never refilled - onSizeChanged only speaks
    // when the size it reports really changes, and the box is the same width as
    // it was.
    var fieldPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Measured, not estimated off a character count: the travel has to end
    // exactly where the name does, or the pause at the end pauses on the wrong
    // thing. Measured off the string that is actually drawn and in the style it
    // is drawn in, which is why the style is passed rather than assumed - the
    // full player sets its title at 24 sp and the split-screen strip does not.
    val titlePx = remember(text, style, measurer) {
        measurer.measure(text, style = style, softWrap = false, maxLines = 1).size.width
    }
    val over = (titlePx - fieldPx).coerceAtLeast(0)
    val shift = remember(resetKey, text) { Animatable(0f) }
    LaunchedEffect(running, over) {
        if (!running || over <= 0) {
            shift.snapTo(0f)
            return@LaunchedEffect
        }
        delay(TITLE_LEAD_MS)
        val seconds = with(density) { over.toDp().value } / TITLE_VELOCITY_DP
        shift.animateTo(-over.toFloat(), tween((seconds * 1000).toInt(), easing = LinearEasing))
        delay(TITLE_HOLD_MS)
        running = false
    }
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            // The field, measured on the box rather than on the text: while it
            // runs the text is wider than the field on purpose.
            .onSizeChanged { fieldPx = it.width }
            .then(if (over > 0) Modifier.clickable { running = true } else Modifier)
    ) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = if (running) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                // Laid out whole while it runs and clipped by the box; at rest
                // it is the box's width, so the ellipsis has something to cut.
                .then(
                    if (running) Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    else Modifier.fillMaxWidth()
                )
                .graphicsLayer { translationX = shift.value },
        )
    }
}
