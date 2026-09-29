package moe.shizuku.manager.agent.auto

/**
 * Observer — decides what actually happened from REAL output only.
 * Heuristics first (fast, free); the reason string is fed verbatim to the
 * self-correction prompt so the model reacts to facts, not guesses.
 */
object Observer {

    private data class Pattern(val re: Regex, val reason: String)

    private val failurePatterns = listOf(
        Pattern(Regex("""(?i)\b(not found|no such file or directory|applet not found|bad usage)\b"""), "command/applet not found on this Android"),
        Pattern(Regex("""(?i)\b(permission denied|not permitted|operation not permitted|access denied)\b"""), "permission denied by SELinux/user"),
        Pattern(Regex("""(?i)\b(shell_not_connected|shell_error)\b"""), "ADB shell connection problem"),
        Pattern(Regex("""(?i)\b(broken pipe|connection reset|timeout|timed out)\b"""), "connection/stream problem"),
        Pattern(Regex("""(?i)\b(usage:|unknown option|invalid option|unrecognized)\b"""), "wrong arguments for this binary"),
        Pattern(Regex("""(?i)\b(error|failed|failure)\b"""), "command reported an error")
    )

    private val emptyOkCommands = Regex("""^(clear|echo|cd|sync)\b""")

    fun analyze(result: ExecResult): Observed {
        val out = result.output
        val outEmpty = out.isBlank()

        // Connection problems are always failures regardless of rc
        if (out.contains("shell_not_connected") || out.contains("shell_error")) {
            return Observed(Verdict.FAILED, "ADB connection failed: ${out.take(160)}")
        }

        if (result.timedOut) {
            return Observed(Verdict.UNCERTAIN, "timed out after 20s — output may be incomplete; use a narrower command (head/-n 1/-t 100)")
        }

        when (result.exitCode) {
            null -> return Observed(Verdict.UNCERTAIN, "no exit code captured (adb quirk); judge from output only")
            0 -> {
                // rc=0 with completely empty output is suspicious for inspection commands
                if (outEmpty && !emptyOkCommands.containsMatchIn(result.command)) {
                    return Observed(Verdict.UNCERTAIN, "exit 0 but empty output — maybe nothing matched (wrong filter/package name?)")
                }
                // some tools print "error"/"failed" while exiting 0 — surface honestly
                for (p in failurePatterns) {
                    if (p.re.containsMatchIn(out)) return Observed(Verdict.UNCERTAIN, "exit 0 but output says: ${p.reason}")
                }
                return Observed(Verdict.SUCCESS, "exit 0, output captured (${out.length} chars)")
            }
            else -> {
                for (p in failurePatterns) {
                    if (p.re.containsMatchIn(out)) return Observed(Verdict.FAILED, "exit ${result.exitCode}: ${p.reason}")
                }
                return Observed(Verdict.FAILED, "exit code ${result.exitCode}${if (outEmpty) " with no output" else ""}")
            }
        }
    }

    /** One-line compact view persisted in the chat: command + verdict + output tail. */
    fun render(result: ExecResult, observed: Observed): String = buildString {
        append("$ ${result.command}")
        appendLine()
        val mark = when (observed.verdict) {
            Verdict.SUCCESS -> "OK"
            Verdict.FAILED -> "FAILED"
            Verdict.UNCERTAIN -> "UNCERTAIN"
        }
        appendLine("[$mark] ${observed.reason} (${result.durationMs}ms)")
        if (result.output.isNotBlank()) appendLine(result.output.take(2500))
    }.trimEnd()
}
