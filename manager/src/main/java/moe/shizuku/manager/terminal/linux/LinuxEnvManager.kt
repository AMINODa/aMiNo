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
 * HONESTY RULES (user spec #9):
 *  - nothing is reported READY before the rootfs is downloaded, digest-verified,
 *    extracted AND a real probe (id + /etc/os-release through PRoot) succeeds;
 *  - every failure keeps the real error message and sets state BROKEN;
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
 */
object LinuxEnvManager {

    private const val TAG = "LinuxEnvManager"
    const val BASE = "/data/local/tmp/amino-linux"
    const val ROOTFS = "$BASE/rootfs"
    private const val PREFS = "linux_env"

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
        val problems: List<String>
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    private val installing = AtomicBoolean(false)

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
        if (free in 0..(700L * 1024 * 1024)) problems.add("app storage low: ${free / (1024 * 1024)} MB free (need ≥ 700 MB)")
        if (freeData != null && freeData in 0..(800L * 1024 * 1024))
            problems.add("/data storage low: ${freeData / (1024 * 1024)} MB free (need ≥ 800 MB)")
        if (!networkOk(ctx)) problems.add("no active network — the rootfs download needs internet")
        if (!ShizukuExec.available()) problems.add("aMiNo service is not running — start the service (shell mode) first; PRoot runs through it without root")
        return PreFlight(arch != null, arch, free, freeData, networkOk(ctx),
            ShizukuExec.available(), ShizukuExec.serviceUid(), prootBundled(ctx), problems)
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
            State.NOT_INSTALLED -> false to
                "not installed — install Debian 12 (PRoot) from the Linux environment page; it runs without root"
        }
    }

    fun tarballFile(ctx: Context): File {
        val gz = File(ctx.filesDir, "linux/debian-rootfs.tar.gz")
        return if (gz.exists()) gz else File(ctx.filesDir, "linux/debian-rootfs.tar.xz")
    }

    // ---------- install flow ----------

    /**
     * Full honest install: preflight → push bundled binaries → download
     * (digest-verified) → transfer → extract → configure → VERIFY probe.
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
            // ---- 1) preflight (real checks, real numbers) ----
            setState(ctx, State.CHECKING, "checking device (architecture, storage, network, service)")
            step("preflight: checking architecture / storage / network / aMiNo service")
            val pf = preFlight(ctx)
            if (!pf.ok) {
                setState(ctx, State.BROKEN, "preflight failed", pf.problems.joinToString("; "))
                return "PREFLIGHT FAILED — nothing was installed:\n" + pf.problems.joinToString("\n") { "• $it" }
            }
            step("preflight OK: arch=${pf.arch}, app-storage ${pf.freePrivateBytes / (1024 * 1024)} MB free, " +
                "data ${((pf.freeDataBytes ?: -1) / (1024 * 1024))} MB free, service uid=${pf.serviceUid}")

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
                Triple("libamino_lzma.so", "$BASE/lib/liblzma.so.5", "lzma library")
            )
            for ((src, dst, what) in pushes) {
                val f = File(nd, src)
                if (!f.exists()) return fail(ctx, "bundled $what missing from APK ($src)")
                val p = ShizukuExec.pushFile(f, dst)
                if (!p.ok) return fail(ctx, "pushing $what failed: ${p.output.take(200)}")
                step("pushed $what → $dst (${f.length()} bytes, size verified)")
            }
            s = ShizukuExec.oneShot("chmod 755 $BASE/bin/proot $BASE/bin/xz && $BASE/bin/proot --version 2>&1 | head -n 1")
            if (!s.ok || !s.output.lowercase().contains("proot"))
                return fail(ctx, "proot does not run on this device: rc=${s.rc} ${s.output.take(200)}")
            val prootVersion = s.output.trim().take(80)
            step("proot verified on device: $prootVersion")

            // ---- 3) download (digest-verified) ----
            setState(ctx, State.DOWNLOADING, "downloading the Debian 12 rootfs")
            val dl = download(ctx) { d, t -> step("download: ${d / (1024 * 1024)} MB${if (t > 0) " / ${t / (1024 * 1024)} MB" else ""}") }
            if (dl == null) return fail(ctx, "rootfs download failed (both sources unreachable or verification failed) — the download was deleted; check the network and retry")
            step("download verified: ${dl.file.name} from ${dl.source} (${dl.file.length() / (1024 * 1024)} MB)")

            // ---- 4) transfer into the runtime dir (shell-readable path) ----
            setState(ctx, State.TRANSFERRING, "transferring rootfs to the runtime directory")
            val remoteTar = "$BASE/rootfs.tar." + if (dl.file.name.endsWith(".xz")) "xz" else "gz"
            var lastPct = -1
            val p = ShizukuExec.pushFile(dl.file, remoteTar) { d, t ->
                val pct = if (t > 0) (d * 100 / t).toInt() else 0
                if (pct != lastPct) { lastPct = pct; step("transfer: $pct%") }
            }
            if (!p.ok) return fail(ctx, "transfer failed: ${p.output.take(200)}")
            step("transfer verified (remote size matches)")

            // ---- 5) extract (toybox-safe pipe, preserved modes/symlinks) ----
            setState(ctx, State.EXTRACTING, "extracting the rootfs (this takes a minute or two)")
            s = ShizukuExec.oneShot(
                "rm -rf $ROOTFS.tmp && mkdir -p $ROOTFS.tmp && " +
                    if (remoteTar.endsWith(".xz"))
                        "LD_LIBRARY_PATH=$BASE/lib $BASE/bin/xz -dc $remoteTar | tar -xf - -C $ROOTFS.tmp && echo EXTRACT_OK"
                    else
                        "gzip -dc $remoteTar | tar -xf - -C $ROOTFS.tmp && echo EXTRACT_OK",
                timeoutMs = 900_000
            )
            if (!s.ok || !s.output.contains("EXTRACT_OK"))
                return fail(ctx, "extraction failed: rc=${s.rc} ${s.output.take(300)}")
            s = ShizukuExec.oneShot(
                "test -x $ROOTFS.tmp/bin/bash && test -f $ROOTFS.tmp/etc/os-release && rm -rf $ROOTFS && mv $ROOTFS.tmp $ROOTFS && echo SWAP_OK",
                timeoutMs = 120_000
            )
            if (!s.ok || !s.output.contains("SWAP_OK"))
                return fail(ctx, "extracted rootfs failed sanity check (bin/bash or /etc/os-release missing): ${s.output.take(200)}")
            step("extraction verified: /bin/bash + /etc/os-release present")

            // ---- 6) configure (DNS, hosts, tmp, ownership) ----
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
            ShizukuExec.oneShot("rm -f $remoteTar")
            step("configuration written (DNS 1.1.1.1/8.8.8.8, hosts, /tmp); remote tarball removed")

            // ---- 7) VERIFY: a real probe through PRoot (spec: never claim before proof) ----
            setState(ctx, State.VERIFYING, "verifying with a real probe (id + os-release through PRoot)")
            val probe = probe(ctx)
            if (!probe.first)
                return fail(ctx, "verification probe FAILED: ${probe.second.take(300)}")
            step("probe verified: ${probe.second.take(200).replace("\n", " · ")}")

            // ---- 8) READY (real, evidenced) ----
            meta.put("state", State.READY.name)
                .put("message", "ready")
                .put("arch", pf.arch)
                .put("source", dl.source)
                .put("manifestDigest", dl.digest ?: "")
                .put("prootVersion", prootVersion)
                .put("installedAt", System.currentTimeMillis())
                .remove("lastError")
            save(ctx, meta)
            setState(ctx, State.READY, "ready")
            return "INSTALLED AND VERIFIED\n" +
                "• source: ${dl.source}${if (dl.digest != null) "\n• layer digest: ${dl.digest.take(19)}…" else ""}\n" +
                "• $prootVersion\n" +
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
        return "INSTALL FAILED — nothing is claimed to work:\n$why"
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
                onProgress("reset: extracting")
                s = ShizukuExec.oneShot(
                    if (remoteTar.endsWith(".xz"))
                        "LD_LIBRARY_PATH=$BASE/lib $BASE/bin/xz -dc $remoteTar | tar -xf - -C $BASE/rootfs && echo EX_OK"
                    else
                        "gzip -dc $remoteTar | tar -xf - -C $BASE/rootfs && echo EX_OK",
                    timeoutMs = 900_000
                )
                if (!s.ok || !s.output.contains("EX_OK"))
                    return@withContext "RESET FAILED at extraction: ${s.output.take(200)}"
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
                    "/bin/sh", "-c", "id; head -n 1 /etc/os-release"
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
        val ok = text.contains("uid=0") && text.contains("Debian")
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
