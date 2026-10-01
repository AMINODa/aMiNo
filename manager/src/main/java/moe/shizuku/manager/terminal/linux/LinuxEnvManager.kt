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
import moe.shizuku.manager.terminal.TerminalEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
 *    (os-release + bash + apt; id is evidence only) — unchanged (user req #6).
 *
 * r1390 FIX (Debian runtime smoke test — user bug report, 6 requirements):
 *  - FALSE-FAILURE ROOT CAUSE (device evidence, mechanism reproduced with the
 *    bundled binary): the aMiNo service runs as Android shell UID 2000 and the
 *    smoke rootfs has NO /etc/passwd — toybox `id` on a uid with no passwd
 *    entry FAILS ("bad uid 2000", rc=1). The r1389 gates required `uid=0`
 *    from `id` in BOTH the host-side post-setup verification AND the PRoot
 *    smoke run, so a perfectly healthy userspace rootfs was rejected by an
 *    identity lookup that says nothing about Debian. `id` is no longer a gate
 *    ANYWHERE: not in the smoke test, not in probe(), not in acceptance T3.
 *  - IDENTITY HONESTY (reqs #1/#6): this is NOT a root environment. The
 *    service executes everything as the Android shell user (UID 2000; a
 *    root-mode service is dropped to 2000 via `su 2000 -c`). Every label now
 *    says "Debian userspace under Android shell UID 2000"; a UID-0 label is
 *    used only when a genuine privileged backend actually answered.
 *  - CANONICAL SYMLINK VALIDATION (req #3): `readlink -f` is resolved on BOTH
 *    sides (link and target) and those canonical paths are compared with each
 *    other — a valid `bin → usr/bin` + `cat → toybox` chain can no longer be
 *    rejected because the expected path was expressed differently (relative
 *    vs absolute, prefix symlinks).
 *  - THE SMOKE RUN IS A DEBIAN USERSPACE SMOKE TEST (req #4): (1) toybox is
 *    executable in place; (2) `toybox --help` runs (rc=0, stable
 *    "usage: toybox" token — validated with the exact shipped binary);
 *    (3) `/bin/cat /etc/os-release` runs through the merged-/usr chain and
 *    the content must match; (4) each run's rc and output are verified
 *    INDEPENDENTLY as its own named CHECK; (5) the service UID is reported
 *    separately as identity evidence, never as a pass condition.
 *  - FAILURE TEXT (req #5): "nothing was downloaded" is never claimed once
 *    binaries were already pushed — the report names the EXACT failed check
 *    and states what is PRESERVED (pushed binaries, the minimal rootfs for
 *    inspection, and any previously verified rootfs archive for the retry).
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

    /**
     * r1390 — failure text for the SMOKE-ROOTFS BUILD steps (user req #5): the
     * EXACT failed check — the failing command with its real rc/stdout/stderr,
     * PLUS the directory listing captured AT THE MOMENT OF FAILURE — reported
     * before anything is changed or cleaned, so the state is inspected, never
     * guessed. It never claims "nothing was downloaded": binaries WERE pushed
     * by this point and a previously verified archive may exist — the text
     * states what is PRESERVED instead. The tree is deliberately left in
     * place; the next install removes and rebuilds it.
     */
    private suspend fun smokeFailMsg(inv: Inv, mini: String, why: String): String = buildString {
        append("SMOKE ROOTFS SETUP FAILED — the exact failed check is below\n")
        append("• reason: $why\n")
        append(inv.render())
        val listing = try {
            ShizukuExec.oneShot(
                "echo '--- miniroot ---'; ls -la '$mini' 2>&1; echo '--- miniroot/usr ---'; ls -la '$mini/usr' 2>&1; " +
                    "echo '--- miniroot/usr/bin ---'; ls -la '$mini/usr/bin' 2>&1; echo '--- miniroot/bin ---'; ls -la '$mini/bin' 2>&1; " +
                    "echo '--- miniroot/etc ---'; ls -la '$mini/etc' 2>&1", 15_000
            ).output.trim()
        } catch (e: Exception) { "(listing unavailable: ${e.message ?: e.javaClass.simpleName})" }
        append("• directory listing AT FAILURE (unchanged, reported before any fix):\n$listing\n")
        append("• the minimal rootfs is left in place for inspection; the next install attempt removes and rebuilds it\n")
        append("• PRESERVED: the bundled binaries already pushed to $BASE and any previously verified rootfs archive in app storage — the next attempt reuses them without re-downloading\n")
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
            State.READY -> true to "ready — Debian 12 userspace via PRoot, running under the Android shell identity (no root)"
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

    // ---------- r1395: honest stage board (user Task 5) + acceptance record ----------

    /** Persist the last acceptance run (per-test evidence) for the stage board. */
    fun recordAcceptance(ctx: Context, pass: Boolean, results: List<LinuxAcceptance.TestResult>) {
        val arr = JSONArray()
        for (r in results) arr.put(JSONObject()
            .put("name", r.name).put("pass", r.pass)
            .put("rc", r.exitCode ?: -1).put("evidence", r.evidence.take(200)))
        save(ctx, load(ctx).put("acceptance", JSONObject()
            .put("ts", System.currentTimeMillis()).put("pass", pass).put("results", arr)))
    }

    /** The honest stage ladder: what is PROVEN, what is not, and what failed. */
    fun stageBoard(ctx: Context): String {
        val o = load(ctx)
        val installed = o.optString("state") == State.READY.name
        val sb = StringBuilder()
        fun line(ok: Boolean?, key: String, detail: String) {
            sb.append(when (ok) { true -> "✓ "; false -> "✗ "; null -> "• " }).append(key)
            if (detail.isNotBlank()) sb.append(" — ").append(detail.take(220))
            sb.append('\n')
        }
        if (installed) {
            val stages = o.optJSONArray("stages")
            if (stages != null) {
                for (i in 0 until stages.length()) {
                    val st = stages.optJSONObject(i) ?: continue
                    line(st.optBoolean("ok"), st.optString("key"), st.optString("detail"))
                }
            } else {
                // pre-r1395 install: reconstruct from the evidence already in prefs
                line(o.optString("dlDigest").isNotBlank(), "ARCHIVE_VERIFIED",
                    "source ${o.optString("source")} · digest ${o.optString("dlDigest").take(16)}…")
                line(true, "ROOTFS_EXTRACTED", "runtime $ROOTFS")
                line(o.optString("smoke").isNotBlank(), "ROOTFS_VALIDATED", "audit recorded at install")
                line(o.optString("smoke").isNotBlank(), "PROOT_RUNTIME_VERIFIED", o.optString("prootVersion"))
            }
        } else {
            val err = o.optString("lastError")
            line(false, "ARCHIVE_VERIFIED", if (err.isNotBlank()) "last error: ${err.take(160)}" else "install not completed yet")
            line(false, "ROOTFS_EXTRACTED", ""); line(false, "ROOTFS_VALIDATED", ""); line(false, "PROOT_RUNTIME_VERIFIED", "")
        }
        // live session evidence (in-process — no service roundtrip)
        val live = TerminalEngine.listSessions().any { it.backend == TermBackend.LINUX_USERSPACE && it.alive && it.ready }
        line(live, "TERMINAL_SESSION_CREATED",
            if (live) "a live Debian (PRoot) session exists now" else "no live Debian session right now (open the terminal to create one)")
        // acceptance evidence (persisted per-test)
        val acc = o.optJSONObject("acceptance")
        if (acc == null) {
            line(null, "ACCEPTANCE_TESTS_PASSED", "never run yet")
        } else {
            val failed = ArrayList<String>()
            acc.optJSONArray("results")?.let { rs ->
                for (i in 0 until rs.length()) { val r = rs.optJSONObject(i) ?: continue; if (!r.optBoolean("pass")) failed.add(r.optString("name")) }
            }
            line(acc.optBoolean("pass"), "ACCEPTANCE_TESTS_PASSED",
                (if (acc.optBoolean("pass")) "all tests passed" else "FAILED: ${failed.joinToString(", ").ifBlank { "runner" }}") +
                    " · last run " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(acc.optLong("ts"))))
        }
        return sb.toString().trimEnd()
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
            step("preflight: arch / storage / network / service / symlink+hardlink-support")
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
            val hardlinkState = when (pf.fs?.hardlinkOk) {
                true -> "OK"
                false -> "DENIED by this device — extraction auto-repairs the affected entries by copy (r1394)"
                null -> "unknown"
            }
            step("preflight OK: arch=${pf.arch}, app-storage ${pf.freePrivateBytes / (1024 * 1024)} MB free, " +
                "data ${((pf.freeDataBytes ?: -1) / (1024 * 1024))} MB free, service uid=${pf.serviceUid}, " +
                "fstype=${pf.fs?.fstype ?: "?"}, symlinks=OK, hardlinks=$hardlinkState")
            pf.prootElfDiag?.let { step("proot ELF diagnostic: $it") }

            // ---- 2) runtime dirs + bundled binaries ----
            setState(ctx, State.CHECKING, "preparing runtime directory")
            var s = ShizukuExec.oneShot("mkdir -p $BASE/bin $BASE/lib $BASE/libexec/proot $BASE/tmp $BASE/rootfs && echo DIRS_OK")
            if (!s.ok || !s.output.contains("DIRS_OK"))
                return fail(ctx, "cannot create $BASE through the service: rc=${s.rc} ${s.output.take(200)}")

            val nd = ctx.applicationInfo.nativeLibraryDir
            // r1393: this proot build (termux-packages v5.1.107.95) rewrites EVERY
            // guest execve to its loader path ("Execute the loader instead of the
            // program", enter.c). Its compiled-in default points into Termux's
            // private data dir, unreachable for the shell UID (r1392 T6 evidence:
            // /data/data/com.termux is 0700 u0_a372). The loader binary from the
            // SAME Termux package is therefore shipped and PROOT_LOADER is set on
            // every invocation — the mechanism AND the fix are proven locally
            // (scripts/verify_loader_mechanism.sh: unreachable loader rc=1
            // "Permission denied" == device T0/T3x; PROOT_LOADER=<non-loader> rc=255
            // == device T5; real loader + PROOT_LOADER rc=0 == working exec).
            val pushes = listOf(
                Triple("libamino_proot.so", "$BASE/bin/proot", "PRoot binary"),
                Triple("libamino_proot_loader.so", "$BASE/libexec/proot/loader", "PRoot loader (r1393: the execve loader slot must point at a reachable file)"),
                Triple("libamino_proot_loader32.so", "$BASE/libexec/proot/loader32", "PRoot 32-bit loader (r1393)"),
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
                "chmod 755 $BASE/bin/proot $BASE/libexec/proot/loader $BASE/libexec/proot/loader32 $BASE/bin/xz $TOYBOX && " +
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

            // ---- 2c) MINIMAL-ROOTFS SMOKE TEST (r1387; rebuilt r1389; DEBIAN
            //          USERSPACE rework r1390 — user bug report) ----
            // r1390 ROOT CAUSE (user device evidence + mechanism reproduced with
            // the bundled binary): the service runs as Android shell UID 2000 and
            // the smoke rootfs has NO /etc/passwd — toybox `id` on a uid with no
            // passwd entry ERRORS ("bad uid 2000", rc=1). The r1389 gates
            // required `uid=0` from `id` BOTH in post-setup verification AND in
            // the PRoot smoke run, so a healthy userspace rootfs was rejected by
            // an identity lookup that says nothing about Debian (reqs #1/#2).
            // `id` is no longer run or linked here at all; the service UID is
            // reported separately as evidence (req #4). This is a USERSPACE
            // environment — never classified as root (req #1). Symlink
            // validation compares readlink -f of BOTH sides so a valid link
            // expressed differently cannot be rejected (req #3). The smoke run
            // verifies toybox exec, `toybox --help` and `cat /etc/os-release`
            // with each rc and output checked INDEPENDENTLY (req #4). The
            // layout still mirrors Debian 12 merged-/usr: /usr/bin FIRST, then
            // /bin -> usr/bin as a RELATIVE symlink; every op stays
            // exit-code-checked, the tree is inspected before linking.
            setState(ctx, State.CHECKING, "PRoot smoke test on a minimal userspace rootfs (no Debian involved yet)")
            val MINI = "$BASE/miniroot"

            // 2c-1) KEEP, don't delete (r1391, user req #6): the diagnostic
            // rootfs is EVIDENCE — an existing tree is validated and REUSED
            // whenever it is sound; only an INVALID/partial tree is removed
            // (the reason is logged first) and rebuilt. Debian is still gated
            // behind the smoke test, so nothing is downloaded on top of an
            // unisolated failure either.
            val mrReuse = runStep("smoke rootfs: validate any existing tree (keep instead of delete)",
                "if [ ! -e '$MINI/usr/bin/toybox' ]; then echo MR_ABSENT; " +
                    "elif '$MINI/usr/bin/toybox' --version >/dev/null 2>&1 && " +
                    "[ \"\$('$TOYBOX' readlink '$MINI/bin' 2>&1)\" = usr/bin ] && " +
                    "[ \"\$('$TOYBOX' readlink -f '$MINI/bin/cat' 2>&1)\" = \"\$('$TOYBOX' readlink -f '$MINI/usr/bin/toybox' 2>&1)\" ] && " +
                    "grep -q 'AMINO userspace smoke rootfs' '$MINI/etc/os-release' 2>/dev/null; then echo MR_VALID; " +
                    "else echo MR_INVALID; fi")
            val reuseTree = mrReuse.rc == 0 && mrReuse.stdout.contains("MR_VALID")
            if (reuseTree) {
                step("smoke rootfs: REUSING the existing diagnostic tree at $MINI (kept per req #6 — never deleted just to rebuild); it is re-verified canonically below")
            } else {
                val absent = mrReuse.stdout.contains("MR_ABSENT")
                if (!absent)
                    step("smoke rootfs: existing tree is INVALID (${mrReuse.stdout.take(120)}) — removing it WITH THIS LOGGED REASON (the only permitted deletion); then rebuilding")
                val mrClean = runStep(
                    if (absent) "smoke rootfs: no previous tree — build fresh"
                    else "smoke rootfs: remove the INVALID tree (verified removal, reason logged above)",
                    "rm -rf '$MINI' && test ! -e '$MINI' && echo CLEAN_OK")
                if (mrClean.rc != 0 || !mrClean.stdout.contains("CLEAN_OK"))
                    return runtimeFail(ctx, smokeFailMsg(mrClean, MINI,
                        "the previous smoke rootfs could not be removed at $MINI"), "smoke rootfs build")
            }
            // (the tree build below is skipped entirely when an existing valid
            // tree is reused — 2c-8 re-verifies it canonically either way)
            if (!reuseTree) {

            // 2c-2) directory tree — merged-/usr: /usr/bin FIRST (Debian 12 layout)
            val mrDirs = runStep("smoke rootfs: create directory tree (merged-/usr: usr/bin first)",
                "mkdir -p '$MINI/usr/bin' '$MINI/etc' '$MINI/root' '$MINI/tmp' '$MINI/dev' '$MINI/proc' && " +
                    "test -d '$MINI/usr/bin' && echo DIRS_OK")
            if (mrDirs.rc != 0 || !mrDirs.stdout.contains("DIRS_OK"))
                return runtimeFail(ctx, smokeFailMsg(mrDirs, MINI,
                    "could not create the smoke-rootfs directory tree under $MINI"), "smoke rootfs build")

            // 2c-3) toybox into /usr/bin + exec it IN PLACE at its final location.
            // Req #4 (1)+(2): toybox is executable AND `toybox --help` runs —
            // the bundled binary answers rc=0 with a stable "usage: toybox"
            // token (validated with the exact shipped binary; NO pipeline —
            // the rc is toybox's own, per the r1387/r1388 lesson).
            val mrToy = runStep("smoke rootfs: install toybox into /usr/bin, exec it in place (+ --help)",
                "cp '$TOYBOX' '$MINI/usr/bin/toybox' && chmod 755 '$MINI/usr/bin/toybox' && " +
                    "'$MINI/usr/bin/toybox' --version && '$MINI/usr/bin/toybox' --help && echo TOY_OK")
            if (mrToy.rc != 0 || !mrToy.stdout.contains("TOY_OK") || !mrToy.stdout.contains("usage: toybox"))
                return runtimeFail(ctx, smokeFailMsg(mrToy, MINI,
                    "the toybox copy inside the smoke rootfs did not exec, or --help did not answer, at $MINI/usr/bin/toybox"), "smoke rootfs build")

            // 2c-4) INSPECT every parent path BEFORE creating any link (user req #1)
            val mrInspect = runStep("smoke rootfs: directory inspection before linking",
                "echo '--- miniroot ---'; ls -la '$MINI' 2>&1; echo '--- miniroot/usr ---'; ls -la '$MINI/usr' 2>&1; " +
                    "echo '--- miniroot/usr/bin ---'; ls -la '$MINI/usr/bin' 2>&1")
            if (mrInspect.rc != 0)
                return runtimeFail(ctx, smokeFailMsg(mrInspect, MINI,
                    "could not list the smoke-rootfs directories"), "smoke rootfs build")
            step("smoke-rootfs listing before any link:\n${mrInspect.stdout.take(700)}")

            // 2c-5) /bin -> usr/bin — RELATIVE symlink (merged-/usr; survives being
            // mounted at any root; /bin is NEVER treated as a normal directory)
            val mrBin = runStep("smoke rootfs: /bin -> usr/bin (relative, merged-/usr)",
                "ln -s usr/bin '$MINI/bin' && echo \"BIN_TARGET=\$('$TOYBOX' readlink '$MINI/bin' 2>&1)\"")
            if (mrBin.rc != 0 || !mrBin.stdout.contains("BIN_TARGET=usr/bin"))
                return runtimeFail(ctx, smokeFailMsg(mrBin, MINI,
                    "could not create the merged-/usr /bin -> usr/bin symlink"), "smoke rootfs build")

            // 2c-6) applet links — EACH ln individually exit-code-checked, fail-fast
            // with the applet name. r1390 (user req #2): `id` is NOT linked and
            // NEVER run — the Android shell UID (2000) has no entry in the smoke
            // rootfs's (nonexistent) passwd database, so toybox `id` errors there
            // ("bad uid 2000"); that is the normal Android identity showing
            // through, NOT an installation failure. Only `cat` is linked: it is
            // the applet the userspace smoke test actually verifies.
            val mrLinks = runStep("smoke rootfs: applet links (each ln rc-checked)",
                "ok=1; for a in cat; do ln -sf toybox '$MINI/usr/bin/'\$a || { ok=0; echo \"LN_FAIL applet=\$a\"; }; done; " +
                    "[ \"\$ok\" = 1 ] && echo LINKS_OK")
            if (mrLinks.rc != 0 || !mrLinks.stdout.contains("LINKS_OK"))
                return runtimeFail(ctx, smokeFailMsg(mrLinks, MINI,
                    "an applet symlink failed inside the smoke rootfs (${mrLinks.stdout.take(120)})"), "smoke rootfs build")

            // 2c-7) /etc/os-release — written AND read back (this is the file the
            // userspace smoke test cats through the merged-/usr chain, req #4 (3))
            val mrOsr = runStep("smoke rootfs: write /etc/os-release and read it back",
                "printf 'PRETTY_NAME=\"AMINO userspace smoke rootfs\"\\nID=amino-smoke\\n' > '$MINI/etc/os-release' && " +
                    "cat '$MINI/etc/os-release' && echo OSR_OK")
            if (mrOsr.rc != 0 || !mrOsr.stdout.contains("OSR_OK"))
                return runtimeFail(ctx, smokeFailMsg(mrOsr, MINI,
                    "could not write/read the smoke rootfs's /etc/os-release"), "smoke rootfs build")
            } // end of "build fresh tree" (skipped when a valid tree was reused)

            // 2c-8) post-setup verification BEFORE proot (r1390, user reqs #3/#4):
            //   • /bin/cat exists + executable through the /bin -> usr/bin chain
            //   • CANONICAL symlink validation (req #3): `readlink -f` is
            //     resolved on BOTH sides and the two canonical paths are compared
            //     WITH EACH OTHER — a valid bin→usr/bin + cat→toybox chain can no
            //     longer be rejected because the expected path was expressed
            //     differently (relative vs absolute, prefix symlinks)
            //   • the link EXPRESSIONS (usr/bin, toybox) are recorded as evidence
            //   • a REAL direct exec of /bin/cat against /etc/os-release
            //   NO `id` anywhere (req #2 — see 2c-6)
            val mrVerify = runStep("smoke rootfs: verify links canonically (readlink -f both sides) and exec cat",
                "test -e '$MINI/bin/cat' && echo E_CAT; test -x '$MINI/bin/cat' && echo X_CAT; " +
                    "echo \"LINK_BIN=\$('$TOYBOX' readlink '$MINI/bin' 2>&1)\"; " +
                    "echo \"LINK_CAT=\$('$TOYBOX' readlink '$MINI/usr/bin/cat' 2>&1)\"; " +
                    "echo \"CANON_TOY=\$('$TOYBOX' readlink -f '$MINI/usr/bin/toybox' 2>&1)\"; " +
                    "echo \"CANON_CAT=\$('$TOYBOX' readlink -f '$MINI/bin/cat' 2>&1)\"; " +
                    "'$MINI/bin/cat' '$MINI/etc/os-release' && echo CAT_OK")
            fun vTag(k: String) = Regex("$k=(.*)").find(mrVerify.stdout)?.groupValues?.get(1)?.trim()
            val canonToy = vTag("CANON_TOY")
            val canonCat = vTag("CANON_CAT")
            val verifyOk = mrVerify.rc == 0 && mrVerify.stdout.contains("E_CAT") &&
                mrVerify.stdout.contains("X_CAT") && mrVerify.stdout.contains("CAT_OK") &&
                !canonToy.isNullOrBlank() && !canonCat.isNullOrBlank() && canonToy == canonCat
            if (!verifyOk)
                return runtimeFail(ctx, smokeFailMsg(mrVerify, MINI,
                    "the smoke rootfs failed post-setup verification (exists/executable/canonical-resolve/exec; " +
                        "canonical toybox=$canonToy, canonical cat=$canonCat — they must be the SAME path)"), "smoke rootfs build")
            step("smoke rootfs verified before PRoot: /bin/cat exists ✓ executable ✓ " +
                "resolves canonically → $canonCat (== toybox, merged-/usr chain) ✓ direct exec ✓ " +
                "link expressions: /bin→${vTag("LINK_BIN")}, cat→${vTag("LINK_CAT")}")

            // 2c-8b) SERVICE IDENTITY — reported SEPARATELY, never a gate
            // (user reqs #2/#4). `id -u` runs through the service's own execution
            // path (a root-mode service is already dropped to 2000 via
            // `su 2000 -c`, so this is the true EXECUTING identity).
            val uidInv = runStep("smoke rootfs: service identity (reported separately, never a gate)",
                "echo \"UID_NUM=\$(id -u 2>&1)\"; id 2>&1")
            val uidNum = Regex("UID_NUM=(\\d+)").find(uidInv.stdout)?.groupValues?.get(1)
                ?: ShizukuExec.serviceUid().takeIf { it >= 0 }?.toString() ?: "unknown"
            val uidLabel = if (uidNum == "0") "UID 0 — a genuine privileged backend answered"
                else "Android shell UID $uidNum — Debian userspace, NOT root"
            step("service identity (evidence only): $uidLabel")

            // 2c-8c) EXEC-RUNTIME DIAGNOSTICS (r1391, reqs #1/#2) — READ-ONLY
            // evidence gathered on the EXACT canonical host path PRoot must
            // execve, BEFORE the smoke run: stat (mode/owner/group/size), the
            // SELinux label, the service's own domain + enforcing state, the
            // ptrace policy, the containing filesystem's DEVICE + MOUNT
            // OPTIONS (noexec would explain everything), the ELF
            // class/machine bytes of the file itself, any stale proot loader
            // files in $BASE/tmp, and a DIRECT exec OUTSIDE PRoot with stderr
            // and rc captured SEPARATELY. Nothing here changes the system:
            // no chmod, no setenforce, no remount (req #5). This step is
            // evidence-only and never gates the install by itself.
            val diag = runStep("exec-runtime diagnostics (read-only): stat/SELinux/mounts/ELF + direct exec outside PRoot",
                "CP=\$('$TOYBOX' readlink -f '$MINI/usr/bin/toybox' 2>/dev/null); echo \"CANON=\$CP\"; " +
                    "stat -c 'STAT=%A (%a) owner=%u:%g size=%s' \"\$CP\" 2>/dev/null; " +
                    "LBL=\$(stat -c '%C' \"\$CP\" 2>/dev/null | head -n 1); echo \"SELABEL=\${LBL:-unavailable}\"; " +
                    "echo \"DOMAIN=\$(cat /proc/self/attr/current 2>/dev/null || echo unknown)\"; " +
                    "echo \"ENFORCE=\$(getenforce 2>/dev/null || echo unknown)\"; " +
                    "echo \"YAMA=\$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo unavailable)\"; " +
                    "echo \"ELFM=\$(head -c 20 \"\$CP\" 2>/dev/null | od -An -tx1 -v | tr -d ' \\n')\"; " +
                    "DEV=\$(df -P \"\$CP\" 2>/dev/null | tail -n 1 | awk '{print \$1}'); echo \"DEVICE=\$DEV\"; " +
                    "echo MOUNTS_BEGIN; if [ -n \"\$DEV\" ] && [ \"\$DEV\" != '-' ]; then grep -F \"\$DEV\" /proc/mounts 2>/dev/null | head -n 4; " +
                    "else grep -E ' (/data|/data/local/tmp) ' /proc/mounts 2>/dev/null | head -n 4; fi; echo MOUNTS_END; " +
                    "echo TMPDIRLS_BEGIN; ls -la '$BASE/tmp' 2>/dev/null | head -n 8; echo TMPDIRLS_END; " +
                    "\"\$CP\" --version 2>'$BASE/tmp/dexec.err'; echo \"DIRECT_RC=\$?\"; " +
                    "echo DIRECT_ERR_BEGIN; cat '$BASE/tmp/dexec.err' 2>/dev/null; echo DIRECT_ERR_END",
                30_000)
            fun dTag(k: String) = Regex("$k=(.*)").find(diag.stdout)?.groupValues?.get(1)?.trim()
            val canonDiag = dTag("CANON") ?: "(unavailable)"
            val directRc = dTag("DIRECT_RC")?.toIntOrNull() ?: -1
            val directErr = Regex("DIRECT_ERR_BEGIN\\n?([\\s\\S]*?)\\n?DIRECT_ERR_END")
                .find(diag.stdout)?.groupValues?.get(1)?.trim().orEmpty()
            val mountEv = Regex("MOUNTS_BEGIN\\n?([\\s\\S]*?)\\n?MOUNTS_END")
                .find(diag.stdout)?.groupValues?.get(1)?.trim().orEmpty()
            val tmpDirLs = Regex("TMPDIRLS_BEGIN\\n?([\\s\\S]*?)\\n?TMPDIRLS_END")
                .find(diag.stdout)?.groupValues?.get(1)?.trim().orEmpty()
            val elfHex = dTag("ELFM").orEmpty()
            val elfMachine = when {
                elfHex.length >= 40 && elfHex.substring(36, 40) == "b700" -> "aarch64"
                elfHex.length >= 40 && elfHex.substring(36, 40) == "3e00" -> "x86_64"
                elfHex.isBlank() -> "unknown"
                else -> "unrecognized"
            }
            val devAbi = archTag()
            val abiMismatch = (devAbi == "arm64" && elfMachine == "x86_64") ||
                (devAbi == "amd64" && elfMachine == "aarch64")
            val diagSummary = "canonical=$canonDiag | stat=${dTag("STAT") ?: "?"} | " +
                "selabel=${dTag("SELABEL") ?: "unavailable"} | domain=${dTag("DOMAIN") ?: "?"} | " +
                "enforce=${dTag("ENFORCE") ?: "?"} | yama=${dTag("YAMA") ?: "?"} | " +
                "elf=$elfMachine | device=${dTag("DEVICE") ?: "?"} | " +
                "DIRECT-EXEC-OUTSIDE-PROOT rc=$directRc" +
                (if (directErr.isNotBlank()) " stderr=${directErr.take(160)}" else " (no stderr)")
            step("exec-runtime diagnostics: $diagSummary")
            if (mountEv.isNotBlank()) step("mount options of the containing fs: ${mountEv.take(400)}")
            if (tmpDirLs.isNotBlank()) step("PROOT_TMP_DIR listing (stale loader files?): ${tmpDirLs.take(400)}")
            if (abiMismatch) step("BINARY COMPATIBILITY ALERT: the file is $elfMachine but this device reports $devAbi")

            // 2c-9) THE SMOKE RUN — a DEBIAN USERSPACE SMOKE TEST (user req #4);
            // proot execs the applets DIRECTLY (no shell: the bundled toybox has
            // no sh applet). Two INDEPENDENT checks, each with its OWN rc marker
            // and its OWN output assertion, verified SEPARATELY:
            //   CHECK 1: `toybox --help` inside the rootfs — exec + loader +
            //            chroot proof, identity-free (rc=0 + "usage: toybox")
            //   CHECK 2: `/bin/cat /etc/os-release` through the merged-/usr
            //            chain — rc=0 + the exact written content must return
            // r1391 WRAPPER FIX (req #7): the payload's LAST command IS the
            // smoke verdict — `[ $RC1 -eq 0 ] && [ $RC2 -eq 0 ]` — so the
            // wrapper's reported rc is the smoke test's OWN return code, never
            // the final echo's 0. The SMOKE_RC markers stay the authoritative
            // per-check evidence and are cross-checked against the wrapper rc.
            // r1391 ISOLATION MATRIX (reqs #3/#4): if the baseline config fails,
            // the SAME smoke test is re-run under controlled single-variable
            // variants BEFORE declaring failure and BEFORE any Debian download:
            //   NO_SECCOMP          — proot's seccomp filter disabled
            //                         (PROOT_NO_SECCOMP=1; known device-specific
            //                         execve interaction in termux proot)
            //   FRESH_TMP           — a clean PROOT_TMP_DIR (a stale loader file
            //                         in the shared tmp dir cannot poison it)
            //   PROOT_DISTRO_BINDS  — -b /dev -b /proc -b /sys, exactly what
            //                         proot-distro passes on every run
            //   NO_FAKEROOT         — the -0 fakeroot mapping removed
            //   HOST_PATH           — the HOST path exec'd directly (guest-path
            //                         translation bypassed for the initial exec)
            //   NO_WORKDIR          — no -w /root (cwd translation removed)
            // A variant that passes BOTH checks is recorded (prefs "prootFix")
            // and applied to probe() and the Linux sessions, so the Debian stage
            // runs under the EXACT configuration the smoke test proved.
            fun smokePayload(mode: String): String {
                val tmp = if (mode == "FRESH_TMP") "$BASE/tmp2" else "$BASE/tmp"
                val seccomp = if (mode == "NO_SECCOMP") "PROOT_NO_SECCOMP=1 " else ""
                val proot = buildString {
                    append("$BASE/bin/proot --kill-on-exit")
                    if (mode != "NO_FAKEROOT") append(" -0")
                    if (mode == "PROOT_DISTRO_BINDS") append(" -b /dev -b /proc -b /sys")
                    append(" -r '$MINI'")
                    if (mode != "NO_WORKDIR") append(" -w /root")
                }.toString()
                val exe = if (mode == "HOST_PATH") "'$MINI/usr/bin/toybox'" else "/usr/bin/toybox"
                val pre = if (mode == "FRESH_TMP") "mkdir -p '$tmp'; " else ""
                val p1 = "${pre}PROOT_TMP_DIR='$tmp' LD_LIBRARY_PATH=$BASE/lib PROOT_LOADER=$BASE/libexec/proot/loader $seccomp$proot $exe --help"
                val p2 = "PROOT_TMP_DIR='$tmp' LD_LIBRARY_PATH=$BASE/lib PROOT_LOADER=$BASE/libexec/proot/loader $seccomp$proot /bin/cat /etc/os-release"
                return "$p1; SMOKE_RC1=\$?; echo \"SMOKE_RC1=\$SMOKE_RC1\"; " +
                    "$p2; SMOKE_RC2=\$?; echo \"SMOKE_RC2=\$SMOKE_RC2\"; " +
                    "[ \"\$SMOKE_RC1\" -eq 0 ] && [ \"\$SMOKE_RC2\" -eq 0 ]"
            }
            fun parseSmoke(inv: Inv): Triple<Int, Int, Boolean> {
                val r1 = Regex("SMOKE_RC1=(\\d+)").find(inv.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val r2 = Regex("SMOKE_RC2=(\\d+)").find(inv.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                return Triple(r1, r2, r1 >= 0 && r2 >= 0)
            }
            fun contentOk(inv: Inv, r1: Int, r2: Int): Boolean {
                val helpOut = inv.stdout.substringBefore("SMOKE_RC1=").trim()
                val catOut = inv.stdout.substringAfter("SMOKE_RC1=").substringBefore("SMOKE_RC2=").trim()
                return r1 == 0 && r2 == 0 && helpOut.contains("usage: toybox") &&
                    catOut.contains("AMINO userspace smoke rootfs")
            }
            setState(ctx, State.CHECKING, "PRoot userspace smoke test (Debian is NOT downloaded until this passes)")
            val smokeInv = runStep("PRoot smoke run (baseline config: toybox --help + cat os-release)",
                smokePayload("BASELINE"), 90_000, "$BASE/tmp/smoke.err")
            val (rc1, rc2, markersOk) = parseSmoke(smokeInv)
            val baselineOk = contentOk(smokeInv, rc1, rc2)
            var winner: String? = if (baselineOk) "BASELINE" else null
            val matrix = ArrayList<Triple<String, Int, Int>>()
            var originSummary = ""
            var originDetail = ""
            if (winner == null) {
                step("smoke FAILED on the baseline config (rc1=$rc1 rc2=$rc2, wrapper rc=${smokeInv.rc}) — running the single-variable isolation matrix; nothing is downloaded and nothing is modified")
                val variants = listOf(
                    "NO_SECCOMP" to "PROOT_NO_SECCOMP=1 (proot's seccomp execve handling off)",
                    "FRESH_TMP" to "clean PROOT_TMP_DIR (rules out a stale proot loader file)",
                    "PROOT_DISTRO_BINDS" to "-b /dev -b /proc -b /sys (the exact proot-distro flags)",
                    "NO_FAKEROOT" to "no -0 (the fakeroot mapping removed)",
                    "HOST_PATH" to "the HOST path exec'd directly (guest-path translation bypassed)",
                    "NO_WORKDIR" to "no -w /root (the cwd translation removed)")
                for ((mode, desc) in variants) {
                    val inv = runStep("PRoot smoke variant $mode — $desc", smokePayload(mode), 90_000, "$BASE/tmp/smoke.err")
                    val (r1, r2, _) = parseSmoke(inv)
                    matrix.add(Triple(mode, r1, r2))
                    Log.i(TAG, "smoke variant $mode: rc1=$r1 rc2=$r2 stderr=${inv.stderr.take(200)}")
                    if (contentOk(inv, r1, r2)) { winner = mode; break }
                }
                // ---- 2c-9d) EXEC-ORIGIN ISOLATION MATRIX (r1392, round-7
                // reqs 1-4) — runs ONLY when the baseline AND every
                // single-variable variant failed. Four questions the previous
                // matrix could not answer, all READ-ONLY (binds only, no
                // copy, no chmod, no SELinux change, rootfs untouched):
                //   T0  the failing baseline re-run as the CONTROL (if it
                //       passes here, the earlier all-variant failure was not
                //       reproducible in this run).
                //   T1  can PRoot start ANY command inside the rootfs? The
                //       ANDROID SYSTEM SHELL bound at its own path
                //       (-b /system), builtin-only command — no guest binary
                //       involved (req #1).
                //   T2  does the SAME toybox file exec at a DIFFERENT guest
                //       path? The known-good file is BIND-mounted (no copy,
                //       no change) to /host-toybox and invoked there (req #2).
                //   T3  can PRoot exec AT ALL on this device? (3A) the same
                //       file by its HOST path with NO -r (identity
                //       translation); (3C) the host path again but WITH -r
                //       and -b /data so the host path is visible inside the
                //       guest ("the required bind mapping if needed");
                //       (3B) the system shell with NO rootfs at all (req #3
                //       — host paths are NOT assumed visible in the guest;
                //       each form makes them visible explicitly).
                //   T4  the documented trace option PROOT_VERBOSE=2 (present
                //       in this build's strings, parsed from the env var in
                //       cli.c) around the failing baseline — the exec
                //       translation evidence around the failing execve, not
                //       a guess (req #4). Full trace copy kept at
                //       $BASE/tmp/execve_trace.txt for adb pull.
                //   T5  LOADER-SLOT PROBE (upstream evidence, reproducible):
                //       this build was compiled with PROOT_UNBUNDLE_LOADER=
                //       "/data/data/com.termux/files/usr/libexec/proot" —
                //       upstream cli.c prints the GUEST path in the error
                //       while enter.c rewrote the execve to the LOADER path
                //       ("Execute the loader instead of the program"), and
                //       get_loader_path() = PROOT_LOADER env ?: that
                //       compiled-in Termux path. Pointing PROOT_LOADER at a
                //       runnable host binary (/system/bin/id) therefore
                //       replaces the failing path-walk with a known-good
                //       exec. r1393 empirical reading (proven locally,
                //       scripts/verify_loader_mechanism.sh): id in the slot
                //       yields rc=255 with NO output — proot masks the
                //       loader's syscalls (enter.c ignore_loader_syscalls)
                //       and a non-loader binary dies in the protocol stage;
                //       rc=255 therefore means PROOT_LOADER was consumed AND
                //       the exec denial at the slot is GONE (an unreachable
                //       loader still gives rc=1 "Permission denied"), while
                //       rc=0 + output would mean the slot ran a real loader.
                //       (req #6)
                //   T6  the compiled-in loader path probed read-only
                //       (ls -ld): ENOENT (no Termux) or EACCES (Termux's
                //       0700 app data, invisible to the shell UID) — either
                //       matches the observed denial class.
                val originPayload =
                    "P=$BASE/bin/proot; MINI=$MINI; T=$BASE/tmp\n" +
                        "export PROOT_TMP_DIR=$BASE/tmp LD_LIBRARY_PATH=$BASE/lib PROOT_LOADER=$BASE/libexec/proot/loader PROOT_LOADER_32=$BASE/libexec/proot/loader32\n" +
                        "sec() { if [ -s \$1 ]; then echo \$2_BEGIN; head -c 300 \$1; echo; echo \$2_END; else echo \$2_EMPTY; fi; }\n" +
                        "\$P --kill-on-exit -0 -r \$MINI -w /root /usr/bin/toybox --help >\$T/o0 2>\$T/e0; T0_RC=\$?; echo T0_RC=\$T0_RC\n" +
                        "\$P --kill-on-exit -0 -r \$MINI -b /system /system/bin/sh -c 'echo PROOT_SHELL_OK' >\$T/o1 2>\$T/e1; T1_RC=\$?; echo T1_RC=\$T1_RC\n" +
                        "\$P --kill-on-exit -0 -r \$MINI -b \$MINI/usr/bin/toybox:/host-toybox /host-toybox --help >\$T/o2 2>\$T/e2; T2_RC=\$?; echo T2_RC=\$T2_RC\n" +
                        "\$P --kill-on-exit -0 \$MINI/usr/bin/toybox --help >\$T/o3a 2>\$T/e3a; T3A_RC=\$?; echo T3A_RC=\$T3A_RC\n" +
                        "\$P --kill-on-exit -0 -r \$MINI -b /data \$MINI/usr/bin/toybox --help >\$T/o3c 2>\$T/e3c; T3C_RC=\$?; echo T3C_RC=\$T3C_RC\n" +
                        "\$P --kill-on-exit -0 /system/bin/sh -c 'echo PROOT_SHELL_HOST_OK' >\$T/o3b 2>\$T/e3b; T3B_RC=\$?; echo T3B_RC=\$T3B_RC\n" +
                        "PROOT_VERBOSE=2 \$P --kill-on-exit -0 -r \$MINI -w /root /usr/bin/toybox --help >\$T/o4 2>\$T/e4; T4_RC=\$?; echo T4_RC=\$T4_RC\n" +
                        "PROOT_LOADER=/system/bin/id \$P --kill-on-exit -0 -r \$MINI /usr/bin/toybox --help >\$T/o5 2>\$T/e5; T5_RC=\$?; echo T5_RC=\$T5_RC\n" +
                        "echo T6_BEGIN; ls -ld /data/data/com.termux 2>&1; ls -ld /data/data/com.termux/files/usr/libexec/proot 2>&1; ls -ld /data/data/com.termux/files/usr/libexec/proot/loader 2>&1; echo T6_END\n" +
                        "cp \$T/e4 \$T/execve_trace.txt 2>/dev/null\n" +
                        "echo LOADER_ENV=\${PROOT_LOADER:-unset}\n" +
                        "sec \$T/o0 O0; sec \$T/e0 E0; sec \$T/o1 O1; sec \$T/e1 E1; sec \$T/o2 O2; sec \$T/e2 E2; " +
                        "sec \$T/o3a O3A; sec \$T/e3a E3A; sec \$T/o3c O3C; sec \$T/e3c E3C; sec \$T/o3b O3B; sec \$T/e3b E3B; sec \$T/o4 O4; sec \$T/o5 O5; sec \$T/e5 E5\n" +
                        "echo EXECVE_LINES_BEGIN; grep -i execve \$T/e4 2>/dev/null | head -n 8; echo EXECVE_LINES_END\n" +
                        "echo TRACE_BEGIN; head -c 2400 \$T/e4 2>/dev/null; echo; echo TRACE_END\n" +
                        "echo MATRIX_BEGIN\n" +
                        "echo T0_baseline_rootfs_toybox_help_RC=\$T0_RC\n" +
                        "echo T1_rootfs_bind_system_exec_system_sh_echo_RC=\$T1_RC\n" +
                        "echo T2_rootfs_bind_toybox_to_host_toybox_exec_help_RC=\$T2_RC\n" +
                        "echo T3A_no_rootfs_exec_host_toybox_path_RC=\$T3A_RC\n" +
                        "echo T3C_rootfs_bind_data_exec_host_toybox_path_RC=\$T3C_RC\n" +
                        "echo T3B_no_rootfs_exec_system_sh_echo_RC=\$T3B_RC\n" +
                        "echo T4_baseline_PROOT_VERBOSE_RC=\$T4_RC\n" +
                        "echo T5_loader_slot_id_rc=\$T5_RC\n" +
                        "echo MATRIX_END"
                val originInv = runStep(
                    "exec-origin isolation matrix (read-only): can PRoot exec anything at all / the same file at other paths / PROOT_VERBOSE trace",
                    originPayload, 150_000, "$BASE/tmp/origin.err")
                fun oRc(n: String) =
                    Regex("$n=(\\d+)").find(originInv.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                fun section(tag: String): String? {
                    val m = Regex(tag + "_BEGIN\\n?([\\s\\S]*?)\\n?" + tag + "_END").find(originInv.stdout)
                    if (m != null) return m.groupValues[1].trim()
                    return if (Regex(tag + "_EMPTY").containsMatchIn(originInv.stdout)) "" else null
                }
                val t0o = oRc("T0_RC"); val t1o = oRc("T1_RC"); val t2o = oRc("T2_RC")
                val t3ao = oRc("T3A_RC"); val t3co = oRc("T3C_RC"); val t3bo = oRc("T3B_RC")
                val t4o = oRc("T4_RC"); val t5o = oRc("T5_RC")
                val t1Ok = t1o == 0 && section("O1")?.contains("PROOT_SHELL_OK") == true
                val t2Ok = t2o == 0 && section("O2")?.contains("usage: toybox") == true
                val t3aOk = t3ao == 0 && section("O3A")?.contains("usage: toybox") == true
                val t3cOk = t3co == 0 && section("O3C")?.contains("usage: toybox") == true
                val t3bOk = t3bo == 0 && section("O3B")?.contains("PROOT_SHELL_HOST_OK") == true
                val t5Ok = t5o == 0 && !(section("O5").isNullOrEmpty())
                val loaderEnv = Regex("LOADER_ENV=(.*)").find(originInv.stdout)?.groupValues?.get(1)?.trim() ?: "unknown"
                val loaderPathProbe = section("T6").orEmpty().replace("\n", " | ").ifBlank { "(unavailable)" }
                val execveLines = section("EXECVE_LINES").orEmpty()
                val traceExcerpt = section("TRACE").orEmpty()
                    .ifBlank { "(trace unavailable — this build printed nothing under PROOT_VERBOSE)" }
                val anyOriginPass = t1Ok || t2Ok || t3aOk || t3bOk || t3cOk
                val originVerdict = when {
                    t0o == 0 -> "T0 (the failing baseline, re-run as the control) PASSED this time — the all-variant failure was NOT reproducible in this run; treat the matrix below as transient-failure evidence"
                    t5Ok -> "CONFIRMED by a reproducible test (req #6): with PROOT_LOADER=/system/bin/id the SAME rootfs exec succeeds through proot's loader slot (id's output was produced INSIDE the exec slot, rc=0) — the 'Permission denied' was the kernel rejecting proot's compiled-in loader path /data/data/com.termux/files/usr/libexec/proot/loader (upstream enter.c rewrites EVERY guest execve to that path, 'Execute the loader instead of the program', while cli.c prints the guest path in the error). NOT the toybox file, NOT the rootfs, NOT Android ptrace/SELinux/seccomp policy. The fix shipped in r1393: the loader binary from the SAME Termux package + PROOT_LOADER on every invocation (already active in this run — see LOADER_ENV)"
                    !anyOriginPass -> "PRoot cannot exec ANY command on this device even with AMINO's own shipped loader active (LOADER_ENV below now lists $BASE/libexec/proot/loader, NOT the unreachable Termux path) — the system shell with no rootfs (T3B), the same file by its host path with identity translation (T3A), inside the rootfs (T1), and at a fresh bound guest path (T2) ALL fail: the denial is in PRoot's exec/ptrace path itself, NOT in the rootfs config, NOT the guest-path form, NOT the file, and — unlike the r1392 run — NOT the loader path"
                    (t3aOk || t3bOk || t3cOk) && !t1Ok && !t2Ok -> "PRoot execs OUTSIDE the rootfs context but EVERY in-rootfs exec fails (the system shell inside the rootfs, the file at its original path, and at a fresh bound path): the -r rootfs invocation context is what breaks exec on this device"
                    t1Ok && !t2Ok -> "PRoot starts commands inside the rootfs (the system shell ran) but the toybox file fails at BOTH the original and a fresh guest path — a file-specific interaction in PRoot's exec path"
                    t2Ok -> "the SAME file execs through PRoot at a DIFFERENT guest path while /usr/bin/toybox fails — the guest-path translation of the original location is implicated"
                    else -> "mixed outcome — read the per-test sections below"
                }
                originSummary = "T0=$t0o T1=$t1o T2=$t2o T3A=$t3ao T3B=$t3bo T3C=$t3co T4=$t4o T5=$t5o (rc; PASS = rc 0 + expected output) — $originVerdict"
                originDetail = buildString {
                    append("• exec-origin matrix (r1392, read-only; rc verified against expected output):\n")
                    append("   - T0 baseline control (expected FAIL): rc=$t0o${if (t0o == 0) " — PASSED UNEXPECTEDLY" else ""}\n")
                    append("   - T1 rootfs + -b /system, /system/bin/sh -c 'echo PROOT_SHELL_OK': rc=$t1o → ${if (t1Ok) "PASS" else "FAIL"}\n")
                    append("   - T2 rootfs, bind toybox:/host-toybox, /host-toybox --help: rc=$t2o → ${if (t2Ok) "PASS" else "FAIL"}\n")
                    append("   - T3A NO rootfs, exec host toybox path: rc=$t3ao → ${if (t3aOk) "PASS" else "FAIL"}\n")
                    append("   - T3C rootfs + -b /data, exec host toybox path: rc=$t3co → ${if (t3cOk) "PASS" else "FAIL"}\n")
                    append("   - T3B NO rootfs, /system/bin/sh -c echo: rc=$t3bo → ${if (t3bOk) "PASS" else "FAIL"}\n")
                    append("   - T4 baseline + PROOT_VERBOSE=2 trace: rc=$t4o\n")
                    append("   - T5 loader-slot probe, PROOT_LOADER=/system/bin/id (upstream: the failing execve is the LOADER path, not the guest path; ANY output from the slot proves a real exec succeeded): rc=$t5o → ${if (t5Ok) "PASS — CONFIRMED" else "FAIL"}${if (t5Ok) " · slot printed: ${section("O5").orEmpty().take(80)}" else ""}\n")
                    append("→ verdict: $originVerdict\n")
                    append("• loader provenance: PROOT_LOADER=$loaderEnv; the compiled-in default /data/data/com.termux/files/usr/libexec/proot/loader (PROOT_UNBUNDLE_LOADER, termux-packages build.sh v5.1.107.95) is overridden by AMINO since r1393: the loader binary from the SAME Termux proot package is shipped at $BASE/libexec/proot/loader and PROOT_LOADER (+ PROOT_LOADER_32) is set on every invocation — before r1393 no loader was shipped and every guest execve was rewritten to the unreachable Termux path; upstream enter.c rewrites EVERY guest execve to the loader (\"Execute the loader instead of the program\")\n")
                    append("• T6 compiled-in loader path probed (read-only): $loaderPathProbe\n")
                    if (execveLines.isNotBlank()) append("• execve lines from the trace: ${execveLines.take(400)}\n")
                    append("• PROOT_VERBOSE=2 trace excerpt (full copy at $BASE/tmp/execve_trace.txt):\n")
                    for (ln in traceExcerpt.lines().take(24)) append("   | ${ln.take(160)}\n")
                }
                Log.i(TAG, "exec-origin matrix: $originSummary")
            }
            if (winner == null) {
                val execDenied = Regex("(?i)permission denied|execve|exec format")
                    .containsMatchIn(smokeInv.stderr + smokeInv.stdout) || rc1 == 126 || rc2 == 126
                val why = buildString {
                    append("PRoot could not exec the toybox binary inside the minimal userspace rootfs — Debian was NOT downloaded (the runtime failure must be isolated first)\n")
                    if (execDenied) append("• classification: EXEC DENIAL — this is NOT a missing /etc/os-release: that file was written AND read back during setup, and /bin/cat resolves to the SAME toybox executable through the merged-/usr chain — every failed check here is the SAME single exec denial\n")
                    append("• CHECK 1 (toybox --help inside the rootfs): rc=$rc1\n")
                    append("• CHECK 2 (cat /etc/os-release through the merged-/usr chain): rc=$rc2\n")
                    append("• stderr: ${(smokeInv.stderr.ifBlank { "(empty)" }).take(400)}\n")
                    if (!markersOk) append("• wrapper: SMOKE_RC markers ABSENT — the payload itself broke before the checks completed (wrapper rc=${smokeInv.rc})\n")
                    else append("• wrapper rc=${smokeInv.rc} — the smoke verdict itself (both checks must exit 0); no longer the final echo's rc (req #7)\n")
                    append("• exec-runtime diagnostics (read-only): $diagSummary\n")
                    if (mountEv.isNotBlank()) append("• mount options: ${mountEv.take(280)}\n")
                    if (abiMismatch) append("• BINARY COMPATIBILITY: the executable is $elfMachine but this device reports $devAbi\n")
                    if (matrix.isNotEmpty()) {
                        append("• isolation matrix (each run's full command/rc/stdout/stderr is in the install log):\n")
                        for ((m, mr1, mr2) in matrix) append("   - $m: CHECK1 rc=$mr1, CHECK2 rc=$mr2\n")
                        append("→ no variant passed: the denial is not explained by proot's seccomp handling, a stale loader in PROOT_TMP_DIR, missing proot-distro binds, the fakeroot mapping, the cwd, or the exec-path form ALONE\n")
                    }
                    if (originDetail.isNotBlank()) append(originDetail)
                    append("→ left unchanged: no chmod, no setenforce, no SELinux/policy change, no remount (req #5)\n")
                    append("• service identity: the aMiNo service runs as $uidLabel; this test never uses or claims root\n")
                    append("• PRESERVED: the pushed binaries, the diagnostic rootfs at $MINI (kept for inspection), and any previously verified rootfs archive in app storage\n")
                    append(smokeInv.render())
                }
                Log.e(TAG, "PROOT SMOKE FAILED (no variant passed): rc1=$rc1 rc2=$rc2 diag=[$diagSummary] origin=[$originSummary]")
                return runtimeFail(ctx, why, "PRoot smoke test")
            }
            if (winner != "BASELINE") {
                meta.put("prootFix", winner)
                save(ctx, meta)
                if (winner == "FRESH_TMP") ShizukuExec.oneShot("mkdir -p '$BASE/tmp2'", 10_000)
                step("smoke ISOLATED: variant $winner passed BOTH checks — recorded and applied to probe() and Linux sessions so Debian runs under the exact proven configuration")
            } else {
                step("smoke PASSED on the baseline config — no invocation change needed")
            }
            step("smoke test PASSED (Debian userspace smoke test, mode $winner): toybox --help rc=0 · cat /etc/os-release rc=0 " +
                "through the merged-/usr chain — PRoot chroot + exec work; service identity: $uidLabel; proceeding to Debian")
            step("diagnostic rootfs KEPT at $MINI (req #6 — evidence preserved; the next install reuses it instead of rebuilding)")

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
            val smokeEvidence = "Debian userspace smoke test passed (mode $winner): toybox --help + cat /etc/os-release ran inside " +
                "the minimal rootfs through PRoot (both rc=0, verified independently); service identity: $uidLabel — no root claims"
            // r1395 — persist the PROVEN stage ladder (user Task 5): each entry
            // is recorded at the moment it was actually verified.
            val stages = JSONArray()
                .put(JSONObject().put("key", "ARCHIVE_VERIFIED").put("ok", true)
                    .put("detail", "${dl.file.name} ${dl.file.length() / (1024 * 1024)} MB · $algo ${localDigest.take(16)}… · source: ${dl.source}"))
                .put(JSONObject().put("key", "ROOTFS_EXTRACTED").put("ok", true)
                    .put("detail", "extracted to $ROOTFS (${ex.detail.take(140)})"))
                .put(JSONObject().put("key", "ROOTFS_VALIDATED").put("ok", true)
                    .put("detail", "post-extract audit: ${audit.second.take(140)}"))
                .put(JSONObject().put("key", "PROOT_RUNTIME_VERIFIED").put("ok", true)
                    .put("detail", "probe through PRoot: ${probe.second.trim().replace("\n", " · ").take(140)}"))
            meta.put("state", State.READY.name)
                .put("message", "ready")
                .put("arch", pf.arch)
                .put("prootVersion", prootVersion)
                .put("extractor", extractorVersion)
                .put("smoke", smokeEvidence)
                .put("stages", stages)
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
                "• runtime: $ROOTFS — Debian userspace under the aMiNo service identity ($uidLabel); " +
                "PRoot may map it to root inside the container; nothing here is real root\n" +
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
     * r1387/r1388/r1390 — failure of a BUNDLED-BINARY runtime phase (proot exec,
     * dependency resolution, smoke rootfs, or the r1388 extractor gates), all of
     * which run BEFORE the Debian rootfs is extracted. The environment is NOT
     * "broken": the user's storage already passed the preflight. State returns
     * to NOT_INSTALLED with the full diagnostic. r1390 (user req #5): the text
     * never claims "nothing was downloaded" — binaries WERE pushed by this
     * point and a previously verified archive may exist; the state line below
     * says exactly what is PRESERVED for the retry.
     */
    private fun runtimeFail(ctx: Context, why: String, stage: String = "PRoot runtime test"): String {
        Log.e(TAG, "RUNTIME FAILED [$stage]: ${why.take(300)}")
        setState(ctx, State.NOT_INSTALLED, "install stopped before Debian: $stage failed", why)
        val archive = File(ctx.filesDir, "linux").listFiles()
            ?.firstOrNull { it.name.startsWith("debian-rootfs.tar.") }
        val kept = if (archive != null)
            "a previously verified rootfs archive (${archive.name}, ${archive.length() / (1024 * 1024)} MB) is PRESERVED in app storage — the next install reuses it without re-downloading"
        else
            "no rootfs archive exists in app storage yet — the next install will download it"
        return "RUNTIME TEST FAILED ($stage) — nothing was extracted and nothing on your storage is broken; " +
            "the EXACT failed check is reported above:\n$why\n• state: $kept"
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
     *
     * r1394 hardlink repair: some devices refuse link() during extraction
     * while allowing symlinks (user device, r1393 install: "can't link
     * 'usr/bin/perl5.36.0' -> 'usr/bin/perl': Permission denied" +
     * "…'usr/bin/uncompress' -> 'usr/bin/gunzip'…" — SELinux 'link' on the
     * FILE class is denied even though file create + symlink creation are
     * allowed). toybox 0.8.11 tar.c (extract_to_disk) CONTINUES with the
     * next entry after a failed link() and only exits 1 at the end ("had
     * errors"), so the tree is complete EXCEPT the hardlink entries. When
     * link errors are the ONLY error class, each pair is repaired by copying
     * the already-extracted target file with the bundled toybox (rm -f first
     * — exactly what tar.c does before its link()). The tree is then NOT
     * cleaned and the flow continues into the full audit + Debian probe,
     * which remain the real success gates. Verified locally against the
     * bundled toybox: scripts/verify_hardlink_repair_1394.py (message
     * format + arg order, tar continuation, repair incl. mode preservation,
     * unrecoverable-pair refusal).
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
            // ---- r1394: hardlink-only failures are repairable ----
            val tarErrFull = run {
                val head = ShizukuExec.oneShot("head -c 40000 '$BASE/tmp/tar.err' 2>/dev/null").output
                if (head.length >= 40000)
                    head + "\n" + ShizukuExec.oneShot("tail -c 4000 '$BASE/tmp/tar.err' 2>/dev/null").output
                else head
            }
            // toybox tar.c: perror_msg("can't link '%s' -> '%s'", name, link_target)
            // — the FIRST path is the entry being created (missing), the SECOND
            // is the already-extracted target file.
            val linkPairs = Regex("can't link '([^']*)' -> '([^']*)': ")
                .findAll(tarErrFull)
                .map { it.groupValues[1] to it.groupValues[2] }
                .filter { (n, t) -> safeTarPath(n) && safeTarPath(t) }
                .toList()
            val otherErrs = tarErrFull.lines().filter {
                it.startsWith("tar:") && !it.contains("can't link '") && !it.trim().endsWith("had errors")
            }
            if (linkPairs.isNotEmpty() && otherErrs.isEmpty() && linkPairs.size <= 500) {
                val rep = repairDeniedHardlinks(targetDir, linkPairs)
                if (rep != null) {
                    Log.w(TAG, "extract rc=$rc repaired: ${rep.take(200)}")
                    return Extract(true, rep)
                }
                Log.e(TAG, "hardlink repair FAILED — falling back to the fatal path")
            } else {
                Log.e(TAG, "extract rc=$rc is NOT a hardlink-only failure (otherErrs=${otherErrs.size}, pairs=${linkPairs.size}) — no repair attempted")
            }
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

    /** A tar-entry path may only reach the repair shell if it is strictly
     *  relative and cannot break out of the single-quoted context. */
    private fun safeTarPath(p: String): Boolean =
        p.isNotEmpty() && !p.startsWith("/") && !p.contains('\'') &&
            !p.contains('\n') && !p.contains('\r') &&
            p.split('/').none { it == ".." }

    /**
     * r1394: repair the hardlink entries this device refused to create.
     * Returns the honest detail line on success, null on ANY failure (the
     * caller then falls back to the fatal path — the tree is cleaned there
     * exactly as before). For every reported pair the destination is removed
     * first (mirroring tar.c's unlink-before-link), then the already-
     * extracted target file is COPIED with the bundled toybox `cp -a` (mode
     * + timestamps preserved; the two files simply stop sharing an inode,
     * which is exactly what this device class demands). Both members are
     * required to exist afterwards, so a repair can never silently pass.
     */
    private suspend fun repairDeniedHardlinks(
        targetDir: String,
        pairs: List<Pair<String, String>>
    ): String? {
        val q = { p: String -> p.replace("'", "'\\''") }
        val cmd = buildString {
            append("cd '").append(q(targetDir)).append("' || { echo REPAIR_RC=9; exit; }; R=0; N=0")
            for ((name, target) in pairs) {
                val n = q(name); val t = q(target)
                append("; rm -f '").append(n).append("' 2>/dev/null")
                append("; if [ -f '").append(t).append("' ]; then '").append(TOYBOX)
                    .append("' cp -a '").append(t).append("' '").append(n).append("' || R=1")
                append("; else '").append(TOYBOX).append("' cp -a '").append(n)
                    .append("' '").append(t).append("' || R=1; fi")
                append("; [ -f '").append(n).append("' ] && [ -f '").append(t)
                    .append("' ] && N=\$((N+1)) || R=1")
            }
            append("; echo REPAIR_RC=\$R N=\$N")
        }
        val s = ShizukuExec.oneShot(cmd, 120_000)
        val m = Regex("REPAIR_RC=(\\d+) N=(\\d+)").find(s.output) ?: return null
        val (rrc, nn) = m.destructured
        if (rrc != "0" || nn.toIntOrNull() != pairs.size) {
            Log.e(TAG, "hardlink repair rc=$rrc n=$nn (expected ${pairs.size}): ${s.output.take(300)}")
            return null
        }
        val listing = pairs.joinToString(", ") { (n, t) -> "$n ← $t" }
        return "toybox 0.8.11 tar rc=1, but the ONLY errors were HARDLINK creations this device refuses " +
            "(link(): Permission denied — symlink creation is allowed; the SELinux 'link' permission on the " +
            "file class is not): toybox tar continues after each failed link, so the tree was complete except " +
            "those entries; repaired ${pairs.size} of them by COPYING the already-extracted target file " +
            "(both members verified present, mode preserved): $listing — the tree was NOT deleted; " +
            "the post-extract audit + Debian probe below remain the real success gates"
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
     * Spawns PRoot once and runs identity + os-release + bash + apt INSIDE the
     * container. r1390 (user req #2): the GATES are Debian's own artifacts —
     * os-release + bash + apt. `id` is recorded as identity evidence ONLY: the
     * Android shell UID (2000) has no entry in the Debian passwd database, so
     * `id` may print a bare numeric uid or an applet error there; that is the
     * normal host identity showing through, NOT an installation failure. The
     * environment itself is Debian USERSPACE under the shell identity — never
     * claimed as root. Returns (verified, evidence).
     */
    suspend fun probe(ctx: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuExec.available()) return@withContext false to "aMiNo service is not running"
        // r1391: use EXACTLY the proot invocation mode the smoke-test variant
        // matrix proved on this device (prefs "prootFix") — the probe must run
        // under the same configuration the Debian sessions will get.
        val fix = prootFix(ctx)
        var env = arrayOf(
            "PROOT_TMP_DIR=${prootTmpFor(fix)}",
            "LD_LIBRARY_PATH=$BASE/lib",
            "PROOT_LOADER=$BASE/libexec/proot/loader",
            "PROOT_LOADER_32=$BASE/libexec/proot/loader32",
            "HOME=/root"
        )
        if (fix == "NO_SECCOMP") env = env + "PROOT_NO_SECCOMP=1"
        val args = buildList {
            add("$BASE/bin/proot"); add("--kill-on-exit"); add("--link2symlink")
            if (fix != "NO_FAKEROOT") add("-0")
            addAll(listOf("-r", ROOTFS, "-b", "/dev", "-b", "/proc", "-b", "/sys"))
            add(if (fix == "HOST_PATH") "$ROOTFS/usr/bin/env" else "/usr/bin/env")
            addAll(listOf(
                "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME=/root",
                "/bin/sh", "-c", "id 2>&1 | head -n 1; head -n 1 /etc/os-release; bash --version 2>/dev/null | head -n 1; apt-get --version 2>/dev/null | head -n 1"
            ))
        }
        val p = try {
            ShizukuExec.spawn(args, env, "/")
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
        // Gates are Debian's OWN artifacts — never the guest's view of the uid
        // (the shell UID may not exist in the Debian passwd database; r1390).
        val ok = text.contains("Debian") &&
            text.contains("bash", ignoreCase = true) && text.contains("apt", ignoreCase = true)
        ok to text
    }

    // ---------- session command builder (used by TerminalSession) ----------

    /**
     * r1391 — the proot invocation mode isolated by the smoke-test variant
     * matrix (BASELINE, NO_SECCOMP, FRESH_TMP, PROOT_DISTRO_BINDS,
     * NO_FAKEROOT, HOST_PATH, NO_WORKDIR). Persisted as prefs "prootFix" when
     * the smoke test finds a mode that passes where the baseline failed.
     */
    private fun prootFix(ctx: Context): String = try {
        load(ctx).optString("prootFix", "BASELINE")
    } catch (e: Exception) { "BASELINE" }

    private fun prootTmpFor(mode: String): String =
        if (mode == "FRESH_TMP") "$BASE/tmp2" else "$BASE/tmp"

    /**
     * r1396 — `guest` selects the guest shell (/bin/bash by default; /bin/sh for the
     * launch fallback) and `wrapShell` wraps the direct argv in `sh -c 'exec …'`.
     * WHY the wrap: EVERY service execution that works on real devices (probe,
     * preflight, install steps — all oneShot) spawns `sh -c …`; the only direct-argv
     * spawn was the persistent session, and it is exactly the launch that hung
     * (r1395 dialog: INIT_TIMEOUT — spawned but never answered) while the same shape
     * answers in <1s in the local reproduction (scripts/repro_session_shape_1396.py:
     * A_exact_bash / B_guest_sh / A2_in_marker / D_sh_wrap all PASS in 0.08s).
     * Aligning the session spawn with the proven-on-device shape removes the last
     * structural difference between the launches that work and the one that hung.
     */
    fun sessionCommand(ctx: Context, guest: String = "/bin/bash", wrapShell: Boolean = true): Pair<List<String>, Array<String>> {
        val fix = prootFix(ctx)
        val binds = ArrayList<String>()
        for (b in listOf("/dev", "/proc", "/sys", "/system", "/vendor"))
            if (java.io.File(b).exists()) { binds.add("-b"); binds.add(b) }
        // r1398 — the shared exchange dir moved OUT of the app-private storage:
        // /data/user/0/<pkg>/… is invisible to the shell-uid service and proot
        // reported "can't sanitize binding … Permission denied" (r1397 device
        // dialog evidence). $BASE/shared lives in the same service-owned tree
        // as proot and the rootfs; the preflight creates it (mkdir -p).
        val shared = java.io.File("$BASE/shared")
        binds.add("-b"); binds.add("${shared.absolutePath}:/shared")
        val baseCmd = buildList {
            add("$BASE/bin/proot"); add("--kill-on-exit"); add("--link2symlink")
            if (fix != "NO_FAKEROOT") add("-0")
            add("-r"); add(ROOTFS)
            if (fix != "NO_WORKDIR") { add("-w"); add("/root") }
            addAll(binds)
            add(if (fix == "HOST_PATH") "$ROOTFS/usr/bin/env" else "/usr/bin/env")
            addAll(listOf(
                "-i",
                "HOME=/root", "USER=root", "SHELL=$guest",
                "TERM=xterm-256color", "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR=/tmp",
                guest
            ))
        }
        var env = arrayOf(
            "PROOT_TMP_DIR=${prootTmpFor(fix)}",
            "LD_LIBRARY_PATH=$BASE/lib",
            "PROOT_LOADER=$BASE/libexec/proot/loader",
            "PROOT_LOADER_32=$BASE/libexec/proot/loader32",
            "HOME=/root"
        )
        if (fix == "NO_SECCOMP") env = env + "PROOT_NO_SECCOMP=1"
        val cmd = if (wrapShell) {
            val q = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
            listOf("sh", "-c", "exec " + baseCmd.joinToString(" ") { q(it) })
        } else baseCmd
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
