package moe.shizuku.manager.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.terminal.TermBackend
import moe.shizuku.manager.terminal.TerminalEngine
import moe.shizuku.manager.terminal.linux.LinuxAcceptance
import moe.shizuku.manager.terminal.linux.LinuxEnvManager
import moe.shizuku.manager.terminal.linux.ShizukuExec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * aMiNo r1385 — Linux environment manager screen.
 *
 * Everything shown here is REAL: the pre-flight checks query the actual device,
 * the install progress reports actual stages, and the state flips to READY only
 * after the verification probe (id + /etc/os-release through PRoot) succeeded.
 * The screen also makes the honesty contract visible to the user: PRoot gives a
 * FAKEROOT inside the container, never real system privileges.
 */
class LinuxEnvActivity : AppActivity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusDetails: TextView
    private lateinit var preflightBox: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var btnInstall: TextView
    private lateinit var btnUpdate: TextView
    private lateinit var btnReset: TextView
    private lateinit var btnRemove: TextView
    private lateinit var btnTests: TextView
    private lateinit var btnTerminal: TextView

    private val logBuf = StringBuilder()
    private var working = false

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_linux_env)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        statusTitle = findViewById(R.id.statusTitle)
        statusDetails = findViewById(R.id.statusDetails)
        preflightBox = findViewById(R.id.preflightBox)
        progress = findViewById(R.id.progress)
        logView = findViewById(R.id.logView)
        btnInstall = findViewById(R.id.btnInstall)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnReset = findViewById(R.id.btnReset)
        btnRemove = findViewById(R.id.btnRemove)
        btnTests = findViewById(R.id.btnTests)
        btnTerminal = findViewById(R.id.btnTerminal)

        log("صفحة بيئة Linux — كل ما يُعرض هنا نتائج فعلية، لا افتراضات")

        btnInstall.setOnClickListener { askInstall() }
        btnUpdate.setOnClickListener { askUpdate() }
        btnReset.setOnClickListener { askReset() }
        btnRemove.setOnClickListener { askRemove() }
        btnTests.setOnClickListener { runTests() }
        btnTerminal.setOnClickListener { openTerminal() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(600) }
            }
        }
    }

    private fun log(line: String) {
        logBuf.append("[").append(ts.format(Date())).append("] ").append(line).append("\n")
        if (logBuf.length > 12000) logBuf.delete(0, logBuf.length - 12000)
        runOnUiThread { logView.text = logBuf.toString() }
    }

    private fun refresh() {
        val st = LinuxEnvManager.status(this)
        val (titleColor, title) = when (st.state) {
            LinuxEnvManager.State.READY -> 0xFF69F069.toInt() to getString(R.string.linux_state_ready)
            LinuxEnvManager.State.BROKEN -> 0xFFFF5252.toInt() to getString(R.string.linux_state_broken)
            LinuxEnvManager.State.NOT_INSTALLED -> 0xFFC9C9D2.toInt() to getString(R.string.linux_state_not_installed)
            else -> 0xFFFFC24A.toInt() to getString(R.string.linux_state_working)
        }
        statusTitle.setTextColor(titleColor)
        statusTitle.text = if (working) "$title…" else title
        val sb = StringBuilder()
        sb.append(getString(R.string.linux_detail_arch, st.arch ?: "-")).append('\n')
        if (st.state == LinuxEnvManager.State.READY) {
            sb.append(getString(R.string.linux_detail_source, st.installSource ?: "-")).append('\n')
            st.manifestDigest?.let { sb.append(getString(R.string.linux_detail_digest, it.take(19) + "…")).append('\n') }
            st.rootfsDuKb?.let { sb.append(getString(R.string.linux_detail_used, it / 1024)).append('\n') }
            sb.append(getString(R.string.linux_detail_runtime, LinuxEnvManager.ROOTFS)).append('\n')
        }
        st.tarballBytes.takeIf { it > 0 }?.let { sb.append(getString(R.string.linux_detail_archive, it / (1024 * 1024))).append('\n') }
        sb.append(getString(R.string.linux_detail_free, ((st.freePrivateBytes ?: 0L) / (1024 * 1024)).coerceAtLeast(0)))
        // r1388: the error now carries the first real failing command + its
        // stderr verbatim (never a generic message) — give it room to render.
        st.lastError?.let { sb.append("\n").append(getString(R.string.linux_detail_error, it.take(1600))) }
        statusDetails.text = sb.toString()

        // buttons enablement — honest states
        val ready = st.state == LinuxEnvManager.State.READY
        val busyStates = setOf(LinuxEnvManager.State.CHECKING, LinuxEnvManager.State.DOWNLOADING,
            LinuxEnvManager.State.TRANSFERRING, LinuxEnvManager.State.EXTRACTING, LinuxEnvManager.State.VERIFYING)
        val installingNow = st.state in busyStates || working
        progress.visibility = if (installingNow) View.VISIBLE else View.GONE
        btnInstall.alpha = if (installingNow) 0.45f else 1f
        btnUpdate.alpha = if (ready && !working) 1f else 0.45f
        btnReset.alpha = if ((ready || st.state == LinuxEnvManager.State.BROKEN) && !working) 1f else 0.45f
        btnRemove.alpha = if (st.state != LinuxEnvManager.State.NOT_INSTALLED && !working) 1f else 0.45f
        btnTests.alpha = if (ready && !working) 1f else 0.45f
        btnTerminal.alpha = if (ready) 1f else 0.45f
    }

    private fun busy(): Boolean {
        if (working) toast(getString(R.string.linux_busy))
        return working
    }

    private fun askInstall() {
        if (busy()) return
        lifecycleScope.launch {
            val pf = withContext(Dispatchers.IO) { LinuxEnvManager.preFlight(this@LinuxEnvActivity) }
            val msg = buildString {
                append(getString(R.string.linux_preflight_summary))
                append("\n• ").append(getString(R.string.linux_pf_arch, pf.arch ?: getString(R.string.linux_pf_unsupported)))
                append("\n• ").append(getString(R.string.linux_pf_private, pf.freePrivateBytes / (1024 * 1024)))
                append("\n• ").append(getString(R.string.linux_pf_data, pf.freeDataBytes?.div(1024 * 1024) ?: -1))
                append("\n• ").append(getString(R.string.linux_pf_net, if (pf.networkOk) "✓" else "✗"))
                append("\n• ").append(getString(R.string.linux_pf_service, if (pf.serviceOk) "✓ (uid ${pf.serviceUid})" else "✗"))
                append("\n• ").append(getString(R.string.linux_pf_fs,
                    pf.fs?.fstype ?: "?",
                    if (pf.fs?.symlinkOk == true) "✓" else "✗",
                    (pf.fs?.symlinkErr ?: "").ifBlank { "" }.take(120)))
                if (pf.problems.isNotEmpty()) {
                    append("\n\n").append(getString(R.string.linux_pf_problems))
                    pf.problems.forEach { append("\n• ").append(it) }
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(this@LinuxEnvActivity)
                .setTitle(getString(R.string.linux_btn_install))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.linux_btn_install)) { _, _ -> doInstall() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun doInstall() {
        working = true
        refresh()
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    LinuxEnvManager.install(this@LinuxEnvActivity) { log(it) }
                }
                log(report.replace("\n", "\n  "))
                toast(if (LinuxEnvManager.currentState(this@LinuxEnvActivity) == LinuxEnvManager.State.READY)
                    getString(R.string.linux_toast_ready) else getString(R.string.linux_toast_failed))
            } catch (e: Throwable) {
                log("install error: ${e.message ?: e.javaClass.simpleName}")
            } finally { working = false; refresh() }
        }
    }

    private fun askUpdate() {
        if (busy()) return
        if (LinuxEnvManager.currentState(this) != LinuxEnvManager.State.READY) { toast(getString(R.string.linux_need_ready)); return }
        confirm(getString(R.string.linux_btn_update), getString(R.string.linux_update_confirm)) {
            working = true; refresh()
            lifecycleScope.launch {
                try {
                    val s = withContext(Dispatchers.IO) {
                        TerminalEngine.create(this@LinuxEnvActivity, TermBackend.LINUX_USERSPACE, "update", false)
                    }
                    val u = withContext(Dispatchers.IO) { s.execute("export DEBIAN_FRONTEND=noninteractive; apt-get update 2>&1 | tail -n 4; echo UPD_RC=\${PIPESTATUS[0]}", 300_000) }
                    log("apt-get update → ${Regex("UPD_RC=(\\d+)").find(u.stdout)?.groupValues?.get(1) ?: u.exitCode}\n  ${u.stdout.take(500)}")
                    val g = withContext(Dispatchers.IO) { s.execute("export DEBIAN_FRONTEND=noninteractive; apt-get upgrade -y 2>&1 | tail -n 6; echo GRD_RC=\${PIPESTATUS[0]}", 900_000) }
                    log("apt-get upgrade → ${Regex("GRD_RC=(\\d+)").find(g.stdout)?.groupValues?.get(1) ?: g.exitCode}\n  ${g.stdout.take(700)}")
                    TerminalEngine.close(s.id)
                } catch (e: Throwable) { log("update error: ${e.message}") } finally { working = false; refresh() }
            }
        }
    }

    private fun askReset() {
        if (busy()) return
        confirm(getString(R.string.linux_btn_reset), getString(R.string.linux_reset_confirm)) {
            working = true; refresh()
            lifecycleScope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { LinuxEnvManager.reset(this@LinuxEnvActivity) { log(it) } }
                    log(r)
                } catch (e: Throwable) { log("reset error: ${e.message}") } finally { working = false; refresh() }
            }
        }
    }

    private fun askRemove() {
        if (busy()) return
        confirm(getString(R.string.linux_btn_remove), getString(R.string.linux_remove_confirm)) {
            working = true; refresh()
            lifecycleScope.launch {
                try {
                    TerminalEngine.listSessions().filter { it.backend == TermBackend.LINUX_USERSPACE }
                        .forEach { TerminalEngine.close(it.id) }
                    val r = withContext(Dispatchers.IO) { LinuxEnvManager.remove(this@LinuxEnvActivity) { log(it) } }
                    log(r)
                } catch (e: Throwable) { log("remove error: ${e.message}") } finally { working = false; refresh() }
            }
        }
    }

    private fun runTests() {
        if (busy()) return
        if (LinuxEnvManager.currentState(this) != LinuxEnvManager.State.READY) { toast(getString(R.string.linux_need_ready)); return }
        working = true; refresh()
        lifecycleScope.launch {
            try {
                val (pass, results) = withContext(Dispatchers.IO) {
                    LinuxAcceptance.runAll(this@LinuxEnvActivity) { log(it) }
                }
                val report = LinuxAcceptance.report(pass, results)
                log(report)
                androidx.appcompat.app.AlertDialog.Builder(this@LinuxEnvActivity)
                    .setTitle(if (pass) getString(R.string.linux_tests_pass) else getString(R.string.linux_tests_fail))
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: Throwable) { log("tests error: ${e.message}") } finally { working = false; refresh() }
        }
    }

    private fun openTerminal() {
        if (LinuxEnvManager.currentState(this) != LinuxEnvManager.State.READY) { toast(getString(R.string.linux_need_ready)); return }
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                TerminalEngine.listSessions().firstOrNull { it.backend == TermBackend.LINUX_USERSPACE && it.alive }
                    ?: runCatching { TerminalEngine.create(this@LinuxEnvActivity, TermBackend.LINUX_USERSPACE, "linux", false) }.getOrNull()
            }
            if (s == null) { toast(getString(R.string.linux_session_failed)); return@launch }
            startActivity(Intent(this@LinuxEnvActivity, TerminalActivity::class.java))
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
