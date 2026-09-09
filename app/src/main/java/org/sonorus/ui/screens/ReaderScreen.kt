package org.sonorus.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import org.sonorus.data.ReaderFont
import org.sonorus.data.ReaderStyle
import org.sonorus.data.download.LocalBook
import org.sonorus.data.model.Ebook
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.LoadBox
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusColors
import org.sonorus.ui.theme.SonorusTheme
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The reading view: the book's own document in a WebView, everything around it
 * in Compose.
 *
 * **Why a WebView at all.** An EPUB is HTML with its own stylesheet, and a book
 * that drops its own formatting is a book that reads wrong. Compose would mean
 * writing an HTML renderer; this way the engine that was built for the job does
 * the work, and the server hands it a page that already carries the reading
 * view's CSS and script (`public/reader/`).
 *
 * **Why every request goes through OkHttp.** The documents sit behind the same
 * login as everything else, and a WebView has its own cookie store that knows
 * nothing about the app's session. [shouldInterceptRequest] answers each one out
 * of [org.sonorus.data.SonorusApi.client] instead, which carries the session and,
 * later, is where a downloaded book would be served from without touching this
 * screen.
 *
 * The division of labour with the page's own script is the important part and it
 * is not obvious: **how many pages a chapter has is a question only the layout
 * engine can answer**, so paging lives in `reader.js` and this screen only asks.
 * It hears back through the `SonorusReader` bridge - a page turn, a tap in the
 * middle, and running off either end of a chapter.
 *
 * The page number of the *whole* book is that same question asked of every
 * chapter, which is what [MeasuringView] is for.
 */
@UnstableApi
@Composable
fun ReaderScreen(vm: AppViewModel, id: Int, onBack: () -> Unit) {
    val load = rememberLoad("ebookRead", id) { vm.lib.ebook(id) }
    LoadBox(load) { data -> Reader(vm, data.book, onBack) }
}

/** What the page is showing right now, as `reader.js` last reported it. */
private data class PageState(val page: Int = 0, val pages: Int = 1, val ratio: Double = 0.0)

/** A place in the book worth being able to come back to. */
private data class Place(val doc: Int, val ratio: Double, val page: Int)

