package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import java.net.ConnectException

/**
 * aMiNo r1373 - the pairing-capture accessibility service now works on EVERY
 * device (phones AND TVs).
 *
 * aMiNo r1372 and earlier copied Shizuku's TV-only gate: on a phone the
 * service showed "only supported on TV devices" and immediately called
 * disableSelf() - so enabling it from system settings looked broken, and the
 * state chip went gray again by itself. That gate, the 60-second timeout and
 * every disableSelf() are REMOVED: the service stays exactly as enabled or
 * disabled by the user in the official system settings - it never turns
 * itself off, and the home chip always mirrors that real state.
 *
 * While enabled it listens ONLY to the Android settings app windows
 * (com.android.settings, com.android.tv.settings - see
 * accessibility_service_config.xml) and, when the wireless-debugging pairing
 * screen shows an IP:port plus a 6-digit code, it captures both and pairs -
 * ready to be reused for every new pairing code (state is reset after each
 * attempt). No other window content is ever read.
 */
class AdbPairingAccessibilityService : AccessibilityService() {

    private var port: Int? = null
    private var password: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // aMiNo r1373: no TV-only gate, no toast, no disableSelf(), no timeout.
        // The user enabled the service - it stays enabled until THEY disable it.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (port != null && password != null) return

        if ((event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) == 0) return
        val text = event.source?.text ?: return

        val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
        val passwordRegex = Regex("""\d{6}""")

        ipPortRegex.matchEntire(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.let { port = it }
        passwordRegex.matchEntire(text)
            ?.value
            ?.let { password = it }

        if (port != null && password != null) {
            val port = port!!
            val password = password!!

            var toastMsg = getString(R.string.notification_adb_pairing_failed_title)
            GlobalScope.launch(Dispatchers.IO) {
                val host = "127.0.0.1"

                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
                } catch (e: Throwable) {
                    toastMsg = getString(R.string.adb_error_key_store)
                    return@launch
                }

                AdbPairingClient(host, port, password, key).runCatching {
                    start()
                }.onFailure {
                    when (it) {
                        is ConnectException -> toastMsg = getString(R.string.cannot_connect_port)
                        is AdbInvalidPairingCodeException -> toastMsg = getString(R.string.paring_code_is_wrong)
                        is AdbKeyException -> toastMsg = getString(R.string.adb_error_key_store)
                    }
                }.onSuccess {
                    if (it) {
                        toastMsg = "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}"
                   
                        val intent = Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbPairingAccessibilityService, toastMsg, Toast.LENGTH_LONG).show()
                    // aMiNo r1373: reset so the NEXT pairing code is captured too.
                    // The service itself stays enabled - only the user may turn it
                    // off (no disableSelf() anywhere anymore).
                    this@AdbPairingAccessibilityService.port = null
                    this@AdbPairingAccessibilityService.password = null
                }
            }
        }
    }

    override fun onInterrupt() {}

}