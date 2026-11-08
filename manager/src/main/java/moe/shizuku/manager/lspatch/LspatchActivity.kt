package moe.shizuku.manager.lspatch

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.terminal.linux.ShizukuExec
import moe.shizuku.manager.utils.ApkUtils.installPackage
import org.lsposed.lspatch.share.Constants
import org.lsposed.patch.ApkPatcher
import org.lsposed.patch.PatchException
import org.lsposed.patch.PatchSpec
import org.lsposed.patch.util.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * aMiNo v2 — LSPatch screen. Drives the EMBEDDED LSPatch v1.2 engine (:lspatch-engine,
 * frozen JingMatrix v1.2-487 sources) to patch a target app and embed the Rinigan module,
 * then installs the result (silent via the aMiNo shell service when it is up, else the one
 * system PackageInstaller confirmation). Same engine API the standalone manager uses —
 * «تعمل بنفس الكفاءة».
 */
class LspatchActivity : AppActivity() {

    private class Target(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        val sourceDir: String,
        val splits: Array<out String>?
    )

    private class AppPickAdapter(
        private val inflater: LayoutInflater,
        private val onClick: (Target) -> Unit
    ) : RecyclerView.Adapter<AppPickAdapter.VH>() {

        private val all = mutableListOf<Target>()
        private val shown = mutableListOf<Target>()

        fun reset(list: List<Target>) {
            all.clear(); all.addAll(list)
            shown.clear(); shown.addAll(list)
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val q = query.lowercase().trim()
            shown.clear()
            shown.addAll(all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) })
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(inflater.inflate(R.layout.item_app_pick, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = shown[position]
            h.icon.setImageDrawable(t.icon)
            h.name.text = t.label
            h.pkg.text = t.pkg
            h.itemView.setOnClickListener { onClick(t) }
        }
    }

    private val busy = AtomicBoolean(false) // r1420 discipline: one patch at a time, CAS-gated
    private var target: Target? = null
    private var patchedApk: File? = null

    private lateinit var targetIcon: ImageView
    private lateinit var targetName: TextView
    private lateinit var targetPackage: TextView
    private lateinit var moduleRinigan: CheckBox
    private lateinit var sigBypass: Spinner
    private lateinit var patchBtn: MaterialButton
    private lateinit var installBtn: MaterialButton
    private lateinit var log: TextView

    private val workDir: File by lazy { File(noBackupFilesDir, "lspatch").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lspatch)

        findViewById<View>(R.id.toolbar).setOnClickListener { finish() }
        // the layout uses MaterialToolbar; AppActivity does not install a support action bar
        (findViewById<View>(R.id.toolbar) as? com.google.android.material.appbar.MaterialToolbar)
            ?.setNavigationOnClickListener { finish() }

        targetIcon = findViewById(R.id.targetIcon)
        targetName = findViewById(R.id.targetName)
        targetPackage = findViewById(R.id.targetPackage)
        moduleRinigan = findViewById(R.id.moduleRinigan)
        sigBypass = findViewById(R.id.sigBypass)
        patchBtn = findViewById(R.id.patchBtn)
        installBtn = findViewById(R.id.installBtn)
        log = findViewById(R.id.log)