@UnstableApi
@Composable
private fun Reader(vm: AppViewModel, book: Ebook, onBack: () -> Unit) {
    val colors = SonorusTheme.colors
    val context = LocalContext.current
    val style by vm.readerStyle.collectAsState()

    // Which spine document is open, and where in it. The document is state
    // because changing it loads a page; the place inside it is not, because
    // the page owns that and only reports it.
    var doc by remember { mutableIntStateOf(book.progress.doc.coerceIn(0, lastDoc(book))) }
    var page by remember { mutableStateOf(PageState()) }
    var overlay by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // Where to land when the document has finished laying out: the stored share
    // on the first open, the far end when a chapter was entered backwards.
    var enterAt by remember { mutableStateOf(book.progress.ratio) }
    var enterFromEnd by remember { mutableStateOf(false) }

    // The book's page numbers, and the place to come back to after a jump.
    val paging = remember(book.id) { Paging(book) }
    var jumpBack by remember { mutableStateOf<Place?>(null) }
    // What the finger is pointing at while the bar is being dragged, as a share
    // of the whole book. Nothing moves until the finger lifts.
    var scrub by remember { mutableStateOf<Float?>(null) }

    var web by remember { mutableStateOf<WebView?>(null) }

    val latestDoc by rememberUpdatedState(doc)
    val latestPage by rememberUpdatedState(page)

    fun save(finished: Boolean = false) {
        vm.saveEbookProgress(book.id, latestDoc, latestPage.ratio, finished)
    }

    fun here(): Place = Place(latestDoc, latestPage.ratio, paging.pageOfBook(latestDoc, latestPage))

    fun goToDoc(next: Int, fromEnd: Boolean) {
        val target = next.coerceIn(0, lastDoc(book))
        if (target == doc) return
        save()
        enterAt = if (fromEnd) 1.0 else 0.0
        enterFromEnd = fromEnd
        doc = target
    }

    /** A jump the reader can undo: anywhere in the book, from anywhere in it. */
    fun jumpTo(target: Int, at: Double) {
        val to = target.coerceIn(0, lastDoc(book))
        jumpBack = here()
        save()
        if (to == doc) {
            web?.evalReader("Reader.goToRatio($at)")
            return
        }
        enterAt = at
        enterFromEnd = false
        doc = to
    }

    // The chip is a way back, not a fixture. Half a minute is long enough to
    // look around after a jump and still find the way home.
    LaunchedEffect(jumpBack) {
        if (jumpBack != null) {
            delay(30_000)
            jumpBack = null
        }
    }

    // The overlay is what the back button closes first; only with it closed does
    // back leave the book. Leaving is also the save that has to land.
    BackHandler(enabled = true) {
        when {
            sheet != Sheet.NONE -> sheet = Sheet.NONE
            overlay -> overlay = false
            else -> {
                save()
                onBack()
            }
        }
    }

    DisposableEffect(book.id) {
        onDispose { save() }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onState(json: String) {
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                val state = PageState(
                    page = o.optInt("page", 0),
                    pages = o.optInt("pages", 1).coerceAtLeast(1),
                    ratio = o.optDouble("ratio", 0.0),
                )
                page = state
                // The chapter on screen is a measurement too, and the first one
                // to arrive - the estimate for the rest is built on it.
                paging.saw(latestDoc, state.pages)
                if (o.optString("reason") == "turn") save()
            }

            @JavascriptInterface
            fun onTap() {
                overlay = !overlay
            }

            /** Ran off an end of the chapter: the next one, or the one before. */
            @JavascriptInterface
            fun onEdge(where: String) {
                if (where == "end") goToDoc(latestDoc + 1, fromEnd = false)
                else goToDoc(latestDoc - 1, fromEnd = true)
            }

            /**
             * A link inside the book. Only one that names a document of this
             * spine is followed - an outside link has no business opening a
             * browser out of a reading view.
             */
            @JavascriptInterface
            fun onLink(href: String) {
                val clean = href.substringBefore('#')
                val target = book.spine.indexOfFirst { it.endsWith(clean) }
                if (target >= 0) jumpTo(target, 0.0)
            }
        }
    }

    // What the view is already showing. **Not state**, and that is the point:
    // `update` runs on every recomposition, and the page reports its own state
    // through the bridge - so a plain `loadUrl` in there reloads the document
    // every time the reader turns a page, which is a reload loop and a blank
    // screen. These three say what has really been done to the view.
    val shown = remember { Loaded() }
    val url = vm.api.ebookReadUrl(book.id, hrefOf(book, doc))
    val latestStyle by rememberUpdatedState(style)

    val footer = paging.footerText(doc, page)

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        // The tape measure, first in the box so the real page is drawn over it.
        // It is laid out at exactly the size of the visible one, because a page
        // count is a fact about a screen.
        MeasuringView(
            vm = vm,
            book = book,
            style = style,
            colors = colors,
            paging = paging,
            modifier = Modifier.matchParentSize(),
        )

        AndroidView(
            factory = {
                WebView(context).apply {
                    web = this
                    // Definite, not WRAP_CONTENT. A WebView told to wrap its
                    // content has no height to resolve `vh` and `%` against, so
                    // `height: calc(100vh - ...)` computes to 0 - and a column
                    // of zero height turns one chapter into 800 empty pages.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    readerDefaults(colors)
                    addJavascriptInterface(bridge, "SonorusReader")
                    webViewClient = ReaderClient(vm, book) {
                        applyStyle(this, latestStyle, colors)
                        shown.style = latestStyle
                        shown.footer = null
                        // Land where the reader was. reader.js holds on to the
                        // share until it has broken the text into columns, so
                        // this may be said before there is a page to say it of.
                        if (enterFromEnd) evalReader("Reader.goToEnd()")
                        else evalReader("Reader.goToRatio(${enterAt})")
                    }
                }
            },
            update = { view ->
                if (shown.url != url) {
                    shown.url = url
                    view.loadUrl(url)
                    return@AndroidView
                }
                // Restyling is not a reload: reader.js keeps the reader's place
                // across it, which is why style() exists on that side.
                if (shown.style != style) {
                    shown.style = style
                    applyStyle(view, style, colors)
                }
                if (shown.footer != footer) {
                    shown.footer = footer
                    view.evalReader("Reader.footer(${JSONObject.quote(footer)})")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = book.title,
                chapter = chapterTitle(book, doc),
                onBack = { save(); onBack() },
                onChapters = { sheet = Sheet.CHAPTERS },
                onStyle = { sheet = Sheet.STYLE },
            )
        }

        // The way back sits above the bar rather than in it, because it outlives
        // the bar: the reader closes the menu and reads on, and the chip has to
        // still be there.
        Column(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = jumpBack != null && sheet == Sheet.NONE,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                val target = jumpBack
                JumpBackChip(
                    label = "Zurück zu Seite ${target?.page ?: 1}",
                    onClick = {
                        val to = target ?: return@JumpBackChip
                        jumpBack = null
                        if (to.doc == doc) {
                            web?.evalReader("Reader.goToRatio(${to.ratio})")
                        } else {
                            enterAt = to.ratio
                            enterFromEnd = false
                            doc = to.doc
                        }
                    },
                )
            }
            AnimatedVisibility(
                visible = overlay,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                ReaderBottomBar(
                    paging = paging,
                    doc = doc,
                    page = page,
                    scrub = scrub,
                    onScrub = { scrub = it },
                    onSeek = { share ->
                        scrub = null
                        val (target, at) = paging.placeAt(share)
                        jumpTo(target, at)
                    },
                )
            }
        }

        when (sheet) {
            Sheet.CHAPTERS -> ChapterSheet(
                book = book,
                current = doc,
                onPick = { jumpTo(it, 0.0); sheet = Sheet.NONE; overlay = false },
                onDismiss = { sheet = Sheet.NONE },
            )
            Sheet.STYLE -> StyleSheet(
                style = style,
                onChange = { vm.setReaderStyle(it) },
                onDismiss = { sheet = Sheet.NONE },
            )
            Sheet.NONE -> Unit
        }
    }
}

