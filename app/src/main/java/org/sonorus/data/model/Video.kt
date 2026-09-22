package org.sonorus.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Films and series, as `src/models/videos.js` and `src/routes/video.js` shape them.

@Serializable
data class VideoProgress(
    val position: Double = 0.0,
    val completed: Boolean = false,
    val started: Boolean = false,
    val fraction: Double = 0.0,
)

/**
 * A film or a series. One class for both, because the server has one shape
 * (`titleShape`) and the list endpoints add their own fields to it: a film its
 * [videoId], [duration] and [progress], a series its season and episode counts.
 */
@Serializable
data class VideoTitle(
    val id: Int = 0,
    val kind: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val originalTitle: String = "",
    val overview: String = "",
    val tagline: String = "",
    val releaseDate: String = "",
    val endDate: String = "",
    val certification: String = "",
    val vote: Double? = null,
    val studios: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
    val addedAt: String = "",
    val genreIds: List<Int> = emptyList(),
    val videoId: Int? = null,
    val duration: Double = 0.0,
    val progress: VideoProgress = VideoProgress(),
    val watchedAt: String? = null,
    val seasons: Int = 0,
    val episodes: Int = 0,
    val watched: Int = 0,
    val newest: String? = null,
) {
    val isMovie: Boolean get() = kind == "movie"
    val done: Boolean get() = if (isMovie) progress.completed else episodes > 0 && watched >= episodes
}

/** One episode, or the one video of a film where only these fields are needed. */
@Serializable
data class VideoEpisode(
    val id: Int = 0,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val name: String = "",
    val overview: String = "",
    val airDate: String = "",
    val still: String? = null,
    val duration: Double = 0.0,
    val height: Int? = null,
    val progress: VideoProgress = VideoProgress(),
)

@Serializable
data class ContinueItem(
    val kind: String = "movie",
    val at: String = "",
    val title: VideoTitle = VideoTitle(),
    val video: VideoEpisode = VideoEpisode(),
)

@Serializable
data class VideoGenre(val id: Int = 0, val name: String = "", val count: Int = 0)

@Serializable
data class VideoCollectionSummary(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val movies: Int = 0,
    val watched: Int = 0,
)

@Serializable
data class VideoHomeResponse(
    val tmdb: Boolean = false,
    val movies: List<VideoTitle> = emptyList(),
    val shows: List<VideoTitle> = emptyList(),
    @SerialName("continue") val carryOn: List<ContinueItem> = emptyList(),
    val collections: List<VideoCollectionSummary> = emptyList(),
)

@Serializable
data class MoviesResponse(
    val movies: List<VideoTitle> = emptyList(),
    val genres: List<VideoGenre> = emptyList(),
)

@Serializable
data class ShowsResponse(
    val shows: List<VideoTitle> = emptyList(),
    val genres: List<VideoGenre> = emptyList(),
)

@Serializable
data class VideoPerson(
    val id: Int = 0,
    val name: String = "",
    val character: String = "",
    val photo: String? = null,
    val role: String = "",
)

@Serializable
data class AudioInfo(
    val index: Int = 0,
    val codec: String = "",
    val lang: String = "",
    val title: String = "",
    val channels: Int = 0,
    val default: Boolean = false,
)

@Serializable
data class TechSubtitle(
    val lang: String = "",
    val forced: Boolean = false,
    val external: Boolean = false,
    val text: Boolean? = null,
)

@Serializable
data class VideoTech(
    val container: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val video: String = "",
    val hdr: Boolean = false,
    val audio: List<AudioInfo> = emptyList(),
    val subtitles: List<TechSubtitle> = emptyList(),
)

@Serializable
data class MovieVideo(
    val id: Int = 0,
    val duration: Double = 0.0,
    val progress: VideoProgress = VideoProgress(),
    val tech: VideoTech? = null,
)

@Serializable
data class VideoCollection(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val movies: List<VideoTitle> = emptyList(),
)

