package org.sonorus.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonorus.data.download.DownloadStatus
import org.sonorus.data.model.Track
import org.sonorus.ui.Fmt
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num

/** What the track menu can do. Assembled by the screen that shows the list. */
data class TrackActions(
    val onPlay: (Int) -> Unit,
    val onPlayNext: (Track) -> Unit,
    val onEnqueue: (Track) -> Unit,
    val onRate: (Track, Int) -> Unit,
    val onAddToPlaylist: (Track) -> Unit,
    val onGoArtist: (Track) -> Unit = {},
    val onGoAlbum: (Track) -> Unit = {},
    val onEdit: (Track) -> Unit = {},
    val onRemove: ((Track) -> Unit)? = null,
    /**
     * The rating to draw. A row is a snapshot of a response, so the stars it
     * carries stop being true the moment one is handed out - the view model
     * answers with what it last set instead.
     */
    val starsOf: (Track) -> Int = { it.stars },
    /** What this phone has of the song - the same reasoning as [starsOf]. */
    val statusOf: (Track) -> DownloadStatus = { DownloadStatus.NONE },
    val onDownload: (Track) -> Unit = {},
    val onCancelDownload: (Track) -> Unit = {},
    val onRemoveDownload: (Track) -> Unit = {},
)

@Composable
fun TrackList(
    tracks: List<Track>,
    currentTrackId: Int?,
    actions: TrackActions,
    modifier: Modifier = Modifier,
    /**
     * Whether the queue that is running was started from *this* list. Handed
     * over ready-made rather than as a key to compare, because the screen
     * already holds the player state that the comparison needs and this
     * component deliberately knows nothing about the player.
     */
    currentFromHere: Boolean = true,
    numbered: Boolean = true,
    showAlbum: Boolean = false,
    showYear: Boolean = false,
    /**
     * Per track rather than per list, which is the only way to get a
     * compilation right: on an ordinary album the name under every row is the
     * one already printed at the top of the page, and on "Various" it is the
     * only thing that tells the twelve rows apart. A single guest track on an
     * otherwise solo record is the same question again, one row at a time.
     */
    showArtist: (Track) -> Boolean = { true },
    coverUrl: (Track) -> String? = { null },
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    header: (@Composable () -> Unit)? = null,
    /**
     * What a row files under for the fast scroller down the right-hand edge.
     * The title by default, because that is what the list is sorted by unless a
     * screen says otherwise - and a bar that says "M" while the rows are sorted
     * by artist would be worse than no bar at all.
     */
    labelOf: (Track) -> String = { scrollLabel(it.title) },
) {
    var menuFor by remember { mutableStateOf<Pair<Int, Track>?>(null) }
    val listState = rememberLazyListState()
    // The header is item 0 where there is one, so a row's place in the list and
    // its place in `tracks` are one apart.
    val offset = if (header != null) 1 else 0
    // Measured off this list, so an album keeps the narrow column and "Alle
    // Songs" gets one wide enough for its four- and five-digit numbers.
    val indexWidth = indexColumnWidth(tracks.size)

    Box(Modifier.fillMaxWidth()) {
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = contentPadding) {
        if (header != null) item { header() }
        if (tracks.isEmpty()) {
            item { EmptyNote("Hier ist noch nichts.") }
        }
        itemsIndexed(tracks, key = { i, t -> t.itemId ?: (t.id.toLong() * 100000 + i) }) { index, track ->
            TrackRow(
                track = track,
                index = if (numbered) index + 1 else null,
                indexWidth = indexWidth,
                isCurrent = track.id == currentTrackId,
                elsewhere = !currentFromHere,
                showAlbum = showAlbum,
                showYear = showYear,
                showArtist = showArtist(track),
                stars = actions.starsOf(track),
                downloaded = actions.statusOf(track) == DownloadStatus.DONE,
                coverUrl = coverUrl(track),
                // A row taken out of a playlist, or a list re-sorted under the
                // finger, moves to its new place instead of the list snapping
                // into a different shape.
                modifier = Modifier.animateItem().padding(horizontal = 6.dp),
                onPlay = { actions.onPlay(index) },
                onMenu = { menuFor = index to track },
            )
        }
    }

    FastScroller(
        itemCount = tracks.size,
        firstVisible = (listState.firstVisibleItemIndex - offset).coerceAtLeast(0),
        visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1),
        labelAt = { tracks.getOrNull(it)?.let(labelOf).orEmpty() },
        onScrollTo = { listState.scrollToItem(it + offset) },
    )
    }

    menuFor?.let { (index, track) ->
        TrackMenu(
            track = track,
            index = index,
            actions = actions,
            onDismiss = { menuFor = null },
        )
    }
}

