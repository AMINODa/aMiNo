package moe.shizuku.manager.agent.skills

import android.content.Context
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.agent.ToolRegistry
import moe.shizuku.manager.terminal.linux.ShizukuExec
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.4 SKILLS SYSTEM — the runtime router / execution coordinator.
 *
 * Two execution styles, both REAL:
 *
 *  1. playbook (skill_use)  — the skill carries INSTRUCTIONS instead of steps;
 *     the router loads them into the agent's context together with an honest
 *     availability report (required tools, shell state, dependencies) and the
 *     agent then executes the instructions with its own verified tools.
 *
 *  2. deterministic (skill_run / skill_workflow) — the skill carries steps;
 *     SkillEngine executes them fail-fast through the proven channels
 *     (Shizuku host shell / Debian terminal / screen input). The workflow
 *     coordinator composes SEVERAL skills in one task: dependency-aware
 *     ordering, structured output passing ({N.output} substitution), per-step
 *     retry, and a per-step honest status report.
 *
 * Composition contract (skill_workflow steps JSON):
 *   [{"skill":"wifi-open","params":{"ssid":"Home"}}, {"skill":"report","params":{"note":"{0.output}"}}]
 *   step fields: skill (required), params (object), optional (bool), retries (int, default 1)
 *   params of step N may reference the (truncated) output of earlier step j via "{j.output}".
 */
object SkillRouter {

    // ================================================================ availability

    /** Honest per-tool availability report for a package's requirements. */
    fun availabilityReport(ctx: Context, pkg: SkillPackage): String {
        val sb = StringBuilder()
        if (pkg.requiredTools.isEmpty()) return "no specific tool requirements declared"
        for (name in pkg.requiredTools) {
            val tool = ToolRegistry.get(name)
            when {
                tool == null -> sb.append("- $name: MISSING (not a registered tool)")
                tool.requiresShell -> sb.append("- $name: ${if (ShizukuExec.available()) "available" else "UNAVAILABLE (shell/service not connected)"}")
                else -> sb.append("- $name: registered (in-app)")
            }
            sb.append('\n')
        }
        if (pkg.dependencies.isNotEmpty()) {
            sb.append("dependencies: ")
            sb.append(pkg.dependencies.joinToString(", ") { d ->
                val dp = SkillManager.get(ctx, d)
                "$d" + when {
                    dp == null -> "(NOT INSTALLED)"
                    !dp.enabled -> "(installed but DISABLED)"
                    else -> "(ok)"
                }
            })
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // ================================================================ skill_use — playbook loader

    /** Loads a skill's instructions + real availability into the agent's hands. */
    fun playbook(ctx: Context, id: String, params: JSONObject?): AgentTools.ToolResult {
        val gate = SkillManager.runPreflight(ctx, id)
        if (gate != null) return gate
        val pkg = SkillManager.get(ctx, id)!!
        val out = StringBuilder()
        out.append("SKILL PLAYBOOK: ${pkg.name} (id=${pkg.id}, v${pkg.version}, category=${pkg.category})\n")
        if (pkg.description.isNotBlank()) out.append("about: ${pkg.description}\n")
        if (pkg.paramsHint != null) out.append("params hint: ${pkg.paramsHint}\n")
        if (params != null && params.length() > 0) out.append("run params: ${params.toString().take(500)}\n")
        out.append("\nTOOL AVAILABILITY (real, right now):\n${availabilityReport(ctx, pkg)}\n")
        if (pkg.scripts.isNotEmpty()) {
            out.append("\nscripts (DATA — approved: ${pkg.scripts.count { it.approved }}/${pkg.scripts.size}): ")
            out.append(pkg.scripts.joinToString(", ") { "${it.name}${if (it.approved) "✓" else "⚠not-approved"}" })
            out.append('\n')
        }
        out.append("\nINSTRUCTIONS:\n")
        out.append(if (pkg.instructions.isNotBlank()) pkg.instructions.take(7000)
            else "This skill has no playbook text — it carries ${if (pkg.hasSteps) "deterministic steps: run it with skill_run instead" else "nothing executable; treat the description as the goal"}.")
        out.append("\n\nEXECUTION RULES: follow the instructions with your real tools step by step; VERIFY each step (screen_read after UI actions, exit codes for commands); never claim success without verification; pass intermediate results forward.")
        return AgentTools.ToolResult(true, out.toString().take(8500))
    }

    // ================================================================ skill_workflow — composition coordinator

    fun workflow(ctx: Context, stepsJson: String): AgentTools.ToolResult {
        val steps = try { JSONArray(stepsJson.trim()) } catch (e: Exception) {
            return AgentTools.ToolResult(false, "steps is not valid JSON: ${e.message?.take(150)}")
        }
        if (steps.length() == 0) return AgentTools.ToolResult(false, "steps must be a NON-empty JSON array")
        if (steps.length() > 10) return AgentTools.ToolResult(false, "a workflow composes at most 10 skills (got ${steps.length()})")

        // resolve + validate everything BEFORE running anything (all-or-nothing gate)
        data class WStep(val idx: Int, val skill: SkillPackage, val params: JSONObject, val optional: Boolean, val retries: Int)
        val parsed = ArrayList<WStep>()
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i)
                ?: return AgentTools.ToolResult(false, "steps[$i] is not a JSON object")
            val sid = s.optString("skill", "").trim()
            if (sid.isBlank()) return AgentTools.ToolResult(false, "steps[$i] missing 'skill' id")
            val pkg = SkillManager.get(ctx, sid)
                ?: return AgentTools.ToolResult(false, "steps[$i]: skill '$sid' is not installed (check skill_search)")
            if (!pkg.enabled) return AgentTools.ToolResult(false, "steps[$i]: skill '$sid' is DISABLED — enable it first (Skills Center)")
            if (pkg.hasUnapprovedScripts) return AgentTools.ToolResult(false,
                "steps[$i]: skill '$sid' holds UNAPPROVED scripts — user must approve them in the Skills Center")
            if (!pkg.hasSteps) return AgentTools.ToolResult(false,
                "steps[$i]: skill '$sid' is a PLAYBOOK skill (no deterministic steps) — a workflow needs executable skills; handle it interactively with skill_use")
            parsed.add(WStep(i, pkg, s.optJSONObject("params") ?: JSONObject(), s.optBoolean("optional", false), s.optInt("retries", 1).coerceIn(1, 3)))
        }

        val missingDeps = parsed.flatMap { ws -> ws.skill.dependencies.filter { d -> parsed.none { it.skill.id == d } && SkillManager.get(ctx, d) == null } }
        if (missingDeps.isNotEmpty())
            return AgentTools.ToolResult(false, "workflow blocked — missing dependencies: ${missingDeps.distinct().joinToString(", ")}")

        // stable topological order: honor the given order, but a step never runs before
        // a step providing one of its declared dependencies (cycle → honest block)
        val remaining = parsed.toMutableList()
        val ordered = ArrayList<WStep>()
        val doneIds = HashSet<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { ws ->
                ws.skill.dependencies.all { it in doneIds || parsed.none { p -> p.skill.id == it } }
            }
            if (next == null)
                return AgentTools.ToolResult(false,
                    "workflow blocked — circular/unsatisfied dependencies among steps: ${remaining.joinToString(", ") { it.skill.id }}")
            remaining.remove(next); ordered.add(next); doneIds.add(next.skill.id)
        }

