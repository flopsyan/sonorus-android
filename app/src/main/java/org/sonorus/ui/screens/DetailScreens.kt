package org.sonorus.ui.screens

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.model.Playlist
import org.sonorus.data.model.Track
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Routes
import org.sonorus.ui.components.ConfirmDialog
import org.sonorus.ui.components.CoverMosaic
import org.sonorus.ui.components.albumCovers
import org.sonorus.ui.components.DetailSkeleton
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.MediaCard
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.Section
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.components.TrackList
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.starLabel
import org.sonorus.ui.theme.SonorusTheme

/**
 * The head of a detail page: artwork, title, a quiet line, and the buttons.
 * Shared by everything that is a collection you put on - an album, an artist, a
 * playlist, a star playlist, a genre - which is why it is not private.
 *
 * [coverUrls] is a list rather than one picture: a collection without artwork of
 * its own shows the covers of the first four albums in it (see [CoverMosaic]),
 * and everything with one simply passes a list of one.
 */
@Composable
fun DetailHead(
    title: String,
    subtitle: String,
    coverUrls: List<String>,
    round: Boolean = false,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEdit: (() -> Unit)? = null,
    /** The download control, which every collection has and only its head draws. */
    download: (@Composable () -> Unit)? = null,
) {
    val colors = SonorusTheme.colors
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverMosaic(
                coverUrls,
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
        // Two rows: the transport first, then what is done *with* the collection
        // rather than to it. Four buttons do not fit across a phone.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SonorusButton("Abspielen", primary = true, onClick = onPlay)
            SonorusButton("Zufällig", onClick = onShuffle)
        }
        if (download != null || onEdit != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                download?.invoke()
                onEdit?.let { SonorusButton("Bearbeiten", onClick = it) }
            }
        }
    }
}

/**
 * The download control of a whole collection - an album, a playlist, a genre, a
 * rating, an artist.
 *
 * One button with several states rather than a switch, because there are
 * several different things to say: nothing here yet, part of it, it is coming,
 * it is all here. Taking it back asks first - on a phone a mistap would
 * otherwise throw away an hour of downloading.
 *
 * [playlist] is passed for a playlist and for nothing else. A playlist's order
 * exists nowhere but on the server, so it is stored along with the songs; every
 * other collection can be rebuilt from the songs themselves.
 */
@UnstableApi
@Composable
fun CollectionDownload(vm: AppViewModel, tracks: List<Track>, playlist: Playlist? = null) {
    val state by vm.downloads.state.collectAsState()
    var confirming by remember { mutableStateOf(false) }

    val here = tracks.filter { !it.missing }
    if (here.isEmpty()) return
    val done = here.count { it.id in state.done }
    val busy = here.count { it.id == state.active || it.id in state.queued }
    val start = { if (playlist != null) vm.downloadPlaylist(playlist, here) else vm.download(here) }

    when {
        busy > 0 -> SonorusButton("Lädt … $done/${here.size}", enabled = false) {}
        done == here.size -> SonorusButton("Heruntergeladen") { confirming = true }
        done > 0 -> SonorusButton("Rest laden ($done/${here.size})") { start() }
        else -> SonorusButton("Herunterladen") { start() }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Download entfernen",
            message = "${Fmt.plural(done, "Song", "Songs")} werden von diesem Gerät gelöscht. " +
                "Auf dem Server bleibt alles, wie es ist.",
            confirmLabel = "Entfernen",
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                vm.removeDownloads(here)
            },
        )
    }
}

// --- Album ------------------------------------------------------------------

@UnstableApi
@Composable
fun AlbumScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("album", id) { vm.lib.album(id) }
    val player by vm.player.state.collectAsState()
    val key = Routes.album(id)

    var editing by remember { mutableStateOf(false) }

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val album = data.album
        val tracks = album.tracks
        if (editing) {
            EditAlbumDialog(
                vm = vm,
                album = album,
                onDismiss = { editing = false },
                onSaved = { load.reload() },
            )
        }
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, tracks, "Album: ${album.title}", key, onGo),
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
                    coverUrls = listOfNotNull(vm.coverUrl(album.cover)),
                    onPlay = { vm.player.playTracks(tracks, 0, "Album: ${album.title}", key) },
                    onShuffle = { vm.player.shuffleTracks(tracks, "Album: ${album.title}", key) },
                    onEdit = { editing = true },
                    download = { CollectionDownload(vm, tracks) },
                )
            },
        )
    }
}

// --- Artist -----------------------------------------------------------------

