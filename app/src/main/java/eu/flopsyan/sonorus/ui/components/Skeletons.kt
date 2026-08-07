package eu.flopsyan.sonorus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.ui.shimmer

/**
 * What a screen draws while its data is on the way.
 *
 * The point is not decoration. A spinner has to be *replaced* by the page, and
 * that swap is a hard cut no fade really hides; an outline in the shape of what
 * is coming is simply filled in, so the page settles instead of appearing. It
 * also stops the layout jumping, because the placeholder already takes the room
 * the real thing will need.
 *
 * Each of these mirrors one real layout, and they are deliberately a little
 * uneven in width - a column of identical bars reads as a loading graphic, a
 * ragged one reads as text that has not arrived yet.
 */

/** One bar of "text that is not here yet". */
@Composable
private fun Bar(width: Float, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(width)
            .height(height)
            .shimmer(RoundedCornerShape(4.dp))
    )
}

/** The rows of a track list: number, two lines of text, a duration. */
@Composable
fun TrackListSkeleton(rows: Int = 10, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        repeat(rows) { i ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(18.dp, 12.dp).shimmer(RoundedCornerShape(4.dp)))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Bar(TITLES[i % TITLES.size])
                    Bar(SUBTITLES[i % SUBTITLES.size], 9.dp)
                }
                Box(Modifier.size(28.dp, 10.dp).shimmer(RoundedCornerShape(4.dp)))
            }
        }
    }
}

/**
 * The cards of the artist, album and genre grids.
 *
 * The padding is the grid's own - 8 dp around it, 8 dp inside each card - so the
 * placeholder stands exactly where the card will. A skeleton in the wrong size
 * is worse than none: the page then visibly resizes as it fills in, which is the
 * jump it was there to prevent.
 */
@Composable
fun CardGridSkeleton(cards: Int = 8, round: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        repeat((cards + 1) / 2) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(2) { col ->
                    Column(
                        Modifier.weight(1f).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .shimmer(if (round) CircleShape else RoundedCornerShape(8.dp))
                        )
                        Bar(TITLES[(row * 2 + col) % TITLES.size])
                        Bar(0.4f, 9.dp)
                    }
                }
            }
        }
    }
}

/** The home page: the two buttons, then the shelves of artwork. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(2) {
                Box(Modifier.weight(1f).height(40.dp).shimmer(RoundedCornerShape(8.dp)))
            }
        }
        repeat(2) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Bar(0.3f, 10.dp, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(3) {
                        Column(
                            Modifier.width(150.dp).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .shimmer(RoundedCornerShape(8.dp))
                            )
                            Bar(0.85f)
                            Bar(0.5f, 9.dp)
                        }
                    }
                }
            }
        }
    }
}

/** The head of a detail page, over the rows of its track list. */
@Composable
fun DetailSkeleton(round: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .shimmer(if (round) CircleShape else RoundedCornerShape(10.dp))
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Bar(0.8f, 20.dp)
                Bar(0.5f, 10.dp)
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(104.dp, 38.dp).shimmer(RoundedCornerShape(8.dp)))
            Box(Modifier.size(92.dp, 38.dp).shimmer(RoundedCornerShape(8.dp)))
        }
        Spacer(Modifier.height(4.dp))
        TrackListSkeleton(rows = 7)
    }
}

/** Ragged on purpose - see the note at the top of this file. */
private val TITLES = listOf(0.72f, 0.55f, 0.84f, 0.46f, 0.66f, 0.78f, 0.5f)
private val SUBTITLES = listOf(0.4f, 0.52f, 0.33f, 0.46f, 0.28f, 0.44f, 0.36f)
