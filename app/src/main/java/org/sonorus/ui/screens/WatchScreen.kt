package org.sonorus.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import org.sonorus.data.model.AudioInfo
import org.sonorus.data.model.Cue
import org.sonorus.data.model.PlayerInfo
import org.sonorus.player.Letterbox
import org.sonorus.player.PictureShare
import org.sonorus.player.VideoCaps
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.VideoFmt
import org.sonorus.ui.components.SeekRail
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Numbers shared with `public/js/video-player.js`, so both clients count the same. */
private const val NEXT_LEAD = 30.0
private const val SAVE_EVERY = 10.0
private const val PLAY_REPORT_EVERY = 30.0
private const val COMPLETE_AT = 0.9
private const val SKIP = 10.0
private const val HIDE_AFTER_MS = 3_000L
private const val SAMPLE_W = 192
private const val SAMPLE_H = 108

// How far a stream loads ahead. ExoPlayer's byte cap (about 140 MB) still holds, so a
// high bitrate stops sooner; a download is local and keeps the defaults.
private const val AHEAD_MS = 5 * 60_000

/**
 * One film or episode, full screen and always in landscape.
 *
 * The server contract is the web player's: ask `/plan` where to start, play the
 * file directly or a stream that starts at an offset, and keep the clock as
 * offset + position, because a stream cannot seek - a jump asks for a new one.
 * A downloaded video skips all that and plays from the phone. Subtitles are the
 * server's cues drawn here, not ExoPlayer's text tracks, so a stream, a file and
 * a download show them the same way.
 */
@UnstableApi
@Composable
fun WatchScreen(vm: AppViewModel, id: Int, fromStart: Boolean, onBack: () -> Unit, onNext: (Int) -> Unit) {
    // Not rememberLoad: it keys on the offline flag, and a flicker of the
    // network must not tear down a film that is playing.
    val info by produceState<Result<PlayerInfo>?>(null, id) {
        value = runCatching { vm.lib.playerInfo(id) }
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.player.pause() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val result = info
        when {
            result == null -> CircularProgressIndicator(color = SonorusTheme.colors.accent)
            result.isFailure -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.message(result.exceptionOrNull()!!), color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Zurück", color = SonorusTheme.colors.accent, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            }
            else -> VideoPlayer(vm, result.getOrThrow(), fromStart, onBack, onNext)
        }
    }
}

/**
 * Landscape, no system bars, screen on. Called by the shell for as long as the
 * route is a player, not by the player itself: going on to the next episode
 * swaps one player for another, and the old one letting go of landscape would
 * turn the phone upright between the two.
 */
@Composable
fun FullscreenLandscape() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        val window = activity?.window
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        // Android 15 and up draw into the camera cutout anyway; older ones need asking.
        val cutoutBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window?.attributes?.layoutInDisplayCutoutMode else null
        window?.setCutoutMode(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
        view.keepScreenOn = true
        onDispose {
            cutoutBefore?.let { window?.setCutoutMode(it) }
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before
            view.keepScreenOn = false
        }
    }
}

/**
 * Two fingers, reported once they lift, with how far they spread (above 1) or
 * closed. Taken before the tap handler below it, which then sees no click.
 */
private suspend fun PointerInputScope.detectPinch(onPinch: (Float) -> Unit) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var zoom = 1f
    var pinched = false
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed } >= 2) {
            pinched = true
            zoom *= event.calculateZoom()
        }
        if (pinched) event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
    if (pinched) onPinch(zoom)
}

