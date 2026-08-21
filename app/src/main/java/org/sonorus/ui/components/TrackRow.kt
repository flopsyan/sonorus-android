package org.sonorus.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonorus.data.model.Track
import org.sonorus.ui.Fmt
import org.sonorus.ui.Motion
import org.sonorus.ui.pressable
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num

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
    /**
     * The song is the one playing, but the queue was started somewhere else -
     * from this album's page while you are looking at "Alle Songs", say.
     *
     * A song sits in an album, on its interpret's page, in three playlists and
     * in the search at once, and marking every one of them the same says less
     * than marking none. The list it is really playing from keeps the full
     * lamp; the others get the same lamp turned down - "yes, that one, but it
     * is running somewhere else".
     */
    elsewhere: Boolean = false,
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
    // The row that is playing lights up rather than switching on: with the queue
    // stepping from song to song by itself, the marker travelling down the list
    // is what says *which* song without having to be read.
    val plate by animateColorAsState(
        when {
            isCurrent && elsewhere -> colors.accentGhost
            isCurrent -> colors.accentSoft
            else -> Color.Transparent
        },
        Motion.standard(),
        label = "current",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(plate)
            .pressable(
                dip = 0.985f,
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
                // The number gives way to the speaker rather than being replaced
                // by it, so the change reads as the same row changing state.
                AnimatedContent(
                    targetState = isCurrent,
                    transitionSpec = {
                        (fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.6f))
                            .togetherWith(fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.6f))
                    },
                    label = "nowPlaying",
                ) { playing ->
                    if (playing) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = if (elsewhere) "Läuft woanders" else "Läuft gerade",
                            tint = if (elsewhere) colors.accentDim else colors.accent,
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
            }
        } else {
            Cover(coverUrl, Modifier.size(40.dp), contentDescription = track.title)
        }

        Column(Modifier.weight(1f)) {
            val ink by animateColorAsState(
                when {
                    dim -> colors.textFaint
                    isCurrent && elsewhere -> colors.accentDim
                    isCurrent -> colors.accent
                    else -> colors.text
                },
                Motion.standard(),
                label = "title",
            )
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
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
