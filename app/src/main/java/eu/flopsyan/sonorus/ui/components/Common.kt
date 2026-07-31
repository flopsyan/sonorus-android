package eu.flopsyan.sonorus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.flopsyan.sonorus.ui.theme.RackLabel
import eu.flopsyan.sonorus.ui.theme.num
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

/**
 * The small, wide-tracked, uppercase monospace label that titles every section,
 * the way a label on a hi-fi front panel does. This is the single strongest
 * carrier of the design, so it is used everywhere the web app uses `.rack-label`.
 */
@Composable
fun RackLabelText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = RackLabel,
        color = SonorusTheme.colors.textFaint,
        modifier = modifier,
    )
}

/**
 * Artwork. Falls back to a note on a tinted plate rather than to empty space,
 * so a grid keeps its rhythm when a cover is missing.
 */
@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentDescription: String? = null,
) {
    val colors = SonorusTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrEmpty()) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = contentDescription,
                tint = colors.textFaint,
                modifier = Modifier.fillMaxSize(0.4f),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun RoundCover(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) =
    Cover(url, modifier, CircleShape, contentDescription)

/**
 * The rating widget. Clicking the star a track already has clears the rating,
 * which is the behaviour from the web app and the reason [onRate] gets the
 * value clicked rather than the resulting one.
 */
@Composable
fun Stars(
    value: Int,
    modifier: Modifier = Modifier,
    size: Int = 16,
    enabled: Boolean = true,
    onRate: (Int) -> Unit = {},
) {
    val colors = SonorusTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (star in 1..5) {
            val filled = star <= value
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "$star Sterne",
                tint = if (filled) colors.accent else colors.textFaint,
                modifier = Modifier
                    .size(size.dp)
                    .then(if (enabled) Modifier.clickable { onRate(star) } else Modifier),
            )
        }
    }
}

/** A switch in a filter row - used by both the rating and the genre pickers. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.accentSoft else colors.surface2)
            .border(
                width = 1.dp,
                color = if (selected) colors.accentLine else colors.line,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.accent else colors.textDim,
        )
        if (count != null) {
            Text(
                count.toString(),
                style = num(11.sp),
                color = if (selected) colors.accent else colors.textFaint,
            )
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SonorusTheme.colors.accent)
    }
}

@Composable
fun ErrorNote(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val colors = SonorusTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = colors.danger, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) {
            SonorusButton("Erneut versuchen", onClick = onRetry)
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = SonorusTheme.colors.textDim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun SonorusButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val background = when {
        !enabled -> colors.surface2
        primary -> colors.accent
        danger -> colors.dangerSoft
        else -> colors.surface2
    }
    val foreground = when {
        !enabled -> colors.textFaint
        primary -> colors.accentInk
        danger -> colors.danger
        else -> colors.text
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (primary || danger) Modifier else Modifier.border(1.dp, colors.line, RoundedCornerShape(8.dp)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A section with a front-panel label over it, optionally with something on the
 * right of the label row.
 */
@Composable
fun Section(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RackLabelText(label)
            trailing?.invoke()
        }
        content()
    }
}

/**
 * The scan progress bar. During the walking phase the total is still unknown,
 * so it sweeps rather than claiming 0 %.
 */
@Composable
fun Progress(done: Int, total: Int, indeterminate: Boolean, modifier: Modifier = Modifier) {
    val colors = SonorusTheme.colors
    if (indeterminate || total <= 0) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = colors.accent,
            trackColor = colors.surface3,
        )
    } else {
        LinearProgressIndicator(
            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = colors.accent,
            trackColor = colors.surface3,
        )
    }
}

/** A card in a grid: square artwork, title, one quiet line under it. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Cover(
            coverUrl,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (round) CircleShape else RoundedCornerShape(8.dp),
            contentDescription = title,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else null,
            modifier = if (round) Modifier.fillMaxWidth() else Modifier,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else null,
                modifier = if (round) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp)
