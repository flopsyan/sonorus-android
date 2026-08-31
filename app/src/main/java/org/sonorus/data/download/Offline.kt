package org.sonorus.data.download

import org.sonorus.data.Shuffle
import org.sonorus.data.model.Album
import org.sonorus.data.model.Artist
import org.sonorus.data.model.ArtistResponse
import org.sonorus.data.model.ArtistSummary
import org.sonorus.data.model.ArtistsResponse
import org.sonorus.data.model.AlbumResponse
import org.sonorus.data.model.AlbumsResponse
import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.Genre
import org.sonorus.data.model.GenreResponse
import org.sonorus.data.model.GenreSelection
import org.sonorus.data.model.GenresResponse
import org.sonorus.data.model.HomeResponse
import org.sonorus.data.model.LibraryStats
import org.sonorus.data.model.Lyrics
import org.sonorus.data.model.LyricsResponse
import org.sonorus.data.model.Playlist
import org.sonorus.data.model.PlaylistResponse
import org.sonorus.data.model.PlaylistTree
import org.sonorus.data.model.PlaylistsResponse
import org.sonorus.data.model.SearchResponse
import org.sonorus.data.model.ShuffleResponse
import org.sonorus.data.model.StarsResponse
import org.sonorus.data.model.Track
import org.sonorus.data.model.TrackResponse
import org.sonorus.data.model.TracksResponse
import org.sonorus.data.model.User
import kotlinx.serialization.Serializable

/** One downloaded song: the row as it was, and where its file lies. */
@Serializable
data class DownloadedTrack(
    val track: Track,
    /** File name inside the audio directory - never a path. */
    val file: String,
    val bytes: Long = 0,
    /**
     * The quality this copy was actually fetched at, as the server named it in
     * `X-Sonorus-Quality`. Not what was *asked* for: a 128k MP3 asked for as
     * Opus comes back as the original, and the player has to say so.
     *
     * Defaults to the original for an entry written before this existed, which
     * is right - everything downloaded until now was the file itself.
     */
    val quality: String = "original",
    /** When it finished, as the server writes dates: `YYYY-MM-DD HH:MM:SS`. */
    val at: String = "",
    /**
     * The words, taken along with the song. They are their own endpoint online,
     * and a plane is exactly where reading along cannot ask for them.
     */
    val lyrics: Lyrics? = null,
)

/**
 * A collection that was downloaded as a whole.
 *
 * Two jobs, and the second one is why albums, genres and star lists are in here
 * as well since 2026-08-31, where it used to hold playlists only:
 *
 *  - **A playlist's order**, which nothing in a track says, so offline it would
 *    be lost. Only playlists are read back for this.
 *  - **What the collection held the last time the server was asked**, which is
 *    the baseline every later reconcile is a diff against: songs that appeared
 *    are fetched, songs that went are deleted unless something else still holds
 *    them. See `DownloadSync`.
 *
 * The baseline is deliberately the last *server* answer and not what lies on
 * disk. A song the user deleted by hand is in neither half of the diff, so it
 * stays deleted instead of being fetched again on the next sync.
 */
@Serializable
data class OfflineCollection(
    /** `playlist`, `album`, `genre` or `stars`. */
    val kind: String = "playlist",
    val id: Int = 0,
    val name: String = "",
    /** In playlist order, including songs that are no longer downloaded. */
    val trackIds: List<Int> = emptyList(),
    /**
     * The whole selection behind a genre or a star page, which can be several
     * at once (`/genres/3,7`, `/stars/4,5`). Empty means the single [id], which
     * is what a playlist and an album always are.
     */
    val ids: List<Int> = emptyList(),
) {
    /** The one string that names this collection - `playlist:12`, `stars:4,5`. */
    val key: String get() = "$kind:${selection.joinToString(",")}"

    /** The ids this collection really stands for. */
    val selection: List<Int> get() = ids.ifEmpty { listOf(id) }
}

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
    /**
     * Songs that were asked for on their own rather than as part of a
     * collection - one track's download from its menu, or an artist page, which
     * is deliberately not kept in sync.
     *
     * They are what keeps reference counting honest: a song is deleted when the
     * last collection lets go of it, and one somebody fetched by hand is held
     * by nothing else. Without this list, taking a song out of a playlist would
     * quietly delete a download nobody asked to lose.
     */
    val manual: List<Int> = emptyList(),
    /**
     * Songs the user deleted by hand although a collection they belong to is
     * still downloaded.
     *
     * Without this, "Download entfernen" on a single song of a downloaded
     * playlist would be a button that undoes itself: the next reconcile sees a
     * song the collection holds and the phone does not, and fetches it back.
     * Tapping download on the collection again clears the exclusions of its
     * songs, which is the one way to say "no, do fetch them after all".
     */
    val excluded: List<Int> = emptyList(),
    /** The server's genre list as it was, so an offline genre keeps its real id. */
    val genres: List<Genre> = emptyList(),
    /**
     * The last look at the account.
     *
     * **Kept in a file of its own** (`account.json`), not in this index, and
     * that is a correctness rule rather than tidiness. Everything else here is a
     * fact about the downloads and is rightly thrown away when they are; this
     * one is the proof that somebody is logged in, and losing it means a cold
     * start without a network cannot tell "logged in, nothing downloaded" from
     * "never logged in" - and shows the login screen for a server that is not
     * there. It therefore survives "Alle Downloads entfernen", a corrupt index
     * and a [VERSION] bump alike. See [DownloadStore].
     */
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
            prefs = account?.prefs ?: org.sonorus.data.model.Prefs(),
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
            // A list made on this phone shows even while it is empty. It has a
            // negative id, it exists nowhere else yet, and hiding it until it
            // has a song in it would hide the list somebody just made in order
            // to put songs in it.
            .filter { it.kind == "playlist" && (it.id < 0 || it.trackIds.any { id -> id in have }) }
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

    /**
     * The songs behind [ids], **in the order they were asked for** and without
     * the ones that are not here - exactly what the server's `/tracks/by-ids`
     * answers, because the caller restoring a queue reads the answer positionally.
     */
    fun tracksByIds(s: OfflineSnapshot, ids: List<Int>): TracksResponse {
        val byId = s.tracks.associate { it.track.id to it.track }
        val hits = ids.mapNotNull { byId[it] }
        return TracksResponse(total = hits.size, tracks = hits)
    }

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
            // "stars" falls through on purpose: a snapshot is built out of the
            // songs on this phone and carries no album ratings, so there is
            // nothing to sort by. Title order beats an order that pretends.
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

    /**
     * The draw stays per song, as on the server; only the order is spread, so
     * the same interpret stops following itself. See [Shuffle].
     */
    fun shuffle(s: OfflineSnapshot, limit: Int = 300, unrated: Boolean = false): ShuffleResponse {
        val pool = s.tracks.map { it.track }.filter { !unrated || it.stars == 0 }
        val drawn = pool.shuffled().take(limit)
        return ShuffleResponse(Shuffle.spread(drawn) { Shuffle.artistOf(it) })
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
