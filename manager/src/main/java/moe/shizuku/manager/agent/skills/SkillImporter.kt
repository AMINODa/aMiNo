package moe.shizuku.manager.agent.skills

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * v1.4 SKILLS SYSTEM — the import/normalization layer.
 *
 * Converts EVERY supported external skill source into ONE internal candidate
 * (SkillImporter.ImportedSkill) which SkillManager.install() then normalizes
 * into the amino-skill/1 package:
 *
 *   - SKILL.md            (Agent-Skills frontmatter format: name/description/…)
 *   - JSON manifests      (flexible key mapping: name|title, description|desc, steps, …)
 *   - ZIP packages        (zip-slip guarded; finds SKILL.md / manifest.json / skill.json)
 *   - Local folders       (SAF OpenDocumentTree — DocumentsContract traversal)
 *   - GitHub URLs         (repo / blob / tree / raw — public api.github.com, no auth)
 *   - Pasted instructions (heuristic: frontmatter? JSON? plain playbook)
 *
 * SECURITY: imported scripts (files under a "scripts" dir or with .sh, .py, .js,
 * .bin extensions) are carried as DATA with approved=false — nothing here
 * executes anything. ZIP extraction is path-traversal guarded and size-capped (20 MB).
 */
object SkillImporter {

    private const val MAX_ZIP_BYTES = 20L * 1024 * 1024

    data class ImportedFile(val relPath: String, val bytes: ByteArray)

    data class ImportedSkill(
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val category: String,
        val tags: List<String>,
        val instructions: String,
        val steps: JSONArray?,
        val verify: String?,
        val paramsHint: String?,
        val requiredTools: List<String>,
        val dependencies: List<String>,
        val inputSchema: JSONObject?,
        val outputSchema: JSONObject?,
        val examples: JSONArray,
        val permissions: List<String>,
        val files: List<ImportedFile>,
        val changelog: List<SkillPackage.ChangelogEntry>,
        val sourceType: String,       // github | zip | file | folder | paste | manual
        val sourceUrl: String,
        val originalFormat: String    // skillmd | json | zip | folder | paste
    )

    data class GithubRepo(val owner: String, val repo: String, val description: String, val url: String)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ================================================================ SKILL.md

    /** Parses the Agent-Skills frontmatter format: --- key: value --- + markdown body. */
    fun fromSkillMd(text: String, sourceUrl: String): ImportedSkill {
        var fm = JSONObject()
        var body = text
        val t = text.trim()
        if (t.startsWith("---")) {
            val end = t.indexOf("\n---", 3)
            if (end > 0) {
                val header = t.substring(3, end).trim()
                body = t.substring(end + 4).trim()
                fm = frontmatterToJson(header)
            }
        }
        val name = fm.optString("name", "").ifBlank { firstHeadingOrLine(body, "imported-skill") }
        return ImportedSkill(
            name = name.take(60),
            description = fm.optString("description", "").take(400),
            version = fm.optString("version", "1.0.0"),
            author = fm.optString("author", fm.optString("license", "")).take(80),
            category = fm.optString("category", "imported"),
            tags = splitList(fm.opt("tags")),
            instructions = body.take(24_000),
            steps = null,   // SKILL.md is a playbook — the agent executes it with real tools
            verify = null,
            paramsHint = null,
            requiredTools = splitList(fm.opt("allowed-tools")),
            dependencies = emptyList(),
            inputSchema = null,
            outputSchema = null,
            examples = JSONArray(),
            permissions = emptyList(),
            files = emptyList(),
            changelog = listOf(SkillPackage.ChangelogEntry(fm.optString("version", "1.0.0"), "imported from SKILL.md", System.currentTimeMillis())),
            sourceType = if (sourceUrl.startsWith("http")) "github" else "file",
            sourceUrl = sourceUrl,
            originalFormat = "skillmd"
        )
    }

