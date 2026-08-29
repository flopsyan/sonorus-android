package org.sonorus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import org.sonorus.data.model.Book
import org.sonorus.data.model.Track
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Routes
import org.sonorus.ui.components.CardGridSkeleton
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.DetailSkeleton
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.MediaCard
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusTheme

/**
 * Podcasts, Hörbücher and Hörspiele - the three spoken-word libraries.
 *
 * They arrived on the web on 2026-08-19 and were still missing here ten weeks
 * later, which is the gap that sharpened the cross-repo rule in the vault. The
 * shape follows the web app deliberately, because the two clients are one
 * product: a show is a list of episodes with a remembered position, and a book
 * is **one thing** whose files are never shown.
 *
 * Audiobooks and radio plays are the same three screens twice, told apart by
 * `base` - "audiobooks" or "audiodramas", the same word the server's paths use.
 * The one real difference is the narrator: a play has a cast, so it carries no
 * "Gesprochen von" line.
 */

/** The words that differ between the two book-shaped libraries. */
data class SpokenWords(
    val base: String,
    val plural: String,
    val label: String,
    val one: String,
    val many: String,
    val section: String,
)

fun spokenWords(base: String): SpokenWords =
    if (base == "audiodramas") {
        SpokenWords("audiodramas", "Hörspiele", "Hörspiel", "Hörspiel", "Hörspiele", "Hörspiele")
    } else {
        SpokenWords("audiobooks", "Hörbücher", "Hörbuch", "Buch", "Bücher", "Bücher")
    }

// --- Podcasts ----------------------------------------------------------------

