package org.sonorus.data.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.sonorus.data.SonorusApi
import org.sonorus.data.model.Ebook
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What one book's download is doing. */
enum class EbookDownloadStatus { NONE, RUNNING, DONE, FAILED }

/**
 * Taking a book along.
 *
 * Deliberately **not** part of [Downloads]. That queue is built around songs -
 * its state is keyed by track id, it picks a quality, it counts bitrates - and
 * a book is one file with none of those questions. Threading an EPUB through it
 * would mean pretending a book is a track everywhere the queue looks at one.
 *
 * What a book needs beyond its own bytes is the reading view: the stylesheet,
 * the script and the four faces live on the server, and a book downloaded
 * without them opens to an unstyled page the first time the server is out of
 * reach. They are fetched once and kept beside the books.
 */
class EbookDownloads(
    private val api: SonorusApi,
    private val store: DownloadStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Which books are being fetched, and how far along each is. */
    data class State(
        val running: Map<Int, Float> = emptyMap(),
        val failed: Map<Int, String> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val jobs = mutableMapOf<Int, Job>()

    fun statusOf(id: Int): EbookDownloadStatus = when {
        _state.value.running.containsKey(id) -> EbookDownloadStatus.RUNNING
        store.isEbookDownloaded(id) -> EbookDownloadStatus.DONE
        _state.value.failed.containsKey(id) -> EbookDownloadStatus.FAILED
        else -> EbookDownloadStatus.NONE
    }

    fun progressOf(id: Int): Float = _state.value.running[id] ?: 0f

    fun download(book: Ebook) {
        synchronized(jobs) {
            if (jobs[book.id]?.isActive == true) return
            jobs[book.id] = scope.launch {
                _state.value = _state.value.copy(
                    running = _state.value.running + (book.id to 0f),
                    failed = _state.value.failed - book.id,
                )
                val error = runCatching { fetch(book) }.exceptionOrNull()
                _state.value = State(
                    running = _state.value.running - book.id,
                    failed = if (error == null) _state.value.failed - book.id
                    else _state.value.failed + (book.id to (error.message ?: "Fehlgeschlagen")),
                )
            }
        }
    }

    fun cancel(id: Int) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        _state.value = _state.value.copy(running = _state.value.running - id)
        store.ebookTarget(id).let { File(it.path + PART).delete() }
    }

    /** Gives a book back. The place it was read to goes with it. */
    fun remove(id: Int) {
        cancel(id)
        store.removeEbook(id)
    }

    private suspend fun fetch(book: Ebook) = withContext(Dispatchers.IO) {
        store.ebookDir.mkdirs()
        val target = store.ebookTarget(book.id)
        val part = File(target.path + PART)
        // Resumed rather than restarted: the route sends the file, so the server
        // answers a range - the same deal a half-finished song gets.
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            .url(api.ebookFileUrl(book.id))
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()

        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val fresh = response.code != 206
            if (fresh) part.delete()
            val total = (response.body.contentLength().takeIf { it > 0 } ?: 0L) +
                if (fresh) 0L else have
            var written = if (fresh) 0L else have
            FileOutputStream(part, !fresh).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = _state.value.copy(
                                running = _state.value.running +
                                    (book.id to (written.toFloat() / total).coerceIn(0f, 1f))
                            )
                        }
                    }
                }
            }
        }

        if (!part.isFile || part.length() == 0L) throw IOException("Leere Datei")
        target.delete()
        if (!part.renameTo(target)) throw IOException("Konnte nicht abgelegt werden")

        cacheReader()
        // Last, and only now: an entry in the index is the promise that the
        // book behind it can be opened.
        store.putEbook(book)
    }

    /**
     * The reading view's own files, fetched once and kept.
     *
     * A missing one is not a failed download - the book is readable without
     * Ubuntu, only plainer - so a face that does not come is passed over rather
     * than taking the whole thing down with it.
     */
    private suspend fun cacheReader() = withContext(Dispatchers.IO) {
        store.readerDir.mkdirs()
        READER_FILES.forEach { path ->
            val name = path.substringAfterLast('/')
            val into = File(store.readerDir, name)
            if (into.isFile && into.length() > 0) return@forEach
            runCatching {
                val request = Request.Builder().url(api.serverUrl + path).build()
                api.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    into.writeBytes(response.body.bytes())
                }
            }
        }
    }

    private companion object {
        const val PART = ".part"
        const val BUFFER = 64 * 1024

        /** Everything the injected reader head asks for, by server path. */
        val READER_FILES = listOf(
            "/static/reader/reader.css",
            "/static/reader/reader.js",
            "/static/fonts/Ubuntu-Regular.ttf",
            "/static/fonts/Ubuntu-Italic.ttf",
            "/static/fonts/Ubuntu-Bold.ttf",
            "/static/fonts/Ubuntu-BoldItalic.ttf",
        )
    }
}
