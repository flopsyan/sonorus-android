package eu.flopsyan.sonorus.data.download

import eu.flopsyan.sonorus.data.model.Album
import eu.flopsyan.sonorus.data.model.Artist
import eu.flopsyan.sonorus.data.model.ArtistResponse
import eu.flopsyan.sonorus.data.model.ArtistSummary
import eu.flopsyan.sonorus.data.model.ArtistsResponse
import eu.flopsyan.sonorus.data.model.AlbumResponse
import eu.flopsyan.sonorus.data.model.AlbumsResponse
import eu.flopsyan.sonorus.data.model.Bootstrap
import eu.flopsyan.sonorus.data.model.Genre
import eu.flopsyan.sonorus.data.model.GenreResponse
import eu.flopsyan.sonorus.data.model.GenreSelection
import eu.flopsyan.sonorus.data.model.GenresResponse
import eu.flopsyan.sonorus.data.model.HomeResponse
import eu.flopsyan.sonorus.data.model.LibraryStats
import eu.flopsyan.sonorus.data.model.Lyrics
import eu.flopsyan.sonorus.data.model.LyricsResponse
import eu.flopsyan.sonorus.data.model.Playlist
import eu.flopsyan.sonorus.data.model.PlaylistResponse
import eu.flopsyan.sonorus.data.model.PlaylistTree
import eu.flopsyan.sonorus.data.model.PlaylistsResponse
import eu.flopsyan.sonorus.data.model.SearchResponse
import eu.flopsyan.sonorus.data.model.ShuffleResponse
import eu.flopsyan.sonorus.data.model.StarsResponse
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.data.model.TrackResponse
import eu.flopsyan.sonorus.data.model.TracksResponse
import eu.flopsyan.sonorus.data.model.User
import kotlinx.serialization.Serializable

/** One downloaded song: the row as it was, and where its file lies. */
@Serializable
data class DownloadedTrack(
    val track: Track,
    /** File name inside the audio directory - never a path. */
    val file: String,
    val bytes: Long = 0,
    /** When it finished, as the server writes dates: `YYYY-MM-DD HH:MM:SS`. */
    val at: String = "",
    /**
     * The words, taken along with the song. They are their own endpoint online,
     * and a plane is exactly where reading along cannot ask for them.
     */
    val lyrics: Lyrics? = null,
)

/**
 * A collection that was downloaded as a whole. Only playlists are read back -
 * an album, an artist or a genre is derived from the songs themselves, but a
 * playlist is an order somebody chose and nothing in a track says it.
 */
@Serializable
data class OfflineCollection(
    val kind: String = "playlist",
    val id: Int = 0,
    val name: String = "",
    /** In playlist order, including songs that are no longer downloaded. */
    val trackIds: List<Int> = emptyList(),
)

/**
 * Everything the app knows without a server: the songs on this phone, the
 * playlists they came from, and the last look at the account.
 *
 * [account] is the last bootstrap seen while online. It carries who is logged
 * in, what the site is called and how the player was set - none of which can be
 * asked for offline, and all of which the shell needs before it draws anything.
 */
