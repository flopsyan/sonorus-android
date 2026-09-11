package org.sonorus.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import org.sonorus.player.SKIP_MS
import org.sonorus.ui.theme.SonorusTheme

/**
 * A fifteen-second skip, with fifteen written on it.
 *
 * Material ships Replay5, Replay10 and Replay30 and nothing between, so the
 * ready-made glyph would have said 10 while the button jumped 15 - a control
 * that lies about what it does. The plain circular arrow carries the number
 * instead, and the forward one is the same arrow mirrored.
 *
 * The sizes are passed in rather than derived: the four surfaces that draw this
 * were each measured against the transport they sit in, and a formula over the
 * glyph would have made the number unreadable in the minimised bar long before
 * it made it too large in the full player.
 */
@Composable
fun SkipButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    button: Dp = 60.dp,
    glyph: Dp = 42.dp,
    number: TextUnit = 10.sp,
    tint: Color = SonorusTheme.colors.text,
) {
    val seconds = SKIP_MS / 1000
    IconButton(onClick = onClick, modifier = modifier.size(button)) {
        Box(Modifier.size(glyph), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Replay,
                if (forward) "$seconds Sekunden vor" else "$seconds Sekunden zurück",
                tint = tint,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (forward) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier),
            )
            Text(
                "$seconds",
                style = MaterialTheme.typography.labelSmall,
                fontSize = number,
                color = tint,
                // The glyph's own opening sits a hair below centre, and by how
                // much scales with it.
                modifier = Modifier.padding(top = glyph * 0.12f),
            )
        }
    }
}
