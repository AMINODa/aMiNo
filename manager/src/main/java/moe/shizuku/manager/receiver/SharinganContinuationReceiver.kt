package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.agent.AgentOrchestrator

/**
 * r1418 — receives the AlarmManager fire for one durable task continuation.
 *
 * Non-exported + explicit-component PendingIntent only (house pattern:
 * NotifAttemptReceiver) — nothing external can inject a resume.
 *
 * Fast-path contract: [AgentOrchestrator.resumeScheduled] does its own
 * scoping (launches the real work on the orchestrator's IO scope) and
 * returns immediately, so onReceive never trips the 10s broadcast limit.
 */
class SharinganContinuationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return
        val app = context.applicationContext
        AgentOrchestrator.resumeScheduled(app, id)
    }

    companion object {
        const val EXTRA_ID = "continuation_id"
    }
}
