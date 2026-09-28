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
    private var sessionScope: CoroutineScope? = null
    private var connecting = false

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected && shell?.isOpen == true

    fun appendLocal(line: String) {
        scope.launch { _output.emit(line) }
    }

    /**
     * Connect (or reconnect) the persistent shell.
     * Order: last known working port -> mDNS discovery of the wireless debugging port.
     */
    @Synchronized
    fun connect(context: Context) {
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
                        val text = String(data)
                        _output.tryEmit(text)
                        interpret(appContext, text)
                    },
                    onClosed = { err ->
                        Log.w(TAG, "shell closed", err)
                        appendLocal("\n[aMiNo] ${err?.message ?: "connection closed"}\n")
                        _state.value = ConnectionState.Disconnected
                        shell = null
                    }
                )
                shell = s
                ShizukuSettings.setShellPort(port)
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

    /** Emit a friendly interpretation line when known diagnostic output is detected. */
    private fun interpret(context: Context, text: String) {
        when {
            text.contains("AMINO_PING") -> appendLocal(
                "\n>>> ✓ " + context.getString(moe.shizuku.manager.R.string.shell_pair_ok) + "\n"
            )
            text.contains("libamino") -> appendLocal(
                "\n>>> ✓ " + context.getString(moe.shizuku.manager.R.string.shell_service_ok) + "\n"
            )
        }
    }

    fun send(text: String) {
        if (!isConnected) {
            appendLocal("[aMiNo] not connected.\n")
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

    fun disconnect() {
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
