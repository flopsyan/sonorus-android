package org.sonorus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Whether a page arms shuffle by itself when it is opened.
 *
 * Florian, 2026-09-22: "für manche Bereiche soll automatisch shuffle aktiviert
 * sein, wenn man es aufruft, für manche nicht". What decides is the kind of page
 * and not what is on it: a playlist, a star list and a genre are a selection
 * somebody made and the order inside them means nothing, while a record and an
 * interpret's page carry a running order somebody else already decided.
 *
 * The whole rule is this table, so it can be read and changed in one place
 * instead of being looked for at seven call sites.
 *
 * The phone only. The web app has a "Mischen" button of its own next to
 * "Abspielen" - there the choice is made in the moment and there is no switch
 * standing at a default.
 */
enum class ShuffleDefault(val armed: Boolean) {
    ALBUM(false),
    ARTIST(false),

    /** An interpret's singles are still that interpret's page. */
    SINGLES(false),

    PLAYLIST(true),

    /** Every song with these stars, across the whole library. */
    STARS(true),

    /** The same, inside one interpret. */
    ARTIST_STARS(true),

    GENRE(true),
}

/**
 * The shuffle switch a page shows, standing at what [ShuffleDefault] says.
 *
 * **Local to the page, and that is the point.** It arms what is played *from
 * here* and never touches what is already running: opening an album while a
 * shuffled playlist plays must not re-deal that playlist, and
 * [PlayerController.setShuffle] would. The player is told at the moment
 * something is played and not before - see [AppViewModel.playCollection].
 *
 * Remembered rather than saved: opening a page is meant to show the default
 * again, and only what is switched while standing on it belongs to the reader.
 * The running queue is switched in the full player or in the split-screen strip,
 * which are the two places that speak for the player rather than for a page.
 */
@Composable
fun rememberShuffle(page: ShuffleDefault): MutableState<Boolean> =
    remember(page) { mutableStateOf(page.armed) }
