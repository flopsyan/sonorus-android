package eu.flopsyan.sonorus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.Motion
import eu.flopsyan.sonorus.ui.confirmed
import eu.flopsyan.sonorus.ui.pressable
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
 * How long a cover takes to appear once it is decoded.
 *
 * Coil skips the fade for anything it already had in memory, so this only ever
 * costs the picture that really did just arrive - which is exactly the one that
 * used to pop into a grid mid-scroll.
 */
private const val COVER_FADE = 220

/** The artwork request, with the fade attached. Built here so both artwork
 *  composables ask for the picture the same way. */
@Composable
private fun coverRequest(url: String): ImageRequest =
    ImageRequest.Builder(LocalContext.current).data(url).crossfade(COVER_FADE).build()

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
                model = coverRequest(url),
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
 * The artwork of a collection that has none of its own - a playlist, a star
 * playlist, a genre: the covers of the first four albums in it as a 2x2 mosaic.
 * Below four it is the first cover alone, the way a genre card has always
 * looked, and without any it falls back to [Cover]'s plate.
 *
 * Build the list with [albumCovers]: it counts albums and not songs, because
 * four tracks off one record would otherwise fill all four tiles with the same
 * picture and say nothing about what is in the list.
 */
@Composable
fun CoverMosaic(
    urls: List<String>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentDescription: String? = null,
) {
    if (urls.size < 4) {
        Cover(urls.firstOrNull(), modifier, shape, contentDescription)
        return
    }
    Column(
        modifier
            .clip(shape)
            .background(SonorusTheme.colors.surface2)
    ) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    AsyncImage(
                        model = coverRequest(urls[row * 2 + col]),
                        // One description for the whole tile: four of them read
                        // out as four pictures, and this is one piece of artwork.
                        contentDescription = if (row == 0 && col == 0) contentDescription else null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * The cover of each of the first [limit] albums in a track list, in the order
 * the list has them. A track without artwork is skipped, a second track off an
 * album already taken is skipped, and a single - which belongs to no album -
 * stands for itself.
 */
fun albumCovers(tracks: List<Track>, limit: Int = 4): List<String> {
    val seen = mutableSetOf<String>()
    val covers = mutableListOf<String>()
    for (track in tracks) {
        val cover = track.cover
        if (cover.isNullOrEmpty()) continue
        if (!seen.add(track.albumId?.let { "album-$it" } ?: "track-${track.id}")) continue
        covers += cover
        if (covers.size == limit) break
    }
    return covers
}

/**
 * The rating widget. Clicking the star a track already has clears the rating,
 * which is the behaviour from the web app and the reason [onRate] gets the
 * value clicked rather than the resulting one.
 *
 * A star that fills springs up to size rather than simply turning amber. Rating
 * is the one thing in the app done over and over in a row - a whole evening of
 * it, going through the unrated - so it is worth the moment of feedback, and it
 * is the only place the app confirms with a buzz.
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
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (star in 1..5) {
            val filled = star <= value
            val tint by animateColorAsState(
                if (filled) colors.accent else colors.textFaint,
                Motion.quick(),
                label = "star",
            )
            val scale by animateFloatAsState(
                targetValue = if (filled) 1f else 0.86f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "starPop",
            )
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "$star Sterne",
                tint = tint,
                modifier = Modifier
                    .size(size.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(
                        if (enabled) {
                            Modifier.clickable {
                                haptics.confirmed()
                                onRate(star)
                            }
                        } else Modifier
                    ),
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
    // A switch that is thrown should be *seen* being thrown - all four of its
    // colours travel rather than jumping.
    val fill by animateColorAsState(
        if (selected) colors.accentSoft else colors.surface2, Motion.quick(), label = "chipFill",
    )
    val edge by animateColorAsState(
        if (selected) colors.accentLine else colors.line, Motion.quick(), label = "chipEdge",
    )
    val ink by animateColorAsState(
        if (selected) colors.accent else colors.textDim, Motion.quick(), label = "chipInk",
    )
    val quiet by animateColorAsState(
        if (selected) colors.accent else colors.textFaint, Motion.quick(), label = "chipCount",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .border(width = 1.dp, color = edge, shape = RoundedCornerShape(999.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
        if (count != null) {
            Text(count.toString(), style = num(11.sp), color = quiet)
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

/**
 * What a page that lives entirely on the server says while there is none.
 * Statistics, notices and accounts have nothing downloadable in them - there is
 * no shorter version of them to show, only an honest sentence.
 */
@Composable
fun ServerOnlyNote(what: String, modifier: Modifier = Modifier) =
    EmptyNote("$what gibt es nur mit Verbindung zum Server.", modifier)

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
            .pressable(enabled = enabled, onClick = onClick)
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

/**
 * A card in a grid: square artwork, title, one quiet line under it.
 *
 * [coverUrls] is the collection case - a card for something that has no artwork
 * of its own carries the covers of what is in it, the same mosaic as the page it
 * leads to. Everything with one cover of its own passes [coverUrl] instead.
 */
@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    coverUrl: String? = null,
    modifier: Modifier = Modifier,
    coverUrls: List<String> = emptyList(),
    round: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            // A card is the biggest thing in the app that is a single tap, so it
            // is where the dip under the finger is worth the most.
            .pressable(dip = 0.95f, onClick = onClick)
            .padding(8.dp),
    ) {
        CoverMosaic(
            coverUrls.ifEmpty { listOfNotNull(coverUrl) },
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
