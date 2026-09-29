package moe.shizuku.manager.agent.auto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.AgentOrchestrator
import moe.shizuku.manager.agent.CloudflareProvider
import moe.shizuku.manager.agent.GeminiProvider
import moe.shizuku.manager.agent.LlmDecision
import moe.shizuku.manager.agent.LlmMessage
import moe.shizuku.manager.agent.LlmProvider
import moe.shizuku.manager.agent.OpenAiCompatProvider
import moe.shizuku.manager.agent.OpenRouterProvider
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.keys.SecureStore
import moe.shizuku.manager.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * AutonomousEngine — the Autonomous Execution Loop (architecture C, r1379).
 *
 *   PLAN (LLM) -> per step: VALIDATE -> [CONFIRM gate] -> EXECUTE -> OBSERVE
 *               -> (failure? SELF-CORRECT: retry/replace/skip/abort) -> ...
 *   -> REPORT (LLM, from real results only) -> persist everything locally.
 *
 * The model NEVER executes: every command passes CommandValidator; mutating
 * commands pause the loop until the user approves; blocked categories never run.
 * All rows are persisted through MemoryRepository so the chat survives process death.
 */
object AutonomousEngine {

    private const val TAG = "AutonomousEngine"
    private const val MAX_STEPS = 6
    private const val MAX_COMMANDS_PER_STEP = 3
    private const val MAX_CORRECTIONS = 6
    private const val NO_SHELL_MARKER = "echo no_shell_needed"

    private val guard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught auto-loop error", e)
        runCatching { _state.value = AutoUiState(busy = false) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)

    private val _state = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = _state.asStateFlow()

    @Volatile private var cancelled = false
    @Volatile private var pendingApproval: CompletableDeferred<Boolean>? = null

    fun cancel() {
        cancelled = true
        pendingApproval?.complete(false)
    }

    /** User answered the approval dialog (true = allow). */
    fun approve(allowed: Boolean) {
        pendingApproval?.complete(allowed)
    }

