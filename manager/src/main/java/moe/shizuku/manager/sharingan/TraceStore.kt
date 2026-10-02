package moe.shizuku.manager.sharingan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Trace storage — append-only JSONL, one file per trace.
 *
 * Files are the truth (the same principle as the v1.4 skills system):
 *   filesDir/sharingan/<trace_id>.jsonl
 * Each line = one real captured frame:
 *   {"t":seconds_since_start,"hash":"md5","activity":"...","text":"..."}
 * Nothing is written that was not really captured in this session.
 */
object TraceStore {

    data class Entry(
        val tSec: Double,
        val frameHash: String,
        val activity: String?,
        val screenText: String?
    )

    @Volatile
    var lastId: String? = null
        private set

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "sharingan").apply { mkdirs() }

    private fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.jsonl")

    fun exists(ctx: Context, id: String): Boolean = file(ctx, id).exists()

    /** Start a new recording trace. Returns the id. */
    fun start(ctx: Context): String {
        val id = "trace_" + java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US
        ).format(java.util.Date())
        file(ctx, id).writeText("")
        lastId = id
        return id
    }

    /** Create a single-frame trace (the Execute button live snapshot). */
    suspend fun snapshot(ctx: Context, frame: ScreenCapture.Frame): String {
        val id = "snapshot_" + System.currentTimeMillis()
        val f = file(ctx, id)
        val o = JSONObject()
            .put("t", 0.0)
            .put("hash", frame.hash)
            .put("activity", frame.activity ?: "")
            .put("text", ScreenCapture.screenText() ?: "")
        f.writeText(o.toString() + "\n")
        lastId = id
        return id
    }

    /** Append one real captured entry. */
    fun append(ctx: Context, id: String, tSec: Double, frame: ScreenCapture.Frame, text: String?) {
        val o = JSONObject()
            .put("t", tSec)
            .put("hash", frame.hash)
            .put("activity", frame.activity ?: "")
            .put("text", (text ?: "").take(2000))
        file(ctx, id).appendText(o.toString() + "\n")
    }

    /** Close the current trace; returns its id. */
    fun stop(ctx: Context, id: String): String? {
        val f = file(ctx, id)
        lastId = if (f.exists()) id else null
        return lastId
    }

    fun read(ctx: Context, id: String): List<Entry> {
        val f = file(ctx, id) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(
                    tSec = o.optDouble("t", 0.0),
                    frameHash = o.optString("hash"),
                    activity = o.optString("activity").ifBlank { null },
                    screenText = o.optString("text").ifBlank { null }
                )
            }.getOrNull()
        }
    }

    /**
     * [SHARINGAN CONTEXT] block for the agent prompt — built ONLY from real
     * stored entries. Includes the trace id so the agent can cite it and a
     * VERIFY hint (re-read the screen before acting on stale context).
     */
    fun buildContextBlock(ctx: Context, id: String, lastN: Int = 5): String {
        val entries = read(ctx, id)
        if (entries.isEmpty()) {
            // Honest empty case: the trace exists but holds no real frames.
            return "[SHARINGAN CONTEXT] trace_id=$id — trace file exists but contains NO captured frames. Do not assume any screen content from it."
        }
        val sb = StringBuilder()
        sb.appendLine("[SHARINGAN CONTEXT] trace_id=$id (${entries.size} real frame(s) captured by Sharingan Live):")
        entries.takeLast(lastN).forEach { e ->
            sb.appendLine("- t=${e.tSec}s activity=${e.activity ?: "?"}")
            if (!e.screenText.isNullOrBlank()) {
                sb.appendLine("  screen_text: \"${e.screenText.take(600)}\"")
            }
        }
        sb.appendLine("NOTE: this context may be STALE by the time you act — screen_read to verify the CURRENT screen before any UI action. Cite trace_id when you use it.")
        return sb.toString().trim()
    }

    /** List stored traces (newest first), for the future trace_use tool. */
    fun list(ctx: Context): List<Pair<String, Int>> =
        dir(ctx).listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".jsonl") to it.readLines().count { l -> l.isNotBlank() } }
            ?: emptyList()

    /** JSON export (used later by the 1.6 Trace→Skill generator). */
    fun exportJson(ctx: Context, id: String): String {
        val arr = JSONArray()
        read(ctx, id).forEach { e ->
            arr.put(JSONObject()
                .put("t", e.tSec)
                .put("hash", e.frameHash)
                .put("activity", e.activity ?: "")
                .put("text", e.screenText ?: ""))
        }
        return arr.toString(2)
    }
}
