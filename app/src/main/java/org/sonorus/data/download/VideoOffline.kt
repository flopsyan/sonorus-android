package org.sonorus.data.download

import kotlinx.serialization.Serializable
import org.sonorus.data.model.ContinueItem
import org.sonorus.data.model.MovieDetail
import org.sonorus.data.model.MovieResponse
import org.sonorus.data.model.MovieVideo
import org.sonorus.data.model.MoviesResponse
import org.sonorus.data.model.PlayerInfo
import org.sonorus.data.model.ShowDetail
import org.sonorus.data.model.ShowResponse
import org.sonorus.data.model.ShowsResponse
import org.sonorus.data.model.VideoEpisode
import org.sonorus.data.model.VideoHomeResponse
import org.sonorus.data.model.VideoProgress
import org.sonorus.data.model.VideoSeason
import org.sonorus.data.model.VideoTitle

/**
 * A film or an episode on this phone.
 *
 * [info] is the player's answer at download time, so the player needs nothing
 * else offline. [kind] is what the server sent: `file` (the original) or the
 * copy it prepared (`remux`, `full`, `small`). A prepared copy carries only the
 * one audio track [audio] names; the subtitles lie beside it as cues.
 */
@Serializable
data class DownloadedVideo(
    val info: PlayerInfo = PlayerInfo(),
    val overview: String = "",
    val airDate: String = "",
    val file: String = "",
    val bytes: Long = 0,
    val quality: String = "original",
    val kind: String = "file",
    val audio: Int? = null,
    val subtitles: List<String> = emptyList(),
    val at: String = "",
    val position: Double = 0.0,
    val completed: Boolean = false,
) {
    val id: Int get() = info.id
    val titleId: Int get() = info.title.id
    val isMovie: Boolean get() = info.kind == "movie"

    val progress: VideoProgress
        get() {
            val at = if (completed) 0.0 else position
            return VideoProgress(
                position = at,
                completed = completed,
                started = at >= RESUME_MIN,
                fraction = if (info.duration > 0) (at / info.duration).coerceIn(0.0, 1.0) else 0.0,
            )
        }

    fun episode(): VideoEpisode = VideoEpisode(
        id = info.id,
        season = info.season,
        episode = info.episode,
        episodeEnd = info.episodeEnd,
        name = info.name,
        overview = overview,
        airDate = airDate,
        still = info.still,
        duration = info.duration,
        progress = progress,
    )

    companion object {
        /** Under this many seconds a position is a click, not a start - the server's number. */
        const val RESUME_MIN = 30.0
    }
}

/**
 * The film and series pages, derived from what is on the phone - the same rule
 * as [Offline]: offline you see what you downloaded, in the shapes the server
 * uses, so no screen needs to know.
 */
object VideoOffline {

    private val EPISODE_ORDER = compareBy<DownloadedVideo>(
        { it.info.season == 0 },
        { it.info.season ?: 0 },
        { it.info.episode ?: Int.MAX_VALUE },
        { it.info.name },
    )

    private fun films(s: OfflineSnapshot) = s.videos.filter { it.isMovie }

    private fun episodes(s: OfflineSnapshot, titleId: Int) =
        s.videos.filter { !it.isMovie && it.titleId == titleId }.sortedWith(EPISODE_ORDER)

    private fun movieTitle(v: DownloadedVideo) = v.info.title.copy(
        videoId = v.id,
        duration = v.info.duration,
        progress = v.progress,
    )

    private fun showTitle(list: List<DownloadedVideo>): VideoTitle {
        val regular = list.filter { it.info.season != 0 }
        return list.first().info.title.copy(
            kind = "show",
            seasons = regular.mapNotNull { it.info.season }.distinct().size,
            episodes = regular.size,
            watched = regular.count { it.completed },
        )
    }

    fun home(s: OfflineSnapshot): VideoHomeResponse {
        val shows = s.videos.filter { !it.isMovie }.groupBy { it.titleId }.values.map { showTitle(it) }
        val carryOn = s.videos.filter { it.progress.started }.map {
            ContinueItem(kind = it.info.kind, title = it.info.title, video = it.episode())
        }
        return VideoHomeResponse(
            movies = films(s).map(::movieTitle),
            shows = shows,
            carryOn = carryOn,
        )
    }

    fun movies(s: OfflineSnapshot): MoviesResponse =
        MoviesResponse(movies = films(s).map(::movieTitle).sortedBy { it.title.lowercase() })

    fun shows(s: OfflineSnapshot): ShowsResponse = ShowsResponse(
        shows = s.videos.filter { !it.isMovie }.groupBy { it.titleId }.values
            .map { showTitle(it) }
            .sortedBy { it.title.lowercase() },
    )

    fun movie(s: OfflineSnapshot, id: Int): MovieResponse? {
        val v = films(s).firstOrNull { it.titleId == id } ?: return null
        val t = v.info.title
        return MovieResponse(
            MovieDetail(
                id = t.id,
                title = t.title,
                year = t.year,
                originalTitle = t.originalTitle,
                overview = t.overview,
                tagline = t.tagline,
                certification = t.certification,
                vote = t.vote,
                studios = t.studios,
                poster = t.poster,
                backdrop = t.backdrop,
                logo = t.logo,
                thumb = t.thumb,
                video = MovieVideo(id = v.id, duration = v.info.duration, progress = v.progress),
            )
        )
    }

    fun show(s: OfflineSnapshot, id: Int): ShowResponse? {
        val list = episodes(s, id)
        if (list.isEmpty()) return null
        val t = list.first().info.title
        val seasons = list.groupBy { it.info.season ?: 0 }.map { (season, eps) ->
            VideoSeason(
                season = season,
                name = if (season == 0) "Specials" else "Staffel $season",
                episodes = eps.map { it.episode() },
                watched = eps.count { it.completed },
            )
        }.sortedWith(compareBy({ it.season == 0 }, { it.season }))
        val regular = list.filter { it.info.season != 0 }
        val next = list.firstOrNull { it.progress.started } ?: list.firstOrNull { !it.completed } ?: list.first()
        return ShowResponse(
            ShowDetail(
                id = t.id,
                title = t.title,
                year = t.year,
                overview = t.overview,
                tagline = t.tagline,
                endDate = t.endDate,
                certification = t.certification,
                vote = t.vote,
                studios = t.studios,
                poster = t.poster,
                backdrop = t.backdrop,
                logo = t.logo,
                thumb = t.thumb,
                seasons = seasons,
                episodes = regular.size,
                watched = regular.count { it.completed },
                next = next.episode(),
            )
        )
    }

    /**
     * The player's answer for a downloaded video. What comes before and after is
     * what is on the phone, so autoplay never reaches for an episode that is not.
     */
    fun playerInfo(s: OfflineSnapshot, videoId: Int): PlayerInfo? {
        val v = s.videos.firstOrNull { it.id == videoId } ?: return null
        if (v.isMovie) return v.info.copy(progress = v.progress, next = null, prev = null)
        val list = episodes(s, v.titleId).filter { (it.info.season == 0) == (v.info.season == 0) }
        val at = list.indexOfFirst { it.id == videoId }
        return v.info.copy(
            progress = v.progress,
            next = list.getOrNull(at + 1)?.episode(),
            prev = list.getOrNull(at - 1)?.episode(),
        )
    }
}
