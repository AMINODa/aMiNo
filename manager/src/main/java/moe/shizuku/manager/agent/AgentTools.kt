package moe.shizuku.manager.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.shell.ShellSession
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * REAL tool implementations. Every tool returns actual device data or a real error -
 * no demo data, no fabricated results.
 */
object AgentTools {

    /** Run a command through the REAL wireless ADB connection (one-shot, like runCheck). */
    fun shellCommand(context: Context, command: String): ToolResult {
        if (command.isBlank()) return ToolResult(false, "empty command")
        val st = ShellSession.state.value
        if (st !is ShellSession.ConnectionState.Connected) {
            return ToolResult(false, "shell_not_connected: open the Shell page / pair first (لا يوجد اتصال ADB)")
        }
        return try {
            val key = AdbKey(PreferenceAdbKeyStore(moe.shizuku.manager.ShizukuSettings.getPreferences()), "amino")
            val sb = StringBuilder()
            val timeoutMs = 15_000L
            AdbClient("127.0.0.1", st.port, key).use { client ->
                client.connect()
                client.command("shell:$command; echo __AMINO_RC_\$?") { bytes -> sb.append(String(bytes)) }
            }
            val raw = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
            var rc: Int? = null
            val out = StringBuilder()
            for (line in raw.lines()) {
                val m = Regex("__AMINO_RC_(\\d+)").find(line)
                if (m != null) rc = m.groupValues[1].toIntOrNull()
                else out.append(line).append('\n')
            }
            val clean = out.toString().trimEnd('\n').take(4000)
            val ok = rc == 0 || rc == null // adb shell may not print rc for busybox-less output
            Log.d("AgentTools", "shell rc=$rc len=${clean.length}")
            ToolResult(ok, if (clean.isEmpty()) "(no output, exit=$rc)" else clean)
        } catch (e: Exception) {
            ToolResult(false, "shell_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Real device information from official Android APIs. */
    fun deviceInfo(context: Context): ToolResult {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalGb = stat.totalBytes / 1e9
            val freeGb = stat.availableBytes / 1e9
            val json = JSONObject()
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("battery_percent", if (level in 1..100) level else JSONObject.NULL)
                .put("battery_charging", charge == BatteryManager.BATTERY_STATUS_CHARGING)
                .put("ram_total_gb", Math.round((mem.totalMem / 1e9) * 10) / 10.0)
                .put("ram_available_gb", Math.round((mem.availMem / 1e9) * 10) / 10.0)
                .put("ram_low", mem.lowMemory)
                .put("storage_total_gb", Math.round(totalGb * 10) / 10.0)
                .put("storage_free_gb", Math.round(freeGb * 10) / 10.0)
            ToolResult(true, json.toString(2))
        } catch (e: Exception) {
            ToolResult(false, "device_info_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Real installed (launcher) apps via PackageManager. */
    fun installedApps(context: Context, filter: String?, limit: Int): ToolResult {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val ris = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            val arr = JSONArray()
            val f = filter?.lowercase()?.trim()
            for (ri in ris) {
                val pkg = ri.activityInfo.packageName
                if (!seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                if (f != null && !(label.lowercase().contains(f) || pkg.lowercase().contains(f))) continue
                arr.put(JSONObject().put("app", label).put("package", pkg))
                if (arr.length() >= limit.coerceIn(1, 50)) break
            }
            ToolResult(true, JSONObject().put("total_launcher_apps", seen.size).put("matching", arr.length())
                .put("apps", arr).toString(2))
        } catch (e: Exception) {
            ToolResult(false, "apps_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Open a URL with the user's default browser - a real, visible action. */
    fun openUrl(context: Context, url: String): ToolResult {
        val u = url.trim()
        val full = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) == null) {
                ToolResult(false, "no_browser: no app can open $full")
            } else {
                context.startActivity(i)
                ToolResult(true, "opened: $full")
            }
        } catch (e: Exception) {
            ToolResult(false, "open_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    data class ToolResult(val ok: Boolean, val output: String)
}
