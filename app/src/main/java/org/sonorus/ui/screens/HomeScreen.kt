package org.sonorus.ui.screens

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.model.DailyTrack
import org.sonorus.data.model.HomeFavorites
import org.sonorus.data.model.ResumeTile
import org.sonorus.data.model.Track
import org.sonorus.ui.AppPhase
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Routes
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.CoverMosaic
import org.sonorus.ui.components.HomeSkeleton
import org.sonorus.ui.components.MediaCard
import org.sonorus.ui.components.Section
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.components.Stars
import org.sonorus.ui.pressable
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.starLabel
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num

// The same four blocks as the web's home page, in the same order: the song of the day,
// "Weiterhören", the favourites and how far rating has come.

@UnstableApi
@Composable
fun HomeScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("home") { vm.lib.home() }
    // The drawer's star counts are fetched again after every rating, the page's own only
    // with the page - so the bar reads the fresher of the two.
    val phase by vm.phase.collectAsState()
    LoadBox(load, skeleton = { HomeSkeleton() }) { data ->
        val stars = (phase as? AppPhase.Ready)?.bootstrap?.stars?.takeIf { it.isNotEmpty() } ?: data.stars
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
            data.daily?.let { daily -> item { DailyCard(daily, vm, onGo) } }
            if (data.resume.isNotEmpty()) {
                item {
                    Section("Weiterhören") {
                        Column(
                            Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            data.resume.forEach { ResumeRow(it, vm, onGo) }
                        }
                    }
                }
            }
            favorites(data.favorites, vm, onGo)
            // Only with a server: offline the page has no counts of its own to gate on.
            if (data.stars.isNotEmpty()) item { RatingPanel(stars, onGo) }
            // The downloads while offline, and a server older than 0.30.0.
            if (data.recentlyAdded.isNotEmpty()) {
                item { Shelf("Zuletzt hinzugefügt", data.recentlyAdded, vm) }
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

/** The song of the day is at least two months unheard, so it is counted in months and years. */
private fun lastHeard(days: Int?): String? = when {
    days == null -> null
    days <= 1 -> "Zuletzt gestern gehört"
    days < 60 -> "Zuletzt vor $days Tagen gehört"
    Math.round(days / 30.44) < 24 -> "Zuletzt vor ${Math.round(days / 30.44)} Monaten gehört"
    else -> "Zuletzt vor ${Math.round(days / 365.25)} Jahren gehört"
}

@UnstableApi
@Composable
private fun DailyCard(daily: DailyTrack, vm: AppViewModel, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val track = daily.track
    Section("Heute aufgelegt") {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cover(
                    vm.coverUrl(track.cover),
                    Modifier.size(112.dp),
                    contentDescription = track.album.ifEmpty { track.title },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = track.artistId?.let { id -> Modifier.pressable { onGo(Routes.artist(id)) } } ?: Modifier,
                    )
                    Text(
                        listOf(
                            track.album.ifEmpty { "Single" },
                            Fmt.year(track.releaseDate, track.year),
                            Fmt.duration(track.duration),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Stars(
                        vm.starsShown(track),
                        Modifier.padding(top = 4.dp),
                        size = 20,
                        waiting = vm.ratingWaiting(track),
                    ) { vm.rate(track.id, it, vm.starsOf(track)) }
                    lastHeard(daily.daysSince)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // One song and no list behind it, so the queue names no page.
                SonorusButton("Abspielen", primary = true, modifier = Modifier.weight(1f)) {
                    vm.player.playTracks(listOf(track), 0, "Heute aufgelegt", "")
                }
                track.albumId?.let { id ->
                    SonorusButton("Zum Album", modifier = Modifier.weight(1f)) { onGo(Routes.album(id)) }
                }
            }
        }
    }
}

private fun resumeIcon(name: String): ImageVector = when (name) {
    "shuffle" -> Icons.Filled.Shuffle
    "star-outline" -> Icons.Outlined.StarBorder
    "star" -> Icons.Filled.Star
    "filter" -> Icons.Filled.FilterAlt
    else -> Icons.Filled.MusicNote
}

/** A random run has no page to go back to, so its whole row starts a new one. */
@UnstableApi
@Composable
private fun ResumeRow(tile: ResumeTile, vm: AppViewModel, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val shuffle = tile.kind == "shuffle"
    val shape = if (tile.round) CircleShape else RoundedCornerShape(5.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .pressable { if (shuffle) vm.playResume(tile) else onGo(tile.key) }
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tile.covers.isNotEmpty()) {
            CoverMosaic(tile.covers.mapNotNull { vm.coverUrl(it) }, Modifier.size(44.dp), shape, tile.title)
        } else {
            Box(
                Modifier.size(44.dp).clip(shape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(resumeIcon(tile.icon), contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                tile.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tile.sub,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .pressable { vm.playResume(tile) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (shuffle) Icons.Filled.Shuffle else Icons.Filled.PlayArrow,
                contentDescription = "${tile.title} abspielen",
                tint = colors.accentInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@UnstableApi
private fun androidx.compose.foundation.lazy.LazyListScope.favorites(
    fav: HomeFavorites,
    vm: AppViewModel,
    onGo: (String) -> Unit,
) {
    if (fav.artists.isNotEmpty()) {
        item {
            Section("Favoriten · Interpreten") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(fav.artists, key = { it.id }) { artist ->
                        MediaCard(
                            title = artist.name,
                            subtitle = "",
                            coverUrl = vm.coverUrl(artist.cover),
                            coverUrls = artist.covers.mapNotNull { vm.coverUrl(it) },
                            round = true,
                            modifier = Modifier.width(120.dp),
                        ) { onGo(Routes.artist(artist.id)) }
                    }
                }
            }
        }
    }
    if (fav.albums.isNotEmpty()) {
        item {
            Section("Favoriten · Alben") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(fav.albums, key = { it.id }) { album ->
                        MediaCard(
                            title = album.title,
                            subtitle = album.artist,
                            coverUrl = vm.coverUrl(album.cover),
                            modifier = Modifier.width(150.dp),
                        ) { onGo(Routes.album(album.id)) }
                    }
                }
            }
        }
    }
    if (fav.genres.isNotEmpty()) {
        item {
            Section("Favoriten · Genres") {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fav.genres.forEach { genre ->
                        Chip(genre.name, selected = false, count = genre.trackCount) {
                            onGo(Routes.genre(listOf(genre.id)))
                        }
                    }
                }
            }
        }
    }
}

/** Best first, then what is still waiting; each part opens its star list. */
private val RATING_PARTS = listOf(5, 4, 3, 2, 1, 0)

@Composable
private fun ratingColor(stars: Int): Color {
    val colors = SonorusTheme.colors
    return when (stars) {
        5 -> colors.accent
        4 -> lerp(colors.surface2, colors.accent, 0.78f)
        3 -> lerp(colors.surface2, colors.accent, 0.55f)
        2 -> lerp(colors.surface2, colors.accent, 0.36f)
        1 -> lerp(colors.surface2, colors.accent, 0.22f)
        else -> colors.surface3
    }
}

@Composable
private fun RatingPanel(counts: Map<String, Int>, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val count = { stars: Int -> counts[stars.toString()] ?: 0 }
    val rated = (1..5).sumOf(count)
    val total = rated + count(0)
    if (total == 0) return
    val parts = RATING_PARTS.filter { count(it) > 0 }
    Section("Bewertet") {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Math.round(rated * 100.0 / total)} %", style = num(18.sp), color = colors.accent)
                Text(
                    "${Fmt.number(rated)} von ${Fmt.plural(total, "Song", "Songs")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textDim,
                )
            }
            // A sliver of a part stays visible: below 0.5 % of the bar it would be gone.
            Row(
                Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(colors.surface2),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.forEach { stars ->
                    Box(
                        Modifier
                            .weight(maxOf(count(stars).toFloat(), total * 0.005f))
                            .fillMaxHeight()
                            .background(ratingColor(stars))
                            .pressable { onGo(Routes.stars(listOf(stars))) }
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parts.forEach { stars ->
                    Row(
                        Modifier.pressable { onGo(Routes.stars(listOf(stars))) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(ratingColor(stars)))
                        Text(starLabel(stars), style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                        Text(Fmt.number(count(stars)), style = num(11.sp), color = colors.textFaint)
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun Shelf(label: String, tracks: List<Track>, vm: AppViewModel) {
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