private enum class Sheet { NONE, CHAPTERS, STYLE }

/** What has really been pushed into the view, as opposed to what Compose holds. */
private class Loaded(
    var url: String = "",
    var style: ReaderStyle? = null,
    var footer: String? = null,
)

// --- Counting the pages of a whole book ---------------------------------------

/**
 * How many pages the book has, and which one is on screen.
 *
 * There is no such thing as *the* page count of an EPUB: a page is whatever
 * fits on this screen at this font size, so the number changes with every one
 * of the four settings and with turning the phone. It can only be measured, by
 * laying every chapter out once - which is what [MeasuringView] does while the
 * reader reads.
 *
 * Until that has finished the count is **estimated** from the character counts
 * the server sent, calibrated against the chapters that have been laid out
 * already. That way a book opens with a number instead of a wait, and the
 * number it opens with is close enough that the exact one does not read as a
 * correction.
 */
private class Paging(private val book: Ebook) {
    /** Measured pages per document; null where nothing has been laid out yet. */
    private val counts = mutableStateOf<List<Int?>>(List(book.documents.coerceAtLeast(1)) { null })

    private val lengths: List<Int> =
        if (book.lengths.size == book.documents) book.lengths else emptyList()

    fun saw(doc: Int, pages: Int) {
        if (doc !in counts.value.indices || pages < 1) return
        if (counts.value[doc] == pages) return
        counts.value = counts.value.toMutableList().also { it[doc] = pages }
    }

    fun seen(doc: Int): Int? = counts.value.getOrNull(doc)

    /** Everything measured, in spine order, for the store. Null if incomplete. */
    fun measured(): List<Int>? = counts.value.takeIf { l -> l.all { it != null } }?.map { it!! }

    fun load(pages: List<Int>) {
        if (pages.size == counts.value.size && pages.all { it >= 1 }) counts.value = pages
    }

