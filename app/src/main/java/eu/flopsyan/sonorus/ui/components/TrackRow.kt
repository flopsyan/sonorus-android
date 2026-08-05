package eu.flopsyan.sonorus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.flopsyan.sonorus.data.model.Track
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num

/**
 * One row of a track list.
 *
 * Two rules from the web app carry over literally:
 *
 *  - **The row is the play button.** On a touch screen there is no room for a
 *    30 px number column that only reveals a play symbol on hover, so a tap
 *    anywhere on the row starts the track.
 *  - **A missing file is shown, not hidden.** Its rating and playlist entries
 *    outlive it, so it appears greyed out and struck through with a `fehlt`
 *    badge, and it is never playable or queued. The path is the one thing left
 *    worth showing, so it takes the place of the artist line.
 *
 * Holding the row opens its menu, which is where every other action lives.
 */
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    /** The running number, or null to show artwork instead. */
    index: Int? = null,
    isCurrent: Boolean = false,
    showAlbum: Boolean = false,
    showYear: Boolean = false,
    /** The rating to draw. Passed in, because a row given a star has to redraw
     *  without its whole list being fetched again. */
    stars: Int = track.stars,
    /** Drawn as the small arrow that says this song plays without a network. */
    downloaded: Boolean = false,
    coverUrl: String? = null,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val dim = track.missing
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (isCurrent) Modifier.background(colors.accentSoft) else Modifier)
            .combinedClickable(
                enabled = true,
                onClick = { if (!dim) onPlay() },
                onLongClick = onMenu,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The number column, or the artwork where a list has no numbering.
        if (index != null) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = "Läuft gerade",
                        tint = colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Text(
                        index.toString(),
                        style = num(12.sp),
                        color = if (dim) colors.textFaint else colors.textDim,
                    )
                }
            }
        } else {
            Cover(coverUrl, Modifier.size(40.dp), contentDescription = track.title)
        }

        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    dim -> colors.textFaint
                    isCurrent -> colors.accent
                    else -> colors.text
                },
                textDecoration = if (dim) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = when {
                dim -> track.path.orEmpty()
                showAlbum && track.album.isNotEmpty() -> "${track.artist} · ${track.album}"
                else -> track.artist
            }
            if (second.isNotEmpty()) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (dim) {
            Text(
                "fehlt",
                style = num(11.sp),
                color = colors.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.dangerSoft)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else if (stars > 0) {
            Stars(stars, size = 12, enabled = false)
        }

        // The one marker a downloaded row wears, in the amber the rest of the
        // chassis uses. Small on purpose: it is a fact about the row, not an
        // action - the menu is where a download is started or taken back.
        if (downloaded) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Heruntergeladen",
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
        }

        if (showYear) {
            Text(
                Fmt.year(track.releaseDate, track.year),
                style = num(12.sp),
                color = colors.textDim,
            )
        }

        Text(
            Fmt.duration(track.duration),
            style = num(12.sp),
            color = if (dim) colors.textFaint else colors.textDim,
        )

        IconButton(onClick = onMenu, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Mehr",
                tint = colors.textDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
