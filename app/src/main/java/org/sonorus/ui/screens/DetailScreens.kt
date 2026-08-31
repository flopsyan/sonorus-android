package org.sonorus.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.download.OfflineCollection
import org.sonorus.data.model.Playlist
import org.sonorus.data.model.Track
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.LocalOffline
import org.sonorus.ui.Motion
import org.sonorus.ui.Routes
import org.sonorus.ui.pressable
import org.sonorus.ui.toggled
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
 * The head of a detail page: artwork, title, who it is by, the facts, the icons.
 *
 * Shared by everything that is a collection you put on - an album, an artist, a
 * playlist, a star playlist, a genre - which is why it is not private.
 *
 * Laid out the way Spotify lays it out, and for the reason Spotify does: this
 * page is opened to *play* something, so the artwork leads, the name is the
 * heading it deserves, and the one control that matters is a circle on the right
 * that the thumb finds without looking. It used to be four labelled buttons in
 * two rows of equal weight, which said that "Bearbeiten" and "Abspielen" were
 * the same kind of thing.
 *
 * The four lines from top to bottom are deliberate and each earns its place:
 * cover, title, artist, then the two facts worth knowing before pressing play -
 * how much of it there is and when it came out.
 *
 * [coverUrls] is a list rather than one picture: a collection without artwork of
 * its own shows the covers of the first four albums in it (see [CoverMosaic]),
 * and everything with one simply passes a list of one.
 */
