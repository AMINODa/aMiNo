package org.lsposed.lspatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Collapses every background role to true black, for OLED panels.
 *
 * Applied on top of whichever dark scheme is in use — including a dynamic one — so the accent
 * colours the user's wallpaper produced survive.
 *
 * Which palette is in force is never this app's choice: from API 31 the wallpaper decides it, and
 * below that, or whenever the user turns dynamic colour off, the seed they picked does. So nothing
 * in this app may rely on a specific hue to carry meaning.
 */
fun ColorScheme.toAmoled(): ColorScheme =
    copy(
        // [aMiNo suite] collapse the stage to the aMiNo agent background (#0B0B0E) instead of
        // pure black, and step the container ladder through the same #131318/#16161B/#2B2B33
        // family the agent UI uses — the whole suite shares one background identity.
        background = Color(0xFF0B0B0E),
        surface = Color(0xFF0B0B0E),
        surfaceDim = Color(0xFF0B0B0E),
        surfaceContainerLowest = Color(0xFF0B0B0E),
        surfaceContainerLow = Color(0xFF131318),
        surfaceContainer = Color(0xFF16161B),
        surfaceContainerHigh = Color(0xFF1A1A20),
        surfaceContainerHighest = Color(0xFF1E1E24),
    )
