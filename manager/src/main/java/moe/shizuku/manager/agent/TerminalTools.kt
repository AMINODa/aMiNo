package moe.shizuku.manager.agent

import android.content.Context
import android.content.Intent
import moe.shizuku.manager.agent.auto.CommandValidator
import moe.shizuku.manager.terminal.ExecOutcome
import moe.shizuku.manager.terminal.TermBackend
import moe.shizuku.manager.terminal.TerminalEngine
import moe.shizuku.manager.terminal.TerminalNet
import moe.shizuku.manager.terminal.TerminalSession
import moe.shizuku.manager.terminal.linux.LinuxAcceptance
import moe.shizuku.manager.terminal.linux.LinuxEnvManager
import org.json.JSONArray
import org.json.JSONObject

/** ToolResult is nested in AgentTools — alias for readability. */
private typealias ToolResult = AgentTools.ToolResult

/**
 * aMiNo r1384 — the eleven terminal_* tools (spec C).
 *
 * Every tool returns the REAL result of a REAL execution: stdout, stderr,
 * exit code, cwd. No tool ever fakes success: output alone is not success —
 * the exit code and verification probes decide (spec G).
 *
 * Safety: commands routed through CommandValidator (BLOCK refuses outright,
 * CONFIRM is reported as such in the result and shown in the chat tool row).
 * Agent sessions are tagged and kept separate from user sessions; scrollback
 * lives in memory only — never persisted, so nothing secret is stored.
 */
object TerminalTools {

    // ---------- 1) terminal_list_environments ----------

