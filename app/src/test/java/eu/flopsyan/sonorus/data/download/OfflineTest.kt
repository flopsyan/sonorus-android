package eu.flopsyan.sonorus.data.download

import eu.flopsyan.sonorus.data.model.Bootstrap
import eu.flopsyan.sonorus.data.model.Genre
import eu.flopsyan.sonorus.data.model.Playlist
import eu.flopsyan.sonorus.data.model.PlaylistFolder
import eu.flopsyan.sonorus.data.model.PlaylistTree
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library the app shows with no server behind it.
 *
 * This is the part of the download feature that cannot be tested by looking at
 * a screen: every list has to come out of the downloads in the same shape the
 * server would have sent, or an offline app looks broken in a way that only
 * shows up in a plane.
 */
class OfflineTest {

    private fun track(
        id: Int,
        title: String = "Song $id",
        artist: String = "Bowie",
        artistId: Int? = 1,
        album: String = "",
        albumId: Int? = null,
        trackNo: Int? = null,
        discNo: Int? = null,
        duration: Double = 200.0,
        stars: Int = 0,
        genres: List<String> = emptyList(),
        cover: String? = null,
        releaseDate: String = "",
        addedAt: String = "",
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        trackNo = trackNo,
        discNo = discNo,
        duration = duration,
        stars = stars,
        genres = genres,
        cover = cover,
        releaseDate = releaseDate,
        addedAt = addedAt,
    )

    private fun snapshot(
        tracks: List<Track>,
        playlists: List<OfflineCollection> = emptyList(),
        genres: List<Genre> = emptyList(),
        account: Bootstrap? = null,
        at: (Int) -> String = { "" },
    ) = OfflineSnapshot(
        tracks = tracks.map { DownloadedTrack(track = it, file = "${it.id}.mp3", bytes = 1000, at = at(it.id)) },
        playlists = playlists,
        genres = genres,
        account = account,
    )

    // --- Tracks ---------------------------------------------------------------

    @Test
    fun `only what is downloaded is listed, in the sort that was asked for`() {
        val s = snapshot(listOf(track(1, "Ziggy"), track(2, "Ashes"), track(3, "Life on Mars")))

        val byTitle = Offline.tracks(s, sort = "title", dir = "asc")

        assertEquals(3, byTitle.total)
        assertEquals(listOf("Ashes", "Life on Mars", "Ziggy"), byTitle.tracks.map { it.title })
        assertEquals(
            listOf("Ziggy", "Life on Mars", "Ashes"),
            Offline.tracks(s, sort = "title", dir = "desc").tracks.map { it.title },
        )
    }

    @Test
    fun `sorting by title ignores case, or Abba files after ZZ Top`() {
        val s = snapshot(listOf(track(1, "zoo"), track(2, "Apple")))

        assertEquals(listOf("Apple", "zoo"), Offline.tracks(s).tracks.map { it.title })
    }

    @Test
    fun `a query narrows the list by every word, anywhere`() {
        val s = snapshot(
            listOf(
                track(1, "Fame", artist = "Bowie", album = "Young Americans"),
                track(2, "Heroes", artist = "Bowie", album = "Heroes"),
            )
        )

        assertEquals(listOf(1), Offline.tracks(s, q = "fame americans").tracks.map { it.id })
        assertEquals(emptyList<Int>(), Offline.tracks(s, q = "fame heroes").tracks.map { it.id })
    }

    // --- Albums and artists ---------------------------------------------------

    @Test
    fun `an album is rebuilt from its songs, in the order a record plays`() {
        val s = snapshot(
            listOf(
                track(2, "Second", album = "Hunky Dory", albumId = 7, trackNo = 2, duration = 100.0),
                track(1, "First", album = "Hunky Dory", albumId = 7, trackNo = 1, duration = 150.0),
                track(9, "Elsewhere", album = "Low", albumId = 8, trackNo = 1),
            )
        )

        val album = Offline.album(s, 7)?.album
        assertEquals("Hunky Dory", album?.title)
        assertEquals(2, album?.trackCount)
        assertEquals(250.0, album?.duration ?: 0.0, 0.001)
        assertEquals(listOf("First", "Second"), album?.tracks?.map { it.title })
        assertEquals(2, Offline.albums(s).albums.size)
    }

