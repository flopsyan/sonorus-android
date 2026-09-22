package org.sonorus.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.model.PlayerInfo
import org.sonorus.data.model.VideoTitle

class VideoOfflineTest {

    private val show = VideoTitle(id = 18, kind = "show", title = "Breaking Bad")
    private val film = VideoTitle(id = 3, kind = "movie", title = "Fight Club")

    private fun episode(id: Int, season: Int, number: Int, position: Double = 0.0, completed: Boolean = false) =
        DownloadedVideo(
            info = PlayerInfo(id = id, kind = "show", title = show, season = season, episode = number, duration = 2900.0),
            file = "$id.mkv",
            position = position,
            completed = completed,
        )

    private val snapshot = OfflineSnapshot(
        videos = listOf(
            episode(31, 1, 3),
            episode(29, 1, 1, completed = true),
            episode(40, 2, 1, position = 600.0),
            DownloadedVideo(
                info = PlayerInfo(id = 3, kind = "movie", title = film, duration = 8348.0),
                file = "3.mp4",
                position = 10.0,
            ),
        ),
    )

    @Test
    fun `a series is one entry counting only what is on the phone`() {
        val home = VideoOffline.home(snapshot)
        assertEquals(listOf(3), home.movies.map { it.id })
        val bb = home.shows.single()
        assertEquals(3, bb.episodes)
        assertEquals(1, bb.watched)
        assertEquals(2, bb.seasons)
    }

    @Test
    fun `only a real start counts as Weiterschauen`() {
        // Ten seconds into the film is a click, not a start - the server's 30 s rule.
        val carryOn = VideoOffline.home(snapshot).carryOn
        assertEquals(listOf(40), carryOn.map { it.video.id })
    }

    @Test
    fun `next and previous are the neighbours on the phone`() {
        val info = VideoOffline.playerInfo(snapshot, 31)!!
        assertEquals(29, info.prev?.id)
        assertEquals(40, info.next?.id)
        assertNull(VideoOffline.playerInfo(snapshot, 3)!!.next)
    }

    @Test
    fun `the series page opens where the viewer is`() {
        val detail = VideoOffline.show(snapshot, 18)!!.show
        assertEquals(40, detail.next?.id)
        assertEquals(listOf(1, 2), detail.seasons.map { it.season })
        assertEquals(listOf(29, 31), detail.seasons.first().episodes.map { it.id })
        assertTrue(detail.seasons.first().episodes.first().progress.completed)
    }
}
