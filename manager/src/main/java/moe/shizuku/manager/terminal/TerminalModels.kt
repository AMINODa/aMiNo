package moe.shizuku.manager.terminal

/**
 * aMiNo r1384 — Integrated Terminal Environment: shared models.
 *
 * The terminal is a REAL execution engine, not a display mock:
 *  - persistent sessions (one long-lived `sh` per session — cwd/env state survives)
 *  - separate stdout / stderr capture per command
 *  - real exit codes via an in-stream marker protocol
 *  - multiple independent sessions, user sessions kept separate from agent sessions
 *
 * Backends are only ever REPORTED as available when actually probed — we never
 * invent an environment that is not there (no fake root, no fake Termux).
 */
enum class TermBackend(val id: String, val title: String, val startDir: String) {
    /** The app's own sandbox: uid of aMiNo, toybox tools, always exists. */
    LOCAL_APP("local", "Local app shell", ""),

    /** The ADB shell identity (uid 2000) through the wireless debugging channel. */
    ADB_SHELL("adb", "ADB shell", "/data/local/tmp"),

    /** Root — only reported/usable when `su` REALLY runs and returns uid 0. */
    ROOT("root", "Root shell", "/data/local/tmp"),

    /** Termux app — detection + RUN_COMMAND handoff; never claimed without the app. */
    TERMUX("termux", "Termux", ""),

    /** A bundled Linux userspace (proot distro). NOT shipped in this build — reported absent. */
    LINUX_USERSPACE("linux", "Linux userspace", "")
}

/** One environment as reported by honest discovery. */
data class EnvironmentInfo(
    val backend: TermBackend,
    val available: Boolean,
    val status: String,        // human-readable REAL state
    val path: String,          // where commands run from / home dir
    val identity: String,      // uid / permission level actually held
    val notes: String          // limits, package manager availability...
)

/** One line of terminal scrollback. */
data class TermLine(
    val seq: Long,
    val kind: TermLine.Kind,
    val text: String,
    val ts: Long = System.currentTimeMillis()
) {
    enum class Kind { CMD, OUT, ERR, SYS }
}

/** Result of one terminal_execute — everything real, nothing invented. */
data class ExecOutcome(
    val ok: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val cwd: String?,
    val durationMs: Long,
    val timedOut: Boolean,
    val stillRunning: Boolean,
    val fgPid: Int?,
    val sessionRestarted: Boolean,
    val note: String? = null
)

/** Session status snapshot for tools / UI. */
data class SessionStatus(
    val id: String,
    val name: String,
    val backend: TermBackend,
    val agentSession: Boolean,
    val alive: Boolean,
    val ready: Boolean,
    val busy: Boolean,
    val cwd: String?,
    val lastExitCode: Int?,
    val fgPid: Int?,
    val restarts: Int,
    val lineCount: Int
)
