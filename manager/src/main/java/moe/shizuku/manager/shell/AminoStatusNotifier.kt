package moe.shizuku.manager.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R

/**
 * aMiNo r1372 - REAL Android notifications owned by aMiNo (never faked to look
 * like system settings). Posted through the standard Notification API on the
 * app's own channel so the user always sees they come from aMiNo.
 *
 * Only the true current state is ever displayed:
 *   - device paired        (after a successful pairing)
 *   - shell session online (live ADB connection established)
 *   - connection lost      (session dropped)
 *
 * Nothing is shown when notifications are disabled by the user/Android - aMiNo
 * never claims something it did not actually display.
 */
object AminoStatusNotifier {

    const val CHANNEL_STATUS = "amino_status"

    private const val ID_PAIRED = 2001
    private const val ID_SHELL = 2002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_STATUS,
                        context.getString(R.string.notification_channel_status),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(0xFFD32F2F.toInt())
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

    /** Posted once right after a successful pairing with the device. */
    fun onPaired(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)

        val n = base(context)
            .setContentTitle(context.getString(R.string.notification_paired_title))
            .setContentText(context.getString(R.string.notification_paired_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notification_paired_text)))
            .setOngoing(false)
            .setContentIntent(contentIntent(context))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID_PAIRED, n) }
    }

    /**
     * Mirror of [ShellSession.ConnectionState] into the status bar.
     * Called on every real state transition of the persistent shell session.
     */
    fun onShellStateChanged(context: Context, state: ShellSession.ConnectionState) {
        if (!canPost(context)) return
        ensureChannel(context)

        val nm = NotificationManagerCompat.from(context)

        when (state) {
            is ShellSession.ConnectionState.Connected -> {
                val n = base(context)
                    .setContentTitle(context.getString(R.string.notification_shell_connected_title))
                    .setContentText(
                        context.getString(R.string.notification_shell_connected_text, "127.0.0.1:${state.port}")
                    )
                    .setOngoing(true)
                    .setContentIntent(contentIntent(context))
                    .build()
                runCatching { nm.notify(ID_SHELL, n) }
            }
            is ShellSession.ConnectionState.Failed -> {
                val n = base(context)
                    .setContentTitle(context.getString(R.string.notification_disconnected_title))
                    .setContentText(context.getString(R.string.notification_disconnected_text))
                    .setOngoing(false)
                    .build()
                runCatching { nm.notify(ID_SHELL, n) }
            }
            is ShellSession.ConnectionState.Disconnected -> {
                val n = base(context)
                    .setContentTitle(context.getString(R.string.notification_disconnected_title))
                    .setContentText(context.getString(R.string.notification_disconnected_text))
                    .setOngoing(false)
                    .build()
                runCatching { nm.notify(ID_SHELL, n) }
            }
            is ShellSession.ConnectionState.Connecting -> {
                // transitional state - keep the previous notification untouched
                // instead of spamming "connecting" updates
            }
        }
    }

    /** Called when the user stops wanting any shell session (e.g. app teardown). */
    fun clearShell(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SHELL) }
    }

    private fun contentIntent(context: Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
}