    fun forget() {
        counts.value = List(book.documents.coerceAtLeast(1)) { null }
    }

    /**
     * Characters that fit on one page, taken from what has actually been laid
     * out. Without a single measurement there is nothing to calibrate against,
     * and the estimate falls back to a paperback page.
     */
    private fun charsPerPage(): Double {
        if (lengths.isEmpty()) return FALLBACK_CHARS
        var chars = 0L
        var pages = 0L
        counts.value.forEachIndexed { i, measured ->
            if (measured != null) {
                chars += lengths.getOrElse(i) { 0 }.toLong()
                pages += measured.toLong()
            }
        }
        return if (chars > 0 && pages > 0) max(1.0, chars.toDouble() / pages) else FALLBACK_CHARS
    }

    fun pagesOf(doc: Int): Int {
        counts.value.getOrNull(doc)?.let { return it }
        val chars = lengths.getOrNull(doc) ?: return 1
        return max(1, (chars / charsPerPage()).roundToInt())
    }

    fun total(): Int = counts.value.indices.sumOf { pagesOf(it) }.coerceAtLeast(1)

    fun before(doc: Int): Int = (0 until doc).sumOf { pagesOf(it) }

    /** Which page of the whole book the reader is looking at, counted from 1. */
    fun pageOfBook(doc: Int, page: PageState): Int =
        (before(doc) + page.page + 1).coerceIn(1, total())

    /**
     * How far through the whole book the reader is.
     *
     * Weighted by how much text each document holds rather than by their count:
     * a book that opens with eight one-line front-matter pages would otherwise
     * be "18 % read" before the first sentence.
     */
    fun share(doc: Int, ratio: Double): Double {
        if (lengths.isEmpty()) {
            val total = book.documents.coerceAtLeast(1)
            return ((doc + ratio) / total).coerceIn(0.0, 1.0)
        }
        val total = lengths.sumOf { it.toLong() }.coerceAtLeast(1L).toDouble()
        val before = lengths.take(doc).sumOf { it.toLong() }.toDouble()
        val here = (lengths.getOrNull(doc) ?: 0).toDouble() * ratio
        return ((before + here) / total).coerceIn(0.0, 1.0)
    }

    /** The reverse: the place in the book that a share of it names. */
    fun placeAt(share: Float): Pair<Int, Double> {
        val at = share.coerceIn(0f, 1f).toDouble()
        if (lengths.isEmpty()) {
            val docs = book.documents.coerceAtLeast(1)
            val exact = at * docs
            val doc = exact.toInt().coerceIn(0, docs - 1)
            return doc to (exact - doc).coerceIn(0.0, 1.0)
        }
        val total = lengths.sumOf { it.toLong() }.coerceAtLeast(1L).toDouble()
        var wanted = at * total
        lengths.forEachIndexed { i, len ->
            if (wanted <= len || i == lengths.lastIndex) {
                return i to (if (len > 0) (wanted / len).coerceIn(0.0, 1.0) else 0.0)
            }
            wanted -= len
        }
        return 0 to 0.0
    }

    /** The line in the book's bottom margin: `38/379 (10,0 %)`. */
    fun footerText(doc: Int, page: PageState): String =
        "${pageOfBook(doc, page)}/${total()} (${percent(share(doc, page.ratio))})"

    private companion object {
        /** A page of a paperback, for a book nothing has been laid out of yet. */
        const val FALLBACK_CHARS = 1400.0
    }
}

/** A share as German per cent, with the one decimal that makes it move. */
private fun percent(share: Double): String =
    String.format(Locale.GERMANY, "%.1f %%", (share * 100).coerceIn(0.0, 100.0))

/**
 * A WebView nobody sees, which lays every chapter out once to count its pages.
 *
 * A second view rather than the reader's own, because the reader is busy showing
 * a chapter and navigating it away and back would be visible. Everything that
 * decides where a line breaks is part of the key: the four settings and the size
 * of the view. Change one and the old counts are counts of another book.
 *
 * What it finds is kept on the phone, so a book opened again with the same
 * settings has its numbers at once.
 */
