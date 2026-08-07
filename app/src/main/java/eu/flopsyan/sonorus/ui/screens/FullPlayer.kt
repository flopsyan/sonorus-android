package eu.flopsyan.sonorus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.Lyrics
import eu.flopsyan.sonorus.player.PlayerState
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.Routes
import eu.flopsyan.sonorus.ui.components.Cover
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.SeekRail
import eu.flopsyan.sonorus.ui.components.Stars
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How far ahead of the voice a lyric line is shown, in seconds.
 *
 * Reading a line costs a moment, so a line lit exactly on its own timestamp is
 * already half sung by the time it has been read - which is the whole reason
 * singing along to it does not work. Nothing says Spotify draws a line early;
 * its own guidance is to time a line to the first word being sung, and the sync
 * tool behind it allows a line to sit at most half a second ahead of that.
 *
 * A full second is therefore deliberately *more* than any synced lyric is built
 * with, and it is what a karaoke lead-in has always been: enough to read the
 * line, draw breath and come in on the beat. The trade is that a song whose
 * lines follow each other faster than a second will show the next one while the
 * current is still being sung. Turned up from half a second on 2026-08-06.
 */
private const val LYRICS_LEAD_SECONDS = 1.0

/**
 * The player as a full screen, the way Spotify does it - explicitly a phone
 * feature.
 *
 * It shows the *same* player as the bar, not a second one: every control here
 * drives the same state, which is the decision that keeps the two from ever
 * drifting apart. Ways out: the chevron, a wipe down over the artwork, the back
 * button, and following a link in it.
 */
