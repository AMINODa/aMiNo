package moe.shizuku.manager.agent.skills

import android.content.ContentValues
import android.content.Context
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.memory.AminoDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.4 SKILLS SYSTEM — the central registry.
 *
 * Storage model ("files are the truth, the DB is the index"):
 *   <filesDir>/skills/<id>.json        — skill package (amino-skill/1, or legacy v1.3)
 *   <filesDir>/skills/<id>_files/...   — imported scripts + data files (scripts: approved=false)
 *   <filesDir>/exports/<id>-v<ver>.json — exportJson output
 *   AminoDb v3 table `skills`          — metadata index (search, categories, integrity,
 *                                        installed/updated stamps) — rebuilt from files
 *                                        by reconcile() if lost, so skills SURVIVE
 *                                        restarts, DB wipes and app updates.
 *
 * SECURITY (enforced here, not by convention):
 *   - imported scripts are DATA; nothing executes them (no code path exists)
 *   - runPreflight() blocks runs of disabled skills and skills holding unapproved scripts
 *   - integrity: content-hash (counters excluded) stored at install/update; a mismatch
 *     at run time means the file was edited outside aMiNo → run blocked until re-saved
 *   - source tracking: every package carries where it came from and when
 */
object SkillManager {

    private val STEP_TYPES = setOf("shell", "terminal", "input", "perceive", "wait")
    private val SCRIPT_EXT = setOf("sh", "py", "js", "bin", "exe", "pl", "rb", "lua")
    private const val EXPORT_DIR = "exports"

    // ================================================================ queries

