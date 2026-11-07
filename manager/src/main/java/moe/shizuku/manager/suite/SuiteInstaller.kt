package moe.shizuku.manager.suite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.shell.ShellSession
import java.io.File

/**
 * aMiNo v2 SUITE — installs the bundled suite APKs (aMiNo Rinigan, aMiNo Patcher)
 * that ship inside aMiNo's assets/suite/ folder.
 *
 * Roles stay consistent: aMiNo only INSTALLS and LAUNCHES the suite apps; it never
 * re-implements what they do (patching stays in the Patcher, camera-link rendering
 * stays in Rinigan).
 *
 * Two honest install paths:
 *  - SHELL: when the wireless ADB shell is connected (the normal aMiNo flow) the APK
 *    is copied to the app's external files dir (shell uid can read it — /data/data it
 *    cannot) and `pm install -r` runs through AgentTools.shellCommand, silent, no taps.
 *  - INSTALLER: otherwise a FileProvider content URI opens the system package installer
 *    (one manual tap per app).
 */
object SuiteInstaller {

    data class SuiteApp(
        val label: String,
        val packageName: String,
        val assetName: String
    )

    val APPS = listOf(
        SuiteApp("aMiNo Rinigan", "com.rinigan.app", "suite/rinigan-v6.3.apk"),
        SuiteApp("aMiNo Patcher", "org.lsposed.lspatch", "suite/patcher-v1.2.apk")
    )

    private fun externalSuiteDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "suite")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheSuiteDir(context: Context): File {
        val dir = File(context.cacheDir, "suite")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun stagedApk(context: Context, app: SuiteApp): File =
        File(externalSuiteDir(context), File(app.assetName).name)

    /** Copy the bundled APK asset to both stage locations; returns true on success. */
    fun stage(context: Context, app: SuiteApp): Boolean = runCatching {
        context.assets.open(app.assetName).use { input ->
            val ext = stagedApk(context, app)
            ext.outputStream().use { input.copyTo(it) }
            val cache = File(cacheSuiteDir(context), File(app.assetName).name)
            ext.inputStream().use { it.copyTo(cache.outputStream()) }
        }
        true
    }.getOrDefault(false)

    fun shellConnected(): Boolean =
        ShellSession.state.value is ShellSession.ConnectionState.Connected

    /**
     * Silent install through the ADB shell. Returns Result.success(output) when
     * pm reports Success, Result.failure(message) otherwise — never a fake result.
     */
    fun shellInstall(context: Context, app: SuiteApp): Result<String> {
        val apk = stagedApk(context, app)
        if (!apk.exists() || apk.length() == 0L) {
            return Result.failure(IllegalStateException("staged apk missing"))
        }
        val res = AgentTools.shellCommand(context, "pm install -r ${apk.absolutePath}")
        val out = res.output.trim()
        return if (res.ok && out.contains("Success")) {
            Result.success(out)
        } else {
            Result.failure(IllegalStateException(out.ifBlank { "pm install failed" }))
        }
    }

    /** Fall back to the system package installer (file already staged in cacheDir). */
    fun installerIntent(context: Context, app: SuiteApp): Intent? {
        val cache = File(cacheSuiteDir(context), File(app.assetName).name)
        if (!cache.exists() || cache.length() == 0L) return null
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", cache
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isInstalled(context: Context, app: SuiteApp): Boolean = runCatching {
        context.packageManager.getPackageInfo(app.packageName, 0)
        true
    }.getOrDefault(false)

    fun installedVersion(context: Context, app: SuiteApp): String = runCatching {
        context.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun launch(context: Context, app: SuiteApp): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else false
    }.getOrDefault(false)
}
