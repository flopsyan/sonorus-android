package eu.flopsyan.sonorus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.player.PlayerState
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.Routes
import eu.flopsyan.sonorus.ui.components.Cover
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.Stars
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num

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
    var scrub by remember { mutableStateOf<Float?>(null) }

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
                    IconButton(onClick = { showQueue = !showQueue }) {
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
                } else {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(Unit) {
                                // A wipe down over the artwork closes it.
                                detectVerticalDragGestures { _, drag ->
                                    if (drag > 24) onClose()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Cover(
                            vm.api.coverUrl(track.cover),
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            RoundedCornerShape(14.dp),
                            track.title,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
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
                        Stars(track.stars, size = 30) { value ->
                            vm.rate(track.id, value, track.stars)
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
                // makes the player re-request the file and stutter.
                val fraction = scrub
                    ?: if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .pointerInput(state.durationMs) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scrub?.let { f ->
                                        if (state.durationMs > 0) {
                                            vm.player.seekTo((state.durationMs * f).toLong())
                                        }
                                    }
                                    scrub = null
                                },
                            ) { change, _ ->
                                scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.surface3)
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(colors.accent)
                        )
                    }
                }
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
