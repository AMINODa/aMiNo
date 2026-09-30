package moe.shizuku.manager.agent

import android.content.Context
import android.provider.Settings
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
                "Run a shell command on the phone through the AMINO wireless ADB connection. " +
                    "Use for real system inspection (df, ps, getprop, settings, dumpsys, ls, pm ...). " +
                    "Only works when the wireless debugging session is connected.",
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
                "Grant EVERY runtime permission Android allows to the ADB shell identity " +
                    "(call log, SMS, contacts, phone state, location, camera, mic, storage) " +
                    "and verify real access. Use when a content query or dump fails with " +
                    "Permission Denial, or when the user asks to enable all permissions. Requires shell.",
                JSONObject().put("type", "object").put("properties", JSONObject())
            ),
            requiresShell = true
        ) { ctx, _ ->
            AgentTools.grantShellPermissions(ctx)
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
                    "survive between commands). environment = local | adb. Returns the session_id " +
                    "used by all other terminal_* tools.",
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
                    "stdout, stderr, exit_code, cwd. session_id optional (an agent session is " +
                    "auto-created in the best environment). timeout_seconds default 20; on timeout " +
                    "the command keeps running — stop it with terminal_stop_process.",
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
                    "environment optional (termux | adb | local — auto-probed in that order).",
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
                    "full path and version line. Use to verify installs and before planning commands. " +
                    "session_id optional.",
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

    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun shellState(): String = when (val s = ShellSession.state.value) {
        is ShellSession.ConnectionState.Connected -> "connected (port ${s.port})"
        is ShellSession.ConnectionState.Connecting -> "connecting"
        is ShellSession.ConnectionState.Failed -> "failed: ${s.message}"
        else -> "disconnected"
    }
}
