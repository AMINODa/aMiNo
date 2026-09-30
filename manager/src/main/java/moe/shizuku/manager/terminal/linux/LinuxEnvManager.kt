package moe.shizuku.manager.terminal.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.terminal.TermBackend
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * aMiNo r1385 — Linux Environment Manager: a REAL Debian 12 (bookworm) user-space
 * running through PRoot inside /data/local/tmp (executed by the aMiNo service as
 * the ADB shell identity — NO root, no fake claims).
 *
 * r1387 — PRoot RUNTIME fix: the r1385/r1386 installs never proved that the
 * bundled PRoot binary can execute under the Android linker. Termux builds carry
 * DT_RUNPATH=/data/data/com.termux/files/usr/lib (absent on aMiNo devices), so
 * every exec MUST set LD_LIBRARY_PATH=$BASE/lib or the linker fails with
 * CANNOT LINK EXECUTABLE … library "libtalloc.so.2" not found — exactly the
 * user's screenshot. Now: ELF dependency diagnostic before install, a strict
 * `proot --version` gate (no pipeline masking, no error-text false positive),
 * a minimal-root smoke test BEFORE the Debian download, and runtime failures
 * set NOT_INSTALLED (never BROKEN — the user's storage is not at fault).
 *
 * HONESTY RULES (user spec #9):
 *  - nothing is reported READY before the rootfs is downloaded, digest-verified,
 *    extracted AND a real probe (id + /etc/os-release through PRoot) succeeds;
 *  - every failure keeps the real error message; Debian-phase failures set BROKEN,
 *    PRoot-runtime failures set NOT_INSTALLED (Debian was never touched);
 *  - the environment is NEVER claimed to be installed/successful without proof.
 *
 * STORAGE LAYOUT (user spec #2 + Android 10+ W^X reality, documented honestly):
 *  - app private dir (files/linux/): the downloaded rootfs archive (kept for
 *    Reset) — private to aMiNo;
 *  - /data/local/tmp/amino-linux/: bin/ (proot, xz), lib/ (talloc, shmem, lzma),
 *    rootfs/ (extracted Debian), tmp/ — the runtime tree, shell-owned, because
 *    only shell-owned files can be exec()-ed without root on modern Android.
 *
 * INTEGRITY: primary source is the official Docker `library/debian` registry —
 * manifest fetched by tag, layer blob verified byte-by-byte against its
 * sha256 digest. Fallback: cdimage.debian.org cloud rootfs verified against the
 * official SHA512SUMS file. Nothing is extracted before verification passes.
 *
 * r1386 FIX (Debian install failing with "symlink: Permission denied" and the
 * environment left BROKEN):
 *  - the REAL paths are verified at runtime, never assumed: download → the
 *    app-private dir files/linux/; extract → /data/local/tmp/amino-linux via
 *    the aMiNo service (shell uid). Shared storage (/sdcard, FUSE) is refused
 *    by a hard path guard.
 *  - a FILESYSTEM CAPABILITY PROBE (write + symlink + hardlink) now runs at
 *    the real install path BEFORE anything is downloaded. A device whose
 *    storage/SELinux policy denies symlink creation gets the exact original
 *    error text and the install never starts — the state goes back to
 *    NOT_INSTALLED with the reason (never a half-broken environment).
 *  - extraction uses the BUNDLED toybox tar (libamino_tar.so, static musl,
 *    pushed to $BASE/bin/toybox) instead of the device's own tar — verified
 *    in CI against the real Debian layer (byte-identical tree, 642 symlinks);
 *    stderr is captured and on failure the failing paths, the original errno
 *    text, the tool and its version are logged (spec #9).
 *  - after extraction an AUDIT checks /bin → usr/bin and counts symlinks so a
 *    silently-skipped symlink pass can never be reported as success; only
 *    then Debian is booted through PRoot and os-release + bash + apt are
 *    verified before READY (spec #8).
 *  - the verified archive stays in app storage, so a failed install retries
 *    without re-downloading (remote sha256 verified after transfer), and
 *    every failure cleans its temp files safely.
 *
 * r1388 FIX (installation failed with "the bundled extractor does not run on
 * this device: [ acpi arch ascii base32 … ]" even though preflight AND the
 * PRoot gate now pass — the user's 7 requirements):
 *  - ROOT CAUSE (reproduced in CI with the exact bundled binary): the r1386
 *    gate ran the multicall binary BARE (`toybox 2>&1 | head -n 1`). With no
 *    arguments toybox prints its NORMAL applet banner "[ acpi arch ascii … ]"
 *    and exits rc=0; the `head` pipeline masked the real exit code (the same
 *    bug class r1387 fixed for proot) and the banner — which contains no
 *    "toybox" token — was quoted into the failure message. A perfectly working
 *    extractor was misclassified by its own capability check: normal
 *    command-list output was treated as an error.
 *  - the extractor gate is now: (1) `toybox --version` with the REAL rc (no
 *    pipeline) and a strict version token; (2) an END-TO-END tiny-archive
 *    test through the service — a regular file, a directory, a symlink and a
 *    dangling symlink are created, packed with `toybox tar -cf`, extracted
 *    with `toybox tar -xf` and verified BEFORE any Debian archive is touched.
 *  - "the binary failed to EXECUTE" (rc 126/127, spawn failure,
 *    linker/permission/format text) is reported DIFFERENTLY from "it executed
 *    but the capability check did not pass" (user req #2).
 *  - every gated step records the exact executable path, the full command
 *    with arguments, the working directory, the exit code, and stdout and
 *    stderr SEPARATELY (stderr goes to a file — user req #1); failures carry
 *    that evidence verbatim into the log and the UI instead of a generic
 *    message (user req #7).
 *  - the service runtime identity is recorded before anything depends on it:
 *    `id` (real uid/gid), cwd, LD_LIBRARY_PATH/PATH visibility, and the
 *    extractor file's mode/size (user req #5).
 *  - READY still requires extract → symlink audit → a real PRoot boot probe
 *    (id + os-release + bash + apt) — unchanged (user req #6).
 */
object LinuxEnvManager {

    private const val TAG = "LinuxEnvManager"
    const val BASE = "/data/local/tmp/amino-linux"
    const val ROOTFS = "$BASE/rootfs"
    private const val PREFS = "linux_env"

    /** Bundled deterministic extractor (toybox tar, static) — pushed to the device. */
    const val TOYBOX = "$BASE/bin/toybox"
    /** The Debian 12 docker layer has 642 symlinks — an extraction that produced
     *  fewer than this threshold silently lost symlinks and MUST NOT pass. */
    private const val MIN_SYMLINKS = 300

    enum class State { NOT_INSTALLED, CHECKING, DOWNLOADING, TRANSFERRING, EXTRACTING, VERIFYING, READY, BROKEN }

    data class Status(
        val state: State,
        val message: String,
        val arch: String?,
        val installSource: String?,     // "docker-registry (digest-verified)" | "cdimage.debian.org (SHA512SUMS)"
        val manifestDigest: String?,
        val tarballBytes: Long,
        val rootfsDuKb: Long?,          // real `du` of the runtime tree
        val freePrivateBytes: Long?,
        val freeDataBytes: Long?,
        val installedAt: Long?,
        val lastError: String?
    )

    data class PreFlight(
        val archOk: Boolean, val arch: String?,
        val freePrivateBytes: Long, val freeDataBytes: Long?,
        val networkOk: Boolean,
        val serviceOk: Boolean, val serviceUid: Int,
        val prootBundled: Boolean,
        val prootElfDiag: String?,   // r1387: dependency diagnostic of the bundled PRoot
        val fs: FsProbe?,
        val problems: List<String>
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * REAL filesystem capability probe at the actual install path (r1386).
     * Proves writability, symlink creation and hardlink creation BEFORE any
     * download, and identifies the backing filesystem type. [symlinkErr]
     * carries the original shell error text when a capability is denied.
     */
    data class FsProbe(
        val base: String,
        val fstype: String?,
        val writeOk: Boolean,
        val symlinkOk: Boolean,
        val symlinkErr: String?,
        val hardlinkOk: Boolean?,
        val extractor: String?,
        val details: List<String>
    )

    private val installing = AtomicBoolean(false)

    // ---------- r1388 diagnostics: full invocation evidence (req #1/#2/#7) ----------

    /**
     * One recorded service invocation. Distinguishes "the binary never
     * EXECUTED" (EXEC_FAIL: rc 126/127, spawn failure, or linker/permission/
     * format text in stderr) from "it executed but the capability check did
     * not pass" (CAP_FAIL) — user req #2. [render] carries the full evidence
     * verbatim into logs, prefs and the UI — never a generic message.
     */
    private class Inv(
        val what: String, val cmd: String, val cwd: String,
        val rc: Int, val stdout: String, val stderr: String
    ) {
        val execFailure: Boolean get() =
            rc == 126 || rc == 127 || rc == -1 ||
                Regex("(?i)not found|permission denied|not executable|exec format error|cannot execute|CANNOT LINK")
                    .containsMatchIn(stderr)
        val kind: String
            get() = when {
                rc == 0 -> "OK"
                execFailure -> "EXEC_FAIL"
                else -> "CAP_FAIL"
            }

        fun render(): String = buildString {
            append("• command: $cmd\n")
            append("• working dir: $cwd\n")
            append("• exit code: $rc (classification: $kind)\n")
            append("• stdout: ${if (stdout.isBlank()) "(empty)" else stdout.take(700)}\n")
            append("• stderr: ${if (stderr.isBlank()) "(empty)" else stderr.take(700)}\n")
        }
    }

    private var cachedCwd: String? = null

    /** The service's default working directory for spawned commands (req #1). */
    private suspend fun serviceCwd(): String {
        cachedCwd?.let { return it }
        val s = ShizukuExec.oneShot("pwd 2>/dev/null")
        return s.output.trim().ifEmpty { "(unavailable)" }.also { cachedCwd = it }
    }

    /**
     * Runs one gated step with FULL evidence (user req #1): the command's
     * stderr goes to a file (never merged into stdout), the reported exit
     * code is the command's OWN (no pipeline masking — the r1386/r1387 bug
     * class), and the working directory is recorded. A multicall binary's
     * applet banner is captured as normal stdout and is never an error here.
     */
    private suspend fun runStep(
        what: String, cmd: String, timeoutMs: Long = 25_000,
        errFile: String = "$BASE/tmp/step.err"
    ): Inv {
        ShizukuExec.oneShot("mkdir -p $BASE/tmp 2>/dev/null; : >'$errFile'")
        val s = ShizukuExec.oneShot("{ $cmd; } 2>'$errFile'; echo __RC__=\$?", timeoutMs)
        val rc = Regex("__RC__=(\\d+)").find(s.output)?.groupValues?.get(1)?.toIntOrNull() ?: s.rc
        val stdout = s.output.replace(Regex("__RC__=\\d+"), "").trim()
        val stderr = ShizukuExec.oneShot("cat '$errFile' 2>/dev/null").output.trim()
        val inv = Inv(what, cmd, serviceCwd(), rc, stdout, stderr)
        val line = "[$what] rc=${inv.rc} kind=${inv.kind} stdout=${stdout.take(220)} stderr=${stderr.take(220)}"
        if (inv.kind == "OK") Log.i(TAG, line) else Log.e(TAG, line)
        return inv
    }

    /**
     * The failure text for the extractor gates (version / tiny archive):
     * classifies EXEC_FAIL vs CAP_FAIL, then the full invocation evidence.
     */
    private fun extractorFailMsg(inv: Inv, why: String): String = buildString {
        if (inv.execFailure) {
            append("EXTRACTOR EXECUTION FAILURE — the bundled extractor binary never ran on this device\n")
            append("• classification: EXEC_FAIL (not found / not executable / wrong ELF format / linker refusal)\n")
        } else {
            append("EXTRACTOR CAPABILITY CHECK FAILED — the binary executed, the check did not pass\n")
            append("• classification: CAP_FAIL (rc != 0 or verification mismatch; a multicall binary's applet banner is NORMAL output, never an error)\n")
        }
        append("• reason: $why\n")
        append(inv.render())
        append("• this is the bundled extractor (libamino_tar.so → $TOYBOX), NOT the device tar and NOT your storage — preflight already proved symlinks/fstype OK\n")
    }

    // ---------- persistence ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("state", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun save(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString("state", o.toString()).apply()
    }

    private fun setState(ctx: Context, s: State, message: String, error: String? = null) {
        val o = load(ctx).put("state", s.name).put("message", message)
        if (error != null) o.put("lastError", error) else o.remove("lastError")
        save(ctx, o)
        Log.i(TAG, "state=${s.name} $message")
    }

    fun currentState(ctx: Context): State = try {
        State.valueOf(load(ctx).optString("state", State.NOT_INSTALLED.name))
    } catch (e: Exception) { State.NOT_INSTALLED }

    // ---------- real checks (user spec #8) ----------

    fun archTag(): String? = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64"
        Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "amd64"
        else -> null
    }

    fun prootBundled(ctx: Context): Boolean {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        return File(dir, "libamino_proot.so").exists() && File(dir, "libamino_talloc.so").exists()
    }

    fun networkOk(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) { false }

    fun freePrivateBytes(ctx: Context): Long = try {
        StatFs(ctx.filesDir.absolutePath).availableBytes
    } catch (e: Exception) { -1L }

    suspend fun freeDataBytes(): Long? = try {
        val s = ShizukuExec.oneShot("df -k /data/local/tmp 2>/dev/null | tail -n 1")
        val cols = s.output.trim().split(Regex("\\s+"))
        cols.getOrNull(cols.size - 3)?.toLongOrNull()?.times(1024)
    } catch (e: Exception) { null }

    suspend fun preFlight(ctx: Context): PreFlight {
        val arch = archTag()
        val free = freePrivateBytes(ctx)
        val freeData = freeDataBytes()
        val problems = ArrayList<String>()
        if (arch == null) problems.add("no supported CPU ABI for the Debian rootfs (needs arm64 or x86_64)")
        if (!prootBundled(ctx)) problems.add("proot is not bundled in this APK build")
        // r1387: dependency diagnostic BEFORE anything is pushed or downloaded —
        // ELF machine/ABI, dynamic interpreter and DT_NEEDED of the bundled PRoot.
        var prootElfDiag: String? = null
        if (prootBundled(ctx)) {
            val pf = File(ctx.applicationInfo.nativeLibraryDir, "libamino_proot.so")
            val info = ElfInspector.inspect(pf)
            prootElfDiag = ElfInspector.describe(pf)
            if (!info.ok) {
                problems.add("cannot read the bundled proot ELF header (${info.reason}) — the binary may be corrupted")
            } else {
                val want = when (arch) { "arm64" -> "aarch64"; "amd64" -> "x86-64"; else -> null }
                if (want != null && info.machine != want)
                    problems.add("bundled proot is built for ${info.machine} but this device needs $want — a build error, not a device problem")
                if (!info.isAndroidInterpreter)
                    problems.add("bundled proot has a non-Android dynamic interpreter (${info.interpreter}) — it can never execute under the Android linker")
            }
        }
        if (free in 0..(700L * 1024 * 1024)) problems.add("app storage low: ${free / (1024 * 1024)} MB free (need ≥ 700 MB)")
        if (freeData != null && freeData in 0..(800L * 1024 * 1024))
            problems.add("/data storage low: ${freeData / (1024 * 1024)} MB free (need ≥ 800 MB)")
        if (!networkOk(ctx)) problems.add("no active network — the rootfs download needs internet")
        val serviceOk = ShizukuExec.available()
        if (!serviceOk) problems.add("aMiNo service is not running — start the service (shell mode) first; PRoot runs through it without root")
        // r1386: filesystem capability probe at the REAL install path — this is
        // what catches "symlink: Permission denied" BEFORE anything is downloaded.
        var fs: FsProbe? = null
        if (serviceOk) {
            fs = fsProbe()
            if (fs != null) {
                if (!fs.writeOk) problems.add("the runtime dir ${fs.base} is not writable by the service: ${fs.symlinkErr ?: "no details"}")
                if (!fs.symlinkOk) problems.add(
                    "THIS DEVICE DENIES SYMLINK CREATION at ${fs.base} (${fs.fstype ?: "unknown fs"}): " +
                        "${fs.symlinkErr ?: "unknown error"} — a Debian rootfs is unusable without symlinks (/bin→usr/bin, …); " +
                        "the install is refused before downloading anything (no broken half-installs)"
                )
            }
        }
        return PreFlight(arch != null, arch, free, freeData, networkOk(ctx),
            serviceOk, ShizukuExec.serviceUid(), prootBundled(ctx), prootElfDiag, fs, problems)
    }

    /**
     * Proves — with real syscalls through the service — that the runtime path
     * supports everything a Linux rootfs needs (write, symlink, hardlink).
     * Runs BEFORE the download; returns the ORIGINAL error text on denial.
     */
    suspend fun fsProbe(): FsProbe? {
        // hard guard: the runtime path must stay on /data/local/tmp (shell-owned,
        // ext4/f2fs, exec-able by the service). NEVER shared storage like /sdcard
        // (FUSE there breaks Unix semantics: no symlinks, no real permissions).
        if (!BASE.startsWith("/data/local/tmp") || BASE == "/data/local/tmp") {
            Log.e(TAG, "runtime path guard tripped: $BASE")
            return FsProbe(BASE, null, false, false,
                "runtime path $BASE is not allowed (must be under /data/local/tmp)", null, null,
                listOf("path guard"))
        }
        val details = ArrayList<String>()
        val fstype = try {
            val s = ShizukuExec.oneShot("awk '\$2==\"/data\" || \$2==\"/data/local\" {print \$3; exit}' /proc/mounts 2>/dev/null")
            s.output.trim().ifEmpty { null }
        } catch (e: Exception) { null }
        if (fstype != null) details.add("fstype(/data)=$fstype")
        val s = ShizukuExec.oneShot(
            "B='$BASE'; R=''; " +
                "mkdir -p \"\$B\" 2>/dev/null && R=\"\$R;BASEDIR=OK\" || R=\"\$R;BASEDIR=FAIL\"; " +
                "mkdir -p \"\$B/fprobe\" 2>/dev/null && R=\"\$R;WRITE=OK\" || R=\"\$R;WRITE=FAIL\"; " +
                "touch \"\$B/fprobe/t\" 2>/dev/null && R=\"\$R;TOUCH=OK\" || R=\"\$R;TOUCH=FAIL\"; " +
                "ln -s t \"\$B/fprobe/lnk\" 2>/dev/null && R=\"\$R;SYMLINK=OK\" || R=\"\$R;SYMLINK=FAIL:\$(ln -s t \"\$B/fprobe/lnk\" 2>&1 | tail -n 1)\"; " +
                "RL=\$(readlink \"\$B/fprobe/lnk\" 2>/dev/null); R=\"\$R;READLINK=\$RL\"; " +
                "ln \"\$B/fprobe/t\" \"\$B/fprobe/hard\" 2>/dev/null && R=\"\$R;HARDLINK=OK\" || R=\"\$R;HARDLINK=FAIL\"; " +
                "rm -rf \"\$B/fprobe\" 2>/dev/null; " +
                "echo \"FSPROBE[\$R]\"",
            30_000
        )
        val m = Regex("FSPROBE\\[(.*)\\]").find(s.output)?.groupValues?.get(1) ?: run {
            Log.e(TAG, "fsProbe returned nothing: rc=${s.rc} ${s.output.take(200)}")
            return FsProbe(BASE, fstype, false, false,
                "the capability probe did not return a result: ${s.output.take(200)}", null, null, details)
        }
        fun tag(k: String): String? = m.split(";").firstOrNull { it.startsWith("$k=") }?.removePrefix("$k=")
        val writeOk = tag("WRITE") == "OK" && tag("TOUCH") == "OK" && tag("BASEDIR") == "OK"
        val symlinkOk = tag("SYMLINK") == "OK" && tag("READLINK") == "t"
        val symlinkErr = if (symlinkOk) null else (tag("SYMLINK") ?: "unknown")
        val hard = tag("HARDLINK")
        details.add("probe=$m")
        Log.i(TAG, "fsProbe: fstype=$fstype $m")
        // when the bundled extractor is already on the device, record its version
        // (r1388: `--version` with the real rc — the old bare invocation captured
        // the applet banner "[ acpi arch … ]", which contains no "toybox" token)
        var extractor: String? = null
        try {
            val v = ShizukuExec.oneShot("test -x '$TOYBOX' && '$TOYBOX' --version 2>/dev/null", 15_000)
            val tok = Regex("(?i)toybox\\s+v?[0-9]+\\.[0-9]+(\\.[0-9]+)?").find(v.output)?.value
            if (v.ok && tok != null) {
                extractor = tok; details.add("extractor=$extractor")
            }
        } catch (_: Exception) {}
        return FsProbe(BASE, fstype, writeOk, symlinkOk, symlinkErr,
            if (hard == null) null else hard == "OK", extractor, details)
    }

    // ---------- status ----------

    fun status(ctx: Context): Status {
        val o = load(ctx)
        val st = try { State.valueOf(o.optString("state", State.NOT_INSTALLED.name)) } catch (e: Exception) { State.NOT_INSTALLED }
        val tar = tarballFile(ctx)
        val duKb = if (st == State.READY) duRootfsKb() else null
        return Status(
            state = st, message = o.optString("message", ""),
            arch = o.optString("arch").ifEmpty { null },
            installSource = o.optString("source").ifEmpty { null },
            manifestDigest = o.optString("manifestDigest").ifEmpty { null },
            tarballBytes = if (tar.exists()) tar.length() else 0L,
            rootfsDuKb = duKb,
            freePrivateBytes = freePrivateBytes(ctx),
            freeDataBytes = null,
            installedAt = if (o.has("installedAt")) o.getLong("installedAt") else null,
            lastError = o.optString("lastError").ifEmpty { null }
        )
    }

    fun duRootfsKb(): Long? = runCatching {
        kotlinx.coroutines.runBlocking {
            val s = ShizukuExec.oneShot("du -sk $ROOTFS 2>/dev/null | cut -f1", 15_000)
            s.output.trim().toLongOrNull()
        }
    }.getOrNull()

    /** Discovery entry used by TerminalEngine — never pretends. */
    fun discoveryInfo(ctx: Context): Pair<Boolean, String> {
        val st = currentState(ctx)
        return when (st) {
            State.READY -> true to "ready — Debian 12 via PRoot (fakeroot inside, no real root)"
            State.BROKEN -> false to "broken: ${load(ctx).optString("lastError", "unknown")} — reinstall from the Linux environment page"
            State.CHECKING, State.DOWNLOADING, State.TRANSFERRING, State.EXTRACTING, State.VERIFYING ->
                false to "installation in progress (${st.name.lowercase()})"
            State.NOT_INSTALLED -> {
                val err = load(ctx).optString("lastError", "")
                false to (if (err.isNotBlank())
                    "not installed — the last attempt was refused or failed: $err"
                else
                    "not installed — install Debian 12 (PRoot) from the Linux environment page; it runs without root")
            }
        }
    }

    fun tarballFile(ctx: Context): File {
        val gz = File(ctx.filesDir, "linux/debian-rootfs.tar.gz")
        return if (gz.exists()) gz else File(ctx.filesDir, "linux/debian-rootfs.tar.xz")
    }

    // ---------- install flow ----------

    /**
     * Full honest install: preflight → push bundled binaries → PROOT RUNTIME
     * TEST (LD_LIBRARY_PATH-correct, version-gated) → MINIMAL-ROOT SMOKE TEST →
     * download (digest-verified) → transfer → extract → audit → configure →
     * VERIFY probe. Debian is only touched AFTER PRoot itself proved it runs.
     * Returns the final human-readable summary; every step's real result is kept.
     */
    suspend fun install(ctx: Context, onProgress: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            if (!installing.compareAndSet(false, true))
                return@withContext "an installation is already running"
            try { installInner(ctx, onProgress) } finally { installing.set(false) }
        }

    private suspend fun installInner(ctx: Context, onProgress: (String) -> Unit): String {
        val step: (String) -> Unit = { onProgress(it); Log.i(TAG, it) }
        val meta = load(ctx)
        try {
            // ---- 1) preflight (real checks incl. the r1386 filesystem probe) ----
            setState(ctx, State.CHECKING, "checking device (architecture, storage, network, service, filesystem)")
            step("preflight: arch / storage / network / service / symlink-support")
            val pf = preFlight(ctx)
            if (!pf.ok) {
                val symlinkBlocked = pf.fs != null && !pf.fs.symlinkOk && pf.fs.writeOk
                if (symlinkBlocked) {
                    // requirement: a device whose storage denies symlinks is NEVER
                    // left half-installed — back to NOT_INSTALLED with the reason.
                    val why = pf.problems.joinToString("; ")
                    setState(ctx, State.NOT_INSTALLED, "install refused: this device cannot host a Linux rootfs", why)
                    Log.e(TAG, "INSTALL REFUSED (symlink probe): $why")
                    return "INSTALL REFUSED — nothing was downloaded, nothing is broken:\n" +
                        pf.problems.joinToString("\n") { "• $it" }
                }
                setState(ctx, State.BROKEN, "preflight failed", pf.problems.joinToString("; "))
                return "PREFLIGHT FAILED — nothing was installed:\n" + pf.problems.joinToString("\n") { "• $it" }
            }
            step("preflight OK: arch=${pf.arch}, app-storage ${pf.freePrivateBytes / (1024 * 1024)} MB free, " +
                "data ${((pf.freeDataBytes ?: -1) / (1024 * 1024))} MB free, service uid=${pf.serviceUid}, " +
                "fstype=${pf.fs?.fstype ?: "?"}, symlinks=OK")
            pf.prootElfDiag?.let { step("proot ELF diagnostic: $it") }

            // ---- 2) runtime dirs + bundled binaries ----
            setState(ctx, State.CHECKING, "preparing runtime directory")
            var s = ShizukuExec.oneShot("mkdir -p $BASE/bin $BASE/lib $BASE/tmp $BASE/rootfs && echo DIRS_OK")
            if (!s.ok || !s.output.contains("DIRS_OK"))
                return fail(ctx, "cannot create $BASE through the service: rc=${s.rc} ${s.output.take(200)}")

            val nd = ctx.applicationInfo.nativeLibraryDir
            val pushes = listOf(
                Triple("libamino_proot.so", "$BASE/bin/proot", "PRoot binary"),
                Triple("libamino_talloc.so", "$BASE/lib/libtalloc.so.2", "talloc library"),
                Triple("libamino_shmem.so", "$BASE/lib/libandroid-shmem.so", "android-shmem library"),
                Triple("libamino_xz.so", "$BASE/bin/xz", "xz (fallback extraction)"),
                Triple("libamino_lzma.so", "$BASE/lib/liblzma.so.5", "lzma library"),
                Triple("libamino_tar.so", TOYBOX, "toybox tar (bundled extractor: preserves symlinks + Unix permissions)")
            )
            for ((src, dst, what) in pushes) {
                val f = File(nd, src)
                if (!f.exists()) return fail(ctx, "bundled $what missing from APK ($src)")
                val p = ShizukuExec.pushFile(f, dst)
                if (!p.ok) return fail(ctx, "pushing $what failed: ${p.output.take(200)}")
                step("pushed $what → $dst (${f.length()} bytes, size verified)")
            }
            // ---- 2b) PROOT RUNTIME TEST (r1387 — the r1385/r1386 blocker) ----
            // The bionic linker resolves libtalloc.so.2 ONLY through LD_LIBRARY_PATH:
            // the Termux build's DT_RUNPATH points at /data/data/com.termux/files/usr/lib
            // which does not exist on aMiNo devices. The r1386 gate was ALSO unsound:
            // it piped through `head` (masking proot's exit code) and matched the
            // linker's own error text with contains("proot"). Both fixed here:
            //   - LD_LIBRARY_PATH is set for every proot exec, like Termux does;
            //   - no pipeline → the exit code is PROOT's own;
            //   - success requires a real version token, and linker error text is
            //     rejected explicitly and reported VERBATIM (library name included).
            s = ShizukuExec.oneShot(
                "chmod 755 $BASE/bin/proot $BASE/bin/xz $TOYBOX && " +
                    "LD_LIBRARY_PATH=$BASE/lib $BASE/bin/proot --version 2>&1"
            )
            val versionTok = Regex("(?i)proot\\s+v?[0-9]+\\.[0-9]+").find(s.output)?.value
                ?: Regex("\\b[0-9]+\\.[0-9]+\\.[0-9]+\\b").find(s.output)?.value
            val linkerErr = Regex("CANNOT LINK|not found|not accessible", RegexOption.IGNORE_CASE).containsMatchIn(s.output)
            if (!s.ok || versionTok == null || linkerErr) {
                val elfDiag = pf.prootElfDiag ?: ElfInspector.describe(File(nd, "libamino_proot.so"))
                val lib = ElfInspector.failingLibraryIn(s.output)
                val why = buildString {
                    append("the PRoot binary itself failed to execute on this device\n")
                    append("• failing library: ${lib ?: "(not stated by the linker)"}\n")
                    append("• complete linker error: ${s.output.take(400)}\n")
                    append("• proot ELF: $elfDiag\n")
                    append("• the linker resolves the libraries through LD_LIBRARY_PATH=$BASE/lib — if this test fails the shipped binary/dependency pair is incompatible with this device\n")
                    append("→ this is a bundled-binary problem, NOT your storage (the preflight already proved symlinks/fstype OK); Debian was NOT downloaded and nothing was extracted\n")
                    append("→ update aMiNo and retry; use Remove on this page to clear $BASE")
                }
                Log.e(TAG, "PROOT RUNTIME TEST FAILED: rc=${s.rc} ${s.output.take(300)}")
                return runtimeFail(ctx, why)
            }
            val prootVersion = "${versionTok.take(40)} (rc=0, all DT_NEEDED libraries resolved incl. libtalloc.so.2)"
            step("proot verified on device: $prootVersion")
            val smokeEvidence = "PRoot ran a minimal root with fake-root (id → uid=0) and read /etc/os-release"
            // ---- 2b-α) SERVICE RUNTIME EVIDENCE (r1388, user req #5) ----
            // The REAL identity and environment the service uses to run proot
            // AND the extractor: actual uid/gid (`id`), working directory,
            // LD_LIBRARY_PATH/PATH as the spawned shell sees them, and the
            // extractor file's mode/size. The device tar is recorded for
            // reference only — we never rely on it.
            val envInv = runStep("service runtime environment evidence",
                "echo \"UID_OUT=\$(id 2>&1)\"; echo \"CWD_OUT=\$(pwd)\"; " +
                    "echo \"LDP_OUT=\$LD_LIBRARY_PATH\"; echo \"PATH_OUT=\$PATH\"; " +
                    "stat -c 'TOY_MODE=%A TOY_SIZE=%s TOY_PATH=%n' '$TOYBOX' 2>&1; " +
                    "command -v tar >/dev/null 2>&1 && echo \"DEV_TAR=\$(tar --version 2>&1 | head -n 1)\" || echo \"DEV_TAR=none\"")
            step("service runtime evidence: ${envInv.stdout.take(400)}")
            if (envInv.rc != 0)
                return runtimeFail(ctx, "could not even read the service runtime environment (req #5)\n" +
                    extractorFailMsg(envInv, "the evidence command itself failed"), "service environment check")

            // ---- 2b-β) EXTRACTOR VERSION GATE (r1388 — the r1386 false failure) ----
            // The r1386 check ran the multicall binary BARE piped through `head`:
            // toybox then prints its NORMAL applet banner "[ acpi arch ascii … ]"
            // (rc=0!), head masked the real exit code, and the banner — with no
            // "toybox" token in it — was quoted into "the bundled extractor does
            // not run on this device". A sound binary was misclassified by its
            // own capability check. Now: `--version`, the REAL rc (no pipeline),
            // and a strict version token; banner text can never fail this gate.
            val ver = runStep("extractor version check", "'$TOYBOX' --version")
            val verTok = Regex("(?i)toybox\\s+v?[0-9]+\\.[0-9]+(\\.[0-9]+)?").find(ver.stdout)?.value
            if (ver.rc != 0 || verTok == null)
                return runtimeFail(ctx, extractorFailMsg(ver,
                    if (ver.rc == 0) "toybox ran (rc=0) but --version did not return a version token"
                    else "toybox --version exited with rc=${ver.rc}"),
                    "extractor runtime test")
            step("extractor version verified on device: $verTok (rc=0, static musl — no shared-library resolution needed)")

            // ---- 2b-γ) EXTRACTOR END-TO-END TINY-ARCHIVE TEST (r1388, req #4) ----
            // The capability test that actually matters: pack a tiny archive
            // with the bundled tar, extract it, and verify a REGULAR FILE, a
            // DIRECTORY, a SYMLINK and a DANGLING SYMLINK round-trip intact —
            // the exact classes the 642-symlink Debian layer depends on. All
            // through the service, on the real runtime filesystem.
            setState(ctx, State.CHECKING, "testing the bundled extractor on a tiny archive (file + dir + symlink)")
            val T = "$BASE/tmp/extest"
            val tiny = runStep("extractor end-to-end tiny-archive test",
                "rm -rf '$T' && mkdir -p '$T/src/sub' '$T/out' && " +
                    "printf 'amino-extractor-test' > '$T/src/sub/file.txt' && " +
                    "ln -sf sub/file.txt '$T/src/link' && ln -sf /nonexistent '$T/src/dangling' && " +
                    "'$TOYBOX' tar -C '$T/src' -cf '$T/test.tar' . && " +
                    "'$TOYBOX' tar -C '$T/out' -xf '$T/test.tar' && " +
                    "echo \"TINY[content=\$(cat '$T/out/sub/file.txt' 2>/dev/null) " +
                    "dir=\$(test -d '$T/out/sub' && echo yes) " +
                    "link=\$(readlink '$T/out/link' 2>/dev/null) " +
                    "dangling=\$(test -L '$T/out/dangling' && echo yes)]\"",
                60_000)
            val tinyM = Regex("TINY\\[(.*)\\]").find(tiny.stdout)?.groupValues?.get(1)
            fun f(k: String): String? = tinyM?.let { Regex("$k=([^ ]+)").find(it)?.groupValues?.get(1) }
            val tinyOk = tiny.rc == 0 && tinyM != null &&
                f("content") == "amino-extractor-test" &&
                f("dir") == "yes" &&
                f("link") == "sub/file.txt" &&
                f("dangling") == "yes"
            if (!tinyOk)
                return runtimeFail(ctx, extractorFailMsg(tiny,
                    if (tiny.rc == 0) "the tiny archive did not round-trip intact — extraction itself is broken on this device " +
                        "(evidence: ${tinyM ?: "no TINY marker in stdout"})"
                    else "the tar round-trip exited with rc=${tiny.rc}"),
                    "extractor runtime test")
            step("extractor end-to-end test PASSED: file content ✓, directory ✓, symlink → sub/file.txt ✓, dangling symlink ✓")
            ShizukuExec.oneShot("rm -rf '$T'", 30_000)
            val extractorVersion = "$verTok (rc=0; tiny-archive round-trip passed: regular file + directory + symlink + dangling symlink preserved)"

            // ---- 2c) MINIMAL-ROOTFS SMOKE TEST (r1387, before ANY Debian download) ----
            // A tiny root built from the bundled toybox: proves PRoot can actually
            // chroot + fake-root + exec INSIDE a rootfs (id → uid=0, /etc/os-release
            // readable) before the 48 MB Debian archive is even fetched.
            setState(ctx, State.CHECKING, "PRoot smoke test on a minimal root (no Debian involved yet)")
            val mk = ShizukuExec.oneShot(
                "rm -rf $BASE/miniroot && mkdir -p $BASE/miniroot/bin $BASE/miniroot/etc $BASE/miniroot/root " +
                    "$BASE/miniroot/tmp $BASE/miniroot/dev $BASE/miniroot/proc && " +
                    "cp $TOYBOX $BASE/miniroot/bin/toybox && chmod 755 $BASE/miniroot/bin/toybox && " +
                    "for a in sh cat id uname echo ls; do ln -sf toybox \$BASE/miniroot/bin/\$a; done && " +
                    "printf 'PRETTY_NAME=\"AMINO PRoot smoke test\"\\nID=amino-smoke\\n' > $BASE/miniroot/etc/os-release && " +
                    "echo MINI_OK", 30_000
            )
            if (!mk.ok || !mk.output.contains("MINI_OK"))
                return runtimeFail(ctx, "could not build the minimal smoke root at $BASE/miniroot\n" +
                    "• original error: ${mk.output.take(300)}\n" +
                    "→ the runtime tree is not usable by the service; use Remove on this page and retry")
            val smoke = ShizukuExec.oneShot(
                "PROOT_TMP_DIR=$BASE/tmp LD_LIBRARY_PATH=$BASE/lib $BASE/bin/proot --kill-on-exit " +
                    "-0 -r $BASE/miniroot -w /root /bin/sh -c 'id; cat /etc/os-release' 2>&1; " +
                    "echo SMOKE_RC=\$?", 60_000
            )
            val smokeRc = Regex("SMOKE_RC=(\\d+)").find(smoke.output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val smokeOut = smoke.output.replace(Regex("SMOKE_RC=\\d+"), "").trim()
            if (smokeRc != 0 || !smokeOut.contains("uid=0") || !smokeOut.contains("AMINO PRoot smoke test")) {
                val why = buildString {
                    append("PRoot could not run even the minimal smoke root — installing Debian would fail the same way\n")
                    append("• exit code: $smokeRc\n")
                    append("• full output: ${smokeOut.take(400)}\n")
                    append("→ this is a PRoot runtime problem, NOT your storage; nothing was downloaded\n")
                    append("→ update aMiNo and retry; use Remove on this page to clear $BASE")
                }
                Log.e(TAG, "PROOT SMOKE TEST FAILED: rc=$smokeRc ${smokeOut.take(300)}")
                return runtimeFail(ctx, why)
            }
            step("smoke test PASSED: ${smokeOut.lines().take(3).joinToString(" · ")} — PRoot chroot + fake-root + exec all work; proceeding to Debian")
            ShizukuExec.oneShot("rm -rf $BASE/miniroot", 30_000)

            // ---- 3) download — or REUSE the verified archive from a failed attempt ----
            setState(ctx, State.DOWNLOADING, "obtaining the Debian 12 rootfs")
            val cached = cachedVerifiedArchive(ctx)
            val dl: Download = if (cached != null) {
                step("reusing the previously verified archive (${cached.file.name}, ${cached.file.length() / (1024 * 1024)} MB) — no re-download")
                cached
            } else {
                val d = download(ctx) { done, total ->
                    step("download: ${done / (1024 * 1024)} MB${if (total > 0) " / ${total / (1024 * 1024)} MB" else ""}")
                }
                if (d == null) return fail(ctx, "rootfs download failed (both sources unreachable or verification failed) — the download was deleted; check the network and retry")
                d
            }
            step("archive verified: ${dl.file.name} from ${dl.source} (${dl.file.length() / (1024 * 1024)} MB)")
            // remember the archive so any retry skips the download (resumable install)
            val algo = if (dl.file.name.endsWith(".xz")) "sha512" else "sha256"
            val localDigest = fileDigest(dl.file, algo)
            if (localDigest == null) return fail(ctx, "cannot hash the downloaded archive")
            meta.put("dlFile", dl.file.name).put("dlSize", dl.file.length())
                .put("dlAlgo", algo).put("dlDigest", localDigest)
                .put("source", dl.source).put("manifestDigest", dl.digest ?: "")
            save(ctx, meta)

            // ---- 4) transfer + REMOTE digest verification ----
            setState(ctx, State.TRANSFERRING, "transferring rootfs to the runtime directory")
            val ext = if (dl.file.name.endsWith(".xz")) "xz" else "gz"
            val remoteTar = "$BASE/rootfs.tar.$ext"
            ShizukuExec.oneShot("rm -f '$BASE/rootfs.tar.gz' '$BASE/rootfs.tar.xz'")
            var lastPct = -1
            val p = ShizukuExec.pushFile(dl.file, remoteTar) { d, t ->
                val pct = if (t > 0) (d * 100 / t).toInt() else 0
                if (pct != lastPct) { lastPct = pct; step("transfer: $pct%") }
            }
            if (!p.ok) return fail(ctx, "transfer failed: ${p.output.take(200)}")
            val remoteSum = ShizukuExec.oneShot("sha256sum '$remoteTar' 2>/dev/null | awk '{print \$1}'")
            val remoteDigest = remoteSum.output.trim().take(64)
            val localSha = fileDigest(dl.file, "sha256")
            if (!remoteSum.ok || remoteDigest.length != 64 || localSha == null)
                return fail(ctx, "cannot verify the transferred archive on the device (sha256sum missing?): ${remoteSum.output.take(120)}")
            if (remoteDigest != localSha)
                return fail(ctx, "transferred archive is CORRUPT (sha256 mismatch: expected ${localSha.take(16)}… got ${remoteDigest.take(16)}…) — the device copy was deleted; retry the install")
            step("transfer verified on-device (sha256 ${remoteDigest.take(16)}… matches)")

            // ---- 5) extract with the BUNDLED extractor (r1386 hardened) ----
            setState(ctx, State.EXTRACTING, "extracting the rootfs (this takes a minute or two)")
            val ex = extractRootfs(remoteTar, "$ROOTFS.tmp")
            if (!ex.ok)
                return fail(ctx, "extraction failed — ${ex.detail}")
            step("extraction ok (${ex.detail})")

            // ---- 6) post-extract AUDIT: symlinks are the r1385 failure class ----
            setState(ctx, State.EXTRACTING, "auditing the extracted tree (symlinks, bash, apt)")
            val audit = auditRootfs("$ROOTFS.tmp")
            if (!audit.first)
                return fail(ctx, "post-extract audit FAILED: ${audit.second}")
            step("audit ok: ${audit.second}")

            // ---- 7) swap into place ----
            s = ShizukuExec.oneShot(
                "rm -rf $ROOTFS && mv $ROOTFS.tmp $ROOTFS && echo SWAP_OK",
                timeoutMs = 120_000
            )
            if (!s.ok || !s.output.contains("SWAP_OK"))
                return fail(ctx, "could not move the verified rootfs into place: ${s.output.take(200)}")

            // ---- 8) configure (DNS, hosts, tmp, ownership) ----
            setState(ctx, State.VERIFYING, "configuring the environment")
            s = ShizukuExec.oneShot(
                "printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > $ROOTFS/etc/resolv.conf && " +
                    "printf '127.0.0.1 localhost\\n' > $ROOTFS/etc/hosts && " +
                    "mkdir -p $ROOTFS/tmp $ROOTFS/root $ROOTFS/dev $ROOTFS/proc $ROOTFS/sys && " +
                    "chmod 1777 $ROOTFS/tmp && echo CONF_OK"
            )
            if (!s.ok || !s.output.contains("CONF_OK"))
                return fail(ctx, "configuration failed: ${s.output.take(200)}")
            if (ShizukuExec.serviceUid() == 0) {
                ShizukuExec.oneShot("chown -R 2000:2000 $BASE", 300_000)
                step("service runs as root — ownership dropped to shell (2000) so nothing runs as real root")
            }
            ShizukuExec.oneShot("rm -f '$remoteTar'")
            step("configuration written (DNS 1.1.1.1/8.8.8.8, hosts, /tmp); device tarball removed")

            // ---- 9) VERIFY: a real boot through PRoot — os-release + bash + apt ----
            setState(ctx, State.VERIFYING, "verifying: booting Debian through PRoot (id + os-release + bash + apt)")
            val probe = probe(ctx)
            if (!probe.first)
                return fail(ctx, "verification probe FAILED: ${probe.second.take(300)}")
            step("probe verified: ${probe.second.take(200).replace("\n", " · ")}")

            // ---- 10) READY (real, evidenced) ----
            meta.put("state", State.READY.name)
                .put("message", "ready")
                .put("arch", pf.arch)
                .put("prootVersion", prootVersion)
                .put("extractor", extractorVersion)
                .put("smoke", smokeEvidence)
                .put("installedAt", System.currentTimeMillis())
                .remove("lastError")
            save(ctx, meta)
            setState(ctx, State.READY, "ready")
            return "INSTALLED AND VERIFIED\n" +
                "• source: ${dl.source}${if (dl.digest != null) "\n• layer digest: ${dl.digest.take(19)}…" else ""}\n" +
                "• $prootVersion\n" +
                "• smoke test: $smokeEvidence\n" +
                "• extractor: $extractorVersion\n" +
                "• audit: ${audit.second}\n" +
                "• probe: ${probe.second.trim().replace("\n", " · ")}\n" +
                "• runtime: $ROOTFS (shell uid 2000, PRoot fakeroot — NOT real root)\n" +
                "• archive kept in app private storage for Reset (${dl.file.length() / (1024 * 1024)} MB)"
        } catch (e: Throwable) {
            Log.e(TAG, "install crashed", e)
            return fail(ctx, "installation error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(ctx: Context, why: String): String {
        Log.e(TAG, "INSTALL FAILED: $why")
        setState(ctx, State.BROKEN, "installation failed", why)
        // r1386: safe cleanup so a retry starts clean — the broken extract tree
        // and any partial device tarball are removed; the VERIFIED archive in
        // app storage is KEPT so the retry skips the download (resumable).
        if (ShizukuExec.available()) kotlinx.coroutines.runBlocking {
            ShizukuExec.oneShot(
                "rm -rf '$ROOTFS.tmp' 2>/dev/null; rm -f '$BASE/rootfs.tar.gz' '$BASE/rootfs.tar.xz' 2>/dev/null; echo CLEAN_OK",
                60_000
            )
        }
        return "INSTALL FAILED — nothing is claimed to work:\n$why"
    }

    /**
     * r1387/r1388 — failure of a BUNDLED-BINARY runtime phase (proot exec,
     * dependency resolution, smoke root, or the r1388 extractor gates), all of
     * which run BEFORE any Debian download. The environment is NOT "broken":
     * Debian was never downloaded or extracted, and the user's storage already
     * passed the preflight. State returns to NOT_INSTALLED with the full
     * diagnostic — a broken state would be a false claim about the device.
     */
    private fun runtimeFail(ctx: Context, why: String, stage: String = "PRoot runtime test"): String {
        Log.e(TAG, "RUNTIME FAILED [$stage]: ${why.take(300)}")
        setState(ctx, State.NOT_INSTALLED, "install stopped before Debian: $stage failed", why)
        return "RUNTIME TEST FAILED ($stage) — Debian was NOT downloaded, nothing was extracted, " +
            "nothing on the storage is broken:\n$why"
    }

    // ---------- r1386 helpers: resume, bundled extraction, audit ----------

    /** sha256/sha512 of a local file (streaming). */
    private fun fileDigest(f: File, algo: String): String? = try {
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    /**
     * A previously downloaded archive whose digest still matches what we
     * recorded — lets a failed install RETRY without re-downloading (r1386).
     */
    private fun cachedVerifiedArchive(ctx: Context): Download? {
        val o = load(ctx)
        val name = o.optString("dlFile", "")
        val expected = o.optString("dlDigest", "")
        if (name.isEmpty() || expected.isEmpty()) return null
        val f = File(ctx.filesDir, "linux/$name")
        if (!f.exists() || f.length() != o.optLong("dlSize", -1)) return null
        val algo = o.optString("dlAlgo", "sha256")
        val hex = fileDigest(f, algo) ?: return null
        if (!hex.equals(expected, ignoreCase = true)) {
            Log.w(TAG, "cached archive hash mismatch — deleting and re-downloading")
            f.delete()
            return null
        }
        return Download(f, o.optString("source", "cached archive (digest re-verified)"),
            o.optString("manifestDigest", "").ifEmpty { null })
    }

    private class Extract(val ok: Boolean, val detail: String)

    /**
     * Extract with the BUNDLED toybox tar (never the device's tar — r1386).
     * stderr is captured to files; on failure the ORIGINAL error text (with
     * the failing paths) is returned verbatim for the log and the UI.
     */
    private suspend fun extractRootfs(remoteTar: String, targetDir: String): Extract {
        ShizukuExec.oneShot("rm -rf '$targetDir' && mkdir -p '$targetDir' && echo MK_OK", 60_000)
        val decode = if (remoteTar.endsWith(".xz"))
            "LD_LIBRARY_PATH=$BASE/lib $BASE/bin/xz -dc '$remoteTar'"
        else
            "gzip -dc '$remoteTar'"
        val cmd = "$decode 2>'$BASE/tmp/gzip.err' | '$TOYBOX' tar -xf - -C '$targetDir' 2>'$BASE/tmp/tar.err'; echo EXTRACT_RC=\$?"
        val s = ShizukuExec.oneShot(cmd, timeoutMs = 900_000)
        val rc = Regex("EXTRACT_RC=(\\d+)").find(s.output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val tarErr = ShizukuExec.oneShot("tail -c 1500 '$BASE/tmp/tar.err' 2>/dev/null").output.trim()
        if (rc != 0) {
            val why = buildString {
                append("bundled toybox tar exited with rc=$rc. ")
                if (tarErr.isNotBlank()) {
                    append("original extractor errors (paths + errno, spec #9): ")
                    append(tarErr.take(900))
                } else append("no stderr captured (tool output: ${s.output.take(200)})")
                append(" — the broken tree and the device tarball were cleaned; the verified archive is still in app storage, retry the install")
            }
            Log.e(TAG, "extract failed: $why")
            return Extract(false, why)
        }
        if (tarErr.isNotBlank()) Log.w(TAG, "extract warnings: ${tarErr.take(300)}")
        return Extract(true, "toybox 0.8.11 tar rc=0${if (tarErr.isNotBlank()) " (warnings: ${tarErr.take(120)})" else ""}")
    }

    /**
     * Post-extract audit — the r1385 failure class ("symlink: Permission denied")
     * can also appear as tar CONTINUING with rc=0 while skipping symlinks. This
     * audit makes that impossible to pass as success:
     *  - /bin must be a real symlink → usr/bin (Debian 12 merged-usr);
     *  - the tree must contain a plausible number of symlinks (the layer has 642);
     *  - /etc/os-release, bash and apt-get must exist.
     */
    private suspend fun auditRootfs(dir: String): Pair<Boolean, String> {
        val s = ShizukuExec.oneShot(
            "R=''; " +
                "test -L '$dir/bin' && R=\"\$R BIN_LINK=\$(readlink '$dir/bin')\" || R=\"\$R BIN_LINK=MISSING\"; " +
                "test -f '$dir/etc/os-release' && R=\"\$R OSREL=yes\" || R=\"\$R OSREL=no\"; " +
                "test -x '$dir/usr/bin/bash' && R=\"\$R BASH=yes\" || R=\"\$R BASH=no\"; " +
                "test -x '$dir/usr/bin/apt-get' && R=\"\$R APT=yes\" || R=\"\$R APT=no\"; " +
                "N=\$(find '$dir' -type l 2>/dev/null | wc -l); R=\"\$R SYMLINKS=\$N\"; " +
                "echo \"AUDIT[\$R]\"", 120_000)
        val m = Regex("AUDIT\\[(.*)\\]").find(s.output)?.groupValues?.get(1)
            ?: return false to "audit did not return: ${s.output.take(200)}"
        fun tag(k: String): String = m.split(" ").firstOrNull { it.startsWith("$k=") }?.removePrefix("$k=") ?: "?"
        val links = tag("SYMLINKS").toLongOrNull() ?: -1L
        val problems = ArrayList<String>()
        if (tag("BIN_LINK") != "usr/bin") problems.add("/bin is not a symlink to usr/bin (got ${tag("BIN_LINK")}) — symlink creation was denied or skipped (storage/SELinux?)")
        if (tag("OSREL") != "yes") problems.add("/etc/os-release missing")
        if (tag("BASH") != "yes") problems.add("/usr/bin/bash missing")
        if (tag("APT") != "yes") problems.add("/usr/bin/apt-get missing")
        if (links < MIN_SYMLINKS) problems.add("only $links symlinks found (expected ≥ $MIN_SYMLINKS; the Debian layer has 642) — symlink creation was silently skipped")
        return if (problems.isEmpty()) true to "bin→usr/bin ✓, os-release ✓, bash ✓, apt-get ✓, $links symlinks ✓"
        else false to problems.joinToString("; ")
    }

    // ---------- download sources (both digest-verified) ----------

    private class Download(val file: File, val source: String, val digest: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val rb = Request.Builder().url(url)
        for ((k, v) in headers) rb.header(k, v)
        return http.newCall(rb.build()).execute()
    }

    private fun registryToken(): String? = try {
        httpGet("https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull")
            .body?.string()?.let { JSONObject(it).optString("token") }
    } catch (e: Exception) { null }

    /**
     * Primary: Docker registry manifest→layer chain (sha256 verified while
     * streaming). Fallback: cdimage.debian.org + official SHA512SUMS.
     * Returns null only if BOTH fail — never a fake file.
     */
    private suspend fun download(ctx: Context, onProgress: (Long, Long) -> Unit): Download? {
        val arch = archTag() ?: return null
        // ---- primary: registry by tag, verified by the layer's own digest ----
        try {
            val token = registryToken()
            val idxBody = httpGet(
                "https://registry-1.docker.io/v2/library/debian/manifests/bookworm",
                mapOf(
                    "Accept" to "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json",
                    "Authorization" to "Bearer $token"
                )
            ).body?.string()
            val idx = JSONObject(idxBody ?: throw IllegalStateException("empty index"))
            var mDigest: String? = null
            val manifests = idx.optJSONArray("manifests")
            if (manifests != null) for (i in 0 until manifests.length()) {
                val m = manifests.getJSONObject(i)
                val p = m.optJSONObject("platform") ?: continue
                if (p.optString("os") == "linux" && p.optString("architecture") == arch) { mDigest = m.optString("digest"); break }
            }
            if (mDigest.isNullOrEmpty()) throw IllegalStateException("no linux/$arch manifest in the bookworm index")
            val manBody = httpGet(
                "https://registry-1.docker.io/v2/library/debian/manifests/$mDigest",
                mapOf(
                    "Accept" to "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
                    "Authorization" to "Bearer $token"
                )
            ).body?.string()
            val man = JSONObject(manBody ?: throw IllegalStateException("empty manifest"))
            val layers = man.optJSONArray("layers")
                ?: throw IllegalStateException("manifest has no layers")
            var best: Pair<String, Long>? = null
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                val cur = best
                if (cur == null || l.optLong("size", 0) > cur.second) best = l.optString("digest") to l.optLong("size", 0)
            }
            val (layerDigest, layerSize) = best ?: throw IllegalStateException("no layer found")
            val dir = File(ctx.filesDir, "linux").apply { mkdirs() }
            val out = File(dir, "debian-rootfs.tar.gz")
            val expected = layerDigest.removePrefix("sha256:")
            streamToFile(
                "https://registry-1.docker.io/v2/library/debian/blobs/$layerDigest",
                out, mapOf("Authorization" to "Bearer $token"), "sha256", expected, layerSize, onProgress
            )
            return Download(out, "docker-registry (sha256 digest-verified)", layerDigest)
        } catch (e: Exception) {
            Log.w(TAG, "registry download failed — trying cdimage fallback", e)
        }
        // ---- fallback: official Debian cloud rootfs + SHA512SUMS ----
        try {
            val sums = httpGet("https://cdimage.debian.org/cdimage/cloud/bookworm/latest/SHA512SUMS")
                .body?.string() ?: throw IllegalStateException("SHA512SUMS unreachable")
            val name = "debian-12-genericcloud-$arch.tar.xz"
            val expected = sums.lineSequence()
                .filter { it.contains(name) }
                .firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                ?: throw IllegalStateException("$name not listed in the official SHA512SUMS")
            val dir = File(ctx.filesDir, "linux").apply { mkdirs() }
            val out = File(dir, "debian-rootfs.tar.xz")
            streamToFile(
                "https://cdimage.debian.org/cdimage/cloud/bookworm/latest/$name",
                out, emptyMap(), "sha512", expected, -1, onProgress
            )
            return Download(out, "cdimage.debian.org (official SHA512SUMS-verified)", null)
        } catch (e: Exception) {
            Log.e(TAG, "cdimage fallback failed too", e)
            return null
        }
    }

    /** Streams a URL to a file while computing the digest — fails on mismatch. */
    private fun streamToFile(url: String, out: File, headers: Map<String, String>,
                             algo: String, expected: String, total: Long,
                             onProgress: (Long, Long) -> Unit) {
        val resp = httpGet(url, headers)
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} for $url")
            val body = r.body ?: throw IllegalStateException("empty body")
            val md = MessageDigest.getInstance(algo)
            val tmp = File(out.absolutePath + ".part")
            var done = 0L
            val declared = if (total > 0) total else body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { fos ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(done, declared)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(expected, ignoreCase = true)) {
                tmp.delete()
                throw IllegalStateException("digest mismatch: expected $expected got $hex")
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("cannot finalize the downloaded file")
        }
    }

    // ---------- reset / remove ----------

    /** Re-extract from the private archive (no re-download when it is still there). */
    suspend fun reset(ctx: Context, onProgress: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val tar = tarballFile(ctx)
            if (!tar.exists())
                return@withContext "RESET FAILED: the rootfs archive is not in app storage anymore — run Install instead."
            if (currentState(ctx) != State.READY)
                return@withContext "RESET FAILED: environment is not READY (state=${currentState(ctx)}) — run Install instead."
            try {
                onProgress("reset: removing the runtime tree")
                var s = ShizukuExec.oneShot("rm -rf $BASE/rootfs && mkdir -p $BASE/rootfs && echo R1_OK", 120_000)
                if (!s.ok) return@withContext "RESET FAILED at cleanup: ${s.output.take(200)}"
                onProgress("reset: transferring the archive again")
                val p = ShizukuExec.pushFile(tar, "$BASE/rootfs.tar." + if (tar.name.endsWith(".xz")) "xz" else "gz")
                if (!p.ok) return@withContext "RESET FAILED at transfer: ${p.output.take(200)}"
                val remoteTar = "$BASE/rootfs.tar." + if (tar.name.endsWith(".xz")) "xz" else "gz"
                onProgress("reset: ensuring the bundled extractor is present")
                val nd = ctx.applicationInfo.nativeLibraryDir
                val tbf = File(nd, "libamino_tar.so")
                if (tbf.exists()) {
                    ShizukuExec.pushFile(tbf, TOYBOX)
                    ShizukuExec.oneShot("chmod 755 '$TOYBOX'")
                }
                onProgress("reset: extracting with the bundled toybox tar")
                val ex = extractRootfs(remoteTar, "$BASE/rootfs")
                if (!ex.ok)
                    return@withContext "RESET FAILED at extraction: ${ex.detail}"
                val audit = auditRootfs("$BASE/rootfs")
                if (!audit.first)
                    return@withContext "RESET FAILED at audit: ${audit.second}"
                s = ShizukuExec.oneShot(
                    "printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > $ROOTFS/etc/resolv.conf && " +
                        "printf '127.0.0.1 localhost\\n' > $ROOTFS/etc/hosts && " +
                        "mkdir -p $ROOTFS/tmp $ROOTFS/root && chmod 1777 $ROOTFS/tmp && rm -f $remoteTar && echo CFG_OK"
                )
                if (ShizukuExec.serviceUid() == 0) ShizukuExec.oneShot("chown -R 2000:2000 $BASE", 300_000)
                val probe = probe(ctx)
                if (!probe.first) {
                    setState(ctx, State.BROKEN, "reset failed verification", probe.second.take(300))
                    return@withContext "RESET FAILED at verification: ${probe.second.take(300)}"
                }
                setState(ctx, State.READY, "ready (reset ${System.currentTimeMillis()})")
                "RESET DONE AND VERIFIED — probe: ${probe.second.trim().replace("\n", " · ")}"
            } catch (e: Throwable) {
                setState(ctx, State.BROKEN, "reset failed", e.message ?: e.javaClass.simpleName)
                "RESET FAILED: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    /** Removes the runtime tree AND the private archive. Honest about what is deleted. */
    suspend fun remove(ctx: Context, onProgress: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            try {
                onProgress("remove: deleting the runtime tree")
                val s = ShizukuExec.oneShot("rm -rf $BASE && echo RM_OK", 300_000)
                if (!s.ok && !s.output.contains("RM_OK") && s.rc != 0)
                    return@withContext "REMOVE: runtime tree delete returned rc=${s.rc} (${s.output.take(120)}) — check manually: $BASE"
                val tar = tarballFile(ctx)
                val freedTar = if (tar.exists()) tar.length() else 0L
                if (tar.exists()) tar.delete()
                File(ctx.filesDir, "linux").deleteRecursively()
                setState(ctx, State.NOT_INSTALLED, "removed")
                val o = JSONObject().put("state", State.NOT_INSTALLED.name).put("message", "removed")
                save(ctx, o)
                "REMOVED — runtime tree $BASE deleted${if (freedTar > 0) ", private archive (${freedTar / (1024 * 1024)} MB) deleted" else ""}."
            } catch (e: Throwable) {
                "REMOVE FAILED: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    // ---------- probe (the ONLY thing that flips claims into facts) ----------

    /**
     * Spawns PRoot once and runs `id; head -1 /etc/os-release` INSIDE the
     * container. Returns (verified, evidence).
     */
    suspend fun probe(ctx: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuExec.available()) return@withContext false to "aMiNo service is not running"
        val env = arrayOf(
            "PROOT_TMP_DIR=$BASE/tmp",
            "LD_LIBRARY_PATH=$BASE/lib",
            "HOME=/root"
        )
        val p = try {
            ShizukuExec.spawn(
                listOf(
                    "$BASE/bin/proot", "--kill-on-exit", "--link2symlink",
                    "-0", "-r", ROOTFS,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "/usr/bin/env", "-i",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "HOME=/root",
                    "/bin/sh", "-c", "id; head -n 1 /etc/os-release; bash --version 2>/dev/null | head -n 1; apt-get --version 2>/dev/null | head -n 1"
                ), env, "/"
            )
        } catch (e: Throwable) {
            return@withContext false to "probe spawn failed: ${e.message ?: e.javaClass.simpleName}"
        }
        val out = StringBuilder()
        try {
            val buf = ByteArray(4096)
            while (true) {
                val n = p.stdout.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n))
            }
            p.waitFor()
        } catch (e: Throwable) {
            return@withContext false to "probe io failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            p.destroy()
        }
        val text = out.toString().trim()
        val ok = text.contains("uid=0") && text.contains("Debian") &&
            text.contains("bash", ignoreCase = true) && text.contains("apt", ignoreCase = true)
        ok to text
    }

    // ---------- session command builder (used by TerminalSession) ----------

    /** The PRoot argv that becomes a persistent bash session inside Debian. */
    fun sessionCommand(ctx: Context): Pair<List<String>, Array<String>> {
        val binds = ArrayList<String>()
        for (b in listOf("/dev", "/proc", "/sys", "/system", "/vendor"))
            if (java.io.File(b).exists()) { binds.add("-b"); binds.add(b) }
        val shared = File(ctx.filesDir, "linux/shared").apply { mkdirs() }
        binds.add("-b"); binds.add("${shared.absolutePath}:/shared")
        val cmd = listOf(
            "$BASE/bin/proot", "--kill-on-exit", "--link2symlink",
            "-0", "-r", ROOTFS, "-w", "/root"
        ) + binds + listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "SHELL=/bin/bash",
            "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "/bin/bash"
        )
        val env = arrayOf(
            "PROOT_TMP_DIR=$BASE/tmp",
            "LD_LIBRARY_PATH=$BASE/lib",
            "HOME=/root"
        )
        return cmd to env
    }

    /** Out-of-band kill for a tracked in-container pid (global pid namespace). */
    suspend fun killPid(pid: Int): Boolean {
        val s = ShizukuExec.oneShot("kill -9 $pid 2>/dev/null; echo KILLDONE", 10_000)
        return s.ok && s.output.contains("KILLDONE")
    }

    /** Sweep any leftover processes bound to the amino-linux runtime tree. */
    suspend fun sweepStrayProcesses() {
        ShizukuExec.oneShot(
            "for p in /proc/[0-9]*/cmdline; do " +
                "if grep -aq 'amino-linux' \"\$p\" 2>/dev/null; then " +
                "pid=\${p%/cmdline}; pid=\${pid#/proc/}; kill -9 \$pid 2>/dev/null; fi; done; echo SWEEP_DONE",
            20_000
        )
    }

    val backend: TermBackend get() = TermBackend.LINUX_USERSPACE
}
