package eu.flopsyan.sonorus.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

/**
 * Every duration, easing and spring the app moves by, in one place.
 *
 * The app was built almost entirely out of hand-rolled `Row`s and `Box`es rather
 * than stock Material components, which is why it had almost no motion in it:
 * nothing was animated unless it was written out, and only three things ever
 * were. Material3's own `MotionScheme` would not have helped either - it only
 * feeds the stock components this app does not use.
 *
 * So the vocabulary is small and deliberate. Three speeds, and the rule for
 * which is which:
 *
 *  - [Quick] for something that only changes colour or swaps a glyph. Fast
 *    enough that it reads as feedback rather than as an animation.
 *  - [Standard] for a page, a panel, a list settling. The default.
 *  - [Rise] for the one big move - the player growing out of the bar.
 *
 * Anything travelling across the screen uses a spring rather than a duration, so
 * it keeps momentum instead of arriving at a fixed time; anything that only
 * fades uses a tween, because a fade has no momentum to keep.
 */
object Motion {

    const val Quick = 140
    const val Standard = 240
    const val Rise = 380

    /** Material's emphasized curve: leaves quickly, arrives softly. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For something entering that was not on screen before. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Quick, easing = LinearEasing)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(Standard, easing = Emphasized)

    fun <T> entering(): FiniteAnimationSpec<T> = tween(Standard, easing = Decelerate)

    /** What a control does under a thumb: no overshoot, it is a key being pressed. */
    fun <T> press(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** What something moving across the screen does: a little life, no wobble. */
    fun <T> travel(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * A tap target that dips under the finger, the way a physical key does.
 *
 * This is `combinedClickable` plus the one thing that separates an app that
 * feels alive from one that does not: something has to happen the instant the
 * finger lands, before the tap has even been let go. The ripple alone does not
 * do it - it draws *on* the surface, not *to* it.
 *
 * Deliberately no haptic on the long press here: `combinedClickable` already
 * fires `LongPress` itself, and a second one on top is felt as a stutter.
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    dip: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) dip else 1f,
        animationSpec = Motion.press(),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/**
 * The placeholder a list wears while its data is on the way.
 *
 * A spinner says "something is happening"; a shape in the size of the row that
 * is coming says *what*, and the page then fills in rather than replacing
 * itself. That swap - spinner gone, whole page suddenly there - was the single
 * most-seen hard cut in the app, because every screen did it on every visit.
 */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    val colors = SonorusTheme.colors
    val sweep = rememberInfiniteTransition(label = "shimmer")
    val progress by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "sweep",
    )
    return this
        .clip(shape)
        .drawBehind {
            drawRect(colors.surface2)
            val band = size.width * 0.7f
            val x = -band + progress * (size.width + band)
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to colors.surface3,
                    1f to Color.Transparent,
                    start = Offset(x, 0f),
                    end = Offset(x + band, 0f),
                )
            )
        }
}

// --- Haptics ----------------------------------------------------------------
//
// Named for what happened, not for which buzz it is: the caller says "this was
// a switch" and the platform decides what a switch feels like on that phone.
// Long presses are missing on purpose - `combinedClickable` does those itself.

/** A switch that just flipped: shuffle, repeat, play/pause. */
fun HapticFeedback.toggled(on: Boolean) =
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

/** Something was accepted and written down - a star, a confirmed dialog. */
fun HapticFeedback.confirmed() = performHapticFeedback(HapticFeedbackType.Confirm)

/** Stepping from one thing to the next one along: a tab, a row of a picker. */
fun HapticFeedback.stepped() = performHapticFeedback(HapticFeedbackType.SegmentTick)

/** A drag just went far enough to mean something once it is let go. */
fun HapticFeedback.armed() = performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

/** The finger came off and the gesture did what it said it would. */
fun HapticFeedback.landed() = performHapticFeedback(HapticFeedbackType.GestureEnd)
