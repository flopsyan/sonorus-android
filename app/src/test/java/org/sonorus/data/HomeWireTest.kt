package org.sonorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.model.HomeResponse

/**
 * `GET /api/home`, taken off a running instance and trimmed to one entry per list. The page
 * and the server are deployed apart, so the answer of a server from before 0.30.0 has to
 * read as a page with no new blocks rather than fail to decode.
 */
class HomeWireTest {

    @Test
    fun `the four blocks of the home page`() {
        val body = """
            {"ok":true,"stats":{"tracks":74,"artists":11,"albums":12,"singles":1,"genres":10,"missing":0,"duration":18659,"size":13313295},
             "unrated":22,
             "daily":{"track":{"missing":false,"id":53,"title":"Kleiner Brauner","artist":"Ostwind Trio","artistId":8,
               "album":"Kaffeehaus Sessions","albumId":9,"podcastId":null,"podcast":"","episodeNo":null,"audiobookId":null,
               "book":"","bookKind":"","author":"","bookAuthorId":null,"partNo":null,"cover":"/covers/album-9.jpg","trackNo":3,
               "discNo":null,"year":2016,"releaseDate":"2016-11-11","duration":204,"bitrate":5428,"codec":"FLAC","lossless":true,
               "genres":["Jazz"],"stars":3,"hasLyrics":false,"addedAt":"2026-10-23 22:44:25"},"daysSince":200},
             "resume":[{"key":"genres/3","kind":"genre","title":"Post-Rock","sub":"Genre","cover":"/covers/album-3.jpg",
                        "covers":["/covers/album-3.jpg"],"round":false,"icon":""},
                       {"key":"shuffle-unrated","kind":"shuffle","title":"Unbewertete","sub":"Zufallsmix","cover":null,
                        "covers":[],"round":false,"icon":"star-outline"}],
             "favorites":{"artists":[{"id":8,"name":"Ostwind Trio","trackCount":6,"albumCount":1,"cover":"/covers/album-9.jpg","favorite":true,"covers":[]}],
                          "albums":[{"id":9,"title":"Kaffeehaus Sessions","artist":"Ostwind Trio","artistId":8,"year":2016,
                                     "releaseDate":"2016-11-11","cover":"/covers/album-9.jpg","trackCount":6,"duration":1424,"favorite":true}],
                          "genres":[{"id":3,"name":"Post-Rock","trackCount":6,"favorite":true}]},
             "stars":{"0":22,"1":0,"2":0,"3":13,"4":12,"5":27},
             "recentlyPlayed":[]}
        """
        val home = ApiJson.decodeFromString<HomeResponse>(body)
        assertEquals("Kleiner Brauner", home.daily?.track?.title)
        assertEquals(200, home.daily?.daysSince)
        assertEquals(listOf("genres/3", "shuffle-unrated"), home.resume.map { it.key })
        assertNull("a random run has no cover", home.resume[1].cover)
        assertEquals("star-outline", home.resume[1].icon)
        assertEquals(8, home.favorites.artists.single().id)
        assertEquals(6, home.favorites.genres.single().trackCount)
        assertEquals(27, home.stars["5"])
        assertTrue("none of the old shelves come along", home.recentlyAdded.isEmpty() && home.newestAlbums.isEmpty())
    }

    @Test
    fun `a song of the day nobody has heard yet`() {
        val body = """{"ok":true,"daily":{"track":{"id":53,"title":"Kleiner Brauner"},"daysSince":null},"resume":[],"stars":{}}"""
        val home = ApiJson.decodeFromString<HomeResponse>(body)
        assertNull(home.daily?.daysSince)
    }

    @Test
    fun `a server from before 0_30_0 still draws its shelves`() {
        val body = """
            {"ok":true,"stats":{"tracks":74},"unrated":3,
             "newestAlbums":[{"id":9,"title":"Kaffeehaus Sessions","artist":"Ostwind Trio","trackCount":6}],
             "recentlyAdded":[{"id":53,"title":"Kleiner Brauner"}],
             "recentlyPlayed":[],"mostPlayed":[{"id":62,"title":"Terminal"}]}
        """
        val home = ApiJson.decodeFromString<HomeResponse>(body)
        assertNull(home.daily)
        assertTrue(home.resume.isEmpty() && home.stars.isEmpty())
        assertEquals(53, home.recentlyAdded.single().id)
        assertEquals(9, home.newestAlbums.single().id)
    }
}
