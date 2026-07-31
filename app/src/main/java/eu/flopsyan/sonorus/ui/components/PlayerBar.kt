package eu.flopsyan.sonorus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

/**
 * The transport at the bottom edge.
 *
 * The seek bar **is** the top edge of the bar, exactly as in the web app -
 * a signature element there, and the reason the progress line sits flush at the
 * top rather than inside the padding.
 */
@Composable
fun PlayerBar(
    track: Track,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val colors = SonorusTheme.colors
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // The rail is the top edge and can be grabbed anywhere along it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(colors.surface3)
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures { change, _ ->
                        onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(colors.accent)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(coverUrl, Modifier.size(44.dp), RoundedCornerShape(6.dp), track.title)
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, "Zurück", tint = colors.textDim)
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pause" else "Abspielen",
                    tint = colors.accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, "Weiter", tint = colors.textDim)
            }
        }
    }
}