    fun all(
        ctx: Context, query: String? = null, category: String? = null,
        enabledOnly: Boolean = false
    ): List<SkillPackage> {
        var list = SkillPackage.dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(200)
            ?.mapNotNull { f -> SkillPackage.fromFile(f) }
            ?: emptyList()
        if (enabledOnly) list = list.filter { it.enabled }
        if (!category.isNullOrBlank()) list = list.filter { it.category.equals(category, true) }
        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { p ->
                p.name.lowercase().contains(q) || p.description.lowercase().contains(q) ||
                    p.trigger.lowercase().contains(q) || p.instructions.lowercase().contains(q) ||
                    p.tags.any { it.lowercase().contains(q) } || p.id.lowercase().contains(q)
            }
        }
        return list
    }

    fun get(ctx: Context, id: String): SkillPackage? =
        SkillPackage.fileOf(ctx, SkillPackage.sanitizeId(id)).takeIf { it.isFile }
            ?.let { f -> SkillPackage.fromFile(f) }

    fun categories(ctx: Context): List<String> =
        all(ctx).map { it.category.ifBlank { "custom" } }.distinct().sorted()

    fun search(ctx: Context, q: String): List<SkillPackage> = all(ctx, query = q)

    // ================================================================ steps validation (same rules as SkillEngine.save)

    private fun validateSteps(stepsJson: String?): Pair<JSONArray, String?> {
        if (stepsJson.isNullOrBlank() || stepsJson.trim() == "[]") return Pair(JSONArray(), null)
        return try {
            val arr = JSONArray(stepsJson.trim())
            if (arr.length() > 50) return Pair(arr, "more than 50 steps is not allowed")
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i)
                    ?: return Pair(arr, "steps[$i] is not a JSON object")
                val type = st.optString("type", "")
                if (type !in STEP_TYPES)
                    return Pair(arr, "steps[$i].type='$type' is invalid — use shell | terminal | input | perceive | wait")
                when (type) {
                    "shell", "terminal" -> if (st.optString("cmd", "").isBlank())
                        return Pair(arr, "steps[$i] ($type) requires cmd")
                    "input" -> if (st.optString("action", "").isBlank())
                        return Pair(arr, "steps[$i] (input) requires action")
                }
            }
            Pair(arr, null)
        } catch (e: Exception) {
            Pair(JSONArray(), "steps is not valid JSON: ${e.message?.take(150)}")
        }
    }

    // ================================================================ install / create / update

    /** Normalizes an imported candidate into a real package (files + DB). */
    fun install(ctx: Context, cand: SkillImporter.ImportedSkill): AgentTools.ToolResult {
        val (steps, err) = validateSteps(cand.steps?.toString())
        if (err != null) return AgentTools.ToolResult(false, "import rejected: $err")
        if (cand.name.isBlank()) return AgentTools.ToolResult(false, "import rejected: skill has no name")

        val baseId = SkillPackage.sanitizeId(cand.name)
        var id = baseId
        var n = 2
        while (SkillPackage.fileOf(ctx, id).exists() && n < 100) { id = "$baseId-$n"; n++ }

        // write carried files (scripts + attachments) as DATA
        val filesDir = SkillPackage.filesDirOf(ctx, id)
        val scripts = ArrayList<SkillPackage.ScriptRef>()
        val attachments = ArrayList<String>()
        for (f in cand.files) {
            val rel = f.relPath.replace("..", "_").trimStart('/')
            if (rel.isBlank()) continue
            val out = File(filesDir, rel)
            out.parentFile?.mkdirs()
            out.writeBytes(f.bytes)
            val ext = rel.substringAfterLast('.', "").lowercase()
            if (rel.startsWith("scripts/") || ext in SCRIPT_EXT)
                scripts.add(SkillPackage.ScriptRef(rel.substringAfterLast('/'), rel, false))
            else attachments.add(rel)
        }

        val now = System.currentTimeMillis()
        val pkg = SkillPackage(
            id = id,
            name = cand.name.trim().take(80),
            description = cand.description.trim().take(400),
            version = cand.version.trim().take(24).ifBlank { "1.0.0" },
            author = cand.author.trim().take(80),
            category = cand.category.trim().take(24).lowercase().ifBlank { "imported" },
            tags = cand.tags.map { it.trim().lowercase().take(24) }.filter { it.isNotBlank() }.distinct().take(12),
            enabled = true,
            trigger = "imported skill: ${cand.name.take(120)}",
            paramsHint = cand.paramsHint?.trim()?.take(400),
            instructions = cand.instructions,
            stepsJson = steps.toString(),
            verify = cand.verify?.trim()?.take(400),
            requiredTools = cand.requiredTools.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            dependencies = cand.dependencies.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            inputSchema = cand.inputSchema,
            outputSchema = cand.outputSchema,
            examples = cand.examples,
            permissions = cand.permissions.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            scripts = scripts,
            attachments = attachments,
            changelog = cand.changelog.ifEmpty { listOf(SkillPackage.ChangelogEntry(cand.version, "installed", now)) },
            source = SkillPackage.SourceInfo(cand.sourceType, cand.sourceUrl, cand.originalFormat, now),
            success = 0, fail = 0, created = now, lastUsed = 0L
        )
        pkg.writeTo(ctx)
        dbUpsert(ctx, pkg)
        val scriptsNote = if (scripts.isEmpty()) "" else
            " ⚠ ${scripts.size} script(s) carried as DATA and NOT approved — nothing executes until the user reviews them in the Skills Center."
        return AgentTools.ToolResult(true,
            "skill installed: id=$id v${pkg.version} [${pkg.category}] " +
                "${if (pkg.hasSteps) "(${steps.length()} deterministic steps — skill_run)" else "(playbook — skill_use)"}.$scriptsNote" +
                " It now appears in the Skills Center and the Known-skills context.")
    }

    /** Manual creation from the Skills Center form (or the agent via tool args). */
    fun createManual(
        ctx: Context, name: String, description: String, version: String, category: String,
        tagsCsv: String, trigger: String, instructions: String, paramsHint: String?,
        verify: String?, stepsJson: String, requiredToolsCsv: String
    ): AgentTools.ToolResult {
        if (name.isBlank()) return AgentTools.ToolResult(false, "name is required")
        val (steps, err) = validateSteps(stepsJson)
        if (err != null) return AgentTools.ToolResult(false, err ?: "invalid steps")
        val baseId = SkillPackage.sanitizeId(name)
        var id = baseId
        var n = 2
        while (SkillPackage.fileOf(ctx, id).exists() && n < 100) { id = "$baseId-$n"; n++ }
        val now = System.currentTimeMillis()
        val pkg = SkillPackage(
            id = id,
            name = name.trim().take(80),
            description = description.trim().take(400),
            version = version.trim().take(24).ifBlank { "1.0.0" },
            author = "user",
            category = category.trim().take(24).lowercase().ifBlank { "custom" },
            tags = tagsCsv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(12),
            enabled = true,
            trigger = trigger.trim().take(200),
            paramsHint = paramsHint?.trim()?.take(400)?.ifBlank { null },
            instructions = instructions.trim().take(24_000),
            stepsJson = steps.toString(),
            verify = verify?.trim()?.take(400)?.ifBlank { null },
            requiredTools = requiredToolsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            dependencies = emptyList(),
            inputSchema = null, outputSchema = null,
            examples = JSONArray(), permissions = emptyList(),
            scripts = emptyList(), attachments = emptyList(),
            changelog = listOf(SkillPackage.ChangelogEntry(version.trim().take(24).ifBlank { "1.0.0" }, "created in Skills Center", now)),
            source = SkillPackage.SourceInfo("manual", "", "manual", now),
            success = 0, fail = 0, created = now, lastUsed = 0L
        )
        pkg.writeTo(ctx)
        dbUpsert(ctx, pkg)
        return AgentTools.ToolResult(true,
            "skill created: id=$id v${pkg.version} ${if (pkg.hasSteps) "(deterministic — skill_run)" else "(playbook — skill_use)"}")
    }

    /** Re-writes an edited package (Skills Center edit) — re-signs integrity + changelog note. */
    fun update(ctx: Context, edited: SkillPackage, changelogNote: String, bumpVersion: Boolean): AgentTools.ToolResult {
        val current = get(ctx, edited.id)
            ?: return AgentTools.ToolResult(false, "no skill '${edited.id}' to update")
        val (steps, err) = validateSteps(edited.stepsJson)
        if (err != null) return AgentTools.ToolResult(false, err ?: "invalid steps")
        val newVersion = if (bumpVersion) nextVersion(current.version) else edited.version
        val now = System.currentTimeMillis()
        val pkg = edited.copy(
            stepsJson = steps.toString(),
            version = newVersion,
            changelog = current.changelog + SkillPackage.ChangelogEntry(newVersion, changelogNote.take(200), now),
            source = if (current.source.originalFormat == "legacy-1.3" || current.source.originalFormat == "legacy")
                SkillPackage.SourceInfo(current.source.type, current.source.url, "legacy-1.3", current.source.importedAt)
            else current.source,
            success = current.success, fail = current.fail, created = current.created, lastUsed = current.lastUsed
        )
        pkg.writeTo(ctx)
        dbUpsert(ctx, pkg)
        return AgentTools.ToolResult(true, "skill updated: ${pkg.id} → v${pkg.version}")
    }

    private fun nextVersion(v: String): String {
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").find(v.trim())
        return if (m != null) {
            val minor = (m.groupValues[2].toIntOrNull() ?: 0) + 1
            "${m.groupValues[1]}.$minor.${m.groupValues[3]}"
        } else "1.0.1"
    }

    // ================================================================ lifecycle

    fun setEnabled(ctx: Context, id: String, enabled: Boolean): AgentTools.ToolResult {
        val pkg = get(ctx, id) ?: return AgentTools.ToolResult(false, "no skill '$id' — use skill_list")
        val updated = pkg.copy(enabled = enabled)
        updated.writeTo(ctx)
        dbUpsert(ctx, updated)
        return AgentTools.ToolResult(true,
            "skill ${pkg.id} ${if (enabled) "ENABLED — it re-enters the Known-skills context" else "DISABLED — excluded from context and refused by skill_run/skill_use"}")
    }

    fun delete(ctx: Context, id: String): AgentTools.ToolResult {
        val f = SkillPackage.fileOf(ctx, id)
        val fid = SkillPackage.filesDirOf(ctx, id)
        if (!f.isFile) return AgentTools.ToolResult(false, "no skill with id '$id' — use skill_list")
        val ok = f.delete()
        fid.deleteRecursively()
        dbDelete(ctx, SkillPackage.sanitizeId(id))
        return if (ok) AgentTools.ToolResult(true, "skill deleted: ${SkillPackage.sanitizeId(id)} (files + attachments + index)")
        else AgentTools.ToolResult(false, "could not delete the skill file")
    }

    fun approveScript(ctx: Context, id: String, scriptName: String, approved: Boolean): AgentTools.ToolResult {
        val pkg = get(ctx, id) ?: return AgentTools.ToolResult(false, "no skill '$id'")
        if (pkg.scripts.none { it.name == scriptName })
            return AgentTools.ToolResult(false, "skill '$id' has no script named '$scriptName' (has: ${pkg.scripts.joinToString { it.name }})")
        val updated = pkg.copy(scripts = pkg.scripts.map {
            if (it.name == scriptName) it.copy(approved = approved) else it
        })
        updated.writeTo(ctx)
        dbUpsert(ctx, updated)
        return AgentTools.ToolResult(true,
            "script '$scriptName' of '$id' ${if (approved) "APPROVED by the user (executable as part of this skill's steps only)" else "approval REVOKED"}")
    }

    fun scriptContent(ctx: Context, id: String, scriptName: String): String? {
        val pkg = get(ctx, id) ?: return null
        val ref = pkg.scripts.firstOrNull { it.name == scriptName } ?: return null
        val f = File(SkillPackage.filesDirOf(ctx, id), ref.path)
        return runCatching { f.takeIf { it.isFile }?.readText()?.take(8000) }.getOrNull()
    }

    // ================================================================ run gate (SECURITY)

    /**
     * Returns null = safe to run; otherwise a blocking ToolResult with the reason.
     * Order: exists → enabled → scripts approved → integrity (content hash).
     */
    fun runPreflight(ctx: Context, id: String): AgentTools.ToolResult? {
        val pkg = get(ctx, id)
            ?: return AgentTools.ToolResult(false, "no skill with id '$id' — use skill_list")
        if (!pkg.enabled)
            return AgentTools.ToolResult(false, "skill '$id' is DISABLED — enable it in the Skills Center (or skill_enable) before running")
        if (pkg.hasUnapprovedScripts)
            return AgentTools.ToolResult(false,
                "skill '$id' carries ${pkg.scripts.count { !it.approved }} UNAPPROVED script(s) — " +
                    "aMiNo never executes imported scripts automatically; the user must review and approve them in the Skills Center first")
        val row = dbGet(ctx, pkg.id)
        if (row != null) {
            val stored = row.optString("sha256", "")
            if (stored.isNotBlank() && stored != contentHash(pkg))
                return AgentTools.ToolResult(false,
                    "integrity check FAILED for '$id' — the skill file changed outside aMiNo since install. " +
                        "Re-save it from the Skills Center (Edit) to re-sign, or delete it.")
        }
        return null
    }

    /** Content hash = counters-independent integrity signature (insertion-order safe: built from parsed fields). */
    private fun contentHash(p: SkillPackage): String = SkillPackage.sha256(
        listOf(p.id, p.name, p.description, p.version, p.instructions, p.stepsJson,
            p.verify ?: "", p.paramsHint ?: "", p.category,
            p.scripts.joinToString("|") { "${it.name}:${it.approved}" },
            p.attachments.joinToString("|"), p.dependencies.joinToString("|"),
            p.requiredTools.joinToString("|")).joinToString("\u0000").toByteArray(Charsets.UTF_8))

    // ================================================================ export

    fun exportJson(ctx: Context, id: String): AgentTools.ToolResult {
        val pkg = get(ctx, id) ?: return AgentTools.ToolResult(false, "no skill '$id'")
        val dir = File(ctx.filesDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(dir, "${pkg.id}-v${pkg.version}.json")
        out.writeText(pkg.toJsonObject().toString(2))
        return AgentTools.ToolResult(true, "exported: ${out.absolutePath} (${out.length()} bytes) — share it from the Skills Center")
    }

    /** Re-importable ZIP: skill.json + SKILL.md rendering + carried files. */
    fun exportZip(ctx: Context, id: String, out: OutputStream): Boolean {
        val pkg = get(ctx, id) ?: return false
        try {
            ZipOutputStream(out).use { zos ->
                fun put(name: String, bytes: ByteArray) {
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
                put("skill.json", pkg.toJsonObject().toString(2).toByteArray(Charsets.UTF_8))
                val md = buildString {
                    appendLine("---")
                    appendLine("name: ${pkg.name}")
                    appendLine("description: ${pkg.description.replace('\n', ' ')}")
                    appendLine("version: ${pkg.version}")
                    if (pkg.author.isNotBlank()) appendLine("author: ${pkg.author}")
                    appendLine("category: ${pkg.category}")
                    if (pkg.tags.isNotEmpty()) appendLine("tags: ${pkg.tags.joinToString(", ")}")
                    appendLine("---")
                    appendLine()
                    if (pkg.instructions.isNotBlank()) append(pkg.instructions) else
                        appendLine("Deterministic aMiNo skill — run with skill_run {\"id\":\"${pkg.id}\"}. Steps: ${pkg.stepsJson.take(2000)}")
                }
                put("SKILL.md", md.toByteArray(Charsets.UTF_8))
                val fdir = SkillPackage.filesDirOf(ctx, pkg.id)
                fdir.walkTopDown().filter { it.isFile }.take(40).forEach {
                    put("files/${it.relativeTo(fdir).path.replace(File.separatorChar, '/')}", it.readBytes())
                }
            }
            return true
        } catch (e: Exception) { return false }
    }

    // ================================================================ DB index (AminoDb v3)

    fun dbUpsert(ctx: Context, p: SkillPackage) {
        runCatching {
            val cv = ContentValues().apply {
                put("id", p.id); put("name", p.name); put("version", p.version)
                put("category", p.category); put("description", p.description.take(300))
                put("enabled", if (p.enabled) 1 else 0)
                put("source_type", p.source.type)
                put("has_steps", if (p.hasSteps) 1 else 0)
                put("has_scripts", if (p.scripts.isNotEmpty()) 1 else 0)
                put("scripts_approved", if (p.scripts.all { it.approved }) 1 else 0)
                put("sha256", contentHash(p))
                put("tags", p.tags.joinToString(","))
                put("success", p.success); put("fail", p.fail)
                put("installed_at", p.created); put("updated_at", System.currentTimeMillis())
            }
            val w = AminoDb.get(ctx).writableDatabase
            w.insertWithOnConflict(AminoDb.T_SKILLS, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun dbDelete(ctx: Context, id: String) {
        runCatching { AminoDb.get(ctx).writableDatabase.delete(AminoDb.T_SKILLS, "id=?", arrayOf(id)) }
    }

    private fun dbGet(ctx: Context, id: String): JSONObject? = runCatching {
        AminoDb.get(ctx).readableDatabase.query(AminoDb.T_SKILLS, null, "id=?", arrayOf(id), null, null, null)
            ?.use { c -> if (c.moveToFirst()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) o.put(c.getColumnName(i), c.getString(i) ?: JSONObject.NULL)
                o
            } else null }
    }.getOrNull()

    /** DB is an index over the files — resync (call cheaply on UI open / skill_list). */
    fun reconcile(ctx: Context) {
        runCatching {
            val files = SkillPackage.dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
            val db = AminoDb.get(ctx).writableDatabase
            val ids = ArrayList<String>()
            db.query(AminoDb.T_SKILLS, arrayOf("id"), null, null, null, null, null)?.use { c ->
                while (c.moveToNext()) ids.add(c.getString(0))
            }
            ids.filter { it !in files }.forEach { db.delete(AminoDb.T_SKILLS, "id=?", arrayOf(it)) }
            val known = HashSet(files)
            known.addAll(ids)
            // index any file that has no row yet
            files.filter { f -> ids.none { it == f } }.forEach { fid ->
                SkillPackage.fileOf(ctx, fid).takeIf { it.isFile }?.let { f ->
                    SkillPackage.fromFile(f)?.let { dbUpsert(ctx, it) }
                }
            }
        }
    }

    /** DB-side counters mirror (file counters are bumped by SkillEngine.recordResult). */
    fun recordResult(ctx: Context, id: String, ok: Boolean) {
        runCatching {
            val db = AminoDb.get(ctx).writableDatabase
            val col = if (ok) "success" else "fail"
            db.execSQL("UPDATE ${AminoDb.T_SKILLS} SET $col = $col + 1, updated_at = ? WHERE id = ?",
                arrayOf<Any>(System.currentTimeMillis(), SkillPackage.sanitizeId(id)))
        }
    }
}
