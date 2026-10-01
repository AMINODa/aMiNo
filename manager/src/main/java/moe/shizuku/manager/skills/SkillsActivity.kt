package moe.shizuku.manager.skills

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.agent.skills.SkillEngine
import moe.shizuku.manager.agent.skills.SkillImporter
import moe.shizuku.manager.agent.skills.SkillManager
import moe.shizuku.manager.agent.skills.SkillPackage
import moe.shizuku.manager.agent.skills.SkillRouter

/**
 * v1.4 SKILLS CENTER — the dedicated Skills window (drawer 🧩).
 *
 * Every button here is wired to a REAL backend operation (SkillManager /
 * SkillImporter / SkillEngine / SkillRouter). Nothing is simulated:
 *   Import    → file (.md/.json/.zip) via SAF, folder via OpenDocumentTree,
 *               GitHub URL, or pasted text → normalized → installed (files + DB)
 *   Create    → manual package form → SkillManager.createManual
 *   Discover  → real GitHub repository search → import SKILL.md
 *   Run       → SkillManager.runPreflight (security gate) + SkillEngine.run
 *   Edit      → SkillManager.update (re-signs integrity, bumps version)
 *   Export    → JSON / re-importable ZIP to a user-chosen location
 *   Delete    → package file + carried files + metadata index row
 *   Scripts   → review content + explicit user approval (never auto-executed)
 */
class SkillsActivity : AppActivity() {

    private lateinit var adapter: Adapter
    private var currentQuery: String? = null
    private var currentCategory: String? = null
    private val shownCategories = HashSet<String>()
    private var pendingExportId: String? = null

