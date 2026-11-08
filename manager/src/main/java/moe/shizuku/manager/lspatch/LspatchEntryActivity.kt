package moe.shizuku.manager.lspatch

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity

/**
 * aMiNo v2 — LSPatch ENTRY (drawer target). Field truth (user, verbatim):
 * «واجهتها عند الدخول اليها ليس كما في الاصلية … اريدها كما في النسخة الاصلية غير فقط اللون».
 *
 * Routing, in order of honesty:
 *  1. companion installed → launch the ORIGINAL LSPatch manager UI immediately
 *     (its own MainActivity, every screen/setting/option upstream, colours already
 *     matched to aMiNo) and finish this hop — no intermediate screen in the way;
 *  2. companion missing → try the silent shell path, then ONE system confirmation;
 *  3. companion not installable right now → the embedded-engine quick-patch screen
 *     (LspatchActivity) stays reachable — nothing of the existing build is lost.
 */
class LspatchEntryActivity : AppActivity() {

    private lateinit var statusLine: TextView
    private lateinit var noteLine: TextView
    private lateinit var installBtn: MaterialButton
    private lateinit var openBtn: MaterialButton
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lspatch_entry)

        (findViewById<View>(R.id.toolbar) as? com.google.android.material.appbar.MaterialToolbar)
            ?.setNavigationOnClickListener { finish() }

        statusLine = findViewById(R.id.statusLine)
        noteLine = findViewById(R.id.noteLine)
        installBtn = findViewById(R.id.installBtn)
        openBtn = findViewById(R.id.openBtn)

        installBtn.setOnClickListener { startInstall() }
        openBtn.setOnClickListener { openCompanion() }
        findViewById<MaterialButton>(R.id.quickPatchBtn).setOnClickListener {
            startActivity(Intent(this, LspatchActivity::class.java))
        }

        // the entry's whole job: if the original UI is already here, BE it — no extra tap
        routeOrPresent()
    }

    override fun onResume() {
        super.onResume()
        // coming back from the system installer: route straight into the original UI
        routeOrPresent()
    }

    private fun routeOrPresent() {
        if (LSPatchCompanion.installedVersion(this) != null) {
            if (!routed && LSPatchCompanion.launch(this)) {
                routed = true
                finish()
                return
            }
        }
        refresh()
    }

    private fun refresh() {
        val pi = LSPatchCompanion.installedVersion(this)
        if (pi == null) {
            statusLine.text = getString(R.string.lspatch_companion_missing)
            statusLine.setTextColor(0xFFFF5252.toInt())
            noteLine.text = getString(R.string.lspatch_companion_note)
            installBtn.text = getString(R.string.lspatch_install_companion)
            openBtn.visibility = View.GONE
        } else {
            statusLine.text = getString(R.string.rinigan_installed, pi.versionName ?: "?", pi.longVersionCode)
            statusLine.setTextColor(0xFFF2F2F5.toInt())
            val stale = pi.longVersionCode < LSPatchCompanion.EMBEDDED_VERSION_CODE
            noteLine.text = getString(R.string.lspatch_companion_note)
            installBtn.text = if (stale) getString(R.string.lspatch_install_companion) else getString(R.string.rinigan_open)
            openBtn.visibility = if (stale) View.GONE else View.VISIBLE
        }
    }

    private fun startInstall() {
        val pi = LSPatchCompanion.installedVersion(this)
        if (pi != null && pi.longVersionCode >= LSPatchCompanion.EMBEDDED_VERSION_CODE) {
            openCompanion(); return
        }
        lifecycleScope.launch {
            val shot = LSPatchCompanion.silentInstall(this@LspatchEntryActivity)
            if (shot != null) {
                if (shot.ok) {
                    Toast.makeText(this@LspatchEntryActivity, R.string.lspatch_install_silent, Toast.LENGTH_LONG).show()
                    if (LSPatchCompanion.launch(this@LspatchEntryActivity)) finish()
                } else {
                    Toast.makeText(
                        this@LspatchEntryActivity,
                        getString(R.string.lspatch_install_failed, shot.output.take(200)),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refresh()
            } else {
                LSPatchCompanion.confirmInstall(this@LspatchEntryActivity) { ok, _ ->
                    Toast.makeText(this@LspatchEntryActivity, R.string.lspatch_install_sent, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
    }

    private fun openCompanion() {
        if (!LSPatchCompanion.launch(this)) {
            Toast.makeText(this, R.string.lspatch_companion_missing, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
}
