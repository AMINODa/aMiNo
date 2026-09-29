package moe.shizuku.manager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ActivityAllPermissionsBinding

/**
 * r1382 — "Grant all permissions" screen.
 * Section 1: the app's own runtime permissions, requested one by one through the
 * OFFICIAL Android dialogs (each appears as a real system prompt).
 * Section 2: boost the ADB shell identity (com.android.shell) through the wireless
 * connection so content providers (call log / SMS / contacts) answer the agent.
 */
class AllPermissionsActivity : AppActivity() {

    private lateinit var binding: ActivityAllPermissionsBinding

    private data class PermRow(val labelRes: Int, val perms: List<String>)

    private val rows: List<PermRow> by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(PermRow(R.string.perm_notifications, listOf(Manifest.permission.POST_NOTIFICATIONS)))
                add(PermRow(R.string.perm_photos, listOf(Manifest.permission.READ_MEDIA_IMAGES)))
                add(PermRow(R.string.perm_videos, listOf(Manifest.permission.READ_MEDIA_VIDEO)))
                add(PermRow(R.string.perm_audio, listOf(Manifest.permission.READ_MEDIA_AUDIO)))
            } else {
                add(PermRow(R.string.perm_storage, listOf(Manifest.permission.READ_EXTERNAL_STORAGE)))
            }
            add(PermRow(R.string.perm_call_log, listOf(Manifest.permission.READ_CALL_LOG)))
            add(PermRow(R.string.perm_contacts, listOf(Manifest.permission.READ_CONTACTS)))
            add(PermRow(R.string.perm_sms, listOf(Manifest.permission.READ_SMS)))
            add(PermRow(R.string.perm_phone_state, listOf(Manifest.permission.READ_PHONE_STATE)))
        }
    }

    private val statusViews = HashMap<String, TextView>()
    private var requestQueue: List<String> = emptyList()
    private var requestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        buildRows()

        binding.grantAllBtn.setOnClickListener { startGrantAll() }
        binding.boostBtn.setOnClickListener { boostShell() }
    }

    private fun buildRows() {
        // keep child 0 (the subtitle), rebuild only the rows
        while (binding.permList.childCount > 1) {
            binding.permList.removeViewAt(binding.permList.childCount - 1)
        }
        statusViews.clear()
        val inf = layoutInflater
        for (row in rows) {
            val item = inf.inflate(R.layout.item_permission_row, binding.permList, false) as LinearLayout
            val label = item.findViewById<TextView>(R.id.permLabel)
            val status = item.findViewById<TextView>(R.id.permStatus)
            label.text = getString(row.labelRes)
            val granted = row.perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            status.text = getString(if (granted) R.string.perm_status_granted else R.string.perm_status_missing)
            status.setTextColor(if (granted) 0xFF4CAF50.toInt() else 0xFFFF8A80.toInt())
            row.perms.forEach { statusViews[it] = status }
            binding.permList.addView(item)
        }
        binding.grantAllBtn.text = if (allGranted()) getString(R.string.perm_all_done) else getString(R.string.perm_grant_all)
    }

    private fun allGranted() = rows.all { r ->
        r.perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startGrantAll() {
        val pending = rows.flatMap { it.perms }
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (pending.isEmpty()) {
            Toast.makeText(this, R.string.perm_all_done, Toast.LENGTH_SHORT).show()
            return
        }
        requestQueue = pending
        requestCode = 100
        askNext()
    }

    private fun askNext() {
        if (requestQueue.isEmpty()) {
            buildRows()
            return
        }
        val p = requestQueue.first()
        ActivityCompat.requestPermissions(this, arrayOf(p), requestCode)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        requestQueue = requestQueue.drop(1)
        requestCode++
        askNext()
    }

    private fun boostShell() {
        binding.boostBtn.isEnabled = false
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { AgentTools.grantShellPermissions(this@AllPermissionsActivity) }
            binding.boostBtn.isEnabled = true
            AlertDialog.Builder(this@AllPermissionsActivity)
                .setTitle(getString(R.string.perm_boost_done))
                .setMessage(res.output.take(1500))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
