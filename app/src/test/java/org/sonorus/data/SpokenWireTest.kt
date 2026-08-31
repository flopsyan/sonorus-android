package org.sonorus.data

import org.sonorus.data.model.BookResponse
import org.sonorus.data.model.SpokenAuthorResponse
import org.sonorus.data.model.SpokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one field the spoken-word contract sends in two shapes.
 *
 * `parts` is the files a book is made of on `GET /api/<base>/books/:id`, and the
 * *number* of them everywhere a book appears in a list - `shapeBook` in the
 * server's `src/models/audiobooks.js` writes `parts: row.partCount || 0`, and
 * only `getBook` replaces it with the real list. The web client never noticed
 * because JavaScript does not type its answers; this one decodes into a
 * `List<Track>` and threw.
 *
 * What that cost is out of all proportion to a mistyped field: a
 * `SerializationException` is not an `ApiException`, so [Library] could only
 * read it as a server that had not answered, and one tap on Hörspiele put the
 * *whole app* into offline mode - no music, no podcasts, nothing but the
 * downloads, until it was closed and opened again. Hence this test, which
 * decodes the bodies as the server really writes them.
 */
class SpokenWireTest {

    /** A book as every list writes it: `parts` is the count. */
    private val inAList = """
        {
          "id": 3, "title": "Die drei ???", "author": "Various", "authorId": 7,
          "cover": "/covers/book-3.jpg", "kind": "drama", "narrator": "",
          "releaseDate": "1979-10-01", "year": 1979,
          "duration": 3600.0, "parts": 4,
          "elapsed": 900.0, "started": true, "finished": false
        }
    """

    @Test
    fun `the list of a spoken library decodes with a part count`() {
        val body = """
            { "ok": true, "kind": "drama",
              "authors": [ { "id": 7, "name": "Various", "cover": null,
                             "bookCount": 12, "duration": 43200.0 } ],
              "continue": [ $inAList ],
              "stats": { "books": 12, "duration": 43200.0, "authors": 3, "open": 5 } }
        """
        val answer = ApiJson.decodeFromString<SpokenResponse>(body)

        assertEquals("drama", answer.kind)
        assertEquals(1, answer.authors.size)
        assertEquals(12, answer.stats.books)
        // The count is not the files, so the files are simply not here. Nothing
        // in a list draws them - only the play button on the book page does, and
        // that page asks the endpoint that really sends them.
        assertEquals(1, answer.carryOn.size)
        assertTrue(answer.carryOn.first().parts.isEmpty())
    }

    @Test
    fun `an author page decodes with a part count on every book`() {
        val body = """
            { "ok": true, "author": { "id": 7, "name": "Various", "cover": null,
              "hasOwnCover": false, "books": [ $inAList ] } }
        """
        val author = ApiJson.decodeFromString<SpokenAuthorResponse>(body).author

        assertEquals("Various", author.name)
        assertEquals(1, author.books.size)
        assertEquals("Die drei ???", author.books.first().title)
        assertTrue(author.books.first().parts.isEmpty())
    }

    @Test
    fun `the book page still gets its files, which is what the play button queues`() {
        val body = """
            { "ok": true, "book": {
                "id": 3, "title": "Die drei ???", "author": "Various", "authorId": 7,
                "cover": null, "kind": "drama", "narrator": "", "releaseDate": "",
                "year": null, "duration": 3600.0, "elapsed": 0.0, "remaining": 3600.0,
                "started": false, "finished": false,
                "resume": { "index": 0, "offset": 0.0 },
                "chapters": [],
                "parts": [ { "id": 41, "title": "Folge 1", "audiobookId": 3, "partNo": 1 },
                           { "id": 42, "title": "Folge 1", "audiobookId": 3, "partNo": 2 } ] } }
        """
        val book = ApiJson.decodeFromString<BookResponse>(body).book

        assertEquals(2, book.parts.size)
        assertEquals(listOf(41, 42), book.parts.map { it.id })
    }
}
