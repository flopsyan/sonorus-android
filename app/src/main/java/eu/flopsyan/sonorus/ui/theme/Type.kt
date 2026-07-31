package eu.flopsyan.sonorus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography carries the hi-fi thesis, so two rules matter more than the sizes:
 *
 *  - [RackLabel] titles every section the way a label on a front panel does:
 *    small, wide-tracked, uppercase monospace.
 *  - *All* numbers - durations, track numbers, counts, the transport counter -
 *    are monospace, so a running counter does not jitter as its digits change.
 *    That is what [Num] is for.
 *
 * No font files are shipped (the web app ships none either); the system stacks
 * are what both platforms have in common.
 */
val SonorusTypography = Typography(
    displaySmall = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** The front-panel label over a section. */
val RackLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.14.em,
)

/** Every number in the app. Monospace so digits keep their column. */
fun num(size: TextUnit = 13.sp, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size,
    fontWeight = weight,
)

/** A number that sits in a column and is read against its neighbours. */
fun numRight(size: TextUnit = 13.sp) = num(size).copy(textAlign = TextAlign.End)
