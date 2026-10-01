package moe.shizuku.manager.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * aMiNo r1400 — crash-safe toasts.
 *
 * Toast.makeText(...).show() MUST run on a thread that has a Looper. On
 * Android 13 a toast raised from a plain background thread (a Shizuku binder
 * callback, a Dispatchers.IO coroutine, ...) throws
 * "NullPointerException: Can't toast on a thread that has not called
 * Looper.prepare()" and the WHOLE PROCESS dies — the r1399 device crash:
 * a command-execution failure toasted from the terminal's IO coroutine, the
 * app crashed instantly, and the orphaned PRoot tree left behind then broke
 * the next launch (INIT_TIMEOUT + "Function not implemented").
 *
 * Every potentially-background call site routes through this helper: a
 * caller already on the main thread toasts synchronously (zero behavior
 * change), anything else posts to the main looper. The toast uses the
 * application context so a posted toast never leaks a destroyed activity.
 */
object Toasts {

    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context?, text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        if (context == null || text.isBlank()) return
        val app = context.applicationContext ?: return
        val show = Runnable { Toast.makeText(app, text, duration).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else main.post(show)
    }

    fun show(context: Context?, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        if (context == null) return
        show(context, context.getString(resId), duration)
    }
}
