package eu.flopsyan.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.LoadBox
import eu.flopsyan.sonorus.ui.Routes
import eu.flopsyan.sonorus.ui.components.Cover
import eu.flopsyan.sonorus.ui.components.EmptyNote
import eu.flopsyan.sonorus.ui.components.MediaCard
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.Section
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.components.TrackList
import eu.flopsyan.sonorus.ui.rememberLoad
import eu.flopsyan.sonorus.ui.starLabel
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

/** The head of a detail page: artwork, title, a quiet line, and the buttons. */
@Composable
private fun DetailHead(
    title: String,
    subtitle: String,
    coverUrl: String?,
    round: Boolean = false,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Cover(
                coverUrl,
                Modifier.size(112.dp),
                if (round) CircleShape else RoundedCornerShape(10.dp),
                title,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SonorusButton("Abspielen", primary = true, onClick = onPlay)
            SonorusButton("Zufällig", onClick = onShuffle)
        }
    }
}

// --- Album ------------------------------------------------------------------

@UnstableApi
@Composable
fun AlbumScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("album", id) { vm.api.album(id) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        val album = data.album
        val tracks = album.tracks
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, tracks, "Album: ${album.title}", onGo),
            header = {
                DetailHead(
                    title = album.title,
                    // The album page is the only place that prints the full
                    // date - everywhere else there is only room for the year.
                    subtitle = listOfNotNull(
                        album.artist.takeIf { it.isNotEmpty() },
                        Fmt.releaseDate(album.releaseDate).takeIf { it.isNotEmpty() },
                        Fmt.plural(album.trackCount, "Song", "Songs"),
                        Fmt.durationLong(album.duration),
                    ).joinToString(" · "),
                    coverUrl = vm.api.coverUrl(album.cover),
                    onPlay = { vm.player.playTracks(tracks, 0, "Album: ${album.title}") },
                    onShuffle = {
                        vm.player.setShuffle(true)
                        vm.player.playTracks(tracks, 0, "Album: ${album.title}")
                    },
                )
            },
        )
    }
}

// --- Artist -----------------------------------------------------------------

@UnstableApi
@Composable
fun ArtistScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("artist", id) { vm.api.artist(id) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        val artist = data.artist
        val tracks = artist.tracks
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, tracks, artist.name, onGo),
            showAlbum = true,
            header = {
                Column {
                    DetailHead(
                        title = artist.name,
                        subtitle = listOf(
                            Fmt.plural(artist.albums.size, "Album", "Alben"),
                            Fmt.plural(tracks.size, "Song", "Songs"),
                        ).joinToString(" · "),
                        coverUrl = vm.api.coverUrl(artist.cover),
                        round = true,
                        onPlay = { vm.player.playTracks(tracks, 0, artist.name) },
                        onShuffle = {
                            vm.player.setShuffle(true)
                            vm.player.playTracks(tracks, 0, artist.name)
                        },
                    )

                    // Only the ratings this artist actually has get a switch -
                    // one leading to an empty list would be noise.
                    val ratings = tracks.map { it.stars }.distinct().sortedDescending()
                    if (ratings.any { it > 0 }) {
                        Section("Nach Bewertung") {
                            PickerRow(
                                items = ratings.filter { it > 0 }.map { it to starLabel(it) },
                                selected = emptyList(),
                                onPick = { onGo(Routes.artistStars(id, it)) },
                            )
                        }
                    }

                    if (artist.albums.isNotEmpty() || artist.singles.isNotEmpty()) {
                        Section("Alben") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(artist.albums, key = { it.id }) { album ->
                                    MediaCard(
                                        title = album.title,
                                        subtitle = Fmt.year(album.releaseDate, album.year),
                                        coverUrl = vm.api.coverUrl(album.cover),
                                        modifier = Modifier.width(140.dp),
                                    ) { onGo(Routes.album(album.id)) }
                                }
                                // Files lying directly in the artist folder get
                                // their own card; they never count as an album.
                                if (artist.singles.isNotEmpty()) {
                                    item {
                                        MediaCard(
                                            title = "Singles",
                                            subtitle = Fmt.plural(artist.singles.size, "Song", "Songs"),
                                            coverUrl = vm.api.coverUrl(artist.singles.firstNotNullOfOrNull { it.cover }),
                                            modifier = Modifier.width(140.dp),
                                        ) { onGo(Routes.artistSingles(id)) }
                                    }
                                }
                            }
                        }
                    }
                    RackLabelText("Alle Songs", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            },
        )
    }
}

/** The singles of an artist: no album, so they carry their own year. */
@UnstableApi
@Composable
fun ArtistSinglesScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("singles", id) { vm.api.artist(id) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        val singles = data.artist.singles
        TrackList(
            tracks = singles,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, singles, "${data.artist.name}: Singles", onGo),
            // The singles list swaps the always-empty album column for a year.
            showYear = true,
            header = {
                DetailHead(
                    title = "Singles",
                    subtitle = "${data.artist.name} · ${Fmt.plural(singles.size, "Song", "Songs")}",
                    coverUrl = vm.api.coverUrl(singles.firstNotNullOfOrNull { it.cover }),
                    onPlay = { vm.player.playTracks(singles, 0, "Singles") },
                    onShuffle = {
                        vm.player.setShuffle(true)
                        vm.player.playTracks(singles, 0, "Singles")
                    },
                )
            },
        )
    }
}

/**
 * One artist's tracks narrowed to a rating. This is a filter of the artist
 * answer rather than a query of its own - the page already fetched every track
 * with its stars, so the selection is applied here and the order stays the
 * artist page's (newest album first), not the star playlist's.
 */
@UnstableApi
@Composable
fun ArtistStarsScreen(vm: AppViewModel, id: Int, values: List<Int>, onGo: (String) -> Unit) {
    val load = rememberLoad("artist-stars", id) { vm.api.artist(id) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        val filtered = data.artist.tracks.filter { it.stars in values }
        val ratings = data.artist.tracks.map { it.stars }.distinct()
            .filter { it > 0 || 0 in values }
            .plus(values)
            .distinct()
            .sortedDescending()
        TrackList(
            tracks = filtered,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, filtered, data.artist.name, onGo),
            showAlbum = true,
            header = {
                Column {
                    Text(
                        data.artist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonorusTheme.colors.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    PickerRow(
                        items = ratings.map { it to starLabel(it) },
                        selected = values,
                        onPick = { onGo(Routes.artistStars(id, it)) },
                    )
                }
            },
        )
    }
}

// --- Playlist ---------------------------------------------------------------

@UnstableApi
@Composable
fun PlaylistScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("playlist", id) { vm.api.playlist(id) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        val tracks = data.tracks
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(
                vm = vm,
                tracks = tracks,
                source = data.playlist.name,
                onGo = onGo,
                onRemove = { track ->
                    track.itemId?.let { item ->
                        vm.removeFromPlaylist(id, item) { load.reload() }
                    }
                },
            ),
            showAlbum = true,
            header = {
                DetailHead(
                    title = data.playlist.name,
                    subtitle = listOf(
                        Fmt.plural(data.playlist.trackCount, "Song", "Songs"),
                        Fmt.durationLong(data.playlist.duration),
                    ).joinToString(" · "),
                    coverUrl = vm.api.coverUrl(tracks.firstNotNullOfOrNull { it.cover }),
                    onPlay = { vm.player.playTracks(tracks, 0, data.playlist.name) },
                    onShuffle = {
                        vm.player.setShuffle(true)
                        vm.player.playTracks(tracks, 0, data.playlist.name)
                    },
                )
            },
        )
    }
}
