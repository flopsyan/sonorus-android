package org.sonorus.data

import org.sonorus.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error the server itself produced, carrying its German message. */
class ApiException(val code: String, override val message: String) : IOException(message)

/**
 * The whole Sonorus REST API.
 *
 * Nothing on the server had to change for this: a native client sends neither
 * `Origin` nor `Sec-Fetch-Site`, and `rejectCrossSite` in `src/lib/security.js`
 * deliberately lets such requests through ("CSRF is strictly a browser
 * problem"). So the app speaks the same API the web client does.
 */
/**
 * How every answer from the server is read.
 *
 * Out here rather than inside [SonorusApi] so a test can decode a recorded body
 * with the *real* settings. A body that decodes here and not in a test with a
 * hand-written `Json { }` teaches nothing about the app.
 */
internal val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

class SonorusApi(private val session: Session) {

    private val json = ApiJson

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(session.cookieJar)
        // Ten seconds, not the library's thirty: this is the *connect*, and the
        // only thing a longer one buys is a longer wait before the app decides
        // the server is gone and shows the downloads instead. A handshake that
        // has not happened in ten seconds is not going to.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // A streamed file must not be cut off mid-track on a slow connection.
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Serialises re-logins so ten parallel 401s do not cause ten logins. */
    private val loginLock = Mutex()

    private class Answer(val text: String, val status: Int, val ok: Boolean)

    // --- URLs -----------------------------------------------------------------

    val serverUrl: String get() = session.serverUrl

    /** `/covers/album-3.jpg` -> an absolute URL the image loader can fetch. */
    fun coverUrl(path: String?): String? =
        if (path.isNullOrEmpty()) null else session.serverUrl + path

    /**
     * `res.sendFile(..., { acceptRanges: true })` on the server means Range
     * requests work, which is what gives ExoPlayer its seek bar for free.
     *
     * The original carries **no query at all** rather than `?q=original`, and
     * that is deliberate on both sides: the URL is what ExoPlayer keys its cache
     * and its `Range` requests on, and the plain stream has to keep the plain
     * URL. It is also what every queue saved before the quality setting existed
     * still asks for.
     */
    fun streamUrl(trackId: Int, quality: Quality = Quality.ORIGINAL): String {
        val base = "${session.serverUrl}/api/stream/$trackId"
        return if (quality == Quality.ORIGINAL) base else "$base?q=${quality.wire}"
    }

    /** What this server can serve, so a picker never offers what cannot work. */
    suspend fun quality(): QualityResponse = get("/api/quality")

    // --- Login ----------------------------------------------------------------

    /**
     * The same form POST the browser makes. A success is the 302 redirect the
     * route answers with; wrong credentials render the login page again with
     * 401, and the per-IP limiter answers 429.
     *
     * Redirects are deliberately not followed - the cookie arrives with the 302
     * itself, and following it would pull down the whole app shell for nothing.
     */
    suspend fun login(server: String, user: String, pass: String): Unit = withContext(Dispatchers.IO) {
        val base = server.trim().trimEnd('/')
        val url = (base + "/login").toHttpUrlOrNull()
            ?: throw ApiException("bad_url", "Die Server-Adresse ist keine gültige URL.")

        val body = FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .add("next", "/")
            .build()

        val noRedirects = client.newBuilder().followRedirects(false).build()
        val request = Request.Builder().url(url).post(body).build()

        noRedirects.newCall(request).execute().use { res ->
            when {
                res.isRedirect -> Unit
                res.code == 401 -> throw ApiException("bad_login", "Benutzername oder Passwort falsch.")
                res.code == 429 -> throw ApiException("blocked", "Zu viele Fehlversuche. Bitte kurz warten.")
                // The setup page redirects here when no account exists yet.
                res.code == 200 -> throw ApiException("bad_login", "Benutzername oder Passwort falsch.")
                else -> throw ApiException("http_${res.code}", "Unerwartete Antwort vom Server (HTTP ${res.code}).")
            }
        }
        session.store(base, user, pass)
    }

