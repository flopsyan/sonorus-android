package org.sonorus.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.download.DownloadStatus
import org.sonorus.data.model.Lyrics
import org.sonorus.data.model.Track
import org.sonorus.player.PlayerState
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.Motion
import org.sonorus.ui.Routes
import org.sonorus.ui.armed
import org.sonorus.ui.pressable
import org.sonorus.ui.components.Cover
import org.sonorus.ui.components.MenuItem
import org.sonorus.ui.components.PlayerCoverKey
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SeekRail
import org.sonorus.ui.components.Stars
import org.sonorus.ui.components.TransportGlyph
import org.sonorus.ui.components.rememberPlayhead
import org.sonorus.ui.theme.RackLabel
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num
import org.sonorus.ui.toggled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * The name of the song, and the line under it.
 *
 * Both are the player's own styles rather than shared tokens, for the same
 * reason the lyric lines below are: this screen is read at a glance and at arm's
 * length, and no list row wants these sizes. The title came down from
 * `displaySmall` (27 sp) and the line under it went up from `bodyLarge` (15 sp)
 * on 2026-08-24 - the old pair put almost all the weight on the title, and the
 * album it names is a link now, so it has to be readable as one.
 */
private val PlayerTitle = TextStyle(
    fontSize = 24.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.02).em,
)
private val PlayerSubtitle = TextStyle(fontSize = 17.sp, lineHeight = 23.sp)

/**
 * What a tapped title costs to read once, end to end.
 *
 * `basicMarquee` neither reports that it has finished nor says how far it has to
 * travel, so the way back to an ellipsis has to be timed by hand. The distance
 * is estimated from the number of characters that do not fit - [MARQUEE_CHAR_DP]
 * is an average glyph at [PlayerTitle]'s size, not a measurement - so the timing
 * is approximate on purpose. Being wrong costs nothing either way: too early and
 * the run is cut short, too late and the name simply stands there a moment
 * longer before its "..." comes back.
 */
private const val MARQUEE_CHAR_DP = 12f
private const val MARQUEE_VELOCITY_DP = 45f
private const val MARQUEE_LEAD_MS = 500L

/**
 * The two lines over the artwork: what kind of list is playing, and which one.
 *
 * The kind is read off [PlayerState.sourceKey] - the *route* the queue came from
 * (`albums/7`, `playlists/2`) - because that is the only part of the player state
 * that is structured. [PlayerState.source] is a label written for a human and its
 * shape differs per screen ("Album: Kid A", but a bare "Crywank"), so it supplies
 * the name and the prefix is taken back off.
 *
 * A list whose name *is* its kind - Downloads, Alle Songs, Suche - answers with a
 * null kind and is drawn on one line: "Wiedergabe aus Downloads / Downloads"
 * would say it twice.
 */
private fun playbackSource(state: PlayerState): Pair<String?, String> {
    val name = state.source.ifEmpty { "Wiedergabe" }
    val key = state.sourceKey
    return when {
        key.startsWith("albums/") -> "Album" to name.removePrefix("Album: ")
        key.endsWith("/singles") -> "Singles" to name.removeSuffix(": Singles").removeSuffix("Singles")
            .ifBlank { name }
        key.contains("/stars/") || key.startsWith("stars/") -> "Bewertung" to name
        key.startsWith("artists/") -> "Interpret" to name
        key.startsWith("playlists/") -> "Playlist" to name
        key.startsWith("genres/") -> "Genre" to name
        else -> null to name
    }
}