@UnstableApi
@Composable
fun PodcastsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("podcasts") { vm.lib.podcasts() }

    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        if (data.podcasts.isEmpty()) {
            return@LoadBox EmptyNote(
                "Noch keine Podcasts. Sonorus liest sie aus dem Ordner, den der Server " +
                    "unter PODCAST_DIR eingehängt hat - ein Unterordner je Sendung."
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (data.carryOn.isNotEmpty()) {
                item {
                    CarryOnRow("Weiterhören", data.carryOn) { episode ->
                        vm.player.playTracks(
                            data.carryOn, data.carryOn.indexOf(episode), "Weiterhören", "podcasts:continue"
                        )
                    }
                }
            }
            item { SectionLabel("Sendungen") }
            items(data.podcasts, key = { it.id }) { show ->
                SpokenRow(
                    title = show.name,
                    subtitle = listOfNotNull(
                        Fmt.plural(show.episodeCount, "Folge", "Folgen"),
                        "${Fmt.number(show.unplayedCount)} ungehört".takeIf { show.unplayedCount > 0 },
                    ).joinToString(" · "),
                    meta = Fmt.durationRack(show.duration),
                    coverUrl = vm.coverUrl(show.cover),
                ) { onGo(Routes.podcast(show.id)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun PodcastScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    var sort by remember { mutableStateOf<String?>(null) }
    val load = rememberLoad("podcast", id, sort) { vm.lib.podcast(id, sort) }
    val player by vm.player.state.collectAsState()
    val key = Routes.podcast(id)

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val show = data.podcast
        val source = "Podcast: ${show.name}"
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHead(
                    title = show.name,
                    artist = "",
                    meta = listOfNotNull(
                        Fmt.plural(show.episodeCount, "Folge", "Folgen"),
                        "${Fmt.number(show.unplayedCount)} ungehört".takeIf { show.unplayedCount > 0 },
                        Fmt.durationLong(show.duration),
                    ).joinToString(" · "),
                    coverUrls = listOfNotNull(vm.coverUrl(show.cover)),
                    // Playing a show means carrying on where you stopped, or the
                    // newest episode when you have not started - which is what
                    // the list is already ordered by.
                    onPlay = { vm.player.playCollection(show.episodes, source, key) },
                )
            }
            if (show.description.isNotEmpty()) {
                item {
                    Text(
                        show.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonorusTheme.colors.textDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                // The order is remembered on the account, so it follows the
                // listener to the web app rather than being a fact about
                // this phone.
                SortToggle(
                    newest = show.sort != "old",
                    onPick = { sort = if (it) "new" else "old" },
                )
            }
            itemsIndexed(show.episodes, key = { _, e -> e.id }) { index, episode ->
                EpisodeRow(
                    episode = episode,
                    running = player.current?.id == episode.id,
                ) { vm.player.playTracks(show.episodes, index, source, key) }
            }
            if (show.episodes.isEmpty()) item { EmptyNote("Diese Sendung hat noch keine Folgen.") }
        }
    }
}

// --- Audiobooks and radio plays ----------------------------------------------

@UnstableApi
@Composable
fun SpokenScreen(vm: AppViewModel, base: String, onGo: (String) -> Unit) {
    val words = spokenWords(base)
    val load = rememberLoad("spoken", base) { vm.lib.spoken(base) }

    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        if (data.authors.isEmpty()) {
            return@LoadBox EmptyNote(
                "Noch keine ${words.plural}. Sonorus liest sie aus dem Ordner, den der Server " +
                    "eingehängt hat - ein Ordner je Autor, darin ein Ordner je ${words.label}."
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (data.carryOn.isNotEmpty()) {
                item { BookCarryOnRow(vm, data.carryOn, words, onGo) }
            }
            item { SectionLabel("Autoren") }
            // A plain list rather than a grid of round pictures: an author is a
            // name and a count, and the list says more per screenful.
            items(data.authors, key = { it.id }) { author ->
                SpokenRow(
                    title = author.name,
                    subtitle = Fmt.plural(author.bookCount, words.one, words.many),
                    meta = Fmt.durationRack(author.duration),
                    coverUrl = vm.coverUrl(author.cover),
                    round = true,
                ) { onGo(Routes.spokenAuthor(base, author.id)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun SpokenAuthorScreen(vm: AppViewModel, base: String, id: Int, onGo: (String) -> Unit) {
    val words = spokenWords(base)
    val load = rememberLoad("spokenAuthor", base, id) { vm.lib.spokenAuthor(base, id) }
    var editing by remember { mutableStateOf(false) }

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val author = data.author
        if (editing) {
            EditAuthorDialog(
                vm = vm,
                base = base,
                author = author,
                onDismiss = { editing = false },
                onSaved = { load.reload() },
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHead(
                    title = author.name,
                    meta = listOfNotNull(
                        Fmt.plural(author.books.size, words.one, words.many),
                        Fmt.durationLong(author.books.sumOf { it.duration }),
                    ).joinToString(" · "),
                    coverUrls = listOfNotNull(vm.coverUrl(author.cover)),
                    round = true,
                    // Nothing to press play on: an author is a shelf, and which
                    // book to open is the listener's choice.
                    onPlay = { author.books.firstOrNull()?.let { onGo(Routes.book(base, it.id)) } },
                    onEdit = { editing = true },
                )
            }
            item { SectionLabel(words.section) }
            items(author.books, key = { it.id }) { book ->
                SpokenRow(
                    title = book.title,
                    subtitle = bookSub(book),
                    meta = Fmt.durationRack(book.duration),
                    coverUrl = vm.coverUrl(book.cover),
                ) { onGo(Routes.book(base, book.id)) }
            }
        }
    }
}

/**
 * One book or one play.
 *
 * **No parts list**, and that is the whole feature: however many files the rip
 * produced, the page has a cover, an author, a length, one button and one bar.
 */
@UnstableApi
@Composable
fun BookScreen(vm: AppViewModel, base: String, id: Int, onGo: (String) -> Unit) {
    val words = spokenWords(base)
    val load = rememberLoad("book", base, id) { vm.lib.book(base, id) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val book = data.book
        if (editing) {
            EditBookDialog(
                vm = vm,
                base = base,
                book = book,
                onDismiss = { editing = false },
                onSaved = { load.reload() },
            )
        }
        val source = "${words.label}: ${book.title}"
        val key = Routes.book(base, id)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHead(
                    title = book.title,
                    artist = book.author,
                    meta = listOfNotNull(
                        // Between the author and the length, which is the order
                        // the question comes in. A play has a cast and the
                        // server sends none, so this never appears for one.
                        "Gesprochen von ${book.narrator}".takeIf { book.narrator.isNotEmpty() },
                        Fmt.releaseDate(book.releaseDate).takeIf { it.isNotEmpty() },
                        Fmt.durationLong(book.duration),
                        if (book.finished) "gehört"
                        else if (book.started) "noch ${Fmt.durationLong(book.remaining)}" else null,
                    ).joinToString(" · "),
                    coverUrls = listOfNotNull(vm.coverUrl(book.cover)),
                    // The parts, in order, opened at the part and second the
                    // listener stopped at. They are handed to the queue and
                    // never drawn.
                    onPlay = {
                        vm.player.playTracks(
                            book.parts,
                            book.resume.index.coerceIn(0, maxOf(0, book.parts.size - 1)),
                            source,
                            key,
                            book.resume.offset,
                        )
                    },
                    onEdit = { editing = true },
                )
            }
            if (book.started && !book.finished) {
                item { BookProgress(book) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SonorusButton(
                        text = if (book.finished) "Als ungehört markieren" else "Als gehört markieren",
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { vm.api.setBookHeard(base, id, !book.finished) }
                                    .onFailure { vm.say(it.message ?: "Konnte nicht gespeichert werden.") }
                                busy = false
                                load.reload()
                            }
                        },
                    )
                }
            }
            if (book.chapters.isNotEmpty()) {
                item { SectionLabel("Kapitel") }
                itemsIndexed(book.chapters, key = { _, c -> c.index }) { index, chapter ->
                    ChapterRow(
                        number = index + 1,
                        title = chapter.title.ifEmpty { "Kapitel ${index + 1}" },
                        at = chapter.start,
                    ) {
                        // Open the part the chapter lies in, at its offset.
                        val at = chapter.part.coerceIn(0, maxOf(0, book.parts.size - 1))
                        vm.player.playTracks(book.parts, at, source, key, chapter.offset)
                    }
                }
            }
        }
    }
}

// --- Pieces ------------------------------------------------------------------

/** What a book says about itself in a list: who wrote it, how much is left. */
private fun bookSub(b: Book): String = when {
    b.finished -> "${b.author} · gehört"
    b.started -> "${b.author} · noch ${Fmt.durationRack(b.duration - b.elapsed)}"
    else -> "${b.author} · ${Fmt.durationRack(b.duration)}"
}

@Composable
private fun SectionLabel(text: String) {
    RackLabelText(text, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
}

/**
 * A row with a picture, a name and two facts. The list shape the spoken-word
 * pages use throughout - a shelf reads better as a list than as a grid, because
 * the count and the length are worth as much as the name.
 */
@Composable
private fun SpokenRow(
    title: String,
    subtitle: String,
    meta: String,
    coverUrl: String?,
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
        Cover(
            coverUrl,
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
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
        }
    }
}

/** One episode: what it is called, when it came out, and how far in you are. */
@Composable
private fun EpisodeRow(episode: Track, running: Boolean, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Text(
            episode.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (running) colors.accent else colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                Fmt.date(episode.releaseDate).takeIf { it.isNotEmpty() },
                Fmt.durationRack(episode.duration),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDim,
        )
        // The absence of a bar is what says "not started" - a badge on 360 of
        // 361 rows would be ink rather than information.
        val done = episode.position / episode.duration
        if (episode.duration > 0 && done > 0.001) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.line)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(done.coerceIn(0.0, 1.0).toFloat())
                        .height(3.dp)
                        .background(if (episode.completed) colors.textFaint else colors.accent)
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(number: Int, title: String, at: Double, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
            modifier = Modifier.width(28.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(Fmt.duration(at), style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
    }
}

@Composable
private fun BookProgress(book: Book) {
    val colors = SonorusTheme.colors
    val done = if (book.duration > 0) (book.elapsed / book.duration).coerceIn(0.0, 1.0) else 0.0
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.line)
        ) {
            Box(Modifier.fillMaxWidth(done.toFloat()).height(4.dp).background(colors.accent))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${Fmt.durationLong(book.elapsed)} von ${Fmt.durationLong(book.duration)} · ${(done * 100).toInt()} %",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDim,
        )
    }
}

@UnstableApi
@Composable
private fun CarryOnRow(label: String, episodes: List<Track>, onPlay: (Track) -> Unit) {
    Column {
        SectionLabel(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(episodes, key = { it.id }) { episode ->
                Column(Modifier.width(150.dp).clickable { onPlay(episode) }) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonorusTheme.colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        episode.podcast,
                        style = MaterialTheme.typography.bodySmall,
                        color = SonorusTheme.colors.textDim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun BookCarryOnRow(
    vm: AppViewModel,
    books: List<Book>,
    words: SpokenWords,
    onGo: (String) -> Unit,
) {
    Column {
        SectionLabel("Weiterhören")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(books, key = { it.id }) { book ->
                MediaCard(
                    title = book.title,
                    subtitle = bookSub(book),
                    coverUrl = vm.coverUrl(book.cover),
                    modifier = Modifier.width(150.dp),
                ) { onGo(Routes.book(words.base, book.id)) }
            }
        }
    }
}

/** Newest first or oldest first, remembered on the account. */
@Composable
private fun SortToggle(newest: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("Neueste zuerst", newest) { onPick(true) }
        Chip("Älteste zuerst", !newest) { onPick(false) }
    }
}

