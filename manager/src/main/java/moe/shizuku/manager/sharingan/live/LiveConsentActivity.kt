package moe.shizuku.manager.sharingan.live

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import moe.shizuku.manager.terminal.linux.ShizukuExec

/**
 * aMiNo 2.0 (r1437) — PROJECTION CONSENT trampoline (translucent, no UI):
 * shows the SYSTEM MediaProjection dialog (exactly Gemini's "Start recording
 * with…" consent), then starts LiveBroadcastService with the result.
 *
 * Denial is honest too: if the shell is up the broadcast continues with real
 * shell-captured frames (slower cadence — stated in the notification); if
 * the shell is down the broadcast refuses with a visible reason instead of
 * pretending to watch.
 */
class LiveConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 7001)
        } catch (_: Throwable) {
            // No projection capability on this ROM — straight to honest fallback.
            finishWithFallback()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001 && resultCode == RESULT_OK && data != null) {
            LiveBroadcastService.startProjection(this, resultCode, data, LiveBroadcastService.micGranted(this))
        } else {
            finishWithFallback()
        }
        finish()
    }

    private fun finishWithFallback() {
        if (ShizukuExec.available()) {
            LiveBroadcastService.startShellFallback(this, LiveBroadcastService.micGranted(this))
            SharinganStateAccess.update { it.copy(lastError = "live_projection_denied_shell_mode") }
        } else {
            SharinganStateAccess.update { it.copy(lastError = "live_no_source_projection_denied_shell_down") }
        }
    }
}