    /**
     * Logs in again with the credentials already stored, and lets ten callers
     * at once cause one login rather than ten.
     *
     * [call] does this by itself for the JSON API. A download speaks HTTP
     * directly - it streams a file rather than parsing a body - so it needs the
     * same recovery in a form it can use.
     */
    suspend fun relogin() {
        loginLock.withLock { login(session.serverUrl, session.username, session.password) }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(session.serverUrl + "/logout")
                .post(FormBody.Builder().build())
                .build()
            client.newBuilder().followRedirects(false).build().newCall(request).execute().close()
        }
        session.clear()
    }

    // --- Plumbing -------------------------------------------------------------

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val base = (session.serverUrl + path).toHttpUrlOrNull()
            ?: throw ApiException("bad_url", "Die Server-Adresse ist keine gültige URL.")
        if (query.isEmpty()) return base
        return base.newBuilder().apply {
            for ((k, v) in query) if (!v.isNullOrEmpty()) addQueryParameter(k, v)
        }.build()
    }

    private fun jsonBody(build: JsonObject): RequestBody =
        build.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    /**
     * Runs a call and hands back its parsed body.
     *
     * A 401 means the session cookie is gone - expired after its 30 days, or
     * invalidated by a password change. The credentials are still here, so the
     * app logs in again and retries once instead of throwing the user out.
     */
    private suspend fun call(request: Request, retry: Boolean = true): JsonElement =
        withContext(Dispatchers.IO) {
            val answer = client.newCall(request).execute().use { res ->
                Answer(res.body?.string().orEmpty(), res.code, res.isSuccessful)
            }
            val text = answer.text
            val status = answer.status
            val ok = answer.ok

            if (status == 401 && retry && session.password.isNotEmpty()) {
                loginLock.withLock {
                    login(session.serverUrl, session.username, session.password)
                }
                return@withContext call(request.newBuilder().build(), retry = false)
            }

            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: throw ApiException(
                    "not_json",
                    // Exactly the diagnosis the web client learned to give: if the
                    // body is not JSON it did not come from Sonorus at all - a
                    // proxy answered instead (nginx caps request bodies at 1 MB
                    // by default and serves its own HTML 413 page).
                    "Unerwartete Antwort vom Server (HTTP $status).",
                )

            if (!ok) {
                val err = runCatching { json.decodeFromJsonElement(ApiError.serializer(), parsed) }.getOrNull()
                throw ApiException(err?.error ?: "http_$status", err?.message ?: "Fehler ($status).")
            }
            parsed
        }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        json.decodeFromJsonElement<T>(call(Request.Builder().url(url(path, query)).get().build()))

    private suspend inline fun <reified T> post(path: String, body: JsonObject = JsonObject(emptyMap())): T =
        json.decodeFromJsonElement<T>(call(Request.Builder().url(url(path)).post(jsonBody(body)).build()))

    private suspend inline fun <reified T> put(path: String, body: JsonObject): T =
        json.decodeFromJsonElement<T>(call(Request.Builder().url(url(path)).put(jsonBody(body)).build()))

    private suspend inline fun <reified T> patch(path: String, body: JsonObject): T =
        json.decodeFromJsonElement<T>(call(Request.Builder().url(url(path)).patch(jsonBody(body)).build()))

    private suspend inline fun <reified T> delete(path: String): T =
        json.decodeFromJsonElement<T>(call(Request.Builder().url(url(path)).delete().build()))

    // --- Bootstrap and library ------------------------------------------------

    suspend fun bootstrap(): Bootstrap = get("/api/bootstrap")

    suspend fun tracks(
        q: String = "",
        sort: String = "title",
        dir: String = "asc",
        limit: Int = 0,
        offset: Int = 0,
    ): TracksResponse = get(
        "/api/tracks",
        mapOf(
            "q" to q,
            "sort" to sort,
            "dir" to dir,
            "limit" to limit.takeIf { it > 0 }?.toString(),
            "offset" to offset.takeIf { it > 0 }?.toString(),
        ),
    )

    suspend fun tracksByIds(ids: List<Int>): TracksResponse =
        post("/api/tracks/by-ids", buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) })

    suspend fun track(id: Int): TrackResponse = get("/api/tracks/$id")

    /**
     * The words of one song. Its own endpoint on purpose: a text block has no
     * business riding along in the track projection every list selects.
     */
    suspend fun lyrics(id: Int): LyricsResponse = get("/api/tracks/$id/lyrics")

    /**
     * Moves this song's text against the music, in seconds and positive for
     * later. Its own endpoint rather than part of the track PATCH, because it
     * is written from a control that moves while the song plays.
     *
     * The server clamps to +/-5 and rounds to a tenth and answers with what it
     * really stored. That answer is deliberately not read back: the control
     * clamps and rounds to the same numbers, and a late reply landing on a
     * value the finger has already moved past would drag the slider backwards.
     */
    suspend fun setLyricsOffset(id: Int, offset: Double) {
        put<JsonElement>("/api/tracks/$id/lyrics-offset", buildJsonObject { put("offset", offset) })
    }

    suspend fun artists(q: String = ""): ArtistsResponse = get("/api/artists", mapOf("q" to q))

    suspend fun artist(id: Int): ArtistResponse = get("/api/artists/$id")

    suspend fun albums(q: String = "", sort: String = "title", dir: String = "asc"): AlbumsResponse =
        get("/api/albums", mapOf("q" to q, "sort" to sort, "dir" to dir))

    suspend fun album(id: Int): AlbumResponse = get("/api/albums/$id")

    suspend fun genres(): GenresResponse = get("/api/genres")

    /** Several at once: `/genres/1,4` is one combined list, not two. */
    suspend fun genre(ids: List<Int>): GenreResponse = get("/api/genres/${ids.joinToString(",")}")

    /** `0` is "Nicht bewertet"; several ratings at once give one list. */
    suspend fun stars(values: List<Int>): StarsResponse = get("/api/stars/${values.joinToString(",")}")

    suspend fun home(): HomeResponse = get("/api/home")

    /**
     * `unrated` narrows the random run to what has no star yet.
     *
     * 300 is the web app's number, and it used to be 60 here. Sixty is under
     * four hours of one run and then the queue wraps and deals the *same* sixty
     * again - which is most of why the random run on the phone kept turning up
     * the same names while the browser did not.
     */
    suspend fun shuffle(limit: Int = 300, unrated: Boolean = false): ShuffleResponse =
        get(
            "/api/shuffle",
            mapOf("limit" to limit.toString(), "unrated" to if (unrated) "1" else null),
        )

    suspend fun search(q: String): SearchResponse = get("/api/search", mapOf("q" to q))

    // --- Ratings and plays ----------------------------------------------------

    suspend fun rate(trackId: Int, stars: Int): RatingResponse =
        put("/api/tracks/$trackId/rating", buildJsonObject { put("stars", stars) })

    /**
     * `playedAt` is sent only by [org.sonorus.data.PlayLog], for a play that was
     * heard offline and is going out late: without it the server stamps the play
     * on arrival, and a fortnight away would land on one day. An ordinary play
     * leaves it empty and is stamped when it happens, as it always was.
     *
     * **It has to carry its zone**, which `Instant.toString()` does. Measured
     * 2026-08-31: `recordPlay` parses the value with `new Date(...)`, and Node
     * reads a space-separated `2026-08-30 23:54:37` as the *server's* local
     * time - the same play came back two hours early on a CEST server, which is
     * enough to file a late evening under the day before. With the `Z` there is
     * nothing left to interpret.
     */
    suspend fun startPlay(trackId: Int, seconds: Double, playedAt: String = ""): PlayResponse =
        post("/api/plays", buildJsonObject {
            put("trackId", trackId)
            put("seconds", seconds)
            if (playedAt.isNotEmpty()) put("playedAt", playedAt)
        })

    suspend fun updatePlay(playId: Int, seconds: Double) {
        put<JsonElement>("/api/plays/$playId", buildJsonObject { put("seconds", seconds) })
    }

    suspend fun clearHistory() {
        delete<JsonElement>("/api/plays")
    }

    // --- Spoken word ----------------------------------------------------------
    // Three libraries, two shapes. Audiobooks and radio plays are the same
    // endpoints at two paths ("audiobooks" / "audiodramas"), which is what the
    // `base` argument is - see spokenRoutes in the server's src/routes/api.js.

    suspend fun podcasts(): PodcastsResponse = get("/api/podcasts")

    /** `sort` is "new" or "old"; the server remembers the choice per account. */
    suspend fun podcast(id: Int, sort: String? = null): PodcastResponse =
        get("/api/podcasts/$id", mapOf("sort" to sort))

    /**
     * Where the listener stopped in an episode or a book part. `completed` says
     * it is done with; the server takes both and the two clients agree on it.
     */
    suspend fun setProgress(trackId: Int, position: Double, completed: Boolean? = null) {
        put<JsonElement>("/api/progress/$trackId", buildJsonObject {
            put("position", position)
            if (completed != null) put("completed", completed)
        })
    }

    suspend fun spoken(base: String): SpokenResponse = get("/api/$base")

    suspend fun spokenAuthor(base: String, id: Int): SpokenAuthorResponse =
        get("/api/$base/authors/$id")

    suspend fun book(base: String, id: Int): BookResponse = get("/api/$base/books/$id")

    suspend fun setBookHeard(base: String, id: Int, heard: Boolean): BookResponse =
        put("/api/$base/books/$id/heard", buildJsonObject { put("heard", heard) })

    /** The narrator and the release date. A radio play sends only the date. */
    suspend fun editBook(base: String, id: Int, narrator: String?, date: String): BookResponse =
        patch("/api/$base/books/$id", buildJsonObject {
            if (narrator != null) put("narrator", narrator)
            put("date", date)
        })

    suspend fun editAuthorCover(base: String, id: Int, cover: JsonElement): SpokenAuthorResponse =
        patch("/api/$base/authors/$id", buildJsonObject { put("cover", cover) })

    // --- Playlists ------------------------------------------------------------

    suspend fun playlists(): PlaylistsResponse = get("/api/playlists")

    suspend fun playlist(id: Int): PlaylistResponse = get("/api/playlists/$id")

    suspend fun createPlaylist(name: String, folderId: Int? = null): TreeResponse =
        post("/api/playlists", buildJsonObject {
            put("name", name)
            folderId?.let { put("folderId", it) }
        })

    suspend fun renamePlaylist(id: Int, name: String): TreeResponse =
        patch("/api/playlists/$id", buildJsonObject { put("name", name) })

    suspend fun pinPlaylist(id: Int, pinned: Boolean): TreeResponse =
        patch("/api/playlists/$id", buildJsonObject { put("pinned", pinned) })

    suspend fun movePlaylist(id: Int, folderId: Int?): TreeResponse =
        patch("/api/playlists/$id", buildJsonObject { put("folderId", folderId ?: 0) })

    suspend fun deletePlaylist(id: Int): TreeResponse = delete("/api/playlists/$id")

    suspend fun addToPlaylist(id: Int, trackIds: List<Int>): TreeResponse =
        post("/api/playlists/$id/tracks", buildJsonObject {
            put("trackIds", JsonArray(trackIds.map { JsonPrimitive(it) }))
        })

    suspend fun removeFromPlaylist(playlistId: Int, itemId: Int): TreeResponse =
        delete("/api/playlists/$playlistId/items/$itemId")

    suspend fun reorderPlaylistItems(playlistId: Int, itemIds: List<Int>) {
        put<JsonElement>("/api/playlists/$playlistId/order", buildJsonObject {
            put("itemIds", JsonArray(itemIds.map { JsonPrimitive(it) }))
        })
    }

    /** One endpoint does reordering *and* moving between folders. */
    suspend fun reorderPlaylists(folderId: Int?, ids: List<Int>): TreeResponse =
        put("/api/playlists/order", buildJsonObject {
            put("folderId", folderId ?: 0)
            put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
        })

    suspend fun createFolder(name: String): TreeResponse =
        post("/api/folders", buildJsonObject { put("name", name) })

    suspend fun renameFolder(id: Int, name: String): TreeResponse =
        patch("/api/folders/$id", buildJsonObject { put("name", name) })

    /** Deleting a folder keeps its playlists - they move to the top level. */
    suspend fun deleteFolder(id: Int): TreeResponse = delete("/api/folders/$id")

    // --- Edits ----------------------------------------------------------------
    // Editable is exactly what nobody else can answer. Title, artist and track
    // number come from the folder structure, so the next scan would read them
    // back and an edit there would silently revert.

    suspend fun editAlbum(
        id: Int,
        date: String? = null,
        genres: String? = null,
        cover: JsonElement? = null,
    ): AlbumResponse = patch("/api/albums/$id", buildJsonObject {
        date?.let { put("date", it) }
        genres?.let { put("genres", it) }
        cover?.let { put("cover", it) }
    })

    /** Refused for a track that has an album - it takes these from the album. */
    suspend fun editSingle(
        id: Int,
        date: String? = null,
        genres: String? = null,
        cover: JsonElement? = null,
    ): TrackResponse = patch("/api/tracks/$id", buildJsonObject {
        date?.let { put("date", it) }
        genres?.let { put("genres", it) }
        cover?.let { put("cover", it) }
    })

    suspend fun editArtistCover(id: Int, cover: JsonElement): ArtistResponse =
        patch("/api/artists/$id", buildJsonObject { put("cover", cover) })

    /**
     * The payload `writeCover` expects: the type and the raw bytes as base64,
     * *not* a data URL. `JsonNull` is how a picture is removed - the server
     * clears the column and sets the lock, so the next scan cannot put the
     * embedded artwork back.
     */
    fun coverPayload(mime: String, base64: String): JsonElement = buildJsonObject {
        put("type", mime)
        put("data", base64)
    }

    // --- Scan, import, notices ------------------------------------------------

    suspend fun scanState(): ScanResponse = get("/api/scan")

    suspend fun startScan(): ScanResponse = post("/api/scan")

    suspend fun importCsv(text: String, name: String, playlistId: Int? = null, folderId: Int? = null): JsonElement =
        call(Request.Builder().url(url("/api/import/csv")).post(
            jsonBody(buildJsonObject {
                put("text", text)
                put("name", name)
                playlistId?.let { put("playlistId", it) }
                folderId?.let { put("folderId", it) }
            })
        ).build())

    suspend fun issues(): IssuesResponse = get("/api/import/issues")

    suspend fun recheckIssues(): IssuesResponse = post("/api/import/issues/recheck")

    suspend fun dismissIssue(id: Int) {
        delete<JsonElement>("/api/import/issues/$id")
    }

    suspend fun clearIssues() {
        delete<JsonElement>("/api/import/issues")
    }

    // --- Preferences and accounts ---------------------------------------------

    /** The account remembers this, so it follows the user to another device. */
    suspend fun setPref(key: String, value: JsonElement) {
        put<JsonElement>("/api/prefs", buildJsonObject {
            put("key", key)
            put("value", value)
        })
    }

    suspend fun users(): UsersResponse = get("/api/users")

    suspend fun createUser(username: String, password: String, displayName: String, isAdmin: Boolean): UsersResponse =
        post("/api/users", buildJsonObject {
            put("username", username)
            put("password", password)
            put("displayName", displayName)
            put("isAdmin", isAdmin)
        })

    suspend fun deleteUser(id: Int): UsersResponse = delete("/api/users/$id")

    suspend fun updateProfile(
        displayName: String,
        avatar: String,
        currentPassword: String = "",
        newPassword: String = "",
    ): ProfileResponse = put("/api/profile", buildJsonObject {
        put("displayName", displayName)
        put("avatar", avatar)
        if (newPassword.isNotEmpty()) {
            put("currentPassword", currentPassword)
            put("newPassword", newPassword)
        }
    })

    /**
     * One period at a time. The offset is in **minutes** and is what makes day
     * boundaries the listener's rather than the server's - the server stores
     * `played_at` in UTC and applies this when it groups.
     */
    suspend fun stats(offsetMinutes: Int, range: String? = null, period: String? = null): StatsResponse =
        get(
            "/api/stats",
            mapOf(
                "offset" to offsetMinutes.toString(),
                "range" to range,
                "period" to period,
            ),
        )
}
