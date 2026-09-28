package moe.shizuku.manager.home

import android.content.Context
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.utils.SettingsPage

fun Context.showAccessibilityDialog() {
    // aMiNo r1372: the old path silently ENABLED the accessibility service
    // programmatically (Settings.Secure.putString) whenever WRITE_SECURE_SETTINGS
    // happened to be held - this bypassed the Android protection the user must
    // control. Now: every route leads to the OFFICIAL system page, and the real
    // state is always re-read from the system afterwards (AccessibilityViewHolder).
    if (isAccessibilityEnabled()) {
        showNavigateDialog()
    } else {
        showEnableDialog()
    }
}

private fun Context.showEnableDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(R.string.dialog_adb_pairing_accessibility_enable)
        .setPositiveButton(R.string.enable) { _, _ ->
            SettingsPage.Accessibility.launch(this)
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.showNavigateDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(R.string.dialog_adb_pairing_accessibility_navigate)
        .setPositiveButton(R.string.development_settings) { _, _ ->
            SettingsPage.Developer.HighlightWirelessDebugging.launch(this)
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.getEnabledAccessibilityServices(): List<String>? {
    val enabledServices =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    return enabledServices?.split(":")
}

private fun Context.isAccessibilityEnabled(): Boolean {
    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    return getEnabledAccessibilityServices()?.any { it.equals(accessibilityServiceName) } ?: false
}