@Composable
private fun MeasuringView(
    vm: AppViewModel,
    book: Ebook,
    style: ReaderStyle,
    colors: SonorusColors,
    paging: Paging,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var size by remember { mutableStateOf(0 to 0) }

    AndroidView(
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                readerDefaults(colors)
                view = this
            }
        },
        // Invisible and out of reach: the real page is drawn over it and takes
        // every touch, and alpha 0 keeps a half-drawn chapter from flickering
        // into sight between two measurements.
        //
        // The size comes from the layout rather than from the view, because
        // asking the view for it is asking too early: a `post` from `update`
        // still runs before the first layout and answers 0 by 0, and a size
        // that never changes again never starts the count.
        modifier = modifier
            .alpha(0f)
            .onSizeChanged { size = it.width to it.height },
    )

    LaunchedEffect(view, book.id, style, size) {
        val web = view ?: return@LaunchedEffect
        if (size.first <= 0 || size.second <= 0) return@LaunchedEffect
        val key = pagesKey(style, size)

        paging.forget()
        vm.storedReaderPages(book.id, key)?.let {
            paging.load(it)
            return@LaunchedEffect
        }
        // In reading order, so the pages just ahead of the reader are right
        // before the ones at the end of the book are.
        var failed = false
        for (doc in 0 until book.documents) {
            if (paging.seen(doc) != null) continue
            val pages = withTimeoutOrNull(SLOW_CHAPTER_MS) {
                measureChapter(web, vm, book, doc, style, colors)
            }
            if (pages == null) failed = true
            paging.saw(doc, pages ?: 1)
        }
        // A run that gave up on a chapter is not worth keeping: it would be
        // read back as fact on every later open, and the book would be one
        // page short for good.
        if (!failed) paging.measured()?.let { vm.storeReaderPages(book.id, key, it) }
    }
}

/** Everything a page count depends on, as one string. */
private fun pagesKey(style: ReaderStyle, size: Pair<Int, Int>): String =
    "${style.font.wire}/${style.size}/${style.leading}/${style.margin}/${size.first}x${size.second}"

/** Lays one chapter out in [web] and answers how many pages it came to. */
private suspend fun measureChapter(
    web: WebView,
    vm: AppViewModel,
    book: Ebook,
    doc: Int,
    style: ReaderStyle,
    colors: SonorusColors,
): Int = suspendCancellableCoroutine { cont ->
    web.webViewClient = ReaderClient(vm, book) {
        applyStyle(this, style, colors)
        countPages(this, 0) { pages -> if (cont.isActive) cont.resume(pages) }
    }
    web.loadUrl(vm.api.ebookReadUrl(book.id, hrefOf(book, doc)))
}

/**
 * Asks the page what it came to, and asks again until the book's own faces have
 * arrived.
 *
 * `Reader.measureNow()` rather than the usual report, because this view is
 * never drawn and therefore never given a frame - the reader's own measurement
 * runs on `requestAnimationFrame` and would simply never happen here.
 */
private fun countPages(web: WebView, tries: Int, then: (Int) -> Unit) {
    web.postDelayed({
        web.evaluateJavascript("window.Reader ? Reader.measureNow() : null") { answer ->
            val seen = measurementIn(answer)
            if (seen.fonts || tries >= FONT_TRIES) then(seen.pages)
            else countPages(web, tries + 1, then)
        }
    }, if (tries == 0) SETTLE_MS else FONT_WAIT_MS)
}

private data class Measurement(val pages: Int, val fonts: Boolean)

/**
 * The measurement out of what `evaluateJavascript` hands back.
 *
 * It arrives as a JSON *string* holding JSON - the quoting is the bridge's, not
 * the reader's - so it is unwrapped once before it is read.
 */
private fun measurementIn(answer: String?): Measurement {
    if (answer.isNullOrBlank() || answer == "null") return Measurement(1, false)
    val inner = runCatching { JSONObject("{\"v\":$answer}").getString("v") }.getOrNull()
        ?: return Measurement(1, false)
    val o = runCatching { JSONObject(inner) }.getOrNull() ?: return Measurement(1, false)
    return Measurement(o.optInt("pages", 1).coerceAtLeast(1), o.optBoolean("fonts", false))
}

