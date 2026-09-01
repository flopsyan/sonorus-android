package org.sonorus.data.download

import org.sonorus.data.model.Chapter
import org.sonorus.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken word without a server.
 *
 * Two questions, and they are the two ways this feature can be wrong on a phone
 * in a plane:
 *
 *  - **A downloaded book must not turn up in the music.** The server draws that
 *    line in SQL (`MUSIC` in `src/models/library.js`), and a client that
 *    rebuilds the library out of its downloads has to draw the same one - or
 *    forty untitled parts arrive in Alle Songs and the author stands among the
 *    Interpreten.
 *  - **A book must come back out of its parts as the same book.** Nothing on the
 *    phone stores a book; it is put back together out of what each file says
 *    about itself, and the arithmetic behind "noch 3 Std." is the part that has
 *    no second chance to be checked.
 */
class OfflineSpokenTest {

    // --- Fixtures -------------------------------------------------------------

    private fun song(id: Int, title: String = "Song $id") = Track(
        id = id,
        title = title,
        artist = "Bowie",
        artistId = 1,
        album = "Hunky Dory",
        albumId = 7,
        duration = 200.0,
        stars = 4,
        genres = listOf("Rock"),
    )

    private fun part(
        id: Int,
        bookId: Int = 100,
        title: String = "Teil $id",
        book: String = "Der Schwarm",
        kind: String = "book",
        partNo: Int? = null,
        duration: Double = 600.0,
        position: Double = 0.0,
        completed: Boolean = false,
    ) = Track(
        id = id,
        title = title,
        duration = duration,
        audiobookId = bookId,
        book = book,
        bookKind = kind,
        author = "Frank Schätzing",
        bookAuthorId = 9,
        partNo = partNo,
        cover = "/covers/book-$bookId.jpg",
        position = position,
        completed = completed,
    )

    private fun episode(
        id: Int,
        showId: Int = 300,
        title: String = "Folge $id",
        show: String = "Lage der Nation",
        releaseDate: String = "2026-01-0$id",
        duration: Double = 3600.0,
        position: Double = 0.0,
        completed: Boolean = false,
    ) = Track(
        id = id,
        title = title,
        duration = duration,
        podcastId = showId,
        podcast = show,
        episodeNo = id,
        releaseDate = releaseDate,
        position = position,
        completed = completed,
    )

    private fun snapshot(tracks: List<Track>, collections: List<OfflineCollection> = emptyList()) =
        OfflineSnapshot(
            tracks = tracks.map { DownloadedTrack(track = it, file = "${it.id}.mp3", bytes = 1000) },
            playlists = collections,
        )

    // --- The line between music and spoken word -------------------------------

    @Test
    fun `a downloaded book stays out of every music list`() {
        val s = snapshot(listOf(song(1), part(2), part(3), episode(4)))

        assertEquals(listOf(1), Offline.tracks(s).tracks.map { it.id })
        assertEquals(1, Offline.tracks(s).total)
        assertEquals(listOf(1), Offline.artists(s).artists.map { it.id })
        assertEquals(listOf(7), Offline.albums(s).albums.map { it.id })
        assertEquals(listOf("Rock"), Offline.genres(s).genres.map { it.name })
        assertEquals(listOf(1), Offline.stars(s, listOf(4)).tracks.map { it.id })
        assertEquals(listOf(1), Offline.shuffle(s).tracks.map { it.id })
        assertEquals(listOf(1), Offline.search(s, "Song").tracks.map { it.id })
        assertEquals(listOf(1), Offline.home(s).recentlyAdded.map { it.id })
    }

    @Test
    fun `the statistics count the music and not the parts of a book`() {
        val s = snapshot(listOf(song(1), song(2), part(3), episode(4)))

        val stats = Offline.stats(s)

        assertEquals(2, stats.tracks)
        assertEquals(1, stats.artists)
        assertEquals(1, stats.albums)
        assertEquals(400.0, stats.duration, 0.001)
        // Two songs of a thousand bytes each, and neither the part nor the
        // episode - the record counts the music library, and the Downloads
        // screen has the true total of the phone.
        assertEquals(2000L, stats.size)
    }

