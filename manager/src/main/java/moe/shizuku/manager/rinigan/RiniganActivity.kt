package moe.shizuku.manager.rinigan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.lspatch.LspatchEntryActivity
import moe.shizuku.manager.lspatch.RiniganInstaller

/**
 * aMiNo v2 — Rinigan screen. The virtual-camera MODULE app ships embedded inside aMiNo
 * (aMiNo-themed rebuild of v6.3, signed with its original keystore). This screen installs it
 * (silent when the aMiNo shell service is up, else one system confirmation), opens its full
 * UI, and hands the user to LSPatch for embedding it into target apps.
 */
class RiniganActivity : AppActivity() {

    private lateinit var statusLine: TextView
    private lateinit var noteLine: TextView
    private lateinit var installBtn: MaterialButton
    private lateinit var openBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rinigan)

        (findViewById<View>(R.id.toolbar) as? com.google.android.material.appbar.MaterialToolbar)
            ?.setNavigationOnClickListener { finish() }

        statusLine = findViewById(R.id.statusLine)
        noteLine = findViewById(R.id.noteLine)
        installBtn = findViewById(R.id.installBtn)
        openBtn = findViewById(R.id.openBtn)

        installBtn.setOnClickListener { startInstall() }
        openBtn.setOnClickListener { openRinigan() }
        findViewById<MaterialButton>(R.id.toLspatchBtn).setOnClickListener {
            // r1426 — same routing as the drawer: into the ORIGINAL manager UI when present
            startActivity(Intent(this, moe.shizuku.manager.lspatch.LspatchEntryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val pi = RiniganInstaller.installedVersion(this)
        if (pi == null) {
            statusLine.text = getString(R.string.rinigan_not_installed)
            statusLine.setTextColor(0xFFFF5252.toInt())
            noteLine.text = getString(R.string.rinigan_embedded_note)
            installBtn.text = getString(R.string.rinigan_install)
            openBtn.visibility = View.GONE
        } else {
            statusLine.text = getString(R.string.rinigan_installed, pi.versionName, pi.longVersionCode)
            statusLine.setTextColor(0xFFF2F2F5.toInt())
            val stale = pi.longVersionCode < RiniganInstaller.EMBEDDED_VERSION_CODE
            noteLine.text = if (stale) getString(R.string.rinigan_update_available)
            else getString(R.string.rinigan_embedded_note)
            installBtn.text = if (stale) getString(R.string.rinigan_install) else getString(R.string.rinigan_open)
            openBtn.visibility = if (stale) View.GONE else View.VISIBLE
        }
    }

    private fun startInstall() {
        val pi = RiniganInstaller.installedVersion(this)
        if (pi != null && pi.longVersionCode >= RiniganInstaller.EMBEDDED_VERSION_CODE) {
            openRinigan(); return
        }
        lifecycleScope.launch {
            val shot = RiniganInstaller.silentInstall(this@RiniganActivity)
            if (shot != null) {
                if (shot.ok) {
                    Toast.makeText(this@RiniganActivity, R.string.rinigan_install_silent, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this@RiniganActivity,
                        getString(R.string.lspatch_install_failed, shot.output.take(200)),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refresh()
            } else {
                RiniganInstaller.confirmInstall(this@RiniganActivity) { ok, _ ->
                    Toast.makeText(this@RiniganActivity, R.string.rinigan_install_sent, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
    }

    private fun openRinigan() {
        val launch = packageManager.getLaunchIntentForPackage(RiniganInstaller.RINIGAN_PACKAGE) ?: run {
            Toast.makeText(this, R.string.rinigan_not_installed, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launch)
    }
}
