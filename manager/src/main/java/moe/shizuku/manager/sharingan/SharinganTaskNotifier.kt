package moe.shizuku.manager.sharingan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.AgentHomeActivity

/**
 * r1415 — Sharingan BACKGROUND TASK notifications.
 *
 * The user's spec, verbatim: «يجب نقل الأوامر إلى aMiNo ليُطبّق في الخلفية
 * دون الحاجة إلى التطبيق المفتوح أو الواجهة — هذا هو دور الشارينغان».
 *
 * A command typed in the floating panel now runs entirely in the background
 * (AgentOrchestrator's own scope + SQLite persistence; the process stays alive
 * because the accessibility service is system-bound). The user stays inside
 * whatever app they were using; the outcome arrives HERE:
 *   - "started"  : ongoing "running in background" notice (replaces itself)
 *   - "done"     : heads-up with the REAL final answer text (never invented —
 *                  it is the last assistant row of the conversation), tap → chat
 *
 * Honest by design: when notifications are disabled nothing is posted and the
 * panel tells the user the result is in the chat instead.
 */
object SharinganTaskNotifier {

    private const val CHANNEL_TASKS = "sharingan_tasks"
    private const val ID_TASK = 3001
    private const val ID_SCHEDULED = 3003 // r1418 — armed durable continuation card
    private const val MAX_NOTIF_TEXT = 450

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_TASKS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_TASKS,
                        context.getString(R.string.sharingan_task_chn),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { setShowBadge(true) }
                )
            }
        }
    }

    private fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun chatIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            3002,
            Intent(context, AgentHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Posted the moment a panel command starts executing in the background. */
    fun taskStarted(context: Context, command: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val preview = command.take(80) + if (command.length > 80) "…" else ""
        val n = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(0xFFE53935.toInt())
            .setContentTitle(context.getString(R.string.sharingan_task_started_title))
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(chatIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_TASK, n) }
    }

    /**
     * r1418 — a DURABLE continuation was armed (task_wait > 120s): the pending
     * step is saved in local memory and will run even if the app is closed.
     * Ongoing card so the user always sees what is still pending (M1 honesty).
     */
    fun taskScheduled(context: Context, action: String, fireAt: Long) {
        if (!canPost(context)) return
        ensureChannel(context)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val body = context.getString(R.string.sharingan_task_scheduled_body, action.take(80), fmt.format(java.util.Date(fireAt)))
        val n = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(0xFFE53935.toInt())
            .setContentTitle(context.getString(R.string.sharingan_task_scheduled_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(chatIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_SCHEDULED, n) }
    }

    /** The continuation fired (or was abandoned) — the pending-step card is obsolete. */
    fun cancelScheduled(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SCHEDULED) }
    }

    /**
     * Posted when the background run ends. `finalText` is the REAL last
     * assistant message (answer, honest error, or cancellation note) — never
     * a fabricated summary.
     */
    fun taskDone(context: Context, finalText: String) {
        val isError = finalText.startsWith("⚠️")
        if (!canPost(context)) return
        ensureChannel(context)
        val body = finalText.take(MAX_NOTIF_TEXT) + if (finalText.length > MAX_NOTIF_TEXT) "…" else ""
        val n = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(0xFFE53935.toInt())
            .setContentTitle(
                context.getString(
                    if (isError) R.string.sharingan_task_err_title
                    else R.string.sharingan_task_done_title
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(chatIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_TASK, n) }
    }
}
