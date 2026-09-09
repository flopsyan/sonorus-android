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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import okhttp3.Request
import org.json.JSONObject
import org.sonorus.data.ReaderFont
import org.sonorus.data.ReaderStyle
import org.sonorus.data.model.Ebook
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.LoadBox
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.rememberLoad
import org.sonorus.ui.theme.SonorusColors
import org.sonorus.ui.theme.SonorusTheme
import java.io.ByteArrayInputStream

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
 */
@UnstableApi
@Composable
fun ReaderScreen(vm: AppViewModel, id: Int, onBack: () -> Unit) {
    val load = rememberLoad("ebookRead", id) { vm.lib.ebook(id) }
    LoadBox(load) { data -> Reader(vm, data.book, onBack) }
}

/** What the page is showing right now, as `reader.js` last reported it. */
private data class PageState(val page: Int = 0, val pages: Int = 1, val ratio: Double = 0.0)

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

    val latestDoc by rememberUpdatedState(doc)
    val latestPage by rememberUpdatedState(page)

    fun save(finished: Boolean = false) {
        vm.saveEbookProgress(book.id, latestDoc, latestPage.ratio, finished)
    }

    fun goToDoc(next: Int, fromEnd: Boolean) {
        val target = next.coerceIn(0, lastDoc(book))
        if (target == doc) return
        save()
        enterAt = if (fromEnd) 1.0 else 0.0
        enterFromEnd = fromEnd
        doc = target
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
                page = PageState(
                    page = o.optInt("page", 0),
                    pages = o.optInt("pages", 1).coerceAtLeast(1),
                    ratio = o.optDouble("ratio", 0.0),
                )
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
                if (target >= 0) goToDoc(target, fromEnd = false)
            }
        }
    }

    // What the view is already showing. **Not state**, and that is the point:
    // `update` runs on every recomposition, and the page reports its own state
    // through the bridge - so a plain `loadUrl` in there reloads the document
    // every time the reader turns a page, which is a reload loop and a blank
    // screen. These two say what has really been done to the view.
    val shown = remember { Loaded() }
    val url = vm.api.ebookReadUrl(book.id, hrefOf(book, doc))
    val latestStyle by rememberUpdatedState(style)

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        AndroidView(
            factory = {
                WebView(context).apply {
                    // Definite, not WRAP_CONTENT. A WebView told to wrap its
                    // content has no height to resolve `vh` and `%` against, so
                    // `height: calc(100vh - ...)` computes to 0 - and a column
                    // of zero height turns one chapter into 800 empty pages.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(colors.bg.toArgb())
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    // The text is laid out in columns of exactly one viewport,
                    // so anything that rescales it breaks the page count.
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.builtInZoomControls = false
                    settings.textZoom = 100
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    addJavascriptInterface(bridge, "SonorusReader")
                    webViewClient = ReaderClient(vm) {
                        applyStyle(this, latestStyle, colors)
                        shown.style = latestStyle
                        // Land where the reader was, once the page has been
                        // broken into columns - reader.js reports 'ready' then.
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

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(book = book, doc = doc, page = page)
        }

        when (sheet) {
            Sheet.CHAPTERS -> ChapterSheet(
                book = book,
                current = doc,
                onPick = { goToDoc(it, fromEnd = false); sheet = Sheet.NONE; overlay = false },
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
private class Loaded(var url: String = "", var style: ReaderStyle? = null)

// --- The page's own half ------------------------------------------------------

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
    private val onReady: WebView.() -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith(vm.api.serverUrl)) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
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
            .windowInsetsPadding(WindowInsets.statusBars)
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
 * The page number and how far through the book this is.
 *
 * The page is the page of the chapter, because that is the only page anybody
 * can point at: the whole book has no fixed page count at a size the reader can
 * change. The share of the book comes from the character counts the server sent.
 */
@Composable
private fun ReaderBottomBar(book: Ebook, doc: Int, page: PageState) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Seite ${page.page + 1} von ${page.pages}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
            )
            Text(
                "${(shareRead(book, doc, page.ratio) * 100).toInt()} % gelesen",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.line)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shareRead(book, doc, page.ratio).toFloat())
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent)
            )
        }
    }
}

/**
 * How far through the whole book the reader is.
 *
 * Weighted by how much text each document holds rather than by their count: a
 * book that opens with eight one-line front-matter pages would otherwise be
 * "18 % read" before the first sentence.
 */
private fun shareRead(book: Ebook, doc: Int, ratio: Double): Double {
    val lengths = book.lengths
    if (lengths.size != book.documents || lengths.isEmpty()) {
        val total = book.documents.coerceAtLeast(1)
        return ((doc + ratio) / total).coerceIn(0.0, 1.0)
    }
    val total = lengths.sumOf { it.toLong() }.coerceAtLeast(1L).toDouble()
    val before = lengths.take(doc).sumOf { it.toLong() }.toDouble()
    val here = (lengths.getOrNull(doc) ?: 0).toDouble() * ratio
    return ((before + here) / total).coerceIn(0.0, 1.0)
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
                .windowInsetsPadding(WindowInsets.navigationBars)
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
                .windowInsetsPadding(WindowInsets.navigationBars)
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
            ) { onChange(style.withSize(it.toInt())) }
            StyleSlider(
                label = "Zeilenabstand",
                value = style.leading,
                range = ReaderStyle.MIN_LEADING..ReaderStyle.MAX_LEADING,
                readout = String.format("%.1f", style.leading),
            ) { onChange(style.withLeading(it)) }
            StyleSlider(
                label = "Rand",
                value = style.margin.toFloat(),
                range = ReaderStyle.MIN_MARGIN.toFloat()..ReaderStyle.MAX_MARGIN.toFloat(),
                readout = "${style.margin} px",
            ) { onChange(style.withMargin(it.toInt())) }
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