/**
 * The context menu of a track. A sheet from the bottom edge rather than a
 * dropdown, which is what a touch screen wants - and it is also where rating
 * lives, because a five-star widget does not fit into a row on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(track: Track, index: Int, actions: TrackActions, onDismiss: () -> Unit) {
    val colors = SonorusTheme.colors
    val sheet = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surface,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            track.artist.takeIf { it.isNotEmpty() },
                            track.album.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(Fmt.duration(track.duration), style = num(12.sp), color = colors.textDim)
            }

            // Rating belongs here: below 760 px the web app hides both star
            // widgets, so the menu is the way to hand out a star at all.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RackLabelText("Bewerten")
                Stars(actions.starsOf(track), size = 28) { value ->
                    actions.onRate(track, value)
                    onDismiss()
                }
            }

            if (!track.missing) {
                MenuItem(Icons.Filled.PlayArrow, "Abspielen") {
                    onDismiss(); actions.onPlay(index)
                }
                MenuItem(Icons.Filled.PlaylistPlay, "Als Nächstes spielen") {
                    onDismiss(); actions.onPlayNext(track)
                }
                MenuItem(Icons.AutoMirrored.Filled.QueueMusic, "Zur Warteschlange") {
                    onDismiss(); actions.onEnqueue(track)
                }
            }
            MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Zu Playlist hinzufügen") {
                onDismiss(); actions.onAddToPlaylist(track)
            }
            // A missing file has nothing to download, and the label says what
            // tapping does right now rather than what the feature is called.
            if (!track.missing) {
                when (actions.statusOf(track)) {
                    DownloadStatus.DONE -> MenuItem(Icons.Filled.DownloadDone, "Download entfernen") {
                        onDismiss(); actions.onRemoveDownload(track)
                    }
                    DownloadStatus.RUNNING -> MenuItem(Icons.Filled.Close, "Download abbrechen") {
                        onDismiss(); actions.onCancelDownload(track)
                    }
                    DownloadStatus.QUEUED -> MenuItem(Icons.Filled.Close, "Aus der Warteschlange nehmen") {
                        onDismiss(); actions.onCancelDownload(track)
                    }
                    DownloadStatus.FAILED -> MenuItem(Icons.Filled.Download, "Download erneut versuchen") {
                        onDismiss(); actions.onDownload(track)
                    }
                    DownloadStatus.NONE -> MenuItem(Icons.Filled.Download, "Herunterladen") {
                        onDismiss(); actions.onDownload(track)
                    }
                }
            }
            if (track.artistId != null) {
                MenuItem(Icons.Filled.Person, "Zum Interpreten") {
                    onDismiss(); actions.onGoArtist(track)
                }
            }
            if (track.albumId != null) {
                MenuItem(Icons.Filled.Album, "Zum Album") {
                    onDismiss(); actions.onGoAlbum(track)
                }
            } else {
                // Only a single can be edited on its own: a track of an album
                // takes date, genres and cover from the album.
                MenuItem(Icons.Filled.Edit, "Single bearbeiten") {
                    onDismiss(); actions.onEdit(track)
                }
            }
            actions.onRemove?.let { remove ->
                MenuItem(Icons.Filled.Delete, "Aus Playlist entfernen", danger = true) {
                    onDismiss(); remove(track)
                }
            }
        }
    }
}

/**
 * One row of a bottom-sheet menu. Shared with the player's own sheet in
 * `FullPlayer.kt`, so the two never drift apart in height, padding or tint.
 */
@Composable
fun MenuItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = if (danger) colors.danger else colors.textDim)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) colors.danger else colors.text,
        )
    }
}