@Serializable
data class MovieDetail(
    val id: Int = 0,
    val title: String = "",
    val year: Int? = null,
    val originalTitle: String = "",
    val overview: String = "",
    val tagline: String = "",
    val certification: String = "",
    val vote: Double? = null,
    val studios: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
    val genres: List<VideoGenre> = emptyList(),
    val cast: List<VideoPerson> = emptyList(),
    val crew: List<VideoPerson> = emptyList(),
    val video: MovieVideo? = null,
    val collection: VideoCollection? = null,
    val similar: List<VideoTitle> = emptyList(),
)

@Serializable
data class VideoSeason(
    val season: Int = 0,
    val name: String = "",
    val overview: String = "",
    val airDate: String = "",
    val poster: String? = null,
    val episodes: List<VideoEpisode> = emptyList(),
    val watched: Int = 0,
)

@Serializable
data class ShowDetail(
    val id: Int = 0,
    val title: String = "",
    val year: Int? = null,
    val overview: String = "",
    val tagline: String = "",
    val endDate: String = "",
    val certification: String = "",
    val vote: Double? = null,
    val studios: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
    val genres: List<VideoGenre> = emptyList(),
    val cast: List<VideoPerson> = emptyList(),
    val crew: List<VideoPerson> = emptyList(),
    val seasons: List<VideoSeason> = emptyList(),
    val episodes: Int = 0,
    val watched: Int = 0,
    val next: VideoEpisode? = null,
    val similar: List<VideoTitle> = emptyList(),
)

@Serializable
data class PersonDetail(
    val id: Int = 0,
    val name: String = "",
    val photo: String? = null,
    val movies: List<PersonCredit> = emptyList(),
    val shows: List<PersonCredit> = emptyList(),
)

@Serializable
data class PersonCredit(
    val id: Int = 0,
    val kind: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val poster: String? = null,
    val roles: List<String> = emptyList(),
)

@Serializable
data class SubtitleInfo(
    val key: String = "",
    val lang: String = "",
    val title: String = "",
    val forced: Boolean = false,
    val sdh: Boolean = false,
    val supported: Boolean = false,
    val external: Boolean = false,
)

/** Everything the player needs to start one video and to know what follows it. */
@Serializable
data class PlayerInfo(
    val id: Int = 0,
    val kind: String = "movie",
    val title: VideoTitle = VideoTitle(),
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val name: String = "",
    val still: String? = null,
    val duration: Double = 0.0,
    val progress: VideoProgress = VideoProgress(),
    val audio: List<AudioInfo> = emptyList(),
    val subtitles: List<SubtitleInfo> = emptyList(),
    val next: VideoEpisode? = null,
    val prev: VideoEpisode? = null,
)

/** How the server serves one video from one position; see `planPlayback`. */
@Serializable
data class PlaybackPlan(
    val mode: String = "direct",
    val offset: Double = 0.0,
    val start: Double = 0.0,
    val audio: Int? = null,
    val url: String = "",
)

/** One subtitle line: from, to, text. The server's own short names. */
@Serializable
data class Cue(val s: Double = 0.0, val e: Double = 0.0, val t: String = "")

@Serializable
data class CuesResponse(val pending: Boolean = false, val cues: List<Cue> = emptyList())

/**
 * What the phone gets for one download. `file` is the original; anything else
 * is a copy the server prepares first, and [ready] says whether it is there.
 */
@Serializable
data class DownloadTicket(
    val ready: Boolean = false,
    val kind: String = "file",
    val key: String? = null,
    val audio: Int? = null,
    val size: Long = 0,
    val ext: String = "mp4",
    val url: String? = null,
    val progress: Double = 0.0,
    val queued: Int = 0,
    val error: String? = null,
)

@Serializable
data class PlayerInfoResponse(val video: PlayerInfo = PlayerInfo())

@Serializable
data class PlanResponse(val plan: PlaybackPlan = PlaybackPlan())

@Serializable
data class MovieResponse(val movie: MovieDetail = MovieDetail())

@Serializable
data class ShowResponse(val show: ShowDetail = ShowDetail())

@Serializable
data class CollectionsResponse(val collections: List<VideoCollectionSummary> = emptyList())

@Serializable
data class CollectionResponse(val collection: VideoCollection = VideoCollection())

@Serializable
data class PersonResponse(val person: PersonDetail = PersonDetail())

@Serializable
data class PlayIdResponse(val playId: Int = 0)
