package moe.shizuku.manager.agent.auto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.shell.ShellSession

/**
 * ExecutionEngine — real command execution for the autonomous loop (module: Executor).
 *
 * Reuses the proven r1376 execution path: one-shot wireless-ADB client per command,
 * `shell:<cmd>; echo __AMINO_RC_$?` marker, real exit-code parsing. Hard 20s timeout
 * so a streaming command can never hang the loop; output tail-trimmed to keep the
 * LLM context small.
 */
object ExecutionEngine {

    private const val TAG = "AutoExec"
    private const val TIMEOUT_MS = 20_000L
    private const val MAX_OUTPUT = 6000

    /** True when the Shell page has a live wireless ADB session. */
    fun shellReady(): Boolean = ShellSession.state.value is ShellSession.ConnectionState.Connected

    fun shellPort(): Int? =
        (ShellSession.state.value as? ShellSession.ConnectionState.Connected)?.port

    /**
     * Executes ONE command for real and captures stdout/stderr + exit code.
     * Runs on Dispatchers.IO; never throws — failures come back as ExecResult
     * with exitCode=null and an error line in [output].
     */
    suspend fun run(command: String): ExecResult {
        val st = ShellSession.state.value
        if (st !is ShellSession.ConnectionState.Connected) {
            return ExecResult(
                command, null,
                "shell_not_connected: open the Shell page and connect first (لا يوجد اتصال ADB)",
                timedOut = false, durationMs = 0
            )
        }
        val started = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
                val sb = StringBuilder()
                val got = withTimeoutOrNull(TIMEOUT_MS) {
                    AdbClient("127.0.0.1", st.port, key).use { client ->
                        client.connect()
                        client.command("shell:$command; echo __AMINO_RC_\$?") { bytes -> sb.append(String(bytes)) }
                    }
                    true
                }
                val duration = System.currentTimeMillis() - started
                if (got == null) {
                    Log.w(TAG, "timeout after ${duration}ms: $command")
                    ExecResult(command, null, tail(sb.toString()) + "\n[timeout after ${TIMEOUT_MS / 1000}s]",
                        timedOut = true, durationMs = duration)
                } else {
                    val raw = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
                    var rc: Int? = null
                    val out = StringBuilder()
                    for (line in raw.lines()) {
                        val m = Regex("__AMINO_RC_(\\d+)").find(line)
                        if (m != null) rc = m.groupValues[1].toIntOrNull()
                        else out.append(line).append('\n')
                    }
                    ExecResult(command, rc, tail(out.toString()), timedOut = false, durationMs = duration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "exec failed: $command", e)
                ExecResult(command, null, "shell_error: ${e.message ?: e.javaClass.simpleName}",
                    timedOut = false, durationMs = System.currentTimeMillis() - started)
            }
        }
    }

    private fun tail(s: String): String {
        val clean = s.trimEnd('\n')
        return if (clean.length <= MAX_OUTPUT) clean
        else "…(trimmed)…\n" + clean.takeLast(MAX_OUTPUT)
    }
}
