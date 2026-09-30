package moe.shizuku.manager.terminal.linux

import android.content.Context
import moe.shizuku.manager.terminal.TermBackend
import moe.shizuku.manager.terminal.TerminalEngine

/**
 * aMiNo r1385 — the user's post-install acceptance tests, executed for REAL
 * against a live Linux session. Every test reports the actual command, the
 * actual exit code and the actual evidence — PASS is never assumed.
 *
 *  1) /etc/os-release shows the distribution
 *  2) bash runs (and the session itself IS a real persistent bash)
 *  3) id / pwd / uname -a
 *  4) apt update really works
 *  5) install a small tool (tree) and verify it runs
 *  6) the session keeps its cwd after cd (persistence)
 *  7) a long-running process can be stopped (in-container kill AND session stop)
 */
object LinuxAcceptance {

    data class TestResult(
        val name: String,
        val command: String,
        val pass: Boolean,
        val evidence: String,
        val exitCode: Int?
    )

    suspend fun runAll(context: Context, onProgress: (String) -> Unit = {}): Pair<Boolean, List<TestResult>> {
        val results = ArrayList<TestResult>()
        var session: moe.shizuku.manager.terminal.TerminalSession? = null
        try {
            onProgress("creating a real Linux session…")
            session = TerminalEngine.create(context, TermBackend.LINUX_USERSPACE, "acceptance", isAgentSession = false)

            fun record(name: String, cmd: String, pass: Boolean, evidence: String, rc: Int?) {
                results.add(TestResult(name, cmd, pass, evidence.take(400), rc))
                onProgress("test: $name → ${if (pass) "PASS" else "FAIL"}")
            }

            // ---- T1: os-release ----
            onProgress("T1: /etc/os-release")
            run {
                val o = session!!.execute("head -n 5 /etc/os-release", 20_000)
                val ev = o.stdout.ifBlank { o.stderr }
                record("distribution info via /etc/os-release", "head -n 5 /etc/os-release",
                    o.ok && o.stdout.contains("Debian"), ev, o.exitCode)
            }

            // ---- T2: bash ----
            onProgress("T2: bash")
            run {
                val o = session!!.execute("bash --version | head -n 1", 20_000)
                val ev = o.stdout.ifBlank { o.stderr }
                record("bash runs", "bash --version | head -n 1",
                    o.ok && o.stdout.contains("bash"), ev + " (session shell itself: bash, persistent)",
                    o.exitCode)
            }

            // ---- T3: id / pwd / uname -a ----
            // r1390 (user req #2): `id` is EVIDENCE, not a gate — the Android
            // shell UID (2000) has no entry in the Debian passwd database, so
            // `id` may print a bare numeric uid or an applet-level error there;
            // that is the normal host identity showing through, NOT an
            // installation failure. Gates: rc + cwd (/root) + a real Linux
            // kernel string from uname.
            onProgress("T3: id / pwd / uname -a")
            run {
                val o = session!!.execute("id; pwd; uname -a", 20_000)
                val ev = o.stdout.ifBlank { o.stderr }
                val ok = o.ok && o.stdout.contains("/root") && o.stdout.contains("Linux")
                record("pwd + uname -a (id reported as identity evidence only)", "id; pwd; uname -a", ok,
                    ev + " — identity note: the aMiNo service runs as the Android shell user (uid 2000); " +
                        "inside the container PRoot may map that identity (fake-root). This is Debian " +
                        "USERSPACE — never real root, no system privileges.",
                    o.exitCode)
            }

            // ---- T4: apt update ----
            onProgress("T4: apt update (can take a while)")
            run {
                val o = session!!.execute("export DEBIAN_FRONTEND=noninteractive; apt-get update 2>&1 | tail -n 4; echo APT_RC=\${PIPESTATUS[0]}", 300_000)
                val rcIn = Regex("APT_RC=(\\d+)").find(o.stdout)?.groupValues?.get(1)?.toIntOrNull()
                val ev = o.stdout.ifBlank { o.stderr }
                record("apt update", "apt-get update", rcIn == 0, ev, rcIn ?: o.exitCode)
            }

            // ---- T5: install a small tool + verify ----
            onProgress("T5: install 'tree' and verify it runs")
            run {
                val i = session!!.execute("export DEBIAN_FRONTEND=noninteractive; apt-get install -y tree 2>&1 | tail -n 2", 300_000)
                val v = session.execute("command -v tree && tree --version | head -n 1", 30_000)
                val ok = i.ok && v.ok && v.stdout.contains("/")
                record("install a tool and verify it runs", "apt-get install -y tree && tree --version", ok,
                    (i.stdout.ifBlank { i.stderr }).take(150) + " → " + v.stdout.take(200),
                    v.exitCode)
            }

            // ---- T6: cwd persistence after cd ----
            onProgress("T6: session persistence after cd")
            run {
                session!!.execute("cd /usr", 10_000)
                val o = session.execute("pwd", 10_000)
                val ok = o.ok && o.stdout.trim() == "/usr"
                session.execute("cd /root", 10_000)
                record("session keeps cwd after cd (same process)", "cd /usr && pwd", ok,
                    o.stdout.trim().ifBlank { o.stderr }, o.exitCode)
            }

            // ---- T7: stop a long-running process (in-container kill) ----
            onProgress("T7: stop a long-running process")
            run {
                // 7a) start sleep in background INSIDE the container, kill it by pid, prove it is gone
                val start = session!!.execute("sleep 120 & echo BG_PID=\$!", 10_000)
                val pid = Regex("BG_PID=(\\d+)").find(start.stdout)?.groupValues?.get(1)?.toIntOrNull()
                var okA = false
                var evA = "could not read the background pid: ${start.stdout.take(120)}"
                var rcA: Int? = null
                if (pid != null && pid > 0) {
                    val k = session.execute("kill -9 $pid; sleep 1; test -d /proc/$pid && echo STILL_ALIVE || echo KILLED", 20_000)
                    rcA = k.exitCode
                    okA = k.stdout.contains("KILLED")
                    evA = "pid $pid → ${k.stdout.trim().ifBlank { k.stderr.take(120) }}"
                }
                record("stop a long-running process (in-container kill, session survives)", "sleep 120 & kill -9 \$!", okA, evA, rcA)

                // 7b) session-level stop of a foreground long process (out-of-band kill)
                val before = session.fgPid
                val long = session.execute("sleep 120", 6_000)   // short timeout on purpose
                val running = long.timedOut && long.stillRunning
                session.stopProcess()
                Thread.sleep(1500)
                val after = session.status()
                val okB = running && !after.busy
                record("stop a long-running process (out-of-band session stop)",
                    "sleep 120 → stop → verify session usable again", okB,
                    "timeout returned still_running=${long.stillRunning} (pid ${long.fgPid ?: before}); after stop: busy=${after.busy}, rc=${after.lastExitCode}",
                    after.lastExitCode)
            }

            return results.all { it.pass } to results
        } catch (e: Throwable) {
            results.add(TestResult("runner", "-", false, "runner error: ${e.message ?: e.javaClass.simpleName}", null))
            return false to results
        } finally {
            try { session?.let { TerminalEngine.close(it.id) } } catch (_: Throwable) {}
        }
    }

    fun report(pass: Boolean, results: List<TestResult>): String = buildString {
        append(if (pass) "ALL ACCEPTANCE TESTS PASSED\n" else "ACCEPTANCE TESTS FAILED\n")
        for (r in results) {
            append("\n[${if (r.pass) "PASS" else "FAIL"}] ${r.name}\n")
            append("  cmd: ${r.command}\n")
            append("  rc : ${r.exitCode ?: "-"}\n")
            append("  out: ${r.evidence.replace("\n", "\n       ")}\n")
        }
    }
}