@Composable
fun DetailHead(
    title: String,
    coverUrls: List<String>,
    /** Who it is by, on its own line under the title. Empty leaves the line out. */
    artist: String = "",
    /** The quiet line of facts: how many songs, what date, how long. */
    meta: String = "",
    round: Boolean = false,
    onPlay: () -> Unit,
    /**
     * Whether shuffle is armed, and how to arm it.
     *
     * A **switch and not a second play button**, which is the whole change here.
     * Spotify's shuffle arms the next thing you play and starts nothing by
     * itself; Sonorus had it as "Zufällig", a button that began playing the
     * moment it was touched. So the one control you would expect to be safe to
     * try was the one that started the music. Now it lights up and waits, and
     * the play button deals the order out.
     */
    shuffle: Boolean = false,
    onToggleShuffle: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    /** The download control, which every collection has and only its head draws. */
    download: (@Composable () -> Unit)? = null,
) {
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        // Centred, and sized against the window rather than fixed: 58 % is large
        // enough to be the thing the page is about and small enough that the
        // title and the transport are still on screen without scrolling, which
        // is the whole of "aber nicht zu groß".
        CoverMosaic(
            coverUrls,
            Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.58f)
                .widthIn(max = 260.dp)
                .aspectRatio(1f),
            if (round) CircleShape else RoundedCornerShape(10.dp),
            title,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                artist,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(14.dp))
        // What is done *to* the collection sits on the left as quiet glyphs;
        // what is done *with* it is the pair on the right. The gap between the
        // two groups is the sentence: these are not the same kind of button.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            download?.invoke()
            onEdit?.let {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = it, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        "Bearbeiten",
                        tint = colors.textDim,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            onToggleShuffle?.let { toggle ->
                val tint by animateColorAsState(
                    if (shuffle) colors.accent else colors.textDim,
                    Motion.quick(),
                    label = "shuffle",
                )
                IconButton(
                    onClick = {
                        haptics.toggled(!shuffle)
                        toggle()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, "Zufall", tint = tint, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .pressable(dip = 0.94f, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    "Abspielen",
                    tint = colors.accentInk,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/**
 * The download control of a whole collection - an album, a playlist, a genre, a
 * rating, an artist.
 *
 * One glyph with four states rather than a switch, because there are four
 * different things to say: nothing here yet, part of it, it is coming, it is all
 * here. Taking it back asks first - on a phone a mistap would otherwise throw
 * away an hour of downloading.
 *
 * **While it runs it is a ring, and the ring goes by bytes.** Three of ten songs
 * that happen to be the three long ones are not thirty per cent of the work, and
 * a bar that says so stalls near the end and then jumps. Tapping it again during
 * a run cancels - see [AppViewModel.cancelDownloadRun] for why only what *this*
 * run fetched is taken back.
 *
 * [playlist] is passed for a playlist and for nothing else. A playlist's order
 * exists nowhere but on the server, so it is stored along with the songs; every
 * other collection can be rebuilt from the songs themselves.
 */
@UnstableApi
@Composable
fun CollectionDownload(vm: AppViewModel, tracks: List<Track>, collection: OfflineCollection? = null) {
    val colors = SonorusTheme.colors
    val state by vm.downloads.state.collectAsState()
    val offline = LocalOffline.current
    var confirmingRemove by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }

    val here = tracks.filter { !it.missing }
    // What this phone remembers of the collection, if it is one that is kept in
    // step at all. Recomputed whenever the download state changes, which is the
    // same moment the store behind it does.
    val remembered = remember(state, collection?.key) {
        collection?.let { vm.downloads.store.collectionOf(it.kind, it.selection) }
    }
    if (here.isEmpty() && remembered == null) return

    /**
     * The songs the button is talking about.
     *
     * Offline this is the *remembered* list and not what is on screen, and that
     * is the fix for the button Florian ran into: offline a playlist page shows
     * only the songs that are downloaded, so counting those made every
     * half-downloaded list look complete - and the button offered to delete it
     * instead of to finish it.
     */
    val ids = if (offline && remembered != null) remembered.trackIds else here.map { it.id }
    if (ids.isEmpty()) return
    val done = ids.count { it in state.done }
    val busy = ids.count { it == state.active || it in state.queued }
    val start = {
        if (collection != null) vm.downloadCollection(collection, here) else vm.download(here)
    }

    // A collection that is kept in step is reconciled the moment its page is
    // open, out of the list the screen has just loaded - so no request of its
    // own. This is what makes "a song was added in the browser" show up here.
    LaunchedEffect(collection?.key, offline, tracks.size) {
        if (!offline && remembered != null) vm.reconcileOnScreen(remembered, tracks)
    }

    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (busy > 0) {
            val progress = state.batchProgress
            // Until the first size is known there is nothing honest to draw, so
            // it sweeps rather than claiming zero.
            if (progress == null) {
                CircularProgressIndicator(
                    Modifier.size(44.dp),
                    color = colors.accent,
                    trackColor = colors.surface2,
                    strokeWidth = 3.dp,
                )
            } else {
                val shown by animateFloatAsState(progress, Motion.standard(), label = "downloadRing")
                CircularProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.size(44.dp),
                    color = colors.accent,
                    trackColor = colors.surface2,
                    strokeWidth = 3.dp,
                )
            }
        }
        IconButton(
            onClick = {
                when {
                    busy > 0 -> confirmingCancel = true
                    done == ids.size -> confirmingRemove = true
                    else -> start()
                }
            },
            modifier = Modifier.size(44.dp),
        ) {
            when {
                // A square inside the ring, the way every transfer that can be
                // stopped says so. The arrow would read as "it is still going".
                busy > 0 -> Icon(
                    Icons.Filled.Stop,
                    "Download abbrechen",
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                done == ids.size -> Icon(
                    Icons.Filled.DownloadDone,
                    "Heruntergeladen - antippen zum Entfernen",
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
                done > 0 -> Icon(
                    Icons.Filled.DownloadForOffline,
                    "Rest herunterladen ($done von ${ids.size})",
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
                else -> Icon(
                    Icons.Filled.Download,
                    "Herunterladen",
                    tint = colors.textDim,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (confirmingRemove) {
        ConfirmDialog(
            title = "Download entfernen",
            message = removeMessage(vm, remembered, done),
            confirmLabel = "Entfernen",
            onDismiss = { confirmingRemove = false },
            onConfirm = {
                confirmingRemove = false
                // Through the collection where there is one: only the songs
                // nothing else holds are really deleted.
                if (remembered != null) vm.removeCollection(remembered)
                else vm.removeDownloads(here)
            },
        )
    }

    if (confirmingCancel) {
        val fetched = vm.downloads.runCount
        ConfirmDialog(
            title = "Download abbrechen",
            message = if (fetched > 0) {
                "Der laufende Download wird abgebrochen. Die " +
                    "${Fmt.plural(fetched, "Song", "Songs")}, die dabei schon geladen " +
                    "wurden, werden wieder gelöscht - alles, was vorher schon auf dem " +
                    "Gerät war, bleibt."
            } else {
                "Der laufende Download wird abgebrochen. Auf dem Gerät ändert sich nichts."
            },
            confirmLabel = "Download abbrechen",
            // Not "Abbrechen" next to "Abbrechen": here the action *is* an
            // abort, so the way out has to be named after what it does.
            dismissLabel = "Weiter laden",
            onDismiss = { confirmingCancel = false },
            onConfirm = {
                confirmingCancel = false
                vm.cancelDownloadRun()
            },
        )
    }
}

/**
 * What the confirmation says before a collection's download is given back.
 *
 * It counts the songs that will really go, because the answer is not "all of
 * them": one that also sits in a downloaded album or another playlist stays,
 * and a dialog that promised to delete it would be lying about the thing it is
 * asking permission for.
 */
private fun removeMessage(vm: AppViewModel, collection: OfflineCollection?, done: Int): String {
    val store = vm.downloads.store
    if (collection == null) {
        return "${Fmt.plural(done, "Song", "Songs")} werden von diesem Gerät gelöscht. " +
            "Auf dem Server bleibt alles, wie es ist."
    }
    val going = collection.trackIds.count {
        store.isDownloaded(it) && !store.isHeld(it, exceptKey = collection.key)
    }
    val kept = collection.trackIds.count { store.isDownloaded(it) } - going
    val first = "${Fmt.plural(going, "Song", "Songs")} werden von diesem Gerät gelöscht. " +
        "Auf dem Server bleibt alles, wie es ist."
    return if (kept > 0) {
        "$first ${Fmt.plural(kept, "Song bleibt", "Songs bleiben")} da - " +
            "sie hängen noch in anderen Downloads."
    } else {
        first
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
        val source = "Album: ${album.title}"
        TrackList(
            tracks = tracks,
            currentTrackId = player.current?.id,
            currentFromHere = player.sourceKey == key,
            actions = trackActions(vm, tracks, source, key, onGo),
            // The name under every row would be the one printed once at the top
            // of the page, twelve times over. A compilation is the exception:
            // there the artist really is the only thing telling the rows apart,
            // and it is decided per track rather than per album so a single
            // guest on an otherwise solo record still gets a name.
            showArtist = { it.artist.isNotEmpty() && it.artist != album.artist },
            header = {
                DetailHead(
                    title = album.title,
                    artist = album.artist,
                    // The album page is the only place that prints the full
                    // date - everywhere else there is only room for the year.
                    meta = listOfNotNull(
                        Fmt.plural(album.trackCount, "Song", "Songs"),
                        Fmt.releaseDate(album.releaseDate).takeIf { it.isNotEmpty() },
                        Fmt.durationLong(album.duration),
                    ).joinToString(" · "),
                    coverUrls = listOfNotNull(vm.coverUrl(album.cover)),
                    onPlay = { vm.player.playCollection(tracks, source, key) },
                    shuffle = player.shuffle,
                    onToggleShuffle = { vm.toggleShuffle() },
                    onEdit = { editing = true },
                    // Kept in step with the server: a song that appears on the
                    // record after a scan is fetched, one that goes is let go.
                    download = {
                        CollectionDownload(
                            vm,
                            tracks,
                            OfflineCollection(kind = "album", id = album.id, name = album.title),
                        )
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
            // Same rule as an album: the page is one person, so their name under
            // every row says nothing. A track credited to somebody else does.
            showArtist = { it.artist.isNotEmpty() && it.artist != artist.name },
            header = {
                Column {
                    DetailHead(
                        title = artist.name,
                        meta = listOf(
                            Fmt.plural(artist.albums.size, "Album", "Alben"),
                            Fmt.plural(tracks.size, "Song", "Songs"),
                        ).joinToString(" · "),
                        // Filled for "Various" only, which then shows the four
                        // compilations it is made of instead of one of them.
                        coverUrls = artist.covers.mapNotNull { vm.coverUrl(it) }
                            .ifEmpty { listOfNotNull(vm.coverUrl(artist.cover)) },
                        round = true,
                        onPlay = { vm.player.playCollection(tracks, artist.name, key) },
                        shuffle = player.shuffle,
                        onToggleShuffle = { vm.toggleShuffle() },
                        onEdit = { editing = true },
                        // Kept in step like an album: a song that appears under
                        // this artist after a scan is fetched, one that goes is
                        // let go. The list is the same one the button gets, so
                        // the baseline and the page can never disagree.
                        download = {
                            CollectionDownload(
                                vm,
                                tracks,
                                OfflineCollection(kind = "artist", id = artist.id, name = artist.name),
                            )
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
                    artist = data.artist.name,
                    meta = Fmt.plural(singles.size, "Song", "Songs"),
                    coverUrls = albumCovers(singles).mapNotNull { vm.coverUrl(it) },
                    onPlay = { vm.player.playCollection(singles, "Singles", key) },
                    shuffle = player.shuffle,
                    onToggleShuffle = { vm.toggleShuffle() },
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
                        artist = data.artist.name,
                        meta = listOf(
                            Fmt.plural(filtered.size, "Song", "Songs"),
                            Fmt.durationLong(filtered.sumOf { it.duration }),
                        ).joinToString(" · "),
                        coverUrls = albumCovers(filtered).mapNotNull { vm.coverUrl(it) },
                        onPlay = { vm.player.playCollection(filtered, label, key) },
                        shuffle = player.shuffle,
                        onToggleShuffle = { vm.toggleShuffle() },
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
                onRemove = { track -> vm.removeFromPlaylist(id, track) { load.reload() } },
            ),
            showAlbum = true,
            header = {
                DetailHead(
                    title = data.playlist.name,
                    // Counted from the tracks, not from `playlist.trackCount`:
                    // GET /api/playlists/:id answers { playlist, tracks } and
                    // carries neither count, so the head used to read
                    // "0 Songs - 0 Sek." over a list that was plainly not empty.
                    meta = listOf(
                        Fmt.plural(tracks.size, "Song", "Songs"),
                        Fmt.durationLong(tracks.sumOf { it.duration }),
                    ).joinToString(" · "),
                    coverUrls = albumCovers(tracks).mapNotNull { vm.coverUrl(it) },
                    onPlay = { vm.player.playCollection(tracks, data.playlist.name, key) },
                    shuffle = player.shuffle,
                    onToggleShuffle = { vm.toggleShuffle() },
                    // The one collection whose order has to be stored with it.
                    download = {
                        CollectionDownload(
                            vm,
                            tracks,
                            OfflineCollection(
                                kind = "playlist",
                                id = data.playlist.id,
                                name = data.playlist.name,
                            ),
                        )
                    },
                )
            },
        )
    }
}
