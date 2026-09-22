package org.sonorus.data

import org.sonorus.data.model.MissingResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/library/missing`, decoded as the server really writes it.
 *
 * The body below was taken off a running instance rather than typed from the
 * model, which is the whole point of a wire test: the two ends are in different
 * repos and nothing but this notices when one of them moves. It carries a field
 * the app does not declare (`albumTracks`, which the server uses to decide
 * `albumId` and then has no further use for) and a `null` where a nullable is
 * expected, and both have to pass without throwing - a SerializationException
 * is not an ApiException, and one that escapes here would read as a server that
 * did not answer at all. See [SpokenWireTest] for what that costs.
 */
class MissingWireTest {

    private val body = """
        {
          "ok": true,
          "missing": [
            {
              "id": 299, "title": "Song 299", "path": "/music/299.flac",
              "missingAt": "2026-09-22T10:00:00Z",
              "artistId": 300, "artist": "Interpret 300",
              "albumId": null, "album": "Album 300",
              "stars": 5, "albumTracks": 0, "playlists": []
            },
            {
              "id": 33, "title": "Song 33", "path": "/music/33.flac",
              "missingAt": "2026-09-22T10:00:00Z",
              "artistId": 34, "artist": "Interpret 34",
              "albumId": 34, "album": "Album 34",
              "stars": 3, "albumTracks": 11, "playlists": ["Autofahrt"]
            },
            {
              "id": 901, "title": "Song 901", "path": "/music/901.flac",
              "missingAt": "2026-09-22T10:00:00Z",
              "artistId": 2, "artist": "Interpret 2",
              "albumId": 202, "album": "Album 202",
              "stars": 0, "albumTracks": 11, "playlists": ["Laufen"]
            }
          ]
        }
    """

    @Test
    fun `the list decodes as the server writes it`() {
        val list = ApiJson.decodeFromString<MissingResponse>(body).missing
        assertEquals(3, list.size)
        assertEquals("Song 299", list[0].title)
        assertEquals(5, list[0].stars)
    }

    @Test
    fun `a record with nothing left in it has no page to link to`() {
        val list = ApiJson.decodeFromString<MissingResponse>(body).missing
        // The album is still named - it is what the song was on - but there is
        // no id, so the name is drawn as text rather than as a link.
        assertNull(list[0].albumId)
        assertEquals("Album 300", list[0].album)
        assertEquals(34, list[1].albumId)
    }

    @Test
    fun `a corpse held by a playlist alone carries no stars`() {
        val list = ApiJson.decodeFromString<MissingResponse>(body).missing
        assertEquals(0, list[2].stars)
        assertEquals(listOf("Laufen"), list[2].playlists)
        assertTrue(list[0].playlists.isEmpty())
    }
}
