package moe.shizuku.manager.agent

/**
 * r1419 — DURATION GUARDIAN (deterministic, code-level timing detection).
 *
 * FIELD TRUTH (user, verbatim): after r1418 shipped a full memory core
 * (task_wait tool + SQLite continuations + AlarmManager), the user tried
 * «تسجيل فيديو لمدة 10 ثواني» AGAIN and the recording still never stopped.
 * Root cause found by reading the whole chain: r1418 made the timed step
 * depend 100% on the model VOLUNTARILY calling task_wait. A text promise
 * («سأوقفه بعد 10 ثواني»), a 12-round camera UI burnout, or a mid-task
 * provider error ALL end the task with the recording still running — zero
 * code-level net.
 *
 * This parser is the net's trigger: it detects timed commands LOCALLY
 * (no LLM), in Arabic and English, Arabic-Indic digits included:
 *  - DurationStop  : «سجّل فيديو لمدة 10 ثواني» / «وتوقف بعد 10 ثواني» /
 *                    "record for 10 seconds" / "then stop after 10 s"
 *                    → start NOW, the pending stop MUST run at t0 + N.
 *  - DelayedStart  : «بعد 10 ثواني افتح الكاميرا» / "after 10 s open the camera"
 *                    → nothing runs now; the WHOLE command fires at t0 + N.
 *
 * The orchestrator arms a guard continuation row (SQLite + AlarmManager)
 * for every detection and auto-fires the pending step at the deadline even
 * if the model never called task_wait — and reports that honestly.
 */
object TimedCommandParser {

    /** A detected timing constraint in the user's command. */
    sealed class TimedPlan {
        abstract val seconds: Int
    }

    /** Start now; the deferred completion step fires at t0 + seconds. */
    data class DurationStop(override val seconds: Int) : TimedPlan()

    /** Defer the WHOLE command by seconds (command begins with «بعد N ...»). */
    data class DelayedStart(override val seconds: Int) : TimedPlan()

    private const val MAX_SECONDS = 86_400

    /** unit word → seconds multiplier. Exact matches only. */
    private val UNITS: Map<String, Int> = mapOf(
        // Arabic
        "ثانية" to 1, "ثواني" to 1, "ثوان" to 1, "ث" to 1,
        "دقيقة" to 60, "دقائق" to 60, "د" to 60,
        "ساعة" to 3600, "ساعات" to 3600, "س" to 3600,
        // English
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1, "s" to 1,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60, "m" to 60,
        "hour" to 3600, "hours" to 3600, "h" to 3600
    )

    private const val N = "(\\d+)"
    private const val U = "([\\p{L}]+)"

    /** «لمدة 10 ثواني» / «للمدة 5 دقائق» */
    private val AR_DURATION = Regex("(?:لمدة|للمدة)\\s*$N\\s*$U")
    /** "record for 10 seconds" */
    private val EN_DURATION = Regex("\\bfor\\s+$N\\s+$U\\b", RegexOption.IGNORE_CASE)
    /** «وتوقف بعد 10 ثواني» / «ثم أوقف التصوير بعد 10 ثوان» */
    private val AR_STOP_AFTER = Regex("(?:توقف|أوقف|اوقف|وقف)[^.!?\\n]*?بعد\\s*$N\\s*$U")
    /** "then stop after 10 seconds" */
    private val EN_STOP_AFTER = Regex("\\bstop\\b[^.!?\\n]*?\\bafter\\s+$N\\s+$U\\b", RegexOption.IGNORE_CASE)
    /** «بعد 10 ثواني افتح الكاميرا» — ONLY when the command STARTS with it */
    private val AR_DELAYED = Regex("^\\s*(?:بعد|بعد\\s+مرور)\\s*$N\\s*$U")
    /** "after 10 seconds open the camera" — ONLY when the command STARTS with it */
    private val EN_DELAYED = Regex("^\\s*after\\s+$N\\s+$U\\b", RegexOption.IGNORE_CASE)

    /** Detect a timing plan in a user command, or null when none exists. */
    fun detect(rawText: String): TimedPlan? {
        val text = normalize(rawText)

        // 1) delayed start — only when the command BEGINS with the phrase
        match(AR_DELAYED, text)?.let { return DelayedStart(it) }
        match(EN_DELAYED, text)?.let { return DelayedStart(it) }

        // 2) duration (start now, deferred stop) — the canonical failing case
        match(AR_DURATION, text)?.let { return DurationStop(it) }
        match(EN_DURATION, text)?.let { return DurationStop(it) }

        // 3) explicit "stop ... after N"
        match(AR_STOP_AFTER, text)?.let { return DurationStop(it) }
        match(EN_STOP_AFTER, text)?.let { return DurationStop(it) }

        return null
    }

    /** First match → validated seconds, or null (bad unit / out of range). */
    private fun match(regex: Regex, text: String): Int? {
        val m = regex.find(text) ?: return null
        val mult = UNITS[m.groupValues[2]] ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val seconds = n * mult
        if (seconds < 1 || seconds > MAX_SECONDS) return null
        return seconds.toInt()
    }

    /** Arabic-Indic digits → ASCII + strip tatweel, so both digit systems parse. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '٠'..'٩' -> sb.append(c - '٠')
                in '۰'..'۹' -> sb.append(c - '۰')
                'ـ' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
