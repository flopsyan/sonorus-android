package org.sonorus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.download.OfflineCollection
import org.sonorus.data.model.Album
import org.sonorus.data.model.SortPref
import org.sonorus.data.model.Track
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Routes
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.FastScroller
import org.sonorus.ui.components.scrollLabel
import org.sonorus.ui.components.albumCovers
import org.sonorus.ui.components.CardGridSkeleton
import org.sonorus.ui.components.DetailSkeleton
import org.sonorus.ui.components.HomeSkeleton
import org.sonorus.ui.components.TrackListSkeleton
import org.sonorus.ui.components.CoverMosaic
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.Loading
import org.sonorus.ui.components.MediaCard
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.Section
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.components.TrackActions
import org.sonorus.ui.components.TrackList
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.starLabel
import org.sonorus.ui.theme.SonorusTheme
import java.text.Normalizer
import org.sonorus.ui.theme.num

/** Builds the standard set of row actions for a list of tracks. */
@UnstableApi
@Composable
fun trackActions(
    vm: AppViewModel,
    tracks: List<Track>,
    source: String,
    /** The route this list *is*, so a row can tell where the queue came from. */
    sourceKey: String,
    onGo: (String) -> Unit,
    onRemove: ((Track) -> Unit)? = null,
): TrackActions {
    // Read here, so a row redraws the moment its download finishes - the same
    // reasoning the ratings map follows.
    val downloads by vm.downloads.state.collectAsState()
    return TrackActions(
        onPlay = { index -> vm.player.playTracks(tracks, index, source, sourceKey) },
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
    LoadBox(load, skeleton = { HomeSkeleton() }) { data ->
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
                    // A shelf on the home page is no list you can look at, so
                    // the queue belongs to the page itself - which no track list
                    // carries, and every row marks itself as playing elsewhere.
                    vm.player.playTracks(tracks, tracks.indexOf(track), label, Routes.HOME)
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
    var query by remember { mutableStateOf("") }

    LoadBox(load, skeleton = { TrackListSkeleton() }) { data ->
        val tracks = remember(data.tracks, query) {
            if (query.isBlank()) data.tracks
            else data.tracks.filter { matches(query, it.title, it.artist, it.album) }
        }
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            // Deliberately the plain route: sorting the list rewrites nothing
            // about *which* list it is, the same call the web app makes by
            // taking the path without its query.
            currentFromHere = player.sourceKey == Routes.TRACKS,
            actions = trackActions(vm, data.tracks, "Alle Songs", Routes.TRACKS, onGo),
            showAlbum = true,
            header = {
                Column {
                TabSearch(query, "Songs durchsuchen") { query = it }
                SortRow(
                    options = TRACK_SORTS,
                    sort = sort,
                    dir = dir,
                    total = if (query.isBlank()) data.total else tracks.size,
                    onPick = { key, direction ->
                        sort = key
                        dir = direction
                        vm.saveSort("trackSort", SortPref(key, direction))
                    },
                )
                }
            },
            // The bar on the right has to agree with the sort, or it would say
            // "M" while the rows are ordered by artist. A sort with no letter
            // behind it - year, length, when it arrived, how it is rated - gets
            // none: an empty label hides the bubble, which is better than a "M"
            // that describes nothing about where the finger is.
            emptyNote = if (query.isBlank()) "Hier ist noch nichts." else "Nichts gefunden.",
            labelOf = { track ->
                when (sort) {
                    "title" -> scrollLabel(track.title)
                    "artist" -> scrollLabel(track.artist)
                    "album" -> scrollLabel(track.album)
                    else -> ""
                }
            },
        )
    }
}

/**
 * A search that narrows the tab it sits on, and nothing else.
 *
 * Filtered here rather than fetched: the list is already in hand - Alle Songs
 * loads up to 5000 rows in one go - so this is instant and works offline, where
 * a request per keystroke would be neither. The global search in the top bar is
 * a different question ("where is this in the library?") and is untouched.
 */
@Composable
private fun TabSearch(query: String, hint: String, onChange: (String) -> Unit) {
    val colors = SonorusTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(hint, color = colors.textFaint) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.textDim) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, "Suche leeren", tint = colors.textDim)
                }
            }
        },
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Case- and accent-blind, and every word has to be in there somewhere.
 *
 * Folded the same way the scroll bar folds its letters, so "Bjork" finds
 * "Björk" - typing an umlaut to find a band is not a thing anyone does.
 */