        // signature-spoofing level 0..3 (LSPatch Constants), default 2 = pm + openat (upstream default)
        sigBypass.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("0 — off", "1 — pm", "2 — pm+openat", "3 — +svc")
        )
        sigBypass.setSelection(2)

        findViewById<MaterialCardView>(R.id.targetCard).setOnClickListener { showAppPicker() }
        findViewById<TextView>(R.id.targetPick).setOnClickListener { showAppPicker() }
        patchBtn.setOnClickListener { startPatch() }
        installBtn.setOnClickListener { startInstall() }
    }

    // ------------------------------------------------------------- target picker

    private fun loadTargets(): List<Target> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = pm.queryIntentActivities(intent, 0)
        val self = packageName
        return ris.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != self }
            .distinctBy { it.packageName }
            .map { ai ->
                Target(
                    pkg = ai.packageName,
                    label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName },
                    icon = try { pm.getApplicationIcon(ai.packageName) } catch (_: Exception) { null },
                    sourceDir = ai.sourceDir ?: "",
                    splits = ai.splitSourceDirs
                )
            }
            .filter { it.sourceDir.isNotEmpty() }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun showAppPicker() {
        val inflater = layoutInflater
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            setHint(R.string.lspatch_search)
            setSingleLine()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LspatchActivity)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(search)
        container.addView(rv)

        var dialog: androidx.appcompat.app.AlertDialog? = null
        val adapter = AppPickAdapter(inflater) { t ->
            selectTarget(t)
            dialog?.dismiss()
        }
        adapter.reset(loadTargets())
        rv.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { adapter.filter(s?.toString().orEmpty()) }
        })

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lspatch_target)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun selectTarget(t: Target) {
        target = t
        targetIcon.setImageDrawable(t.icon)
        targetName.text = t.label
        targetPackage.text = t.pkg
        log.text = ""
        log("▶ ${t.label} (${t.pkg})")
    }

    // ------------------------------------------------------------------- patching

    private fun log(s: String) {
        log.append(if (log.text.isEmpty()) s else "\n$s")
    }

    private fun logLine(s: String) = runOnUiThread { log(s) }

    private fun startPatch() {
        val t = target
        if (t == null) {
            Toast.makeText(this, R.string.lspatch_no_target, Toast.LENGTH_SHORT).show()
            return
        }
        if (!busy.compareAndSet(false, true)) { // CAS — never two patches (r1420 lesson)
            Toast.makeText(this, R.string.lspatch_busy, Toast.LENGTH_SHORT).show()
            return
        }
        patchBtn.isEnabled = false
        installBtn.visibility = View.GONE
        log.text = ""

        val sigBypassLevel = sigBypass.selectedItemPosition.coerceIn(0, Constants.SIGBYPASS_LV_MAX - 1)
        val embedRinigan = moduleRinigan.isChecked

        // Logger BEFORE any suspend — mirrors the engine contract (ApkPatcher ctor)
        val engineLogger = object : Logger() {
            override fun d(msg: String) { if (verbose) logLine("· $msg") }
            override fun i(msg: String) { logLine(msg) }
            override fun e(msg: String) { logLine("✗ $msg") }
            override fun stage(stage: Stage, index: Int, total: Int) {
                logLine("◇ $stage ($index/$total)")
            }
        }

        lifecycleScope.launch {
            try {
                val out: File? = withContext(Dispatchers.IO) {
                    logLine(getString(R.string.lspatch_copied))
                    // 1. copy target base + splits into our private storage (pm path is readable,
                    //    but our copy is stable while the engine runs)
                    val base = File(workDir, "${t.pkg}-base.apk")
                    File(t.sourceDir).copyTo(base, overwrite = true)
                    val apks = mutableListOf(base)
                    t.splits?.forEachIndexed { i, sp ->
                        val f = File(workDir, "${t.pkg}-split$i.apk")
                        File(sp).copyTo(f, overwrite = true)
                        apks.add(f)
                    }
                    // 2. module: embedded Rinigan
                    val modules = buildList {
                        if (embedRinigan) add(RiniganInstaller.embeddedFile(this@LspatchActivity))
                    }
                    // 3. engine — the SAME ApkPatcher API the standalone manager drives
                    val spec = PatchSpec.builder()
                        .apks(apks)
                        .outputDir(workDir)
                        .useManager(false)              // integrated mode: modules embedded, no LSPatch manager app
                        .debuggable(false)
                        .sigBypassLevel(sigBypassLevel)
                        .modules(modules)
                        .forceOverwrite(true)
                        .build()
                    ApkPatcher(engineLogger, spec).patch().firstOrNull()
                }
                patchedApk = out
                out?.let { f ->
                    logLine("${getString(R.string.lspatch_done)} ${f.absolutePath} (${f.length() / 1024 / 1024}MB)")
                    installBtn.visibility = View.VISIBLE
                } ?: run {
                    logLine("✗ ${getString(R.string.lspatch_failed)}: no output")
                }
            } catch (e: PatchException) {
                logLine("✗ ${getString(R.string.lspatch_failed)}: ${e.message}")
            } catch (e: Throwable) {
                logLine("✗ ${getString(R.string.lspatch_failed)}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy.set(false)
                patchBtn.isEnabled = true
            }
        }
    }

    // ------------------------------------------------------------------ installing

    private fun startInstall() {
        val apk = patchedApk ?: return
        lifecycleScope.launch {
            val shot = withContext(Dispatchers.IO) { silentInstall(apk) }
            if (shot != null) {
                if (shot.ok) {
                    Toast.makeText(this@LspatchActivity, R.string.lspatch_install_silent, Toast.LENGTH_LONG).show()
                    logLine("✓ ${getString(R.string.lspatch_install_silent)}")
                } else {
                    logLine("✗ ${getString(R.string.lspatch_install_failed, shot.output.take(200))}")
                }
            } else {
                installPackage(apk) { ok, msg ->
                    Toast.makeText(
                        this@LspatchActivity,
                        if (ok) getString(R.string.lspatch_install_done)
                        else getString(R.string.lspatch_install_failed, msg ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Shizuku silent path (same mechanism as RiniganInstaller), for an arbitrary APK file. */
    private suspend fun silentInstall(apk: File): ShizukuExec.Shot? {
        if (!ShizukuExec.available()) return null
        return ShizukuExec.oneShot("pm install -r '${apk.absolutePath}'", timeoutMs = 120_000)
    }
}
