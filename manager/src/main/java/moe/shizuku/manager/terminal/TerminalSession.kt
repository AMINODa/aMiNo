package moe.shizuku.manager.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInteractiveShell
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "TerminalSession"

/**
 * aMiNo r1384 — ONE REAL persistent terminal session.
 *
 * A session owns a single long-lived `sh` process (mksh), so the current
 * directory, exported environment variables and shell state SURVIVE between
 * commands — a new process is NOT spawned per command.
 *
 * Marker protocol (raw `shell:` stream / local pipes carry no echo):
 *   __AMINO_T9_HI_<n>    session init done
 *   __AMINO_T9_ERRB/ERRE stderr block of the running command
 *   __AMINO_T9_RC_<n>    real exit code of the command
 *   __AMINO_T9_FG_<pid>  pid of the backgrounded command (0 for builtins)
 *   __AMINO_T9_CWD_<dir> cwd AFTER the command (cd/export persist — same shell)
 *
 * Commands run as background jobs with `wait` so a long-running process can be
 * stopped out-of-band (kill of the tracked pid) while the session survives with
 * its state. Shell builtins that must mutate the session (cd/export/...) run in
 * the shell context in the foreground instead, which is what makes
 * `cd x` → `pwd` work, per the acceptance tests.
 *
 * stdout/stderr separation: the command's stderr is redirected to a per-session
 * temp file and re-emitted between ERRB/ERRE markers — parsed into a SEPARATE
 * buffer. (Legacy `shell:` merges both on one fd; the file hop gives true
 * attribution on every Android version. The local backend additionally drains
 * the native stderr pipe so it can never block.)
 */
