package moe.shizuku.manager.agent.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * v1.4 SKILLS SYSTEM — the rich internal skill package ("amino-skill/1").
 *
 * Every skill — imported (SKILL.md / JSON manifest / ZIP / folder / GitHub URL /
 * pasted text) or created manually or captured from a successful workflow — is
 * normalized into THIS format and stored as one JSON file in the SAME directory
 * the v1.3 SkillEngine already scans (<filesDir>/skills/<id>.json), so the proven
 * deterministic executor keeps working unchanged on new packages. Legacy v1.3
 * files are wrapped in memory as first-class packages (never rewritten behind
 * the user's back); they convert to this format only when edited in the Skills
 * Center.
 *
 * SECURITY MODEL: imported scripts are stored under <filesDir>/skills/<id>_files/
 * and referenced here with approved=false. NOTHING ever executes them
 * automatically — the user must review and approve each script in the Skills
 * Center; the run preflight (SkillManager.runPreflight) blocks runs of skills
 * with unapproved scripts, and scripts never gain any execution path of their
 * own (they are data, not code — the only executors remain SkillEngine's
 * shell/terminal/input steps, themselves gated by Shizuku availability and the
 * CommandValidator).
 */
data class SkillPackage(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val category: String,
    val tags: List<String>,
    val enabled: Boolean,
    val trigger: String,
    val paramsHint: String?,
    val instructions: String,
    val stepsJson: String,          // JSONArray string — same step schema as SkillEngine
    val verify: String?,
    val requiredTools: List<String>,
    val dependencies: List<String>,
    val inputSchema: JSONObject?,
    val outputSchema: JSONObject?,
    val examples: JSONArray,
    val permissions: List<String>,
    val scripts: List<ScriptRef>,
    val attachments: List<String>,
    val changelog: List<ChangelogEntry>,
    val source: SourceInfo,
    val success: Int,
    val fail: Int,
    val created: Long,
    val lastUsed: Long
) {

    data class ScriptRef(val name: String, val path: String, val approved: Boolean)
    data class ChangelogEntry(val version: String, val notes: String, val date: Long)
    data class SourceInfo(val type: String, val url: String, val originalFormat: String, val importedAt: Long)

    val hasSteps: Boolean
        get() = runCatching { JSONArray(stepsJson).length() > 0 }.getOrDefault(false)

    val hasUnapprovedScripts: Boolean get() = scripts.any { !it.approved }

    fun summaryLine(): String =
        "$id «$name» v$version [$category] — ${description.take(90)}"

    /** Serializes to the canonical "amino-skill/1" file format. */
    fun toJsonObject(): JSONObject {
        val o = JSONObject()
            .put("format", FORMAT)
            .put("format_version", FORMAT_VERSION)
            .put("id", id)
            .put("name", name)
            .put("description", description)
            .put("version", version)
            .put("author", author)
            .put("category", category)
            .put("tags", JSONArray(tags))
            .put("enabled", enabled)
            .put("trigger", trigger)
            .put("instructions", instructions)
            .put("steps", JSONArray(stepsJson))
            .put("required_tools", JSONArray(requiredTools))
            .put("dependencies", JSONArray(dependencies))
            .put("examples", examples)
            .put("permissions", JSONArray(permissions))
            .put("source", JSONObject()
                .put("type", source.type)
                .put("url", source.url)
                .put("original_format", source.originalFormat)
                .put("imported_at", source.importedAt))
            .put("success", success).put("fail", fail)
            .put("created", created).put("last_used", lastUsed)
        paramsHint?.let { o.put("params_hint", it) }
        verify?.let { o.put("verify", it) }
        inputSchema?.let { o.put("input_schema", it) }
        outputSchema?.let { o.put("output_schema", it) }
        if (scripts.isNotEmpty()) {
            val arr = JSONArray()
            scripts.forEach { arr.put(JSONObject().put("name", it.name).put("path", it.path).put("approved", it.approved)) }
            o.put("scripts", arr)
        }
        if (attachments.isNotEmpty()) o.put("attachments", JSONArray(attachments))
        if (changelog.isNotEmpty()) {
            val arr = JSONArray()
            changelog.forEach { arr.put(JSONObject().put("version", it.version).put("notes", it.notes).put("date", it.date)) }
            o.put("changelog", arr)
        }
        return o
    }

    /** Writes the package file (the ONLY writer for the new format). */
    fun writeTo(ctx: Context) {
        fileOf(ctx, id).writeText(toJsonObject().toString(2))
    }

    companion object {
        const val FORMAT = "amino-skill"
        const val FORMAT_VERSION = 1
        const val DIR = "skills"

        // Same naming rules as SkillEngine — one shared directory, one shared id space.
        fun sanitizeId(id: String) =
            id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
                .joinToString("").trim('-').take(64).ifBlank { "skill" }

        fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
        fun fileOf(ctx: Context, id: String): File = File(dir(ctx), "${sanitizeId(id)}.json")
        fun filesDirOf(ctx: Context, id: String): File = File(dir(ctx), "${sanitizeId(id)}_files")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        // -------------------------------------------------------------- parsing

        /** Dual-format loader: "amino-skill/1" packages + legacy v1.3 skill files. */
        fun fromFile(f: File): SkillPackage? = runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("format", "") == FORMAT) fromJson(o) else legacyFrom(o)
        }.getOrNull()

        fun fromJson(o: JSONObject): SkillPackage {
            val src = o.optJSONObject("source")
            val scripts = ArrayList<ScriptRef>()
            o.optJSONArray("scripts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    scripts.add(ScriptRef(s.optString("name", "script"), s.optString("path", ""), s.optBoolean("approved", false)))
                }
            }
            val changelog = ArrayList<ChangelogEntry>()
            o.optJSONArray("changelog")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    changelog.add(ChangelogEntry(c.optString("version", "1.0.0"), c.optString("notes", ""), c.optLong("date", 0L)))
                }
            }
            return SkillPackage(
                id = o.getString("id"),
                name = o.optString("name", o.optString("id", "skill")),
                description = o.optString("description", ""),
                version = o.optString("version", "1.0.0"),
                author = o.optString("author", ""),
                category = o.optString("category", "custom"),
                tags = stringList(o.optJSONArray("tags")),
                enabled = o.optBoolean("enabled", true),
                trigger = o.optString("trigger", ""),
                paramsHint = o.optString("params_hint", "").ifBlank { null },
                instructions = o.optString("instructions", ""),
                stepsJson = (o.optJSONArray("steps") ?: JSONArray()).toString(),
                verify = o.optString("verify", "").ifBlank { null },
                requiredTools = stringList(o.optJSONArray("required_tools")),
                dependencies = stringList(o.optJSONArray("dependencies")),
                inputSchema = o.optJSONObject("input_schema"),
                outputSchema = o.optJSONObject("output_schema"),
                examples = o.optJSONArray("examples") ?: JSONArray(),
                permissions = stringList(o.optJSONArray("permissions")),
                scripts = scripts,
                attachments = stringList(o.optJSONArray("attachments")),
                changelog = changelog,
                source = SourceInfo(
                    src?.optString("type", "manual") ?: "manual",
                    src?.optString("url", "") ?: "",
                    src?.optString("original_format", "manual") ?: "manual",
                    src?.optLong("imported_at", 0L) ?: 0L),
                success = o.optInt("success", 0),
                fail = o.optInt("fail", 0),
                created = o.optLong("created", 0L),
                lastUsed = o.optLong("last_used", 0L)
            )
        }

        /** Legacy v1.3 file → wrapped as a first-class package (file itself untouched). */
        private fun legacyFrom(o: JSONObject): SkillPackage = SkillPackage(
            id = o.getString("id"),
            name = o.optString("title", o.getString("id")),
            description = "v1.3 skill (legacy format) — saved workflow",
            version = "1.3-legacy",
            author = "",
            category = "custom",
            tags = emptyList(),
            enabled = true,
            trigger = o.optString("trigger", ""),
            paramsHint = o.optString("params_hint", "").ifBlank { null },
            instructions = "",
            stepsJson = o.get("steps").toString(),
            verify = o.optString("verify", "").ifBlank { null },
            requiredTools = emptyList(),
            dependencies = emptyList(),
            inputSchema = null,
            outputSchema = null,
            examples = JSONArray(),
            permissions = emptyList(),
            scripts = emptyList(),
            attachments = emptyList(),
            changelog = emptyList(),
            source = SourceInfo("workflow", "", "legacy-1.3", o.optLong("created", 0L)),
            success = o.optInt("success", 0),
            fail = o.optInt("fail", 0),
            created = o.optLong("created", 0L),
            lastUsed = o.optLong("last_used", 0L)
        )

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}