    @Test
    fun `an album nothing was downloaded from is simply not there`() {
        val s = snapshot(listOf(track(1, album = "Low", albumId = 8)))

        assertNull(Offline.album(s, 99))
    }

    @Test
    fun `an artist wears the artwork of a record of theirs`() {
        val s = snapshot(
            listOf(
                track(1, artistId = 3, artist = "Bowie", albumId = 7, cover = null),
                track(2, artistId = 3, artist = "Bowie", albumId = 7, cover = "/covers/album-7.jpg"),
                track(3, artistId = 4, artist = "Eno", albumId = null),
            )
        )

        val artists = Offline.artists(s).artists
        assertEquals(listOf("Bowie", "Eno"), artists.map { it.name })
        assertEquals("/covers/album-7.jpg", artists.first { it.name == "Bowie" }.cover)
        assertEquals(2, artists.first { it.name == "Bowie" }.trackCount)
        // A file lying directly in the artist folder belongs to no album.
        assertEquals(1, Offline.artist(s, 4)?.artist?.singles?.size)
    }

    // --- Genres ---------------------------------------------------------------

    @Test
    fun `a genre keeps the id the server gave it`() {
        val s = snapshot(
            listOf(
                track(1, genres = listOf("Glam Rock")),
                track(2, genres = listOf("Glam Rock", "Art Rock")),
            ),
            genres = listOf(Genre(id = 42, name = "Glam Rock"), Genre(id = 43, name = "Art Rock")),
        )

        val glam = Offline.genres(s).genres.first { it.name == "Glam Rock" }
        assertEquals(42, glam.id)
        assertEquals(2, glam.trackCount)
        assertEquals(listOf(1, 2), Offline.genre(s, listOf(42)).genre.tracks.map { it.id })
    }

    @Test
    fun `without the servers list a genre still gets an id of its own`() {
        val s = snapshot(listOf(track(1, genres = listOf("Krautrock"))))

        val genre = Offline.genres(s).genres.single()
        assertTrue(genre.id > 0)
        assertEquals(listOf(1), Offline.genre(s, listOf(genre.id)).genre.tracks.map { it.id })
    }

    // --- Ratings, playlists, home ---------------------------------------------

    @Test
    fun `a rating list is the downloads with that rating`() {
        val s = snapshot(listOf(track(1, stars = 5), track(2, stars = 3), track(3, stars = 5)))

        assertEquals(listOf(1, 3), Offline.stars(s, listOf(5)).tracks.map { it.id }.sorted())
        assertEquals(listOf(1, 2, 3), Offline.stars(s, listOf(5, 3)).tracks.map { it.id }.sorted())
    }

    @Test
    fun `a playlist keeps the order it was downloaded in`() {
        val s = snapshot(
            listOf(track(1), track(2), track(3)),
            playlists = listOf(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(3, 1, 2))),
        )

