package moe.shizuku.manager.agent.skills

import android.content.Context
import kotlinx.coroutines.delay
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.agent.TerminalTools
import moe.shizuku.manager.agent.perception.ScreenPerception
import moe.shizuku.manager.terminal.linux.ShizukuExec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3 SUPER AGENT — Skill Engine (the AppAgentX school adapted on-device:
 * Explore -> Learn -> Store -> Reuse).
 *
 * When a multi-step procedure succeeds, the model converts it into a SKILL: a
 * small parameterized JSON file stored in the app's private storage. Next time
 * the same goal appears, the model runs the skill in ONE call instead of
 * re-exploring — the same evolution loop AppAgentX formalizes, but as plain
 * local files + a deterministic executor (no model retraining, no cloud).
 *
 * A skill step is ONE of:
 *   {"type":"shell","cmd":"...", "timeout_seconds":25, "optional":false}
 *       — Android HOST command through the aMiNo service (Shizuku shell uid)
 *   {"type":"terminal","cmd":"...", "timeout_seconds":20, "optional":false}
 *       — inside the agent's default terminal environment (Debian when installed),
 *         routed through the same CommandValidator as terminal_execute
 *   {"type":"input","action":"tap|longtap|swipe|text|key|wait", ...screen_act fields}
 *   {"type":"perceive","max_elements":30}   — a screen_read snapshot in the log
 *   {"type":"wait","ms":500}
 *
 * "{param}" placeholders in cmd/text/verify are substituted from the run params.
 * Execution is fail-fast and honest: the first failing step stops the skill and
 * the FULL per-step log (rc + output tail) is returned, success counters update,
 * and a verify command (rc==0) is the final gate when provided.
 */
object SkillEngine {

    private const val DIR = "skills"
    private val STEP_TYPES = setOf("shell", "terminal", "input", "perceive", "wait")

    data class Skill(
        val id: String, val title: String, val trigger: String, val stepsJson: String,
        val verify: String?, val paramsHint: String?, val success: Int, val fail: Int,
        val created: Long, val lastUsed: Long
    )

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun fileOf(ctx: Context, id: String): File = File(dir(ctx), "${sanitizeId(id)}.json")

    private fun sanitizeId(id: String) =
        id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
            .joinToString("").trim('-').take(64).ifBlank { "skill" }

    private fun fromJson(o: JSONObject): Skill? = runCatching {
        Skill(
            o.getString("id"), o.getString("title"), o.optString("trigger", ""),
            o.getString("steps"), o.optString("verify", "").ifBlank { null },
            o.optString("params_hint", "").ifBlank { null },
            o.optInt("success", 0), o.optInt("fail", 0),
            o.optLong("created", 0L), o.optLong("last_used", 0L)
        )
    }.getOrNull()

    // -------------------------------------------------------------- store

    fun all(ctx: Context): List<Skill> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(50)
            ?.mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?: emptyList()

    fun get(ctx: Context, id: String): Skill? =
        fileOf(ctx, id).takeIf { it.isFile }
            ?.let { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }

