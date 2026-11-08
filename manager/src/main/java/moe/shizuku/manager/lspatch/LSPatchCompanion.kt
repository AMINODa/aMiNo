package moe.shizuku.manager.lspatch

import android.content.Context
import android.content.Intent
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
 * aMiNo v2 — companion installer for the ORIGINAL LSPatch manager app
 * (org.lsposed.lspatch, built from the vendored JingMatrix v1.2 sources at
 * suite/lspatch with ONLY the colour palette matched to aMiNo — every screen,
 * setting and option is the upstream UI untouched).
 *
 * Field truth (user, verbatim): «واجهتها عند الدخول اليها ليس كما في الالاصلية …
 * اريدها كما في النسخة الاصلية غير فقط اللون» — the LSPatch drawer entry must open
 * the REAL original manager UI, not a re-implementation. Android physics: the
 * manager is its own app (its Application class, Koin graph, module binder
 * contracts and providers are keyed to the org.lsposed.lspatch identity), so the
 * honest way to show its original UI is to SHIP it — embedded in aMiNo's assets
 * and installed automatically — then launch it. Same delivery contract as the
 * Rinigan module: «تثبت اوتوماتيكيا عند تثبيت تطبيق amino».
 *
 * Install order: silent through the aMiNo shell service when it is up, else the
 * ONE system PackageInstaller confirmation — never fake, never silent-by-lie.
 */
object LSPatchCompanion {

    const val COMPANION_PACKAGE = "org.lsposed.lspatch"
    const val COMPANION_ASSET = "lspatch/aminopatcher-1.2-amino.apk"
    /** VENDORED_VERSION of the embedded build (upstream v1.2 = 487; this rebuild = 488). */
    const val EMBEDDED_VERSION_CODE = 488

    fun embeddedFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, "lspatch-manager").apply { mkdirs() }
        val out = File(dir, "aminopatcher-1.2-amino.apk")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(COMPANION_ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    fun installedVersion(context: Context): PackageInfo? = try {
        context.packageManager.getPackageInfo(COMPANION_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** true when the embedded companion adds anything over what is installed. */
    fun needsInstall(context: Context): Boolean {
        val pi = installedVersion(context) ?: return true
        return pi.longVersionCode < EMBEDDED_VERSION_CODE
    }

    /**
     * Silent path via the aMiNo shell service. r1428 FIX (user screenshot, verbatim:
     * «Install failed: Error: Unable to open file: /data/user/0/com.amino.manager/...»):
     * the APK was staged in the app-private noBackupFilesDir, which `pm` (shell uid)
     * can never read. Now it goes the `adb install` way — streamed to
     * /data/local/tmp through the shell's own stdin, installed from there, cleaned up.
     * Returns the real outcome: null = Shizuku not available (caller falls back),
     * Shot = actual pm result.
     */
    suspend fun silentInstall(context: Context): ShizukuExec.Shot? {
        if (!ShizukuExec.available()) return null
        val apk = embeddedFile(context)
        return ShizukuExec.installApk(apk, "amino_lspatch_${EMBEDDED_VERSION_CODE}.apk", timeoutMs = 180_000)
    }

    /** Platform path: one system confirmation dialog. */
    fun confirmInstall(context: Context, onDone: (Boolean, String?) -> Unit) {
        context.installPackage(embeddedFile(context), onDone)
    }

    /** Launch the ORIGINAL manager UI — its real MainActivity, unmodified. */
    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(COMPANION_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /**
     * Auto-install hook for app start. Installed+current → no-op. Shizuku up → silent
     * install with a toast. Otherwise a ONE-TAP dialog (per aMiNo version, remembered in prefs).
     */
    fun ensureInstalledOnStart(activity: android.app.Activity) {
        if (!needsInstall(activity)) return
        val prefs = activity.getSharedPreferences("amino_v2", Context.MODE_PRIVATE)
        val key = "lspatch_prompted_$EMBEDDED_VERSION_CODE"
        Thread {
            val shot = kotlinx.coroutines.runBlocking { silentInstall(activity) }
            activity.runOnUiThread {
                when {
                    shot != null && shot.ok ->
                        Toast.makeText(activity, R.string.lspatch_install_silent, Toast.LENGTH_LONG).show()
                    prefs.getBoolean(key, false) -> Unit // already declined this version — drawer entry stays available
                    else -> {
                        prefs.edit().putBoolean(key, true).apply()
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.nav_lspatch)
                            .setMessage(activity.getString(R.string.lspatch_intro))
                            .setPositiveButton(R.string.lspatch_install_companion) { _, _ ->
                                confirmInstall(activity) { ok, msg ->
                                    Toast.makeText(
                                        activity,
                                        if (ok) activity.getString(R.string.lspatch_install_sent)
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
