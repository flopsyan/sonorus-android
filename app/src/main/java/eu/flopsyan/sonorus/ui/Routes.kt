package eu.flopsyan.sonorus.ui

/**
 * The same addresses the web app uses, so both clients describe a place in the
 * library the same way. Comma lists are deliberate: `/stars/5,4` is *one* list,
 * not two, and `/genres/1,4` likewise.
 */
object Routes {
    const val HOME = "home"
    const val TRACKS = "tracks"
    const val ARTISTS = "artists"
    const val ARTIST = "artists/{id}"
    const val ARTIST_SINGLES = "artists/{id}/singles"
    const val ARTIST_STARS = "artists/{id}/stars/{stars}"
    const val ALBUMS = "albums"
    const val ALBUM = "albums/{id}"
    const val GENRES = "genres"
    const val GENRE = "genres/{ids}"
    const val PLAYLIST = "playlists/{id}"
    const val STARS = "stars/{stars}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val PROFILE = "profile"

    fun artist(id: Int) = "artists/$id"
    fun artistSingles(id: Int) = "artists/$id/singles"
    fun artistStars(id: Int, stars: List<Int>) = "artists/$id/stars/${stars.joinToString(",")}"
    fun album(id: Int) = "albums/$id"
    fun genre(ids: List<Int>) = "genres/${ids.joinToString(",")}"
    fun playlist(id: Int) = "playlists/$id"
    fun stars(values: List<Int>) = "stars/${values.joinToString(",")}"
}

/** The label of a star playlist, with 0 meaning "not rated yet". */
fun starLabel(value: Int): String = when (value) {
    0 -> "Nicht bewertet"
    1 -> "1 Stern"
    else -> "$value Sterne"
}