private const val SETTLE_MS = 60L
private const val FONT_WAIT_MS = 100L
private const val FONT_TRIES = 12
private const val SLOW_CHAPTER_MS = 8_000L

// --- The page's own half ------------------------------------------------------

/** The settings every reading WebView needs, seen or unseen. */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.readerDefaults(colors: SonorusColors) {
    setBackgroundColor(colors.bg.toArgb())
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    // The text is laid out in columns of exactly one viewport, so anything that
    // rescales it breaks the page count.
    settings.useWideViewPort = false
    settings.loadWithOverviewMode = false
    settings.builtInZoomControls = false
    settings.textZoom = 100
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
}

/**
 * Serves the WebView out of the app's HTTP client.
 *
 * Everything the book asks for - the document, its pictures, its stylesheet,
 * the reader's own CSS and JS, the Ubuntu faces - comes back through the same
 * session as every other request. A WebView left to fetch on its own would be
 * answered with the login page.
 */
private class ReaderClient(
    private val vm: AppViewModel,
    private val book: Ebook,
    private val onReady: WebView.() -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith(vm.api.serverUrl)) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
        // A downloaded book is read off the phone even with the server right
        // there: it is already here, and it is the only way the same screen
        // works on a train.
        fromDisk(url)?.let { return it }
        return runCatching {
            val response = vm.api.client.newCall(Request.Builder().url(url).build()).execute()
            val type = response.header("Content-Type").orEmpty()
            WebResourceResponse(
                type.substringBefore(';').trim().ifEmpty { "application/octet-stream" },
                "utf-8",
                response.code,
                // A WebView refuses a response with an empty reason phrase.
                response.message.ifEmpty { "OK" },
                emptyMap(),
                ByteArrayInputStream(response.body.bytes()),
            )
        }.getOrNull()
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.onReady()
    }

    /** The book and the reader's own files, out of the downloads. */
    private fun fromDisk(url: String): WebResourceResponse? {
        val path = url.removePrefix(vm.api.serverUrl).substringBefore('?')
        val piece = when {
            path.startsWith(READER_ASSETS) || path.startsWith(FONT_ASSETS) ->
                LocalBook.asset(vm.lib.store, path)

            path.startsWith(readPrefix) ->
                LocalBook.entry(vm.lib.store, book, decode(path.removePrefix(readPrefix)))

            else -> null
        } ?: return null
        return WebResourceResponse(piece.mime, "utf-8", 200, "OK", emptyMap(),
            ByteArrayInputStream(piece.bytes))
    }

    private val readPrefix get() = "/api/ebooks/books/${book.id}/read/"

    private fun decode(name: String): String =
        runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)

    private companion object {
        const val READER_ASSETS = "/static/reader/"
        const val FONT_ASSETS = "/static/fonts/"
    }
}

/** Runs a line against `window.Reader`, which only exists once the page loaded. */
private fun WebView.evalReader(script: String) {
    evaluateJavascript("if (window.Reader) { $script }", null)
}

private fun applyStyle(web: WebView, style: ReaderStyle, colors: SonorusColors) {
    val values = style.css(
        ink = colors.text.hex(),
        bg = colors.bg.hex(),
        dim = colors.textDim.hex(),
        accent = colors.accent.hex(),
    )
    val json = JSONObject(values).toString()
    web.setBackgroundColor(colors.bg.toArgb())
    web.evalReader("Reader.style($json)")
}

