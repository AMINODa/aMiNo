package moe.shizuku.manager.sharingan.live

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * aMiNo 2.0 (r1437) — MIC/NOTIFICATION permission trampoline (a translucent
 * activity with NO UI). The floating panel is an accessibility overlay — it
 * CANNOT host runtime permission dialogs. This activity exists only to ask,
 * then immediately hand over to the projection consent dialog.
 *
 * HONEST DEGRADATION: mic denied → the broadcast still starts (vision-only),
 * the user is told once, and the panel shows it — never a silent deaf live.
 */
class LivePermissionActivity : Activity() {

    private val requested = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val need = ArrayList<String>()
        if (!LiveBroadcastService.micGranted(this)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            startConsent()
            return
        }
        requested.addAll(need)
        requestPermissions(need.toTypedArray(), 7002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micOk = !requested.contains(Manifest.permission.RECORD_AUDIO) ||
            grantResults.getOrNull(requested.indexOf(Manifest.permission.RECORD_AUDIO)) ==
            PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            SharinganStateAccess.update { it.copy(lastError = "live_mic_denied_vision_only") }
        }
        // r1437-expert-fix: POST_NOTIFICATIONS denied → the FGS notification
        // (the broadcast's identity + its ⏹ action) is suppressed on 13+.
        // The dock still works; the user gets an honest hint instead of a
        // "missing notification" mystery.
        val notifOk = !requested.contains(Manifest.permission.POST_NOTIFICATIONS) ||
            grantResults.getOrNull(requested.indexOf(Manifest.permission.POST_NOTIFICATIONS)) ==
            PackageManager.PERMISSION_GRANTED
        if (!notifOk) {
            SharinganStateAccess.update { it.copy(lastError = "live_notifications_denied") }
        }
        startConsent()
    }

    private fun startConsent() {
        try {
            startActivity(Intent(this, LiveConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Throwable) { }
        finish()
    }
}

/** Indirection so the activities can touch SharinganState without a cycle. */
internal object SharinganStateAccess {
    fun update(transform: (moe.shizuku.manager.sharingan.SharinganUiState) -> moe.shizuku.manager.sharingan.SharinganUiState) {
        moe.shizuku.manager.sharingan.SharinganState.update(transform)
    }
}
