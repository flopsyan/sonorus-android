package org.sonorus.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.Library
import org.sonorus.data.model.Track
import org.sonorus.ui.starLabel

/**
 * The library as Android Auto sees it: a tree of nodes, each of which is either
 * a folder to open or a list of songs to play.
 *
 * Two decisions carry the whole thing.
 *
 * **Everything is asked of [Library], never of the API.** That is the rule every
 * screen follows, and here it pays twice: the car gets the downloaded songs when
 * there is no network, and there is no second, car-shaped copy of the offline
 * logic to keep in step with the first.
 *
 * **A node remembers the songs it last handed out.** The car asks for children
 * page by page and then reports back nothing but the id of the row that was
 * tapped, so without that the list a song belongs to would be fetched again for
 * every page and once more to play it. The entries expire after [CACHE_TTL_MS],
 * so a rating made on the phone does not stay invisible in the car for the rest
 * of the drive.
 */
@UnstableApi
class MediaBrowseTree(private val library: Library) {

    private class Entry(val tracks: List<Track>, val at: Long)

    /** The songs a node last answered with, newest last (see [remember]). */
    private val lists = LinkedHashMap<String, Entry>()

    /** What a node is called, filled as the tree is walked. */
    private val titles = HashMap<String, String>()

    /** The root, which is a folder and nothing else. */
    fun root(): MediaItem = browsable(MediaIds.ROOT, "Sonorus")

    /**
     * The children of a node. A category answers with folders, everything else
     * with songs - and those are the ones worth remembering, because they are
     * what a tap plays.
     */
    suspend fun children(nodeId: String): List<MediaItem> = when (nodeId) {
        MediaIds.ROOT -> ROOT_LABELS.map { (id, label) -> browsable(id, label) }

        MediaIds.ARTISTS -> library.artists().artists.map {
            browsable(MediaIds.artist(it.id), it.name, art(it.cover))
        }

        MediaIds.ALBUMS -> library.albums().albums.map {
            browsable(MediaIds.album(it.id), it.title, art(it.cover), it.artist)
        }

        MediaIds.GENRES -> library.genres().genres.map {
            browsable(MediaIds.genre(it.id), it.name, art(it.covers.firstOrNull() ?: it.cover))
        }

        MediaIds.STARS -> (5 downTo 0).map { browsable(MediaIds.stars(it), starLabel(it)) }

        MediaIds.PLAYLISTS -> library.bootstrap().playlists.let { tree ->
            tree.folders.map { browsable(MediaIds.folder(it.id), it.name) } +
                tree.loose.map { browsable(MediaIds.playlist(it.id), it.name) }
        }

        // A folder holds playlists, so it is the one numbered node that answers
        // with folders rather than with songs.
        else -> if (MediaIds.sectionOf(nodeId) == MediaIds.FOLDERS) {
            library.bootstrap().playlists.folders
                .firstOrNull { it.id == MediaIds.numberOf(nodeId) }
                ?.playlists.orEmpty()
                .map { browsable(MediaIds.playlist(it.id), it.name) }
        } else {
            tracksOf(nodeId).map { playable(nodeId, it) }
        }
    }

    /** The songs behind a node, from the cache while it is still fresh. */
    suspend fun tracksOf(nodeId: String): List<Track> {
        lists[nodeId]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.let { return it.tracks }
        return load(nodeId).also { remember(nodeId, it) }
    }

    /** What the voice assistant asked for, kept as a list like any other. */
    suspend fun search(query: String): List<MediaItem> {
        val tracks = library.search(query).tracks
        remember(MediaIds.SEARCH, tracks)
        titles[MediaIds.SEARCH] = query
        return tracks.map { playable(MediaIds.SEARCH, it) }
    }

    /** One item by its id - a node, or a song out of the list it was listed in. */
    suspend fun item(mediaId: String): MediaItem? {
        val (parent, trackId) = MediaIds.parse(mediaId)
            ?: return children(MediaIds.ROOT).firstOrNull { it.mediaId == mediaId }
        return tracksOf(parent).firstOrNull { it.id == trackId }?.let { playable(parent, it) }
    }

    /** What a queue started from this node is called in the app's own player. */
    fun label(nodeId: String): String = titles[nodeId] ?: ROOT_LABELS[nodeId].orEmpty()

    // --- Loading --------------------------------------------------------------

    private suspend fun load(nodeId: String): List<Track> = when (nodeId) {
        MediaIds.SHUFFLE -> library.shuffle().tracks
        MediaIds.RECENT -> library.home().recentlyPlayed
        MediaIds.TRACKS -> library.tracks().tracks
        else -> {
            val number = MediaIds.numberOf(nodeId)
            when (MediaIds.sectionOf(nodeId)) {
                MediaIds.ARTISTS -> number?.let { library.artist(it).artist.tracks }
                MediaIds.ALBUMS -> number?.let { library.album(it).album.tracks }
                MediaIds.GENRES -> number?.let { library.genre(listOf(it)).genre.tracks }
                MediaIds.PLAYLISTS -> number?.let { library.playlist(it).tracks }
                MediaIds.STARS -> number?.let { library.stars(listOf(it)).tracks }
                else -> null
            }.orEmpty()
        }
    }

    private fun remember(nodeId: String, tracks: List<Track>) {
        lists.remove(nodeId) // re-inserting moves it to the young end
        lists[nodeId] = Entry(tracks, System.currentTimeMillis())
        while (lists.size > MAX_LISTS) lists.remove(lists.keys.first())
    }

    // --- Items ----------------------------------------------------------------

    private fun browsable(
        id: String,
        title: String,
        art: String? = null,
        subtitle: String? = null,
    ): MediaItem {
        titles[id] = title
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(art?.toUri())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    /**
     * A song as a row in the car. It carries no URI on purpose: a browse result
     * is a description, and the item that is actually played is built by the
     * player when the row is tapped - which is where a downloaded file wins over
     * the stream.
     */
    private fun playable(parent: String, track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.track(parent, track.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album.ifEmpty { null })
                .setArtworkUri(art(track.cover)?.toUri())
                .setIsBrowsable(false)
                // A missing file has nothing to play, and the car has no way of
                // saying so beyond leaving the row unplayable.
                .setIsPlayable(!track.missing)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun art(path: String?): String? = library.coverUrl(path)

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_LISTS = 8

        /**
         * The root, in the order that is useful while driving: put something on
         * first, then the lists that were picked by hand, then the library to
         * dig through. Doubles as the name a queue out of each one gets.
         */
        val ROOT_LABELS = linkedMapOf(
            MediaIds.SHUFFLE to "Zufallsmix",
            MediaIds.RECENT to "Zuletzt gehört",
            MediaIds.PLAYLISTS to "Playlists",
            MediaIds.STARS to "Bewertungen",
            MediaIds.ARTISTS to "Interpreten",
            MediaIds.ALBUMS to "Alben",
            MediaIds.GENRES to "Genres",
            MediaIds.TRACKS to "Alle Songs",
        )
    }
}
