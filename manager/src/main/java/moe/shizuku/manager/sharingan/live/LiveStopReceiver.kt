package moe.shizuku.manager.sharingan.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * aMiNo 2.0 (r1437) — the notification's END action: the ONE tap that ends
 * the broadcast from anywhere (lock screen included). Same stop path as the
 * dock ⏹ and the panel — one truth, three doors.
 */
class LiveStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try { LiveBroadcastService.stopBroadcast(context) } catch (_: Throwable) {}
        try {
            Toast.makeText(context,
                context.getString(moe.shizuku.manager.R.string.sharingan_live_stopped),
                Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }
}