@UnstableApi
@Composable
fun FullPlayer(
    vm: AppViewModel,
    state: PlayerState,
    onClose: () -> Unit,
    onGo: (String) -> Unit,
) {
    val track = state.current ?: return
    val colors = SonorusTheme.colors
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    // The words come out of the file itself, and only this screen shows them -
    // so only this screen asks for them, and only while it is open.
    val lyrics by vm.lyrics.collectAsState()
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    // Which line is being sung - a touch early on purpose, see
    // [LYRICS_LEAD_SECONDS]. -1 before the first one, and always -1 for a lyric
    // the file gave no timestamps for: there is nothing to point at then.
    val activeLine = lyrics.lineAt(state.positionMs / 1000.0 + LYRICS_LEAD_SECONDS)
    // How far the artwork is dragged sideways at the moment. Zero unless a wipe
    // is running, and animated back there when it ends.
    val swipe = remember { Animatable(0f) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .systemBarsPadding()
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ExpandMore, "Schließen", tint = colors.text)
                    }
                    Spacer(Modifier.size(8.dp))
                    RackLabelText(state.source.ifEmpty { "Wiedergabe" }, Modifier.weight(1f))
                    // Only offered for a song that has a text - a button opening
                    // an empty page is noise.
                    if (track.hasLyrics) {
                        IconButton(onClick = {
                            showLyrics = !showLyrics
                            if (showLyrics) showQueue = false
                        }) {
                            Icon(
                                Icons.Filled.Lyrics,
                                "Songtext",
                                tint = if (showLyrics) colors.accent else colors.text,
                            )
                        }
                    }
                    IconButton(onClick = {
                        showQueue = !showQueue
                        if (showQueue) showLyrics = false
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            "Warteschlange",
                            tint = if (showQueue) colors.accent else colors.text,
                        )
                    }
                }

                if (showQueue) {
                    // The real upcoming order - which is what shuffling once up
                    // front, instead of per track, is for.
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(state.upcoming) { i, item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.player.jumpTo(state.pos + 1 + i) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("${i + 1}", style = num(12.sp), color = colors.textFaint)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        item.artist,
                                        color = colors.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(Fmt.duration(item.duration), style = num(12.sp), color = colors.textDim)
                            }
                        }
                    }
                } else if (showLyrics) {
                    LyricsView(
                        lyrics,
                        activeLine,
                        // Landing the same head start before the line keeps the
                        // tapped line the lit one once the jump is through -
                        // seeking to the timestamp itself would hand the light
                        // straight to the next line on a densely sung song.
                        onSeek = { seconds ->
                            val at = (seconds - LYRICS_LEAD_SECONDS).coerceAtLeast(0.0)
                            vm.player.seekTo((at * 1000).toLong())
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(Unit) {
                                coverGestures(
                                    swipe = swipe,
                                    onNext = { vm.player.next() },
                                    onPrevious = { vm.player.previous() },
                                    onClose = onClose,
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Cover(
                            vm.coverUrl(track.cover),
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                // The artwork follows the finger, so a wipe says
                                // what it is about to do before it is let go.
                                .offset { IntOffset(swipe.value.toInt(), 0) },
                            RoundedCornerShape(14.dp),
                            track.title,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    // The one line of the lyric a phone has room for without
                    // leaving the player, in the gap between the artwork and the
                    // title. Only a timed lyric has a line to point at, and a
                    // song without one must not reserve the space. Tapping it
                    // opens the rest.
                    if (activeLine >= 0) {
                        Text(
                            lyrics.lines[activeLine].text,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.accent,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLyrics = true },
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            track.artist.takeIf { it.isNotEmpty() },
                            track.album.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // A song on a "Various" compilation names an interpret
                        // that has no page of its own, so there the line is not
                        // a target at all instead of a tap that does nothing.
                        modifier = track.artistId?.let { artistId ->
                            Modifier.clickable { onGo(Routes.artist(artistId)) }
                        } ?: Modifier,
                    )
                    Spacer(Modifier.height(12.dp))
                    // The full screen shows the stars again - the bar has no
                    // room for them, so this is one of the ways to hand out a
                    // rating on a phone. Next to them the one other thing worth
                    // doing with a song you are hearing: put it on a list.
                    // Deliberately nothing beyond that - the row's own menu
                    // carries the rest.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // The queue holds the track as it was when it was added,
                        // so its own stars go stale the moment one is given here.
                        val stars = vm.starsOf(track)
                        Stars(stars, size = 30) { value ->
                            vm.rate(track.id, value, stars)
                        }
                        IconButton(
                            onClick = { vm.askForPlaylist(track, allowCreate = false) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                "Zu Playlist hinzufügen",
                                tint = colors.textDim,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Seek rail. While it is held the position is only drawn, and
                // the seek runs on release - writing the position on every move
                // makes the player re-request the file and stutter. The knob is
                // worth its space here and only here: the rail sits inside the
                // padding, so it cannot be cut off by the edge of the screen.
                val fraction = scrub
                    ?: if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else 0f
                SeekRail(
                    fraction = fraction,
                    onScrub = { scrub = it },
                    onSeek = { f ->
                        if (state.durationMs > 0) {
                            vm.player.seekTo((state.durationMs * f).toLong())
                        }
                    },
                    height = 26.dp,
                    thickness = 6.dp,
                    rounded = true,
                    knob = 11.dp,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        Fmt.duration((fraction * state.durationMs / 1000).toDouble()),
                        style = num(12.sp),
                        color = colors.textDim,
                    )
                    Text(
                        Fmt.duration(state.durationMs / 1000.0),
                        style = num(12.sp),
                        color = colors.textDim,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = {
                        vm.player.setShuffle(!state.shuffle)
                        vm.savePlayerPrefs()
                    }) {
                        Icon(
                            Icons.Filled.Shuffle,
                            "Zufall",
                            tint = if (state.shuffle) colors.accent else colors.textDim,
                        )
                    }
                    IconButton(onClick = { vm.player.previous() }) {
                        Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.text, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { vm.player.toggle() }, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (state.playing) "Pause" else "Abspielen",
                            tint = colors.accent,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                    IconButton(onClick = { vm.player.next() }) {
                        Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.text, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = {
                        vm.player.cycleRepeat()
                        vm.savePlayerPrefs()
                    }) {
                        Icon(
                            if (state.repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Wiederholen",
                            tint = if (state.repeat != "off") colors.accent else colors.textDim,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The lyric list is the one thing in the app read at arm's length while singing
 * along, so it is set far larger than any shared typography token - which is
 * why these two are its own styles and not `MaterialTheme.typography`.
 */
private val LyricLine = TextStyle(
    fontSize = 22.sp,
    lineHeight = 30.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.02).em,
)
private val LyricLineActive = LyricLine.copy(
    fontSize = 26.sp,
    lineHeight = 34.sp,
    fontWeight = FontWeight.Bold,
)

/**
 * The whole lyric, in the place the artwork usually holds.
 *
 * Whether it can follow the song is the file's decision, not ours: with
 * timestamps the line being sung is lit and scrolled to, and without them the
 * text simply stands there instead of waiting for a cue that never comes.
 *
 * A timed line is also a target: tapping one seeks the song to it, which is the
 * fastest way back to the chorus and the reason the list is worth opening while
 * something is playing at all.
 *
 * Deliberately no "the reader scrolled, stop following" rule here, unlike the
 * web panel: the list is the whole screen and a finger that scrolls it is a
 * finger that has stopped watching, so the next line pulling it back is what
 * you want a moment later anyway.
 */
@Composable
private fun LyricsView(
    lyrics: Lyrics,
    activeLine: Int,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SonorusTheme.colors
    val listState = rememberLazyListState()

    // Half the visible height up, so the line being sung sits in the middle
    // rather than at the top edge where the next ones are hidden below it.
    LaunchedEffect(activeLine) {
        if (activeLine < 0) return@LaunchedEffect
        val middle = listState.layoutInfo.viewportSize.height / 2
        listState.animateScrollToItem(activeLine, -middle)
    }

    if (lyrics.lines.isEmpty() && lyrics.text.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "In dieser Datei steckt kein Songtext.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // Without timestamps there is no line list, so the plain text is split into
    // one item per line and nothing is ever highlighted.
    val lines = lyrics.lines.map { it.text }.ifEmpty { lyrics.text.split("\n") }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        itemsIndexed(lines) { i, line ->
            // Tapping a line jumps the song to it, the way Spotify does. Only a
            // timed lyric can offer that - without timestamps there is nowhere
            // to jump to, and that is exactly when `lyrics.lines` is empty.
            val at = lyrics.lines.getOrNull(i)?.time
            Text(
                line,
                style = if (i == activeLine) LyricLineActive else LyricLine,
                color = when {
                    // Nothing is lit for an untimed lyric, so it reads as one
                    // block instead of sitting there greyed out.
                    activeLine < 0 && lyrics.lines.isEmpty() -> colors.text
                    i == activeLine -> colors.accent
                    // Already sung stays readable - it is the context for the
                    // line that is running - while what is ahead is quieter.
                    i < activeLine -> colors.textDim
                    else -> colors.textFaint
                },
                // The padding sits inside the tap, so the whole strip is the
                // target and not just the glyphs.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (at != null) Modifier.clickable { onSeek(at) } else Modifier)
                    .padding(vertical = 8.dp),
            )
        }
        // Without this the last lines can never reach the middle of the screen,
        // and the text stops following right where a song usually says the thing
        // you looked it up for.
        item { Spacer(Modifier.height(220.dp)) }
    }
}

/**
 * The gestures on the artwork: **wipe sideways for the next or the previous
 * song**, wipe down to close - the two a phone player is expected to have.
 *
 * Both live in one handler on purpose. Two of them side by side would each
 * claim the same finger, and a drag that starts a little diagonally would run
 * both. So the first clear movement decides once what this gesture is, and the
 * other axis is ignored for the rest of it.
 *
 * The artwork follows the finger through [swipe] while it is horizontal, which
 * is what makes the gesture readable before it is let go: nothing happens until
 * a quarter of the width is behind it, and a wipe that stops short slides back
 * instead of skipping a song by accident.
 */
private suspend fun PointerInputScope.coverGestures(
    swipe: Animatable<Float, AnimationVector1D>,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) = coroutineScope {
    val skipAfter = size.width / 4f
    // Further than the slop that decides the axis, so closing stays a deliberate
    // pull rather than something a shaky tap can do.
    val closeAfter = viewConfiguration.touchSlop * 4

    while (true) {
        val down = awaitPointerEventScope { awaitFirstDown() }
        // A new grab takes the artwork over from the animation that is putting it
        // back, so a quick second wipe is not fought over.
        swipe.stop()

        var dx = 0f
        var dy = 0f
        var horizontal: Boolean? = null
        var closed = false

        awaitPointerEventScope {
            drag(down.id) { change ->
                val moved = change.positionChange()
                dx += moved.x
                dy += moved.y
                if (horizontal == null && maxOf(abs(dx), abs(dy)) > viewConfiguration.touchSlop) {
                    horizontal = abs(dx) > abs(dy)
                }
                if (horizontal == true) {
                    change.consume()
                    launch { swipe.snapTo(swipe.value + moved.x) }
                } else if (horizontal == false && dy > closeAfter && !closed) {
                    closed = true
                    change.consume()
                    onClose()
                }
            }
        }

        // Left is forward: the song after this one comes in from the right, the
        // way a list moves under a finger.
        if (horizontal == true) {
            if (dx <= -skipAfter) onNext() else if (dx >= skipAfter) onPrevious()
        }
        launch { swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }
}
