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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.download.Offline
import org.sonorus.data.model.Book
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.Routes
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.ConfirmDialog
import org.sonorus.ui.components.Progress
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.components.TrackList
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num

/**
 * What is on this phone.
 *
 * It is a track list like any other, which is the point: these songs play the
 * same way, they are simply the ones that also play with the radio off. The two
 * switches that decide how downloads behave live here rather than in the
 * settings, because this is the page somebody is on when the question comes up.
 */
@UnstableApi
@Composable
fun DownloadsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val state by vm.downloads.state.collectAsState()
    val wifiOnly by vm.downloads.wifiOnly.collectAsState()
    val offline by vm.offline.collectAsState()
    // The switch shows what the *switch* is set to, not whether the app happens
    // to be offline - or tapping a lit chip to turn it off would turn it on.
    val manualOffline by vm.lib.manualOffline.collectAsState()
    val player by vm.player.state.collectAsState()
    var clearing by remember { mutableStateOf(false) }

    // The index is not a flow, but the download state is republished on every
    // change to it - so it is what says when this list has to be built again.
    //
    // Songs only. Since a Hörbuch can be downloaded, the index holds things that
    // are not songs, and a book's forty untitled parts strewn through the song
    // list would bury it - so the spoken word gets its own section above, in the
    // shape its own pages use.
    val snapshot = remember(state.done) { vm.downloads.store.snapshot }
    val tracks = remember(snapshot) {
        Offline.sortTracks(snapshot.tracks.map { it.track }.filterNot { it.isSpoken }, "artist", "asc")
    }
    val audiobooks = remember(snapshot) { Offline.books(snapshot, "book") }
    val dramas = remember(snapshot) { Offline.books(snapshot, "drama") }
    val shows = remember(snapshot) { Offline.podcasts(snapshot).podcasts }
    val spokenTotal = remember(snapshot) { snapshot.tracks.count { it.track.isSpoken } }

    Column(Modifier.fillMaxSize()) {
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == Routes.DOWNLOADS,
            actions = trackActions(vm, tracks, "Downloads", Routes.DOWNLOADS, onGo),
            showAlbum = true,
            numbered = false,
            coverUrl = { vm.coverUrl(it.cover) },
            modifier = Modifier.weight(1f),
            // The header below says it in its own words, and says it once.
            emptyNote = null,
            header = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Weighted, or the line runs on under the button: it
                        // names up to four kinds now and no longer fits on one.
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            RackLabelText("Auf diesem Gerät")
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    // Left out only when something else is
                                    // here to count - "0 Songs" is the right
                                    // thing to say on an empty page.
                                    Fmt.plural(tracks.size, "Song", "Songs")
                                        .takeIf { tracks.isNotEmpty() || spokenTotal == 0 },
                                    Fmt.plural(audiobooks.size, "Hörbuch", "Hörbücher")
                                        .takeIf { audiobooks.isNotEmpty() },
                                    Fmt.plural(dramas.size, "Hörspiel", "Hörspiele")
                                        .takeIf { dramas.isNotEmpty() },
                                    Fmt.plural(shows.sumOf { it.episodeCount }, "Folge", "Folgen")
                                        .takeIf { shows.isNotEmpty() },
                                    Fmt.bytes(state.bytes),
                                    Fmt.durationLong(snapshot.tracks.sumOf { it.track.duration }),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textDim,
                            )
                        }
                        if (tracks.isNotEmpty() || spokenTotal > 0) {
                            SonorusButton("Alle entfernen", danger = true) { clearing = true }
                        }
                    }

                    if (state.busy) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface)
                                .padding(16.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (state.waiting) "Wartet auf WLAN" else state.activeTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    Fmt.plural(state.running, "Song", "Songs"),
                                    style = num(12.sp),
                                    color = colors.textDim,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Progress(
                                done = (state.progress * 100).toInt(),
                                total = 100,
                                indeterminate = state.waiting || state.progress <= 0f,
                            )
                            Spacer(Modifier.height(12.dp))
                            SonorusButton("Abbrechen") { vm.downloads.cancelAll() }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (state.failed.isNotEmpty()) {
                        Text(
                            "${Fmt.plural(state.failed.size, "Song", "Songs")} konnten nicht geladen " +
                                "werden. Der erste Fehler: ${state.failed.values.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        RackLabelText("Verhalten")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Nur über WLAN", wifiOnly) { vm.downloads.setWifiOnly(!wifiOnly) }
                            // The switch Spotify has: stay on what is here even
                            // though there would be a connection.
                            Chip("Offline-Modus", manualOffline) {
                                vm.setOfflineMode(!manualOffline)
                            }
                        }
                        if (offline && !manualOffline) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Gerade offline, weil der Server nicht erreichbar ist.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textFaint,
                            )
                        }
                    }

                    SpokenSection("Hörbücher", audiobooks, vm) { onGo(Routes.book("audiobooks", it)) }
                    SpokenSection("Hörspiele", dramas, vm) { onGo(Routes.book("audiodramas", it)) }
                    if (shows.isNotEmpty()) {
                        RackLabelText("Podcasts", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        for (show in shows) {
                            SpokenRow(
                                title = show.name,
                                subtitle = Fmt.plural(show.episodeCount, "Folge", "Folgen"),
                                meta = Fmt.durationRack(show.duration),
                                coverUrl = vm.coverUrl(show.cover),
                            ) { onGo(Routes.podcast(show.id)) }
                        }
                    }

                    if (tracks.isNotEmpty()) {
                        RackLabelText(
                            "Songs",
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    } else if (spokenTotal == 0) {
                        Text(
                            "Noch nichts heruntergeladen. Auf einem Album, einer Playlist, einem " +
                                "Hörbuch oder im Menü eines Songs steht \"Herunterladen\"; bei einem " +
                                "Podcast steht der Pfeil neben der einzelnen Folge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                }
            },
        )
    }

    if (clearing) {
        ConfirmDialog(
            title = "Alle Downloads entfernen",
            message = "${Fmt.plural(tracks.size + spokenTotal, "Titel", "Titel")} " +
                "(${Fmt.bytes(state.bytes)}) " +
                "werden von diesem Gerät gelöscht. Auf dem Server bleibt alles, wie es ist - " +
                "ohne Verbindung ist danach aber nichts mehr abspielbar.",
            confirmLabel = "Entfernen",
            onDismiss = { clearing = false },
            onConfirm = {
                clearing = false
                vm.clearDownloads()
            },
        )
    }
}

/**
 * One shelf of downloaded titles, or nothing at all when there are none.
 *
 * A book is one row however many files it is made of, exactly as it is on its
 * own page - the parts are the player's business and were never drawn anywhere
 * else, and listing them here would be the one place the app broke that rule.
 */
@UnstableApi
@Composable
private fun SpokenSection(label: String, books: List<Book>, vm: AppViewModel, onOpen: (Int) -> Unit) {
    if (books.isEmpty()) return
    RackLabelText(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    for (book in books) {
        SpokenRow(
            title = book.title,
            subtitle = book.author,
            meta = Fmt.durationRack(book.duration),
            coverUrl = vm.coverUrl(book.cover),
        ) { onOpen(book.id) }
    }
}
