package eu.flopsyan.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.Album
import eu.flopsyan.sonorus.data.model.SortPref
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.LoadBox
import eu.flopsyan.sonorus.ui.Routes
import eu.flopsyan.sonorus.ui.components.Chip
import eu.flopsyan.sonorus.ui.components.albumCovers
import eu.flopsyan.sonorus.ui.components.EmptyNote
import eu.flopsyan.sonorus.ui.components.Loading
import eu.flopsyan.sonorus.ui.components.MediaCard
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.Section
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.components.TrackActions
import eu.flopsyan.sonorus.ui.components.TrackList
import eu.flopsyan.sonorus.ui.rememberLoad
import eu.flopsyan.sonorus.ui.starLabel
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num

/** Builds the standard set of row actions for a list of tracks. */
@UnstableApi
@Composable
fun trackActions(
    vm: AppViewModel,
    tracks: List<Track>,
    source: String,
    onGo: (String) -> Unit,
    onRemove: ((Track) -> Unit)? = null,
): TrackActions {
    // Read here, so a row redraws the moment its download finishes - the same
    // reasoning the ratings map follows.
    val downloads by vm.downloads.state.collectAsState()
    return TrackActions(
        onPlay = { index -> vm.player.playTracks(tracks, index, source) },
        onPlayNext = { vm.player.playNext(listOf(it)) },
        onEnqueue = { vm.player.enqueue(listOf(it)) },
        // The current rating is the one the view model knows, not the one the row
        // was fetched with - otherwise tapping the star a song already has would
        // fail to clear it as soon as that star was given on this phone.
        onRate = { track, value -> vm.rate(track.id, value, vm.starsOf(track)) },
        onAddToPlaylist = { vm.askForPlaylist(it) },
        onGoArtist = { it.artistId?.let { id -> onGo(Routes.artist(id)) } },
        onGoAlbum = { it.albumId?.let { id -> onGo(Routes.album(id)) } },
        onEdit = { vm.editSingle(it) },
        onRemove = onRemove,
        starsOf = { vm.starsOf(it) },
        statusOf = { downloads.statusOf(it.id) },
        onDownload = { vm.download(listOf(it)) },
        onCancelDownload = { vm.downloads.cancel(it.id) },
        onRemoveDownload = { vm.removeDownloads(listOf(it)) },
    )
}

// --- Home -------------------------------------------------------------------

