package eu.flopsyan.sonorus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.flopsyan.sonorus.ui.components.ErrorNote
import eu.flopsyan.sonorus.ui.components.Loading

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

/** Draws the spinner or the error, and the content once the data is there. */
@Composable
fun <T> LoadBox(load: Load<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val value = load.value
    when {
        value != null -> content(value)
        load.loading -> Loading(modifier)
        load.error != null -> ErrorNote(load.error, modifier, onRetry = load.reload)
    }
}
