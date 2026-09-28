package moe.shizuku.manager.shell

import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityShellBinding
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLProtocolException

/**
 * aMiNo Shell - an in-app terminal directly paired with the device.
 *
 * The ADB connection is owned by [ShellSession] and intentionally stays open
 * after pairing; this screen only attaches to it (and can detach freely).
 */
class ShellActivity : AppBarActivity() {

    private lateinit var binding: ActivityShellBinding
    private var spinAnimator: android.animation.ObjectAnimator? = null

    private val prompt = "amino@device:~$ "

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = getString(R.string.shell_title)

        binding = ActivityShellBinding.inflate(layoutInflater, rootView, true)

        appendLine(getString(R.string.shell_welcome))

        binding.send.setOnClickListener {
            val text = binding.input.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                when (text.lowercase()) {
                    "help" -> {
                        appendLine("$prompt$text")
                        appendLine(
                            "aMiNo Shell - experimental checks:\n" +
                            "  echo AMINO_PING          -> verify pairing + connection\n" +
                            "  whoami / id              -> current adb user\n" +
                            "  getprop ro.product.model -> device model\n" +
                            "  ps -A | grep -i libamino -> aMiNo server process\n" +
                            "  settings get global adb_wifi_enabled -> wadb state\n" +
                            "  clear                    -> clear this screen\n"
                        )
                    }
                    "clear" -> {
                        binding.console.text = ""
                        appendLine(getString(R.string.shell_welcome))
                    }
                    // no local echo: the device PTY echoes the command itself (real terminal behavior)
                    else -> ShellSession.runCommand(text)
                }
                binding.input.setText("")
            }
        }

        binding.input.setOnEditorActionListener { _, _, _ ->
            binding.send.performClick()
            true
        }

        binding.clear.setOnClickListener {
            binding.console.text = ""
            appendLine(getString(R.string.shell_welcome))
        }

        binding.reconnect.setOnClickListener { ShellSession.connect(this) }

        binding.cmdPing.setOnClickListener { ShellSession.runCheck("echo AMINO_PING") }
        binding.cmdWhoami.setOnClickListener { ShellSession.runCheck("whoami; id") }
        binding.cmdDevice.setOnClickListener {
            ShellSession.runCheck("getprop ro.product.model; getprop ro.build.version.release")
        }
        binding.cmdService.setOnClickListener { ShellSession.runCheck("ps -A | grep -i libamino") }
        binding.cmdWadb.setOnClickListener { ShellSession.runCheck("settings get global adb_wifi_enabled") }

        // live state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ShellSession.state.collect { renderState(it) }
                }
                launch {
                    ShellSession.output.collect { appendRaw(it) }
                }
            }
        }

        // attach (or connect) the persistent session
        if (ShellSession.isConnected) {
            appendLine("[aMiNo] attached to the already-open session.")
        } else {
            ShellSession.connect(this)
        }
    }

    private fun renderState(state: ShellSession.ConnectionState) {
        when (state) {
            is ShellSession.ConnectionState.Connecting -> {
                binding.status.text = getString(R.string.shell_status_connecting)
                binding.statusDetail.text = ""
                binding.reconnect.visibility = View.GONE
                spin(900)
            }
            is ShellSession.ConnectionState.Connected -> {
                binding.status.text = getString(R.string.shell_status_connected)
                binding.statusDetail.text = "127.0.0.1:${state.port}"
                binding.status.setTextColor(0xFF69F0AE.toInt())
                binding.reconnect.visibility = View.GONE
                spin(2600)
            }
            is ShellSession.ConnectionState.Failed -> {
                binding.status.text = getString(R.string.shell_status_disconnected)
                binding.status.setTextColor(0xFFFF5252.toInt())
                binding.statusDetail.text = state.message
                binding.reconnect.visibility = View.VISIBLE
                spinPause()
            }
            is ShellSession.ConnectionState.Disconnected -> {
                binding.status.text = getString(R.string.shell_status_disconnected)
                binding.status.setTextColor(0xFFFF5252.toInt())
                binding.statusDetail.text = getString(R.string.shell_disconnected)
                binding.reconnect.visibility = View.VISIBLE
                spinPause()
            }
        }
    }

    private fun spin(durationMs: Long) {
        spinPause()
        spinAnimator = android.animation.ObjectAnimator.ofFloat(binding.sharingan, View.ROTATION, 0f, 360f).apply {
            interpolator = LinearInterpolator()
            duration = durationMs
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
    }

    private fun spinPause() {
        spinAnimator?.cancel()
        spinAnimator = null
    }

    private fun appendRaw(text: String) {
        binding.console.append(text)
        binding.consoleScroll.post {
            binding.consoleScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendLine(text: String) {
        appendRaw("$text\n")
    }

    override fun onDestroy() {
        spinPause()
        // NOTE: the ShellSession is deliberately NOT closed here -
        // the whole point of aMiNo Shell is that the connection stays open.
        super.onDestroy()
    }
}
