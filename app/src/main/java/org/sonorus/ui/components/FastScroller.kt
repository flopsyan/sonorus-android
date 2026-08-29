package org.sonorus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sonorus.ui.Motion
import org.sonorus.ui.theme.SonorusTheme
import java.text.Normalizer

/**
 * The bar down the right-hand edge of a long list, and the letter it shows
 * while it is held.
 *
 * A library of 2761 songs cannot be reached by flinging. Every list app answers
 * this the same way and so does this one: a thumb that can be dragged, and -
 * because a thumb alone tells you how far down you are but not *where* - a large
 * letter under the finger saying which part of the alphabet is passing.
 *
 * The letter is deliberately whatever the row really starts with rather than a
 * fixed A-Z ladder. A library has songs starting with a digit, a bracket and a
 * quotation mark, and a ladder that only knows letters either hides them or
 * files them under a letter they have nothing to do with. Accents are folded
 * (Ä reads as A, É as E) because that is where the sort puts them anyway.
 *
 * Drawn only where it earns its place: a list short enough to scroll by hand
 * gets nothing, which is what [MIN_ITEMS] decides.
 */
@Composable
fun BoxScope.FastScroller(
    /** How many rows there are in total. */
    itemCount: Int,
    /** The first row currently on screen. */
    firstVisible: Int,
    /** How many rows fit on screen, so the thumb can be sized honestly. */
    visibleCount: Int,
    /** The letter that stands for row `index`. Empty hides the bubble. */
    labelAt: (Int) -> String,
    /** Jump to a row. Suspending because both lazy states scroll that way. */
    onScrollTo: suspend (Int) -> Unit,
) {
    if (itemCount < MIN_ITEMS) return

    val colors = SonorusTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableFloatStateOf(0f) }
    // Where the finger is, as a fraction of the track. While nothing is held the
    // thumb follows the list instead.
    var held by remember { mutableFloatStateOf(0f) }

    val scrollable = (itemCount - visibleCount).coerceAtLeast(1)
    val fraction = if (dragging) held else (firstVisible.toFloat() / scrollable).coerceIn(0f, 1f)
    val shown = (fraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)

    fun jumpTo(fractionOfTrack: Float) {
        held = fractionOfTrack.coerceIn(0f, 1f)
        val target = (held * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
        scope.launch { onScrollTo(target) }
    }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(TOUCH_WIDTH)
            .pointerInput(itemCount) {
                trackHeight = size.height.toFloat()
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        jumpTo(start.y / size.height.toFloat())
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    jumpTo(change.position.y / size.height.toFloat())
                }
            }
    ) {
        // The thumb. A hairline at rest so it does not compete with the rows,
        // and the accent while it is held.
        val thumbHeight = THUMB_HEIGHT
        val travel = with(density) {
            ((trackHeight - thumbHeight.toPx()) * fraction).coerceAtLeast(0f).toDp()
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = travel)
                .padding(end = 3.dp)
                .size(width = if (dragging) 5.dp else 3.dp, height = thumbHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(if (dragging) colors.accent else colors.line)
        )

        // The letter, large, beside the thumb rather than under the thumb: a
        // finger on the edge of the screen covers exactly the place it would
        // otherwise be drawn.
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(Motion.entering()) + scaleIn(Motion.entering(), initialScale = 0.8f),
            exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).offset(y = travel - BUBBLE_LIFT),
        ) {
            val label = labelAt(shown)
            if (label.isNotEmpty()) {
                Box(
                    Modifier
                        .offset(x = -BUBBLE_OFFSET)
                        .size(BUBBLE_SIZE)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.accentInk,
                    )
                }
            }
        }
    }
}

/**
 * What a row files under: its first character, upper-cased, with the accent
 * taken off. Anything that is not a letter or a digit stays as it is, because a
 * title really beginning with "(" is best found under "(".
 */
fun scrollLabel(text: String): String {
    val first = text.trim().firstOrNull() ?: return ""
    val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
        .firstOrNull { it.isLetterOrDigit() } ?: first
    return folded.uppercaseChar().toString()
}

private val TOUCH_WIDTH = 28.dp
private val THUMB_HEIGHT = 44.dp
private val BUBBLE_SIZE = 64.dp
private val BUBBLE_OFFSET = 74.dp
private val BUBBLE_LIFT = 10.dp

/** Below this a list is short enough to scroll by hand, and the bar is clutter. */
private const val MIN_ITEMS = 40