private suspend fun SurfaceView.copyInto(frame: Bitmap): Boolean = suspendCancellableCoroutine { done ->
    try {
        PixelCopy.request(this, frame, { done.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        done.resume(false)
    }
}

private fun Window.setCutoutMode(mode: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    attributes = attributes.apply { layoutInDisplayCutoutMode = mode }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Everything that moves while one video plays. */
@UnstableApi
private class VideoSession(context: Context, private val vm: AppViewModel, val info: PlayerInfo, private val scope: CoroutineScope) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(vm.api.client)))
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(
                    AHEAD_MS,
                    AHEAD_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

    private val local: File? = vm.lib.store.videoFileOf(info.id)
    private val localKind: String? = vm.lib.store.videoOf(info.id)?.kind

    var mode by mutableStateOf("")
    private var offset = 0.0
    var pending by mutableStateOf<Double?>(null)
    var audio by mutableStateOf<Int?>(null)
    var sub by mutableStateOf<String?>(null)
    var cues by mutableStateOf<List<Cue>>(emptyList())
    var aspect by mutableFloatStateOf(16f / 9f)
    var playing by mutableStateOf(false)
    var wantsPlay by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var clock by mutableDoubleStateOf(0.0)
    var buffered by mutableDoubleStateOf(0.0)
    var nextDismissed by mutableStateOf(false)
    var picture by mutableStateOf<PictureShare?>(null)
    private val letterbox = Letterbox()
    private var pixels = IntArray(0)
    var forceComplete = false

    private var failed = 0
    private var loadSeq = 0
    private var seekJob: Job? = null
    private var subSeq = 0

    // Time really watched, for the statistics; the web counts the same way.
    private var watched = 0.0
    private var reported = 0.0
    private var playId: Int? = null
    private var playPending = false
    private var lastSaved = -100.0
    private var lastTick = 0L

    val duration: Double get() = info.duration

    fun now(): Double = pending ?: (offset + player.currentPosition / 1000.0)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantsPlay = playWhenReady
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) save()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) {
                    ended = true
                    forceComplete = true
                    save(force = true)
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onTracksChanged(tracks: Tracks) = applyAudio(tracks)

            override fun onPlayerError(e: PlaybackException) {
                failed++
                // What the phone refused as it is gets one more try the expensive way.
                val force = when (mode) {
                    "local", "direct" -> "remux"
                    "remux" -> "encode"
                    else -> null
                }
                if (force != null && failed < 3 && !vm.offline.value) {
                    load(now(), force = force, useLocal = false)
                } else {
                    error = "Dieses Video lässt sich hier nicht abspielen."
                }
            }
        })
    }

    /** The audio track to start with: the one picked last, else German, else English, else the file's default. */
    private fun defaultAudio(): Int? {
        val list = info.audio
        if (list.isEmpty()) return null
        for (want in listOf(vm.prefs.videoAudioLang, "ger", "deu", "de", "eng", "en").filter { it.isNotEmpty() }) {
            list.firstOrNull { it.lang == want }?.let { return it.index }
        }
        return (list.firstOrNull { it.default } ?: list.first()).index
    }

    fun load(at: Double, audioIndex: Int? = audio, force: String? = null, useLocal: Boolean = true, paused: Boolean = false) {
        val seq = ++loadSeq
        pending = at
        error = null
        scope.launch {
            if (useLocal && local != null) {
                mode = "local"
                offset = 0.0
                audio = if (localKind == "file") audioIndex ?: defaultAudio() else info.audio.firstOrNull()?.index
                if (player.currentMediaItem == null) {
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(local)))
                    player.prepare()
                }
                player.seekTo((at * 1000).toLong())
                pending = null
                player.playWhenReady = !paused
                applyAudio(player.currentTracks)
                return@launch
            }
            val plan = runCatching { vm.api.videoPlan(info.id, at, audioIndex, VideoCaps.json, force).plan }
                .getOrElse {
                    if (seq == loadSeq) {
                        pending = null
                        error = vm.message(it)
                    }
                    return@launch
                }
            if (seq != loadSeq) return@launch
            val url = vm.api.absolute(plan.url)
            val same = player.currentMediaItem?.localConfiguration?.uri?.toString() == url
            mode = plan.mode
            audio = plan.audio
            if (plan.mode == "direct") {
                offset = 0.0
                if (!same) {
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                }
                player.seekTo((at * 1000).toLong())
            } else {
                offset = plan.offset
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
            }
            pending = null
            player.playWhenReady = !paused
            applyAudio(player.currentTracks)
        }
    }

    /**
     * A file with several audio tracks plays them itself: the chosen one is the
     * n-th audio group, in the order ffprobe listed them. A stream carries only
     * the one it was asked for.
     */
    private fun applyAudio(tracks: Tracks) {
        if (mode != "direct" && !(mode == "local" && localKind == "file")) return
        val wanted = audio ?: return
        val at = info.audio.sortedBy { it.index }.indexOfFirst { it.index == wanted }
        val group = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(at) ?: return
        if (group.isSelected) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun toggle() {
        if (ended) {
            ended = false
            seek(0.0)
            player.play()
        } else if (player.playWhenReady) player.pause() else player.play()
    }

    // A file seeks itself; a stream is asked for again, a moment after the last
    // tap so a row of taps on +10 does not start a row of ffmpegs.
    fun seek(t: Double) {
        val target = t.coerceIn(0.0, (duration - 0.5).coerceAtLeast(0.0))
        if (target < duration - NEXT_LEAD) nextDismissed = false
        ended = false
        if (mode == "direct" || mode == "local") {
            player.seekTo((target * 1000).toLong())
        } else {
            pending = target
            seekJob?.cancel()
            val paused = !player.playWhenReady
            seekJob = scope.launch {
                delay(350)
                load(target, paused = paused)
            }
        }
        clock = target
        save()
    }

    fun pickAudio(track: AudioInfo) {
        vm.saveVideoPref("videoAudioLang", JsonPrimitive(track.lang)) { it.copy(videoAudioLang = track.lang) }
        if (mode == "local" && localKind != "file") return
        audio = track.index
        if (mode == "local") applyAudio(player.currentTracks) else load(now(), audioIndex = track.index)
    }

    fun pickSubtitle(key: String?, remember: Boolean = true) {
        sub = key
        cues = emptyList()
        val seq = ++subSeq
        val track = info.subtitles.firstOrNull { it.key == key }
        if (remember) {
            val lang = track?.lang?.ifEmpty { "und" } ?: ""
            vm.saveVideoPref("videoSubLang", JsonPrimitive(lang)) { it.copy(videoSubLang = lang) }
        }
        if (track == null) return
        vm.lib.store.cuesOf(info.id, track.key)?.let {
            cues = it
            return
        }
        scope.launch {
            var told = false
            while (true) {
                val answer = runCatching { vm.api.subtitleCues(info.id, track.key) }.getOrElse {
                    if (seq == subSeq) vm.say(vm.message(it), isError = true)
                    return@launch
                }
                if (seq != subSeq) return@launch
                if (!answer.pending) {
                    cues = answer.cues
                    if (told) vm.say("Untertitel sind da.")
                    return@launch
                }
                if (!told) {
                    vm.say("Untertitel werden aus der Datei gelesen, das dauert einen Moment …")
                    told = true
                }
                delay(3_000)
            }
        }
    }

    fun defaultSubtitle(): String? {
        val lang = vm.prefs.videoSubLang
        if (lang.isEmpty()) return null
        val usable = info.subtitles.filter { it.supported && it.lang.ifEmpty { "und" } == lang }
        return (usable.firstOrNull { !it.forced && !it.sdh } ?: usable.firstOrNull())?.key
    }

    fun cueText(at: Double): String = cues.filter { it.s <= at && at <= it.e }.joinToString("\n") { it.t }

    fun sample(frame: Bitmap) {
        if (pixels.size != frame.width * frame.height) pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        letterbox.add(pixels, frame.width, frame.height)
        picture = letterbox.share
    }

    /** Called a few times a second: the clock, the time watched, the saves. */
    fun tick() {
        clock = now()
        buffered = when {
            pending != null -> 0.0
            mode == "local" -> duration
            else -> offset + player.bufferedPosition / 1000.0
        }
        val t = System.currentTimeMillis()
        if (player.isPlaying && lastTick > 0) {
            val d = (t - lastTick) / 1000.0
            if (d > 0 && d < 2) watched += d
        }
        lastTick = t
        if (playId == null && !playPending && watched >= 10) {
            playPending = true
            vm.viewModelScope.launch {
                runCatching { vm.api.startVideoPlay(info.id) }.onSuccess { playId = it.playId }
                playPending = false
            }
        }
        if (playId != null && watched - reported >= PLAY_REPORT_EVERY) reportWatched()
        if (player.isPlaying && abs(clock - lastSaved) >= SAVE_EVERY) save()
    }

    private fun reportWatched() {
        val id = playId ?: return
        reported = watched
        val seconds = watched
        vm.viewModelScope.launch { runCatching { vm.api.updateVideoPlay(id, seconds) } }
    }

    /** On the view model's scope: the last save runs while the screen is already gone. */
    fun save(force: Boolean = false) {
        if (duration <= 0 || mode.isEmpty()) return
        val at = now()
        val completed = forceComplete || at >= duration * COMPLETE_AT
        if (!force && !completed && abs(at - lastSaved) < 2) return
        lastSaved = at
        vm.viewModelScope.launch { runCatching { vm.lib.setVideoProgress(info.id, at, completed) } }
    }

    fun release() {
        save(force = true)
        reportWatched()
        player.release()
    }
}

