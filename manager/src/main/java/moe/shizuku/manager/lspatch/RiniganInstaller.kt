package moe.shizuku.manager.lspatch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.terminal.linux.ShizukuExec
import moe.shizuku.manager.utils.ApkUtils.installPackage
import java.io.File

/**
 * aMiNo v2 — installer for the EMBEDDED Rinigan module APK (assets/rinigan/rinigan-6.3-amino.apk).
 *
 * Contract with the user's request: «تثبت اوتوماتيكيا عند تثبيت تطبيق amino» — everything ships
 * inside the aMiNo APK, and installation is attempted automatically. Android physics: a truly
 * silent install is only possible through an existing privileged shell (aMiNo's own Shizuku
 * service, already the app's core). Without it we fall back to the ONE system confirmation
 * (PackageInstaller) — honest, never fake.
 */
object RiniganInstaller {

    const val RINIGAN_PACKAGE = "com.rinigan.app"
    const val RINIGAN_ASSET = "rinigan/rinigan-6.3-amino.apk"
    const val EMBEDDED_VERSION_CODE = 36 // v6.3 aMiNo-themed rebuild (supersedes official 35)

    fun embeddedFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, "rinigan").apply { mkdirs() }
        val out = File(dir, "rinigan-6.3-amino.apk")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(RINIGAN_ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    fun installedVersion(context: Context): PackageInfo? = try {
        context.packageManager.getPackageInfo(RINIGAN_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** true when the embedded module adds anything over what is installed. */
    fun needsInstall(context: Context): Boolean {
        val pi = installedVersion(context) ?: return true
        return pi.longVersionCode < EMBEDDED_VERSION_CODE
    }

    /**
     * Silent path via the aMiNo shell service. r1428 FIX (same field failure as the
     * LSPatch companion — «Unable to open file: /data/user/0/...»): the APK now goes
     * the `adb install` way — streamed to /data/local/tmp through the shell's own
     * stdin, installed from there, cleaned up. Returns the real outcome:
     * null = Shizuku not available (caller falls back), Shot = actual pm result.
     */
    suspend fun silentInstall(context: Context): ShizukuExec.Shot? {
        if (!ShizukuExec.available()) return null
        val apk = embeddedFile(context)
        return ShizukuExec.installApk(apk, "amino_rinigan_${EMBEDDED_VERSION_CODE}.apk", timeoutMs = 120_000)
    }

    /** Platform path: one system confirmation dialog. */
    fun confirmInstall(context: Context, onDone: (Boolean, String?) -> Unit) {
        context.installPackage(embeddedFile(context), onDone)
    }

    /**
     * Auto-install hook for app start. Installed+current → no-op. Shizuku up → silent install
     * with a toast. Otherwise a ONE-TAP dialog (per aMiNo version, remembered in prefs).
     */
    fun ensureInstalledOnStart(activity: android.app.Activity) {
        if (!needsInstall(activity)) return
        val prefs = activity.getSharedPreferences("amino_v2", Context.MODE_PRIVATE)
        val key = "rinigan_prompted_$EMBEDDED_VERSION_CODE"
        Thread {
            val shot = kotlinx.coroutines.runBlocking { silentInstall(activity) }
            activity.runOnUiThread {
                when {
                    shot != null && shot.ok ->
                        Toast.makeText(activity, R.string.rinigan_install_silent, Toast.LENGTH_LONG).show()
                    prefs.getBoolean(key, false) -> Unit // already declined this version — drawer entry stays available
                    else -> {
                        prefs.edit().putBoolean(key, true).apply()
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.nav_rinigan)
                            .setMessage(activity.getString(R.string.rinigan_intro))
                            .setPositiveButton(R.string.rinigan_install) { _, _ ->
                                confirmInstall(activity) { ok, msg ->
                                    Toast.makeText(
                                        activity,
                                        if (ok) activity.getString(R.string.rinigan_install_sent)
                                        else activity.getString(R.string.lspatch_install_failed, msg ?: "?"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
        }.start()
    }
}