@UnstableApi
@Composable
fun HomeScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("home") { vm.lib.home() }
    LoadBox(load) { data ->
        val playing = vm.player.state
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SonorusButton("Zufällig abspielen", primary = true, modifier = Modifier.weight(1f)) {
                        vm.shufflePlay()
                    }
                    // The other reason to start a random run: rating a library
                    // is done by ear, and picking the next unrated song out of
                    // a list of a few thousand by hand is what makes it stop
                    // happening. Only offered while there is anything left.
                    if (data.unrated > 0) {
                        SonorusButton("Unbewertete", modifier = Modifier.weight(1f)) {
                            vm.shufflePlay(unrated = true)
                        }
                    }
                }
            }
            if (data.recentlyPlayed.isNotEmpty()) {
                item { Shelf("Zuletzt gehört", data.recentlyPlayed, vm, onGo) }
            }
            if (data.recentlyAdded.isNotEmpty()) {
                item { Shelf("Zuletzt hinzugefügt", data.recentlyAdded, vm, onGo) }
            }
            if (data.mostPlayed.isNotEmpty()) {
                item { Shelf("Meistgehört", data.mostPlayed, vm, onGo) }
            }
            if (data.newestAlbums.isNotEmpty()) {
                item {
                    Section("Neueste Alben") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(data.newestAlbums, key = { it.id }) { album ->
                                MediaCard(
                                    title = album.title,
                                    subtitle = listOfNotNull(
                                        album.artist.takeIf { it.isNotEmpty() },
                                        Fmt.year(album.releaseDate, album.year).takeIf { it.isNotEmpty() },
                                    ).joinToString(" · "),
                                    coverUrl = vm.coverUrl(album.cover),
                                    modifier = Modifier.width(150.dp),
                                ) { onGo(Routes.album(album.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun Shelf(label: String, tracks: List<Track>, vm: AppViewModel, onGo: (String) -> Unit) {
    Section(label) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                MediaCard(
                    title = track.title,
                    subtitle = track.artist,
                    coverUrl = vm.coverUrl(track.cover),
                    modifier = Modifier.width(150.dp),
                ) {
                    vm.player.playTracks(tracks, tracks.indexOf(track), label)
                }
            }
        }
    }
}

// --- All songs --------------------------------------------------------------

private val TRACK_SORTS = listOf(
    "title" to "Titel",
    "artist" to "Interpret",
    "album" to "Album",
    "year" to "Jahr",
    "duration" to "Dauer",
    "added" to "Hinzugefügt",
    "stars" to "Bewertung",
)

@UnstableApi
@Composable
fun TracksScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    // The URL wins over the saved preference in the web app; here the screen
    // simply starts from what the account remembers.
    var sort by remember { mutableStateOf(vm.prefs.trackSort.key) }
    var dir by remember { mutableStateOf(vm.prefs.trackSort.dir) }
    val load = rememberLoad("tracks", sort, dir) { vm.lib.tracks(sort = sort, dir = dir, limit = 5000) }
    val player by vm.player.state.collectAsState()

    LoadBox(load) { data ->
        TrackList(
            tracks = data.tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, data.tracks, "Alle Songs", onGo),
            showAlbum = true,
            header = {
                SortRow(
                    options = TRACK_SORTS,
                    sort = sort,
                    dir = dir,
                    total = data.total,
                    onPick = { key, direction ->
                        sort = key
                        dir = direction
                        vm.saveSort("trackSort", SortPref(key, direction))
                    },
                )
            },
        )
    }
}

@Composable
private fun SortRow(
    options: List<Pair<String, String>>,
    sort: String,
    dir: String,
    total: Int,
    onPick: (String, String) -> Unit,
) {
    val colors = SonorusTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RackLabelText(Fmt.plural(total, "Song", "Songs"))
        Box {
            SonorusButton(
                text = (options.firstOrNull { it.first == sort }?.second ?: "Titel") +
                    if (dir == "desc") " ↓" else " ↑",
            ) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // Both directions stated outright, rather than an arrow button
                // next to the name - "Jahr ↑" does not say which end it means.
                for ((key, label) in options) {
                    for (direction in listOf("asc", "desc")) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label + if (direction == "asc") " A-Z" else " Z-A",
                                    color = if (key == sort && direction == dir) colors.accent else colors.text,
                                )
                            },
                            onClick = {
                                open = false
                                onPick(key, direction)
                            },
                        )
                    }
                }
            }
        }
    }
}

// --- Artists ----------------------------------------------------------------

@UnstableApi
@Composable
fun ArtistsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("artists") { vm.lib.artists() }
    LoadBox(load) { data ->
        if (data.artists.isEmpty()) return@LoadBox EmptyNote("Noch keine Interpreten.")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp),
        ) {
            items(data.artists, key = { it.id }) { artist ->
                MediaCard(
                    title = artist.name,
                    subtitle = Fmt.plural(artist.trackCount, "Song", "Songs"),
                    coverUrl = vm.coverUrl(artist.cover),
                    round = true,
                ) { onGo(Routes.artist(artist.id)) }
            }
        }
    }
}

// --- Albums -----------------------------------------------------------------

private val ALBUM_SORTS = listOf(
    "title" to "Titel",
    "artist" to "Interpret",
    "year" to "Jahr",
    "tracks" to "Songs",
)

@UnstableApi
@Composable
fun AlbumsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    var sort by remember { mutableStateOf(vm.prefs.albumSort.key) }
    var dir by remember { mutableStateOf(vm.prefs.albumSort.dir) }
    val load = rememberLoad("albums", sort, dir) { vm.lib.albums(sort = sort, dir = dir) }

    LoadBox(load) { data ->
        Column(Modifier.fillMaxSize()) {
            SortRow(ALBUM_SORTS, sort, dir, data.albums.size) { key, direction ->
                sort = key
                dir = direction
                vm.saveSort("albumSort", SortPref(key, direction))
            }
            AlbumGrid(data.albums, vm, onGo)
        }
    }
}

