package moe.shizuku.manager.agent.auto

import org.json.JSONObject

/** One planned step: what to do, which commands, what we expect to see. */
data class PlanStep(
    val n: Int,
    val title: String,
    val commands: List<String>,
    val expect: String
)

/** The full plan produced by the Planner (LLM call #1). */
data class AutoPlan(
    val goal: String,
    val steps: List<PlanStep>
) {
    fun toMarkdown(): String = buildString {
        appendLine("PLAN — $goal")
        steps.forEach { s ->
            appendLine("${s.n}. ${s.title}")
            s.commands.forEach { c -> appendLine("   $ $c") }
            if (s.expect.isNotBlank()) appendLine("   expect: ${s.expect}")
        }
    }
}

/** Real captured result of one executed command. */
data class ExecResult(
    val command: String,
    val exitCode: Int?,       // null when the marker never arrived (timeout/closed)
    val output: String,       // merged stdout+stderr as adb delivers them, tail-trimmed
    val timedOut: Boolean,
    val durationMs: Long
)

/** Observer verdict for one executed command. */
enum class Verdict { SUCCESS, FAILED, UNCERTAIN }

data class Observed(
    val verdict: Verdict,
    val reason: String        // short human-readable analysis, fed to self-correction
)

/** What the self-correction LLM call decided after a failure. */
data class Correction(
    val action: String,       // retry | replace | skip | abort
    val command: String?,     // only for replace
    val reason: String
)

/** A command waiting for the user's explicit approval (CONFIRM tier). */
data class PendingConfirm(
    val command: String,
    val reason: String
)

/** UI state exposed by the autonomous engine. */
data class AutoUiState(
    val busy: Boolean = false,
    val phase: String? = null,          // localized status text for the chip
    val awaiting: PendingConfirm? = null // non-null => show approval dialog
)