    fun save(
        ctx: Context, title: String, trigger: String, stepsJson: String,
        verify: String?, paramsHint: String?
    ): AgentTools.ToolResult {
        if (title.isBlank()) return AgentTools.ToolResult(false, "skill_save requires a title")
        val steps = try {
            val arr = JSONArray(stepsJson.trim())
            if (arr.length() == 0) return AgentTools.ToolResult(false, "steps_json must be a NON-empty JSON array")
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i)
                    ?: return AgentTools.ToolResult(false, "steps_json[$i] is not a JSON object")
                val type = st.optString("type", "")
                if (type !in STEP_TYPES)
                    return AgentTools.ToolResult(false,
                        "steps_json[$i].type='$type' is invalid — use shell | terminal | input | perceive | wait")
                when (type) {
                    "shell", "terminal" -> if (st.optString("cmd", "").isBlank())
                        return AgentTools.ToolResult(false, "steps_json[$i] ($type) requires cmd")
                    "input" -> if (st.optString("action", "").isBlank())
                        return AgentTools.ToolResult(false, "steps_json[$i] (input) requires action")
                }
            }
            arr
        } catch (e: Exception) {
            return AgentTools.ToolResult(false, "steps_json is not valid JSON: ${e.message?.take(150)}")
        }

        val id = sanitizeId(title) + "-" + (System.currentTimeMillis() / 1000 % 100000)
        val obj = JSONObject()
            .put("id", id)
            .put("title", title.trim().take(80))
            .put("trigger", trigger.trim().take(200))
            .put("steps", steps)
            .put("verify", verify?.trim().takeUnless { it.isNullOrBlank() } ?: JSONObject.NULL)
            .put("params_hint", paramsHint?.trim().takeUnless { it.isNullOrBlank() } ?: JSONObject.NULL)
            .put("success", 0).put("fail", 0)
            .put("created", System.currentTimeMillis()).put("last_used", 0L)
        fileOf(ctx, id).writeText(obj.toString(2))
        return AgentTools.ToolResult(
            true,
            "skill saved: id=$id (${steps.length()} steps) — reuse with skill_run {\"id\":\"$id\"}. " +
                "It now appears in the Known-skills context of every future session."
        )
    }

    fun delete(ctx: Context, id: String): AgentTools.ToolResult {
        val f = fileOf(ctx, id)
        return if (f.isFile && f.delete()) AgentTools.ToolResult(true, "skill deleted: $id")
        else AgentTools.ToolResult(false, "no skill with id '$id' — use skill_list")
    }

    private fun recordResult(ctx: Context, sk: Skill, ok: Boolean) {
        runCatching {
            val f = fileOf(ctx, sk.id)
            val o = JSONObject(f.readText())
                .put(if (ok) "success" else "fail", (if (ok) sk.success else sk.fail) + 1)
                .put("last_used", System.currentTimeMillis())
            f.writeText(o.toString(2))
        }
    }

    // -------------------------------------------------------------- execute

    /** Substitutes "{key}" placeholders from params (missing keys stay literal). */
    private fun subst(t: String, params: JSONObject): String {
        var out = t
        for (k in params.keys()) out = out.replace("{$k}", params.optString(k, "{$k}"))
        return out
    }

    suspend fun run(ctx: Context, id: String, paramsRaw: JSONObject?): AgentTools.ToolResult {
        val sk = get(ctx, id)
            ?: return AgentTools.ToolResult(false, "no skill with id '$id' — use skill_list")
        val params = paramsRaw ?: JSONObject()
        val steps = try { JSONArray(sk.stepsJson) } catch (e: Exception) {
            return AgentTools.ToolResult(false, "skill '$id' has corrupt steps: ${e.message?.take(150)}")
        }

        val log = StringBuilder("SKILL ${sk.id} — ${sk.title}\n")
        var stopped: String? = null

        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            val type = st.optString("type", "")
            val optional = st.optBoolean("optional", false)
            when (type) {
                "shell" -> {
                    val cmd = subst(st.optString("cmd", ""), params)
                    log.append("step ${i + 1} [shell-host] $ ").append(cmd.replace('\n', ' ')).append('\n')
                    val s = ShizukuExec.oneShot(
                        cmd, st.optInt("timeout_seconds", 25).coerceIn(1, 120) * 1000L)
                    log.append("  rc=${s.rc} ").append(s.output.trim().take(300)).append('\n')
                    if (s.rc != 0 && !optional) {
                        stopped = "step ${i + 1} (shell) failed rc=${s.rc}"
                        break
                    }
                }
                "terminal" -> {
                    val cmd = subst(st.optString("cmd", ""), params)
                    log.append("step ${i + 1} [terminal] $ ").append(cmd.replace('\n', ' ')).append('\n')
                    val r = TerminalTools.execute(
                        ctx, "", cmd, st.optInt("timeout_seconds", 20).coerceIn(1, 120))
                    log.append("  ok=${r.ok} ").append(r.output.take(300)).append('\n')
                    if (!r.ok && !optional) {
                        stopped = "step ${i + 1} (terminal) failed"
                        break
                    }
                }
                "input" -> {
                    log.append("step ${i + 1} [input] ").append(st.toString().take(120)).append('\n')
                    val r = ScreenPerception.act(st)
                    log.append("  ok=${r.ok} ").append(r.output.take(200)).append('\n')
                    if (!r.ok && !optional) {
                        stopped = "step ${i + 1} (input) failed: ${r.output.take(120)}"
                        break
                    }
                }
                "perceive" -> {
                    val r = ScreenPerception.read(st.optInt("max_elements", 30))
                    log.append("step ${i + 1} [perceive]\n").append(r.output.take(900)).append('\n')
                }
                "wait" -> {
                    delay(st.optInt("ms", 500).coerceIn(100, 10_000).toLong())
                    log.append("step ${i + 1} [wait]\n")
                }
            }
        }

        var verified = true
        if (stopped == null && !sk.verify.isNullOrBlank()) {
            val cmd = subst(sk.verify, params)
            val s = ShizukuExec.oneShot(cmd, 25_000)
            verified = s.rc == 0
            log.append("verify [$cmd] rc=${s.rc} ").append(s.output.trim().take(200)).append('\n')
        }

        val ok = stopped == null && verified
        recordResult(ctx, sk, ok)
        log.append("SKILL RESULT: ").append(if (ok) "SUCCESS" else "FAILED")
        if (stopped != null) log.append(" — ").append(stopped)
        if (sk.paramsHint != null) log.append("\nparams: ").append(sk.paramsHint)
        return AgentTools.ToolResult(ok, log.toString().take(6000))
    }
}
