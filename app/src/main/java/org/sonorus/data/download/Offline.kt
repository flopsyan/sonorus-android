package org.sonorus.data.download

import org.sonorus.data.Shuffle
import org.sonorus.data.model.Album
import org.sonorus.data.model.Book
import org.sonorus.data.model.BookResponse
import org.sonorus.data.model.BookResume
import org.sonorus.data.model.Chapter
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
import org.sonorus.data.model.Podcast
import org.sonorus.data.model.PodcastResponse
import org.sonorus.data.model.PodcastStats
import org.sonorus.data.model.PodcastSummary
import org.sonorus.data.model.PodcastsResponse
import org.sonorus.data.model.SearchResponse
import org.sonorus.data.model.ShuffleResponse
import org.sonorus.data.model.SpokenAuthor
import org.sonorus.data.model.SpokenAuthorResponse
import org.sonorus.data.model.SpokenAuthorSummary
import org.sonorus.data.model.SpokenResponse
import org.sonorus.data.model.SpokenStats
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
 * Two jobs, and the second one is why albums, artists, genres and star lists are
 * in here as well since 2026-08-31, where it used to hold playlists only:
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
    /**
     * `playlist`, `album`, `artist`, `genre`, `stars`, or one of the two
     * spoken-word kinds: `book` for an audiobook and `drama` for a radio play.
     * The two are told apart because the kind is also what names the server's
     * path - `/api/audiobooks/books/:id` against `/api/audiodramas/books/:id` -
     * and the id alone cannot say which, although the ids are unique across
     * both (one `audiobooks` table with a `kind` column).
     */
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
    /**
     * A book's chapter marks, and the one thing about a book that cannot be
     * derived from its files: a [org.sonorus.data.model.Track] carries no marks,
     * so without keeping them here the offline book page would lose the chapter
     * list a downloaded book had online. Empty for every other kind.
     */
    val chapters: List<Chapter> = emptyList(),
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

    /**
     * The downloaded songs, without the spoken word among them.
     *
     * The server draws exactly this line - `MUSIC` in `src/models/library.js` is
     * `podcast_id IS NULL AND audiobook_id IS NULL`, and every music endpoint
     * carries it - so the offline library has to draw it too. Without it a
     * downloaded audiobook would arrive in Alle Songs as forty untitled parts,
     * its author would stand among the Interpreten, and the star pages would
     * offer to rate something the server has no rating for.
     *
     * Two reads deliberately do **not** use it: [track] and [tracksByIds] answer
     * for named ids, and the ids a restored queue names are as often a book's
     * parts as they are songs.
     */
    private fun music(s: OfflineSnapshot): List<Track> =
        s.tracks.map { it.track }.filterNot { it.isSpoken }

    // --- Bootstrap ------------------------------------------------------------

    /**
     * What the shell needs to draw itself. Counts, ratings and the playlist tree
     * are recomputed from the downloads - the stored ones are the whole
     * library's and would promise songs this phone does not have.
     */
    fun bootstrap(s: OfflineSnapshot): Bootstrap {
        val account = s.account
        val tracks = music(s)
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
        val tracks = music(s)
        val ids = tracks.map { it.id }.toSet()
        return LibraryStats(
            tracks = tracks.size,
            artists = tracks.mapNotNull { it.artistId }.distinct().size,
            albums = tracks.mapNotNull { it.albumId }.distinct().size,
            singles = tracks.count { it.albumId == null },
            genres = genres(s).genres.size,
            missing = 0,
            duration = tracks.sumOf { it.duration },
            // What the downloads take up, which is the number that matters
            // here - of the music, because that is what this whole record
            // counts. The Downloads screen has the true total of the phone.
            size = s.tracks.filter { it.track.id in ids }.sumOf { it.bytes },
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
        val hits = music(s).filter { matches(it, q) }
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
        music(s)
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
        val artists = music(s)
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
        val songs = music(s).filter { it.artistId == id }
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
        val tracks = music(s)
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
        val hits = music(s).filter { track -> track.genres.any { it in names } }
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
            tracks = sortTracks(music(s).filter { it.stars in values }, "artist", "asc"),
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
        val ids = music(s).map { it.id }.toSet()
        val tracks = s.tracks.filter { it.track.id in ids }
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
        val pool = music(s).filter { !unrated || it.stars == 0 }
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
        val tracks = music(s).filter { matches(it, q) }
        return SearchResponse(
            q = q,
            tracks = sortTracks(tracks, "title", "asc"),
            artists = artists(s, q).artists,
            albums = albums(s, q).albums,
        )
    }

    // --- Spoken word ----------------------------------------------------------
    //
    // The five reads behind the three spoken-word libraries, answered out of
    // what is on the phone. They exist for the same reason the music ones do -
    // a downloaded thing has to be findable without a server - and they are
    // rebuilt rather than stored, because a book part already carries the whole
    // of what a book is: `audiobookId`, `book`, `bookKind`, `author`,
    // `bookAuthorId` and `partNo`.
    //
    // Two things a server sends cannot be rebuilt and are honestly left out
    // rather than guessed: a **narrator**, which lives on the book row and on no
    // file, and a podcast's **description**. The chapters would be the third,
    // and they are the reason [OfflineCollection.chapters] exists.

    /** The `kind` column behind a path: `audiodramas` is the radio plays. */
    fun bookKindOf(base: String): String = if (base == "audiodramas") "drama" else "book"

    /** The path behind a kind - the other direction, for a stored collection. */
    fun baseOfKind(kind: String): String = if (kind == "drama") "audiodramas" else "audiobooks"

    fun podcasts(s: OfflineSnapshot): PodcastsResponse {
        val shows = episodes(s).groupBy { it.podcastId!! }.map { (id, list) -> summary(id, list) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return PodcastsResponse(
            podcasts = shows,
            carryOn = carryOnEpisodes(s),
            stats = PodcastStats(
                shows = shows.size,
                episodes = episodes(s).size,
                duration = episodes(s).sumOf { it.duration },
                unplayed = episodes(s).count { !it.completed },
            ),
        )
    }

    /**
     * One show. [sort] is what the screen asked for; offline there is no account
     * to remember it on, so what was asked for is also what is answered.
     */
    fun podcast(s: OfflineSnapshot, id: Int, sort: String? = null): PodcastResponse? {
        val list = episodes(s).filter { it.podcastId == id }
        if (list.isEmpty()) return null
        val order = if (sort == "old") "old" else "new"
        val ordered = inEpisodeOrder(list, newestFirst = order == "new")
        val summary = summary(id, list)
        return PodcastResponse(
            Podcast(
                id = id,
                name = summary.name,
                cover = summary.cover,
                episodeCount = summary.episodeCount,
                unplayedCount = summary.unplayedCount,
                duration = summary.duration,
                latest = summary.latest,
                sort = order,
                resume = list.firstOrNull { started(it) },
                episodes = ordered,
            )
        )
    }

    fun spoken(s: OfflineSnapshot, base: String): SpokenResponse {
        val kind = bookKindOf(base)
        val books = books(s, kind)
        val authors = books
            .filter { it.authorId != null }
            .groupBy { it.authorId!! }
            .map { (id, theirs) ->
                SpokenAuthorSummary(
                    id = id,
                    name = theirs.first().author,
                    cover = theirs.firstNotNullOfOrNull { it.cover },
                    bookCount = theirs.size,
                    duration = theirs.sumOf { it.duration },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return SpokenResponse(
            kind = kind,
            authors = authors,
            carryOn = books.filter { it.started && !it.finished }.sortedBy { it.title },
            stats = SpokenStats(
                books = books.size,
                authors = authors.size,
                duration = books.sumOf { it.duration },
                open = books.count { !it.finished },
            ),
        )
    }

    fun spokenAuthor(s: OfflineSnapshot, base: String, id: Int): SpokenAuthorResponse? {
        val theirs = books(s, bookKindOf(base)).filter { it.authorId == id }
        if (theirs.isEmpty()) return null
        return SpokenAuthorResponse(
            SpokenAuthor(
                id = id,
                name = theirs.first().author,
                cover = theirs.firstNotNullOfOrNull { it.cover },
                // An author picture of their own is never downloaded, so the
                // shelf borrows a cover exactly as the server lets it.
                hasOwnCover = false,
                books = theirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            )
        )
    }

    /**
     * One book or one play, **with** its parts - this is the read the play
     * button is fed from, so the files are the point of it.
     */
    fun book(s: OfflineSnapshot, id: Int): BookResponse? {
        val parts = partsOf(s, id)
        if (parts.isEmpty()) return null
        return BookResponse(bookOf(s, id, parts, withParts = true))
    }

    // --- Spoken word, the pieces ----------------------------------------------

    private fun episodes(s: OfflineSnapshot): List<Track> =
        s.tracks.map { it.track }.filter { it.podcastId != null }

    /** The parts of one title, in the order the server plays them. */
    private fun partsOf(s: OfflineSnapshot, id: Int): List<Track> =
        s.tracks.map { it.track }
            .filter { it.audiobookId == id }
            // `part_no IS NULL, part_no, path` on the server. A downloaded row
            // carries no path, so the title stands in for it - which is the same
            // order for a rip that numbers its files and stable for one that
            // does not.
            .sortedWith(
                compareBy<Track> { it.partNo == null }
                    .thenBy { it.partNo ?: 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )

    /** Every title of one kind with at least one part on this phone. */
    fun books(s: OfflineSnapshot, kind: String): List<Book> =
        s.tracks.map { it.track }
            .filter { it.audiobookId != null && kindOf(it) == kind }
            .map { it.audiobookId!! }
            .distinct()
            .map { id -> bookOf(s, id, partsOf(s, id), withParts = false) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    /** A part with no kind on it is a book, the same default the server has. */
    private fun kindOf(track: Track): String = track.bookKind.ifEmpty { "book" }

    /**
     * A whole title, put back together out of its files.
     *
     * Where the listener stands is [placeInBook]'s answer. [withParts] is what
     * separates the book page from every other place a book appears, and it
     * mirrors the wire: `shapeBook` sends a count everywhere but `getBook`, and
     * the client's `BookParts` reads that as no files at all.
     */
    private fun bookOf(s: OfflineSnapshot, id: Int, parts: List<Track>, withParts: Boolean): Book {
        val first = parts.firstOrNull()
        val place = placeInBook(parts)
        return Book(
            id = id,
            title = first?.book.orEmpty(),
            author = first?.author.orEmpty(),
            authorId = first?.bookAuthorId,
            cover = parts.firstNotNullOfOrNull { it.cover },
            kind = first?.let { kindOf(it) } ?: "book",
            // Nobody on this phone knows who reads it: the narrator sits on the
            // book row and never on a file. An empty line is left out, which is
            // what the page does for a radio play anyway.
            narrator = "",
            releaseDate = parts.firstOrNull { it.releaseDate.isNotEmpty() }?.releaseDate.orEmpty(),
            year = parts.firstNotNullOfOrNull { it.year },
            duration = place.total,
            elapsed = place.elapsed,
            remaining = (place.total - place.elapsed).coerceAtLeast(0.0),
            started = place.started,
            finished = place.finished,
            resume = BookResume(index = place.index, offset = place.offset),
            chapters = chaptersOf(s, id),
            parts = if (withParts) parts else emptyList(),
        )
    }

    private data class Place(
        val total: Double,
        val elapsed: Double,
        val started: Boolean,
        val finished: Boolean,
        val index: Int,
        val offset: Double,
    )

    /**
     * Where the listener stands in one title, as one number - the client's half
     * of the server's `placeInBook`.
     *
     * It is deliberately **not** the same arithmetic. The server picks the
     * current part by `touchedAt`, "where I am" rather than "the furthest I ever
     * got", so that jumping back moves the position back with it. A downloaded
     * row carries no such timestamp, so this reads the first unfinished part
     * instead. The two agree on everything but a book somebody jumped backwards
     * in, and there the offline answer is the *earlier* of the two, which is the
     * one that loses no listening.
     */
    private fun placeInBook(parts: List<Track>): Place {
        val total = parts.sumOf { it.duration }
        if (parts.isEmpty()) return Place(0.0, 0.0, started = false, finished = false, index = 0, offset = 0.0)
        if (parts.all { it.completed }) {
            return Place(total, total, started = true, finished = true, index = 0, offset = 0.0)
        }
        val index = parts.indexOfFirst { !it.completed }.coerceAtLeast(0)
        val offset = parts[index].position.coerceIn(0.0, parts[index].duration)
        val before = parts.take(index).sumOf { it.duration }
        val elapsed = before + offset
        return Place(total, elapsed, started = elapsed > 0.0, finished = false, index, offset)
    }

    /** The marks kept with the download, and nothing when it was never a whole. */
    private fun chaptersOf(s: OfflineSnapshot, id: Int): List<Chapter> =
        s.playlists.firstOrNull { (it.kind == "book" || it.kind == "drama") && it.id == id }
            ?.chapters.orEmpty()

    private fun summary(id: Int, list: List<Track>): PodcastSummary = PodcastSummary(
        id = id,
        name = list.first().podcast,
        cover = list.firstNotNullOfOrNull { it.cover },
        episodeCount = list.size,
        unplayedCount = list.count { !it.completed },
        duration = list.sumOf { it.duration },
        latest = list.maxOfOrNull { it.releaseDate }.orEmpty(),
    )

    /** Newest first is what a podcast list means by "in order". */
    private fun inEpisodeOrder(list: List<Track>, newestFirst: Boolean): List<Track> {
        val sorted = list.sortedWith(
            compareBy<Track> { it.releaseDate }
                .thenBy { it.episodeNo ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        return if (newestFirst) sorted.reversed() else sorted
    }

    /** Begun and not done with - the row at the top of the podcast page. */
    private fun carryOnEpisodes(s: OfflineSnapshot): List<Track> =
        episodes(s).filter { started(it) }.sortedByDescending { it.releaseDate }.take(12)

    private fun started(episode: Track): Boolean = episode.position > 0.0 && !episode.completed

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