@UnstableApi
@Composable
private fun VideoPlayer(vm: AppViewModel, info: PlayerInfo, fromStart: Boolean, onBack: () -> Unit, onNext: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(info.id) { VideoSession(context, vm, info, scope) }
    var controls by remember { mutableStateOf(true) }
    var poked by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var fill by remember { mutableStateOf(vm.videoFill) }
    var fillLabel by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    val autoplay = vm.prefs.videoAutoplay
    val poke = {
        controls = true
        poked = System.currentTimeMillis()
    }

    DisposableEffect(session) {
        val start = if (fromStart) 0.0 else info.progress.position
        session.load(start)
        session.pickSubtitle(session.defaultSubtitle(), remember = false)
        onDispose { session.release() }
    }

    // Leaving the app pauses the film and keeps the place; it does not play on behind the lock screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(session) {
        while (true) {
            session.tick()
            if (controls && session.playing && !panel && dragging == null &&
                System.currentTimeMillis() - poked > HIDE_AFTER_MS
            ) {
                controls = false
            }
            delay(250)
        }
    }

    // A small copy of the running picture once a second, to learn its black bars.
    LaunchedEffect(session) {
        val frame = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
        while (true) {
            delay(1_000)
            val view = surface ?: continue
            if (!session.playing || session.buffering || session.pending != null) continue
            if (view.copyInto(frame)) session.sample(frame)
        }
    }

    LaunchedEffect(fillLabel) {
        if (fillLabel != null) {
            delay(1_200)
            fillLabel = null
        }
    }

    // The end: straight on into the next episode, or the card that offers it.
    LaunchedEffect(session.ended) {
        val next = info.next
        if (session.ended && next != null && autoplay && !session.nextDismissed) onNext(next.id)
        if (session.ended) poke()
    }

    val colors = SonorusTheme.colors
    val clock = session.clock
    val left = session.duration - clock
    val upNext = info.next?.takeIf {
        session.duration >= 120 && !session.nextDismissed &&
            ((left <= NEXT_LEAD && left > 0.3 && session.pending == null) || session.ended)
    }

    // Pinching out zooms past the black bars, pinching in shows the whole frame again.
    val share = session.picture?.takeIf { fill } ?: PictureShare(1f, 1f)
    val shareW by animateFloatAsState(share.width, tween(300), label = "fillWidth")
    val shareH by animateFloatAsState(share.height, tween(300), label = "fillHeight")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectPinch { zoom ->
                    val on = when {
                        zoom > 1.1f -> true
                        zoom < 0.9f -> false
                        else -> return@detectPinch
                    }
                    fill = on
                    vm.videoFill = on
                    fillLabel = (if (on) "Ausfüllen" else "Einpassen") to System.currentTimeMillis()
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (panel) panel = false else if (controls) controls = false else poke()
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { SurfaceView(it).also { view -> session.player.setVideoSurfaceView(view); surface = view } },
            onRelease = { session.player.clearVideoSurfaceView(it); surface = null },
            modifier = Modifier.layout { measurable, constraints ->
                val boxW = constraints.maxWidth
                val boxH = constraints.maxHeight
                val aspect = session.aspect
                val zoom = Letterbox.zoom(boxW.toFloat(), boxH.toFloat(), aspect, PictureShare(shareW, shareH))
                val w = (min(boxW.toFloat(), boxH * aspect) * zoom).roundToInt()
                val h = (w / aspect).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(boxW, boxH) { placeable.place((boxW - w) / 2, (boxH - h) / 2) }
            },
        )

        // Subtitles sit above the controls' reach, lifted while the bar is up.
        val cue = session.cueText(clock)
        if (cue.isNotEmpty()) {
            Text(
                cue,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black, blurRadius = 6f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controls) 96.dp else 28.dp, start = 48.dp, end = 48.dp)
                    .background(Color(0x66000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        if (session.buffering || session.pending != null) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(44.dp))
        }
        session.error?.let {
            Text(it, color = Color.White, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(12.dp))
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                // Top: back and what is playing.
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(info.title.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (info.kind == "show") {
                            Text(
                                VideoFmt.episodeCode(info.season, info.episode, info.episodeEnd) +
                                    if (info.name.isNotEmpty()) " · ${info.name}" else "",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { panel = !panel; poke() }) {
                        Icon(Icons.Filled.Subtitles, "Ton und Untertitel", tint = Color.White)
                    }
                    info.next?.let { next ->
                        IconButton(onClick = { session.forceComplete = true; onNext(next.id) }) {
                            Icon(Icons.Filled.SkipNext, "Nächste Folge", tint = Color.White)
                        }
                    }
                }

                // Middle: back ten, play, on ten.
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { session.seek(session.now() - SKIP); poke() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Replay10, "10 Sekunden zurück", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color(0x55000000)).clickable { session.toggle(); poke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (session.wantsPlay && !session.ended) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (session.wantsPlay) "Pause" else "Abspielen",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    IconButton(onClick = { session.seek(session.now() + SKIP); poke() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Forward10, "10 Sekunden vor", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                // Bottom: the rail and the two times.
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    val fraction = if (session.duration > 0) (clock / session.duration).toFloat().coerceIn(0f, 1f) else 0f
                    SeekRail(
                        fraction = dragging ?: fraction,
                        onScrub = { dragging = it; poke() },
                        onSeek = { session.seek(it * session.duration) },
                        height = 32.dp,
                        thickness = 4.dp,
                        rounded = true,
                        knob = 14.dp,
                        buffered = if (session.duration > 0) (session.buffered / session.duration).toFloat() else 0f,
                        trackColor = Color.White.copy(alpha = 0.24f),
                        bufferColor = Color.White.copy(alpha = 0.5f),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            VideoFmt.clock(dragging?.let { it * session.duration } ?: clock),
                            style = num(12.sp),
                            color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("-" + VideoFmt.clock(left.coerceAtLeast(0.0)), style = num(12.sp), color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }

        fillLabel?.let { (text, _) ->
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .offset(y = (-96).dp)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        if (upNext != null) {
            upNext.let { next ->
                NextCard(
                    label = VideoFmt.episodeCode(next.season, next.episode, next.episodeEnd) +
                        if (next.name.isNotEmpty()) " · ${next.name}" else "",
                    fill = if (autoplay && !session.ended) (1 - left / NEXT_LEAD).toFloat().coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = if (controls) 96.dp else 24.dp),
                    onPlay = {
                        session.forceComplete = true
                        onNext(next.id)
                    },
                    onDismiss = { session.nextDismissed = true },
                )
            }
        }

        if (panel) {
            TracksPanel(
                session = session,
                autoplay = autoplay,
                onAutoplay = { on -> vm.saveVideoPref("videoAutoplay", JsonPrimitive(on)) { it.copy(videoAutoplay = on) } },
                modifier = Modifier.align(Alignment.CenterEnd),
                onClose = { panel = false },
            )
        }
    }
}

@Composable
private fun NextCard(label: String, fill: Float, modifier: Modifier, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val colors = SonorusTheme.colors
    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6181520))
            .clickable(onClick = onPlay)
            .padding(12.dp),
    ) {
        Text("NÄCHSTE FOLGE", style = org.sonorus.ui.theme.RackLabel, color = Color.White.copy(alpha = 0.7f))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(fill).height(3.dp).background(colors.accent))
        }
        Text(
            "Ausblenden",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp).clickable(onClick = onDismiss),
        )
    }
}