    /** Minimal top-level YAML frontmatter parser (key: value scalars only — honest limits). */
    private fun frontmatterToJson(header: String): JSONObject {
        val o = JSONObject()
        for (raw in header.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length - 1)
            if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\'')))
                value = value.substring(1, value.length - 1)
            if (key.isNotEmpty() && value.isNotEmpty()) o.put(key, value)
        }
        return o
    }

    private fun firstHeadingOrLine(text: String, fallback: String): String {
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("---")) continue
            return l.removePrefix("#").trim().take(60).ifBlank { fallback }
        }
        return fallback
    }

    private fun splitList(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is JSONArray -> stringListOf(v)
        else -> v.toString().split(',', ';', '[', ']').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ================================================================ JSON manifest

    /** Flexible JSON manifest mapping — tolerant of the common naming variants. */
    fun fromManifestJson(text: String, sourceUrl: String): ImportedSkill {
        val o = JSONObject(text.trim())
        val name = (o.optString("name", "").ifBlank { o.optString("title", "") })
            .ifBlank { "json-skill" }
        val instructions = when (val ins = o.opt("instructions") ?: o.opt("readme") ?: o.opt("playbook")) {
            is JSONArray -> (0 until ins.length()).joinToString("\n") { i -> ins.optString(i, "") }
            is String -> ins
            null -> ""
            else -> ins.toString()
        }
        val steps = o.optJSONArray("steps")
        val scriptsArr = o.optJSONArray("scripts")
        val files = ArrayList<ImportedFile>()
        if (scriptsArr != null) {
            for (i in 0 until scriptsArr.length()) {
                val s = scriptsArr.optJSONObject(i) ?: continue
                val sName = s.optString("name", "script-${i + 1}").replace('/', '_')
                val content = s.optString("content", s.optString("code", ""))
                if (content.isNotBlank()) files.add(ImportedFile("scripts/$sName", content.toByteArray(Charsets.UTF_8)))
            }
        }
        val changelog = ArrayList<SkillPackage.ChangelogEntry>()
        o.optJSONArray("changelog")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                changelog.add(SkillPackage.ChangelogEntry(
                    c.optString("version", "1.0.0"), c.optString("notes", ""), c.optLong("date", 0L)))
            }
        }
        return ImportedSkill(
            name = name.take(60),
            description = (o.optString("description", "").ifBlank { o.optString("desc", "") }).take(400),
            version = o.optString("version", "1.0.0"),
            author = o.optString("author", "").take(80),
            category = o.optString("category", "imported"),
            tags = stringListOf(o.optJSONArray("tags")),
            instructions = instructions.take(24_000),
            steps = steps,
            verify = o.optString("verify", "").ifBlank { null },
            paramsHint = (o.optString("params_hint", "").ifBlank { o.optString("params", "") }).ifBlank { null },
            requiredTools = stringListOf(o.optJSONArray("required_tools")),
            dependencies = stringListOf(o.optJSONArray("dependencies")),
            inputSchema = o.optJSONObject("input_schema"),
            outputSchema = o.optJSONObject("output_schema"),
            examples = o.optJSONArray("examples") ?: JSONArray(),
            permissions = stringListOf(o.optJSONArray("permissions")),
            files = files,
            changelog = changelog.ifEmpty { listOf(SkillPackage.ChangelogEntry(o.optString("version", "1.0.0"), "imported JSON manifest", System.currentTimeMillis())) },
            sourceType = if (sourceUrl.startsWith("http")) "github" else "file",
            sourceUrl = sourceUrl,
            originalFormat = "json"
        )
    }

    private fun stringListOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    // ================================================================ ZIP

    /**
     * Extracts a ZIP package. Path-traversal guarded (zip-slip) and size-capped.
     * Finds SKILL.md / manifest.json / skill.json at root or one dir deep; every
     * other file is carried as data (scripts stay approved=false).
     */
    fun fromZip(bytes: ByteArray): ImportedSkill {
        if (bytes.size > MAX_ZIP_BYTES) throw IllegalArgumentException("ZIP too large (${bytes.size} bytes, max 20MB)")
        val outDir = File(java.io.File.createTempFile("aminozip", "dir").parentFile, "amino_skill_zip_${System.currentTimeMillis()}")
        outDir.deleteRecursively(); outDir.mkdirs()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            var count = 0
            while (e != null && count < 500) {
                val f = File(outDir, e.name)
                if (!f.canonicalPath.startsWith(outDir.canonicalPath + File.separator) && f.canonicalPath != outDir.canonicalPath)
                    throw SecurityException("zip-slip blocked: ${e.name.take(80)}")
                if (e.isDirectory) { f.mkdirs() } else {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192); var n: Int
                    while (zis.read(buf).also { n = it } > 0) { bos.write(buf, 0, n); total += n; if (total > MAX_ZIP_BYTES) throw IllegalArgumentException("ZIP expands beyond 20MB — rejected") }
                    f.parentFile?.mkdirs()
                    f.writeBytes(bos.toByteArray()); count++
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
        val found = listOf("SKILL.md", "skill.md", "manifest.json", "skill.json", "package.json")
            .map { File(outDir, it) }.firstOrNull { it.isFile }
            ?: outDir.listFiles(File::isDirectory)?.flatMap { d ->
                listOf("SKILL.md", "skill.md", "manifest.json", "skill.json").map { File(d, it) }
            }?.firstOrNull { it.isFile }
        if (found == null) {
            val listing = outDir.listFiles()?.joinToString(", ") { it.name } ?: "(empty)"
            outDir.deleteRecursively()
            throw IllegalArgumentException("no SKILL.md / manifest.json / skill.json found in ZIP — root contains: ${listing.take(200)}")
        }
        val manifestBytes = found.readBytes()
        val base = if (found.name.equals("SKILL.md", true)) fromSkillMd(String(manifestBytes, Charsets.UTF_8), "")
                   else fromManifestJson(String(manifestBytes, Charsets.UTF_8), "")
        // carry siblings as data files
        val files = ArrayList<ImportedFile>()
        outDir.walkTopDown().filter { it.isFile && it != found }.forEach { f ->
            val relPath = f.relativeTo(outDir).path.replace(File.separatorChar, '/')
            if (relPath != found.relativeTo(outDir).path && files.size < 40)
                files.add(ImportedFile(relPath, f.readBytes()))
        }
        outDir.deleteRecursively()
        return base.copy(files = files, sourceType = "zip", originalFormat = "zip")
    }

    // ================================================================ local folder (SAF)

    /** Traverses a SAF directory tree (OpenDocumentTree) — same manifest discovery as ZIP. */
    fun fromFolder(ctx: Context, treeUri: Uri): ImportedSkill {
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        data class Entry(val name: String, val docId: String, val isDir: Boolean, val size: Long)

        fun children(docId: String): List<Entry> {
            val out = ArrayList<Entry>()
            val kidsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            cr.query(kidsUri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val szIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    val id = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    out.add(Entry(name, id, mime == DocumentsContract.Document.MIME_TYPE_DIR, if (szIdx >= 0) c.getLong(szIdx) else 0L))
                }
            }
            return out
        }

        fun readDoc(docId: String, limit: Long = 2L * 1024 * 1024): ByteArray? = runCatching {
            cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))?.use { ins ->
                val bos = ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int; var total = 0L
                while (ins.read(buf).also { n = it } > 0) { bos.write(buf, 0, n); total += n; if (total > limit) throw IllegalArgumentException("file exceeds 2MB read cap") }
                bos.toByteArray()
            }
        }.getOrNull()

        val root = children(rootId)
        val manifest = root.firstOrNull { it.name in listOf("SKILL.md", "skill.md") && !it.isDir }
            ?: root.firstOrNull { it.name in listOf("manifest.json", "skill.json", "package.json") && !it.isDir }
        if (manifest == null) throw IllegalArgumentException(
            "no SKILL.md / manifest.json / skill.json in that folder — it contains: ${root.joinToString(", ") { it.name }.take(200)}")
        val manifestBytes = readDoc(manifest.docId) ?: throw IllegalArgumentException("could not read ${manifest.name}")
        val base = if (manifest.name.endsWith(".md", true)) fromSkillMd(String(manifestBytes, Charsets.UTF_8), "")
                   else fromManifestJson(String(manifestBytes, Charsets.UTF_8), "")
        val files = ArrayList<ImportedFile>()
        root.filter { it != manifest && !it.isDir && it.name != manifest.name }.forEach { files.add(ImportedFile(it.name, readDoc(it.docId) ?: return@forEach)) }
        // one directory level deep (scripts/ data/ …)
        root.filter { it.isDir }.take(10).forEach { dirEntry ->
            children(dirEntry.docId).filter { !it.isDir }.take(20).forEach {
                files.add(ImportedFile("${dirEntry.name}/${it.name}", readDoc(it.docId, 512 * 1024) ?: return@forEach))
            }
        }
        return base.copy(files = files, sourceType = "folder", originalFormat = "folder")
    }

    // ================================================================ pasted instructions

    /** Heuristic paste normalizer: frontmatter? JSON? plain playbook text. */
    fun fromPaste(text: String): ImportedSkill {
        val t = text.trim()
        if (t.startsWith("---")) return fromSkillMd(t, "").copy(sourceType = "paste", originalFormat = "paste")
        if (t.startsWith("{")) return runCatching { fromManifestJson(t, "").copy(sourceType = "paste", originalFormat = "paste") }
            .getOrElse { plainPaste(t) }
        return plainPaste(t)
    }

    private fun plainPaste(text: String): ImportedSkill {
        val name = firstHeadingOrLine(text, "pasted-skill").removePrefix("#").trim().take(60)
        return ImportedSkill(
            name = name.ifBlank { "pasted-skill" },
            description = "skill from pasted instructions",
            version = "1.0.0", author = "", category = "imported",
            tags = emptyList(),
            instructions = text.take(24_000),
            steps = null, verify = null, paramsHint = null,
            requiredTools = emptyList(), dependencies = emptyList(),
            inputSchema = null, outputSchema = null,
            examples = JSONArray(), permissions = emptyList(),
            files = emptyList(),
            changelog = listOf(SkillPackage.ChangelogEntry("1.0.0", "from pasted instructions", System.currentTimeMillis())),
            sourceType = "paste", sourceUrl = "", originalFormat = "paste"
        )
    }

    // ================================================================ GitHub

    /**
     * GitHub import — public API, no auth, User-Agent required:
     *   https://github.com/{owner}/{repo}                       → SKILL.md at repo root
     *   https://github.com/{owner}/{repo}/blob/{br}/{path}      → that file (md/json)
     *   https://github.com/{owner}/{repo}/tree/{br}/{path}      → manifest discovery in that dir
     *   https://raw.githubusercontent.com/{o}/{r}/{br}/{path}   → raw fetch
     */
    fun fromGitHub(url: String): ImportedSkill {
        val u = url.trim().removeSuffix("/")
        val raw = Regex("""raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)""").find(u)
        if (raw != null) {
            val (owner, repo, branch, path) = raw.destructured
            val bytes = httpGet("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
                ?: throw IllegalArgumentException("GitHub raw fetch failed (check the URL/branch and internet)")
            val text = String(bytes, Charsets.UTF_8)
            return if (path.endsWith(".json", true)) fromManifestJson(text, url).copy(sourceType = "github", sourceUrl = url)
                   else fromSkillMd(text, url).copy(sourceType = "github", sourceUrl = url)
        }
        val m = Regex("""github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/(tree|blob)/([^/]+)((?:/.*)?))?/?$""").find(u)
            ?: throw IllegalArgumentException("not a recognized GitHub URL (repo, /blob/, or /tree/)")
        val (owner, repo, kind, branch, rest) = m.destructured
        val path = rest.removePrefix("/")
        val br = branch.ifBlank { defaultBranch(owner, repo) }
        if (kind == "blob" && path.isNotBlank()) {
            val bytes = contentsFile(owner, repo, path, br)
                ?: throw IllegalArgumentException("could not fetch $path from $owner/$repo@$br")
            val text = String(bytes, Charsets.UTF_8)
            return if (path.endsWith(".json", true)) fromManifestJson(text, url).copy(sourceType = "github", sourceUrl = url)
                   else fromSkillMd(text, url).copy(sourceType = "github", sourceUrl = url)
        }
        // repo root or /tree/ dir → manifest discovery
        val dirPath = if (kind == "tree" && path.isNotBlank()) path else ""
        val entries = contentsDir(owner, repo, dirPath, br)
            ?: throw IllegalArgumentException("could not list $owner/$repo@$br${if (dirPath.isNotBlank()) "/$dirPath" else ""}")
        val names = ArrayList<String>()
        val entriesList = ArrayList<JSONObject>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val n = e.optString("name", "")
            if (n.isNotEmpty()) names.add(n)
            entriesList.add(e)
        }
        val manifestName = names.firstOrNull { it in listOf("SKILL.md", "skill.md") }
            ?: names.firstOrNull { it in listOf("manifest.json", "skill.json", "package.json") }
            ?: throw IllegalArgumentException("no SKILL.md / manifest.json in ${if (dirPath.isBlank()) "repo root" else dirPath} — contains: ${names.take(12).joinToString(", ")}")
        val bytes = contentsFile(owner, repo, if (dirPath.isBlank()) manifestName else "$dirPath/$manifestName", br)
            ?: throw IllegalArgumentException("could not fetch $manifestName")
        val text = String(bytes, Charsets.UTF_8)
        val base = if (manifestName.endsWith(".md", true)) fromSkillMd(text, url).copy(sourceType = "github", sourceUrl = url)
                   else fromManifestJson(text, url).copy(sourceType = "github", sourceUrl = url)
        // sibling files (same dir, ≤ 15, ≤ 512KB each) travel as data
        val files = ArrayList<ImportedFile>()
        for (e in entriesList) {
            if (e.optString("type", "") != "file" || e.optString("name", "") == manifestName) continue
            if (files.size >= 15) break
            val rel = if (dirPath.isBlank()) e.optString("name") else "$dirPath/${e.optString("name")}"
            runCatching { contentsFile(owner, repo, rel, br) }.getOrNull()?.let { files.add(ImportedFile(rel, it)) }
        }
        return base.copy(files = files)
    }

    /** Real GitHub repository search for the Discover flow (top 10, unauthenticated). */
    fun githubSearch(query: String): List<GithubRepo> {
        val q = java.net.URLEncoder.encode(query.take(120), "UTF-8")
        val text = httpGetText("https://api.github.com/search/repositories?q=$q&per_page=10&sort=stars")
            ?: throw IllegalArgumentException("GitHub search failed (rate limit or internet)")
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<GithubRepo>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.add(GithubRepo(
                it.optJSONObject("owner")?.optString("login", "") ?: "",
                it.optString("name", ""), it.optString("description", ""), it.optString("html_url", "")))
        }
        return out
    }

    private fun defaultBranch(owner: String, repo: String): String =
        runCatching {
            JSONObject(httpGetText("https://api.github.com/repos/$owner/$repo") ?: return "main").optString("default_branch", "main")
        }.getOrDefault("main")

    private fun contentsFile(owner: String, repo: String, path: String, ref: String): ByteArray? {
        val text = httpGetText("https://api.github.com/repos/$owner/$repo/contents/${java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/")}?ref=$ref")
            ?: return null
        val o = JSONObject(text)
        if (o.optString("encoding", "") == "base64") return Base64.decode(o.optString("content", ""), Base64.DEFAULT)
        return o.optString("download_url", "").takeIf { it.isNotBlank() }?.let { httpGet(it) }
    }

    private fun contentsDir(owner: String, repo: String, path: String, ref: String): JSONArray? {
        val p = if (path.isBlank()) "" else "/" + java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/")
        val text = httpGetText("https://api.github.com/repos/$owner/$repo/contents$p?ref=$ref") ?: return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    private fun httpGet(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "aMiNo-Skills")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }.getOrNull()

    private fun httpGetText(url: String): String? = httpGet(url)?.let { String(it, Charsets.UTF_8) }
}
