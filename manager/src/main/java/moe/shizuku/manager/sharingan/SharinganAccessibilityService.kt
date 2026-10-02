package moe.shizuku.manager.sharingan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityButtonController
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * aMiNo 1.5.0-alpha — the accessibility service that hosts Sharingan Live.
 *
 * Repurposes the system ACCESSIBILITY BUTTON (PLAN_aMiNo2.md §2.1):
 * the user enables this service once (Settings → Accessibility → aMiNo
 * Sharingan Live), the accessibility button appears in the navigation bar,
 * and each tap toggles the floating control panel — built as
 * TYPE_ACCESSIBILITY_OVERLAY, so NO SYSTEM_ALERT_WINDOW permission is needed.
 *
 * Note: the accessibility button itself exists on API 26+; on older devices
 * the service still connects (honest LED) but the panel entry point requires
 * the button. minSdk is 24 — the button is simply not rendered there by the OS.
 */
class SharinganAccessibilityService : AccessibilityService() {

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ask the system for the accessibility button (idempotent).
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        // The button tap arrives via AccessibilityButtonController (API 26+),
        // not via an overridable service method.
        buttonCallback?.let { getAccessibilityButtonController().unregisterAccessibilityButtonCallback(it) }
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                SharinganPanel.toggle(this@SharinganAccessibilityService)
            }
        }
        buttonCallback = cb
        getAccessibilityButtonController()
            .registerAccessibilityButtonCallback(cb, Handler(Looper.getMainLooper()))
        SharinganState.update { it.copy(serviceUp = true) }
        instance = this
    }

    // We do not consume events in the alpha (canRetrieveWindowContent=false);
    // the service exists to host the overlay + the button.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        SharinganState.update { it.copy(serviceUp = false) }
        if (instance === this) instance = null
        SharinganPanel.hide()
        try {
            buttonCallback?.let { getAccessibilityButtonController().unregisterAccessibilityButtonCallback(it) }
        } catch (_: Throwable) {}
        buttonCallback = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SharinganState.update { it.copy(serviceUp = false) }
        if (instance === this) instance = null
        SharinganPanel.hide()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: SharinganAccessibilityService? = null
            private set
    }
}