        // run sequentially with structured output passing + retry
        val log = StringBuilder("WORKFLOW — ${parsed.size} skill(s)\n")
        val results = JSONArray()
        var allOk = true
        val started = System.currentTimeMillis()
        for (ws in ordered) {
            // {j.output} substitution from earlier results (j = 0-based idx or 1-based step number)
            val params = JSONObject()
            for (k in ws.params.keys()) {
                var v = ws.params.optString(k)
                for (e in results.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }) {
                    if (!e.optBoolean("ok", false)) continue
                    val tail = e.optString("output_tail", "")
                    v = v.replace("{${e.optInt("step") - 1}.output}", tail)
                    v = v.replace("{${e.optInt("step")}.output}", tail)
                }
                params.put(k, v)
            }
            var attempt = 0
            var r: AgentTools.ToolResult? = null
            while (attempt <= ws.retries) {
                attempt++
                r = kotlinx.coroutines.runBlocking {
                    moe.shizuku.manager.agent.skills.SkillEngine.run(ctx, ws.skill.id, params)
                }
                if (r.ok) break
                if (attempt <= ws.retries) log.append("  step ${ws.idx + 1} attempt $attempt failed — retrying\n")
            }
            val ok = r?.ok ?: false
            SkillManager.recordResult(ctx, ws.skill.id, ok)
            val entry = JSONObject()
                .put("step", ws.idx + 1).put("skill", ws.skill.id)
                .put("ok", ok).put("attempts", attempt)
                .put("output_tail", (r?.output ?: "").take(700))
            results.put(entry)
            log.append("step ${ws.idx + 1} [${ws.skill.id}] ")
                .append(if (ok) "OK" else "FAILED")
                .append(" (attempt $attempt)\n  ").append((r?.output ?: "").replace('\n', ' ').take(400)).append('\n')
            if (!ok && !ws.optional) { allOk = false; log.append("workflow stopped (fail-fast) at step ${ws.idx + 1}\n"); break }
            if (!ok) allOk = false
        }
        log.append("WORKFLOW RESULT: ").append(if (allOk) "SUCCESS" else "PARTIAL/FAILED")
            .append(" — ${System.currentTimeMillis() - started}ms\n")
        log.append("structured results: ").append(results.toString().take(2500))
        return AgentTools.ToolResult(allOk, log.toString().take(8500))
    }
}
