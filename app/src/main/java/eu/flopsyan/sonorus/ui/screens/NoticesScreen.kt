package eu.flopsyan.sonorus.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.ImportIssue
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.LoadBox
import eu.flopsyan.sonorus.ui.components.EmptyNote
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.rememberLoad
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import kotlinx.coroutines.launch
import eu.flopsyan.sonorus.ui.components.ServerOnlyNote
import eu.flopsyan.sonorus.ui.LocalOffline

/**
 * The import notices.
 *
 * These are the deliberate centrepiece of the CSV import: an import that
 * silently drops rows is worse than useless, so every unmatched row is kept
 * here until it is dismissed - **or until a later scan finds the file**, which
 * is what "Erneut prüfen" asks the server to do. On a hit the track is added to
 * its original playlist and the notice disappears by itself.
 */
@UnstableApi
@Composable
fun NoticesScreen(vm: AppViewModel) {
    if (LocalOffline.current) return ServerOnlyNote("Die Mitteilungen")
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    var issues by remember { mutableStateOf<List<ImportIssue>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val load = rememberLoad("issues") { vm.api.issues().issues }
    val list = issues ?: load.value

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
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
                            val gone = (list?.size ?: 0) - it.issues.size
                            issues = it.issues
                            vm.say(
                                if (gone > 0) "$gone Mitteilung(en) erledigt."
                                else "Nichts Neues gefunden.",
                            )
                            vm.refreshQuietly()
                        }
                        .onFailure { vm.say(vm.message(it), true) }
                    busy = false
                }
            }
            if (!list.isNullOrEmpty()) {
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

        LoadBox(load) {
            val current = list.orEmpty()
            if (current.isEmpty()) {
                EmptyNote("Keine offenen Mitteilungen. Alles, was importiert wurde, ist zugeordnet.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(current, key = { it.id }) { issue ->
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
