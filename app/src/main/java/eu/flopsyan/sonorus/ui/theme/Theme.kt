package eu.flopsyan.sonorus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** What the account picked. Dark is the default, *not* the system setting. */
enum class ThemeMode { DARK, LIGHT, AUTO }

val LocalSonorusColors = staticCompositionLocalOf { SonorusDarkColors }

/** `SonorusTheme.colors.accent` reads better than a raw composition local. */
object SonorusTheme {
    val colors: SonorusColors
        @Composable get() = LocalSonorusColors.current
}

/** --radius / --radius-sm / --radius-xs from the stylesheet. */
val SonorusShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun SonorusTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val colors = if (dark) SonorusDarkColors else SonorusLightColors

    // Material components should land in the same world as the hand-built ones,
    // so the stock scheme is derived from the same tokens rather than picked.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.accent,
            secondary = colors.accent,
            onSecondary = colors.accentInk,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textDim,
            surfaceContainer = colors.surface2,
            surfaceContainerHigh = colors.surface3,
            surfaceContainerHighest = colors.surface3,
            outline = colors.line,
            outlineVariant = colors.lineSoft,
            error = colors.danger,
            onError = colors.text,
            errorContainer = colors.dangerSoft,
            onErrorContainer = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.accent,
            secondary = colors.accent,
            onSecondary = colors.accentInk,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textDim,
            surfaceContainer = colors.surface2,
            surfaceContainerHigh = colors.surface3,
            surfaceContainerHighest = colors.surface3,
            outline = colors.line,
            outlineVariant = colors.lineSoft,
            error = colors.danger,
            onError = colors.surface,
            errorContainer = colors.dangerSoft,
            onErrorContainer = colors.danger,
        )
    }

    CompositionLocalProvider(LocalSonorusColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SonorusTypography,
            shapes = SonorusShapes,
            content = content,
        )
    }
}