@UnstableApi
@Composable
fun AlbumGrid(albums: List<Album>, vm: AppViewModel, onGo: (String) -> Unit) {
    if (albums.isEmpty()) return EmptyNote("Noch keine Alben.")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            MediaCard(
                title = album.title,
                subtitle = listOfNotNull(
                    album.artist.takeIf { it.isNotEmpty() },
                    Fmt.year(album.releaseDate, album.year).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                coverUrl = vm.coverUrl(album.cover),
            ) { onGo(Routes.album(album.id)) }
        }
    }
}

// --- Genres -----------------------------------------------------------------

@UnstableApi
@Composable
fun GenresScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("genres") { vm.lib.genres() }
    LoadBox(load) { data ->
        if (data.genres.isEmpty()) {
            return@LoadBox EmptyNote(
                "Noch keine Genres. Sie kommen aus den Tags der Dateien - " +
                    "oder du vergibst sie von Hand am Album oder an einer Single."
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp),
        ) {
            items(data.genres, key = { it.id }) { genre ->
                MediaCard(
                    title = genre.name,
                    subtitle = Fmt.plural(genre.trackCount, "Song", "Songs"),
                    // The same artwork the genre's own page carries, so the grid
                    // and the page it leads to introduce it the same way.
                    coverUrls = genre.covers.mapNotNull { vm.coverUrl(it) },
                ) { onGo(Routes.genre(listOf(genre.id))) }
            }
        }
    }
}

/**
 * One combined list for a selection of genres, with a switch per genre above
 * it - `/genres/1,4` is one list, not two. The last switch cannot be turned
 * off, because an empty selection has nothing to show.
 */
@UnstableApi
@Composable
fun GenreScreen(vm: AppViewModel, ids: List<Int>, onGo: (String) -> Unit) {
    val selection = rememberLoad("genre", ids.joinToString(",")) { vm.lib.genre(ids) }
    val all = rememberLoad("all-genres") { vm.lib.genres() }
    val player by vm.player.state.collectAsState()

    LoadBox(selection) { data ->
        TrackList(
            tracks = data.genre.tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, data.genre.tracks, data.genre.name, onGo),
            showAlbum = true,
            header = {
                Column {
                    // The same head an album gets: a genre is a collection you
                    // put on, so it is introduced like one.
                    DetailHead(
                        title = data.genre.name,
                        subtitle = listOf(
                            Fmt.plural(data.genre.tracks.size, "Song", "Songs"),
                            Fmt.durationLong(data.genre.tracks.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(data.genre.tracks).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playTracks(data.genre.tracks, 0, data.genre.name) },
                        onShuffle = {
                            vm.player.setShuffle(true)
                            vm.player.playTracks(data.genre.tracks, 0, data.genre.name)
                        },
                        download = { CollectionDownload(vm, data.genre.tracks) },
                    )
                    all.value?.genres?.let { genres ->
                        PickerRow(
                            // The switches that are on come first: a library of a
                            // hundred genres makes a row that scrolls a long way,
                            // and a switch you cannot see is one you cannot turn
                            // off again. `sortedBy` is stable, so both halves keep
                            // the alphabetical order the server sends.
                            items = genres
                                .sortedBy { it.id !in data.genre.ids }
                                .map { it.id to it.name },
                            selected = data.genre.ids,
                            onPick = { onGo(Routes.genre(it)) },
                        )
                    }
                }
            },
        )
    }
}

/**
 * The switch row shared by the genre and the rating pickers. Clicking a switch
 * that is on removes it from the selection - unless it is the last one left.
 */
