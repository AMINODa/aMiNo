package com.rinigan.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── aMiNo suite identity (v2) ────────────────────────────────────────────────
// One scheme, always dark, never dynamic: the whole background stage is the
// aMiNo agent palette (#0B0B0E) with the aMiNo red accent family. This is a
// deliberate retire of the old Material-You dynamic color — the suite must
// look like aMiNo on every device, in every system mode.

private val AminoColorScheme = darkColorScheme(
    primary = AminoRed,
    onPrimary = Color.White,
    primaryContainer = AminoRedContainer,
    onPrimaryContainer = AminoOnRedContainer,
    inversePrimary = AminoRedDeep,
    secondary = AminoRedSoft,
    onSecondary = Color(0xFF3B0A08),
    secondaryContainer = AminoRedContainer,
    onSecondaryContainer = AminoOnRedContainer,
    tertiary = AminoRedDeep,
    onTertiary = Color.White,
    tertiaryContainer = AminoRedContainer,
    onTertiaryContainer = AminoOnRedContainer,
    error = AminoRed,
    onError = Color.White,
    errorContainer = AminoRedContainer,
    onErrorContainer = AminoOnRedContainer,
    background = AminoBackground,
    onBackground = AminoTextPrimary,
    surface = AminoBackground,
    onSurface = AminoTextPrimary,
    surfaceVariant = AminoSurfaceLow,
    onSurfaceVariant = AminoTextSecondary,
    outline = AminoOutline,
    outlineVariant = AminoOutlineVariant,
    surfaceBright = AminoSurfaceHighest,
    surfaceDim = AminoBackground,
    surfaceContainerLowest = AminoBackground,
    surfaceContainerLow = AminoSurfaceLow,
    surfaceContainer = AminoSurface,
    surfaceContainerHigh = AminoSurfaceHigh,
    surfaceContainerHighest = AminoSurfaceHighest,
    inverseSurface = AminoTextPrimary,
    inverseOnSurface = AminoBackground,
    scrim = Color.Black
)

@Composable
fun RiniganTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AminoBackground.toArgb()
            window.navigationBarColor = AminoBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = AminoColorScheme,
        typography = Typography,
        content = content
    )
}
