package org.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.sonorus.data.model.Ebook
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.LoadBox
import org.sonorus.ui.Routes
import org.sonorus.ui.components.CardGridSkeleton
import org.sonorus.ui.components.DetailSkeleton
import org.sonorus.ui.components.EmptyNote
import org.sonorus.ui.components.MediaCard
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusTheme

/**
 * The eBook shelf: authors, their books, and one book.
 *
 * Shaped like the spoken-word pages on purpose - the two clients are one
 * product and a shelf is a shelf. What is missing here is everything to do with
 * playing: no queue, no stars, no playlist, no download. A book is opened and
 * read, and the only state it carries is how far through it the reader got.
 *
 * **There is no offline half.** Reading needs the server, so [org.sonorus.data.Library]
 * throws rather than answering out of the downloads, and these screens show that
 * as the error it is. Downloads are their own session.
 */

@UnstableApi
@Composable
fun EbooksScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val load = rememberLoad("ebooks") { vm.lib.ebooks() }

    LoadBox(load, skeleton = { CardGridSkeleton() }) { data ->
        if (data.authors.isEmpty()) {
            return@LoadBox EmptyNote(
                "Noch keine eBooks. Sonorus liest sie aus dem Ordner, den der Server " +
                    "eingehängt hat - ein Ordner je Autor, darin ein Ordner je Buch."
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (data.carryOn.isNotEmpty()) {
                item { EbookCarryOnRow(vm, data.carryOn, onGo) }
            }
            item { EbookSectionLabel("Autoren") }
            items(data.authors, key = { it.id }) { author ->
                SpokenRow(
                    title = author.name,
                    subtitle = Fmt.plural(author.bookCount, "Buch", "Bücher"),
                    meta = "",
                    coverUrl = vm.coverUrl(author.cover),
                    round = true,
                ) { onGo(Routes.ebookAuthor(author.id)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun EbookAuthorScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("ebookAuthor", id) { vm.lib.ebookAuthor(id) }

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val author = data.author
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHead(
                    title = author.name,
                    meta = Fmt.plural(author.books.size, "Buch", "Bücher"),
                    coverUrls = listOfNotNull(vm.coverUrl(author.cover)),
                    round = true,
                    onPlay = { author.books.firstOrNull()?.let { onGo(Routes.ebook(it.id)) } },
                )
            }
            item { EbookSectionLabel("Bücher") }
            items(author.books, key = { it.id }) { book ->
                SpokenRow(
                    title = book.title,
                    subtitle = ebookSub(book),
                    meta = "",
                    coverUrl = vm.coverUrl(book.cover),
                ) { onGo(Routes.ebook(book.id)) }
            }
        }
    }
}

/**
 * One book: what it is, how far through it the reader is, and the one button
 * that matters.
 */
@UnstableApi
@Composable
fun EbookScreen(vm: AppViewModel, id: Int, onGo: (String) -> Unit) {
    val load = rememberLoad("ebook", id) { vm.lib.ebook(id) }
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    LoadBox(load, skeleton = { DetailSkeleton() }) { data ->
        val book = data.book
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DetailHead(
                    title = book.title,
                    artist = book.author,
                    meta = listOfNotNull(
                        book.year?.takeIf { it > 0 }?.toString(),
                        book.publisher.takeIf { it.isNotEmpty() },
                        Fmt.plural(book.documents, "Kapitel", "Kapitel"),
                    ).joinToString(" · "),
                    coverUrls = listOfNotNull(vm.coverUrl(book.cover)),
                    ratio = 2f / 3f,
                    // The play button reads the book. There is nothing else it
                    // could sensibly do, and a reader expects the cover to open
                    // the book rather than a second control below it.
                    onPlay = { onGo(Routes.reader(book.id)) },
                )
            }
            if (book.progress.started) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            "Gelesen: ${(book.progress.read * 100).toInt()} %",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                        )
                    }
                }
            }
            // The same button the spoken word has, for the same reason: a book
            // finished on the last page still needs somebody to say so, and a
            // book started by accident needs taking back.
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SonorusButton(
                        text = if (book.progress.finished) "Als ungelesen markieren"
                        else "Als gelesen markieren",
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { vm.setEbookFinished(book, !book.progress.finished) }
                                    .onFailure { vm.say(it.message ?: "Konnte nicht gespeichert werden.") }
                                busy = false
                                load.reload()
                            }
                        },
                    )
                }
            }
            if (book.description.isNotEmpty()) {
                item { EbookSectionLabel("Klappentext") }
                item {
                    Text(
                        book.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// --- Bits ---------------------------------------------------------------------

private fun ebookSub(b: Ebook): String = when {
    b.progress.finished -> "gelesen"
    b.progress.started -> "noch ${(100 - b.progress.read * 100).toInt()} %"
    else -> Fmt.plural(b.documents, "Kapitel", "Kapitel")
}

@Composable
private fun EbookSectionLabel(text: String) {
    RackLabelText(text, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
}

@UnstableApi
@Composable
private fun EbookCarryOnRow(vm: AppViewModel, books: List<Ebook>, onGo: (String) -> Unit) {
    Column {
        EbookSectionLabel("Weiterlesen")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(books, key = { it.id }) { book ->
                MediaCard(
                    title = book.title,
                    subtitle = ebookSub(book),
                    coverUrl = vm.coverUrl(book.cover),
                    modifier = Modifier.width(140.dp),
                    // A book cover is portrait. Square crops the title off it.
                    ratio = 2f / 3f,
                ) { onGo(Routes.ebook(book.id)) }
            }
        }
    }
}