        assertEquals(listOf(3, 1, 2), Offline.playlist(s, 5)?.tracks?.map { it.id })
        assertEquals(3, Offline.playlist(s, 5)?.playlist?.trackCount)
    }

    @Test
    fun `a playlist song that was taken off the phone drops out of it`() {
        val s = snapshot(
            listOf(track(1), track(3)),
            playlists = listOf(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(3, 1, 2))),
        )

        assertEquals(listOf(3, 1), Offline.playlist(s, 5)?.tracks?.map { it.id })
    }

    @Test
    fun `the shell is drawn from the downloads, not from the whole library`() {
        val account = Bootstrap(
            user = User(id = 1, username = "flopsyan", displayName = "Alex"),
            siteName = "Sonorus",
            stars = mapOf("5" to 900),
            playlists = PlaylistTree(
                folders = listOf(
                    PlaylistFolder(id = 1, name = "Ordner", playlists = listOf(Playlist(id = 9, name = "Leer")))
                ),
                loose = listOf(Playlist(id = 5, name = "Abends", trackCount = 300)),
            ),
        )
        val s = snapshot(
            listOf(track(1, stars = 5, albumId = 7, album = "Low"), track(2, stars = 0, albumId = 7, album = "Low")),
            playlists = listOf(
                OfflineCollection(id = 5, name = "Abends", trackIds = listOf(1)),
                OfflineCollection(id = 9, name = "Leer", trackIds = listOf(404)),
            ),
            account = account,
        )

        val boot = Offline.bootstrap(s)

        assertEquals("Alex", boot.user.displayName)
        assertEquals(2, boot.stats.tracks)
        assertEquals(1, boot.stats.albums)
        assertEquals(2000L, boot.stats.size)
        // The counts are this phone's, not the library's 900.
        assertEquals(1, boot.stars["5"])
        assertEquals(1, boot.stars["0"])
        // "Abends" survives with the one song that is here; the empty list and
        // the folder holding nothing else are gone.
        assertEquals(listOf("Abends"), boot.playlists.loose.map { it.name })
        assertEquals(1, boot.playlists.loose.single().trackCount)
        assertTrue(boot.playlists.folders.isEmpty())
    }

    @Test
    fun `the home page leaves the play log shelves empty rather than guessing`() {
        val s = snapshot(
            listOf(track(1, addedAt = "2026-01-01"), track(2, addedAt = "2026-08-01")),
            at = { id -> if (id == 2) "2026-08-05 10:00:00" else "2026-01-02 10:00:00" },
        )

        val home = Offline.home(s)

        assertTrue(home.recentlyPlayed.isEmpty())
        assertTrue(home.mostPlayed.isEmpty())
        assertEquals(0, home.unrated)
        // Most recently downloaded first - the only "recent" a phone can know.
        assertEquals(listOf(2, 1), home.recentlyAdded.map { it.id })
    }

    @Test
    fun `search answers songs, artists and albums out of the downloads`() {
        val s = snapshot(
            listOf(
                track(1, "Fame", artist = "Bowie", artistId = 1, album = "Young Americans", albumId = 7),
                track(2, "Baba O'Riley", artist = "The Who", artistId = 2, album = "Who's Next", albumId = 8),
            )
        )

        val hits = Offline.search(s, "bowie")

        assertEquals(listOf(1), hits.tracks.map { it.id })
        assertEquals(listOf("Bowie"), hits.artists.map { it.name })
        assertEquals(listOf("Young Americans"), hits.albums.map { it.title })
        assertTrue(Offline.search(s, "").tracks.isEmpty())
    }

    @Test
    fun `a random run only ever draws from what is on the phone`() {
        val s = snapshot(listOf(track(1, stars = 4), track(2, stars = 0), track(3, stars = 0)))

        assertEquals(3, Offline.shuffle(s, limit = 60).tracks.size)
        assertEquals(2, Offline.shuffle(s, limit = 60, unrated = true).tracks.size)
        assertEquals(1, Offline.shuffle(s, limit = 1).tracks.size)
    }

    @Test
    fun `the words of a song ride along with it`() {
        val words = eu.flopsyan.sonorus.data.model.Lyrics(text = "Ground control", synced = false)
        val s = OfflineSnapshot(
            tracks = listOf(
                DownloadedTrack(track = track(1), file = "1.mp3", lyrics = words),
                DownloadedTrack(track = track(2), file = "2.mp3"),
            )
        )

        assertEquals("Ground control", Offline.lyrics(s, 1).lyrics.text)
        assertEquals("", Offline.lyrics(s, 2).lyrics.text)
    }
}