private fun matches(query: String, vararg fields: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = fields.joinToString(" ") { fold(it) }
    return words.all { it in hay }
}

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .filterNot { it.isISOControl() }
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/**
 * Tiles or a list, the same pair the web app offers.
 *
 * Two icons rather than words: it sits in the sort row, and the row already
 * carries a count on the left and a sort button on the right.
 */
@UnstableApi
@Composable
private fun AlbumRows(
    albums: List<Album>,
    vm: AppViewModel,
    onGo: (String) -> Unit,
    labelOf: (Album) -> String,
) {
    val rows = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = rows,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                CollectionRow(
                    title = album.title,
                    subtitle = listOfNotNull(
                        album.artist.takeIf { it.isNotEmpty() },
                        Fmt.year(album.releaseDate, album.year).takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    coverUrl = vm.coverUrl(album.cover),
                ) { onGo(Routes.album(album.id)) }
            }
        }
        ListScroller(rows, albums.size) { labelOf(albums[it]) }
    }
}

/** The same bar the grids have, for a list. */
@Composable
private fun BoxScope.ListScroller(
    state: LazyListState,
    count: Int,
    labelAt: (Int) -> String,
) {
    FastScroller(
        itemCount = count,
        firstVisible = state.firstVisibleItemIndex,
        visibleCount = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1),
        labelAt = labelAt,
        onScrollTo = { state.scrollToItem(it) },
    )
}