private fun Color.hex(): String {
    val argb = toArgb()
    return String.format("#%02X%02X%02X", (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

private fun lastDoc(book: Ebook): Int = (book.documents - 1).coerceAtLeast(0)

private fun hrefOf(book: Ebook, doc: Int): String =
    book.spine.getOrNull(doc) ?: book.spine.firstOrNull().orEmpty()

/** The chapter a document belongs to: the last mark at or before it. */
private fun chapterTitle(book: Ebook, doc: Int): String =
    book.chapters.lastOrNull { it.index <= doc }?.title.orEmpty()

// --- The furniture ------------------------------------------------------------

@Composable
private fun ReaderTopBar(
    title: String,
    chapter: String,
    onBack: () -> Unit,
    onChapters: () -> Unit,
    onStyle: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            // No inset padding of its own. The reader sits inside the Shell's
            // scaffold, which has already kept the status bar clear; padding it
            // a second time is the empty strip that used to sit above this row.
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = colors.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapter.isNotEmpty()) {
                Text(
                    chapter,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onChapters) {
            Icon(Icons.AutoMirrored.Filled.List, "Kapitel", tint = colors.text)
        }
        IconButton(onClick = onStyle) {
            Icon(Icons.Filled.FormatSize, "Schrift", tint = colors.text)
        }
    }
}

/**
 * Where in the book this is, and the one control that moves through it.
 *
 * The page of the *book* is the number on the left, because that is the number
 * a reader means. The page of the chapter is kept under the bar: it says how
 * much of this chapter is left, which the book's number cannot.
 */
@Composable
private fun ReaderBottomBar(
    paging: Paging,
    doc: Int,
    page: PageState,
    scrub: Float?,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val colors = SonorusTheme.colors
    val share = paging.share(doc, page.ratio)
    val shown = scrub?.toDouble() ?: share
    val dragging = scrub != null
    val target = scrub?.let { paging.placeAt(it) }
    val shownPage = if (target != null) {
        val inDoc = (target.second * (paging.pagesOf(target.first) - 1)).roundToInt()
        (paging.before(target.first) + inDoc + 1).coerceIn(1, paging.total())
    } else {
        paging.pageOfBook(doc, page)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Seite $shownPage von ${paging.total()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (dragging) colors.accent else colors.textDim,
            )
            Text(
                "${percent(shown)} gelesen",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = onScrub,
            onValueChangeFinished = { scrub?.let(onSeek) },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.line,
            ),
        )
        Text(
            "Seite ${page.page + 1} von ${page.pages} des Kapitels",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The way back to where a jump started. Lives for half a minute, then goes. */
@Composable
private fun JumpBackChip(label: String, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Undo, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.text)
        }
    }
}

@Composable
private fun ChapterSheet(
    book: Ebook,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(colors.surface)
        ) {
            SheetHead("Kapitel", onDismiss)
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                items(book.chapters) { chapter ->
                    val here = chapter.index == current
                    Text(
                        chapter.title.ifEmpty { "Ohne Titel" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (here) colors.accent else colors.text,
                        fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(chapter.index) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleSheet(
    style: ReaderStyle,
    onChange: (ReaderStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(colors.surface)
                .padding(bottom = 16.dp)
        ) {
            SheetHead("Schrift", onDismiss)
            RackLabelText("Schriftart", Modifier.padding(start = 20.dp, bottom = 6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderFont.entries.forEach { font ->
                    Chip(font.label, font == style.font) { onChange(style.copy(font = font)) }
                }
            }
            StyleSlider(
                label = "Größe",
                value = style.size.toFloat(),
                range = ReaderStyle.MIN_SIZE.toFloat()..ReaderStyle.MAX_SIZE.toFloat(),
                readout = "${style.size} px",
            ) { onChange(style.withSize(it.roundToInt())) }
            StyleSlider(
                label = "Zeilenabstand",
                value = style.leading,
                range = ReaderStyle.MIN_LEADING..ReaderStyle.MAX_LEADING,
                readout = String.format(Locale.GERMANY, "%.1f", style.leading),
            ) { onChange(style.withLeading(it)) }
            StyleSlider(
                label = "Rand",
                value = style.margin.toFloat(),
                range = ReaderStyle.MIN_MARGIN.toFloat()..ReaderStyle.MAX_MARGIN.toFloat(),
                readout = "${style.margin} px",
            ) { onChange(style.withMargin(it.roundToInt())) }
        }
    }
}

@Composable
private fun SheetHead(title: String, onDismiss: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, "Schließen", tint = colors.textDim)
        }
    }
}

@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RackLabelText(label)
            Text(readout, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.line,
            ),
        )
    }
}
