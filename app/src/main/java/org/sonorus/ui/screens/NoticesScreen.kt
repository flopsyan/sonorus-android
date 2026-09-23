package org.sonorus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.model.ImportIssue
import org.sonorus.data.model.MissingTrack
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.components.ConfirmDialog
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.components.Stars
import org.sonorus.ui.Routes
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusTheme
import kotlinx.coroutines.launch
import org.sonorus.ui.components.ServerOnlyNote
import org.sonorus.ui.LocalOffline

/**
 * Two kinds of notice, one page.
 *
 * **Songs whose file is gone** and that a rating or a playlist still holds on
 * to. A scan keeps those rows on purpose - that is what carries a rating across
 * a renamed file - but until now the only trace of them was one number in the
 * scan summary, "2 fehlen, aber bewertet", which says that something is wrong
 * and not what. Florian, 2026-09-22: "ich kann schlecht herausfinden, um welche
 * Lieder es sich handelt."
 *
 * **The import notices**, the deliberate centrepiece of the CSV import: an
 * import that silently drops rows is worse than useless, so every unmatched row
 * is kept here until it is dismissed - **or until a later scan finds the
 * file**, which is what "Erneut prüfen" asks the server to do. On a hit the
 * track is added to its original playlist and the notice disappears by itself.
 */
@UnstableApi
@Composable
fun NoticesScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    if (LocalOffline.current) return ServerOnlyNote("Die Mitteilungen")
    val scope = rememberCoroutineScope()
    var issues by remember { mutableStateOf<List<ImportIssue>?>(null) }
    var missing by remember { mutableStateOf<List<MissingTrack>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf<MissingTrack?>(null) }

    // One load for both: the page is one page, and two spinners over two lists
    // that always arrive together would only say the same thing twice.
    val load = rememberLoad("notices") { vm.api.issues().issues to vm.api.missing().missing }
    val list = issues ?: load.value?.first
    val gone = missing ?: load.value?.second

    LoadBox(load) {
        val current = list.orEmpty()
        val corpses = gone.orEmpty()
        LazyColumn(Modifier.fillMaxSize()) {
            item { NoticeSection("Datei weg, Bewertung geblieben") }
            item {
                NoticeHint(
                    "Songs mit Bewertung oder Playlist-Eintrag, deren Datei der letzte Scan " +
                        "nicht gefunden hat."
                )
            }
            if (corpses.isEmpty()) {
                item { EmptyNote("Nichts vermisst.") }
            } else {
                items(corpses, key = { "missing-${it.id}" }) { track ->
                    MissingCard(
                        track = track,
                        onOpenAlbum = { track.albumId?.let { onGo(Routes.album(it)) } },
                        onDrop = { dropping = track },
                    )
                }
            }

            item { NoticeSection("Aus einem CSV-Import") }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SonorusButton(
                        if (busy) "Wird geprüft …" else "Erneut prüfen",
                        primary = true,
                        enabled = !busy,
                    ) {
                        busy = true
                        scope.launch {
                            runCatching { vm.api.recheckIssues() }
                                .onSuccess {
                                    val done = current.size - it.issues.size
                                    issues = it.issues
                                    vm.say(
                                        if (done > 0) "$done Mitteilung(en) erledigt."
                                        else "Nichts Neues gefunden.",
                                    )
                                    vm.refreshQuietly()
                                }
                                .onFailure { vm.say(vm.message(it), true) }
                            busy = false
                        }
                    }
                    if (current.isNotEmpty()) {
                        SonorusButton("Alle verwerfen", danger = true) {
                            scope.launch {
                                runCatching { vm.api.clearIssues() }
                                    .onSuccess {
                                        issues = emptyList()
                                        vm.refreshQuietly()
                                    }
                                    .onFailure { vm.say(vm.message(it), true) }
                            }
                        }
                    }
                }
            }
            if (current.isEmpty()) {
                item {
                    EmptyNote("Alles zugeordnet, was importiert wurde.")
                }
            } else {
                items(current, key = { "issue-${it.id}" }) { issue ->
                    NoticeCard(issue) {
                        scope.launch {
                            runCatching { vm.api.dismissIssue(issue.id) }
                                .onSuccess {
                                    issues = current.filterNot { it.id == issue.id }
                                    vm.refreshQuietly()
                                }
                                .onFailure { vm.say(vm.message(it), true) }
                        }
                    }
                }
            }
        }
    }

    dropping?.let { track ->
        ConfirmDialog(
            title = "Eintrag entfernen",
            // What it costs, exactly: the rating and the playlist places, never
            // the minutes - the row itself only goes when nothing was listening
            // to it. See the model note in the server's models/missing.js.
            message = "Die Bewertung von \"${track.title}\" und seine Plätze in deinen " +
                "Playlists werden gelöscht. Damit verschwindet der Song überall aus Sonorus. " +
                "Was du von ihm gehört hast, bleibt in der Statistik. Das lässt sich nicht " +
                "rückgängig machen.",
            confirmLabel = "Entfernen",
            onDismiss = { dropping = null },
            onConfirm = {
                dropping = null
                scope.launch {
                    runCatching { vm.api.dropMissing(track.id) }
                        .onSuccess {
                            missing = it.missing
                            vm.refreshQuietly()
                        }
                        .onFailure { vm.say(vm.message(it), true) }
                }
            },
        )
    }
}

@Composable
private fun NoticeSection(label: String) {
    RackLabelText(label, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
}

@Composable
private fun NoticeHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = SonorusTheme.colors.textDim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * One song whose file is gone. The stars are drawn rather than counted in
 * words, because they are the thing being let go of; the record is a link only
 * where it still has files of its own to show.
 */
@Composable
private fun MissingCard(track: MissingTrack, onOpenAlbum: () -> Unit, onDrop: () -> Unit) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                RackLabelText(
                    listOf("Fehlt seit ${Fmt.date(track.missingAt)}")
                        .plus(track.playlists)
                        .joinToString(" · ")
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.artist.isNotEmpty()) {
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track.album.isNotEmpty()) {
                        if (track.artist.isNotEmpty()) {
                            Text(
                                " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textDim,
                            )
                        }
                        Text(
                            track.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (track.albumId != null) colors.accent else colors.textDim,
                            textDecoration = if (track.albumId != null) TextDecoration.Underline
                            else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (track.albumId != null) {
                                Modifier.clickable(onClick = onOpenAlbum)
                            } else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    track.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Stars(track.stars, enabled = false)
                Spacer(Modifier.height(8.dp))
                SonorusButton("Entfernen", danger = true, onClick = onDrop)
            }
        }
    }
}

@Composable
private fun NoticeCard(issue: ImportIssue, onDismiss: () -> Unit) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                RackLabelText(issue.currentPlaylistName ?: issue.playlistName)
                Spacer(Modifier.height(6.dp))
                Text(
                    issue.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val second = listOfNotNull(
                    issue.artists.takeIf { it.isNotEmpty() },
                    issue.album.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (second.isNotEmpty()) {
                    Text(
                        second,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nicht gefunden · ${Fmt.dateTime(issue.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    "Verwerfen",
                    tint = colors.textDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
