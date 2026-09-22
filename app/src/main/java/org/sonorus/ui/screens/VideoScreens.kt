package org.sonorus.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonorus.data.download.DownloadStatus
import org.sonorus.data.download.VideoDownloads
import org.sonorus.data.model.ContinueItem
import org.sonorus.data.model.ShowDetail
import org.sonorus.data.model.VideoEpisode
import org.sonorus.data.model.VideoGenre
import org.sonorus.data.model.VideoPerson
import org.sonorus.data.model.VideoProgress
import org.sonorus.data.model.VideoSeason
import org.sonorus.data.model.VideoTitle
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.LocalOffline
import org.sonorus.ui.Routes
import org.sonorus.ui.VideoFmt
import org.sonorus.ui.components.CardGridSkeleton
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.ConfirmDialog
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.DetailSkeleton
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.pressable
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num

/** Width over height of a film poster; the web's `.card.portrait`. */
private const val POSTER_RATIO = 2f / 3f
private const val WIDE_RATIO = 16f / 9f

// --- Filme & Serien: one tab at the bottom, three tabs on the page ----------------

private enum class VideoTab(val label: String) { HOME("Übersicht"), MOVIES("Filme"), SHOWS("Serien") }

@UnstableApi
@Composable
fun VideosScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    // Three pages one swipe apart, the tabs above them saying which one is on.
    val pager = rememberPagerState(pageCount = { VideoTab.entries.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        VideoTabs(VideoTab.entries[pager.currentPage]) { scope.launch { pager.animateScrollToPage(it.ordinal) } }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (VideoTab.entries[page]) {
                VideoTab.HOME -> VideoOverview(vm, onGo)
                VideoTab.MOVIES -> VideoBrowse(vm, movies = true, onGo)
                VideoTab.SHOWS -> VideoBrowse(vm, movies = false, onGo)
            }
        }
    }
}

@Composable
private fun VideoTabs(active: VideoTab, onPick: (VideoTab) -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tab in VideoTab.entries) {
            val on = tab == active
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(tab) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (on) colors.text else colors.textDim,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(if (on) 28.dp else 0.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.accent)
                )
            }
        }
    }
}

// The tip changes once a day, among the eight newest titles with a backdrop -
// unfinished ones first. The same rule as `pickFeatured` in video-views.js.
private fun pickFeatured(list: List<VideoTitle>): VideoTitle? {
    val withArt = list.filter { it.backdrop != null }
    if (withArt.isEmpty()) return null
    val fresh = withArt.filterNot { it.done }
    val pool = (fresh.ifEmpty { withArt })
        .sortedByDescending { it.newest ?: it.addedAt }
        .take(8)
    val day = (System.currentTimeMillis() / 86_400_000L).toInt()
    return pool[day % pool.size]
}

