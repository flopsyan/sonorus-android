package org.sonorus.ui

import org.sonorus.data.model.Chapter
import org.sonorus.data.model.Track

/**
 * What the transport names, in the three places it has for it.
 *
 * A song and a book do not want the same three things there. A song has a
 * title, an interpret and a record. A book has one file that runs for fifty
 * hours - its title never changes and says nothing about where you are - so the
 * **chapter** takes the first line, the book the second and the author the
 * third. That is the same order the web app uses, and it is the whole reason
 * the chapter marks were read at all.
 *
 * Without chapter marks a book falls back to its own title and its author, and
 * the third line stays empty rather than repeating one of the other two.
 */
data class NowLines(val title: String, val artist: String, val album: String)

fun nowLines(track: Track, chapter: Chapter?): NowLines = when {
    track.audiobookId != null && chapter != null -> NowLines(
        title = chapter.title.ifEmpty { "Kapitel ${chapter.index + 1}" },
        artist = track.book.ifEmpty { track.title },
        album = track.author,
    )
    track.audiobookId != null -> NowLines(track.title, track.author, "")
    else -> NowLines(track.title, track.artist, track.album)
}