    fun listEnvironments(context: Context): ToolResult {
        return try {
            val arr = JSONArray()
            for (e in TerminalEngine.discover(context)) {
                arr.put(JSONObject()
                    .put("environment", e.backend.id)
                    .put("title", e.backend.title)
                    .put("available", e.available)
                    .put("status", e.status)
                    .put("path", e.path)
                    .put("identity", e.identity)
                    .put("notes", e.notes))
            }
            ToolResult(true, arr.toString(2))
        } catch (e: Exception) {
            ToolResult(false, "terminal_env_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 2) terminal_connect ----------

    fun connect(context: Context, environment: String): ToolResult {
        if (environment.isBlank()) return ToolResult(false, "empty environment — use local | adb | root | termux | linux")
        return try {
            val info = kotlinx.coroutines.runBlocking { TerminalEngine.connect(context, environment) }
            ToolResult(info.available, JSONObject()
                .put("environment", info.backend.id)
                .put("available", info.available)
                .put("status", info.status)
                .put("identity", info.identity)
                .put("notes", info.notes)
                .toString(2))
        } catch (e: Exception) {
            ToolResult(false, "terminal_connect_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 3) terminal_create_session ----------

    fun createSession(context: Context, environment: String, name: String, agentSession: Boolean): ToolResult {
        val backend = TermBackend.values().firstOrNull { it.id == environment.lowercase().trim() }
            ?: return ToolResult(false, "unknown environment '$environment' — use: local | adb | root | termux | linux")
        return try {
            val s = TerminalEngine.create(context, backend, name, agentSession)
            ToolResult(true, JSONObject()
                .put("session_id", s.id)
                .put("environment", s.backend.id)
                .put("name", s.displayName)
                .put("cwd", s.cwd)
                .put("agent_session", s.isAgentSession)
                // r1395 (user Task 4): state the identity chain as FACT so no
                // "no root" diagnosis can be invented — PRoot exists precisely
                // to run Linux userspace WITHOUT real root.
                .put("identity", if (s.backend == TermBackend.LINUX_USERSPACE)
                    "host: Android shell uid 2000 (aMiNo service) · guest: PRoot fake uid 0 inside the container only · real Android root: NOT used and NOT required"
                    else JSONObject.NULL)
                .put("note", "persistent session — cwd/env survive between commands")
                .toString(2))
        } catch (e: Exception) {
            ToolResult(false, "cannot create ${backend.title} session: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 4) terminal_execute ----------

    fun execute(context: Context, sessionId: String, command: String, timeoutSec: Int): ToolResult {
        if (command.isBlank()) return ToolResult(false, "empty command")
        // safety gate — BLOCK tier never runs, even here
        val ruling = CommandValidator.validate(command)
        if (ruling.tier == CommandValidator.Tier.BLOCK) {
            return ToolResult(false, "blocked_by_policy: ${ruling.reason} — this command NEVER runs (terminal or otherwise)")
        }
        val s = resolveSession(context, sessionId)
            ?: return ToolResult(false, "no terminal session — call terminal_create_session first (or pass a session_id)")
        return try {
            val o = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull((timeoutSec.coerceIn(1, 300) + 30) * 1000L + 20_000L) {
                    s.execute(command, timeoutSec.coerceIn(1, 300) * 1000L)
                } ?: ExecOutcome(false, null, "", "", s.cwd, timeoutSec * 1000L, true, true, s.fgPid, false, "hard cap")
            }
            ToolResult(o.ok, JSONObject()
                .put("session", s.id)
                // 1.2 — every result names the environment it REALLY ran in, so the
                // model can never mix up the Debian rootfs with the Android host
                .put("environment", s.backend.id)
                .put("command", command)
                .put("exit_code", o.exitCode ?: JSONObject.NULL)
                .put("ok", o.ok)
                .put("tier", ruling.tier.name)
                .put("tier_reason", ruling.reason)
                .put("stdout", o.stdout.take(4000).ifBlank { JSONObject.NULL })
                .put("stderr", o.stderr.take(2000).ifBlank { JSONObject.NULL })
                .put("cwd", o.cwd ?: JSONObject.NULL)
                .put("duration_ms", o.durationMs)
                .put("timed_out", o.timedOut)
                .put("still_running", o.stillRunning)
                .put("pid", o.fgPid ?: JSONObject.NULL)
                .put("note", o.note ?: JSONObject.NULL)
                .toString(2))
        } catch (e: Exception) {
            ToolResult(false, "terminal_execute_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 5) terminal_send_input ----------

    fun sendInput(sessionId: String, text: String): ToolResult {
        val s = TerminalEngine.getSession(sessionId)
            ?: return ToolResult(false, "unknown session '$sessionId'")
        return if (s.sendInput(text)) ToolResult(true, "input sent to session ${s.id} (pid ${s.fgPid ?: "-"})")
        else ToolResult(false, "session ${s.id} is not alive")
    }

    // ---------- 6) terminal_get_output ----------

    fun getOutput(sessionId: String, lastN: Int): ToolResult {
        val s = TerminalEngine.getSession(sessionId)
            ?: return ToolResult(false, "unknown session '$sessionId'")
        val lines = s.snapshotLines().takeLast(lastN.coerceIn(5, 200))
        val arr = JSONArray()
        for (l in lines) arr.put(JSONObject()
            .put("kind", l.kind.name.lowercase()).put("text", l.text))
        return ToolResult(true, JSONObject()
            .put("session", s.id)
            .put("busy", s.busy)
            .put("cwd", s.cwd ?: JSONObject.NULL)
            .put("last_exit_code", s.lastExitCode ?: JSONObject.NULL)
            .put("lines", arr).toString(2))
    }

    // ---------- 7) terminal_get_session_status ----------

    fun sessionStatus(sessionId: String): ToolResult {
        val s = TerminalEngine.getSession(sessionId)
            ?: return ToolResult(false, "unknown session '$sessionId' — sessions: " +
                TerminalEngine.listSessions().joinToString(",") { it.id }.ifEmpty { "(none)" })
        val st = s.status()
        return ToolResult(true, JSONObject()
            .put("session_id", st.id).put("name", st.name)
            .put("environment", st.backend.id).put("agent_session", st.agentSession)
            .put("alive", st.alive).put("ready", st.ready).put("busy", st.busy)
            .put("cwd", st.cwd ?: JSONObject.NULL)
            .put("last_exit_code", st.lastExitCode ?: JSONObject.NULL)
            .put("running_pid", st.fgPid ?: JSONObject.NULL)
            .put("restarts", st.restarts).put("scrollback_lines", st.lineCount)
            .toString(2))
    }

    // ---------- 8) terminal_stop_process ----------

    fun stopProcess(sessionId: String): ToolResult {
        val s = TerminalEngine.getSession(sessionId)
            ?: return ToolResult(false, "unknown session '$sessionId'")
        val pid = s.fgPid
        s.stopProcess()
        return ToolResult(true, JSONObject()
            .put("session", s.id)
            .put("stopped_pid", pid ?: JSONObject.NULL)
            .put("note", if (pid != null && pid > 0)
                "kill sent out-of-band; if the process does not report back the session restarts (reported honestly)"
            else "no tracked process was running").toString(2))
    }

    // ---------- 9) terminal_close_session ----------

    fun closeSession(sessionId: String): ToolResult {
        return if (TerminalEngine.close(sessionId)) ToolResult(true, "session $sessionId closed")
        else ToolResult(false, "unknown session '$sessionId'")
    }

    // ---------- 10) terminal_install_package (spec D flow) ----------

    /**
     * Install tool(s) in the RIGHT environment, never assuming a package manager:
     * discover env → detect manager → (network check) → run → VERIFY (version/path)
     * → report honestly, including WHY a failure happened.
     */
    fun installPackage(context: Context, environment: String, packages: String): ToolResult {
        val pkgs = packages.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (pkgs.isEmpty()) return ToolResult(false, "no package names given")
        if (pkgs.any { it.contains(Regex("[^a-zA-Z0-9._+-]")) }) {
            return ToolResult(false, "invalid package name(s) — refusing: $packages")
        }

        // 1) pick the environment (r1385: an installed Linux env is the BEST home
        //    for apt/dpkg tools — probed first, but never assumed installed)
        val envId = environment.lowercase().trim()
        val candidates: List<String> = if (envId.isNotEmpty()) listOf(envId)
        else listOf("linux", "termux", "adb", "local")   // best home for linux tools first

        val report = StringBuilder()
        for (env in candidates) {
            // 2) probe the env for a REAL package manager
            val manager = detectPackageManager(context, env)
            if (manager == null) {
                report.append("env=$env: no usable package manager found\n")
                continue
            }
            // 3) plan (visible in chat + report)
            val updateCmd = manager.updateCmd
            val installCmd = "${manager.installPrefix} ${pkgs.joinToString(" ")}"
            report.append("env=$env manager=${manager.name}\n")
            report.append("plan: [$updateCmd] then [$installCmd] then verify each tool\n")

            // 4) execute in a real session
            val s = try {
                resolveSessionForEnv(context, env)
            } catch (e: Exception) {
                report.append("  skipped: ${e.message}\n"); continue
            }
            if (s == null) { report.append("  skipped: cannot open a session there\n"); continue }
            if (updateCmd.isNotBlank()) {
                val u = runInSession(context, s, updateCmd, 180)
                report.append("update: exit=${u.exitCode ?: "?"}${if (u.ok) "" else " (continuing — sources may already be ok)"}\n")
            }
            val r = runInSession(context, s, installCmd, 300)
            report.append("install: exit=${r.exitCode ?: "?"}\n")
            if (r.stderr.isNotBlank()) report.append("install stderr (tail): ${r.stderr.take(300)}\n")

            // 5) VERIFY each package really exists (spec: version or path proof)
            var verified = 0
            for (p in pkgs) {
                val v = runInSession(context, s, "command -v $p && ($p --version 2>/dev/null | head -n 1 || echo version-unknown)", 20)
                val ok = v.ok && v.stdout.contains("/")
                if (ok) verified++
                report.append("verify $p: ${if (ok) "OK — ${v.stdout.lineSequence().firstOrNull() ?: "?"}" else "FAILED — ${(v.stdout + v.stderr).take(120).ifBlank { "not found" }}"}\n")
            }
            return ToolResult(verified == pkgs.size && verified > 0,
                (if (verified == pkgs.size) "INSTALLED+VERIFIED in $env\n" else "PARTIAL/FAILED in $env\n") + report +
                    (if (verified < pkgs.size) "honest note: installation did not fully verify — see lines above\n" else ""))
        }

        // nothing worked — say exactly why and what WOULD work
        return ToolResult(false,
            "no_package_manager_found\n$report" +
                "honest conclusion: this device has no environment where '$packages' can be installed right now.\n" +
                "Realistic paths: (1) install Termux (then retry with environment=termux), " +
                "(2) pair the wireless ADB shell and check for a root environment. " +
                "Nothing was faked: no pkg/apt/apk/dnf/yum was found in any probed environment.")
    }

    private class PkgManager(val name: String, val updateCmd: String, val installPrefix: String)

    private fun detectPackageManager(context: Context, env: String): PkgManager? {
        if (env == "termux") {
            // handoff-only env: pkg exists inside Termux by definition, but we can't verify from here
            val info = kotlinx.coroutines.runBlocking { TerminalEngine.connect(context, "termux") }
            return if (info.available) PkgManager("pkg (via Termux RUN_COMMAND handoff)", "", "pkg install -y") else null
        }
        val s = try { resolveSessionForEnv(context, env) } catch (e: Exception) { return null } ?: return null
        val probe = runInSession(context, s,
            "for m in pkg apt apt-get apk dnf yum; do command -v \$m >/dev/null 2>&1 && { echo MANAGER=\$m; break; }; done", 20)
        val m = Regex("MANAGER=(\\S+)").find(probe.stdout)?.groupValues?.get(1) ?: return null
        return when (m) {
            "apt" -> PkgManager("apt", "apt update -y", "apt install -y")
            "apt-get" -> PkgManager("apt-get", "apt-get update -y", "apt-get install -y")
            "pkg" -> PkgManager("pkg", "", "pkg install -y")
            "apk" -> PkgManager("apk", "", "apk add")
            "dnf" -> PkgManager("dnf", "", "dnf install -y")
            "yum" -> PkgManager("yum", "", "yum install -y")
            else -> null
        }
    }

    // ---------- 11) terminal_check_command ----------

    fun checkCommand(context: Context, sessionId: String, command: String): ToolResult {
        val cmd = command.trim().removePrefix("./")
        if (cmd.isBlank() || cmd.contains(Regex("[;&|`$]"))) {
            return ToolResult(false, "invalid command name '$command' — give a bare binary name like nmap")
        }
        val s = TerminalEngine.getSession(sessionId) ?: TerminalEngine.defaultAgentSession(context)
            ?: return ToolResult(false, "no terminal session available")
        val r = runInSession(context, s, "command -v $cmd; echo PATHCHECK_RC=\$?", 15)
        val path = r.stdout.lineSequence().firstOrNull { it.startsWith("/") }
        if (path == null) {
            // 1.2 — honest not-found + the hint that prevents the exact user-reported
            // contradiction: a Debian package can never be visible from local/adb
            return ToolResult(false, JSONObject()
                .put("command", cmd).put("found", false)
                .put("environment", s.backend.id)
                .put("detail", "not on PATH in session ${s.id} (env ${s.backend.id}) — a real check, not an assumption")
                .put("hint", if (s.backend == TermBackend.LINUX_USERSPACE)
                    "installed with apt/dpkg? verify with dpkg -s <pkg> in this same linux session"
                else
                    "Debian packages (apt) are NEVER visible from ${s.backend.id} — they live in the linux environment; check with a linux session_id (or install there with terminal_install_package)")
                .toString(2))
        }
        val ver = runInSession(context, s, "$cmd --version 2>/dev/null | head -n 1", 15)
        return ToolResult(true, JSONObject()
            .put("command", cmd).put("found", true).put("path", path)
            .put("version_line", ver.stdout.take(200).ifBlank { "version flag not supported" })
            .put("environment", s.backend.id)
            .toString(2))
    }

    // ---------- helpers ----------

    private fun resolveSession(context: Context, sessionId: String): TerminalSession? {
        if (sessionId.isNotBlank()) return TerminalEngine.getSession(sessionId)
        return TerminalEngine.defaultAgentSession(context)
    }

    private fun resolveSessionForEnv(context: Context, env: String): TerminalSession? {
        // reuse an existing session on that env (agent first), else create one
        TerminalEngine.listSessions().firstOrNull { it.backend.id == env && it.alive && it.ready }?.let { return it }
        return when (env) {
            "adb", "adb_shell", "shell" -> {
                val port = kotlinx.coroutines.runBlocking { TerminalNet.resolveAdbPort(context) } ?: throw Exception("ADB not connected")
                TerminalEngine.create(context, TermBackend.ADB_SHELL, "agent", true, port)
            }
            "local", "local_app", "app" ->
                TerminalEngine.create(context, TermBackend.LOCAL_APP, "agent", true)
            "linux", "linux_userspace", "debian" ->
                TerminalEngine.create(context, TermBackend.LINUX_USERSPACE, "agent", true)
            "root" -> throw Exception("root not verified — run terminal_connect root first")
            else -> throw Exception("environment '$env' cannot hold a session")
        }
    }

    private fun runInSession(context: Context, s: TerminalSession, command: String, timeoutSec: Int): ExecOutcome {
        return kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull((timeoutSec + 30) * 1000L + 20_000L) {
                s.execute(command, timeoutSec * 1000L)
            } ?: ExecOutcome(false, null, "", "", s.cwd, timeoutSec * 1000L, true, true, s.fgPid, false, "hard cap")
        }
    }

    /** Termux handoff (spec B/D): forward an install to Termux — honest about its limits. */
    fun termuxHandoff(context: Context, packages: String): ToolResult {
        return try {
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/pkg")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("install", "-y", *packages.split(" ").toTypedArray()))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            context.startService(intent)
            ToolResult(true, "install forwarded to Termux (RUN_COMMAND). aMiNo cannot read Termux's output — " +
                "open Termux to watch it finish; verify afterwards with terminal_check_command. " +
                "If nothing happens, enable 'allow-external-apps' in Termux (~/.termux/termux.properties).")
        } catch (e: Exception) {
            ToolResult(false, "Termux handoff failed: ${e.message ?: e.javaClass.simpleName} — " +
                "Termux must be installed, with the RUN_COMMAND permission granted and 'allow-external-apps' enabled.")
        }
    }

    // ================= r1385: LINUX USER-SPACE ENVIRONMENT (6 tools) =================
    //
    // A real Debian 12 user-space through PRoot, executed by the aMiNo service
    // (shell uid 2000 — NO root; PRoot -0 fakes uid 0 inside the container only).
    // Kept SEPARATE from ADB / Termux / local — every tool names its environment.
    // Nothing is claimed installed/successful before a real verification passes.

    /** 1) linux_env_status — REAL state: install stage, storage, source, digests. */
    fun linuxEnvStatus(context: Context): ToolResult {
        return try {
            val st = LinuxEnvManager.status(context)
            val pf = kotlinx.coroutines.runBlocking { LinuxEnvManager.preFlight(context) }
            ToolResult(true, JSONObject()
                .put("state", st.state.name)
                .put("message", st.message)
                .put("arch", st.arch ?: "unsupported")
                .put("install_source", st.installSource ?: JSONObject.NULL)
                .put("manifest_digest", st.manifestDigest ?: JSONObject.NULL)
                .put("archive_mb", st.tarballBytes / (1024 * 1024))
                .put("rootfs_used_mb", st.rootfsDuKb?.div(1024) ?: JSONObject.NULL)
                .put("app_storage_free_mb", (st.freePrivateBytes ?: 0L) / (1024 * 1024))
                .put("network", pf.networkOk)
                .put("service", if (pf.serviceOk) "running (uid ${pf.serviceUid})" else "not running")
                .put("runtime_path", LinuxEnvManager.ROOTFS)
                .put("stage_board", LinuxEnvManager.stageBoard(context))   // r1395: exact proven/failing stage
                .put("fs_probe", JSONObject()
                    .put("path", pf.fs?.base ?: LinuxEnvManager.BASE)
                    .put("fstype", pf.fs?.fstype ?: JSONObject.NULL)
                    .put("write_ok", pf.fs?.writeOk ?: false)
                    .put("symlink_ok", pf.fs?.symlinkOk ?: false)
                    .put("symlink_error", pf.fs?.symlinkErr ?: JSONObject.NULL)
                    .put("hardlink_ok", pf.fs?.hardlinkOk ?: JSONObject.NULL)
                    .put("bundled_extractor", pf.fs?.extractor ?: JSONObject.NULL))
                .put("last_error", st.lastError ?: JSONObject.NULL)
                .toString(2))
        } catch (e: Exception) {
            ToolResult(false, "linux_env_status_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 2) linux_env_install — without confirm=true returns the REAL pre-flight
     * plan; with confirm=true performs the install and verifies with a real
     * probe. READY is only ever reported after the probe passed.
     */
    fun linuxEnvInstall(context: Context, confirm: Boolean): ToolResult {
        return try {
            val pf = kotlinx.coroutines.runBlocking { LinuxEnvManager.preFlight(context) }
            if (!confirm) {
                return ToolResult(false, JSONObject()
                    .put("stage", "preflight-plan (NOT installed yet — call again with confirm=true)")
                    .put("preflight_ok", pf.ok)
                    .put("arch", pf.arch ?: "unsupported")
                    .put("checks", JSONArray()
                        .put("CPU ABI ${pf.arch ?: "unsupported"} for the Debian arm64/amd64 rootfs")
                        .put("app storage free: ${pf.freePrivateBytes / (1024 * 1024)} MB (need ≥ 700)")
                        .put("data free: ${pf.freeDataBytes?.div(1024 * 1024) ?: "?"} MB (need ≥ 800)")
                        .put("network: ${pf.networkOk}")
                        .put("aMiNo service: ${if (pf.serviceOk) "running (uid ${pf.serviceUid})" else "NOT running — required (no root needed)"}")
                        .put("filesystem at ${pf.fs?.base ?: LinuxEnvManager.BASE}: fstype ${pf.fs?.fstype ?: "?"}, writable ${pf.fs?.writeOk}, symlinks ${pf.fs?.symlinkOk}${if (pf.fs?.symlinkErr != null) " (original error: ${pf.fs.symlinkErr})" else ""}"))
                    .put("problems", JSONArray(pf.problems))
                    .put("plan", "download Debian 12 rootfs (digest-verified) → push bundled proot + bundled symlink-safe tar → " +
                        "extract into ${LinuxEnvManager.BASE} → audit symlinks (bin→usr/bin, count ≥ 300) + bash + apt → " +
                        "verify by booting Debian through PRoot (id + os-release + bash + apt)")
                    .toString(2))
            }
            if (!pf.ok) return ToolResult(false,
                "preflight FAILED — nothing was installed:\n" + pf.problems.joinToString("\n") { "• $it" })
            val report = kotlinx.coroutines.runBlocking { LinuxEnvManager.install(context) {} }
            val ready = LinuxEnvManager.currentState(context) == LinuxEnvManager.State.READY
            ToolResult(ready, report)
        } catch (e: Exception) {
            ToolResult(false, "linux_env_install_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 3) linux_env_remove — deletes the runtime tree + the private archive. */
    fun linuxEnvRemove(context: Context, confirm: Boolean): ToolResult {
        if (!confirm) return ToolResult(false,
            "remove requires confirm=true — it deletes ${LinuxEnvManager.BASE} (runtime tree) AND the rootfs archive in app storage")
        val report = kotlinx.coroutines.runBlocking { LinuxEnvManager.remove(context) {} }
        // close any linux sessions — their runtime is gone
        TerminalEngine.listSessions().filter { it.backend == TermBackend.LINUX_USERSPACE }
            .forEach { TerminalEngine.close(it.id) }
        val gone = LinuxEnvManager.currentState(context) == LinuxEnvManager.State.NOT_INSTALLED
        return ToolResult(gone, report)
    }

    /** 4) linux_env_reset — re-extract the verified archive, verify again. */
    fun linuxEnvReset(context: Context, confirm: Boolean): ToolResult {
        if (!confirm) return ToolResult(false,
            "reset requires confirm=true — it wipes ${LinuxEnvManager.BASE}/rootfs and re-extracts the stored archive (packages installed by apt are LOST)")
        val report = kotlinx.coroutines.runBlocking { LinuxEnvManager.reset(context) {} }
        TerminalEngine.listSessions().filter { it.backend == TermBackend.LINUX_USERSPACE }
            .forEach { TerminalEngine.close(it.id) }
        val ok = LinuxEnvManager.currentState(context) == LinuxEnvManager.State.READY
        return ToolResult(ok, report)
    }

    /**
     * 5) linux_env_update — apt update + upgrade INSIDE a real Linux session.
     * Returns the real combined output and exit codes; verification via rc only.
     */
    fun linuxEnvUpdate(context: Context, confirm: Boolean): ToolResult {
        if (!confirm) return ToolResult(false,
            "update requires confirm=true — plan: apt-get update && apt-get upgrade -y INSIDE the Debian environment (upgrades only touch the container, never Android)")
        val s = try { resolveSessionForEnv(context, "linux") } catch (e: Exception) {
            return ToolResult(false, "no Linux session possible: ${e.message}")
        } ?: return ToolResult(false, "no Linux session available")
        val u = runInSession(context, s, "export DEBIAN_FRONTEND=noninteractive; apt-get update 2>&1 | tail -n 3; echo UPD_RC=\${PIPESTATUS[0]}", 240)
        val updRc = Regex("UPD_RC=(\\d+)").find(u.stdout)?.groupValues?.get(1)?.toIntOrNull()
        if (updRc != 0) return ToolResult(false, JSONObject()
            .put("apt_update_rc", updRc)
            .put("output", u.stdout.take(2000).ifBlank { u.stderr.take(800) })
            .toString(2))
        val g = runInSession(context, s, "export DEBIAN_FRONTEND=noninteractive; apt-get upgrade -y 2>&1 | tail -n 5; echo GRD_RC=\${PIPESTATUS[0]}", 900)
        val grdRc = Regex("GRD_RC=(\\d+)").find(g.stdout)?.groupValues?.get(1)?.toIntOrNull()
        return ToolResult(grdRc == 0, JSONObject()
            .put("apt_update_rc", updRc)
            .put("apt_upgrade_rc", grdRc)
            .put("output", g.stdout.take(2500).ifBlank { g.stderr.take(800) })
            .toString(2))
    }

    /** 6) linux_env_acceptance_tests — the user's 7 post-install tests, for real. */
    fun linuxEnvAcceptanceTests(context: Context): ToolResult {
        val (pass, results) = kotlinx.coroutines.runBlocking {
            LinuxAcceptance.runAll(context) { }
        }
        return ToolResult(pass, LinuxAcceptance.report(pass, results))
    }
}

