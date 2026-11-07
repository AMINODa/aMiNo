package com.rinigan.app.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.rinigan.app.R

sealed class Screen(val route: String, @StringRes val titleResId: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.nav_home, Icons.Default.Home)
    object Manage : Screen("manage", R.string.nav_manage, Icons.Default.VideoLibrary)
    object PerApp : Screen("perapp", R.string.nav_perapp, Icons.Default.AutoFixHigh)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}
