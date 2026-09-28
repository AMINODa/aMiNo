package moe.shizuku.manager.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInteractiveShell
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Singleton that owns the aMiNo Shell ADB session.
 *
 * The session outlives the Shell screen: once connected, the ADB connection and
 * its interactive shell stream stay open (that is the whole point of the Shell
 * feature) and any screen can observe its state and output.
 */
object ShellSession {

    private const val TAG = "ShellSession"

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val port: Int) : ConnectionState()
        data class Failed(val message: String) : ConnectionState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // replay keeps recent output so reopening the screen shows history
    private val _output = MutableSharedFlow<String>(replay = 400, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private var shell: AdbInteractiveShell? = null
    private var connecting = false
    private var userDisconnected = false
    private var reconnectAttempts = 0

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected && shell?.isOpen == true

    fun appendLocal(line: String) {
        scope.launch { _output.emit(line) }
    }

    /**
     * Connect (or reconnect) the persistent shell — manual entry point.
     * Order: last known working port -> mDNS discovery of the wireless debugging port.
     */
    @Synchronized
    fun connect(context: Context) {
        userDisconnected = false
        reconnectAttempts = 0
        connectInternal(context)
    }

    @Synchronized
    private fun connectInternal(context: Context) {
        if (connecting || isConnected) return
        connecting = true
        _state.value = ConnectionState.Connecting

        val appContext = context.applicationContext
        scope.launch {
            val savedPort = ShizukuSettings.getShellPort()
            var port = savedPort
            if (port <= 0) {
                appendLocal("[aMiNo] searching for wireless debugging port (mDNS)...\n")
                port = discoverPort(appContext) ?: -1
            }
            if (port <= 0) {
                appendLocal("[aMiNo] no wireless debugging port found.\n")
                _state.value = ConnectionState.Failed("port not found")
                connecting = false
                return@launch
            }

            appendLocal("[aMiNo] connecting to 127.0.0.1:$port ...\n")
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
                val s = AdbInteractiveShell("127.0.0.1", port, key)
                s.connect()
                appendLocal("[aMiNo] connected. opening shell stream...\n")
                s.openShell(
                    onOutput = { data ->
                        // normalize PTY line endings (\r\n) for the console TextView
                        val text = String(data).replace("\r\n", "\n").replace('\r', '\n')
                        _output.tryEmit(text)
                    },
                    onClosed = { err ->
                        Log.w(TAG, "shell closed", err)
                        shell = null
                        _state.value = ConnectionState.Disconnected
                        if (err != null && !userDisconnected) {
                            appendLocal("\n[aMiNo] connection lost (${err.message}) - reconnecting...\n")
                            scheduleReconnect(appContext)
                        } else {
                            appendLocal("\n[aMiNo] ${err?.message ?: "connection closed"}\n")
                        }
                    }
                )
                shell = s
                ShizukuSettings.setShellPort(port)
                reconnectAttempts = 0
                appendLocal("[aMiNo] shell ready - the session stays open. Type 'help' for help.\n")
                _state.value = ConnectionState.Connected(port)
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed", e)
                appendLocal("[aMiNo] connect failed: ${e.message}\n")
                _state.value = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
                runCatching { shell?.close() }
                shell = null
            } finally {
                connecting = false
            }
        }
    }

    /** Auto-reconnect with exponential backoff when the session drops unexpectedly. */
    private fun scheduleReconnect(context: Context) {
        if (userDisconnected) return
        if (reconnectAttempts >= 5) {
            appendLocal("[aMiNo] auto-reconnect gave up after 5 attempts - tap Reconnect.\n")
            _state.value = ConnectionState.Failed("reconnect attempts exhausted")
            return
        }
        val attempt = ++reconnectAttempts
        val delayMs = 2000L * (1 shl (attempt - 1).coerceAtMost(3))  // 2s, 4s, 8s, 16s, 16s
        appendLocal("[aMiNo] auto-reconnect attempt $attempt/5 in ${delayMs / 1000}s...\n")
        scope.launch {
            delay(delayMs)
            if (!isConnected && !userDisconnected && !connecting) connectInternal(context)
        }
    }

    fun send(text: String) {
        if (!isConnected) {
            appendLocal("[aMiNo] not connected - not sent: ${text.trimEnd()}\n")
            return
        }
        scope.launch {
            try {
                shell?.send(text)
            } catch (e: Throwable) {
                appendLocal("[aMiNo] send failed: ${e.message}\n")
            }
        }
    }

    fun runCommand(cmd: String) {
        send(if (cmd.endsWith("\n")) cmd else "$cmd\n")
    }

    /**
     * Structured, verified execution: a dedicated one-shot ADB connection per command
     * (the proven AdbClient path) with exit-code detection via an __AMINO_RC_ marker.
     * Used by the experimental check buttons — and later by the AI agent.
     */
    fun runCheck(cmd: String) {
        appendLocal("amino@device:~$ $cmd\n")
        scope.launch {
            val st = _state.value
            if (st !is ConnectionState.Connected) {
                appendLocal("[aMiNo] not connected.\n")
                return@launch
            }
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
                AdbClient("127.0.0.1", st.port, key).use { client ->
                    client.connect()
                    val sb = StringBuilder()
                    client.command("shell:$cmd; echo __AMINO_RC_\$?") { bytes -> sb.append(String(bytes)) }
                    val raw = sb.toString().replace("\r\n", "\n").replace('\r', '\n')

                    var rc: Int? = null
                    val out = StringBuilder()
                    for (line in raw.lines()) {
                        val m = Regex("__AMINO_RC_(\\d+)").find(line)
                        if (m != null) rc = m.groupValues[1].toIntOrNull()
                        else out.append(line).append('\n')
                    }
                    val clean = out.toString().trimEnd('\n')
                    if (clean.isNotEmpty()) appendLocal("$clean\n")

                    val verdict = when {
                        rc == null -> "[aMiNo] ⚠ exit code unknown"
                        rc == 0 -> "[aMiNo] ✓ succeeded (exit=0)"
                        else -> "[aMiNo] ✗ failed (exit=$rc)"
                    }
                    val extra = when {
                        rc == 0 && cmd.contains("AMINO_PING") -> " - pairing + connection verified ✓"
                        rc == 0 && clean.contains("libamino") -> " - aMiNo service is running ✓"
                        else -> ""
                    }
                    appendLocal(">>> $verdict$extra\n")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "runCheck failed", e)
                appendLocal("[aMiNo] ✗ execution failed: ${e.message}\n")
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectAttempts = 0
        runCatching { shell?.close() }
        shell = null
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun discoverPort(context: Context): Int? {
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                var mdns: AdbMdns? = null
                mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
                    if (it.second > 0 && cont.isActive) {
                        mdns?.stop()
                        cont.resume(it.second)
                    }
                }
                mdns.start()
                cont.invokeOnCancellation { mdns?.stop() }
            }
        }
    }
}