/**
 * The player as a full screen, the way Spotify does it - explicitly a phone
 * feature.
 *
 * It shows the *same* player as the bar, not a second one: every control here
 * drives the same state, which is the decision that keeps the two from ever
 * drifting apart. Ways out: the chevron, a wipe down over the artwork, the back
 * button, and following a link in it.
 *
 * ## Why this is no longer a Dialog
 *
 * It used to be one, and a `Dialog` cannot take part in a shared element: it
 * lives in its own window, outside the composition the bar is in, so the two
 * artworks could only ever be two separate pictures - the full screen appeared
 * over the bar, instantly, with nothing connecting them. As an overlay in the
 * same `SharedTransitionLayout` the cover in the bar *is* the cover here, and
 * opening the player grows it into place.
 *
 * What the dialog used to hand over for free and is therefore done by hand: the
 * back button ([BackHandler]), and swallowing taps so nothing behind the player
 * can be hit through it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun SharedTransitionScope.FullPlayer(
    vm: AppViewModel,
    state: PlayerState,
    visibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    onGo: (String) -> Unit,
) {
    val track = state.current ?: return
    val colors = SonorusTheme.colors
    val haptics = LocalHapticFeedback.current
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    // What this phone has of the song. Read here rather than passed in, so the
    // button below redraws the moment the download finishes.
    val downloads by vm.downloads.state.collectAsState()
    val downloadStatus = downloads.statusOf(track.id)

    BackHandler(onBack = onClose)

    // The words come out of the file itself, and only this screen shows them -
    // so only this screen asks for them, and only while it is open.
    val lyrics by vm.lyrics.collectAsState()
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    // Which line is being sung - a touch early on purpose, see
    // [LYRICS_LEAD_SECONDS] - and moved by whatever this song's own file needed,
    // see [Lyrics.offset]. -1 before the first one, and always -1 for a lyric
    // the file gave no timestamps for: there is nothing to point at then.
    val activeLine = lyrics.lineAt(state.positionMs / 1000.0 + LYRICS_LEAD_SECONDS - lyrics.offset)
    // How far the artwork is dragged sideways at the moment. Zero unless a wipe
    // is running, and animated back there when it ends.
    val swipe = remember { Animatable(0f) }

    Box(
        Modifier
            .fillMaxSize()
            // The chassis lifted towards the top rather than one flat black.
            // Same two tokens the rest of the app is built from - a player that
            // tinted itself from the artwork would be a second colour, and this
            // design has exactly one lamp.
            .background(
                Brush.verticalGradient(
                    0f to colors.surface,
                    0.5f to colors.bg,
                    1f to colors.bg,
                )
            )
            // Nothing behind the player may be hit through it. A dialog got this
            // from its own window; an overlay has to claim the taps itself.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                // Chevron, the two lines saying what is playing, and the menu -
                // and nothing else. What used to sit here (lyrics, queue) moved
                // below the transport: this row is what the eye lands on when
                // the player opens, and a whisper of a label with three buttons
                // beside it was the reason the top of the screen read as empty.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ExpandMore, "Schließen", tint = colors.text)
                    }
                    val (kind, sourceName) = playbackSource(state)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (kind != null) {
                            Text(
                                "Wiedergabe aus $kind",
                                style = RackLabel,
                                color = colors.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            sourceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "Mehr", tint = colors.text)
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
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LyricsView(
                            lyrics,
                            activeLine,
                            // The arithmetic over the line read backwards, so a
                            // tapped line is the lit one once the jump is
                            // through. Landing on the timestamp itself would
                            // hand the light straight to the next line on a
                            // densely sung song.
                            onSeek = { seconds ->
                                val at = (seconds - LYRICS_LEAD_SECONDS + lyrics.offset)
                                    .coerceAtLeast(0.0)
                                vm.player.seekTo((at * 1000).toLong())
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Over the running text and deliberately not a dialog:
                        // nothing behind it stops, so the lines keep moving
                        // while the number does - which is the only way to tell
                        // whether the number is right yet.
                        if (showOffset && lyrics.lines.isNotEmpty()) {
                            LyricOffsetCard(
                                offset = lyrics.offset,
                                onChange = { vm.setLyricsOffset(track.id, it) },
                                onClose = { showOffset = false },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(Unit) {
                                coverGestures(
                                    swipe = swipe,
                                    onArmed = { haptics.armed() },
                                    onNext = { vm.player.next() },
                                    // A wipe always leaves for the song before,
                                    // however far into this one it is - see
                                    // PlayerController.previous. The button in
                                    // the transport below keeps the old rule.
                                    onPrevious = { vm.player.previous(restartFirst = false) },
                                    onClose = onClose,
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Paused pulls the record back a little, the way a deck
                        // lifts the arm off it. It is the one place the whole
                        // screen says "stopped" without a word for it, and it is
                        // drawn rather than laid out - the shared element the
                        // cover takes part in measures the real bounds, and
                        // those must not move under it.
                        val lift by animateFloatAsState(
                            if (state.playing) 1f else 0.93f,
                            Motion.travel(),
                            label = "coverLift",
                        )
                        Cover(
                            vm.coverUrl(track.cover),
                            Modifier
                                // The same picture the bar carries, not a copy of
                                // it: opening the player grows the 44 dp thumb
                                // into this. See [PlayerCoverKey].
                                .sharedElement(
                                    sharedContentState = rememberSharedContentState(PlayerCoverKey),
                                    animatedVisibilityScope = visibilityScope,
                                )
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                // The artwork follows the finger, so a wipe says
                                // what it is about to do before it is let go.
                                .offset { IntOffset(swipe.value.toInt(), 0) }
                                .graphicsLayer { scaleX = lift; scaleY = lift },
                            RoundedCornerShape(20.dp),
                            track.title,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
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
                    // The name of the song is what this screen is opened to
                    // read, so it is set at the top of the scale rather than
                    // the same size as a list row - and the one other thing
                    // worth doing with a song you are hearing sits beside it
                    // instead of below, which is what kept the block stacked.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // A title too long to fit is cut with an ellipsis
                            // and stays that way until it is tapped, and then it
                            // runs through once so it can be read. It used to
                            // scroll for ever, unasked, which made the busiest
                            // line on the screen the one nobody had asked to
                            // move.
                            var tooLong by remember(track.id) { mutableStateOf(false) }
                            var running by remember(track.id) { mutableStateOf(false) }
                            var fieldDp by remember(track.id) { mutableFloatStateOf(0f) }
                            val density = LocalDensity.current
                            LaunchedEffect(running) {
                                if (!running) return@LaunchedEffect
                                val over = (track.title.length * MARQUEE_CHAR_DP - fieldDp)
                                    .coerceAtLeast(0f)
                                delay(MARQUEE_LEAD_MS + (over / MARQUEE_VELOCITY_DP * 1000).toLong())
                                running = false
                            }
                            Text(
                                track.title,
                                style = PlayerTitle,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // Measured off the ellipsized draw, which is the
                                // only one whose width *is* the field: inside a
                                // marquee the text is measured unbounded.
                                onTextLayout = { result ->
                                    if (!running) {
                                        tooLong = result.hasVisualOverflow
                                        fieldDp = with(density) { result.size.width.toDp().value }
                                    }
                                },
                                modifier = Modifier
                                    .then(
                                        if (tooLong) Modifier.clickable { running = true }
                                        else Modifier
                                    )
                                    .then(
                                        if (running) Modifier.basicMarquee(
                                            iterations = 1,
                                            initialDelayMillis = MARQUEE_LEAD_MS.toInt(),
                                            velocity = MARQUEE_VELOCITY_DP.dp,
                                        ) else Modifier
                                    ),
                            )
                            Spacer(Modifier.height(3.dp))
                            // Two links, not one line: the album used to be part
                            // of the interpret's target and therefore led to the
                            // interpret, which is the one place in the app where
                            // a name did not go where it says.
                            //
                            // A song on a "Various" compilation names an
                            // interpret that has no page of its own, and a
                            // single has no album - either half is a plain
                            // caption then rather than a tap that does nothing.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (track.artist.isNotEmpty()) {
                                    Text(
                                        track.artist,
                                        style = PlayerSubtitle,
                                        color = colors.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .then(
                                                track.artistId?.let { id ->
                                                    Modifier.clickable { onGo(Routes.artist(id)) }
                                                } ?: Modifier
                                            ),
                                    )
                                }
                                if (track.artist.isNotEmpty() && track.album.isNotEmpty()) {
                                    Text(
                                        " · ",
                                        style = PlayerSubtitle,
                                        color = colors.textDim,
                                        maxLines = 1,
                                    )
                                }
                                if (track.album.isNotEmpty()) {
                                    Text(
                                        track.album,
                                        style = PlayerSubtitle,
                                        color = colors.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .then(
                                                track.albumId?.let { id ->
                                                    Modifier.clickable { onGo(Routes.album(id)) }
                                                } ?: Modifier
                                            ),
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { vm.askForPlaylist(track, allowCreate = false) },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                "Zu Playlist hinzufügen",
                                tint = colors.textDim,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        // Putting a song on the phone is the other thing worth
                        // doing to what is playing, and until now the only way
                        // to it was to find the song again in a list. The glyph
                        // says what tapping does *now*, exactly as the row menu
                        // does - a missing file has nothing to fetch.
                        if (!track.missing) {
                            IconButton(
                                onClick = {
                                    when (downloadStatus) {
                                        DownloadStatus.DONE -> vm.removeDownloads(listOf(track))
                                        DownloadStatus.RUNNING,
                                        DownloadStatus.QUEUED -> vm.downloads.cancel(track.id)
                                        else -> vm.download(listOf(track))
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    when (downloadStatus) {
                                        DownloadStatus.DONE -> Icons.Filled.DownloadDone
                                        DownloadStatus.RUNNING,
                                        DownloadStatus.QUEUED -> Icons.Filled.Downloading
                                        else -> Icons.Filled.Download
                                    },
                                    when (downloadStatus) {
                                        DownloadStatus.DONE -> "Download entfernen"
                                        DownloadStatus.RUNNING,
                                        DownloadStatus.QUEUED -> "Download abbrechen"
                                        DownloadStatus.FAILED -> "Download erneut versuchen"
                                        else -> "Herunterladen"
                                    },
                                    tint = when (downloadStatus) {
                                        DownloadStatus.DONE,
                                        DownloadStatus.RUNNING,
                                        DownloadStatus.QUEUED -> colors.accent
                                        DownloadStatus.FAILED -> colors.danger
                                        else -> colors.textDim
                                    },
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // The full screen shows the stars again - the bar has no
                    // room for them, so this is one of the ways to hand out a
                    // rating on a phone. Deliberately nothing beyond that: the
                    // row's own menu carries the rest.
                    //
                    // The queue holds the track as it was when it was added, so
                    // its own stars go stale the moment one is given here.
                    val stars = vm.starsOf(track)
                    Stars(stars, size = 30) { value ->
                        vm.rate(track.id, value, stars)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Seek rail. While it is held the position is only drawn, and
                // the seek runs on release - writing the position on every move
                // makes the player re-request the file and stutter. The knob is
                // worth its space here and only here: the rail sits inside the
                // padding, so it cannot be cut off by the edge of the screen.
                val reported = scrub
                    ?: if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else 0f
                val fraction = rememberPlayhead(reported, held = scrub != null, trackKey = track.id)
                SeekRail(
                    fraction = fraction,
                    onScrub = { scrub = it },
                    onSeek = { f ->
                        if (state.durationMs > 0) {
                            vm.player.seekTo((state.durationMs * f).toLong())
                        }
                    },
                    height = 24.dp,
                    thickness = 4.dp,
                    rounded = true,
                    knob = 10.dp,
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

                Spacer(Modifier.height(12.dp))
                // Five controls that used to carry the same weight, and one of
                // them is the reason the screen is open. Play/pause is now the
                // only filled surface anywhere in the player, so the thumb
                // finds it without looking; the two skips stay glyphs, and the
                // two modes - which are settings rather than actions - sit
                // quieter still at the edges.
                //
                // The four around the circle grew on 2026-08-24. The sizes are
                // measured, not guessed: on the same phone Spotify draws its
                // shuffle 22.5 dp wide and its skips 21.3 dp, against 14.5 dp
                // and 17.9 dp here - the glyphs sit inside their boxes with more
                // padding than the declared size suggests. Play/pause is left
                // alone, because at 72 dp it is already larger than Spotify's
                // 64 dp and was never the complaint.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            vm.player.setShuffle(!state.shuffle)
                            vm.savePlayerPrefs()
                            haptics.toggled(!state.shuffle)
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        val tint by animateColorAsState(
                            if (state.shuffle) colors.accent else colors.textDim,
                            Motion.quick(),
                            label = "shuffle",
                        )
                        Icon(Icons.Filled.Shuffle, "Zufall", tint = tint, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(60.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.text, modifier = Modifier.size(46.dp))
                    }
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .pressable(dip = 0.94f) {
                                haptics.toggled(!state.playing)
                                vm.player.toggle()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        TransportGlyph(state.playing, tint = colors.accentInk, size = 34.dp)
                    }
                    IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(60.dp)) {
                        Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.text, modifier = Modifier.size(46.dp))
                    }
                    IconButton(
                        onClick = {
                            vm.player.cycleRepeat()
                            vm.savePlayerPrefs()
                            haptics.toggled(state.repeat == "off")
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        // Off and all wear the same glyph, so only the tint says
                        // which - which is why the tint travels rather than
                        // switching. One is a different symbol and turns over.
                        val tint by animateColorAsState(
                            if (state.repeat != "off") colors.accent else colors.textDim,
                            Motion.quick(),
                            label = "repeat",
                        )
                        AnimatedContent(
                            targetState = state.repeat == "one",
                            transitionSpec = {
                                (fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.7f))
                                    .togetherWith(fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.7f))
                            },
                            label = "repeatGlyph",
                        ) { one ->
                            Icon(
                                if (one) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                "Wiederholen",
                                tint = tint,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                // The two panels the player can swap its middle for, under the
                // transport rather than up in the header. They were the reason
                // the top of the screen carried three buttons and no weight,
                // and they belong to what is playing rather than to where it
                // came from. The offset control comes with the words, so it
                // stands next to them and only while they are up.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (track.hasLyrics) {
                        IconButton(onClick = {
                            showLyrics = !showLyrics
                            if (showLyrics) showQueue = false else showOffset = false
                        }) {
                            Icon(
                                Icons.Filled.Lyrics,
                                "Songtext",
                                tint = if (showLyrics) colors.accent else colors.textDim,
                            )
                        }
                    }
                    // Only a text that carries seconds has anything to shift,
                    // and only while it is on screen is there anything to watch
                    // the shift against - so the control is offered exactly
                    // there and nowhere else.
                    if (showLyrics && lyrics.lines.isNotEmpty()) {
                        IconButton(onClick = { showOffset = !showOffset }) {
                            Icon(
                                Icons.Filled.Tune,
                                "Versatz",
                                tint = if (showOffset) colors.accent else colors.textDim,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        showQueue = !showQueue
                        if (showQueue) {
                            showLyrics = false
                            showOffset = false
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            "Warteschlange",
                            tint = if (showQueue) colors.accent else colors.textDim,
                        )
                    }
                }
        }
    }

    if (showMenu) {
        PlayerMenu(
            track = track,
            coverUrl = vm.coverUrl(track.cover),
            onDismiss = { showMenu = false },
            onAddToPlaylist = { vm.askForPlaylist(track, allowCreate = false) },
            onEnqueue = { vm.player.enqueue(listOf(track)) },
            onGo = onGo,
        )
    }
}

/**
 * What the three dots in the header open: the song it is about, and the handful
 * of things worth doing to it that are not already a button on the screen.
 *
 * Deliberately shorter than the row menu in a list. Rating has the stars right
 * there in the player, playing is what is already happening, and downloading is
 * its own button beside the title - so what is left is the two lists a song can
 * be put into and the two pages it belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMenu(
    track: Track,
    coverUrl: String?,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEnqueue: () -> Unit,
    onGo: (String) -> Unit,
) {
    val colors = SonorusTheme.colors
    val sheet = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surface,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(coverUrl, Modifier.size(52.dp), RoundedCornerShape(8.dp), track.title)
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Artist and album on lines of their own, not joined with a
                    // dot: the sheet has the room, and the album is the half a
                    // list row usually has to drop.
                    if (track.artist.isNotEmpty()) {
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track.album.isNotEmpty()) {
                        Text(
                            track.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Zu Playlist hinzufügen") {
                onDismiss(); onAddToPlaylist()
            }
            if (!track.missing) {
                MenuItem(Icons.AutoMirrored.Filled.QueueMusic, "In die Warteschlange") {
                    onDismiss(); onEnqueue()
                }
            }
            track.albumId?.let { id ->
                MenuItem(Icons.Filled.Album, "Album anzeigen") {
                    onDismiss(); onGo(Routes.album(id))
                }
            }
            track.artistId?.let { id ->
                MenuItem(Icons.Filled.Person, "Interpret anzeigen") {
                    onDismiss(); onGo(Routes.artist(id))
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

/** The smallest step the control offers, and the range the server accepts. */
private const val OFFSET_STEP = 0.1
private const val OFFSET_MAX = 5.0

