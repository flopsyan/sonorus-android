package org.sonorus.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exact mirrors of what the Sonorus server returns.
 *
 * The shapes come from `shapeTrack` / `shapeAlbum` in `src/models/library.js`
 * and from the route handlers in `src/routes/api.js`. Two rules were followed
 * throughout:
 *
 *  - **Everything optional carries a default.** One projection is shared by
 *    every endpoint, but the extras are not: `playCount` only comes back from
 *    the home page, `itemId` only from a playlist, `path` only for a track
 *    whose file is gone. Without a default those endpoints would fail to parse.
 *  - **`duration` is a Double**, because the column is `REAL` in the schema.
 *    Reading it as an Int throws on the very first track.
 */

@Serializable
data class Track(
    val id: Int,
    val title: String = "",
    val artist: String = "",
    val artistId: Int? = null,
    val album: String = "",
    val albumId: Int? = null,
    /** Server path like `/covers/album-3.jpg`, or null. */
    val cover: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    /** `YYYY`, `YYYY-MM` or `YYYY-MM-DD` - the length is the precision. */
    val releaseDate: String = "",
    val duration: Double = 0.0,
    val bitrate: Int? = null,
    val codec: String = "",
    val lossless: Boolean = false,
    val genres: List<String> = emptyList(),
    val stars: Int = 0,
    /**
     * Whether the file carries a lyric at all. The words themselves are their
     * own endpoint - a text block per row of every list is not a projection.
     */
    val hasLyrics: Boolean = false,
    val addedAt: String = "",
    /** The file is gone. Kept for its rating and playlist entries only. */
    val missing: Boolean = false,
    /** Only ever set for a missing track - the one thing left worth showing. */
    val path: String? = null,
    val missingAt: String? = null,
    /** Only from a playlist: the id of the entry, not of the track. */
    val itemId: Int? = null,
    /** Only from the home page. */
    val playCount: Int? = null,
    val lastPlayed: String? = null,
) {
    /** A single lies directly in the artist folder and belongs to no album. */
    val isSingle: Boolean get() = albumId == null
}

