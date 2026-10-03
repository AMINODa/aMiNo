package moe.shizuku.manager.terminal.linux

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

/**
 * aMiNo r1385 — execution layer for the Linux userspace environment.
 *
 * WHY Shizuku: the app targets SDK 36, so Android 10+ W^X policy forbids exec()
 * of files stored in the app's private storage. The Shizuku service (the same
 * local service aMiNo itself manages) runs as the ADB `shell` identity (uid 2000,
 * NO root), and the shell domain may execute binaries in /data/local/tmp.
 * Therefore PRoot + the Debian rootfs live under /data/local/tmp/amino-linux and
 * are executed BY the service — never by the app process itself.
 *
 * ROOT RULE: when the service runs in root mode we DO NOT use its privileges —
 * every spawn/one-shot is wrapped through `su 2000 -c …` to DROP to the shell
 * identity first, so the Linux environment never runs with real root power.
 * (PRoot's `-0` only FAKES uid 0 inside the container — it grants nothing real.)
 */
object ShizukuExec {

    private const val TAG = "ShizukuExec"

    class Shot(val rc: Int, val output: String) {
        val ok: Boolean get() = rc == 0
    }

    /** A live remote process with real streams (persistent session backend). */
    class RemoteProc(
        private val remote: IRemoteProcess,
        val stdin: OutputStream,
        val stdout: InputStream,
        val stderr: InputStream
    ) {
        fun waitFor(): Int = remote.waitFor()
        fun alive(): Boolean = try { remote.alive() } catch (e: Exception) { false }
        fun destroy() { try { remote.destroy() } catch (_: Exception) {} }
    }

    fun available(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** uid of the service (2000 = shell — the desired mode; 0 = root mode, we drop). */
    fun serviceUid(): Int = try { Shizuku.getUid() } catch (e: Throwable) { -1 }

    private fun svc(): IShizukuService {
        val b = Shizuku.getBinder() ?: throw IllegalStateException(
            "Shizuku service binder not received — start the aMiNo service first"
        )
        return IShizukuService.Stub.asInterface(b)
            ?: throw IllegalStateException("Shizuku service binder is not IShizukuService")
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Spawn a long-lived process by the service. In root mode the command is
     * executed through `su 2000 -c …` so nothing ever runs as real root.
     */
    fun spawn(cmd: List<String>, env: Array<String>? = null, dir: String? = null): RemoteProc {
        val s = svc()
        val argv: Array<String> = if (serviceUid() == 0) {
            arrayOf("su", "2000", "-c", cmd.joinToString(" ") { shellQuote(it) })
        } else {
            cmd.toTypedArray()
        }
        val rp: IRemoteProcess = s.newProcess(argv, env, dir)
        return RemoteProc(
            rp,
            ParcelFileDescriptor.AutoCloseOutputStream(rp.getOutputStream()),
            ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream()),
            ParcelFileDescriptor.AutoCloseInputStream(rp.getErrorStream())
        )
    }

    /**
     * One-shot shell command via the service. Returns the REAL exit code and
     * merged stdout+stderr. Small-output helper (install steps, probes, kills).
     */
    suspend fun oneShot(command: String, timeoutMs: Long = 25_000): Shot = withContext(Dispatchers.IO) {
        if (!available()) return@withContext Shot(-1, "shizuku_service_unavailable")
        val p = try {
            spawn(listOf("sh", "-c", command))
        } catch (e: Throwable) {
            Log.e(TAG, "oneShot spawn failed", e)
            return@withContext Shot(-1, "spawn_failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val errBuf = java.io.ByteArrayOutputStream()
        val errT = Thread {
            try { p.stderr.copyTo(errBuf) } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        // r1416 TIMEOUT FIX (was dead code — a wedged service binder or a
        // never-exiting child blocked the read loop FOREVER, which locked the
        // agent busy and looked exactly like "Sharingan does nothing").
        // Watchdog: poll isAlive() (works on every API), destroy() on expiry —
        // destroy closes the pipes, which unblocks the read loop below.
        var timedOut = false
        val watchdog = Thread {
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (!p.alive()) return@Thread
                    Thread.sleep(100)
                }
            } catch (_: Throwable) { return@Thread }
            if (p.alive()) {
                timedOut = true
                try { p.destroy() } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; start() }
        val out = StringBuilder()
        var rc = -1
        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = p.stdout.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n))
            }
            rc = p.waitFor()
        } catch (e: Throwable) {
            Log.w(TAG, "oneShot io failed", e)
        } finally {
            try { watchdog.join(2000) } catch (_: Throwable) {}
            try { errT.join(1000) } catch (_: Throwable) {}
            try { p.destroy() } catch (_: Throwable) {}
        }
        var text = (out.toString() + errBuf.toString()).trim()
        if (timedOut) {
            text = "timeout_after_${timeoutMs}ms (partial output kept): " + text.take(2000)
            rc = -1
        }
        if (rc != 0 && text.isNotBlank()) Log.w(TAG, "oneShot rc=$rc: ${text.take(200)}")
        Shot(rc, text)
    }

    /**
     * Stream a local file into a remote path through the service
     * (`cat > path`). Verifies the remote size afterwards — real integrity,
     * no assumption.
     */
    suspend fun pushFile(local: java.io.File, remotePath: String,
                         onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Shot =
        withContext(Dispatchers.IO) {
            if (!available()) return@withContext Shot(-1, "shizuku_service_unavailable")
            val total = local.length()
            val p = try {
                spawn(listOf("sh", "-c", "cat > " + shellQuote(remotePath)))
            } catch (e: Throwable) {
                return@withContext Shot(-1, "spawn_failed: ${e.message ?: e.javaClass.simpleName}")
            }
            var rc = -1
            try {
                local.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        p.stdin.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    p.stdin.flush()
                }
                p.stdin.close()
                rc = p.waitFor()
            } catch (e: Throwable) {
                Log.e(TAG, "pushFile failed", e)
                return@withContext Shot(-1, "push_failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                p.destroy()
            }
            if (rc != 0) return@withContext Shot(rc, "cat exited with $rc")
            val size = oneShot("stat -c %s " + shellQuote(remotePath) + " 2>/dev/null", 10_000)
            if (!size.ok || size.output.trim().toLongOrNull() != total) {
                return@withContext Shot(-2, "remote size mismatch: expected $total got '${size.output.trim()}'")
            }
            Shot(0, "pushed $total bytes")
        }
}
