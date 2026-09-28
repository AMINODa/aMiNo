package moe.shizuku.manager.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityPermissionsBinding
import moe.shizuku.manager.home.AccessibilityViewHolder
import moe.shizuku.manager.utils.SettingsPage

/**
 * aMiNo permissions status screen.
 *
 * Shows the REAL state of every permission the app can use, read live from
 * Android on every resume. It never claims a permission the system did not
 * grant, and clearly separates:
 *  - runtime permissions (can be requested from the official dialog)
 *  - special accesses (granted only from official system pages)
 *  - permissions Android blocks for all normal apps (honest disclosure)
 *
 * Root is never required.
 */
class PermissionsActivity : AppBarActivity() {

    private enum class State { GRANTED, NOT_GRANTED, ACTIVE, OFF, EXEMPTED, BLOCKED }

    private enum class Action { NONE, REQUEST, APP_SETTINGS, ACCESSIBILITY, BATTERY, ALL_FILES, NOTIFICATIONS, INSTALL }

    private data class Row(
        val titleRes: Int,
        val descRes: Int,
        val state: State,
        val stateRes: Int,
        val action: Action,
        val permissions: List<String> = emptyList()
    )

    private lateinit var binding: ActivityPermissionsBinding

    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.permissions_title)

        binding = ActivityPermissionsBinding.inflate(layoutInflater, rootView, true)

        binding.requestAll.setOnClickListener { requestGrantable() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- state

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun runtimeRows(): List<Row> {
        val rows = mutableListOf<Row>()

        // notifications
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val notifGranted =
            if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else notifEnabled
        rows += Row(
            R.string.perm_notifications_title, R.string.perm_notifications_desc,
            if (notifEnabled) State.GRANTED else State.NOT_GRANTED,
            if (notifEnabled) R.string.perm_state_granted else R.string.perm_state_not_granted,
            Action.NOTIFICATIONS,
            if (Build.VERSION.SDK_INT >= 33 && !notifGranted) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        )

        // camera
        rows += cameraRow()

        // microphone
        rows += micRow()

        // media
        rows += mediaRow()

        // nearby devices (33+ runtime)
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = granted(Manifest.permission.NEARBY_WIFI_DEVICES)
            rows += Row(
                R.string.perm_nearby_title, R.string.perm_nearby_desc,
                if (ok) State.GRANTED else State.NOT_GRANTED,
                if (ok) R.string.perm_state_granted else R.string.perm_state_not_granted,
                Action.REQUEST,
                if (!ok) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()
            )
        } else {
            rows += Row(
                R.string.perm_nearby_title, R.string.perm_nearby_desc,
                State.EXEMPTED, R.string.perm_state_exempted, Action.NONE
            )
        }

        return rows
    }

    private fun cameraRow(): Row {
        val ok = granted(Manifest.permission.CAMERA)
        return Row(
            R.string.perm_camera_title, R.string.perm_camera_desc,
            if (ok) State.GRANTED else State.NOT_GRANTED,
            if (ok) R.string.perm_state_granted else R.string.perm_state_not_granted,
            Action.REQUEST,
            if (!ok) listOf(Manifest.permission.CAMERA) else emptyList()
        )
    }

    private fun micRow(): Row {
        val ok = granted(Manifest.permission.RECORD_AUDIO)
        return Row(
            R.string.perm_microphone_title, R.string.perm_microphone_desc,
            if (ok) State.GRANTED else State.NOT_GRANTED,
            if (ok) R.string.perm_state_granted else R.string.perm_state_not_granted,
            Action.REQUEST,
            if (!ok) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        )
    }

    private fun mediaRow(): Row {
        return if (Build.VERSION.SDK_INT >= 33) {
            val perms = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            val ok = perms.all { granted(it) }
            Row(
                R.string.perm_media_title, R.string.perm_media_desc,
                if (ok) State.GRANTED else State.NOT_GRANTED,
                if (ok) R.string.perm_state_granted else R.string.perm_state_not_granted,
                Action.REQUEST,
                if (!ok) perms else emptyList()
            )
        } else {
            val ok = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            Row(
                R.string.perm_media_title, R.string.perm_media_desc,
                if (ok) State.GRANTED else State.NOT_GRANTED,
                if (ok) R.string.perm_state_granted else R.string.perm_state_not_granted,
                Action.REQUEST,
                if (!ok) listOf(Manifest.permission.READ_EXTERNAL_STORAGE) else emptyList()
            )
        }
    }

    private fun systemRows(): List<Row> {
        val rows = mutableListOf<Row>()

        // accessibility - real state, straight from the system
        val acc = AccessibilityViewHolder.isServiceEnabled(this)
        rows += Row(
            R.string.perm_accessibility_title, R.string.perm_accessibility_desc,
            if (acc) State.ACTIVE else State.OFF,
            if (acc) R.string.perm_state_active else R.string.perm_state_off,
            Action.ACCESSIBILITY
        )

        // battery optimization
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        rows += Row(
            R.string.perm_battery_title, R.string.perm_battery_desc,
            if (exempt) State.EXEMPTED else State.OFF,
            if (exempt) R.string.perm_state_exempted else R.string.perm_state_off,
            Action.BATTERY
        )

        // all files access (special)
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        rows += Row(
            R.string.perm_all_files_title, R.string.perm_all_files_desc,
            if (allFiles) State.GRANTED else State.OFF,
            if (allFiles) R.string.perm_state_granted else R.string.perm_state_off,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Action.ALL_FILES else Action.APP_SETTINGS
        )

        // install packages (special)
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            packageManager.canRequestPackageInstalls() else true
        rows += Row(
            R.string.perm_install_title, R.string.perm_install_desc,
            if (canInstall) State.GRANTED else State.OFF,
            if (canInstall) R.string.perm_state_granted else R.string.perm_state_off,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Action.INSTALL else Action.NONE
        )

        return rows
    }

    private fun blockedRows(): List<Row> = listOf(
        Row(
            R.string.perm_blocked_secure_title, R.string.perm_blocked_secure_desc,
            State.BLOCKED, R.string.perm_state_blocked, Action.NONE
        ),
        Row(
            R.string.perm_blocked_root_title, R.string.perm_blocked_root_desc,
            State.BLOCKED, R.string.perm_state_blocked, Action.NONE
        )
    )

    // ----------------------------------------------------------------- ui

    private fun refresh() {
        binding.list.removeAllViews()

        runtimeRows().forEach { addRow(it) }

        addSectionTitle()
        systemRows().forEach { addRow(it) }

        addSectionTitle()
        blockedRows().forEach { addRow(it) }
    }

    private fun addSectionTitle() {
        val tv = TextView(this)
        tv.text = getString(R.string.perm_section_blocked)
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        tv.setPadding(0, dp(18), 0, dp(4))
        binding.list.addView(tv)
    }

    private fun addRow(row: Row) {
        val view = layoutInflater.inflate(R.layout.view_permission_row, binding.list, false)
        val title = view.findViewById<TextView>(R.id.row_title)
        val desc = view.findViewById<TextView>(R.id.row_desc)
        val state = view.findViewById<TextView>(R.id.row_state)
        val action = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.row_action)

        title.setText(row.titleRes)
        desc.setText(row.descRes)
        state.setText(row.stateRes)
        when (row.state) {
            State.GRANTED, State.ACTIVE, State.EXEMPTED -> state.setTextColor(0xFF69F0AE.toInt())
            State.NOT_GRANTED, State.OFF -> state.setTextColor(0xFF9E9E9E.toInt())
            State.BLOCKED -> state.setTextColor(0xFFFF8A80.toInt())
        }

        if (row.action == Action.NONE) {
            action.visibility = View.GONE
        } else {
            action.visibility = View.VISIBLE
            action.setText(
                when (row.action) {
                    Action.REQUEST -> if (row.permissions.isEmpty()) R.string.perm_action_open_app_settings else R.string.perm_action_grant
                    Action.APP_SETTINGS -> R.string.perm_action_open_app_settings
                    Action.ACCESSIBILITY -> R.string.perm_action_open_accessibility
                    Action.BATTERY -> R.string.perm_action_open_battery
                    Action.ALL_FILES -> R.string.perm_action_open_all_files
                    Action.NOTIFICATIONS -> R.string.perm_action_open_notifications
                    Action.INSTALL -> R.string.perm_action_open_install
                    Action.NONE -> R.string.perm_action_open_app_settings
                }
            )
            action.setOnClickListener { onAction(row) }
        }

        binding.list.addView(view)
        addDivider()
    }

    private fun addDivider() {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(2)
        }
        v.setBackgroundColor(0x22FFFFFF)
        binding.list.addView(v)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- actions

    private fun onAction(row: Row) {
        when (row.action) {
            Action.REQUEST -> {
                if (row.permissions.isNotEmpty()) {
                    requestLauncher.launch(row.permissions.toTypedArray())
                } else {
                    openAppDetails()
                }
            }
            Action.APP_SETTINGS -> openAppDetails()
            Action.ALL_FILES -> openAllFiles()
            Action.ACCESSIBILITY -> SettingsPage.Accessibility.launch(this)
            Action.BATTERY -> requestIgnoreBattery()
            Action.NOTIFICATIONS -> {
                if (row.permissions.isNotEmpty()) {
                    requestLauncher.launch(row.permissions.toTypedArray())
                } else {
                    SettingsPage.Notifications.NotificationSettings.launch(this)
                }
            }
            Action.INSTALL -> openInstall()
            Action.NONE -> Unit
        }
    }

    private fun requestGrantable() {
        val perms = linkedSetOf<String>()
        runtimeRows().forEach { r -> if (r.action == Action.REQUEST) perms.addAll(r.permissions) }
        if (perms.isEmpty()) {
            openAppDetails()
            return
        }
        requestLauncher.launch(perms.toTypedArray())
    }

    private fun openAllFiles() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openInstall() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun requestIgnoreBattery() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
