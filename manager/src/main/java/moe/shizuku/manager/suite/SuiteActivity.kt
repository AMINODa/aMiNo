package moe.shizuku.manager.suite

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivitySuiteBinding
import moe.shizuku.manager.suite.SuiteInstaller.SuiteApp

/**
 * aMiNo v2 SUITE — lists the suite apps bundled inside aMiNo and installs them.
 *
 * Installation is honest in both directions:
 *  - shell connected  → `pm install -r` through the ADB shell, silent, one tap;
 *  - shell not there  → the system package installer opens (the user sees the
 *    standard Android install dialog — nothing is faked).
 * The result of every attempt is shown verbatim; no success is claimed that
 * `pm install` did not report.
 */
class SuiteActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuiteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuiteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.suiteToolbar.setNavigationOnClickListener { finish() }

        binding.suiteRiniganInstall.setOnClickListener { install(SuiteInstaller.APPS[0]) }
        binding.suitePatcherInstall.setOnClickListener { install(SuiteInstaller.APPS[1]) }
        binding.suiteRiniganOpen.setOnClickListener { open(SuiteInstaller.APPS[0]) }
        binding.suitePatcherOpen.setOnClickListener { open(SuiteInstaller.APPS[1]) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val shell = SuiteInstaller.shellConnected()
        binding.suiteShellStatus.text = if (shell) {
            getString(R.string.suite_shell_ready)
        } else {
            getString(R.string.suite_shell_off)
        }
        for (app in SuiteInstaller.APPS) {
            val status = when {
                SuiteInstaller.isInstalled(this, app) ->
                    getString(R.string.suite_status_installed, SuiteInstaller.installedVersion(this, app))
                else -> getString(R.string.suite_status_missing)
            }
            val staged = SuiteInstaller.stagedApk(this, app).let { it.exists() && it.length() > 0 }
            val full = if (staged) status else getString(R.string.suite_status_bundling)
            when (app.packageName) {
                "com.rinigan.app" -> binding.suiteRiniganStatus.text = full
                else -> binding.suitePatcherStatus.text = full
            }
        }
    }

    private fun install(app: SuiteApp) {
        // Stage first — copy the bundled asset out of the APK into a shell-readable
        // location (external files dir) and a FileProvider-able location (cache).
        if (!SuiteInstaller.stage(this, app)) {
            Toast.makeText(this, getString(R.string.suite_stage_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (SuiteInstaller.shellConnected()) {
            Toast.makeText(this, getString(R.string.suite_installing_shell, app.label), Toast.LENGTH_SHORT).show()
            Thread {
                val result = SuiteInstaller.shellInstall(this, app)
                runOnUiThread {
                    result.fold(
                        onSuccess = { out ->
                            Toast.makeText(this, getString(R.string.suite_install_done, app.label), Toast.LENGTH_LONG).show()
                            refresh()
                        },
                        onFailure = { err ->
                            // Honest failure: show the real pm output, then offer the
                            // system installer so the user is never left with nothing.
                            val msg = getString(R.string.suite_install_failed, err.message ?: "?")
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(R.string.suite_install_failed_title)
                                .setMessage(msg)
                                .setPositiveButton(R.string.suite_use_installer) { _, _ ->
                                    openInstaller(app)
                                }
                                .setNegativeButton(R.string.sharingan_btn_later, null)
                                .show()
                            refresh()
                        }
                    )
                }
            }.start()
        } else {
            openInstaller(app)
        }
    }

    private fun openInstaller(app: SuiteApp) {
        val intent = SuiteInstaller.installerIntent(this, app)
        if (intent == null) {
            // Never silent: re-stage and tell the user what happened.
            if (!SuiteInstaller.stage(this, app)) {
                Toast.makeText(this, getString(R.string.suite_stage_failed), Toast.LENGTH_SHORT).show()
                return
            }
            val retry = SuiteInstaller.installerIntent(this, app)
            if (retry == null) {
                Toast.makeText(this, getString(R.string.suite_stage_failed), Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(retry)
        } else {
            startActivity(intent)
        }
    }

    private fun open(app: SuiteApp) {
        if (!SuiteInstaller.launch(this, app)) {
            Toast.makeText(this, getString(R.string.suite_status_missing), Toast.LENGTH_SHORT).show()
        }
    }
}
