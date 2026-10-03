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
            // r1416 HONESTY FIX: rc==null used to count as SUCCESS — but the
            // `echo __AMINO_RC_$?` marker ALWAYS prints for a completed shell
            // line, so a missing marker means the stream was cut short
            // (timeout/disconnect). That is a FAILURE and must be shown as one;
            // pretending rc==0 let failed actions display ✓.
            val ok = rc == 0
            Log.d("AgentTools", "shell rc=$rc len=${clean.length}")
            ToolResult(ok, when {
                clean.isEmpty() && rc == null -> "(no output, no exit marker — stream cut short)"
                clean.isEmpty() -> "(no output, exit=$rc)"
                else -> clean
            })
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

    // ---------- r1382: permissions & user data ----------

    /**
     * Grant every runtime permission Android allows to the ADB shell identity
     * (com.android.shell). THE fix for "Permission Denial" when content queries
     * touch call log / SMS / contacts (the shell user must hold the matching
     * runtime permission for the provider to answer). Each pm grant is a real
     * command through the wireless ADB connection; results are reported honestly.
     */
    fun grantShellPermissions(context: Context): ToolResult {
        val st = ShellSession.state.value
        if (st !is ShellSession.ConnectionState.Connected) {
            return ToolResult(false, "shell_not_connected: open the Shell page / pair first")
        }
        val perms = listOf(
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        val lines = ArrayList<String>()
        var granted = 0
        for (p in perms) {
            val r = shellCommand(context, "pm grant com.android.shell $p")
            if (r.ok) {
                granted++
                lines.add("granted: ${p.substringAfterLast('.')}")
            } else {
                lines.add("failed: ${p.substringAfterLast('.')} — ${r.output.take(120)}")
            }
        }
        // real verification probes through the provider itself
        val probe = StringBuilder()
        for ((label, uri) in listOf(
            "call_log" to "content://call_log/calls",
            "contacts" to "content://com.android.contacts/contacts",
            "sms" to "content://sms"
        )) {
            val r = shellCommand(context, "content query --uri $uri")
            val head = r.output.lineSequence().take(2).joinToString(" | ").take(220)
            probe.append("verify $label: ").append(if (r.ok) "OK $head" else "STILL DENIED — $head").append('\n')
        }
        val summary = "shell permission boost: $granted/${perms.size} granted\n" +
                lines.joinToString("\n") + "\n" + probe
        return ToolResult(granted > 0, summary.take(3500))
    }

    /**
     * v1.4.2 — grant Android runtime permissions to a SPECIFIC INSTALLED app via
     * the shell (pm grant), with an EXISTENCE GATE and per-permission VERIFICATION
     * (dumpsys granted=true). Refuses honestly when the package is not installed —
     * a missing app can never be "made usable" by granting anything.
     */
    fun grantAppPermissions(context: Context, pkgRaw: String): ToolResult {
        val pkg = pkgRaw.trim()
        if (pkg.isEmpty()) return grantShellPermissions(context)
        if (!Regex("^[A-Za-z0-9_.]+$").matches(pkg))
            return ToolResult(false, "invalid package id '$pkg' — use the exact id from pm list / installed_apps")
        val st = ShellSession.state.value
        if (st !is ShellSession.ConnectionState.Connected) {
            return ToolResult(false, "shell_not_connected: open the Shell page / pair first")
        }
        // 1) existence gate — never grant into a phantom package
        val chk = shellCommand(context, "pm list packages $pkg")
        if (!chk.output.contains("package:$pkg")) {
            return ToolResult(
                false,
                "REFUSED — package '$pkg' is NOT installed on this device (pm list found nothing). " +
                        "Nothing was granted — installing permissions cannot make a missing app usable. " +
                        "Install the app first, then ask again."
            )
        }
        val perms = listOf(
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS"
        )
        val lines = ArrayList<String>()
        var granted = 0
        for (p in perms) {
            val g = shellCommand(context, "pm grant $pkg $p")
            val v = shellCommand(context, "dumpsys package $pkg | grep '$p' | head -1")
            val ok = g.ok && v.output.contains("granted=true")
            if (ok) {
                granted++
                lines.add("granted+verified: ${p.substringAfterLast('.')}")
            } else {
                val why = if (!g.ok) g.output.lineSequence().firstOrNull()?.take(110) ?: "pm grant failed"
                          else "present but not granted=true (install-time or not requestable)"
                lines.add("not grantable: ${p.substringAfterLast('.')} — $why")
            }
        }
        val summary = "permissions for $pkg: $granted/${perms.size} granted+verified\n" +
                lines.joinToString("\n") +
                "\nNOTE: some permissions are normal-permission (auto-granted at install) or restricted " +
                "by the system — 'not grantable' is an honest report, not a malfunction."
        return ToolResult(granted > 0, summary.take(3500))
    }

    /**
     * Read the user's own data through the APP identity (ContentResolver) instead
     * of the shell — needs the matching runtime permission granted from the
     * Permissions page. kinds: calls | sms | contacts.
     */
    fun userData(context: Context, kind: String, limit: Int): ToolResult {
        val n = limit.coerceIn(1, 50)
        fun need(perm: String): ToolResult? {
            val has = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            return if (has) null else ToolResult(
                false,
                "permission_missing: $perm — open the side menu > Permissions, grant it, then retry"
            )
        }
        return try {
            val arr = JSONArray()
            when (kind.lowercase().trim()) {
                "calls", "call_log", "calllog" -> {
                    need("android.permission.READ_CALL_LOG")?.let { return it }
                    val cur = context.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            android.provider.CallLog.Calls.NUMBER,
                            android.provider.CallLog.Calls.CACHED_NAME,
                            android.provider.CallLog.Calls.DATE,
                            android.provider.CallLog.Calls.DURATION,
                            android.provider.CallLog.Calls.TYPE
                        ), null, null,
                        android.provider.CallLog.Calls.DATE + " DESC"
                    ) ?: return ToolResult(false, "call_log_query_null")
                    cur.use { c ->
                        while (c.moveToNext() && arr.length() < n) {
                            val type = when (c.getInt(4)) {
                                android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                                android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                                else -> "other"
                            }
                            arr.put(JSONObject()
                                .put("number", c.getString(0) ?: "")
                                .put("name", c.getString(1) ?: "")
                                .put("date_ms", c.getLong(2))
                                .put("duration_s", c.getLong(3))
                                .put("type", type))
                        }
                    }
                    ToolResult(true, JSONObject().put("kind", "calls").put("count", arr.length())
                        .put("entries", arr).toString(2))
                }
                "sms", "messages" -> {
                    need("android.permission.READ_SMS")?.let { return it }
                    val cur = context.contentResolver.query(
                        android.net.Uri.parse("content://sms/inbox"),
                        arrayOf("_id", "address", "body", "date"), null, null, "date DESC"
                    ) ?: return ToolResult(false, "sms_query_null")
                    cur.use { c ->
                        while (c.moveToNext() && arr.length() < n) {
                            arr.put(JSONObject()
                                .put("from", c.getString(1) ?: "")
                                .put("body", (c.getString(2) ?: "").take(160))
                                .put("date_ms", c.getLong(3)))
                        }
                    }
                    ToolResult(true, JSONObject().put("kind", "sms").put("count", arr.length())
                        .put("entries", arr).toString(2))
                }
                "contacts", "contact" -> {
                    need("android.permission.READ_CONTACTS")?.let { return it }
                    val cur = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                        ), null, null,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                    ) ?: return ToolResult(false, "contacts_query_null")
                    cur.use { c ->
                        while (c.moveToNext() && arr.length() < n) {
                            arr.put(JSONObject()
                                .put("name", c.getString(0) ?: "")
                                .put("number", c.getString(1) ?: ""))
                        }
                    }
                    ToolResult(true, JSONObject().put("kind", "contacts").put("count", arr.length())
                        .put("entries", arr).toString(2))
                }
                else -> ToolResult(false, "unknown kind '$kind' — use calls | sms | contacts")
            }
        } catch (e: Exception) {
            ToolResult(false, "user_data_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * r1418 — task_wait: the timing primitive the agent never had (audit M1).
     * "ابدأ التصوير وتوقف بعد 10 ثوانٍ" died because the loop exited after the
     * start action; there was no way to represent "do X at time T".
     *
     * Two honest tiers:
     *  - seconds <= 120 : REAL in-process wait (WakeLock held by the task) —
     *    the runLoop continues to the next round and executes the pending step.
     *  - seconds > 120  : DURABLE continuation — persisted in SQLite +
     *    AlarmManager; survives process death AND reboot. runLoop parks the
     *    task with an honest note and TaskMemory resumes it at fire time.
     */
    suspend fun taskWait(context: Context, args: JSONObject): ToolResult {
        val seconds = args.optInt("seconds", 0)
        val then = args.optString("then", "").trim()
        if (seconds < 1) return ToolResult(false, "task_wait needs seconds >= 1")
        if (seconds > TaskMemory.SCHEDULE_MAX_SECONDS) {
            return ToolResult(false, "task_wait max ${TaskMemory.SCHEDULE_MAX_SECONDS}s (24h) — chain several task_wait calls for longer plans")
        }
        if (seconds <= TaskMemory.IN_PROCESS_MAX_SECONDS) {
            kotlinx.coroutines.delay(seconds * 1000L)
            return ToolResult(true,
                "waited ${seconds}s (REAL elapsed time). The task is STILL RUNNING — now execute the pending step" +
                (if (then.isNotBlank()) " «$then»" else "") +
                ". If it is a screen action, screen_read FIRST to verify the real state, then act, then screen_read again.")
        }
        // durable path (> 120s)
        val convId = AgentOrchestrator.state.value.conversationId
        if (convId <= 0) return ToolResult(false, "no active conversation — cannot schedule a continuation")
        val goal = moe.shizuku.manager.memory.MemoryRepository.working(context, convId)?.first
            ?.takeIf { it.isNotBlank() } ?: "task"
        val action = if (then.isNotBlank()) then
        else "continue the task: verify the real state (screen_read), then complete any pending step"
        val id = TaskMemory.schedule(context, convId, goal, action, seconds)
        val fireAt = System.currentTimeMillis() + seconds * 1000L
        moe.shizuku.manager.sharingan.SharinganTaskNotifier.taskScheduled(context, action, fireAt)
        return ToolResult(true,
            "SCHEDULED_CONTINUATION id=$id fires_in=${seconds}s — the pending step «${action.take(200)}» is saved in " +
            "DURABLE MEMORY (survives app close, process death and reboot) and WILL run automatically. " +
            "Do NOT claim the pending step already ran; your reply must state the resume plan only.")
    }

    data class ToolResult(val ok: Boolean, val output: String)
}