class TerminalSession(
    val id: String,
    val backend: TermBackend,
    private val appContext: Context,
    val displayName: String,
    val isAgentSession: Boolean,
    private val adbPort: Int? = null
) {

    companion object {
        private const val M = "__AMINO_T9_"
        private val RE_RC = Regex("__AMINO_T9_RC_(-?\\d+)")
        private val RE_FG = Regex("__AMINO_T9_FG_(-?\\d+)")
        private val RE_CWD = Regex("__AMINO_T9_CWD_(.*)")
        private val RE_HI = Regex("__AMINO_T9_HI_(-?\\d+)")
        private const val ERRB = "__AMINO_T9_ERRB"
        private const val ERRE = "__AMINO_T9_ERRE"

        /** First-word builtins that MUST run in the shell context (state mutation). */
        private val BUILTINS = setOf(
            "cd", "export", "unset", "alias", "unalias", "source", ".", "set",
            "umask", "eval", "exit", "history", "hash", "declare", "typeset",
            "readonly", "local", "shift", "trap", "jobs", "bg", "fg", "disown",
            "popd", "pushd", "dirs", "ulimit", "times", "getopts", "bind"
        )

        fun isBuiltinCommand(command: String): Boolean {
            val t = command.trim().split(Regex("\\s+"))
            val first = t.getOrNull(0) ?: return false
            // skip leading env-assignment prefix (FOO=bar cmd)
            val word = t.firstOrNull { !Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) } ?: first
            return word in BUILTINS
        }

        /** Escape a user command for single-quoted eval embedding (newlines kept). */
        fun shellQuote(command: String): String =
            "'" + command.replace("'", "'\\''") + "'"
    }

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught terminal error [$id]", e)
        runCatching { addSys("internal error: ${e.message}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val execMutex = Mutex()

    private val lock = Any()
    private val lines = ArrayList<TermLine>()
    private val seqGen = AtomicLong(0)
    private val lineBuf = StringBuilder()          // partial line from the stream

    // --- session state ---
    @Volatile var alive = false; private set
    @Volatile var ready = false; private set
    @Volatile var busy = false; private set
    @Volatile var cwd: String? = null; private set
    @Volatile var lastExitCode: Int? = null; private set
    @Volatile var fgPid: Int? = null; private set
    @Volatile var restarts = 0; private set
    val history = ArrayDeque<String>()             // commands typed in THIS session

    // per-executing-command capture
    private var outCap = StringBuilder()
    private var errCap = StringBuilder()
    private var pendingRc: Int? = null
    private var pendingFg: Int? = null
    private var pendingCwd: String? = null
    private var errState = false                   // between ERRB and ERRE
    private var pending: CompletableDeferred<ExecOutcome>? = null
    private var execStart = 0L

    // backend handles
    private var proc: Process? = null              // LOCAL_APP / ROOT
    private var adbShell: AdbInteractiveShell? = null   // ADB_SHELL
    private var stdin: java.io.OutputStream? = null
    private var errFile: String? = null

    val startDir: String
        get() = when (backend) {
            TermBackend.LOCAL_APP -> appContext.filesDir.absolutePath
            else -> backend.startDir
        }

    // ---------- lifecycle ----------

    fun start(): Boolean {
        synchronized(lock) {
            if (alive) return true
            return try {
                when (backend) {
                    TermBackend.ADB_SHELL -> startAdb()
                    TermBackend.LOCAL_APP, TermBackend.ROOT -> startLocal(root = backend == TermBackend.ROOT)
                    else -> false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "session start failed [$id]", e)
                addSys("start failed: ${e.message ?: e.javaClass.simpleName}")
                alive = false
                false
            }
        }
    }

    private fun startLocal(root: Boolean): Boolean {
        val pb = ProcessBuilder(if (root) listOf("su") else listOf("sh"))
        pb.redirectErrorStream(false)
        val p = pb.start()
        proc = p
        stdin = p.outputStream
        errFile = "${appContext.cacheDir.absolutePath}/.amino_err_${id}"
        // native stderr pipe MUST be drained or the child can block on a full pipe.
        // The marker protocol still provides per-command stderr via the temp file.
        Thread({
            try { p.errorStream.copyTo(object : java.io.OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }) } catch (_: Throwable) {}
        }, "amino-term-errdrain-$id").apply { isDaemon = true; start() }
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                var line = r.readLine()
                while (line != null) { parseLine(line); line = r.readLine() }
            } catch (_: Throwable) {}
            onBackendDied()
        }, "amino-term-out-$id").apply { isDaemon = true; start() }
        alive = true
        sendInit()
        return true
    }

    private fun startAdb(): Boolean {
        val port = adbPort ?: throw IllegalStateException("no ADB port resolved")
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
        val s = AdbInteractiveShell("127.0.0.1", port, key)
        s.connect()
        s.openShell(
            onOutput = { data -> feed(String(data, Charsets.UTF_8)) },
            onClosed = { err ->
                Log.w(TAG, "adb terminal stream closed [$id]: ${err?.message}")
                onBackendDied()
            }
        )
        adbShell = s
        stdin = null
        errFile = "/data/local/tmp/.amino_err_$id"
        alive = true
        sendInit()
        return true
    }

    private fun sendInit() {
        ready = false
        val path = "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin:/su/bin:/sbin:\$PATH"
        val init = buildString {
            append("export PATH=$path\n")
            append("__amino_err=${errFile}\n")
            append("export __amino_err\n")
            append("mkdir -p /data/local/tmp 2>/dev/null; ")
            append("cd ${shellQuote(startDir)} 2>/dev/null || cd /\n")
            append("echo \"${M}HI_\$((6*7))\"\n")
        }
        writeRaw(init)
        // bounded busy-wait (Thread.sleep is not cancellable — use a wall deadline)
        val deadline = System.currentTimeMillis() + 8000
        while (alive && !ready && System.currentTimeMillis() < deadline) Thread.sleep(80)
        val ok = ready
        if (ok) {
            cwd = startDir
            addSys("session ready — env: ${backend.title}${if (backend == TermBackend.ADB_SHELL) " (uid 2000 shell)" else ""}")
        } else {
            addSys("session init timed out — the environment did not answer")
        }
    }

    private fun onBackendDied() {
        val wasAlive: Boolean
        synchronized(lock) { wasAlive = alive; alive = false; ready = false }
        if (!wasAlive) return
        addSys("backend connection lost — the session will restart on the next command (shell state is reset)")
        failPending("backend connection lost")
    }

    // ---------- low-level io ----------

    private fun writeRaw(text: String) {
        try {
            val sh = adbShell
            if (sh != null) { sh.send(text); return }
            stdin?.write(text.toByteArray(Charsets.UTF_8))
            stdin?.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "writeRaw failed [$id]", e)
            addSys("write failed: ${e.message}")
            onBackendDied()
        }
    }

    /** Feed a raw chunk from the adb stream (may contain partial lines). */
    private fun feed(chunk: String) {
        synchronized(lock) {
            lineBuf.append(chunk)
            var idx: Int
            while (lineBuf.indexOf("\n").also { idx = it } >= 0) {
                val line = lineBuf.substring(0, idx)
                lineBuf.delete(0, idx + 1)
                parseLineLocked(line.trimEnd('\r'))
            }
        }
    }

    /** Feed one complete line from the local process stdout. */
    private fun parseLine(line: String) = synchronized(lock) { parseLineLocked(line.trimEnd('\r')) }

    private fun parseLineLocked(line: String) {
        if (errState) {
            if (line.contains(ERRE)) { errState = false } else { appendLine(TermLine.Kind.ERR, line); errCap.appendLine(line) }
            return
        }
        // markers may arrive in any order; completion = RC seen (CWD checked too)
        RE_HI.find(line)?.let {
            if (it.groupValues[1] == "42") ready = true
            return
        }
        if (line.contains(ERRB)) { errState = true; return }
        RE_RC.find(line)?.let { m ->
            pendingRc = m.groupValues[1].toIntOrNull()
            maybeComplete(); return
        }
        RE_FG.find(line)?.let { m ->
            pendingFg = m.groupValues[1].toIntOrNull()
            if ((pendingFg ?: 0) > 0) fgPid = pendingFg
            maybeComplete(); return
        }
        RE_CWD.find(line)?.let { m ->
            pendingCwd = m.groupValues[1].trim()
            maybeComplete(); return
        }
        if (line.isBlank() && !busy) return   // ignore stray newlines when idle
        appendLine(TermLine.Kind.OUT, line)
        outCap.appendLine(line)
    }

    private fun maybeComplete() {
        val rc = pendingRc ?: return
        if (pendingCwd == null && alive) return   // wait for the CWD marker (order: RC then FG then CWD)
        pendingRc = null; val fg = pendingFg; pendingFg = null; val cd = pendingCwd; pendingCwd = null
        errState = false
        cwd = cd ?: cwd
        lastExitCode = rc
        busy = false
        appendLine(TermLine.Kind.SYS, "rc=$rc" + (if (fg != null && fg > 0) " · pid $fg" else ""))
        val d = pending
        pending = null
        d?.complete(
            ExecOutcome(
                ok = rc == 0, exitCode = rc,
                stdout = outCap.toString().trimEnd('\n'),
                stderr = errCap.toString().trimEnd('\n'),
                cwd = cwd, durationMs = System.currentTimeMillis() - execStart,
                timedOut = false, stillRunning = false, fgPid = fg,
                sessionRestarted = restarts > 0
            )
        )
    }

    private fun failPending(reason: String) {
        busy = false
        val d = pending
        pending = null
        val (o, e) = synchronized(lock) { outCap.toString() to errCap.toString() }
        d?.complete(
            ExecOutcome(false, null, o, e,
                null, System.currentTimeMillis() - execStart, false, false, null, true, reason)
        )
    }

    // ---------- public api ----------

    /**
     * Execute a command INSIDE this persistent shell. Returns the REAL result:
     * stdout, stderr, exit code, cwd. On timeout the command keeps running
     * (streaming output continues) — stop it with [stopProcess].
     */
    suspend fun execute(command: String, timeoutMs: Long = 20_000): ExecOutcome {
        if (command.isBlank()) return ExecOutcome(false, null, "", "", cwd, 0, false, false, null, false, "empty command")
        return execMutex.withLock {
            if (!alive || !ready) {
                addSys("restarting session backend...")
                restarts++
                outCap = StringBuilder(); errCap = StringBuilder()
                if (!start()) return@withLock ExecOutcome(
                    false, null, "", "", cwd, 0, false, false, null, true,
                    "cannot (re)start backend ${backend.title} — environment unavailable"
                )
            }
            if (busy) return@withLock ExecOutcome(
                false, null, outCap.toString(), errCap.toString(), cwd, 0, false, true, fgPid, false,
                "session busy: previous command still running (pid ${fgPid ?: "?"}) — wait or stop it first"
            )
            execStart = System.currentTimeMillis()
            outCap = StringBuilder(); errCap = StringBuilder()
            pendingRc = null; pendingFg = null; pendingCwd = null
            appendLine(TermLine.Kind.CMD, command.replace("\n", " ⏎ "))
            if (history.lastOrNull() != command) { history.addLast(command); if (history.size > 50) history.removeFirst() }
            busy = true
            val deferred = CompletableDeferred<ExecOutcome>()
            pending = deferred

            val quoted = shellQuote(command)
            val wrapper = if (isBuiltinCommand(command)) {
                // builtins must run in the shell context so cd/export persist
                ": >\"\$__amino_err\"; eval $quoted 2>\"\$__amino_err\"; __amino_rc=\$?; " +
                    "echo \"$ERRB\"; cat \"\$__amino_err\" 2>/dev/null; echo \"$ERRE\"; " +
                    "echo \"${M}RC_\${__amino_rc}\"; echo \"${M}FG_0\"; echo \"${M}CWD_\$PWD\"\n"
            } else {
                // external commands: background + tracked pid (killable, output streams live)
                ": >\"\$__amino_err\"; eval $quoted 2>\"\$__amino_err\" & __amino_fg=\$!; " +
                    "wait \"\$__amino_fg\"; __amino_rc=\$?; echo \"$ERRB\"; cat \"\$__amino_err\" 2>/dev/null; echo \"$ERRE\"; " +
                    "echo \"${M}RC_\${__amino_rc}\"; echo \"${M}FG_\${__amino_fg}\"; echo \"${M}CWD_\$PWD\"\n"
            }
            writeRaw(wrapper)

            // bounded wait: the parser completes the deferred when the RC+CWD
            // markers arrive; on timeout the command KEEPS RUNNING (output keeps
            // streaming) and can be stopped with stopProcess.
            val outcome = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (outcome != null) outcome else {
                val (outSnap, errSnap) = synchronized(lock) { outCap.toString() to errCap.toString() }
                ExecOutcome(
                    ok = false, exitCode = null,
                    stdout = outSnap.trimEnd('\n'),
                    stderr = errSnap.trimEnd('\n'),
                    cwd = cwd, durationMs = System.currentTimeMillis() - execStart,
                    timedOut = true, stillRunning = true, fgPid = fgPid, sessionRestarted = false,
                    note = "timeout after ${timeoutMs / 1000}s — the command is STILL RUNNING (pid ${fgPid ?: "?"}); " +
                        "output keeps streaming; stop it with terminal_stop_process"
                )
            }
        }
    }

    /** Raw stdin write — for interactive programs started via execute. */
    fun sendInput(text: String): Boolean {
        if (!alive) return false
        writeRaw(if (text.endsWith("\n")) text else "$text\n")
        return true
    }

    /**
     * Stop the running foreground command. The tracked pid is killed OUT-OF-BAND
     * (separate one-shot channel) so `wait` returns and the session keeps its
     * cwd/env. If the kill cannot reach the process, the session is destroyed and
     * transparently restarted (reported honestly).
     */
    fun stopProcess() {
        val pid = fgPid
        if (pid == null || pid <= 0) {
            addSys("nothing to stop — no tracked process")
            return
        }
        addSys("stopping pid $pid...")
        scope.launch {
            var killed = false
            try {
                when (backend) {
                    TermBackend.ADB_SHELL -> {
                        val st = adbPort
                        if (st != null) {
                            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
                            AdbClient("127.0.0.1", st, key).use { c ->
                                c.connect()
                                val sb = StringBuilder()
                                c.command("shell:kill -9 $pid 2>/dev/null; echo KILLDONE") { b -> sb.append(String(b)) }
                                killed = sb.toString().contains("KILLDONE")
                            }
                        }
                    }
                    TermBackend.LOCAL_APP -> {
                        val p = ProcessBuilder("sh", "-c", "kill -9 $pid 2>/dev/null; echo KILLDONE")
                            .start()
                        val out = p.inputStream.bufferedReader().readText()
                        p.waitFor()
                        killed = out.contains("KILLDONE")
                    }
                    TermBackend.ROOT -> {
                        // root-owned child: the app uid cannot signal it — try, then fall back
                        killed = runCatching {
                            val p = ProcessBuilder("sh", "-c", "kill -9 $pid 2>/dev/null; echo KILLDONE").start()
                            val out = p.inputStream.bufferedReader().readText(); p.waitFor(); out.contains("KILLDONE")
                        }.getOrDefault(false)
                    }
                    else -> {}
                }
            } catch (e: Throwable) {
                Log.w(TAG, "out-of-band kill failed [$id]", e)
            }
            if (killed) {
                // give the marker lines a moment to arrive
                Thread.sleep(700)
                if (busy) {
                    addSys("process did not report back — restarting the session")
                    destroyBackend(); restarts++; busy = false
                    failPending("process force-stopped with its session")
                } else {
                    addSys("process stopped — session state preserved (cwd: ${cwd ?: "?"})")
                }
            } else {
                addSys("out-of-band kill unavailable — restarting the session (state is lost)")
                destroyBackend(); restarts++; busy = false
                failPending("session restarted to stop the process")
            }
        }
    }

    private fun destroyBackend() {
        runCatching { adbShell?.close() }
        runCatching { proc?.destroy() }
        adbShell = null; proc = null; stdin = null
        alive = false; ready = false
    }

    fun close() {
        destroyBackend()
        addSys("session closed")
        failPending("session closed")
    }

    // ---------- scrollback ----------

    fun snapshotLines(): List<TermLine> = synchronized(lock) { lines.toList() }

    fun clearLines() = synchronized(lock) { lines.clear() }

    private fun addSys(text: String) = appendLine(TermLine.Kind.SYS, "[aMiNo] $text")

    private fun appendLine(kind: TermLine.Kind, text: String) {
        if (text.isEmpty() && kind == TermLine.Kind.OUT) return
        synchronized(lock) {
            lines.add(TermLine(seqGen.incrementAndGet(), kind, text))
            if (lines.size > 800) lines.subList(0, lines.size - 800).clear()
        }
    }

    fun status(): SessionStatus = SessionStatus(
        id = id, name = displayName, backend = backend, agentSession = isAgentSession,
        alive = alive, ready = ready, busy = busy, cwd = cwd, lastExitCode = lastExitCode,
        fgPid = if (busy) fgPid else null, restarts = restarts,
        lineCount = synchronized(lock) { lines.size }
    )
}

/** ADB port resolution shared by the engine and sessions. */
object TerminalNet {
    /** Resolve the ADB wireless port (saved first, then mDNS) — honest, with a timeout. */
    suspend fun resolveAdbPort(context: Context): Int? {
        ShizukuSettings.getShellPort().takeIf { it > 0 }?.let { return it }
        return withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var mdns: AdbMdns? = null
                mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { pair ->
                    if (pair.second > 0 && cont.isActive) {
                        mdns?.stop()
                        cont.resume(pair.second)
                    }
                }
                mdns.start()
                cont.invokeOnCancellation { mdns?.stop() }
            }
        }
    }
}