@Serializable
data class Album(
    val id: Int,
    val title: String = "",
    val artist: String = "",
    val artistId: Int? = null,
    val year: Int? = null,
    val releaseDate: String = "",
    val cover: String? = null,
    val trackCount: Int = 0,
    val duration: Double = 0.0,
    /**
     * The genres of the album, which is a fact about the album and not about
     * whatever songs are in it right now - the server hands them down to every
     * one of them, the ones the folder gains later included. Only the detail
     * endpoint fills this; a grid row does not ask for it.
     */
    val genres: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

/**
 * The lyric of one song, as it came out of the audio file. Sonorus looks
 * nothing up anywhere, so a song either carries one or it does not.
 *
 * [lines] is empty unless the file also said *when* each line is sung - that is
 * what tells a lyric that can follow the song from one that can only be read.
 *
 * [offset] is this song's own correction, in seconds and positive for later.
 * Files disagree about where a line belongs - some stamp the first sung letter,
 * some the bar before it, some are a second out for the whole song - so no
 * single lead can be right for all of them. It is stored on the server against
 * the *track* and not against the listener: a lyric that runs late runs late
 * for everybody, and the library is shared. Riding along in [Lyrics] also means
 * it lands in the offline snapshot with the words, for free.
 */
@Serializable
data class Lyrics(
    val text: String = "",
    val lines: List<LyricsLine> = emptyList(),
    val synced: Boolean = false,
    val offset: Double = 0.0,
) {
    /** The line being sung at [seconds], or -1 before the first one. */
    fun lineAt(seconds: Double): Int {
        var at = -1
        for (i in lines.indices) {
            if (lines[i].time > seconds) break
            at = i
        }
        return at
    }
}

@Serializable
data class LyricsLine(
    /** Seconds into the track. */
    val time: Double = 0.0,
    val text: String = "",
)

/** The row shape of the artist grid. */
@Serializable
data class ArtistSummary(
    val id: Int,
    val name: String = "",
    val cover: String? = null,
    val trackCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
data class Artist(
    val id: Int,
    val name: String = "",
    val cover: String? = null,
    /** False means the picture is borrowed from an album or a single. */
    val hasOwnCover: Boolean = false,
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val singles: List<Track> = emptyList(),
)

@Serializable
data class Genre(
    val id: Int,
    val name: String = "",
    /**
     * The covers of the first four records in the genre, for the mosaic on its
     * card. `cover` is the first of them and is only still read so a phone whose
     * APK is older than the server it talks to keeps its artwork.
     */
    val covers: List<String> = emptyList(),
    val cover: String? = null,
    val trackCount: Int = 0,
)

/**
 * One combined list for a selection of genres. `id` and `name` are kept from
 * the single-genre days so anything that only wants a heading still works.
 */
@Serializable
data class GenreSelection(
    val ids: List<Int> = emptyList(),
    val id: Int = 0,
    val name: String = "",
    val names: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class Playlist(
    val id: Int,
    val name: String = "",
    val folderId: Int? = null,
    val pinned: Boolean = false,
    val position: Int = 0,
    val trackCount: Int = 0,
    val duration: Double = 0.0,
    val updatedAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class PlaylistFolder(
    val id: Int,
    val name: String = "",
    val playlists: List<Playlist> = emptyList(),
)

@Serializable
data class PlaylistTree(
    val folders: List<PlaylistFolder> = emptyList(),
    val loose: List<Playlist> = emptyList(),
)

@Serializable
data class LibraryStats(
    val tracks: Int = 0,
    val artists: Int = 0,
    val albums: Int = 0,
    val singles: Int = 0,
    val genres: Int = 0,
    val missing: Int = 0,
    val duration: Double = 0.0,
    val size: Long = 0,
)

@Serializable
data class User(
    val id: Int,
    val username: String = "",
    val displayName: String = "",
    val avatar: String = "",
    val isAdmin: Boolean = false,
)

@Serializable
data class ScanState(
    val running: Boolean = false,
    /** `walking`, `reading`, `pruning`, `transcoding`, `done` or `error`. */
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val error: String = "",
    val musicDir: String = "",
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

@Serializable
data class ImportIssue(
    val id: Int,
    val playlistId: Int? = null,
    val playlistName: String = "",
    val currentPlaylistName: String? = null,
    val title: String = "",
    val artists: String = "",
    val album: String = "",
    val source: String = "",
    val createdAt: String = "",
)

/**
 * `users.prefs`, the one JSON blob the account remembers. It is the answer to
 * every "das soll dauerhaft so bleiben", and it follows the user to another
 * device - which is exactly why the app writes to it instead of keeping player
 * settings locally.
 */
@Serializable
data class Prefs(
    val player: PlayerPrefs = PlayerPrefs(),
    val albumSort: SortPref = SortPref("title", "asc"),
    val trackSort: SortPref = SortPref("title", "asc"),
    val statsRange: String = "day",
)

@Serializable
data class PlayerPrefs(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    /** `off`, `all` or `one`. */
    val repeat: String = "off",
)

@Serializable
data class SortPref(val key: String = "title", val dir: String = "asc")

// --- Envelopes --------------------------------------------------------------
// Every endpoint answers `{ ok: true, ... }` or `{ ok: false, error, message }`.

@Serializable
data class ApiError(
    val ok: Boolean = false,
    val error: String = "",
    val message: String = "",
)

@Serializable
data class Bootstrap(
    val user: User,
    val siteName: String = "Sonorus",
    val stats: LibraryStats = LibraryStats(),
    val playlists: PlaylistTree = PlaylistTree(),
    /** Rating -> count, with 0 meaning "Nicht bewertet". */
    val stars: Map<String, Int> = emptyMap(),
    val issues: Int = 0,
    val prefs: Prefs = Prefs(),
    val scan: ScanState = ScanState(),
    val lastScan: String? = null,
)

@Serializable
data class TracksResponse(val total: Int = 0, val tracks: List<Track> = emptyList())

@Serializable
data class TrackResponse(val track: Track)

@Serializable
data class LyricsResponse(val lyrics: Lyrics = Lyrics())

@Serializable
data class ArtistsResponse(val artists: List<ArtistSummary> = emptyList())

@Serializable
data class ArtistResponse(val artist: Artist)

@Serializable
data class AlbumsResponse(val albums: List<Album> = emptyList())

@Serializable
data class AlbumResponse(val album: Album)

@Serializable
data class GenresResponse(val genres: List<Genre> = emptyList())

@Serializable
data class GenreResponse(val genre: GenreSelection)

@Serializable
data class StarsResponse(
    val stars: List<Int> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class HomeResponse(
    val stats: LibraryStats = LibraryStats(),
    /** How many songs still have no star. Zero means there is nothing to rate. */
    val unrated: Int = 0,
    val newestAlbums: List<Album> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
    val recentlyPlayed: List<Track> = emptyList(),
    val mostPlayed: List<Track> = emptyList(),
)

@Serializable
data class SearchResponse(
    val q: String = "",
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<Album> = emptyList(),
)

@Serializable
data class ShuffleResponse(val tracks: List<Track> = emptyList())

@Serializable
data class PlaylistsResponse(
    val tree: PlaylistTree = PlaylistTree(),
    val playlists: List<Playlist> = emptyList(),
)

@Serializable
data class PlaylistResponse(
    val playlist: Playlist,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class TreeResponse(val tree: PlaylistTree = PlaylistTree())

@Serializable
data class RatingResponse(
    val stars: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class PlayResponse(@SerialName("playId") val playId: Int = 0)

@Serializable
data class ScanResponse(
    val scan: ScanState = ScanState(),
    val lastScan: String? = null,
    val alreadyRunning: Boolean = false,
)

/**
 * What `GET /api/quality` answers.
 *
 * [ready] is the only field the app really acts on: an instance without ffmpeg
 * can serve nothing but the original, and a picker that offers a second choice
 * there would be a switch that quietly does nothing.
 */
@Serializable
data class QualityResponse(
    val ready: Boolean = false,
    val profiles: List<QualityProfile> = emptyList(),
)

@Serializable
data class QualityProfile(
    val name: String = "",
    val label: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
)

@Serializable
data class IssuesResponse(val issues: List<ImportIssue> = emptyList())

@Serializable
data class UsersResponse(
    val users: List<User> = emptyList(),
    val historyCount: Int = 0,
)

@Serializable
data class ProfileResponse(val user: User)