    @Test
    fun `named ids still answer a book, or a restored queue would lose it`() {
        val s = snapshot(listOf(song(1), part(2), episode(3)))

        assertEquals(listOf(2, 3, 1), Offline.tracksByIds(s, listOf(2, 3, 1)).tracks.map { it.id })
        assertEquals(2, Offline.track(s, 2)?.track?.id)
    }

    // --- Books and radio plays ------------------------------------------------

    @Test
    fun `a book comes back out of its parts, in the order they play`() {
        val s = snapshot(
            listOf(
                part(3, partNo = 3, title = "Drittes"),
                part(1, partNo = 1, title = "Erstes"),
                part(2, partNo = 2, title = "Zweites"),
            )
        )

        val book = Offline.book(s, 100)!!.book

        assertEquals("Der Schwarm", book.title)
        assertEquals("Frank Schätzing", book.author)
        assertEquals(9, book.authorId)
        assertEquals("book", book.kind)
        assertEquals(1800.0, book.duration, 0.001)
        assertEquals(listOf("Erstes", "Zweites", "Drittes"), book.parts.map { it.title })
        // Nothing on this phone knows who reads it - the narrator sits on the
        // book row and on no file, so the line is left out rather than guessed.
        assertEquals("", book.narrator)
    }

    @Test
    fun `a part with no number falls behind the numbered ones`() {
        val s = snapshot(
            listOf(part(1, partNo = null, title = "Anhang"), part(2, partNo = 1, title = "Kapitel 1"))
        )

        assertEquals(listOf("Kapitel 1", "Anhang"), Offline.book(s, 100)!!.book.parts.map { it.title })
    }

    @Test
    fun `how far in the listener is, across the files`() {
        val s = snapshot(
            listOf(
                part(1, partNo = 1, completed = true),
                part(2, partNo = 2, position = 120.0),
                part(3, partNo = 3),
            )
        )

        val book = Offline.book(s, 100)!!.book

        assertTrue(book.started)
        assertFalse(book.finished)
        // One whole part behind, plus two minutes into the second.
        assertEquals(720.0, book.elapsed, 0.001)
        assertEquals(1080.0, book.remaining, 0.001)
        assertEquals(1, book.resume.index)
        assertEquals(120.0, book.resume.offset, 0.001)
    }

    @Test
    fun `a book whose every part is done reads as finished and opens at the front`() {
        val s = snapshot(
            listOf(part(1, partNo = 1, completed = true), part(2, partNo = 2, completed = true))
        )

        val book = Offline.book(s, 100)!!.book

        assertTrue(book.finished)
        assertEquals(1200.0, book.elapsed, 0.001)
        assertEquals(0.0, book.remaining, 0.001)
        assertEquals(0, book.resume.index)
    }

    @Test
    fun `the chapters come from the collection, because no file carries them`() {
        val marks = listOf(
            Chapter(index = 0, title = "Erstes Kapitel", start = 0.0, end = 600.0),
            Chapter(index = 1, title = "Zweites Kapitel", start = 600.0, end = 1200.0, part = 1),
        )
        val s = snapshot(
            listOf(part(1, partNo = 1), part(2, partNo = 2)),
            listOf(OfflineCollection(kind = "book", id = 100, name = "Der Schwarm", chapters = marks)),
        )

        assertEquals(
            listOf("Erstes Kapitel", "Zweites Kapitel"),
            Offline.book(s, 100)!!.book.chapters.map { it.title },
        )
        // A book downloaded before the marks were kept simply has none, which is
        // what the page falls back to anyway.
        assertTrue(Offline.book(snapshot(listOf(part(1))), 100)!!.book.chapters.isEmpty())
    }

