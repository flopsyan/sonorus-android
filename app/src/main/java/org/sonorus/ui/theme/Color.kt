package org.sonorus.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Sonorus palette, taken one to one from `public/css/styles.css`.
 *
 * The thesis there is "the app is a piece of audio equipment": a deep ink
 * chassis, one warm amber lamp colour and hi-fi style labelling. Material3's
 * own scheme has no room for the three surface steps or the three text weights
 * this needs, so the tokens live in their own type and reach the tree through
 * [LocalSonorusColors]; the Material scheme is derived from them so that stock
 * components land in the same world.
 */
@Immutable
data class SonorusColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val lineSoft: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentHi: Color,
    val accentSoft: Color,
    /**
     * The lamp turned down, for the song that is playing *somewhere else*: it
     * is the same song seen from further away, and a second hue would say it
     * was a different thing. [accentDim] carries a title, [accentGhost] a row.
     */
    val accentDim: Color,
    val accentGhost: Color,
    val accentLine: Color,
    val accentInk: Color,
    val danger: Color,
    val dangerSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val isDark: Boolean,
)

val SonorusDarkColors = SonorusColors(
    bg = Color(0xFF100E14),
    surface = Color(0xFF1A171F),
    surface2 = Color(0xFF221D29),
    surface3 = Color(0xFF2B2434),
    line = Color(0xFF2A2431),
    lineSoft = Color(0xFF201B27),
    text = Color(0xFFF2EEF4),
    textDim = Color(0xFF9B90A6),
    textFaint = Color(0xFF6D6479),
    accent = Color(0xFFF5A524),
    accentHi = Color(0xFFFFBE57),
    accentSoft = Color(0x24F5A524),
    accentDim = Color(0x85F5A524),
    accentGhost = Color(0x0DF5A524),
    accentLine = Color(0x59F5A524),
    accentInk = Color(0xFF24170A),
    danger = Color(0xFFF0705F),
    dangerSoft = Color(0x26F0705F),
    ok = Color(0xFF63C98A),
    okSoft = Color(0x2663C98A),
    isDark = true,
)

/** Derived from the same tokens, with a darkened amber so it keeps contrast. */
val SonorusLightColors = SonorusColors(
    bg = Color(0xFFF6F3F0),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEFEAE5),
    surface3 = Color(0xFFE4DDD6),
    line = Color(0xFFE2DBD4),
    lineSoft = Color(0xFFECE6E0),
    text = Color(0xFF1C1720),
    textDim = Color(0xFF6B6270),
    textFaint = Color(0xFF8B8290),
    accent = Color(0xFFA86400),
    accentHi = Color(0xFF8A5100),
    accentSoft = Color(0x1FA86400),
    accentDim = Color(0x8CA86400),
    accentGhost = Color(0x0FA86400),
    accentLine = Color(0x4DA86400),
    accentInk = Color(0xFFFFFFFF),
    danger = Color(0xFFC0392B),
    dangerSoft = Color(0x1FC0392B),
    ok = Color(0xFF2E7D52),
    okSoft = Color(0x212E7D52),
    isDark = false,
)