@Composable
private fun ViewSwitch(view: String, onPick: (String) -> Unit) {
    val colors = SonorusTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val options = listOf(
            Triple("grid", Icons.Filled.GridView, "Kacheln"),
            Triple("list", Icons.AutoMirrored.Filled.List, "Liste"),
        )
        for ((value, glyph, label) in options) {
            IconButton(onClick = { onPick(value) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    glyph,
                    label,
                    tint = if (view == value) colors.accent else colors.textFaint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * One entry of a collection as a row.
 *
 * More per screenful than a tile and the name has room to be read - which is
 * the whole reason to want it, on a phone even more than on the web.
 */
@Composable
private fun CollectionRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    coverUrls: List<String> = emptyList(),
    round: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverMosaic(
            coverUrls.ifEmpty { listOfNotNull(coverUrl) },
            Modifier.size(52.dp),
            if (round) CircleShape else RoundedCornerShape(8.dp),
            title,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SortRow(
    options: List<Pair<String, String>>,
    sort: String,
    dir: String,
    total: Int,
    /** What is being counted. Shared by three tabs, and each counts its own. */
    one: String = "Song",
    many: String = "Songs",
    /** Drawn between the count and the sort button, where there is one. */
    trailing: (@Composable () -> Unit)? = null,
    onPick: (String, String) -> Unit,
) {
    val colors = SonorusTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RackLabelText(Fmt.plural(total, one, many))
        Row(verticalAlignment = Alignment.CenterVertically) {
        trailing?.invoke()
        // Interpreten have no sort of their own, so the row carries the count
        // and the view switch and nothing that would open an empty menu.
        if (options.isNotEmpty()) Box {
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
}

// --- Artists ----------------------------------------------------------------

@UnstableApi
@Composable
fun ArtistsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("artists") { vm.lib.artists() }
    var query by remember { mutableStateOf("") }
    LoadBox(load, skeleton = { CardGridSkeleton(round = true) }) { data ->
        if (data.artists.isEmpty()) return@LoadBox EmptyNote("Noch keine Interpreten.")
        val artists = remember(data.artists, query) {
            if (query.isBlank()) data.artists else data.artists.filter { matches(query, it.name) }
        }
        val grid = rememberLazyGridState()
        val rows = rememberLazyListState()
        // Local, seeded from the account. `vm.viewOf` reads the bootstrap,
        // which is a flow nothing here collects - so the switch would save the
        // choice and not redraw with it.
        var view by remember { mutableStateOf(vm.viewOf("artists")) }
        Column(Modifier.fillMaxSize()) {
        TabSearch(query, "Interpreten durchsuchen") { query = it }
        SortRow(
            options = emptyList(),
            sort = "",
            dir = "asc",
            total = artists.size,
            one = "Interpret",
            many = "Interpreten",
            trailing = { ViewSwitch(view) { view = it; vm.saveView("artists", it) } },
            onPick = { _, _ -> },
        )
        if (artists.isEmpty()) return@Column EmptyNote("Nichts gefunden.")
        Box(Modifier.fillMaxSize()) {
        if (view == "list") {
            LazyColumn(state = rows, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(artists, key = { it.id }) { artist ->
                    CollectionRow(
                        title = artist.name,
                        subtitle = Fmt.plural(artist.trackCount, "Song", "Songs"),
                        coverUrl = vm.coverUrl(artist.cover),
                        coverUrls = artist.covers.mapNotNull { vm.coverUrl(it) },
                        round = true,
                    ) { onGo(Routes.artist(artist.id)) }
                }
            }
            ListScroller(rows, artists.size) { scrollLabel(artists[it].name) }
        } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                MediaCard(
                    title = artist.name,
                    subtitle = Fmt.plural(artist.trackCount, "Song", "Songs"),
                    coverUrl = vm.coverUrl(artist.cover),
                    // Filled for "Various" only, which has no face of its own.
                    coverUrls = artist.covers.mapNotNull { vm.coverUrl(it) },
                    round = true,
                    modifier = Modifier.animateItem(),
                ) { onGo(Routes.artist(artist.id)) }
            }
        }
        GridScroller(grid, artists.size) { scrollLabel(artists[it].name) }
        }
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
    // The stars on the record itself, not on its songs. A record nobody has
    // rated sorts to the end of both directions - it is not the worst one, it
    // was never judged.
    "stars" to "Bewertung",
)

@UnstableApi
@Composable
fun AlbumsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    var sort by remember { mutableStateOf(vm.prefs.albumSort.key) }
    var dir by remember { mutableStateOf(vm.prefs.albumSort.dir) }
    val load = rememberLoad("albums", sort, dir) { vm.lib.albums(sort = sort, dir = dir) }
    var query by remember { mutableStateOf("") }

    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        val albums = remember(data.albums, query) {
            if (query.isBlank()) data.albums
            else data.albums.filter { matches(query, it.title, it.artist) }
        }
        Column(Modifier.fillMaxSize()) {
            TabSearch(query, "Alben durchsuchen") { query = it }
            var view by remember { mutableStateOf(vm.viewOf("albums")) }
            SortRow(
                options = ALBUM_SORTS,
                sort = sort,
                dir = dir,
                total = albums.size,
                one = "Album",
                many = "Alben",
                trailing = { ViewSwitch(view) { view = it; vm.saveView("albums", it) } },
            ) { key, direction ->
                sort = key
                dir = direction
                vm.saveSort("albumSort", SortPref(key, direction))
            }
            if (albums.isEmpty()) return@Column EmptyNote("Nichts gefunden.")
            val labelOf: (Album) -> String = { album ->
                when (sort) {
                    "title" -> scrollLabel(album.title)
                    "artist" -> scrollLabel(album.artist)
                    else -> ""
                }
            }
            if (view == "list") AlbumRows(albums, vm, onGo, labelOf)
            else AlbumGrid(albums, vm, onGo, labelOf)
        }
    }
}

@UnstableApi
@Composable
fun AlbumGrid(
    albums: List<Album>,
    vm: AppViewModel,
    onGo: (String) -> Unit,
    /** The letter beside the scroll thumb. Empty for a sort with no letter. */
    labelOf: (Album) -> String = { scrollLabel(it.title) },
) {
    if (albums.isEmpty()) return EmptyNote("Noch keine Alben.")
    val grid = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = grid,
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
                // From the view model and not from the row: a record rated on
                // this phone has to redraw here too, and nothing refetches the
                // grid. Same reason as the stars on a track row.
                // Re-sorting the grid slides the cards to their new places
                // instead of the whole page changing under the finger.
                modifier = Modifier.animateItem(),
            ) { onGo(Routes.album(album.id)) }
        }
    }
    GridScroller(grid, albums.size) { labelOf(albums[it]) }
    }
}

// --- Genres -----------------------------------------------------------------