@UnstableApi
@Composable
private fun VideoOverview(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("videoHome") { vm.lib.videoHome() }
    val scope = rememberCoroutineScope()
    val offline = LocalOffline.current

    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        if (data.movies.isEmpty() && data.shows.isEmpty()) {
            return@LoadBox EmptyNote(
                if (offline) "Keine Filme oder Folgen heruntergeladen."
                else "Noch keine Filme und Serien. Sonorus liest sie aus VIDEO_DIR auf dem Server: " +
                    "\"movies\" mit einem Ordner je Film, \"shows\" mit einem Ordner je Serie."
            )
        }
        val featured = remember(data) { pickFeatured(data.movies + data.shows) }
        val newMovies = remember(data) { data.movies.sortedByDescending { it.addedAt }.take(16) }
        val newShows = remember(data) { data.shows.sortedByDescending { it.newest ?: it.addedAt }.take(16) }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            featured?.let { t -> item { FeaturedHero(vm, t, onGo) } }
            if (data.carryOn.isNotEmpty()) {
                item {
                    VideoSectionLabel("Weiterschauen")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(data.carryOn, key = { "${it.kind}-${it.title.id}" }) { item ->
                            ContinueCard(vm, item, onGo) {
                                scope.launch {
                                    runCatching { vm.lib.setVideoWatched(item.video.id, true) }
                                        .onFailure { vm.say(vm.message(it), isError = true) }
                                    load.reload()
                                }
                            }
                        }
                    }
                }
            }
            if (newMovies.isNotEmpty()) item { PosterRow(vm, "Neue Filme", newMovies, onGo) }
            if (newShows.isNotEmpty()) item { PosterRow(vm, "Neue Folgen", newShows, onGo) }
            if (data.collections.isNotEmpty()) {
                item {
                    VideoSectionLabel("Filmreihen")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(data.collections, key = { it.id }) { c ->
                            PosterCard(
                                vm,
                                poster = c.poster,
                                title = c.name,
                                sub = Fmt.plural(c.movies, "Film", "Filme") +
                                    if (c.watched > 0) " · ${c.watched} gesehen" else "",
                                done = c.watched >= c.movies,
                                modifier = Modifier.width(120.dp),
                            ) { onGo(Routes.videoCollection(c.id)) }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun FeaturedHero(vm: AppViewModel, t: VideoTitle, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val href = if (t.isMovie) Routes.movie(t.id) else Routes.show(t.id)
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .aspectRatio(WIDE_RATIO)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .pressable(dip = 0.98f) { onGo(href) },
    ) {
        AsyncImage(
            model = vm.coverUrl(t.backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                (if (t.isMovie) "Film-Tipp" else "Serien-Tipp").uppercase(),
                style = org.sonorus.ui.theme.RackLabel,
                color = Color.White.copy(alpha = 0.75f),
            )
            TitleOrLogo(vm, t.title, t.logo, onDark = true, height = 40.dp)
            Text(
                listOfNotNull(
                    t.year?.toString(),
                    VideoFmt.certLabel(t.certification).ifEmpty { null },
                    if (t.isMovie) t.duration.takeIf { it > 0 }?.let(VideoFmt::durationLong)
                    else t.seasons.takeIf { it > 0 }?.let { Fmt.plural(it, "Staffel", "Staffeln") },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** A title's logo where there is one, its name in type where not. */
@Composable
private fun TitleOrLogo(vm: AppViewModel, title: String, logo: String?, onDark: Boolean, height: androidx.compose.ui.unit.Dp) {
    val colors = SonorusTheme.colors
    if (logo != null) {
        AsyncImage(
            model = vm.coverUrl(logo),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(height).fillMaxWidth(0.7f),
        )
    } else {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = if (onDark) Color.White else colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A 16:9 tile that plays; the check in its corner marks it seen. */
@UnstableApi
@Composable
private fun ContinueCard(vm: AppViewModel, item: ContinueItem, onGo: (String) -> Unit, onDone: () -> Unit) {
    val colors = SonorusTheme.colors
    val v = item.video
    val t = item.title
    val picture = if (item.kind == "show") v.still ?: t.thumb ?: t.backdrop else t.thumb ?: t.backdrop
    val sub = if (item.kind == "show") {
        VideoFmt.episodeCode(v.season, v.episode, v.episodeEnd) + if (v.name.isNotEmpty()) " · ${v.name}" else ""
    } else {
        "noch ${VideoFmt.durationLong(v.duration - v.progress.position)}"
    }
    // The picture plays; the words under it lead to the title, a series straight
    // into the season the episode is from.
    val page = if (item.kind == "show") Routes.show(t.id, v.season) else Routes.movie(t.id)
    Column(Modifier.width(236.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(WIDE_RATIO)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface2)
                .pressable(dip = 0.97f) { onGo(Routes.watch(v.id)) },
        ) {
            AsyncImage(
                model = vm.coverUrl(picture),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            PlayBadge(Modifier.align(Alignment.Center))
            if (v.progress.started) ProgressLine(v.progress, Modifier.align(Alignment.BottomStart), endGap = 44.dp)
            // Bottom right, the way the web tile has it: a series moves on, a film leaves the row.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xA0100E14))
                    .clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, "Als gesehen markieren", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().clickable { onGo(page) }.padding(vertical = 2.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Color(0x9E100E14)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ProgressLine(progress: VideoProgress, modifier: Modifier = Modifier, endGap: androidx.compose.ui.unit.Dp = 6.dp) {
    val colors = SonorusTheme.colors
    Box(
        modifier
            .padding(start = 6.dp, end = endGap, bottom = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x8C000000)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.fraction.toFloat().coerceIn(0.02f, 1f))
                .height(4.dp)
                .background(colors.accent)
        )
    }
}

@Composable
private fun VideoSectionLabel(text: String) {
    RackLabelText(text, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
}

@UnstableApi
@Composable
private fun PosterRow(vm: AppViewModel, label: String, titles: List<VideoTitle>, onGo: (String) -> Unit) {
    VideoSectionLabel(label)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(titles, key = { it.id }) { t -> TitlePoster(vm, t, Modifier.width(112.dp), onGo) }
    }
}

@UnstableApi
@Composable
private fun TitlePoster(vm: AppViewModel, t: VideoTitle, modifier: Modifier, onGo: (String) -> Unit) {
    PosterCard(
        vm,
        poster = t.poster,
        title = t.title,
        sub = titleSub(t),
        done = t.done,
        progress = if (t.isMovie && t.progress.started) t.progress else null,
        modifier = modifier,
    ) { onGo(if (t.isMovie) Routes.movie(t.id) else Routes.show(t.id)) }
}

private fun titleSub(t: VideoTitle): String = when {
    t.isMovie && t.progress.started -> "noch ${VideoFmt.durationLong(t.duration - t.progress.position)}"
    t.isMovie -> listOfNotNull(t.year?.toString(), t.duration.takeIf { it > 0 }?.let(VideoFmt::durationLong)).joinToString(" · ")
    t.watched in 1 until t.episodes -> "${t.watched}/${t.episodes} gesehen"
    else -> listOfNotNull(t.year?.toString(), t.seasons.takeIf { it > 0 }?.let { Fmt.plural(it, "Staffel", "Staffeln") })
        .joinToString(" · ")
}

@UnstableApi
@Composable
private fun PosterCard(
    vm: AppViewModel,
    poster: String?,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    progress: VideoProgress? = null,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(modifier.pressable(dip = 0.95f, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO)) {
            Cover(vm.coverUrl(poster), Modifier.fillMaxSize(), contentDescription = title)
            if (done) DoneBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomStart)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DoneBadge(modifier: Modifier = Modifier) {
    val colors = SonorusTheme.colors
    Box(
        modifier.size(22.dp).clip(CircleShape).background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, "Gesehen", tint = colors.accentInk, modifier = Modifier.size(14.dp))
    }
}

// --- The full lists, with the web's filters ---------------------------------------------

private enum class VideoSort(val label: String) {
    TITLE("Titel"), YEAR("Jahr"), ADDED("Neu hinzugefügt"), VOTE("TMDB-Wertung"), WATCHED("Zuletzt gesehen"),
}

private fun sortTitles(list: List<VideoTitle>, sort: VideoSort): List<VideoTitle> {
    val byTitle = compareBy<VideoTitle> { it.title.lowercase() }
    return when (sort) {
        VideoSort.TITLE -> list.sortedWith(byTitle)
        VideoSort.YEAR -> list.sortedWith(compareByDescending<VideoTitle> { it.year ?: 0 }.then(byTitle))
        VideoSort.ADDED -> list.sortedWith(compareByDescending<VideoTitle> { it.newest ?: it.addedAt }.then(byTitle))
        VideoSort.VOTE -> list.sortedWith(compareByDescending<VideoTitle> { it.vote ?: 0.0 }.then(byTitle))
        VideoSort.WATCHED -> list.sortedWith(compareByDescending<VideoTitle> { it.watchedAt.orEmpty() }.then(byTitle))
    }
}

@UnstableApi
@Composable
private fun VideoBrowse(vm: AppViewModel, movies: Boolean, onGo: (String) -> Unit) {
    val load = rememberLoad(if (movies) "movies" else "shows") {
        if (movies) vm.lib.movies().let { it.movies to it.genres } else vm.lib.shows().let { it.shows to it.genres }
    }
    var genre by rememberSaveable(movies) { mutableIntStateOf(0) }
    var sort by rememberSaveable(movies) { mutableStateOf(VideoSort.TITLE) }
    var unwatched by rememberSaveable(movies) { mutableStateOf(false) }

    LoadBox(load, skeleton = { CardGridSkeleton() }) { (titles, genres) ->
        if (titles.isEmpty()) {
            return@LoadBox EmptyNote(if (movies) "Keine Filme." else "Keine Serien.")
        }
        val shown = remember(titles, genre, sort, unwatched) {
            sortTitles(
                titles.filter { genre == 0 || genre in it.genreIds }.filter { !unwatched || !it.done },
                sort,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseBar(genres, genre, sort, unwatched, shown.size, { genre = it }, { sort = it }, { unwatched = it })
            }
            items(shown, key = { it.id }) { t -> TitlePoster(vm, t, Modifier, onGo) }
        }
    }
}

@Composable
private fun BrowseBar(
    genres: List<VideoGenre>,
    genre: Int,
    sort: VideoSort,
    unwatched: Boolean,
    count: Int,
    onGenre: (Int) -> Unit,
    onSort: (VideoSort) -> Unit,
    onUnwatched: (Boolean) -> Unit,
) {
    val colors = SonorusTheme.colors
    var genreOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (genres.isNotEmpty()) {
                Box {
                    Chip(genres.firstOrNull { it.id == genre }?.name ?: "Alle Genres", selected = genre != 0) { genreOpen = true }
                    DropdownMenu(genreOpen, onDismissRequest = { genreOpen = false }) {
                        DropdownMenuItem(text = { Text("Alle Genres") }, onClick = { onGenre(0); genreOpen = false })
                        for (g in genres) {
                            DropdownMenuItem(
                                text = { Text("${g.name} (${g.count})") },
                                onClick = { onGenre(g.id); genreOpen = false },
                            )
                        }
                    }
                }
            }
            Box {
                Chip(sort.label, selected = sort != VideoSort.TITLE) { sortOpen = true }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                    for (s in VideoSort.entries) {
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); sortOpen = false })
                    }
                }
            }
            Chip("Nur ungesehene", selected = unwatched) { onUnwatched(!unwatched) }
        }
        Text(
            Fmt.plural(count, "Titel", "Titel"),
            style = num(11.sp),
            color = colors.textFaint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// --- One film ------------------------------------------------------------------------

@UnstableApi
@Composable
fun MovieScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("movie", id) { vm.lib.movie(id).movie }
    val scope = rememberCoroutineScope()
    val colors = SonorusTheme.colors

    LoadBox(load, skeleton = { DetailSkeleton() }) { m ->
        val v = m.video
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                VideoHead(
                    vm,
                    backdrop = m.backdrop,
                    poster = m.poster,
                    title = m.title,
                    logo = m.logo,
                    label = "Film",
                    facts = listOfNotNull(
                        m.year?.toString(),
                        v?.duration?.takeIf { it > 0 }?.let(VideoFmt::durationLong),
                        VideoFmt.certLabel(m.certification).ifEmpty { null },
                        v?.tech?.let { VideoFmt.resolutionLabel(it.height, it.width) }?.ifEmpty { null },
                    ),
                    vote = m.vote,
                )
            }
            if (v != null) {
                item {
                    val started = v.progress.started
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SonorusButton(
                            if (started) "Fortsetzen (noch ${VideoFmt.durationLong(v.duration - v.progress.position)})" else "Abspielen",
                            primary = true,
                        ) { onGo(Routes.watch(v.id)) }
                        if (started) SonorusButton("Von vorne") { onGo(Routes.watch(v.id, fromStart = true)) }
                        SonorusButton(if (v.progress.completed) "Als ungesehen markieren" else "Als gesehen markieren") {
                            scope.launch {
                                runCatching { vm.lib.setVideoWatched(v.id, !v.progress.completed) }
                                    .onFailure { vm.say(vm.message(it), isError = true) }
                                load.reload()
                            }
                        }
                        VideoDownloadButton(vm, v.id) {
                            VideoDownloads.Item(videoId = v.id, label = m.title)
                        }
                    }
                    if (started) {
                        LinearProgressIndicator(
                            progress = { v.progress.fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = colors.accent,
                            trackColor = colors.surface3,
                        )
                    }
                }
            }
            item { DetailText(m.tagline, m.overview, m.originalTitle) }
            if (m.genres.isNotEmpty()) item { GenreChips(m.genres) }
            item { CrewLines(m.crew, m.studios, onGo) }
            if (m.cast.isNotEmpty()) item { PeopleRow(vm, "Besetzung", m.cast.take(20), onGo) }
            m.collection?.let { c ->
                item {
                    VideoSectionLabel(c.name)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(c.movies, key = { it.id }) { t -> TitlePoster(vm, t, Modifier.width(104.dp), onGo) }
                    }
                }
            }
            if (m.similar.isNotEmpty()) item { PosterRow(vm, "Ähnliche Filme", m.similar, onGo) }
            v?.tech?.let { tech ->
                item {
                    VideoSectionLabel("Technik")
                    val audio = tech.audio.map { VideoFmt.audioLabel(it).let { (a, b) -> "$a ($b)" } }
                    FactLine("Bild", listOf(VideoFmt.resolutionLabel(tech.height, tech.width), tech.video.uppercase(), if (tech.hdr) "HDR" else "")
                        .filter { it.isNotEmpty() }.joinToString(" · "))
                    if (audio.isNotEmpty()) FactLine("Ton", audio.joinToString(", "))
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun VideoHead(
    vm: AppViewModel,
    backdrop: String?,
    poster: String?,
    title: String,
    logo: String?,
    label: String,
    facts: List<String>,
    vote: Double?,
) {
    val colors = SonorusTheme.colors
    // The shell's bar says "Sonorus" unless a page names itself, as DetailHead does.
    val screenTitle = org.sonorus.ui.LocalScreenTitle.current
    LaunchedEffect(title) { screenTitle.value = title }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(WIDE_RATIO).background(colors.surface2)) {
            if (backdrop != null) {
                AsyncImage(
                    model = vm.coverUrl(backdrop),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 1f to colors.bg)
                )
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Cover(
                vm.coverUrl(poster),
                Modifier.width(96.dp).aspectRatio(POSTER_RATIO),
                contentDescription = title,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RackLabelText(label)
                TitleOrLogo(vm, title, logo, onDark = false, height = 44.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                    )
                    if (vote != null && vote > 0) {
                        Icon(Icons.Filled.Star, "TMDB-Wertung", tint = colors.accent, modifier = Modifier.size(12.dp))
                        Text("%.1f".format(vote), style = num(12.sp), color = colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailText(tagline: String, overview: String, originalTitle: String = "") {
    val colors = SonorusTheme.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tagline.isNotEmpty()) {
            Text(tagline, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = colors.textDim)
        }
        if (overview.isNotEmpty()) Text(overview, style = MaterialTheme.typography.bodyMedium, color = colors.text)
        if (originalTitle.isNotEmpty()) {
            Text("Originaltitel: $originalTitle", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
        }
    }
}

@Composable
private fun GenreChips(genres: List<VideoGenre>) {
    FactLine(if (genres.size > 1) "Genres" else "Genre", genres.joinToString(", ") { it.name })
}

private val CREW_WORDS = mapOf("director" to "Regie", "writer" to "Drehbuch", "creator" to "Idee", "composer" to "Musik")

@Composable
private fun CrewLines(crew: List<VideoPerson>, studios: List<String>, onGo: (String) -> Unit) {
    val byRole = crew.groupBy { it.role }
    if (byRole.isEmpty() && studios.isEmpty()) return
    Column(Modifier.padding(vertical = 6.dp)) {
        for ((role, people) in byRole) {
            FactLine(CREW_WORDS[role] ?: role, people.take(4).joinToString(", ") { it.name })
        }
        if (studios.isNotEmpty()) FactLine(if (studios.size > 1) "Studios" else "Studio", studios.joinToString(", "))
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    val colors = SonorusTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RackLabelText(label, Modifier.width(86.dp).padding(top = 2.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.text, modifier = Modifier.weight(1f))
    }
}

@UnstableApi
@Composable
private fun PeopleRow(vm: AppViewModel, label: String, people: List<VideoPerson>, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val offline = LocalOffline.current
    VideoSectionLabel(label)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(people, key = { "${it.id}-${it.character}" }) { p ->
            Column(
                Modifier.width(84.dp).pressable(enabled = !offline) { onGo(Routes.videoPerson(p.id)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Cover(vm.coverUrl(p.photo), Modifier.size(72.dp), CircleShape, p.name)
                Spacer(Modifier.height(6.dp))
                Text(p.name, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.character.isNotEmpty()) {
                    Text(p.character, style = MaterialTheme.typography.labelSmall, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// --- One series ----------------------------------------------------------------------

@UnstableApi
@Composable
fun ShowScreen(vm: AppViewModel, id: Int, startSeason: Int?, onGo: (String) -> Unit) {
    val load = rememberLoad("show", id) { vm.lib.show(id).show }
    val scope = rememberCoroutineScope()
    // What a tap on a check shows at once, until the server's answer is back.
    val marks = remember { mutableStateMapOf<Int, Boolean>() }
    // A Weiterschauen tile names the season its episode is from.
    var picked by rememberSaveable(id) { mutableStateOf(startSeason) }

    LaunchedEffect(load.value) { marks.clear() }

    LoadBox(load, skeleton = { DetailSkeleton() }) { s ->
        val season = s.seasons.firstOrNull { it.season == picked }
            ?: s.seasons.firstOrNull { it.season == s.next?.season }
            ?: s.seasons.firstOrNull()
        val allDone = s.episodes > 0 && s.watched >= s.episodes
        val toggle: (VideoEpisode) -> Unit = { e ->
            val now = !(marks[e.id] ?: e.progress.completed)
            marks[e.id] = now
            scope.launch {
                runCatching { vm.lib.setVideoWatched(e.id, now) }
                    .onFailure { vm.say(vm.message(it), isError = true) }
                load.reload()
            }
        }
        val markTitle: (Boolean, Int?) -> Unit = { watched, seasonNo ->
            scope.launch {
                runCatching { vm.lib.setTitleWatched(s.id, watched, seasonNo) }
                    .onFailure { vm.say(vm.message(it), isError = true) }
                load.reload()
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val years = if (s.endDate.length >= 4 && s.year != null && s.endDate.take(4) != s.year.toString()) {
                    "${s.year}-${s.endDate.take(4)}"
                } else s.year?.toString()
                VideoHead(
                    vm,
                    backdrop = s.backdrop,
                    poster = s.poster,
                    title = s.title,
                    logo = s.logo,
                    label = "Serie",
                    facts = listOfNotNull(
                        years,
                        Fmt.plural(s.seasons.count { it.season != 0 }, "Staffel", "Staffeln"),
                        VideoFmt.certLabel(s.certification).ifEmpty { null },
                        if (s.watched > 0) "${s.watched}/${s.episodes} gesehen" else null,
                    ),
                    vote = s.vote,
                )
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.next?.let { next ->
                        val code = VideoFmt.episodeCode(next.season, next.episode, next.episodeEnd)
                        SonorusButton(
                            when {
                                next.progress.started -> "$code fortsetzen"
                                s.watched > 0 -> "$code abspielen"
                                else -> "Abspielen"
                            },
                            primary = true,
                        ) { onGo(Routes.watch(next.id)) }
                    }
                    if (!LocalOffline.current) {
                        SonorusButton(if (allDone) "Als ungesehen markieren" else "Alles als gesehen markieren") {
                            markTitle(!allDone, null)
                        }
                    }
                }
            }
            item { DetailText(s.tagline, s.overview) }
            if (s.genres.isNotEmpty()) item { GenreChips(s.genres) }
            item { CrewLines(s.crew, s.studios, onGo) }
            if (s.seasons.size > 1) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (x in s.seasons) Chip(x.name, selected = x.season == season?.season) { picked = x.season }
                    }
                }
            }
            season?.let { x ->
                item { SeasonHead(vm, s, x, marks, onMark = { markTitle(it, x.season) }) }
                items(x.episodes, key = { it.id }) { e ->
                    EpisodeRow(vm, s, e, marks[e.id] ?: e.progress.completed, onGo, onToggle = { toggle(e) })
                }
            }
            if (s.cast.isNotEmpty()) item { PeopleRow(vm, "Besetzung", s.cast.take(20), onGo) }
            if (s.similar.isNotEmpty()) item { PosterRow(vm, "Ähnliche Serien", s.similar, onGo) }
        }
    }
}

@UnstableApi
@Composable
private fun SeasonHead(
    vm: AppViewModel,
    s: ShowDetail,
    x: VideoSeason,
    marks: Map<Int, Boolean>,
    onMark: (Boolean) -> Unit,
) {
    val colors = SonorusTheme.colors
    val watched = x.episodes.count { marks[it.id] ?: it.progress.completed }
    val done = watched >= x.episodes.size
    val downloads by vm.videoDownloads.state.collectAsState()
    val missing = x.episodes.filter { downloads.statusOf(it.id) == DownloadStatus.NONE || downloads.statusOf(it.id) == DownloadStatus.FAILED }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(x.name, style = MaterialTheme.typography.titleLarge, color = colors.text)
        Text(
            listOfNotNull(
                Fmt.plural(x.episodes.size, "Folge", "Folgen"),
                x.airDate.take(4).ifEmpty { null },
                if (watched > 0) "$watched gesehen" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDim,
        )
        if (x.overview.isNotEmpty()) {
            Text(x.overview, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!LocalOffline.current) {
                SonorusButton(if (done) "Staffel als ungesehen markieren" else "Staffel als gesehen markieren") { onMark(!done) }
                if (missing.isNotEmpty()) {
                    SonorusButton(if (missing.size == x.episodes.size) "Staffel herunterladen" else "Rest der Staffel herunterladen") {
                        vm.downloadVideos(missing.map { episodeItem(s, it) })
                    }
                }
            }
        }
    }
}

private fun episodeItem(s: ShowDetail, e: VideoEpisode) = VideoDownloads.Item(
    videoId = e.id,
    label = "${s.title} · ${VideoFmt.episodeCode(e.season, e.episode, e.episodeEnd)}",
    overview = e.overview,
    airDate = e.airDate,
)

@UnstableApi
@Composable
private fun EpisodeRow(
    vm: AppViewModel,
    s: ShowDetail,
    e: VideoEpisode,
    done: Boolean,
    onGo: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val code = if (e.season == 0) (e.episode?.let { "Special $it" } ?: "Special")
    else "Folge ${e.episode ?: "?"}${e.episodeEnd?.let { "-$it" } ?: ""}"
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(dip = 0.98f) { onGo(Routes.watch(e.id)) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(128.dp)
                .aspectRatio(WIDE_RATIO)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface2),
        ) {
            AsyncImage(
                model = vm.coverUrl(e.still),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (e.progress.started && !done) ProgressLine(e.progress, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RackLabelText(code, Modifier.weight(1f))
                if (e.duration > 0) Text(VideoFmt.durationLong(e.duration), style = num(11.sp), color = colors.textFaint)
            }
            Text(
                e.name.ifEmpty { code },
                style = MaterialTheme.typography.titleSmall,
                color = if (done) colors.textDim else colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (e.overview.isNotEmpty()) {
                Text(e.overview, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    if (done) "Als ungesehen markieren" else "Als gesehen markieren",
                    tint = if (done) colors.accent else colors.textFaint,
                    modifier = Modifier.size(24.dp),
                )
            }
            VideoDownloadIcon(vm, e.id) { episodeItem(s, e) }
        }
    }
}

// --- Downloading a film or an episode ------------------------------------------------------

/** The film page's download control: the round one, beside the buttons. */
@UnstableApi
@Composable
private fun VideoDownloadButton(vm: AppViewModel, videoId: Int, item: () -> VideoDownloads.Item) {
    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) { VideoDownloadIcon(vm, videoId, item) }
}

@UnstableApi
@Composable
private fun VideoDownloadIcon(vm: AppViewModel, videoId: Int, item: () -> VideoDownloads.Item) {
    val colors = SonorusTheme.colors
    val state by vm.videoDownloads.state.collectAsState()
    val offline = LocalOffline.current
    var confirming by remember { mutableStateOf(false) }
    val status = state.statusOf(videoId)
    if (offline && status != DownloadStatus.DONE) return

    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        when (status) {
            DownloadStatus.RUNNING -> {
                val shown by animateFloatAsState(state.progress, label = "videoRing")
                if (state.phase == VideoDownloads.Phase.PREPARING && state.progress <= 0f) {
                    CircularProgressIndicator(Modifier.size(34.dp), color = colors.textDim, trackColor = colors.surface2, strokeWidth = 3.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { shown },
                        modifier = Modifier.size(34.dp),
                        // Preparing on the server is dimmer than the file coming down.
                        color = if (state.phase == VideoDownloads.Phase.PREPARING) colors.textDim else colors.accent,
                        trackColor = colors.surface2,
                        strokeWidth = 3.dp,
                    )
                }
            }
            DownloadStatus.QUEUED -> CircularProgressIndicator(Modifier.size(34.dp), color = colors.textFaint, trackColor = colors.surface2, strokeWidth = 2.dp)
            else -> Unit
        }
        IconButton(
            onClick = {
                when (status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> vm.videoDownloads.cancel(videoId)
                    DownloadStatus.DONE -> confirming = true
                    else -> vm.downloadVideos(listOf(item()))
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            when (status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                    Icon(Icons.Filled.Stop, "Download abbrechen", tint = colors.accent, modifier = Modifier.size(16.dp))
                DownloadStatus.DONE ->
                    Icon(Icons.Filled.DownloadDone, "Heruntergeladen - antippen zum Entfernen", tint = colors.accent, modifier = Modifier.size(22.dp))
                DownloadStatus.FAILED ->
                    Icon(Icons.Filled.ErrorOutline, state.failed[videoId] ?: "Fehlgeschlagen - antippen für einen neuen Versuch", tint = colors.danger, modifier = Modifier.size(22.dp))
                DownloadStatus.NONE ->
                    Icon(Icons.Filled.Download, "Herunterladen", tint = colors.textDim, modifier = Modifier.size(22.dp))
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Download entfernen",
            message = "Das Video wird vom Gerät gelöscht. Ohne Verbindung zum Server lässt es sich dann nicht mehr ansehen.",
            confirmLabel = "Entfernen",
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                vm.removeVideoDownload(videoId)
            },
        )
    }
}

// --- Film series and people ----------------------------------------------------------

@UnstableApi
@Composable
fun VideoCollectionScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("videoCollection", id) { vm.lib.videoCollection(id).collection }
    LoadBox(load, skeleton = { DetailSkeleton() }) { c ->
        val watched = c.movies.count { it.progress.completed }
        val next = c.movies.firstOrNull { !it.progress.completed } ?: c.movies.firstOrNull()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 0.dp)) {
                    VideoHead(
                        vm,
                        backdrop = c.backdrop,
                        poster = c.poster,
                        title = c.name,
                        logo = null,
                        label = "Filmreihe",
                        facts = listOfNotNull(Fmt.plural(c.movies.size, "Film", "Filme"), if (watched > 0) "$watched gesehen" else null),
                        vote = null,
                    )
                    if (next?.videoId != null) {
                        Box(Modifier.padding(vertical = 8.dp)) {
                            SonorusButton(
                                when {
                                    next.progress.started -> "${next.title} fortsetzen"
                                    watched > 0 -> "Weiter mit ${next.title}"
                                    else -> "Mit dem ersten Film beginnen"
                                },
                                primary = true,
                            ) { onGo(Routes.watch(next.videoId)) }
                        }
                    }
                    DetailText("", c.overview)
                }
            }
            items(c.movies, key = { it.id }) { t -> TitlePoster(vm, t, Modifier, onGo) }
        }
    }
}

@UnstableApi
@Composable
fun VideoPersonScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("videoPerson", id) { vm.lib.videoPerson(id).person }
    val colors = SonorusTheme.colors
    LoadBox(load, skeleton = { DetailSkeleton(round = true) }) { p ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(vm.coverUrl(p.photo), Modifier.size(88.dp), CircleShape, p.name)
                    Column {
                        RackLabelText("Person")
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, color = colors.text)
                        Text(
                            listOfNotNull(
                                p.movies.size.takeIf { it > 0 }?.let { Fmt.plural(it, "Film", "Filme") },
                                p.shows.size.takeIf { it > 0 }?.let { Fmt.plural(it, "Serie", "Serien") },
                            ).joinToString(" · ") + " in deiner Bibliothek",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                        )
                    }
                }
            }
            items(p.movies + p.shows, key = { "${it.kind}-${it.id}" }) { t ->
                PosterCard(
                    vm,
                    poster = t.poster,
                    title = t.title,
                    sub = listOfNotNull(t.year?.toString(), t.roles.joinToString(", ").ifEmpty { null }).joinToString(" · "),
                ) { onGo(if (t.kind == "movie") Routes.movie(t.id) else Routes.show(t.id)) }
            }
        }
    }
}