    fun run(context: Context, goal: String) {
        if (_state.value.busy) return
        if (AgentOrchestrator.state.value.busy) return
        val text = goal.trim()
        if (text.isEmpty()) return
        cancelled = false

        scope.launch {
            val ctx = context.applicationContext
            val convId = AgentOrchestrator.state.value.conversationId
            if (convId <= 0) return@launch

            MemoryRepository.addMessage(ctx, convId, "user", text)
            MemoryRepository.saveWorking(ctx, convId, goal = text, stepsJson = "[]", status = "running")
            AgentOrchestrator.refresh(ctx)
            _state.value = AutoUiState(busy = true, phase = ctx.getString(R.string.auto_status_planning))

            try {
                runLoop(ctx, convId, text)
            } catch (e: IOException) {
                fail(ctx, convId, ctx.getString(R.string.agent_err_network, e.message ?: "network"))
            } catch (e: Exception) {
                Log.e(TAG, "auto loop failed", e)
                fail(ctx, convId, ctx.getString(R.string.agent_err_internal, e.message ?: e.javaClass.simpleName))
            } finally {
                _state.value = AutoUiState(busy = false, phase = null, awaiting = null)
                AgentOrchestrator.refresh(ctx)
            }
        }
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun runLoop(ctx: Context, convId: Long, goal: String) {
        val llm = resolveLlm(ctx) ?: run {
            fail(ctx, convId, ctx.getString(R.string.agent_err_no_key)); return
        }

        // ---- phase 1: PLAN -------------------------------------------------
        val caps = AutoPrompts.capabilitiesBlock(ExecutionEngine.shellReady(), ExecutionEngine.shellPort())
        val planJson = llm.json(ctx, AutoPrompts.plan(goal, caps)) ?: run {
            fail(ctx, convId, ctx.getString(R.string.auto_err_plan)); return
        }
        val err = planJson.optString("error")
        if (err.isNotBlank()) { fail(ctx, convId, err); return }

        val plan = parsePlan(planJson) ?: run {
            fail(ctx, convId, ctx.getString(R.string.auto_err_plan)); return
        }
        MemoryRepository.addMessage(ctx, convId, "assistant", "📋 ${plan.toMarkdown()}")
        AgentOrchestrator.refresh(ctx)

        // ---- phase 2: EXECUTION LOOP ---------------------------------------
        val results = ArrayList<String>()
        var correctionsUsed = 0
        var aborted = false

        outer@ for (step in plan.steps) {
            if (cancelled) { cancelledNote(ctx, convId); return }

            _state.value = _state.value.copy(
                phase = ctx.getString(R.string.auto_status_step, step.n, plan.steps.size))

            for (cmd in step.commands.take(MAX_COMMANDS_PER_STEP)) {
                if (cancelled) { cancelledNote(ctx, convId); return }

                // pure-knowledge marker: no device access needed
                if (cmd.trim() == NO_SHELL_MARKER) {
                    results.add("$ ${NO_SHELL_MARKER}\n[SKIPPED] pure question — no device access needed")
                    continue
                }

                var command = cmd.trim()
                var executed: ExecResult? = null
                var observed: Observed? = null
                var attempts = 0

                while (attempts < 2) { // original + at most one corrected attempt
                    attempts++

                    // VALIDATE
                    val ruling = CommandValidator.validate(command)
                    when (ruling.tier) {
                        CommandValidator.Tier.BLOCK -> {
                            results.add("$ $command\n[BLOCKED] ${ruling.reason}")
                            MemoryRepository.addMessage(ctx, convId, "auto",
                                "$ $command\n[BLOCKED] ${ruling.reason}", "shell", false)
                            AgentOrchestrator.refresh(ctx)
                            if (correctionsUsed >= MAX_CORRECTIONS) { aborted = true; break@outer }
                            correctionsUsed++
                            _state.value = _state.value.copy(phase = ctx.getString(R.string.auto_status_correct))
                            val fix = llm.correct(ctx, goal, step,
                                ExecResult(command, null, "blocked by safety validator: ${ruling.reason}", false, 0),
                                Observed(moe.shizuku.manager.agent.auto.Verdict.FAILED, "blocked: ${ruling.reason}"))
                                ?: Correction("skip", null, "no correction available")
                            results.add("🔁 ${fix.reason}")
                            MemoryRepository.addMessage(ctx, convId, "assistant", "🔁 ${fix.reason}")
                            AgentOrchestrator.refresh(ctx)
                            when (fix.action) {
                                "replace" -> { fix.command?.let { command = it.trim() }; continue }
                                "abort" -> { aborted = true; break@outer }
                                else -> break // skip / unknown
                            }
                        }
                        CommandValidator.Tier.CONFIRM -> {
                            // pause the loop until the user explicitly approves
                            _state.value = _state.value.copy(
                                phase = ctx.getString(R.string.auto_status_confirm),
                                awaiting = PendingConfirm(command, ruling.reason))
                            val allowed = awaitApproval()
                            _state.value = _state.value.copy(awaiting = null)
                            if (cancelled) { cancelledNote(ctx, convId); return }
                            if (!allowed) {
                                results.add("$ $command\n[DENIED] rejected by the user")
                                MemoryRepository.addMessage(ctx, convId, "auto",
                                    "$ $command\n[DENIED] rejected by the user", "shell", false)
                                AgentOrchestrator.refresh(ctx)
                                break
                            }
                            val r = ExecutionEngine.run(command)
                            val o = Observer.analyze(r)
                            executed = r; observed = o
                            persistResult(ctx, convId, command, r, o, results)
                            if (o.verdict != Verdict.SUCCESS && correctionsUsed < MAX_CORRECTIONS) {
                                correctionsUsed++
                                val fix = selfCorrectStep(ctx, convId, llm, goal, step, r, o, results) ?: break
                                when (fix.action) {
                                    "replace" -> { fix.command?.let { command = it.trim() }; continue }
                                    "retry" -> continue
                                    "abort" -> { aborted = true; break@outer }
                                    else -> break
                                }
                            }
                            break
                        }
                        CommandValidator.Tier.ALLOW -> {
                            val r = ExecutionEngine.run(command)
                            val o = Observer.analyze(r)
                            executed = r; observed = o
                            persistResult(ctx, convId, command, r, o, results)
                            if (o.verdict != Verdict.SUCCESS) {
                                if (correctionsUsed >= MAX_CORRECTIONS) { continue }
                                correctionsUsed++
                                val fix = selfCorrectStep(ctx, convId, llm, goal, step, r, o, results) ?: break
                                when (fix.action) {
                                    "replace" -> { fix.command?.let { command = it.trim() }; continue }
                                    "retry" -> continue
                                    "abort" -> { aborted = true; break@outer }
                                    else -> break
                                }
                            }
                            break
                        }
                    }
                }
            }
        }

        // ---- phase 3: REPORT -----------------------------------------------
        if (cancelled) { cancelledNote(ctx, convId); return }
        _state.value = _state.value.copy(phase = ctx.getString(R.string.auto_status_report))
        val report = llm.report(ctx, goal, plan, results)
        val suffix = when {
            aborted -> "\n\n⚠️ " + ctx.getString(R.string.auto_note_aborted)
            else -> ""
        }
        MemoryRepository.addMessage(ctx, convId, "assistant", (report ?: "⚠️ " + ctx.getString(R.string.auto_err_report)) + suffix)
        MemoryRepository.saveWorking(ctx, convId, goal, JSONArray(results).toString(), if (aborted) "aborted" else "done")
        AgentOrchestrator.refresh(ctx)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun selfCorrectStep(
        ctx: Context, convId: Long, llm: Llm, goal: String, step: PlanStep,
        r: ExecResult, o: Observed, results: MutableList<String>
    ): Correction? {
        _state.value = _state.value.copy(phase = ctx.getString(R.string.auto_status_correct))
        val fix = llm.correct(ctx, goal, step, r, o) ?: return null
        results.add("🔁 ${fix.reason}")
        MemoryRepository.addMessage(ctx, convId, "assistant", "🔁 ${fix.reason}")
        AgentOrchestrator.refresh(ctx)
        return fix
    }

    private suspend fun awaitApproval(): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingApproval = d
        val r = runCatching { d.await() }.getOrDefault(false)
        pendingApproval = null
        return r
    }

    private suspend fun persistResult(
        ctx: Context, convId: Long, command: String,
        r: ExecResult, o: Observed, results: MutableList<String>
    ) {
        val rendered = Observer.render(r, o)
        results.add(rendered)
        MemoryRepository.addMessage(ctx, convId, "auto", rendered, "shell",
            o.verdict == Verdict.SUCCESS)
        AgentOrchestrator.refresh(ctx)
    }

    private fun parsePlan(o: JSONObject): AutoPlan? {
        val goal = o.optString("goal").ifBlank { "task" }
        val stepsArr = o.optJSONArray("steps") ?: return null
        val steps = ArrayList<PlanStep>()
        for (i in 0 until stepsArr.length().coerceAtMost(MAX_STEPS)) {
            val s = stepsArr.optJSONObject(i) ?: continue
            val cmds = ArrayList<String>()
            val cArr = s.optJSONArray("commands")
            if (cArr != null) for (j in 0 until cArr.length()) {
                val c = cArr.optString(j).trim()
                if (c.isNotEmpty()) cmds.add(c)
            }
            if (cmds.isEmpty()) {
                val single = s.optString("command").trim()
                if (single.isNotEmpty()) cmds.add(single)
            }
            if (cmds.isEmpty()) continue
            steps.add(PlanStep(s.optInt("n", steps.size + 1),
                s.optString("title").ifBlank { "step ${steps.size + 1}" },
                cmds, s.optString("expect")))
        }
        return if (steps.isEmpty()) null else AutoPlan(goal, steps)
    }

    private fun resolveLlm(ctx: Context): Llm? {
        val provider: LlmProvider = when (ApiKeysStore.provider(ctx)) {
            ApiKeysStore.PROVIDER_CLOUDFLARE -> CloudflareProvider
            ApiKeysStore.PROVIDER_OPENROUTER -> OpenRouterProvider
            ApiKeysStore.PROVIDER_OPENAI_COMPAT -> OpenAiCompatProvider
            else -> GeminiProvider
        }
        val key = SecureStore.get(ctx, "apikey_" + ApiKeysStore.provider(ctx)) ?: return null
        return Llm(provider, key, ApiKeysStore.model(ctx), ApiKeysStore.baseUrl(ctx))
    }

    private class Llm(val p: LlmProvider, val key: CharArray, val model: String, val baseUrl: String?) {

        /** Plain-text LLM call with tools disabled (planner/corrector/reporter). */
        private fun call(system: String, prompt: String): LlmDecision =
            p.chat(system, listOf(LlmMessage("user", prompt)), emptyList(), key, model, baseUrl)

        suspend fun json(ctx: Context, prompt: String): JSONObject? = withContext(Dispatchers.IO) {
            when (val d = call(ctx.getString(R.string.auto_sys_json), prompt)) {
                is LlmDecision.Text -> AutoPrompts.extractJson(d.text)
                else -> null
            }
        }

        suspend fun correct(ctx: Context, goal: String, step: PlanStep, r: ExecResult, o: Observed): Correction? {
            val j = json(ctx, AutoPrompts.selfCorrect(goal, step, r, o)) ?: return null
            val action = j.optString("action", "skip")
            return Correction(action, j.optString("command").takeIf { it.isNotBlank() && action == "replace" },
                j.optString("reason", ""))
        }

        suspend fun report(ctx: Context, goal: String, plan: AutoPlan, results: List<String>): String? =
            withContext(Dispatchers.IO) {
                when (val d = call(ctx.getString(R.string.auto_sys_report), AutoPrompts.report(goal, plan, results))) {
                    is LlmDecision.Text -> d.text
                    is LlmDecision.Error -> "⚠️ ${d.message}"
                    else -> null
                }
            }
    }

    private suspend fun fail(ctx: Context, convId: Long, msg: String) {
        MemoryRepository.addMessage(ctx, convId, "assistant", "⚠️ $msg")
        MemoryRepository.saveWorking(ctx, convId, "", "[]", "error")
        AgentOrchestrator.refresh(ctx)
    }

    private suspend fun cancelledNote(ctx: Context, convId: Long) {
        MemoryRepository.addMessage(ctx, convId, "assistant", ctx.getString(R.string.agent_cancelled_note))
        MemoryRepository.saveWorking(ctx, convId, "", "[]", "cancelled")
        AgentOrchestrator.refresh(ctx)
    }
}
