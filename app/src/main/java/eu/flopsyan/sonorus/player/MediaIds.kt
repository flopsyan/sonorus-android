package eu.flopsyan.sonorus.player

/**
 * The addresses of the browse tree Android Auto walks.
 *
 * They are the app's own routes (`albums/7` is the album screen), which keeps
 * one vocabulary for a place in the library across both clients. The one
 * addition is the **playable leaf, which carries the node it was listed under**:
 * `albums/7|312`. That is not decoration. The car sends nothing back but the id
 * of the row that was tapped, and tapping the third song of an album has to
 * play the album from the third song - so the id is the only place the list can
 * be written down.
 *
 * `/` separates a node from its number and `|` a leaf from its list, so the two
 * can never be confused. Deliberately free of Android, so the whole scheme can
 * be tested on a plain JVM.
 */
object MediaIds {

    const val ROOT = "root"
    const val SHUFFLE = "shuffle"
    const val RECENT = "recent"
    const val TRACKS = "tracks"
    const val ARTISTS = "artists"
    const val ALBUMS = "albums"
    const val GENRES = "genres"
    const val PLAYLISTS = "playlists"
    const val STARS = "stars"
    const val FOLDERS = "folders"
    /** Voice search results, which are a list like any other. */
    const val SEARCH = "search"

    fun artist(id: Int) = "$ARTISTS/$id"
    fun album(id: Int) = "$ALBUMS/$id"
    fun genre(id: Int) = "$GENRES/$id"
    fun playlist(id: Int) = "$PLAYLISTS/$id"
    fun folder(id: Int) = "$FOLDERS/$id"

    /** `0` is "Nicht bewertet", the same as everywhere else. */
    fun stars(value: Int) = "$STARS/$value"

    /** One song, and the list it was listed in. */
    fun track(parent: String, trackId: Int) = "$parent$LEAF$trackId"

    /** The (list, song) a playable id names, or null if it names a node. */
    fun parse(mediaId: String): Pair<String, Int>? {
        val at = mediaId.lastIndexOf(LEAF)
        if (at <= 0) return null
        val id = mediaId.substring(at + 1).toIntOrNull() ?: return null
        return mediaId.substring(0, at) to id
    }

    /** The number in a node like `albums/7`, or null for a node without one. */
    fun numberOf(nodeId: String): Int? = nodeId.substringAfterLast('/', "").toIntOrNull()

    /** The `albums` of `albums/7`, and the whole id for a node without a number. */
    fun sectionOf(nodeId: String): String =
        if (numberOf(nodeId) == null) nodeId else nodeId.substringBeforeLast('/')

    private const val LEAF = '|'
}
