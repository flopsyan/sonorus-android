package eu.flopsyan.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.Album
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.components.CoverChoice
import eu.flopsyan.sonorus.ui.components.CoverField
import eu.flopsyan.sonorus.ui.components.CoverImage
import eu.flopsyan.sonorus.ui.components.SonorusDialog
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

/**
 * The hand edits.
 *
 * Editable is exactly what nobody else can answer: release date, genres and
 * cover. **Not** the title, the artist or the track number - those come from
 * the folder structure, and the next scan would read the folder names again, so
 * an edit there would silently revert.
 *
 * Nothing is written into the music folder; the edit lives in the database and
 * sets a lock so the next scan does not put the file's version back.
 *
 * An album edit belongs to the **album**, not to the songs that are in it at the
 * moment: renaming a file, retagging it or dropping a new one into the folder
 * cannot take it back, and a song that joins later is given the same date, the
 * same genres and the same cover.
 */
@UnstableApi
@Composable
fun EditAlbumDialog(vm: AppViewModel, album: Album, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = SonorusTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shown and taken exactly as precise as it is known - the album page is the
    // only place that prints the full date.
    var date by remember { mutableStateOf(Fmt.releaseDateInput(album.releaseDate)) }
    // The album's own list once it has one, the union of its songs' before that.
    // The server decides which, so the phone and the web app show the same thing.
    var genres by remember { mutableStateOf(album.genres.joinToString(", ")) }
    val cover = remember { CoverChoice() }
    var busy by remember { mutableStateOf(false) }

    SonorusDialog(
        title = "Album bearbeiten",
        onDismiss = onDismiss,
        confirmLabel = if (busy) "Speichert …" else "Speichern",
        confirmEnabled = !busy,
        onConfirm = {
            busy = true
            scope.launch {
                runCatching {
                    vm.api.editAlbum(
                        id = album.id,
                        date = date.trim(),
                        // Sent even when empty: emptying the field is an edit of
                        // its own, and the album keeps it that way.
                        genres = genres.trim(),
                        cover = coverPayload(vm, cover),
                    )
                }.onSuccess {
                    onSaved()
                    onDismiss()
                    vm.say("Album gespeichert.")
                }.onFailure { vm.say(vm.message(it), true) }
                busy = false
            }
        },
    ) {
        Column(
            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DialogField("Erscheinungsdatum", date, placeholder = "17.05.2013, 05.2013 oder 2013") { date = it }
            DialogField("Genres", genres, placeholder = "Mit Komma trennen") { genres = it }
            Text(
                "Datum, Genres und Cover gehören zum Album und gelten für alle seine Songs - " +
                    "auch für später hinzugefügte oder umbenannte.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
            CoverField(
                choice = cover,
                currentUrl = vm.api.coverUrl(album.cover),
                onPicked = { uri ->
                    scope.launch {
                        cover.source = CoverImage.load(context, uri)
                        cover.cleared = false
                        cover.fx = 0.5f
                        cover.fy = 0.5f
                    }
                },
            )
        }
    }
}

/**
 * A single can be edited on its own because it has nobody to take these from -
 * a track that belongs to an album gets date, genres and cover from it, and the
 * server refuses the edit (`not_a_single`) rather than let two sources drift.
 */
@UnstableApi
@Composable
fun EditSingleDialog(vm: AppViewModel, track: Track, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = SonorusTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The dialog takes the *exact* date even though the list only prints the
    // year - otherwise saving would throw the day away.
    var date by remember { mutableStateOf(Fmt.releaseDateInput(track.releaseDate)) }
    var genres by remember { mutableStateOf(track.genres.joinToString(", ")) }
    val cover = remember { CoverChoice() }
    var busy by remember { mutableStateOf(false) }

    SonorusDialog(
        title = "Single bearbeiten",
        onDismiss = onDismiss,
        confirmLabel = if (busy) "Speichert …" else "Speichern",
        confirmEnabled = !busy,
        onConfirm = {
            busy = true
            scope.launch {
                runCatching {
                    vm.api.editSingle(
                        id = track.id,
                        date = date.trim(),
                        genres = genres.trim(),
                        cover = coverPayload(vm, cover),
                    )
                }.onSuccess {
                    onSaved()
                    onDismiss()
                    vm.say("Single gespeichert.")
                }.onFailure { vm.say(vm.message(it), true) }
                busy = false
            }
        },
    ) {
        Column(
            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
            )
            DialogField("Erscheinungsdatum", date, placeholder = "17.05.2013, 05.2013 oder 2013") { date = it }
            DialogField("Genres", genres, placeholder = "Mit Komma trennen") { genres = it }
            CoverField(
                choice = cover,
                currentUrl = vm.api.coverUrl(track.cover),
                onPicked = { uri ->
                    scope.launch {
                        cover.source = CoverImage.load(context, uri)
                        cover.cleared = false
                        cover.fx = 0.5f
                        cover.fy = 0.5f
                    }
                },
            )
        }
    }
}

/**
 * An artist has exactly one editable thing: the profile picture. The name is
 * the folder name and stays uneditable. No lock is needed here, because no scan
 * ever writes that column - an empty one simply falls back to an album cover.
 */
@UnstableApi
@Composable
fun EditArtistDialog(
    vm: AppViewModel,
    artistId: Int,
    currentCover: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cover = remember { CoverChoice() }
    var busy by remember { mutableStateOf(false) }

    SonorusDialog(
        title = "Interpret bearbeiten",
        onDismiss = onDismiss,
        confirmLabel = if (busy) "Speichert …" else "Speichern",
        confirmEnabled = !busy && cover.changed,
        onConfirm = {
            busy = true
            scope.launch {
                val payload = coverPayload(vm, cover)
                if (payload == null) {
                    busy = false
                    return@launch
                }
                runCatching { vm.api.editArtistCover(artistId, payload) }
                    .onSuccess {
                        onSaved()
                        onDismiss()
                        vm.say("Bild gespeichert.")
                    }
                    .onFailure { vm.say(vm.message(it), true) }
                busy = false
            }
        },
    ) {
        CoverField(
            choice = cover,
            currentUrl = vm.api.coverUrl(currentCover),
            onPicked = { uri ->
                scope.launch {
                    cover.source = CoverImage.load(context, uri)
                    cover.cleared = false
                    cover.fx = 0.5f
                    cover.fy = 0.5f
                }
            },
        )
    }
}

/**
 * `null` when nothing about the picture changed (so the field is left out of
 * the patch entirely), `JsonNull` to remove it, otherwise the crop.
 */
@UnstableApi
private fun coverPayload(vm: AppViewModel, choice: CoverChoice) = when {
    choice.hasNew -> choice.payload()?.let { (type, data) -> vm.api.coverPayload(type, data) }
    choice.cleared -> JsonNull
    else -> null
}
