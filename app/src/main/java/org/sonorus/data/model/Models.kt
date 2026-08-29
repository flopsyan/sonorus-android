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
    // --- Spoken word ---------------------------------------------------------
    /** Set on a podcast episode. The client reads it as "this is spoken word". */
    val podcastId: Int? = null,
    val podcast: String = "",
    val episodeNo: Int? = null,
    /** Set on a part of a book or a radio play; `title` is the title of the whole. */
    val audiobookId: Int? = null,
    val book: String = "",
    /** 'book' or 'drama' - which of the two libraries the part belongs to. */
    val bookKind: String = "",
    val author: String = "",
    val bookAuthorId: Int? = null,
    val partNo: Int? = null,
    /** Seconds into this episode or part, and whether it is done with. */
    val position: Double = 0.0,
    val completed: Boolean = false,
    /** Where playback picks up: the position, or zero once it is finished. */
    val resumeAt: Double = 0.0,
) {
    /** Neither rated nor put on a playlist, and it remembers a position. */
    val isSpoken: Boolean get() = podcastId != null || audiobookId != null
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
    /**
     * Four covers for "Various" and an empty list for everybody else: the
     * compilation folder is no person, so it shows the records it is made of as
     * a mosaic instead of the artwork of whichever one is newest. The server
     * decides that; an APK older than its server simply keeps the single cover.
     */
    val covers: List<String> = emptyList(),
    val trackCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
data class Artist(
    val id: Int,
    val name: String = "",
    val cover: String? = null,
    /** The mosaic covers, filled for "Various" only - see [ArtistSummary]. */
    val covers: List<String> = emptyList(),
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
    // Spoken word is its own section rather than mixed into the songs: an
    // episode is not a song, and 691 of them would bury the answer.
    val episodes: List<Track> = emptyList(),
    val books: List<Book> = emptyList(),
    val dramas: List<Book> = emptyList(),
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

// --- Spoken word -------------------------------------------------------------
// Podcasts, audiobooks and radio plays. Three tabs to the listener; underneath,
// an episode and a book part are both rows in `tracks` with a remembered
// position, which is why [Track] carries `podcastId` / `audiobookId` and the
// player needs to know nothing else about them.

/** A show in the list. */
@Serializable
data class PodcastSummary(
    val id: Int,
    val name: String = "",
    val description: String = "",
    val cover: String? = null,
    val episodeCount: Int = 0,
    val unplayedCount: Int = 0,
    val duration: Double = 0.0,
    /** Date of the newest episode, `YYYY-MM-DD`. */
    val latest: String = "",
)

/** One show with its episodes. */
@Serializable
data class Podcast(
    val id: Int,
    val name: String = "",
    val description: String = "",
    val cover: String? = null,
    val episodeCount: Int = 0,
    val unplayedCount: Int = 0,
    val duration: Double = 0.0,
    val latest: String = "",
    /** `new` or `old` - remembered on the account, not on the phone. */
    val sort: String = "new",
    /** The episode to carry on with, if there is one. */
    val resume: Track? = null,
    val episodes: List<Track> = emptyList(),
)

/** An author in the list of a spoken-word library. */
@Serializable
data class SpokenAuthorSummary(
    val id: Int,
    val name: String = "",
    val cover: String? = null,
    val bookCount: Int = 0,
    val duration: Double = 0.0,
)

@Serializable
data class SpokenAuthor(
    val id: Int,
    val name: String = "",
    val cover: String? = null,
    /** False means the picture is borrowed from one of their titles. */
    val hasOwnCover: Boolean = false,
    val books: List<Book> = emptyList(),
)

/**
 * A book or a radio play - one thing to the listener, however many files it is
 * made of. `kind` is 'book' or 'drama' and decides which library it belongs to
 * and whether it has a narrator: a play has a cast, and Florian asked for the
 * "Gesprochen von" line to stay away from one.
 */
@Serializable
data class Book(
    val id: Int,
    val title: String = "",
    val author: String = "",
    val authorId: Int? = null,
    val cover: String? = null,
    val kind: String = "book",
    val narrator: String = "",
    val releaseDate: String = "",
    val year: Int? = null,
    val duration: Double = 0.0,
    val elapsed: Double = 0.0,
    val remaining: Double = 0.0,
    val started: Boolean = false,
    val finished: Boolean = false,
    val resume: BookResume = BookResume(),
    /** Numbered across the whole title. Empty when the files carry no marks. */
    val chapters: List<Chapter> = emptyList(),
    /**
     * The files. **Never drawn** - they are what the play button hands to the
     * queue, and that is the whole of their job. A book is one thing to the
     * listener and the parts only decide the order it plays in.
     */
    val parts: List<Track> = emptyList(),
) {
    val isDrama: Boolean get() = kind == "drama"
}

/** Which part to open and how far into it. */
@Serializable
data class BookResume(val index: Int = 0, val offset: Double = 0.0)

/**
 * One chapter mark. [start] and [end] are seconds into the whole title, for the
 * list and the "which chapter is this" question; [part] and [offset] say which
 * file it lies in and where, which is what a jump needs.
 */
@Serializable
data class Chapter(
    val index: Int = 0,
    val title: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
    val part: Int = 0,
    val offset: Double = 0.0,
)

@Serializable
data class PodcastStats(
    val shows: Int = 0,
    val episodes: Int = 0,
    val duration: Double = 0.0,
    val unplayed: Int = 0,
)

@Serializable
data class SpokenStats(
    val books: Int = 0,
    val authors: Int = 0,
    val duration: Double = 0.0,
    val open: Int = 0,
)

@Serializable
data class PodcastsResponse(
    val podcasts: List<PodcastSummary> = emptyList(),
    @SerialName("continue") val carryOn: List<Track> = emptyList(),
    val stats: PodcastStats = PodcastStats(),
)

@Serializable
data class PodcastResponse(val podcast: Podcast)

@Serializable
data class SpokenResponse(
    val kind: String = "book",
    val authors: List<SpokenAuthorSummary> = emptyList(),
    @SerialName("continue") val carryOn: List<Book> = emptyList(),
    val stats: SpokenStats = SpokenStats(),
)

@Serializable
data class SpokenAuthorResponse(val author: SpokenAuthor)

@Serializable
data class BookResponse(val book: Book)
