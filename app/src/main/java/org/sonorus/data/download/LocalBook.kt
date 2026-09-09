package org.sonorus.data.download

import org.sonorus.data.model.Ebook
import java.util.zip.ZipFile

/**
 * A downloaded book, served the way the server would have served it.
 *
 * The reading view is one HTML document per chapter loaded out of the EPUB,
 * and the server does three things on the way out: it finds the entry in the
 * zip, it decides the media type, and it writes the reader's own stylesheet and
 * script into the head. With the server out of reach the app has to do the same
 * three, or a downloaded book opens as unstyled text with no page turning.
 *
 * The head written here is the same string `src/models/ebooks.js` writes. If
 * that one changes this one has to change with it - they are one contract, and
 * the only reason there are two of them is that one of the two has to work with
 * nothing but the phone.
 */
object LocalBook {

    /** What the injected head asks the page to load, by the path it uses. */
    private const val HEAD =
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
            "maximum-scale=1, user-scalable=no\">" +
            "<link rel=\"stylesheet\" href=\"/static/reader/reader.css\">" +
            "<script defer src=\"/static/reader/reader.js\"></script>"

    private val MIME = mapOf(
        "xhtml" to "application/xhtml+xml",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
    )

    data class Piece(val mime: String, val bytes: ByteArray)

    fun mimeOf(name: String): String =
        MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** One of the reader's own files, cached beside the books when one was taken. */
    fun asset(store: DownloadStore, path: String): Piece? {
        val name = path.substringAfterLast('/')
        val file = store.readerAsset(name) ?: return null
        return Piece(mimeOf(name), file.readBytes())
    }

    /**
     * One entry out of the book.
     *
     * The name is normalised the way the server normalises it, because a
     * chapter loads its pictures with the relative links the book was written
     * with and those arrive here as `../Images/x.jpg`.
     */
    fun entry(store: DownloadStore, book: Ebook, name: String): Piece? {
        val file = store.ebookFileOf(book.id) ?: return null
        val clean = normalise(name)
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(clean) ?: return@use null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (book.spine.contains(clean)) {
                    // text/html, not the xhtml the file is: the server sends a
                    // document that way on purpose, and it is what lets a book
                    // with one unclosed tag display at all instead of being
                    // refused by a strict XML parser.
                    Piece(
                        "text/html",
                        inject(bytes.toString(Charsets.UTF_8), book.language),
                    )
                } else {
                    Piece(mimeOf(clean), bytes)
                }
            }
        }.getOrNull()
    }

    /** Resolves `.` and `..` and drops what is left of a leading slash. */
    private fun normalise(name: String): String {
        val out = ArrayDeque<String>()
        name.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }

    /**
     * Writes the reader into the document.
     *
     * Three shapes, in the order the server tries them: a document with a head,
     * one with only a body, and one that is neither - EPUBs in the wild are all
     * three.
     */
    private fun inject(source: String, language: String): ByteArray {
        val html = if (language.isEmpty()) source else Regex("<html\\b", RegexOption.IGNORE_CASE)
            .replaceFirst(source, "<html lang=\"${language.replace("\"", "")}\"")
        val head = Regex("</head>", RegexOption.IGNORE_CASE)
        if (head.containsMatchIn(html)) {
            return head.replaceFirst(html, "$HEAD</head>").toByteArray()
        }
        val body = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
        val match = body.find(html)
        if (match != null) {
            return (html.substring(0, match.range.first) +
                "<head>$HEAD</head>" + match.value +
                html.substring(match.range.last + 1)).toByteArray()
        }
        return "<!doctype html><html><head>$HEAD</head><body>$html</body></html>".toByteArray()
    }
}
