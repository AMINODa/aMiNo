package moe.shizuku.manager.agent

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.shell.ShellSession
import org.json.JSONObject

/**
 * Local tool registry + router. The model can only call tools registered here -
 * unknown names are rejected by the orchestrator (never executed).
 * Parameters use the OpenAPI subset expected by Gemini/OpenAI function calling.
 */
object ToolRegistry {

    data class RegisteredTool(
        val spec: ToolSpec,
        val requiresShell: Boolean,
        val run: (Context, JSONObject) -> AgentTools.ToolResult
    )

    val tools: List<RegisteredTool> = listOf(
        RegisteredTool(
            ToolSpec(
                "shell_command",
                "Run a shell command on the ANDROID HOST (toybox) through the AMINO wireless ADB " +
                    "connection. Use for real system inspection (df, ps, getprop, settings, dumpsys, ls, pm ...). " +
                    "Only works when the wireless debugging session is connected. " +
                    "NOTE: this is the Android host, NOT the Debian environment — Linux packages " +
                    "installed via apt (terminal_install_package / the linux env) are NEVER visible " +
                    "or runnable here; use terminal_execute for those.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("command", JSONObject()
                        .put("type", "string")
                        .put("description", "The shell command to run, without 'adb shell' prefix")))
                    .put("required", org.json.JSONArray().put("command"))
            ),
            requiresShell = true
        ) { ctx, args ->
            AgentTools.shellCommand(ctx, args.optString("command", ""))
        },
        RegisteredTool(
            ToolSpec(
                "device_info",
                "Get REAL device information from official Android APIs: model, Android version, " +
                    "battery level/charging, RAM usage, storage usage. Works without shell.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { ctx, _ ->
            AgentTools.deviceInfo(ctx)
        },
        RegisteredTool(
            ToolSpec(
                "installed_apps",
                "List installed launcher apps (label + package) via PackageManager. " +
                    "Optional filter text; returns up to 30 apps plus the total count.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("filter", JSONObject()
                        .put("type", "string")
                        .put("description", "Optional case-insensitive filter on app name or package")))
            ),
            requiresShell = false
        ) { ctx, args ->
            AgentTools.installedApps(ctx, args.optString("filter", "").takeIf { it.isNotBlank() }, 30)
        },
        RegisteredTool(
            ToolSpec(
                "open_url",
                "Open a URL in the user's default browser (real visible action).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("url", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("url"))
            ),
            requiresShell = false
        ) { ctx, args ->
            AgentTools.openUrl(ctx, args.optString("url", ""))
        },
        RegisteredTool(
            ToolSpec(
                "grant_permissions",
                "Permissions tool with TWO distinct modes. (1) NO args: boost aMiNo's OWN shell identity " +
                    "(call log, SMS, contacts, phone, location, camera, mic, storage) so YOUR content queries " +
                    "work — nothing to do with other apps. (2) package=\"com.exact.name\": grant Android runtime " +
                    "permissions to that INSTALLED app via pm grant, each VERIFIED with dumpsys (granted=true); " +
                    "REFUSES honestly if the package is not installed — granting can never make a missing app " +
                    "usable. NEVER use this to 'enable' an app: verify installation first with installed_apps " +
                    "or pm list. Requires shell.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("package", JSONObject().put("type", "string")
                        .put("description", "Optional exact package id to grant TO (mode 2). " +
                            "Omit to boost aMiNo's own shell identity (mode 1).")))
            ),
            requiresShell = true
        ) { ctx, args ->
            AgentTools.grantAppPermissions(ctx, args.optString("package", ""))
        },
        RegisteredTool(
            ToolSpec(
                "user_data",
                "Read the user's personal data through the app identity: kind = calls " +
                    "(recent call log: number, name, date, duration, type), sms (recent inbox " +
                    "messages), contacts (name + number). Needs the matching runtime permission " +
                    "granted from the side menu > Permissions.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("kind", JSONObject()
                        .put("type", "string")
                        .put("description", "calls | sms | contacts"))
                        .put("limit", JSONObject()
                            .put("type", "integer")
                            .put("description", "Max entries, 1-50, default 10")))
                    .put("required", org.json.JSONArray().put("kind"))
            ),
            requiresShell = false
        ) { ctx, args ->
            AgentTools.userData(ctx, args.optString("kind", ""), args.optInt("limit", 10))
        },

        // ================= r1384: INTEGRATED TERMINAL (11 tools) =================
        RegisteredTool(
            ToolSpec(
                "terminal_list_environments",
                "Discover which REAL terminal environments exist on this device right now: " +
                    "local app shell (always), adb shell (uid 2000, when wireless debugging is paired), " +
                    "root (only if su really runs), termux (only if the app is installed), " +
                    "linux userspace (not bundled). Each entry reports identity, path and limits. " +
                    "ALWAYS call this first when a command needs a place to run — never claim there is no terminal.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { ctx, _ ->
            TerminalTools.listEnvironments(ctx)
        },
        RegisteredTool(
            ToolSpec(
                "terminal_connect",
                "Really verify one environment and report its identity: environment = local | adb | " +
                    "root | termux | linux. root is only reported available if 'su -c id' returns uid=0; " +
                    "adb is only verified by running 'id' over the wireless channel.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("environment", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("environment"))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.connect(ctx, args.optString("environment", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_create_session",
                "Open a REAL persistent shell session (one long-lived sh; cwd and exported env " +
                    "survive between commands). environment = local | adb | linux (linux = the " +
                    "Debian 12 PRoot environment — same rootfs terminal_install_package uses, " +
                    "requires the env installed and ready). Returns the session_id used by all " +
                    "other terminal_* tools.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("environment", JSONObject().put("type", "string"))
                        .put("name", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("environment"))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.createSession(ctx, args.optString("environment", "local"),
                args.optString("name", ""), agentSession = true)
        },
        RegisteredTool(
            ToolSpec(
                "terminal_execute",
                "Run a command INSIDE a persistent terminal session and get the REAL result: " +
                    "stdout, stderr, exit_code, cwd, environment. session_id optional — when the " +
                    "Debian (linux) environment is installed, the agent session runs THERE by " +
                    "default (the same rootfs terminal_install_package installs into, so a " +
                    "package installed once is visible in every later command); pass a " +
                    "session_id to target another environment instead. Every result reports " +
                    "its 'environment' — always check it before claiming where something runs. " +
                    "timeout_seconds default 20; on timeout the command keeps running — stop " +
                    "it with terminal_stop_process.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("command", JSONObject().put("type", "string"))
                        .put("session_id", JSONObject().put("type", "string"))
                        .put("timeout_seconds", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray().put("command"))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.execute(ctx, args.optString("session_id", ""),
                args.optString("command", ""), args.optInt("timeout_seconds", 20))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_send_input",
                "Write raw text (a line is appended) to the stdin of a session's running interactive " +
                    "program (e.g. a prompt started with terminal_execute).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("session_id", JSONObject().put("type", "string"))
                        .put("text", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("session_id").put("text"))
            ),
            requiresShell = false
        ) { _, args ->
            TerminalTools.sendInput(args.optString("session_id", ""), args.optString("text", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_get_output",
                "Read the recent scrollback of a session (kind: cmd/out/err/sys) plus its live state " +
                    "(busy, cwd, last exit code). Use for long-running commands started with terminal_execute.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("session_id", JSONObject().put("type", "string"))
                        .put("last_n", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray().put("session_id"))
            ),
            requiresShell = false
        ) { _, args ->
            TerminalTools.getOutput(args.optString("session_id", ""), args.optInt("last_n", 40))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_get_session_status",
                "Status of one session: environment, alive, ready, busy, cwd, last_exit_code, " +
                    "running pid, restarts, scrollback size.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("session_id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("session_id"))
            ),
            requiresShell = false
        ) { _, args ->
            TerminalTools.sessionStatus(args.optString("session_id", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_stop_process",
                "Stop the running foreground command of a session (out-of-band kill of the tracked " +
                    "pid). If the process cannot report back, the session restarts and this is " +
                    "reported honestly.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("session_id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("session_id"))
            ),
            requiresShell = false
        ) { _, args ->
            TerminalTools.stopProcess(args.optString("session_id", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_close_session",
                "Close and destroy a terminal session.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("session_id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("session_id"))
            ),
            requiresShell = false
        ) { _, args ->
            TerminalTools.closeSession(args.optString("session_id", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_install_package",
                "Install tool(s) like nmap in the RIGHT environment — full honest flow: discover env, " +
                    "detect the real package manager (pkg/apt/apk/dnf/yum), run update+install, then " +
                    "VERIFY each tool with its version/path and report. Never assumes a manager exists. " +
                    "environment optional (linux | termux | adb | local — probed in that order; " +
                    "linux = the Debian PRoot environment, the BEST home for CLI tools — installed " +
                    "packages persist in the shared rootfs and are visible to every linux session, " +
                    "including the agent's default session).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("packages", JSONObject().put("type", "string")
                        .put("description", "Space-separated package names, e.g. 'nmap'"))
                        .put("environment", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("packages"))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.installPackage(ctx, args.optString("environment", ""), args.optString("packages", ""))
        },
        RegisteredTool(
            ToolSpec(
                "terminal_check_command",
                "Check whether a command/binary REALLY exists in a session's environment: returns its " +
                    "full path, version line and the environment it checked. Use to verify installs " +
                    "and before planning commands. session_id optional — without one it checks the " +
                    "agent's default environment (linux when the Debian env is installed). A Debian " +
                    "package is never visible from local/adb — check where you installed.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("command", JSONObject().put("type", "string"))
                        .put("session_id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("command"))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.checkCommand(ctx, args.optString("session_id", ""), args.optString("command", ""))
        },

        // ================= r1385: LINUX USER-SPACE ENVIRONMENT (6 tools) =================
        RegisteredTool(
            ToolSpec(
                "linux_env_status",
                "REAL status of the bundled Linux user-space environment (Debian 12 via PRoot, " +
                    "executed by the aMiNo service as shell uid — no root): install state, architecture, " +
                    "storage used/free, install source + digest, last error. Call BEFORE any linux plan.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { ctx, _ ->
            TerminalTools.linuxEnvStatus(ctx)
        },
        RegisteredTool(
            ToolSpec(
                "linux_env_install",
                "Install the Debian 12 user-space (PRoot, no root, stored in /data/local/tmp — " +
                    "separate from ADB/Termux/local). WITHOUT confirm=true it returns the REAL pre-flight " +
                    "plan (arch, storage, network, service checks). WITH confirm=true it downloads the " +
                    "digest-verified rootfs, installs and VERIFIES with a real probe (id + os-release). " +
                    "READY is never reported without that probe passing.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("confirm", JSONObject().put("type", "boolean")
                        .put("description", "false = show the pre-flight plan only; true = really install")))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.linuxEnvInstall(ctx, args.optBoolean("confirm", false))
        },
        RegisteredTool(
            ToolSpec(
                "linux_env_remove",
                "Completely remove the Linux user-space (runtime tree in /data/local/tmp/amino-linux " +
                    "AND the rootfs archive in app storage). Requires confirm=true. Honest about what is deleted.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("confirm", JSONObject().put("type", "boolean")))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.linuxEnvRemove(ctx, args.optBoolean("confirm", false))
        },
        RegisteredTool(
            ToolSpec(
                "linux_env_reset",
                "Reset the Linux user-space to a fresh Debian 12: wipe the runtime tree and re-extract " +
                    "the verified archive (apt-installed packages are lost). Requires confirm=true. " +
                    "Re-verifies with a real probe afterwards.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("confirm", JSONObject().put("type", "boolean")))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.linuxEnvReset(ctx, args.optBoolean("confirm", false))
        },
        RegisteredTool(
            ToolSpec(
                "linux_env_update",
                "Run apt-get update && apt-get upgrade INSIDE the Linux environment through a real " +
                    "session, return real exit codes + output tail. Requires confirm=true. " +
                    "Only touches the container — never Android itself.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("confirm", JSONObject().put("type", "boolean")))
            ),
            requiresShell = false
        ) { ctx, args ->
            TerminalTools.linuxEnvUpdate(ctx, args.optBoolean("confirm", false))
        },
        RegisteredTool(
            ToolSpec(
                "linux_env_acceptance_tests",
                "Run the 7 post-install acceptance tests for the Linux environment, each with REAL " +
                    "evidence: os-release, bash, id/pwd/uname, apt update, install+verify a tool, " +
                    "session persistence after cd, stopping a long-running process.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { ctx, _ ->
            TerminalTools.linuxEnvAcceptanceTests(ctx)
        },

        // ============ v1.3 SUPER AGENT: SCREEN INTELLIGENCE (perception + actuation) ============
        RegisteredTool(
            ToolSpec(
                "screen_read",
                "LOOK at the CURRENT screen like a human: returns the focused app/activity plus the real " +
                    "UI hierarchy (uiautomator dump) as a list of elements — class, resource-id, text, " +
                    "content-desc and CENTER coordinates for tapping. Text/tree perception: pixel-only " +
                    "content (photos) is not OCR-readable in this version. ALWAYS call this BEFORE any tap " +
                    "(find targets) and AFTER any screen_act (verify what actually changed). Requires the " +
                    "aMiNo service (Shizuku), not wireless ADB.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("max_elements", JSONObject()
                        .put("type", "integer")
                        .put("description", "Max elements to return, 10-200, default 50")))
            ),
            requiresShell = false
        ) { _, args ->
            runBlocking { moe.shizuku.manager.agent.perception.ScreenPerception.read(args.optInt("max_elements", 50)) }
        },
        RegisteredTool(
            ToolSpec(
                "screen_act",
                "ACT on the current screen with REAL input injection (the adb-identical channel, through " +
                    "the aMiNo service): action = tap {x,y} | longtap {x,y} | swipe {x,y,x2,y2,duration_ms} | " +
                    "text {text} (types into the focused field) | key {key: back|home|enter|up|down|left|right|" +
                    "volume_up|volume_down|power|recents|...} | wait {ms}. Use coordinates from screen_read. " +
                    "MANDATORY: verify with a fresh screen_read afterwards — never claim success unverified.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("action", JSONObject().put("type", "string")
                        .put("description", "tap | longtap | swipe | text | key | wait"))
                        .put("x", JSONObject().put("type", "integer"))
                        .put("y", JSONObject().put("type", "integer"))
                        .put("x2", JSONObject().put("type", "integer"))
                        .put("y2", JSONObject().put("type", "integer"))
                        .put("duration_ms", JSONObject().put("type", "integer"))
                        .put("text", JSONObject().put("type", "string"))
                        .put("key", JSONObject().put("type", "string"))
                        .put("ms", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray().put("action"))
            ),
            requiresShell = false
        ) { _, args ->
            runBlocking { moe.shizuku.manager.agent.perception.ScreenPerception.act(args) }
        },
        RegisteredTool(
            ToolSpec(
                "screen_capture",
                "Take a REAL full-screen PNG (screencap through the aMiNo service) and return its on-device " +
                    "path (/data/local/tmp, last 5 kept). The PNG is for the USER or future vision models — " +
                    "this version has no OCR: to READ the screen yourself use screen_read instead.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { _, _ ->
            runBlocking { moe.shizuku.manager.agent.perception.ScreenPerception.capture() }
        },

        // ============ v1.3 SUPER AGENT: SKILL ENGINE (Explore -> Learn -> Store -> Reuse) ============
        RegisteredTool(
            ToolSpec(
                "skill_save",
                "Convert a JUST-SUCCEEDED multi-step procedure into a reusable SKILL (local JSON, survives " +
                    "restarts). steps_json = JSON array where each step is ONE of: {\"type\":\"shell\",\"cmd\":\"...\"} " +
                    "(Android HOST command via the aMiNo service), {\"type\":\"terminal\",\"cmd\":\"...\"} (inside " +
                    "the agent default terminal env — Debian when installed, same validator as terminal_execute), " +
                    "{\"type\":\"input\",\"action\":\"tap\",\"x\":0,\"y\":0} (any screen_act action), " +
                    "{\"type\":\"perceive\",\"max_elements\":30}, or {\"type\":\"wait\",\"ms\":500}. Use {name} " +
                    "placeholders for variable parts and declare them in params_hint. verify = an optional " +
                    "host command that must exit 0 for the skill to count as SUCCESS. Step field \"optional\":true " +
                    "continues past a failed step.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("title", JSONObject().put("type", "string"))
                        .put("trigger", JSONObject().put("type", "string")
                            .put("description", "WHEN this skill applies — used to match future requests"))
                        .put("steps_json", JSONObject().put("type", "string")
                            .put("description", "JSON array of steps (see description)"))
                        .put("verify", JSONObject().put("type", "string"))
                        .put("params_hint", JSONObject().put("type", "string")
                            .put("description", "Documents the {placeholders}, e.g. '{package}: the app package'")))
                    .put("required", org.json.JSONArray().put("title").put("steps_json"))
            ),
            requiresShell = false
        ) { ctx, args ->
            moe.shizuku.manager.agent.skills.SkillEngine.save(
                ctx, args.optString("title", ""), args.optString("trigger", ""),
                args.optString("steps_json", ""), args.optString("verify", "").ifBlank { null },
                args.optString("params_hint", "").ifBlank { null })
        },
        RegisteredTool(
            ToolSpec(
                "skill_save",
                "Convert a JUST-SUCCEEDED multi-step procedure into a reusable SKILL (local JSON, survives " +
                    "restarts). steps_json = JSON array where each step is ONE of: {\"type\":\"shell\",\"cmd\":\"...\"} " +
                    "(Android HOST command via the aMiNo service), {\"type\":\"terminal\",\"cmd\":\"...\"} (inside " +
                    "the agent default terminal env — Debian when installed, same validator as terminal_execute), " +
                    "{\"type\":\"input\",\"action\":\"tap\",\"x\":0,\"y\":0} (any screen_act action), " +
                    "{\"type\":\"perceive\",\"max_elements\":30}, or {\"type\":\"wait\",\"ms\":500}. Use {name} " +
                    "placeholders for variable parts and declare them in params_hint. verify = an optional " +
                    "host command that must exit 0 for the skill to count as SUCCESS. Step field \"optional\":true " +
                    "continues past a failed step. Saved skills are visible to the user in the Skills Center (drawer 🧩).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("title", JSONObject().put("type", "string"))
                        .put("trigger", JSONObject().put("type", "string")
                            .put("description", "WHEN this skill applies — used to match future requests"))
                        .put("steps_json", JSONObject().put("type", "string")
                            .put("description", "JSON array of steps (see description)"))
                        .put("verify", JSONObject().put("type", "string"))
                        .put("params_hint", JSONObject().put("type", "string")
                            .put("description", "Documents the {placeholders}, e.g. '{package}: the app package'")))
                    .put("required", org.json.JSONArray().put("title").put("steps_json"))
            ),
            requiresShell = false
        ) { ctx, args ->
            moe.shizuku.manager.agent.skills.SkillEngine.save(
                ctx, args.optString("title", ""), args.optString("trigger", ""),
                args.optString("steps_json", ""), args.optString("verify", "").ifBlank { null },
                args.optString("params_hint", "").ifBlank { null })
        },
        RegisteredTool(
            ToolSpec(
                "skill_list",
                "List installed skills (id, name, version, category, source, steps/playbook, success/fail " +
                    "counters, enabled state). Check this BEFORE re-doing a known task — a matching skill runs " +
                    "in ONE call.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = false
        ) { ctx, _ ->
            moe.shizuku.manager.agent.skills.SkillManager.reconcile(ctx)
            val all = moe.shizuku.manager.agent.skills.SkillManager.all(ctx)
            if (all.isEmpty()) AgentTools.ToolResult(true, "no skills installed yet — save one with skill_save after a multi-step task succeeds, or install from the Skills Center")
            else AgentTools.ToolResult(true, all.joinToString("\n") { p ->
                "${p.id} «${p.name}» v${p.version} [${p.category}] src=${p.source.type} " +
                    (if (!p.enabled) "DISABLED " else "") +
                    (if (p.hasSteps) "steps(${if (p.hasUnapprovedScripts) "⚠scripts-unapproved" else "ok"}) — skill_run" else "playbook — skill_use") +
                    " | ok ${p.success}/fail ${p.fail}" +
                    (p.paramsHint?.let { " | params: $it" } ?: "")
            })
        },
        RegisteredTool(
            ToolSpec(
                "skill_run",
                "Run an installed DETERMINISTIC skill (step-machine) end-to-end and get the FULL per-step " +
                    "log (rc + output tails). PLAYBOOK skills (imported SKILL.md — instructions, no steps) are " +
                    "REFUSED here by design: use skill_use for those. skill_run NEVER fakes success — a run " +
                    "that executes zero steps fails honestly. " +
                    "Gated by the Skills Center security rules: disabled skills and skills with unapproved " +
                    "imported scripts are REFUSED. params = object whose keys fill the skill's {placeholders}. " +
                    "Fail-fast: the first failing step stops the run and is reported honestly; the verify " +
                    "command (if any) gates SUCCESS. Call skill_list first if unsure of the id.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("id", JSONObject().put("type", "string"))
                        .put("params", JSONObject().put("type", "object")
                            .put("description", "Key/value substitutions for the skill's {placeholders}")))
                    .put("required", org.json.JSONArray().put("id"))
            ),
            requiresShell = false
        ) { ctx, args ->
            runBlocking {
                val id = args.optString("id", "")
                val gate = moe.shizuku.manager.agent.skills.SkillManager.runPreflight(ctx, id)
                if (gate != null) gate
                else {
                    val r = moe.shizuku.manager.agent.skills.SkillEngine.run(
                        ctx, id, args.optJSONObject("params"))
                    moe.shizuku.manager.agent.skills.SkillManager.recordResult(ctx, id, r.ok)
                    r
                }
            }
        },
        RegisteredTool(
            ToolSpec(
                "skill_delete",
                "Delete an installed skill by id (use when a skill is outdated or consistently failing). " +
                    "Removes the package file, its carried files/attachments and the metadata index row.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("id"))
            ),
            requiresShell = false
        ) { ctx, args ->
            moe.shizuku.manager.agent.skills.SkillManager.delete(ctx, args.optString("id", ""))
        },
        RegisteredTool(
            ToolSpec(
                "skill_import",
                "Install a skill from EXTERNAL sources without any model training. source='github_url' + url " +
                    "(github.com repo / /blob/ /tree/ path, or raw.githubusercontent.com — public, no auth) or " +
                    "source='paste' + content (SKILL.md text, JSON manifest, or plain instructions). Everything " +
                    "is normalized into the internal amino-skill package. SECURITY: imported scripts are stored " +
                    "as DATA with approved=false and NEVER execute — the user must review/approve them in the " +
                    "Skills Center; say this explicitly when you import something with scripts. For local files/" +
                    "ZIPs/folders direct the user to the Skills Center (drawer 🧩) — file pickers are UI-only.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("source", JSONObject().put("type", "string")
                        .put("description", "github_url | paste"))
                        .put("url", JSONObject().put("type", "string"))
                        .put("content", JSONObject().put("type", "string")
                            .put("description", "for source=paste: SKILL.md / JSON manifest / plain instructions")))
                    .put("required", org.json.JSONArray().put("source"))
            ),
            requiresShell = false
        ) { ctx, args ->
            when (args.optString("source", "")) {
                "github_url" -> {
                    val url = args.optString("url", "").trim()
                    if (url.isBlank()) AgentTools.ToolResult(false, "skill_import needs url for source=github_url")
                    else try {
                        moe.shizuku.manager.agent.skills.SkillManager.install(ctx,
                            moe.shizuku.manager.agent.skills.SkillImporter.fromGitHub(url))
                    } catch (e: Exception) {
                        AgentTools.ToolResult(false, "import failed: ${e.message?.take(300)}")
                    }
                }
                "paste" -> {
                    val content = args.optString("content", "")
                    if (content.isBlank()) AgentTools.ToolResult(false, "skill_import needs content for source=paste")
                    else try {
                        moe.shizuku.manager.agent.skills.SkillManager.install(ctx,
                            moe.shizuku.manager.agent.skills.SkillImporter.fromPaste(content))
                    } catch (e: Exception) {
                        AgentTools.ToolResult(false, "import failed: ${e.message?.take(300)}")
                    }
                }
                else -> AgentTools.ToolResult(false, "source must be 'github_url' or 'paste' (files/ZIPs/folders are imported in the Skills Center UI)")
            }
        },
        RegisteredTool(
            ToolSpec(
                "skill_search",
                "Search installed skills by keyword (matches name, description, trigger, instructions, tags, " +
                    "id) and optional category. Use this to find a relevant skill before doing a task manually.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("query", JSONObject().put("type", "string"))
                        .put("category", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("query"))
            ),
            requiresShell = false
        ) { ctx, args ->
            val list = moe.shizuku.manager.agent.skills.SkillManager.search(
                ctx, args.optString("query", ""))
            val cat = args.optString("category", "").takeIf { it.isNotBlank() }
            val filtered = if (cat != null) list.filter { it.category.equals(cat, true) } else list
            if (filtered.isEmpty()) AgentTools.ToolResult(true, "no matching skill installed — you can create one (skill_save) or install one (skill_import / Skills Center)")
            else AgentTools.ToolResult(true, filtered.joinToString("\n") { p ->
                "${p.id} «${p.name}» v${p.version} [${p.category}]${if (!p.enabled) " DISABLED" else ""} — " +
                    p.description.take(120).ifBlank { p.trigger.take(120) }
            })
        },
        RegisteredTool(
            ToolSpec(
                "skill_use",
                "Load a PLAYBOOK skill's instructions into your context together with a REAL availability " +
                    "report (required tools, shell state, dependencies). Then execute the instructions step " +
                    "by step with your own tools (screen_read/screen_act/shell/terminal), verifying every " +
                    "step. Use for skills that teach HOW (no deterministic steps). For step-machine skills " +
                    "use skill_run instead.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("id", JSONObject().put("type", "string"))
                        .put("params", JSONObject().put("type", "object")
                            .put("description", "Values for the skill's declared inputs, if any")))
                    .put("required", org.json.JSONArray().put("id"))
            ),
            requiresShell = false
        ) { ctx, args ->
            moe.shizuku.manager.agent.skills.SkillRouter.playbook(
                ctx, args.optString("id", ""), args.optJSONObject("params"))
        },
        RegisteredTool(
            ToolSpec(
                "skill_workflow",
                "COMPOSE several deterministic skills into ONE task (Skill Router + Execution Coordinator): " +
                    "steps = JSON array [{\"skill\":\"id\",\"params\":{...},\"optional\":false,\"retries\":1}, ...] " +
                    "(max 10). Runs them in dependency-aware order, passes structured outputs forward — " +
                    "step N's params may embed an earlier step's output as {1.output} (its 0-based index) — " +
                    "retries failed steps (default 1 retry), stops fail-fast, and returns a per-step honest " +
                    "status report. Playbook-only skills are refused inside workflows (use skill_use).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("steps", JSONObject().put("type", "string")
                        .put("description", "JSON array of {skill, params, optional, retries} — see description")))
                    .put("required", org.json.JSONArray().put("steps"))
            ),
            requiresShell = false
        ) { ctx, args ->
            moe.shizuku.manager.agent.skills.SkillRouter.workflow(ctx, args.optString("steps", ""))
        },

        // ============ v1.3 SUPER AGENT: EXPERIENCE MEMORY (self-evolving) ============
        RegisteredTool(
            ToolSpec(
                "experience_record",
                "Record a finished task into your episodic memory: task (what was attempted), outcome " +
                    "(success | fail), lessons (the concrete takeaway — right steps, gotchas, what NOT to " +
                    "retry), optional skill_id if a skill captured it. Lessons are retrieved and injected " +
                    "into your context on future similar requests — write what your future self needs. " +
                    "Especially valuable for failures with workarounds.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("task", JSONObject().put("type", "string"))
                        .put("outcome", JSONObject().put("type", "string")
                            .put("description", "success | fail"))
                        .put("lessons", JSONObject().put("type", "string"))
                        .put("skill_id", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("task").put("outcome").put("lessons"))
            ),
            requiresShell = false
        ) { ctx, args ->
            val id = moe.shizuku.manager.memory.MemoryRepository.addExperience(
                ctx, args.optString("task", ""), args.optString("outcome", "fail"),
                args.optString("lessons", ""), args.optString("skill_id", "").ifBlank { null })
            if (id > 0) AgentTools.ToolResult(true, "experience #$id recorded — it will surface in future similar tasks")
            else AgentTools.ToolResult(false, "could not record the experience (task/outcome/lessons required)")
        },
        RegisteredTool(
            ToolSpec(
                "experience_recall",
                "Search your episodic memory for past task experiences (newest first, keyword match over " +
                    "task + lessons). Use before starting a task you may have attempted before.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("query", JSONObject().put("type", "string"))
                        .put("limit", JSONObject().put("type", "integer")))
            ),
            requiresShell = false
        ) { ctx, args ->
            val list = moe.shizuku.manager.memory.MemoryRepository.experiences(
                ctx, args.optString("query", "").takeIf { it.isNotBlank() }, args.optInt("limit", 5))
            if (list.isEmpty()) AgentTools.ToolResult(true, "no matching experiences recorded yet")
            else AgentTools.ToolResult(true, list.joinToString("\n") { e ->
                "#${e.id} [${e.outcome}] ${e.task}\n  lesson: ${e.lessons.take(280)}" +
                    (e.skillId?.let { "\n  skill: $it" } ?: "")
            })
        }
    )

    fun get(name: String): RegisteredTool? = tools.firstOrNull { it.spec.name == name }

    fun specs(): List<ToolSpec> = tools.map { it.spec }

    /** Real availability status for the Tools page and the router. */
    fun availability(context: Context, tool: RegisteredTool): String = when {
        !tool.requiresShell -> if (PermissionManager.internetAvailable(context)) "available" else "no_internet"
        else -> if (ShellSession.isConnected) "available"
        else if (PermissionManager.accessibilityEnabled(context)) "accessibility_only"
        else "needs_shell"
    }

    @Suppress("unused")
    private val appId = BuildConfig.APPLICATION_ID
}

/** Reads REAL permission/capability states - never claims more than the system granted. */
object PermissionManager {

    fun internetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    /** TRUE only when the SHARINGAN service itself (not just any aMiNo service) is enabled. */
    fun sharinganServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (enabled.contains("SharinganAccessibilityService")) return true
        val comp = android.content.ComponentName(
            context, moe.shizuku.manager.sharingan.SharinganAccessibilityService::class.java
        )
        return enabled.contains(comp.flattenToString())
    }

    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun shellState(): String = when (val s = ShellSession.state.value) {
        is ShellSession.ConnectionState.Connected -> "connected (port ${s.port})"
        is ShellSession.ConnectionState.Connecting -> "connecting"
        is ShellSession.ConnectionState.Failed -> "failed: ${s.message}"
        else -> "disconnected"
    }
}