@Composable
fun PickerRow(
    items: List<Pair<Int, String>>,
    selected: List<Int>,
    counts: Map<Int, Int> = emptyMap(),
    onPick: (List<Int>) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.first }) { (id, label) ->
            val on = id in selected
            Chip(label = label, selected = on, count = counts[id]) {
                val next = if (on) selected - id else selected + id
                if (next.isNotEmpty()) onPick(next.sortedDescending())
            }
        }
    }
}

// --- Star playlists ---------------------------------------------------------

@UnstableApi
@Composable
fun StarsScreen(vm: AppViewModel, values: List<Int>, onGo: (String) -> Unit) {
    val load = rememberLoad("stars", values.joinToString(",")) { vm.lib.stars(values) }
    val player by vm.player.state.collectAsState()
    val counts = vm.bootstrap?.stars?.mapKeys { it.key.toIntOrNull() ?: -1 } ?: emptyMap()

    LoadBox(load) { data ->
        val title = values.sortedDescending().joinToString(", ") { starLabel(it) }
        TrackList(
            tracks = data.tracks,
            currentTrackId = player.current?.id,
            actions = trackActions(vm, data.tracks, title, onGo),
            showAlbum = true,
            header = {
                Column {
                    // A star playlist is a playlist, so it is introduced like
                    // one: the covers of the first four albums in it next to
                    // what the selection adds up to.
                    DetailHead(
                        title = title,
                        subtitle = listOf(
                            Fmt.plural(data.tracks.size, "Song", "Songs"),
                            Fmt.durationLong(data.tracks.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(data.tracks).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playTracks(data.tracks, 0, title) },
                        onShuffle = {
                            vm.player.setShuffle(true)
                            vm.player.playTracks(data.tracks, 0, title)
                        },
                        download = { CollectionDownload(vm, data.tracks) },
                    )
                    PickerRow(
                        items = listOf(5, 4, 3, 2, 1, 0).map { it to starLabel(it) },
                        selected = values,
                        counts = counts,
                        onPick = { onGo(Routes.stars(it)) },
                    )
                }
            },
        )
    }
}

// --- Search -----------------------------------------------------------------

@UnstableApi
@Composable
fun SearchScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    var query by remember { mutableStateOf("") }
    val load = rememberLoad("search", query) {
        if (query.isBlank()) null else vm.lib.search(query.trim())
    }
    val player by vm.player.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Songs, Interpreten, Alben", color = colors.textFaint) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.textDim) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.line,
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        val data = load.value
        when {
            query.isBlank() -> EmptyNote("Wonach suchst du?")
            data == null && load.loading -> Loading()
            data == null -> EmptyNote("Nichts gefunden.")
            else -> {
                if (data.tracks.isEmpty() && data.artists.isEmpty() && data.albums.isEmpty()) {
                    EmptyNote("Nichts gefunden.")
                } else {
                    TrackList(
                        tracks = data.tracks,
                        currentTrackId = player.current?.id,
                        actions = trackActions(vm, data.tracks, "Suche", onGo),
                        showAlbum = true,
                        header = {
                            Column {
                                if (data.artists.isNotEmpty()) {
                                    Section("Interpreten") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            items(data.artists, key = { it.id }) { artist ->
                                                MediaCard(
                                                    title = artist.name,
                                                    subtitle = Fmt.plural(artist.trackCount, "Song", "Songs"),
                                                    coverUrl = vm.coverUrl(artist.cover),
                                                    round = true,
                                                    modifier = Modifier.width(130.dp),
                                                ) { onGo(Routes.artist(artist.id)) }
                                            }
                                        }
                                    }
                                }
                                if (data.albums.isNotEmpty()) {
                                    Section("Alben") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            items(data.albums, key = { it.id }) { album ->
                                                MediaCard(
                                                    title = album.title,
                                                    subtitle = album.artist,
                                                    coverUrl = vm.coverUrl(album.cover),
                                                    modifier = Modifier.width(130.dp),
                                                ) { onGo(Routes.album(album.id)) }
                                            }
                                        }
                                    }
                                }
                                if (data.tracks.isNotEmpty()) {
                                    RackLabelText(
                                        "Songs",
                                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
