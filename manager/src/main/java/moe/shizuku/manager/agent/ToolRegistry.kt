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
