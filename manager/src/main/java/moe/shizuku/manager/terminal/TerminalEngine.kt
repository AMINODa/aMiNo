package moe.shizuku.manager.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.shell.ShellSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * aMiNo r1384 — TerminalEngine: registry of REAL terminal sessions + honest
 * environment discovery.
 *
 * DISCOVERY RULES (spec B): every environment is PROBED, never assumed.
 *  - local app shell : always real (the app's own uid, toybox)
 *  - adb shell       : real only when the wireless session / saved port is usable
 *  - root            : `su` binary presence is checked passively; availability is
 *                      CONFIRMED only by actually running `su -c id` and seeing uid=0
 *  - termux          : real only if com.termux is actually installed (package query)
 *  - linux userspace : NOT bundled in this build — reported honestly as absent
 *
 * Android shell / ADB shell / Termux / root / linux userspace are NEVER mixed up:
 * each session carries its backend, identity and limits with it.
 */
object TerminalEngine {

    private const val TAG = "TerminalEngine"
    private const val MAX_SESSIONS = 8

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught terminal engine error", e)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val idGen = AtomicInteger(1)

    // ---------- discovery (spec B) ----------

    fun discover(context: Context): List<EnvironmentInfo> {
        val ctx = context.applicationContext
        val list = ArrayList<EnvironmentInfo>()

        // 1) local app sandbox — always real
        list.add(EnvironmentInfo(
            backend = TermBackend.LOCAL_APP, available = true,
            status = "available",
            path = ctx.filesDir.absolutePath,
            identity = "app uid (${android.os.Process.myUid()}) — no elevated rights",
            notes = "toybox/toolbox tools only; cannot touch other apps or system settings"
        ))

        // 2) adb shell — through the wireless debugging channel
        val st = ShellSession.state.value
        val savedPort = moe.shizuku.manager.ShizukuSettings.getShellPort()
        val adb = when (st) {
            is ShellSession.ConnectionState.Connected -> EnvironmentInfo(
                TermBackend.ADB_SHELL, true, "connected (port ${st.port})",
                "/data/local/tmp", "shell uid 2000",
                "full toybox + pm/dumpsys/settings/content/cmd; no root"
            )
            is ShellSession.ConnectionState.Connecting -> EnvironmentInfo(
                TermBackend.ADB_SHELL, false, "connecting...", "/data/local/tmp",
                "shell uid 2000", "waiting for the wireless debugging session"
            )
            else -> if (savedPort > 0) EnvironmentInfo(
                TermBackend.ADB_SHELL, false, "saved port $savedPort — use terminal_connect",
                "/data/local/tmp", "shell uid 2000",
                "pairing key stored; port may need rediscovery"
            ) else EnvironmentInfo(
                TermBackend.ADB_SHELL, false, "not paired / wireless debugging off",
                "/data/local/tmp", "shell uid 2000 (when paired)",
                "pair from the Shell page first"
            )
        }
        list.add(adb)

        // 3) root — passive binary check only; REAL availability needs su -c id (terminal_connect)
        val suPath = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/vendor/bin/su", "/odm/bin/su").firstOrNull { File(it).exists() }
        list.add(EnvironmentInfo(
            backend = TermBackend.ROOT,
            available = false,   // never claims root without a real uid=0 proof
            status = if (suPath != null) "su binary found at $suPath — NOT verified (probe with terminal_connect)"
                     else "no su binary found",
            path = "/", identity = "uid 0 — only if a root manager actually grants it",
            notes = if (suPath != null) "unverified — most devices refuse without a root manager"
                    else "device appears unrooted; this backend will not be invented"
        ))

        // 4) termux — real package query (manifest <queries> covers com.termux)
        val termux = try {
            val pi = ctx.packageManager.getPackageInfo("com.termux", 0)
            EnvironmentInfo(
                TermBackend.TERMUX, true, "installed (v${pi.versionName ?: "?"})",
                "/data/data/com.termux", "termux uid — separate app sandbox",
                "handoff via RUN_COMMAND intent (needs Termux 'allow-external-apps' + permission); " +
                    "sessions cannot be held inside aMiNo — commands are forwarded"
            )
        } catch (e: Exception) {
            EnvironmentInfo(
                TermBackend.TERMUX, false, "not installed",
                "-", "-", "install Termux to enable package installs (pkg/apt) for tools like nmap"
            )
        }
        list.add(termux)

        // 5) linux userspace — REAL state from LinuxEnvManager (r1385):
        //    Debian 12 via PRoot, installed on demand, verified by a real probe.
        //    We never report it ready before the probe (id + os-release) passed.
        run {
            val (ok, why) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.discoveryInfo(ctx)
            val notes = when {
                ok -> "persistent sessions, bash/apt/dpkg inside; PRoot fakeroot only — the host identity stays shell uid 2000; " +
                    "files exchanged via /shared (app private)"
                else -> "separate from ADB/Termux/local — installing it requires the aMiNo service (no root needed)"
            }
            list.add(EnvironmentInfo(
                backend = TermBackend.LINUX_USERSPACE, available = ok,
                status = why,
                path = if (ok) "/root (in ${moe.shizuku.manager.terminal.linux.LinuxEnvManager.ROOTFS})" else "-",
                identity = if (ok) "fakeroot uid=0 INSIDE the container · shell uid 2000 on the host" else "-",
                notes = notes
            ))
        }

        return list
    }

    // ---------- connect (real probes) ----------

    suspend fun connect(context: Context, backendId: String): EnvironmentInfo {
        val ctx = context.applicationContext
        return when (backendId.lowercase().trim()) {
            "local", "local_app", "app" -> discover(ctx).first { it.backend == TermBackend.LOCAL_APP }
            "adb", "adb_shell", "shell" -> {
                var port = moe.shizuku.manager.ShizukuSettings.getShellPort()
                if (port <= 0) port = TerminalNet.resolveAdbPort(ctx) ?: -1
                if (port <= 0) return EnvironmentInfo(
                    TermBackend.ADB_SHELL, false, "no wireless debugging port found (mDNS timed out)",
                    "/data/local/tmp", "shell uid 2000", "enable wireless debugging / pair first"
                )
                try {
                    val key = moe.shizuku.manager.adb.AdbKey(
                        moe.shizuku.manager.adb.PreferenceAdbKeyStore(moe.shizuku.manager.ShizukuSettings.getPreferences()), "amino")
                    val sb = StringBuilder()
                    moe.shizuku.manager.adb.AdbClient("127.0.0.1", port, key).use { c ->
                        c.connect()
                        c.command("shell:id") { b -> sb.append(String(b)) }
                    }
                    val id = sb.toString().trim().take(160)
                    if (id.contains("uid=2000")) {
                        moe.shizuku.manager.ShizukuSettings.setShellPort(port)
                        EnvironmentInfo(TermBackend.ADB_SHELL, true, "verified on port $port",
                            "/data/local/tmp", "shell uid 2000", "ready for terminal sessions")
                    } else EnvironmentInfo(TermBackend.ADB_SHELL, false,
                        "port $port answered but identity is unexpected: $id",
                        "/data/local/tmp", "unknown", "re-pair the shell")
                } catch (e: Exception) {
                    EnvironmentInfo(TermBackend.ADB_SHELL, false,
                        "connect failed on port $port: ${e.message ?: e.javaClass.simpleName}",
                        "/data/local/tmp", "shell uid 2000", "pair from the Shell page first")
                }
            }
            "root", "su" -> {
                // REAL probe: run su -c id and require uid=0 — no assumption
                val probe = withTimeoutOrNull(6000) {
                    try {
                        val p = ProcessBuilder("su", "-c", "id").start()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        p.waitFor()
                        out
                    } catch (e: Exception) { null }
                }
                if (probe != null && probe.contains("uid=0")) EnvironmentInfo(
                    TermBackend.ROOT, true, "VERIFIED: $probe", "/", "uid 0 (root)",
                    "full power — still gated by the command safety validator"
                ) else EnvironmentInfo(
                    TermBackend.ROOT, false,
                    if (probe == null) "su probe timed out or failed — root NOT available"
                    else "su exists but refused: ${probe.take(120)}",
                    "/", "denied", "no root will be claimed or faked"
                )
            }
            "termux" -> discover(ctx).first { it.backend == TermBackend.TERMUX }
            "linux", "linux_userspace", "proot", "debian" -> {
                // REAL probe: spawn PRoot once through the service and require
                // uid=0 (fakeroot) + Debian os-release. No shortcut, no assumption.
                val st = moe.shizuku.manager.terminal.linux.LinuxEnvManager.currentState(ctx)
                if (st != moe.shizuku.manager.terminal.linux.LinuxEnvManager.State.READY) {
                    val (ok2, why2) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.discoveryInfo(ctx)
                    return EnvironmentInfo(TermBackend.LINUX_USERSPACE, false, why2, "-",
                        "-", "install it first: terminal linux_env_install or the Linux environment page")
                }
                val (ok, evidence) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.probe(ctx)
                if (ok) EnvironmentInfo(TermBackend.LINUX_USERSPACE, true,
                    "VERIFIED: ${evidence.replace("\n", " · ").take(140)}",
                    "/root (in ${moe.shizuku.manager.terminal.linux.LinuxEnvManager.ROOTFS})",
                    "fakeroot uid=0 inside · shell uid 2000 on host",
                    "ready for persistent sessions (bash/apt/dpkg)")
                else EnvironmentInfo(TermBackend.LINUX_USERSPACE, false,
                    "probe failed: ${evidence.take(180)}", "-", "-",
                    "the environment is marked installed but did NOT verify — use linux_env_reset or linux_env_remove")
            }
            else -> EnvironmentInfo(
                TermBackend.LINUX_USERSPACE, false, "unknown environment id '$backendId'",
                "-", "-", "use: local | adb | root | termux | linux"
            )
        }
    }

    // ---------- sessions ----------

    fun listSessions(): List<TerminalSession> = sessions.values.sortedBy { it.id }

    fun getSession(id: String): TerminalSession? = sessions[id]

    fun defaultAgentSession(context: Context): TerminalSession? {
        sessions.values.firstOrNull { it.isAgentSession && it.alive && it.ready }?.let { return it }
        // agent preference: adb shell (real power) → local app (always works)
        val st = ShellSession.state.value
        val port = (st as? ShellSession.ConnectionState.Connected)?.port
            ?: moe.shizuku.manager.ShizukuSettings.getShellPort().takeIf { it > 0 }
        val backend = if (port != null) TermBackend.ADB_SHELL else TermBackend.LOCAL_APP
        return try {
            create(context, backend, "agent", isAgentSession = true, adbPort = port)
        } catch (e: Throwable) {
            Log.w(TAG, "agent default session on $backend failed: ${e.message}")
            try { create(context, TermBackend.LOCAL_APP, "agent", isAgentSession = true) } catch (e2: Throwable) { null }
        }
    }

    @Synchronized
    fun create(context: Context, backend: TermBackend, name: String, isAgentSession: Boolean, adbPort: Int? = null): TerminalSession {
        if (sessions.size >= MAX_SESSIONS) {
            throw IllegalStateException("too many open sessions (max $MAX_SESSIONS) — close one first")
        }
        val ctx = context.applicationContext
        val port = adbPort ?: if (backend == TermBackend.ADB_SHELL) {
            val st = ShellSession.state.value
            (st as? ShellSession.ConnectionState.Connected)?.port
                ?: moe.shizuku.manager.ShizukuSettings.getShellPort().takeIf { it > 0 }
                ?: throw IllegalStateException("ADB shell is not connected — pair / connect first")
        } else null

        if (backend == TermBackend.ROOT) throw IllegalStateException(
            "root sessions are only created after a successful terminal_connect root probe (uid=0)"
        )
        if (backend == TermBackend.TERMUX) throw IllegalStateException(
            "termux is handoff-only (RUN_COMMAND) — aMiNo cannot hold a termux session; use local or adb"
        )
        if (backend == TermBackend.LINUX_USERSPACE) {
            val st = moe.shizuku.manager.terminal.linux.LinuxEnvManager.currentState(ctx)
            if (st != moe.shizuku.manager.terminal.linux.LinuxEnvManager.State.READY) {
                val (ok, why) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.discoveryInfo(ctx)
                throw IllegalStateException("linux userspace is not ready: $why")
            }
        }

        val id = "t${idGen.getAndIncrement()}"
        val s = TerminalSession(
            id = id, backend = backend, appContext = ctx,
            displayName = name.ifBlank { "${backend.id}-$id" },
            isAgentSession = isAgentSession, adbPort = port
        )
        val started = s.start()
        if (!started || !s.ready) {
            runCatching { s.close() }
            throw IllegalStateException("could not start a ${backend.title} session on this device right now")
        }
        sessions[id] = s
        return s
    }

    fun close(id: String): Boolean {
        val s = sessions.remove(id) ?: return false
        s.close()
        return true
    }

    /** Kill every session (used when the app needs a clean slate). */
    fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