@Serializable
data class OfflineSnapshot(
    /**
     * Zero rather than [VERSION], and written explicitly: a file that does not
     * name its version has to read as *older*, not as whatever the current
     * default happens to be, or the first migration would silently accept every
     * file it was meant to reject.
     */
    val version: Int = 0,
    val tracks: List<DownloadedTrack> = emptyList(),
    /** Server paths like `/covers/album-3.jpg` whose picture lies on this phone. */
    val covers: List<String> = emptyList(),
    val playlists: List<OfflineCollection> = emptyList(),
    /** The server's genre list as it was, so an offline genre keeps its real id. */
    val genres: List<Genre> = emptyList(),
    val account: Bootstrap? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * The library, derived from what is on this phone.
 *
 * Every endpoint the screens use has a counterpart here, answering the same
 * shapes out of the downloads - which is what lets the app run offline without
 * a single screen knowing about it. The rule throughout is Spotify's: **offline
 * you see what you downloaded**, nothing else, and a list is the same list it
 * was, only shorter.
 *
 * Deliberately free of Android and of the store, so all of it can be tested on
 * a plain JVM. Its only input is a snapshot.
 */
object Offline {

    // --- Bootstrap ------------------------------------------------------------

    /**
     * What the shell needs to draw itself. Counts, ratings and the playlist tree
     * are recomputed from the downloads - the stored ones are the whole
     * library's and would promise songs this phone does not have.
     */
    fun bootstrap(s: OfflineSnapshot): Bootstrap {
        val account = s.account
        val tracks = s.tracks.map { it.track }
        return Bootstrap(
            user = account?.user ?: User(id = 0, username = "offline", displayName = "Offline"),
            siteName = account?.siteName ?: "Sonorus",
            stats = stats(s),
            playlists = tree(s),
            stars = starCounts(tracks),
            issues = 0,
            prefs = account?.prefs ?: eu.flopsyan.sonorus.data.model.Prefs(),
        )
    }

    fun stats(s: OfflineSnapshot): LibraryStats {
        val tracks = s.tracks.map { it.track }
        return LibraryStats(
            tracks = tracks.size,
            artists = tracks.mapNotNull { it.artistId }.distinct().size,
            albums = tracks.mapNotNull { it.albumId }.distinct().size,
            singles = tracks.count { it.albumId == null },
            genres = genres(s).genres.size,
            missing = 0,
            duration = tracks.sumOf { it.duration },
            // What the downloads take up, which is the number that matters here.
            size = s.tracks.sumOf { it.bytes },
        )
    }

    private fun starCounts(tracks: List<Track>): Map<String, Int> =
        (0..5).associate { value -> value.toString() to tracks.count { it.stars == value } }

    /**
     * The playlist tree, narrowed to the lists that have something on this
     * phone. A folder that ends up empty disappears with them - a row that
     * opens onto nothing is worse than no row.
     */
    private fun tree(s: OfflineSnapshot): PlaylistTree {
        val have = s.tracks.map { it.track.id }.toSet()
        val usable = s.playlists
            .filter { it.kind == "playlist" && it.trackIds.any { id -> id in have } }
            .associateBy { it.id }
        if (usable.isEmpty()) return PlaylistTree()

        val stored = s.account?.playlists ?: PlaylistTree()
        fun shape(p: Playlist): Playlist? {
            val kept = usable[p.id]?.trackIds?.filter { it in have } ?: return null
            val songs = kept.mapNotNull { id -> s.tracks.firstOrNull { it.track.id == id }?.track }
            return p.copy(trackCount = songs.size, duration = songs.sumOf { it.duration })
        }

        val folders = stored.folders
            .map { folder -> folder.copy(playlists = folder.playlists.mapNotNull(::shape)) }
            .filter { it.playlists.isNotEmpty() }
        val loose = stored.loose.mapNotNull(::shape)

        // A list downloaded before the tree was ever stored still has to show up.
        val placed = (folders.flatMap { it.playlists } + loose).map { it.id }.toSet()
        val extra = usable.values
            .filter { it.id !in placed }
            .map { c ->
                val songs = c.trackIds.filter { it in have }
                Playlist(
                    id = c.id,
                    name = c.name,
                    trackCount = songs.size,
                    duration = songs.mapNotNull { id ->
                        s.tracks.firstOrNull { it.track.id == id }?.track?.duration
                    }.sum(),
                )
            }
        return PlaylistTree(folders = folders, loose = loose + extra)
    }

    // --- Tracks ---------------------------------------------------------------

    fun tracks(s: OfflineSnapshot, q: String = "", sort: String = "title", dir: String = "asc"): TracksResponse {
        val hits = s.tracks.map { it.track }.filter { matches(it, q) }
        return TracksResponse(total = hits.size, tracks = sortTracks(hits, sort, dir))
    }

    fun track(s: OfflineSnapshot, id: Int): TrackResponse? =
        s.tracks.firstOrNull { it.track.id == id }?.let { TrackResponse(it.track) }

    fun lyrics(s: OfflineSnapshot, id: Int): LyricsResponse =
        LyricsResponse(s.tracks.firstOrNull { it.track.id == id }?.lyrics ?: Lyrics())

    /**
     * The sorts the web app offers, on the list this phone has. Text is compared
     * case-insensitively; anything else would file "Abba" after "ZZ Top".
     */
    fun sortTracks(tracks: List<Track>, sort: String, dir: String): List<Track> {
        val byTitle = compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val sorted = when (sort) {
            "artist" -> tracks.sortedWith(
                compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.discNo ?: 0 }
                    .thenBy { it.trackNo ?: 0 }
            )
            "album" -> tracks.sortedWith(
                compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.discNo ?: 0 }
                    .thenBy { it.trackNo ?: 0 }
            )
            "year" -> tracks.sortedWith(
                compareBy<Track> { it.releaseDate.take(4).toIntOrNull() ?: it.year ?: 0 }.then(byTitle)
            )
            "duration" -> tracks.sortedWith(compareBy<Track> { it.duration }.then(byTitle))
            "added" -> tracks.sortedWith(compareBy<Track> { it.addedAt }.then(byTitle))
            "stars" -> tracks.sortedWith(compareBy<Track> { it.stars }.then(byTitle))
            else -> tracks.sortedWith(byTitle)
        }
        return if (dir == "desc") sorted.reversed() else sorted
    }

    /** In the order a record is played: disc, then track number, then title. */
    private fun inRecordOrder(tracks: List<Track>): List<Track> =
        tracks.sortedWith(
            compareBy<Track> { it.discNo ?: 0 }
                .thenBy { it.trackNo ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )

    // --- Albums ---------------------------------------------------------------

    fun albums(s: OfflineSnapshot, q: String = "", sort: String = "title", dir: String = "asc"): AlbumsResponse {
        val albums = albumList(s).filter { album ->
            q.isBlank() || words(q).all {
                album.title.contains(it, true) || album.artist.contains(it, true)
            }
        }
        val byTitle = compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val sorted = when (sort) {
            "artist" -> albums.sortedWith(
                compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle)
            )
            "year" -> albums.sortedWith(
                compareBy<Album> { it.releaseDate.take(4).toIntOrNull() ?: it.year ?: 0 }.then(byTitle)
            )
            "tracks" -> albums.sortedWith(compareBy<Album> { it.trackCount }.then(byTitle))
            else -> albums.sortedWith(byTitle)
        }
        return AlbumsResponse(if (dir == "desc") sorted.reversed() else sorted)
    }

    fun album(s: OfflineSnapshot, id: Int): AlbumResponse? {
        val album = albumList(s).firstOrNull { it.id == id } ?: return null
        return AlbumResponse(album)
    }

    /** Every album at least one of whose songs is on this phone. */
    private fun albumList(s: OfflineSnapshot): List<Album> =
        s.tracks.map { it.track }
            .filter { it.albumId != null }
            .groupBy { it.albumId!! }
            .map { (id, songs) ->
                val ordered = inRecordOrder(songs)
                val first = ordered.first()
                Album(
                    id = id,
                    title = first.album,
                    artist = first.artist,
                    artistId = first.artistId,
                    year = ordered.firstNotNullOfOrNull { it.year },
                    releaseDate = ordered.firstOrNull { it.releaseDate.isNotEmpty() }?.releaseDate.orEmpty(),
                    cover = ordered.firstNotNullOfOrNull { it.cover },
                    trackCount = ordered.size,
                    duration = ordered.sumOf { it.duration },
                    genres = ordered.flatMap { it.genres }.distinct(),
                    tracks = ordered,
                )
            }

    // --- Artists --------------------------------------------------------------

    fun artists(s: OfflineSnapshot, q: String = ""): ArtistsResponse {
        val artists = s.tracks.map { it.track }
            .filter { it.artistId != null }
            .groupBy { it.artistId!! }
            .map { (id, songs) ->
                ArtistSummary(
                    id = id,
                    name = songs.first().artist,
                    // No artist picture is downloaded, so the artist wears the
                    // artwork of a record of theirs - which is what the server
                    // does too for an artist without one of their own.
                    cover = songs.firstNotNullOfOrNull { it.cover },
                    trackCount = songs.size,
                    albumCount = songs.mapNotNull { it.albumId }.distinct().size,
                )
            }
            .filter { a -> q.isBlank() || words(q).all { a.name.contains(it, true) } }
            .sortedWith(compareBy<ArtistSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
        return ArtistsResponse(artists)
    }

    fun artist(s: OfflineSnapshot, id: Int): ArtistResponse? {
        val songs = s.tracks.map { it.track }.filter { it.artistId == id }
        if (songs.isEmpty()) return null
        val albums = albumList(s)
            .filter { it.artistId == id }
            .sortedByDescending { it.releaseDate.take(4).toIntOrNull() ?: it.year ?: 0 }
        val singles = inRecordOrder(songs.filter { it.albumId == null })
        return ArtistResponse(
            Artist(
                id = id,
                name = songs.first().artist,
                cover = songs.firstNotNullOfOrNull { it.cover },
                hasOwnCover = false,
                albums = albums.map { it.copy(tracks = emptyList()) },
                // The artist page's order: newest record first, singles last.
                tracks = albums.flatMap { it.tracks } + singles,
                singles = singles,
            )
        )
    }

    // --- Genres ---------------------------------------------------------------

    /**
     * The genres of what is downloaded. The ids come from the snapshot of the
     * server's list, taken whenever a download runs - so `/genres/7` means the
     * same thing offline as online. Without that snapshot the names are numbered
     * in order, which is self-consistent for as long as the phone stays offline.
     */
    fun genres(s: OfflineSnapshot): GenresResponse {
        val tracks = s.tracks.map { it.track }
        val present = tracks.flatMap { it.genres }.distinct()
        val known = s.genres.associateBy({ it.name }, { it.id })
        var next = 1
        val genres = present
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { name ->
                val songs = tracks.filter { name in it.genres }
                Genre(
                    id = known[name] ?: (10_000 + next++),
                    name = name,
                    covers = albumCovers(songs),
                    cover = songs.firstNotNullOfOrNull { it.cover },
                    trackCount = songs.size,
                )
            }
        return GenresResponse(genres)
    }

    fun genre(s: OfflineSnapshot, ids: List<Int>): GenreResponse {
        val all = genres(s).genres.filter { it.id in ids }
        val names = all.map { it.name }
        val hits = s.tracks.map { it.track }.filter { track -> track.genres.any { it in names } }
        return GenreResponse(
            GenreSelection(
                ids = all.map { it.id },
                id = all.firstOrNull()?.id ?: 0,
                name = names.joinToString(", "),
                names = names,
                tracks = sortTracks(hits, "artist", "asc"),
            )
        )
    }

    // --- Ratings, playlists, home ---------------------------------------------

    fun stars(s: OfflineSnapshot, values: List<Int>): StarsResponse =
        StarsResponse(
            stars = values,
            tracks = sortTracks(s.tracks.map { it.track }.filter { it.stars in values }, "artist", "asc"),
        )

    fun playlists(s: OfflineSnapshot): PlaylistsResponse {
        val t = tree(s)
        return PlaylistsResponse(tree = t, playlists = t.folders.flatMap { it.playlists } + t.loose)
    }

    fun playlist(s: OfflineSnapshot, id: Int): PlaylistResponse? {
        val stored = s.playlists.firstOrNull { it.kind == "playlist" && it.id == id } ?: return null
        val songs = stored.trackIds.mapNotNull { tid -> s.tracks.firstOrNull { it.track.id == tid }?.track }
        val shape = (tree(s).folders.flatMap { it.playlists } + tree(s).loose).firstOrNull { it.id == id }
            ?: Playlist(id = id, name = stored.name, trackCount = songs.size, duration = songs.sumOf { it.duration })
        return PlaylistResponse(playlist = shape, tracks = songs)
    }

    /**
     * The home page without a server. The two shelves that come out of the play
     * log stay empty on purpose: nothing offline can know what was heard most,
     * and a shelf filled with a guess would be a lie about the library.
     */
    fun home(s: OfflineSnapshot): HomeResponse {
        val tracks = s.tracks
        return HomeResponse(
            stats = stats(s),
            unrated = 0,
            newestAlbums = albums(s, sort = "year", dir = "desc").albums.take(12),
            recentlyAdded = tracks.sortedByDescending { it.at }.map { it.track }.take(12),
            recentlyPlayed = emptyList(),
            mostPlayed = emptyList(),
        )
    }

    fun shuffle(s: OfflineSnapshot, limit: Int = 60, unrated: Boolean = false): ShuffleResponse {
        val pool = s.tracks.map { it.track }.filter { !unrated || it.stars == 0 }
        return ShuffleResponse(pool.shuffled().take(limit))
    }

    /**
     * Search across the downloads. Every word has to appear somewhere in title,
     * artist or album - the same "all words, anywhere" rule the server uses,
     * without its scoring, which needs the whole library to be worth anything.
     */
    fun search(s: OfflineSnapshot, q: String): SearchResponse {
        if (q.isBlank()) return SearchResponse(q = q)
        val tracks = s.tracks.map { it.track }.filter { matches(it, q) }
        return SearchResponse(
            q = q,
            tracks = sortTracks(tracks, "title", "asc"),
            artists = artists(s, q).artists,
            albums = albums(s, q).albums,
        )
    }

    // --- Helpers --------------------------------------------------------------

    private fun words(q: String): List<String> = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matches(track: Track, q: String): Boolean {
        if (q.isBlank()) return true
        val haystack = "${track.title} ${track.artist} ${track.album}"
        return words(q).all { haystack.contains(it, ignoreCase = true) }
    }

    /**
     * The covers of the first four *records* in a list - the same rule the
     * mosaic in the UI follows, repeated here because the data layer must not
     * reach into the screens.
     */
    private fun albumCovers(tracks: List<Track>, limit: Int = 4): List<String> {
        val seen = mutableSetOf<String>()
        val covers = mutableListOf<String>()
        for (track in tracks) {
            val cover = track.cover
            if (cover.isNullOrEmpty()) continue
            if (!seen.add(track.albumId?.let { "album-$it" } ?: "track-${track.id}")) continue
            covers += cover
            if (covers.size == limit) break
        }
        return covers
    }
}
