package org.sonorus.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.sonorus.MainActivity
import org.sonorus.SonorusApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A foreground service is what buys the native client its two real advantages
 * over the web app: playback that survives the app going to the background, and
 * a system notification Android draws itself - metadata, artwork, a progress
 * bar and prev/next - instead of one the browser decides how much of to grant.
 *
 * The player is the one from [SonorusApp], not a second one: service and UI
 * live in the same process, so the session wraps the player the screens are
 * already driving and the two can never drift apart.
 *
 * It is a **[MediaLibraryService] rather than a `MediaSessionService`**, which
 * is the whole of Android Auto: a session only answers transport commands, a
 * library session also lets a controller browse. The car is then just another
 * controller of the same player - what it starts shows up in the app's queue,
 * and what the app plays shows up in the car.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var app: SonorusApp
    private lateinit var tree: MediaBrowseTree
    private var mediaSession: MediaLibrarySession? = null

    /** Browsing is I/O, and the callbacks have to answer without blocking. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        app = application as SonorusApp
        tree = MediaBrowseTree(app.library)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaLibrarySession.Builder(this, app.player.exoPlayer, LibraryCallback())
            .setSessionActivity(openApp)
            // Artwork has to come through the same OkHttp client as everything
            // else, and for the same reason the stream does: `/covers` needs the
            // session cookie, and the stock loader sends none. `DefaultDataSource`
            // on top so a downloaded cover is read straight off the phone.
            .setBitmapLoader(
                DataSourceBitmapLoader.Builder(this)
                    .setDataSourceFactory(
                        DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.api.client))
                    )
                    .build()
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    /**
     * Swiping the notification away with nothing playing should end the service
     * rather than leave a dead card standing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The player belongs to the application and outlives this service, so
        // only the session is released here.
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
            LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = answer {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /**
         * A voice search, and the one browsing path that answers in two steps:
         * the car is told how many results there are and asks for them after.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = answer {
            val hits = tree.search(query)
            session.notifySearchResultChanged(browser, query, hits.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
            LibraryResult.ofItemList(tree.search(query).page(page, pageSize), params)
        }

        /**
         * A row was tapped in the car. What arrives is the media id and nothing
         * else, so the song has to be looked up - and with it the list it was
         * listed in, because tapping the third song of an album has to play the
         * album from the third song, exactly as it does on the phone.
         *
         * [PlayerController.adoptQueue] writes the app's own queue and hands
         * back the order; the session puts that order into ExoPlayer itself.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val unchanged = MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            val picked = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            val (parent, trackId) = picked?.mediaId?.let(MediaIds::parse)
                ?: return Futures.immediateFuture(unchanged)

            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val queued = runCatching {
                    val list = tree.tracksOf(parent)
                    val at = list.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                    // The browse ids *are* the app's routes (`albums/7`), so
                    // the node a song was tapped under is already the key the
                    // screens compare against - put an album on in the car and
                    // its page on the phone marks it as playing from there.
                    app.player.adoptQueue(list, at, tree.label(parent), parent)
                }.getOrNull()
                future.set(
                    if (queued == null || queued.tracks.isEmpty()) unchanged
                    else MediaSession.MediaItemsWithStartPosition(
                        queued.tracks.map(app.player::mediaItem),
                        queued.startIndex,
                        C.TIME_UNSET,
                    )
                )
            }
            return future
        }
    }

    /**
     * Runs a browsing answer off the callback thread. A failure is an ordinary
     * result rather than a broken future: the server can simply be gone, and
     * the car should say so instead of the session dying with it.
     */
    private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            if (!app.session.isConfigured) {
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED))
                return@launch
            }
            future.set(
                runCatching { block() }
                    .getOrElse { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) }
            )
        }
        return future
    }

    /**
     * The car walks a long list in pages, and a page past the end is a legal
     * question with an empty answer - not a crash.
     */
    private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0) return ImmutableList.copyOf(this)
        val from = page.toLong() * pageSize
        if (from >= size) return ImmutableList.of()
        return ImmutableList.copyOf(subList(from.toInt(), minOf(from + pageSize, size.toLong()).toInt()))
    }
}
