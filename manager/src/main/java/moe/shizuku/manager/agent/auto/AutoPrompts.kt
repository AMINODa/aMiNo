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
        appendLine()
        appendLine("READ (ALLOW tier — runs automatically, no approval): " +
                "dumpsys <service> (battery, location, telecom, phone, wifi, audio, notification, media.camera, sensorservice, window, display, usagestats, meminfo, cpuinfo, diskstats, netstats, deviceidle), " +
                "settings get/list, getprop, pm list/path/dump, content query --uri content://sms|content://call_log|content://media, " +
                "appops get, service list, ps, top -n 1, logcat -d, ls/cat/head/tail/du/df/stat/find/grep, " +
                "screencap -p /data/local/tmp/shot.png, uiautomator dump, ip addr/route, ifconfig, netstat, ss, ping -c N, free, nproc.")
        appendLine("PERMISSION DENIAL self-heal: if a read (call log, SMS, contacts, location...) fails with Permission Denial, " +
                "run 'pm grant com.android.shell android.permission.<PERM>' (ASK-FIRST approval dialog) for the missing permission " +
                "(READ_CALL_LOG, READ_CONTACTS, READ_SMS, READ_PHONE_STATE, ACCESS_FINE_LOCATION, CAMERA, RECORD_AUDIO...), then retry the same read.")
        appendLine("ASK-FIRST (CONFIRM tier — runs only after the user approves the dialog; plan them when the task needs them): " +
                "settings put (location_mode, screen_brightness, screen_off_timeout, user_rotation, accelerometer_rotation, airplane_mode_on, zen_mode/dnd), " +
                "svc wifi|data|bluetooth|nfc enable/disable, cmd wifi/bluetooth_manager/connectivity/telecom/camera/audio/display/notification, " +
                "media volume --stream X --set N, cmd notification post/set_dnd, am start (open any app or intent, android.intent.action.DIAL/CALL tel:, android.media.action.IMAGE_CAPTURE), " +
                "input tap/swipe/text/keyevent (KEYCODE_VOLUME_*, KEYCODE_POWER, KEYCODE_ENDCALL...), am force-stop, " +
                "pm install/uninstall/clear/grant/revoke/enable/disable/suspend, appops set, wm size/density, " +
                "rm/mv/cp/mkdir/touch/chmod, kill, screenrecord --time-limit N, locksettings, dumpsys deviceidle whitelist.")
        appendLine("NEVER PLAN (BLOCK tier — rejected, cannot run): reboot/shutdown, fastboot/flash, su/sudo, setenforce, setprop, " +
                "mkfs/dd to block devices, sm partition/forget, stop|start framework, service call, factory reset/wipe, settings put adb_enabled.")
    }

    fun plan(goal: String, caps: String): String = buildString {
        appendLine("You are the PLANNER of AMINO, a local Android agent. Convert the user's goal into a SHORT, SAFE, ordered plan.")
        appendLine()
        appendLine(caps)
        appendLine()
        appendLine("HARD RULES:")
        appendLine("- Return ONLY a JSON object. No markdown, no code fences, no commentary.")
        appendLine("- Max 6 steps, max 3 commands per step, commands are Android 'toybox' shell commands WITHOUT 'adb shell' prefix.")
        appendLine("- Prefer READ-ONLY commands (they run automatically, see READ list).")
        appendLine("- Mutating commands from the ASK-FIRST list ARE allowed in the plan (location, brightness, rotation, wifi/bt, volume, calls via am start DIAL/CALL, camera, pm grant/revoke, input taps, screenshots...), but EACH one pauses the run for explicit user approval — use them only when the task needs them, never for pure inspection.")
        appendLine("- NEVER plan BLOCK-tier commands (reboot, flash, su, setenforce, mkfs, sm partition, stop/start framework, service call, factory reset, adb_enabled). They are rejected outright and will abort the step.")
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
        appendLine("- replace: propose ONE alternative command that works on this Android version (no prefix). Same for 'permission denied' (find what IS readable). Mutating ASK-FIRST commands are acceptable (they will pause for approval); BLOCK-tier commands (reboot, flash, su, setenforce, mkfs...) are never acceptable.")
        appendLine("- skip: the step cannot be done but the goal may survive without it.")
        appendLine("- abort: the whole goal is impossible without blocked/dangerous commands.")
        appendLine("- NEVER propose BLOCK-tier commands (reboot, flash, su, setenforce, mkfs, sm partition, service call, factory reset). They will be rejected.")
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
