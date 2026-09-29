package moe.shizuku.manager.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * aMiNo r1374: last-resort crash visibility.
 *
 * Wraps the default UncaughtExceptionHandler so any fatal crash is first persisted to
 * filesDir/amino_crash.log, then handed to the system handler as usual (process dies).
 * On the next launch of HomeActivity the report is shown in a dialog and the file is
 * removed, so each crash is reported exactly once. This exists so users can send us
 * real stack traces instead of "the app closed by itself".
 */
object AminoCrashGuard {

    private const val TAG = "AminoCrashGuard"
    private const val FILE_NAME = "amino_crash.log"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Guard) return
        Thread.setDefaultUncaughtExceptionHandler(Guard(context.applicationContext, previous))
    }

    /** Returns the last crash report (if any) and deletes it - reported exactly once. */
    fun takeLastCrash(context: Context): String? {
        return runCatching {
            val f = File(context.filesDir, FILE_NAME)
            val text = if (f.exists() && f.length() > 0) f.readText() else null
            f.delete()
            text?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private class Guard(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = Log.getStackTraceString(throwable)
                File(appContext.filesDir, FILE_NAME).writeText(
                    "aMiNo crashed at $ts\n" +
                        "android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                        "thread: ${thread.name}\n" +
                        "$trace\n"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