@UnstableApi
@Composable
private fun TracksPanel(session: VideoSession, autoplay: Boolean, onAutoplay: (Boolean) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    val colors = SonorusTheme.colors
    val info = session.info
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Color(0xF0141119))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TON", style = org.sonorus.ui.theme.RackLabel, color = Color.White.copy(alpha = 0.6f))
        for (a in info.audio) {
            val (main, tech) = VideoFmt.audioLabel(a)
            PanelRow(main, tech, a.index == session.audio) { session.pickAudio(a) }
        }
        Spacer(Modifier.height(12.dp))
        Text("UNTERTITEL", style = org.sonorus.ui.theme.RackLabel, color = Color.White.copy(alpha = 0.6f))
        PanelRow("Aus", "", session.sub == null) { session.pickSubtitle(null) }
        for (s in info.subtitles) {
            PanelRow(
                VideoFmt.subtitleLabel(s),
                if (s.supported) "" else "Bild-Untertitel, nicht unterstützt",
                s.key == session.sub,
                enabled = s.supported,
            ) { session.pickSubtitle(s.key) }
        }
        if (info.kind == "show") {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nächste Folge automatisch", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoplay,
                    onCheckedChange = onAutoplay,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                )
            }
        }
    }
}

@Composable
private fun PanelRow(main: String, sub: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(main, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyMedium)
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
