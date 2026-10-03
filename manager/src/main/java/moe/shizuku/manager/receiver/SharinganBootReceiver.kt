package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.agent.TaskMemory

/**
 * r1418 — re-arms durable task continuations after a reboot.
 *
 * The whole point of TaskMemory is that a pending step ("stop the recording
 * at 21:30") is remembered even if the phone restarts. Alarms do not survive
 * reboot; the SQLite rows DO — this receiver re-arms them (and fires overdue
 * ones immediately). Receives only the protected BOOT_COMPLETED broadcast;
 * exported=false (system can still deliver — WorkManager's own receiver ships
 * the same way).
 */
class SharinganBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        Log.i("SharinganBoot", "boot — re-arming durable task memory")
        runCatching { TaskMemory.rescheduleAll(app) }
            .onFailure { Log.w("SharinganBoot", "reschedule failed: ${it.message}") }
    }
}