/** `+1,2 s`, `-0,4 s`, `0,0 s` - never a bare `1,2` that could be anything. */
private fun formatOffset(seconds: Double): String {
    val sign = if (seconds > 0) "+" else if (seconds < 0) "-" else ""
    return "$sign%.1f s".format(Locale.GERMANY, abs(seconds))
}

/**
 * Moving one song's text against the song.
 *
 * **An overlay over the running text, deliberately not a dialog.** It takes
 * nothing away and dims nothing: the lines keep moving behind it while the
 * number changes, and watching them against the music is the only way to find
 * the right number at all. A dialog would stop exactly the thing being judged.
 *
 * Zero is the karaoke lead-in of [LYRICS_LEAD_SECONDS] and nothing more, which
 * is why the control reads `0,0 s` where the text is already a second early.
 */
// The track is drawn by hand, and `SliderDefaults.Track` with its own drawing
// is still experimental. The same opt-in the track menu's bottom sheet needs.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricOffsetCard(
    offset: Double,
    onChange: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SonorusTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RackLabelText("Versatz", Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "Schließen", tint = colors.textDim, modifier = Modifier.size(16.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(offset - OFFSET_STEP) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Remove, "Eine Zehntelsekunde früher", tint = colors.text)
            }
            // Monospace, so the number does not jump sideways as it is dragged
            // through a digit - the eye is on the text, not on this.
            Text(
                formatOffset(offset),
                style = num(19.sp, FontWeight.SemiBold),
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(offset + OFFSET_STEP) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, "Eine Zehntelsekunde später", tint = colors.text)
            }
        }
        // One notch per tenth over the whole range, so the slider lands on the
        // same values the two buttons produce and never between them.
        val rail = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.line,
        )
        Slider(
            value = offset.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = -OFFSET_MAX.toFloat()..OFFSET_MAX.toFloat(),
            steps = (OFFSET_MAX * 2 / OFFSET_STEP).roundToInt() - 1,
            colors = rail,
            track = { rangeState ->
                SliderDefaults.Track(
                    sliderState = rangeState,
                    colors = rail,
                    // Neither of the two things Material draws on a stepped
                    // track belongs here: 101 notches read as a dotted rule
                    // rather than as a scale, and a dot at one end of a range
                    // that has two ends reads as a stray mark.
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
        )
        // The two ends named where they are, with the way back to nothing
        // between them.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("früher", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
            Text(
                "Zurücksetzen",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
                modifier = Modifier.clickable { onChange(0.0) },
            )
            Text("später", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
        }
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
 *
 * [onArmed] fires the once the wipe crosses that quarter, so the threshold can
 * be *felt* rather than guessed at. It is the whole reason the gesture is
 * trustworthy: without it there is no way to know a wipe went far enough except
 * by letting go and finding out.
 */
private suspend fun PointerInputScope.coverGestures(
    swipe: Animatable<Float, AnimationVector1D>,
    onArmed: () -> Unit,
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
        // Only the crossing is announced, not every frame past it - and a wipe
        // pulled back under the mark can be felt arming again.
        var armed = false

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
                    val far = abs(dx) >= skipAfter
                    if (far != armed) {
                        armed = far
                        if (far) onArmed()
                    }
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
