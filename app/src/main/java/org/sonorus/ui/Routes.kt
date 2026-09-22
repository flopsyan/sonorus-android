package org.sonorus.ui

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

    // Spoken word. `base` is "audiobooks" or "audiodramas" and is the same word
    // the server's paths use, so one screen serves both libraries - see
    // spokenRoutes in the server's src/routes/api.js.
    const val PODCASTS = "podcasts"
    const val PODCAST = "podcasts/{id}"
    const val SPOKEN = "spoken/{base}"
    const val SPOKEN_AUTHOR = "spoken/{base}/authors/{id}"
    const val BOOK = "spoken/{base}/books/{id}"

    // eBooks. The reader is a route of its own rather than an overlay, so back
    // out of a book lands on the book's page and not on whatever came before.
    const val EBOOKS = "ebooks"
    const val EBOOK_AUTHOR = "ebooks/authors/{id}"
    const val EBOOK = "ebooks/books/{id}"
    const val READER = "ebooks/books/{id}/read"

    // Films and series. One place with three tabs, like the web's /videos; the
    // player is a route of its own for the same reason the reader is.
    const val VIDEOS = "videos"
    const val MOVIE = "videos/movies/{id}"
    const val SHOW = "videos/shows/{id}?season={season}"
    const val VIDEO_COLLECTION = "videos/collections/{id}"
    const val VIDEO_PERSON = "videos/people/{id}"
    const val WATCH = "watch/{id}?t={t}"

    const val PLAYLIST = "playlists/{id}"
    const val STARS = "stars/{stars}"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val PROFILE = "profile"
    const val NOTICES = "notices"
    const val ACCOUNTS = "accounts"

    fun artist(id: Int) = "artists/$id"
    fun artistSingles(id: Int) = "artists/$id/singles"
    fun artistStars(id: Int, stars: List<Int>) = "artists/$id/stars/${stars.joinToString(",")}"
    fun album(id: Int) = "albums/$id"
    fun genre(ids: List<Int>) = "genres/${ids.joinToString(",")}"
    fun playlist(id: Int) = "playlists/$id"
    fun podcast(id: Int) = "podcasts/$id"
    fun spoken(base: String) = "spoken/$base"
    fun spokenAuthor(base: String, id: Int) = "spoken/$base/authors/$id"
    fun book(base: String, id: Int) = "spoken/$base/books/$id"
    fun ebookAuthor(id: Int) = "ebooks/authors/$id"
    fun ebook(id: Int) = "ebooks/books/$id"
    fun reader(id: Int) = "ebooks/books/$id/read"
    fun stars(values: List<Int>) = "stars/${values.joinToString(",")}"
    fun movie(id: Int) = "videos/movies/$id"
    fun show(id: Int, season: Int? = null) =
        if (season == null) "videos/shows/$id" else "videos/shows/$id?season=$season"
    fun videoCollection(id: Int) = "videos/collections/$id"
    fun videoPerson(id: Int) = "videos/people/$id"
    fun watch(id: Int, fromStart: Boolean = false) = if (fromStart) "watch/$id?t=0" else "watch/$id"
}

/** The label of a star playlist, with 0 meaning "not rated yet". */
fun starLabel(value: Int): String = when (value) {
    0 -> "Nicht bewertet"
    1 -> "1 Stern"
    else -> "$value Sterne"
}
