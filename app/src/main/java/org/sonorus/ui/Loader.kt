package org.sonorus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.sonorus.ui.components.ErrorNote
import org.sonorus.ui.components.Loading

/**
 * Whether the screens are being answered out of the downloads rather than by
 * the server.
 *
 * It is a composition local because *every* load depends on it and none of them
 * would otherwise say so: switching between the two sources has to refetch, not
 * redraw, or the library would keep showing whichever answer came first. Making
 * it part of [rememberLoad]'s key in one place is the only way that cannot be
 * forgotten at a call site.
 */
val LocalOffline = compositionLocalOf { false }

/** What a screen is doing while it fetches its data. */
class Load<T> internal constructor(
    val value: T?,
    val error: String?,
    val loading: Boolean,
    val reload: () -> Unit,
)

/**
 * Fetches once per [key] and hands the screen its data, an error or a spinner.
 * Keeping this in one place is what stops every screen from growing its own
 * slightly different loading state.
 */
@Composable
fun <T> rememberLoad(vararg key: Any?, fetch: suspend () -> T): Load<T> {
    // Which source answers is part of every key, whether the screen says so or
    // not - see [LocalOffline].
    val keys = arrayOf(LocalOffline.current, *key)
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var attempt by remember(*keys) { mutableIntStateOf(0) }

    LaunchedEffect(*keys, attempt) {
        loading = true
        error = null
        runCatching { fetch() }
            .onSuccess { value = it }
            .onFailure { error = it.message ?: "Der Server ist nicht erreichbar." }
        loading = false
    }
    return Load(value, error, loading) { attempt++ }
}

/** Which of the three things a screen is showing. */
private enum class Phase { WAITING, READY, FAILED }

/**
 * Draws the placeholder or the error, and the content once the data is there.
 *
 * The three fade into each other rather than replacing each other outright. This
 * is the most-seen transition in the whole app - every screen goes through it on
 * every visit - and it used to be a hard cut from a spinner to a full page.
 *
 * [skeleton] is what to draw while waiting. A screen that knows the shape of
 * what is coming should pass one: an outline in the size of the rows fills in,
 * where a spinner can only vanish. Screens with nothing predictable to draw
 * leave it out and get the spinner.
 */
@Composable
fun <T> LoadBox(
    load: Load<T>,
    modifier: Modifier = Modifier,
    skeleton: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val phase = when {
        load.value != null -> Phase.READY
        load.loading -> Phase.WAITING
        else -> Phase.FAILED
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            // The page rises the last few pixels as it arrives, which is what
            // makes it read as arriving rather than as being switched on.
            (fadeIn(Motion.entering()) + slideInVertically(Motion.standard()) { it / 24 })
                .togetherWith(fadeOut(Motion.quick()))
        },
        modifier = modifier,
        label = "load",
    ) { shown ->
        when (shown) {
            Phase.READY -> load.value?.let { content(it) }
            Phase.WAITING -> if (skeleton != null) skeleton() else Loading()
            Phase.FAILED -> ErrorNote(load.error.orEmpty(), onRetry = load.reload)
        }
    }
}
