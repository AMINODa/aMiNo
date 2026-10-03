package moe.shizuku.manager.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import moe.shizuku.manager.memory.MemoryRepository
import moe.shizuku.manager.receiver.SharinganContinuationReceiver

/**
 * r1418 — TaskMemory: the durable half of Sharingan's memory.
 *
 * Field report (user, verbatim): «ابدء التصوير وتوقف لمدة 10 ثواني — يفتح
 * الكاميرا يبداء التصوير لكن لا يتوقف — مشكلة ذاكرة». The independent audit
 * (M1/M2/M3) confirmed the root cause: the agent had NO way to represent
 * "do X at time T" — the pending step lived nowhere and died with the reply.
 *
 * Two tiers, honest about their guarantees:
 *  - IN-PROCESS (<= [IN_PROCESS_MAX_SECONDS]s): the runLoop coroutine stays
 *    alive under a PARTIAL_WAKE_LOCK (15-min hard cap; Android vitals flags
 *    wake locks >= 2h) and continues the same loop — exact, simplest path.
 *  - DURABLE (>120s): the pending step is persisted in SQLite
 *    (task_continuations) and armed with AlarmManager. It SURVIVES process
 *    death and reboot (SharinganBootReceiver re-arms). Exact alarms are used
 *    only when the system allows (SCHEDULE_EXACT_ALARM is user-granted on
 *    API 33+); otherwise setAndAllowWhileIdle — inexact in Doze, honest
 *    "approximately" wording in the notification.
 *
 * This is deliberately the LangGraph-checkpointer idea in plain SQLite:
 * persist the pending step + goal, resume from it, never trust RAM alone.
 */
object TaskMemory {

    private const val TAG = "TaskMemory"

    /** In-process wait ceiling (per task_wait call). Beyond this -> durable path. */
    const val IN_PROCESS_MAX_SECONDS = 120

    /** Hard ceiling for a scheduled continuation (24h). */
    const val SCHEDULE_MAX_SECONDS = 86_400

    /** WakeLock cap — well under the 2h/24h Android vitals excess threshold. */
    private const val WAKELOCK_MS = 15 * 60 * 1000L

    /** Busy-retry policy when a continuation fires while another task runs. */
    const val RETRY_DELAY_MS = 15_000L
    const val MAX_RETRIES = 20

    // ------------------------------------------------------------ wake lock

    @Volatile
    private var taskWake: PowerManager.WakeLock? = null

    /**
     * Keeps the CPU ticking during an in-process task (a 10s task_wait inside
     * Doze would otherwise never tick — M2). Never throws: on any SecurityException
     * the task still runs, just without the guarantee.
     */
    fun holdWake(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            taskWake?.let { if (it.isHeld) it.release() }
            taskWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amino:agent_task").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_MS)
            }
            Log.d(TAG, "wake lock held (${WAKELOCK_MS / 1000}s cap)")
        }.onFailure { Log.w(TAG, "wake lock unavailable: ${it.message}") }
    }

    fun dropWake() {
        runCatching { taskWake?.let { if (it.isHeld) it.release() } }
        taskWake = null
    }

    // ---------------------------------------------------------- scheduling

    /**
     * Persists a durable continuation and arms the alarm. Returns the row id.
     * The alarm targets an explicit, non-exported receiver (house pattern:
     * NotifAttemptReceiver) with FLAG_IMMUTABLE — API 36 compliant.
     */
    fun schedule(
        context: Context, conversationId: Long, goal: String,
        action: String, seconds: Int
    ): Long {
        val fireAt = System.currentTimeMillis() + seconds * 1000L
        val id = MemoryRepository.addContinuation(
            context, conversationId, goal, action, seconds, fireAt
        )
        armAlarm(context, id, fireAt)
        Log.i(TAG, "continuation #$id armed in ${seconds}s (durable): ${action.take(80)}")
        return id
    }

    /** (Re)arms the alarm for an existing row — used at boot and on busy-retry. */
    fun armAlarm(context: Context, continuationId: Long, fireAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context, continuationId)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                // Honest fallback: fires from Doze but may slide by minutes.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        }.onFailure { Log.w(TAG, "alarm arm failed: ${it.message}") }
    }

    fun cancelAlarm(context: Context, continuationId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(alarmIntent(context, continuationId)) }
    }

    private fun alarmIntent(context: Context, continuationId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (continuationId and 0x7FFFFFFFL).toInt(),
            Intent(context, SharinganContinuationReceiver::class.java)
                .putExtra(SharinganContinuationReceiver.EXTRA_ID, continuationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Boot/overdue safety net: re-arm every still-scheduled continuation whose
     * fire time is in the future; fire NOW anything overdue (process died or
     * device was off at the original fire time — the memory must not rot).
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val rows = runCatching { MemoryRepository.scheduledContinuations(context) }.getOrDefault(emptyList())
        for (row in rows) {
            if (row.fireAt > now + 1_000) {
                armAlarm(context, row.id, row.fireAt)
                Log.i(TAG, "re-armed #${row.id} at ${row.fireAt}")
            } else {
                Log.i(TAG, "overdue #${row.id} — resuming now")
                AgentOrchestrator.resumeScheduled(context, row.id)
            }
        }
    }
}
