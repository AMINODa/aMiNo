package moe.shizuku.manager.agent.auto

import org.json.JSONObject

/**
 * Brain prompts for the autonomous loop (architecture C).
 * Prompts are English (models follow structure best); final user-facing text is
 * ALWAYS produced in the user's language. All planner/corrector outputs are STRICT
 * JSON — parsed locally; the model never executes anything itself.
 */
object AutoPrompts {

    fun capabilitiesBlock(shellConnected: Boolean, port: Int?): String = buildString {
        appendLine("REAL DEVICE CAPABILITIES right now:")
        if (shellConnected) {
            appendLine("- wireless ADB shell: CONNECTED (port $port). Shell commands WILL really run.")
        } else {
            appendLine("- wireless ADB shell: NOT CONNECTED. Shell commands CANNOT run. " +
                    "If the task strictly needs shell access, reply with {\"error\":\"shell_not_connected\"}. " +
                    "Otherwise plan only non-shell work and say so in the goal.")
        }
        appendLine("- The shell user is 'shell' (ADB): can read most system info, cannot get root.")
    }

    fun plan(goal: String, caps: String): String = buildString {
        appendLine("You are the PLANNER of AMINO, a local Android agent. Convert the user's goal into a SHORT, SAFE, ordered plan.")
        appendLine()
        appendLine(caps)
        appendLine()
        appendLine("HARD RULES:")
        appendLine("- Return ONLY a JSON object. No markdown, no code fences, no commentary.")
        appendLine("- Max 6 steps, max 3 commands per step, commands are Android 'toybox' shell commands WITHOUT 'adb shell' prefix.")
        appendLine("- Prefer READ-ONLY commands (pm list/path/dump, dumpsys, df, ls, cat, getprop, ps, top -n 1, du, stat, settings get, logcat -d).")
        appendLine("- NEVER plan destructive commands (rm, pm uninstall/clear, settings put, reboot, flash, su). They are blocked or require explicit human approval and will PAUSE the run. If the goal REQUIRES them, plan the inspection steps that prove what is needed instead.")
        appendLine("- Commands must be one-line, self-contained (no interactive prompts).")
        appendLine("- If the goal is a pure question answerable without the device, plan one step whose command is: echo no_shell_needed")
        appendLine()
        appendLine("JSON SHAPE:")
        appendLine("""{"goal":"...","steps":[{"n":1,"title":"...","commands":["..."],"expect":"what output proves this step worked"}]}""")
        appendLine()
        appendLine("USER GOAL: $goal")
    }

    fun selfCorrect(goal: String, step: PlanStep, result: ExecResult, observed: Observed): String = buildString {
        appendLine("You are the SELF-CORRECTOR of the AMINO Android agent. A planned command FAILED or was blocked/uncertain.")
        appendLine()
        appendLine("GOAL: $goal")
        appendLine("STEP ${step.n}: ${step.title} (expect: ${step.expect})")
        appendLine("COMMAND: ${result.command}")
        appendLine("REAL OBSERVATION: ${observed.reason}")
        appendLine("REAL OUTPUT (truncated):")
        appendLine(result.output.take(1200).ifBlank { "(no output)" })
        appendLine()
        appendLine("Decide ONE action. Return ONLY a JSON object:")
        appendLine("""{"action":"retry|replace|skip|abort","command":"<only for replace>","reason":"<short, in the USER's language>"}""")
        appendLine()
        appendLine("RULES:")
        appendLine("- retry: transient issue only (timeout, connection hiccup).")
        appendLine("- replace: propose ONE alternative command that is read-only and works on this Android version (no prefix). Same for 'permission denied' (find what IS readable).")
        appendLine("- skip: the step cannot be done but the goal may survive without it.")
        appendLine("- abort: the whole goal is impossible without blocked/dangerous commands.")
        appendLine("- NEVER propose destructive commands (rm, pm uninstall/clear, settings put, reboot, flash, su, curl/wget). They will be blocked.")
    }

    fun report(goal: String, plan: AutoPlan, results: List<String>): String = buildString {
        appendLine("You are the REPORTER of the AMINO Android agent. Write the FINAL REPORT for a finished autonomous task.")
        appendLine()
        appendLine("GOAL: $goal")
        appendLine("PLAN:")
        appendLine(plan.toMarkdown())
        appendLine()
        appendLine("REAL EXECUTION RESULTS (only these are true; do NOT invent anything else):")
        results.forEachIndexed { i, r -> appendLine("--- result ${i + 1} ---").appendLine(r.take(1400)) }
        appendLine()
        appendLine("Write the report in the USER's language. Structure:")
        appendLine("1) Verdict line: DONE / PARTIAL / FAILED + one sentence why.")
        appendLine("2) What was actually done (commands + what each really returned).")
        appendLine("3) Key findings with concrete numbers/names from the outputs above.")
        appendLine("4) If something failed or was skipped: say it honestly and what would be needed.")
        appendLine("Be concise (max ~250 words). No markdown tables.")
    }

    // ---------- strict JSON parsing helpers ----------

    /** Extracts the first {...} JSON object from a possibly chatty model reply. */
    fun extractJson(raw: String): JSONObject? {
        val s = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') inStr = !inStr
            if (inStr) continue
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
            }
        }
        return null
    }
}