    // ---------------------------------------------------------- SAF launchers
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFromUri(uri)
        }
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) importFromFolder(uri)
        }
    private val exportDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            val id = pendingExportId
            if (uri != null && id != null) exportTo(uri, id, false)
        }
    private val exportZipDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            val id = pendingExportId
            if (uri != null && id != null) exportTo(uri, id, true)
        }

    // ---------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skills)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        list.adapter = adapter

        findViewById<EditText>(R.id.searchEdit).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.takeIf { it.isNotBlank() }
                reload()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { showImportOptions() }
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { showCreateDialog(null) }
        findViewById<MaterialButton>(R.id.btnDiscover).setOnClickListener { showDiscoverDialog() }

        rebuildChips()
        reload()
    }

    // ---------------------------------------------------------- data
    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                SkillManager.reconcile(this@SkillsActivity)
                SkillManager.all(this@SkillsActivity, query = currentQuery, category = currentCategory)
            }
            adapter.submit(items)
            findViewById<TextView>(R.id.emptyText).isVisible = items.isEmpty()
            findViewById<RecyclerView>(R.id.list).isVisible = items.isNotEmpty()
            addCategoryChips(items.map { it.category })
        }
    }

    private fun rebuildChips() {
        val group = findViewById<ChipGroup>(R.id.chipGroup)
        group.removeAllViews()
        val all = Chip(this)
        all.text = getString(R.string.skills_cat_all)
        all.isCheckable = true
        all.isChecked = currentCategory == null
        all.setOnClickListener { currentCategory = null; reload() }
        group.addView(all)
        for (c in FIXED_CATEGORIES) addChipIfNeeded(c)
    }

    private fun addCategoryChips(present: List<String>) {
        present.map { it.ifBlank { "custom" } }.distinct()
            .filter { it !in FIXED_CATEGORIES }.take(4).forEach { addChipIfNeeded(it) }
    }

    private fun addChipIfNeeded(cat: String) {
        if (cat in shownCategories) return
        shownCategories.add(cat)
        val group = findViewById<ChipGroup>(R.id.chipGroup)
        val chip = Chip(this)
        chip.text = cat
        chip.isCheckable = true
        chip.setOnClickListener { currentCategory = cat; reload() }
        group.addView(chip)
    }

    // ---------------------------------------------------------- import
    private fun showImportOptions() {
        val options = arrayOf(
            getString(R.string.skills_imp_file),
            getString(R.string.skills_imp_folder),
            getString(R.string.skills_imp_github),
            getString(R.string.skills_imp_paste))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skills_import)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickFile.launch(arrayOf("*/*"))
                    1 -> pickFolder.launch(null)
                    2 -> showGithubUrlDialog()
                    3 -> showPasteDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val cand = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(uri) ?: "picked-skill"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("could not read the picked file")
                    val lower = name.lowercase()
                    when {
                        lower.endsWith(".zip") -> SkillImporter.fromZip(bytes)
                        lower.endsWith(".json") -> SkillImporter.fromManifestJson(String(bytes, Charsets.UTF_8), "")
                        lower.endsWith(".md") || lower.endsWith(".markdown") ->
                            SkillImporter.fromSkillMd(String(bytes, Charsets.UTF_8), "")
                        else -> SkillImporter.fromPaste(String(bytes, Charsets.UTF_8))
                    }
                }
                previewAndInstall(cand)
            } catch (e: Exception) {
                errDialog("import: ${e.message?.take(300)}")
            }
        }
    }

    private fun importFromFolder(uri: Uri) {
        lifecycleScope.launch {
            try {
                val cand = withContext(Dispatchers.IO) {
                    SkillImporter.fromFolder(this@SkillsActivity, uri)
                }
                previewAndInstall(cand)
            } catch (e: Exception) {
                errDialog("folder import: ${e.message?.take(300)}")
            }
        }
    }

    private fun showGithubUrlDialog() {
        val input = EditText(this).apply { hint = "https://github.com/owner/repo"; setSingleLine() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skills_imp_github)
            .setMessage(R.string.skills_github_help)
            .setView(input)
            .setPositiveButton(R.string.skills_fetch) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val cand = withContext(Dispatchers.IO) { SkillImporter.fromGitHub(url) }
                        previewAndInstall(cand)
                    } catch (e: Exception) {
                        errDialog("github: ${e.message?.take(300)}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.skills_paste_hint)
            minLines = 6
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skills_imp_paste)
            .setView(input)
            .setPositiveButton(R.string.skills_next) { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val cand = withContext(Dispatchers.IO) { SkillImporter.fromPaste(text) }
                        previewAndInstall(cand)
                    } catch (e: Exception) {
                        errDialog("paste: ${e.message?.take(300)}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDiscoverDialog() {
        val input = EditText(this).apply { hint = getString(R.string.skills_discover_hint); setSingleLine() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skills_discover)
            .setMessage(R.string.skills_discover_help)
            .setView(input)
            .setPositiveButton(R.string.skills_search) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val repos = withContext(Dispatchers.IO) { SkillImporter.githubSearch(q) }
                        if (repos.isEmpty()) {
                            toast(getString(R.string.skills_discover_none)); return@launch
                        }
                        val labels = repos.map { r ->
                            "${r.owner}/${r.repo}" + (r.description.takeIf { it.isNotBlank() }?.let { " — ${it.take(80)}" } ?: "")
                        }
                        MaterialAlertDialogBuilder(this@SkillsActivity)
                            .setTitle(R.string.skills_discover_pick)
                            .setItems(labels.toTypedArray()) { _, which ->
                                val r = repos[which]
                                lifecycleScope.launch {
                                    try {
                                        val cand = withContext(Dispatchers.IO) { SkillImporter.fromGitHub(r.url) }
                                        previewAndInstall(cand)
                                    } catch (e: Exception) {
                                        errDialog("github: ${e.message?.take(300)}")
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } catch (e: Exception) {
                        errDialog("discover: ${e.message?.take(300)}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun previewAndInstall(cand: SkillImporter.ImportedSkill) {
        val sb = StringBuilder()
        sb.appendLine("name: ${cand.name}")
        sb.appendLine("version: ${cand.version}   category: ${cand.category}")
        if (cand.author.isNotBlank()) sb.appendLine("author: ${cand.author}")
        sb.appendLine("format: ${cand.originalFormat} ← ${cand.sourceType}")
        if (cand.description.isNotBlank()) sb.appendLine("desc: ${cand.description.take(300)}")
        sb.appendLine("content: " + (if (cand.steps != null && cand.steps.length() > 0)
            "${cand.steps.length()} deterministic steps" else "playbook instructions (${cand.instructions.length} chars)"))
        val scripts = cand.files.count { it.relPath.startsWith("scripts/") }
        if (cand.files.isNotEmpty()) {
            sb.appendLine("carried files: ${cand.files.size}" +
                (if (scripts > 0) " (⚠ $scripts script(s) — stored as DATA, approval required)" else ""))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.skills_preview_title))
            .setMessage(sb.toString())
            .setPositiveButton(R.string.skills_install) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { SkillManager.install(this@SkillsActivity, cand) }
                        toast((if (r.ok) "✔ " else "✖ ") + r.output.take(220))
                        reload()
                    } catch (e: Exception) {
                        errDialog("install: ${e.message?.take(300)}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- create / edit
    private fun showCreateDialog(existing: SkillPackage?) {
        val v = layoutInflater.inflate(R.layout.dialog_skill_create, null)
        val fName = v.findViewById<EditText>(R.id.fName)
        val fVersion = v.findViewById<EditText>(R.id.fVersion)
        val fCategory = v.findViewById<EditText>(R.id.fCategory)
        val fTags = v.findViewById<EditText>(R.id.fTags)
        val fDesc = v.findViewById<EditText>(R.id.fDescription)
        val fTrigger = v.findViewById<EditText>(R.id.fTrigger)
        val fInstr = v.findViewById<EditText>(R.id.fInstructions)
        val fSteps = v.findViewById<EditText>(R.id.fSteps)
        val fParams = v.findViewById<EditText>(R.id.fParamsHint)
        val fVerify = v.findViewById<EditText>(R.id.fVerify)
        val fTools = v.findViewById<EditText>(R.id.fTools)
        existing?.let { p ->
            fName.setText(p.name)
            fVersion.setText(p.version)
            fCategory.setText(p.category)
            fTags.setText(p.tags.joinToString(", "))
            fDesc.setText(p.description)
            fTrigger.setText(p.trigger)
            fInstr.setText(p.instructions)
            fSteps.setText(runCatching { org.json.JSONArray(p.stepsJson).toString(2) }.getOrDefault(p.stepsJson))
            fParams.setText(p.paramsHint ?: "")
            fVerify.setText(p.verify ?: "")
            fTools.setText(p.requiredTools.joinToString(", "))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.skills_create else R.string.skills_edit)
            .setView(v)
            .setPositiveButton(if (existing == null) R.string.skills_create else R.string.skills_save) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) {
                            if (existing == null)
                                SkillManager.createManual(this@SkillsActivity,
                                    fName.text.toString(), fDesc.text.toString(),
                                    fVersion.text.toString(), fCategory.text.toString(),
                                    fTags.text.toString(), fTrigger.text.toString(),
                                    fInstr.text.toString(), fParams.text.toString(),
                                    fVerify.text.toString(), fSteps.text.toString(),
                                    fTools.text.toString())
                            else {
                                val edited = existing.copy(
                                    name = fName.text.toString().take(80),
                                    version = fVersion.text.toString(),
                                    category = fCategory.text.toString().lowercase(),
                                    tags = fTags.text.toString().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                                    description = fDesc.text.toString(),
                                    trigger = fTrigger.text.toString(),
                                    instructions = fInstr.text.toString(),
                                    paramsHint = fParams.text.toString().ifBlank { null },
                                    verify = fVerify.text.toString().ifBlank { null },
                                    stepsJson = fSteps.text.toString(),
                                    requiredTools = fTools.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
                                SkillManager.update(this@SkillsActivity, edited,
                                    getString(R.string.skills_edited_note), bumpVersion = true)
                            }
                        }
                        toast((if (r.ok) "✔ " else "✖ ") + r.output.take(220))
                        reload()
                    } catch (e: Exception) {
                        errDialog("create/edit: ${e.message?.take(300)}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- detail
    private fun showDetail(p: SkillPackage) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 24)
        }
        fun spacer(h: Int) = box.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, h)
        })
        fun line(label: String, value: String) {
            if (value.isBlank()) return
            val tv = TextView(this).apply {
                text = label; textSize = 12f; setTextColor(0xFF8A8A94.toInt())
            }
            val tv2 = TextView(this).apply { text = value; textSize = 13f }
            box.addView(tv); box.addView(tv2); spacer(24)
        }
        line(getString(R.string.skill_d_id), "${p.id}  (v${p.version})")
        line(getString(R.string.skill_d_meta),
            listOfNotNull(
                getString(R.string.skill_d_cat) + " " + p.category,
                if (p.author.isNotBlank()) getString(R.string.skill_d_author) + " " + p.author else null,
                getString(R.string.skill_d_src) + " " + p.source.type +
                    (p.source.url.takeIf { it.isNotBlank() }?.let { " (${it.take(80)})" } ?: "")
            ).joinToString("  •  "))
        line(getString(R.string.skill_d_desc), p.description.ifBlank { p.trigger })
        line(getString(R.string.skill_d_exec),
            if (p.hasSteps) getString(R.string.skill_d_steps,
                runCatching { org.json.JSONArray(p.stepsJson).length() }.getOrDefault(0))
            else getString(R.string.skill_d_playbook))
        line(getString(R.string.skill_d_tools),
            (p.requiredTools.joinToString(", ").ifBlank { "—" } + "\n" + SkillRouter.availabilityReport(this, p)).trim())
        if (p.dependencies.isNotEmpty()) line(getString(R.string.skill_d_deps), p.dependencies.joinToString(", "))
        if (p.permissions.isNotEmpty()) line(getString(R.string.skill_d_perms), p.permissions.joinToString(", "))
        if (p.tags.isNotEmpty()) line(getString(R.string.skill_d_tags), p.tags.joinToString(", "))
        if (p.attachments.isNotEmpty()) line(getString(R.string.skill_d_attach), p.attachments.joinToString(", "))
        if (p.changelog.isNotEmpty()) line(getString(R.string.skill_d_changelog),
            p.changelog.takeLast(5).joinToString("\n") { "${it.version}: ${it.notes}" })
        line(getString(R.string.skill_d_stats), getString(R.string.skill_d_stats_val, p.success, p.fail))
        if (p.instructions.isNotBlank()) line(getString(R.string.skill_d_instructions), p.instructions.take(3000))

        // action rows
        fun action(textRes: Int, onClick: (SkillPackage) -> Unit): MaterialButton =
            MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(textRes); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick(p) }
            }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(action(R.string.skills_run) { runSkill(it) })
        row.addView(action(R.string.skills_edit) { showCreateDialog(it) })
        box.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(action(R.string.skills_export_json) { exportSkill(it, false) })
        row2.addView(action(R.string.skills_export_zip) { exportSkill(it, true) })
        row2.addView(action(R.string.skills_delete) { confirmDelete(it) })
        box.addView(row2)

        // scripts section — review + explicit approval (SECURITY)
        for (s in p.scripts) {
            val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(this).apply {
                text = (if (s.approved) "✓ " else "⚠ ") + s.name
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (s.approved) 0xFF69F0AE.toInt() else 0xFFFF8A80.toInt())
            }
            srow.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val review = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.skills_review); textSize = 12f
                setOnClickListener { reviewScript(p.id, s.name) }
            }
            srow.addView(review)
            val approve = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(if (s.approved) R.string.skills_revoke else R.string.skills_approve); textSize = 12f
                setOnClickListener {
                    lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SkillManager.approveScript(this@SkillsActivity, p.id, s.name, !s.approved)
                        }
                        toast((if (r.ok) "✔ " else "✖ ") + r.output.take(160))
                        reload()
                    }
                }
            }
            srow.addView(approve)
            box.addView(srow)
        }

        scroll.addView(box)
        MaterialAlertDialogBuilder(this)
            .setTitle("🧩 ${p.name}")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun reviewScript(id: String, scriptName: String) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                SkillManager.scriptContent(this@SkillsActivity, id, scriptName)
            }
            MaterialAlertDialogBuilder(this@SkillsActivity)
                .setTitle(getString(R.string.skills_review_title, scriptName))
                .setMessage(content ?: getString(R.string.skills_review_missing))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun runSkill(p: SkillPackage) {
        val input = EditText(this).apply {
            hint = getString(R.string.skills_params_hint)
            setText("{}")
            minLines = 3
            typeface = Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.skills_run_title, p.name))
            .setMessage((p.paramsHint?.takeIf { it.isNotBlank() }?.let { "$it\n" } ?: "") +
                getString(R.string.skills_run_help))
            .setView(input)
            .setPositiveButton(R.string.skills_run) { _, _ ->
                val params = runCatching { org.json.JSONObject(input.text.toString().ifBlank { "{}" }) }
                    .getOrElse { toast(getString(R.string.skills_bad_params)); return@setPositiveButton }
                toast(getString(R.string.skills_running))
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        val gate = SkillManager.runPreflight(this@SkillsActivity, p.id)
                        if (gate != null) gate
                        else {
                            val res = SkillEngine.run(this@SkillsActivity, p.id, params)
                            SkillManager.recordResult(this@SkillsActivity, p.id, res.ok)
                            res
                        }
                    }
                    MaterialAlertDialogBuilder(this@SkillsActivity)
                        .setTitle(if (r.ok) "✔ ${getString(R.string.skills_run_ok)}" else "✖ ${getString(R.string.skills_run_fail)}")
                        .setMessage(r.output.take(4000))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSkill(p: SkillPackage, zip: Boolean) {
        pendingExportId = p.id
        if (zip) exportZipDoc.launch("${p.id}-v${p.version}.zip")
        else exportDoc.launch("${p.id}-v${p.version}.json")
    }

    private fun exportTo(uri: Uri, id: String, zip: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (zip) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            if (!SkillManager.exportZip(this@SkillsActivity, id, os))
                                throw IllegalStateException("export failed — no skill '$id'")
                        } ?: throw IllegalStateException("could not open output stream")
                    } else {
                        val res = SkillManager.exportJson(this@SkillsActivity, id)
                        if (!res.ok) throw IllegalStateException(res.output.take(200))
                        val src = res.output.substringAfter("exported: ").substringBefore(" (")
                        contentResolver.openOutputStream(uri)?.use { os ->
                            java.io.File(src).inputStream().use { ins -> ins.copyTo(os) }
                        } ?: throw IllegalStateException("could not open output stream")
                    }
                }
            }
            if (result.isSuccess) toast("✔ ${getString(R.string.skills_exported)}")
            else errDialog("export: ${result.exceptionOrNull()?.message?.take(200)}")
        }
    }

    private fun confirmDelete(p: SkillPackage) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.skills_delete_title, p.name))
            .setMessage(getString(R.string.skills_delete_msg, p.id))
            .setPositiveButton(R.string.skills_delete) { _, _ ->
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { SkillManager.delete(this@SkillsActivity, p.id) }
                    toast((if (r.ok) "✔ " else "✖ ") + r.output.take(160))
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- helpers
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun errDialog(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skills_error)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sourceLabel(p: SkillPackage): String = when (p.source.type) {
        "github" -> "🐙 github"
        "zip" -> "📦 zip"
        "folder" -> "📁 folder"
        "paste" -> "📋 paste"
        "manual" -> "✍ manual"
        "workflow" -> "⚙ workflow"
        else -> "💾 " + p.source.type
    }

    private fun statusOf(p: SkillPackage): String = when {
        !p.enabled -> getString(R.string.skills_disabled)
        p.hasUnapprovedScripts -> getString(R.string.skills_scripts_warning, p.scripts.count { !it.approved })
        p.hasSteps -> getString(R.string.skills_status_steps, p.success, p.fail)
        else -> getString(R.string.skills_status_playbook, p.success, p.fail)
    }

    // ---------------------------------------------------------- adapter
    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private val rows = ArrayList<SkillPackage>()
        fun submit(list: List<SkillPackage>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_skill_row, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = rows[position]
            holder.name.text = "${p.name}  (${p.version})"
            holder.meta.text = "${p.id} • ${p.category} • ${sourceLabel(p)}"
            holder.desc.text = p.description.ifBlank { p.trigger }.ifBlank { "—" }
            holder.status.text = statusOf(p)
            holder.status.setTextColor(when {
                !p.enabled -> 0xFFFF5252.toInt()
                p.hasUnapprovedScripts -> 0xFFFF8A80.toInt()
                else -> 0xFF69F0AE.toInt()
            })
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = p.enabled
            holder.switch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SkillManager.setEnabled(this@SkillsActivity, p.id, checked)
                    }
                    reload()
                }
            }
            holder.row.setOnClickListener { showDetail(p) }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v
        val name: TextView = v.findViewById(R.id.nameText)
        val meta: TextView = v.findViewById(R.id.metaText)
        val desc: TextView = v.findViewById(R.id.descText)
        val status: TextView = v.findViewById(R.id.statusText)
        val switch: SwitchMaterial = v.findViewById(R.id.switchEnable)
    }

    companion object {
        private val FIXED_CATEGORIES = listOf("system", "network", "files", "productivity", "custom", "imported")
    }
}