@UnstableApi
@Composable
fun ArtistScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("artist", id) { vm.lib.artist(id) }
    val player by vm.player.state.collectAsState()
    val key = Routes.artist(id)

    var editing by remember { mutableStateOf(false) }

    LoadBox(load, skeleton = { DetailSkeleton(round = true) }) { data ->
        val artist = data.artist
        val tracks = artist.tracks
        if (editing) {
            EditArtistDialog(
                vm = vm,
                artistId = artist.id,
                currentCover = if (artist.hasOwnCover) artist.cover else null,
                onDismiss = { editing = false },
                onSaved = { load.reload() },
            )
        }
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, tracks, artist.name, key, onGo),
            showAlbum = true,
            header = {
                Column {
                    DetailHead(
                        title = artist.name,
                        subtitle = listOf(
                            Fmt.plural(artist.albums.size, "Album", "Alben"),
                            Fmt.plural(tracks.size, "Song", "Songs"),
                        ).joinToString(" · "),
                        coverUrls = listOfNotNull(vm.coverUrl(artist.cover)),
                        round = true,
                        onPlay = { vm.player.playTracks(tracks, 0, artist.name, key) },
                        onShuffle = { vm.player.shuffleTracks(tracks, artist.name, key) },
                        onEdit = { editing = true },
                        download = { CollectionDownload(vm, tracks) },
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
                                        coverUrl = vm.coverUrl(album.cover),
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
                                            coverUrl = vm.coverUrl(artist.singles.firstNotNullOfOrNull { it.cover }),
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
    val load = rememberLoad("singles", id) { vm.lib.artist(id) }
    val player by vm.player.state.collectAsState()
    val key = Routes.artistSingles(id)

    LoadBox(load, skeleton = { DetailSkeleton(round = true) }) { data ->
        val singles = data.artist.singles
        TrackList(
            tracks = singles,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, singles, "${data.artist.name}: Singles", key, onGo),
            // The singles list swaps the always-empty album column for a year.
            showYear = true,
            header = {
                DetailHead(
                    title = "Singles",
                    subtitle = "${data.artist.name} · ${Fmt.plural(singles.size, "Song", "Songs")}",
                    coverUrls = albumCovers(singles).mapNotNull { vm.coverUrl(it) },
                    onPlay = { vm.player.playTracks(singles, 0, "Singles", key) },
                    onShuffle = { vm.player.shuffleTracks(singles, "Singles", key) },
                    download = { CollectionDownload(vm, singles) },
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
    val load = rememberLoad("artist-stars", id) { vm.lib.artist(id) }
    val player by vm.player.state.collectAsState()
    val key = Routes.artistStars(id, values)

    LoadBox(load, skeleton = { DetailSkeleton(round = true) }) { data ->
        val filtered = data.artist.tracks.filter { it.stars in values }
        val ratings = data.artist.tracks.map { it.stars }.distinct()
            .filter { it > 0 || 0 in values }
            .plus(values)
            .distinct()
            .sortedDescending()
        TrackList(
            tracks = filtered,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, filtered, data.artist.name, key, onGo),
            showAlbum = true,
            header = {
                val label = values.sortedDescending().joinToString(", ") { starLabel(it) }
                Column {
                    DetailHead(
                        title = label,
                        subtitle = listOf(
                            data.artist.name,
                            Fmt.plural(filtered.size, "Song", "Songs"),
                            Fmt.durationLong(filtered.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(filtered).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playTracks(filtered, 0, label, key) },
                        onShuffle = { vm.player.shuffleTracks(filtered, label, key) },
                        download = { CollectionDownload(vm, filtered) },
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
    val load = rememberLoad("playlist", id) { vm.lib.playlist(id) }
    val player by vm.player.state.collectAsState()
    val key = Routes.playlist(id)

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val tracks = data.tracks
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(
                vm = vm,
                tracks = tracks,
                source = data.playlist.name,
                sourceKey = key,
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
                    // Counted from the tracks, not from `playlist.trackCount`:
                    // GET /api/playlists/:id answers { playlist, tracks } and
                    // carries neither count, so the head used to read
                    // "0 Songs - 0 Sek." over a list that was plainly not empty.
                    subtitle = listOf(
                        Fmt.plural(tracks.size, "Song", "Songs"),
                        Fmt.durationLong(tracks.sumOf { it.duration }),
                    ).joinToString(" · "),
                    coverUrls = albumCovers(tracks).mapNotNull { vm.coverUrl(it) },
                    onPlay = { vm.player.playTracks(tracks, 0, data.playlist.name, key) },
                    onShuffle = { vm.player.shuffleTracks(tracks, data.playlist.name, key) },
                    // The one collection whose order has to be stored with it.
                    download = { CollectionDownload(vm, tracks, data.playlist) },
                )
            },
        )
    }
}
