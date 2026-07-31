package eu.flopsyan.sonorus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.flopsyan.sonorus.ui.components.ErrorNote
import eu.flopsyan.sonorus.ui.components.Loading

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
    var value by remember(*key) { mutableStateOf<T?>(null) }
    var error by remember(*key) { mutableStateOf<String?>(null) }
    var loading by remember(*key) { mutableStateOf(true) }
    var attempt by remember(*key) { mutableIntStateOf(0) }

    LaunchedEffect(*key, attempt) {
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