@UnstableApi
@Composable
fun GenresScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("genres") { vm.lib.genres() }
    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        if (data.genres.isEmpty()) {
            return@LoadBox EmptyNote(
                "Noch keine Genres. Sie kommen aus den Tags der Dateien - " +
                    "oder du vergibst sie von Hand am Album oder an einer Single."
            )
        }
        val grid = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
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
                    modifier = Modifier.animateItem(),
                ) { onGo(Routes.genre(listOf(genre.id))) }
            }
        }
        GridScroller(grid, data.genres.size) { scrollLabel(data.genres[it].name) }
        }
    }
}

/**
 * The fast scroller over a card grid. A grid counts *cells*, not rows, so the
 * visible count has to be the number of cards on screen rather than the number
 * of rows - otherwise the thumb is two or three times too small on a wide
 * phone.
 */
@Composable
private fun BoxScope.GridScroller(
    state: LazyGridState,
    count: Int,
    labelAt: (Int) -> String,
) {
    FastScroller(
        itemCount = count,
        firstVisible = state.firstVisibleItemIndex,
        visibleCount = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1),
        labelAt = labelAt,
        onScrollTo = { state.scrollToItem(it) },
    )
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
    val key = Routes.genre(ids)

    LoadBox(selection, skeleton = { DetailSkeleton() }) { data ->
        TrackList(
            tracks = data.genre.tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, data.genre.tracks, data.genre.name, key, onGo),
            showAlbum = true,
            header = {
                Column {
                    // The same head an album gets: a genre is a collection you
                    // put on, so it is introduced like one.
                    DetailHead(
                        title = data.genre.name,
                        meta = listOf(
                            Fmt.plural(data.genre.tracks.size, "Song", "Songs"),
                            Fmt.durationLong(data.genre.tracks.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(data.genre.tracks).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playCollection(data.genre.tracks, data.genre.name, key) },
                        shuffle = player.shuffle,
                        onToggleShuffle = { vm.toggleShuffle() },
                        // A downloaded genre is kept in step: a song that is
                        // tagged into it later is fetched with the rest.
                        download = {
                            CollectionDownload(
                                vm,
                                data.genre.tracks,
                                OfflineCollection(
                                    kind = "genre",
                                    id = ids.firstOrNull() ?: 0,
                                    name = data.genre.name,
                                    ids = ids,
                                ),
                            )
                        },
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
    val key = Routes.stars(values)
    val counts = vm.bootstrap?.stars?.mapKeys { it.key.toIntOrNull() ?: -1 } ?: emptyMap()

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val title = values.sortedDescending().joinToString(", ") { starLabel(it) }
        TrackList(
            tracks = data.tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, data.tracks, title, key, onGo),
            showAlbum = true,
            header = {
                Column {
                    // A star playlist is a playlist, so it is introduced like
                    // one: the covers of the first four albums in it next to
                    // what the selection adds up to.
                    DetailHead(
                        title = title,
                        meta = listOf(
                            Fmt.plural(data.tracks.size, "Song", "Songs"),
                            Fmt.durationLong(data.tracks.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(data.tracks).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playCollection(data.tracks, title, key) },
                        shuffle = player.shuffle,
                        onToggleShuffle = { vm.toggleShuffle() },
                        // Star playlists move by themselves - a song rated up
                        // into this selection is fetched, one rated out of it
                        // goes again unless something else holds it.
                        download = {
                            CollectionDownload(
                                vm,
                                data.tracks,
                                OfflineCollection(
                                    kind = "stars",
                                    id = values.firstOrNull() ?: 0,
                                    name = title,
                                    ids = values,
                                ),
                            )
                        },
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
    // Opening the search means wanting to type. The field takes the focus and
    // brings the keyboard with it, rather than asking for a second tap on the
    // only thing on the screen.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
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
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
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
                        // One key for every search, so two different words
                        // share it. That marks one song a shade too strongly
                        // and breaks nothing - the same trade the web app makes.
                        currentFromHere = player.sourceKey == Routes.SEARCH,
                        actions = trackActions(vm, data.tracks, "Suche", Routes.SEARCH, onGo),
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
                                                    coverUrls = artist.covers.mapNotNull { vm.coverUrl(it) },
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