    @Test
    fun `a book that is not on the phone has no answer at all`() {
        assertNull(Offline.book(snapshot(listOf(song(1))), 100))
    }

    // --- The two book-shaped libraries ----------------------------------------

    @Test
    fun `Hoerspiele and Hoerbuecher are two shelves and do not mix`() {
        val s = snapshot(
            listOf(
                part(1, bookId = 100, book = "Der Schwarm", kind = "book"),
                part(2, bookId = 200, book = "Die drei Fragezeichen", kind = "drama"),
            )
        )

        val books = Offline.spoken(s, "audiobooks")
        val dramas = Offline.spoken(s, "audiodramas")

        assertEquals("book", books.kind)
        assertEquals(1, books.stats.books)
        assertEquals("drama", dramas.kind)
        assertEquals(1, dramas.stats.books)
        assertEquals(listOf("Frank Schätzing"), books.authors.map { it.name })
        assertEquals(listOf(1), books.authors.map { it.bookCount })
    }

    @Test
    fun `a path names a kind and a kind names a path`() {
        assertEquals("drama", Offline.bookKindOf("audiodramas"))
        assertEquals("book", Offline.bookKindOf("audiobooks"))
        assertEquals("audiodramas", Offline.baseOfKind("drama"))
        assertEquals("audiobooks", Offline.baseOfKind("book"))
    }

    @Test
    fun `weiterhoeren holds what was begun and not finished`() {
        val s = snapshot(
            listOf(
                part(1, bookId = 100, book = "Angefangen", position = 60.0),
                part(2, bookId = 200, book = "Fertig", completed = true),
                part(3, bookId = 300, book = "Unberührt"),
            )
        )

        assertEquals(listOf("Angefangen"), Offline.spoken(s, "audiobooks").carryOn.map { it.title })
    }

    @Test
    fun `an author is their own shelf of what is here`() {
        val s = snapshot(listOf(part(1, bookId = 100, book = "Eins"), part(2, bookId = 200, book = "Zwei")))

        val author = Offline.spokenAuthor(s, "audiobooks", 9)!!.author

        assertEquals("Frank Schätzing", author.name)
        assertEquals(listOf("Eins", "Zwei"), author.books.map { it.title })
        assertFalse(author.hasOwnCover)
        assertNull(Offline.spokenAuthor(s, "audiobooks", 404))
    }

    // --- Podcasts -------------------------------------------------------------

    @Test
    fun `a show counts its downloaded episodes and what is still unheard`() {
        val s = snapshot(listOf(episode(1), episode(2, completed = true), episode(3), song(9)))

        val shows = Offline.podcasts(s)

        assertEquals(listOf("Lage der Nation"), shows.podcasts.map { it.name })
        assertEquals(3, shows.podcasts.first().episodeCount)
        assertEquals(2, shows.podcasts.first().unplayedCount)
        assertEquals("2026-01-03", shows.podcasts.first().latest)
        assertEquals(1, shows.stats.shows)
        assertEquals(3, shows.stats.episodes)
    }

    @Test
    fun `episodes come newest first unless the other order was asked for`() {
        val s = snapshot(listOf(episode(1), episode(2), episode(3)))

        assertEquals(listOf(3, 2, 1), Offline.podcast(s, 300)!!.podcast.episodes.map { it.id })
        assertEquals(listOf(1, 2, 3), Offline.podcast(s, 300, "old")!!.podcast.episodes.map { it.id })
        assertEquals("old", Offline.podcast(s, 300, "old")!!.podcast.sort)
        assertNull(Offline.podcast(s, 404))
    }

    @Test
    fun `an episode that was begun is the one to carry on with`() {
        val s = snapshot(
            listOf(episode(1, position = 600.0), episode(2, completed = true, position = 3600.0), episode(3))
        )

        assertEquals(listOf(1), Offline.podcasts(s).carryOn.map { it.id })
        assertEquals(1, Offline.podcast(s, 300)!!.podcast.resume?.id)
    }
}
