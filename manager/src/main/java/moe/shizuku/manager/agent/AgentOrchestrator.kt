package moe.shizuku.manager.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.keys.SecureStore
import moe.shizuku.manager.memory.ChatMessage
import moe.shizuku.manager.memory.MemoryRepository
import moe.shizuku.manager.sharingan.SharinganTaskNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Agent status shown in the chat UI. */
sealed class AgentStatus {
    object Ready : AgentStatus()
    object Thinking : AgentStatus()
    data class ExecutingTool(val name: String) : AgentStatus()
    object Verifying : AgentStatus()
    object NotConfigured : AgentStatus()
}

/** One row in the chat UI. */
data class ChatItem(
    val id: Long,
    val role: String,
    val text: String,
    val toolName: String? = null,
    val toolOk: Boolean? = null
)

data class AgentUiState(
    val items: List<ChatItem> = emptyList(),
    val status: AgentStatus = AgentStatus.Ready,
    val busy: Boolean = false,
    val providerReady: Boolean = false,
    val conversationId: Long = -1
)

/**
 * AMINO Agent Orchestrator (r1376).
 *
 * The real agent loop: understand -> plan (LLM) -> pick tool -> check availability ->
 * execute through the real channel -> read result -> verify -> reply.
 * All messages/results persist locally (SQLite) so conversations survive process
 * death. Runs are cancellable via [cancel] (stop button).
 */
object AgentOrchestrator {

    private const val TAG = "AgentOrchestrator"
    // v1.3: GUI tasks (screen_read -> screen_act -> screen_read verify cycles) need
    // more rounds than pure shell tasks — 12 keeps perception/skill workflows in one run.
    private const val MAX_TOOL_ROUNDS = 12
    private const val HISTORY_LIMIT = 12

    private val guard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught agent error", e)
        runCatching { _state.value = _state.value.copy(busy = false, status = AgentStatus.Ready) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state

    @Volatile
    private var cancelled = false

    /** r1418 — busy-retry counters for continuations that fired mid-task (M6). */
    private val continuationRetries = ConcurrentHashMap<Long, Int>()

    private var appContext: Context? = null

    // ---------- conversation lifecycle ----------

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    fun newConversation(context: Context) {
        val id = MemoryRepository.createConversation(context, "...")
        _state.value = AgentUiState(emptyList(), AgentStatus.Ready, false, providerReady(context), id)
    }

    fun openConversation(context: Context, conversationId: Long) {
        reload(context, conversationId)
    }

    fun loadLatestOrNew(context: Context) {
        init(context)
        val latest = MemoryRepository.listConversations(context).firstOrNull()
        if (latest == null) newConversation(context) else openConversation(context, latest.id)
    }

    /**
     * r1379: lets the AutonomousEngine publish newly persisted plan/command/report
     * rows into the same chat stream without touching the orchestrator's status.
     */
    fun refresh(context: Context) {
        val convId = _state.value.conversationId
        if (convId <= 0) return
        val msgs = MemoryRepository.messages(context, convId)
        _state.value = _state.value.copy(items = msgs.map { it.toItem() },
            providerReady = providerReady(context))
    }

    /**
     * aMiNo 1.1 — first-launch greeting: persist ONE assistant message into the
     * CURRENT conversation and publish it. Never touches busy/status — a plain
     * append + refresh, exactly how tool rows land in the stream. Call AFTER
     * loadLatestOrNew/openConversation so it lands in a real conversation.
     */
    fun postWelcome(context: Context, text: String) {
        init(context)
        val convId = _state.value.conversationId
        if (convId <= 0) return
        MemoryRepository.addMessage(context, convId, "assistant", text)
        refresh(context)
    }

    private fun providerReady(context: Context): Boolean = ApiKeysStore.hasKey(context)

    private fun reload(context: Context, conversationId: Long) {
        val msgs = MemoryRepository.messages(context, conversationId)
        _state.value = AgentUiState(msgs.map { it.toItem() }, _state.value.status, _state.value.busy,
            providerReady(context), conversationId)
    }

    private fun ChatMessage.toItem() = ChatItem(id, role, content, toolName, toolOk)

    fun cancel() {
        cancelled = true
    }

    // ---------- the agent loop ----------

    /**
     * r1415 — `fromSharingan`: commands typed in the floating panel run with
     * the app in the BACKGROUND (the panel never opens the chat activity).
     * The orchestrator already runs on its own scope with SQLite persistence,
     * so the only change is the outcome channel: a task notification carries
     * the REAL final answer to the user while they stay in whatever app they
     * were using (Sharingan's actual purpose, user's words: "هذا هو دور
     * الشارينغان").
     */
    fun send(context: Context, userText: String, fromSharingan: Boolean = false) {
        init(context)
        if (_state.value.busy) return
        val text = userText.trim()
        if (text.isEmpty()) return

        if (!providerReady(context)) {
            _state.value = _state.value.copy(status = AgentStatus.NotConfigured)
            return
        }

        cancelled = false
        scope.launch {
            val ctx = appContext ?: return@launch
            TaskMemory.holdWake(ctx) // r1418 (M2): CPU stays on during in-process waits
            try {
                var convId = _state.value.conversationId
                if (convId <= 0) convId = MemoryRepository.createConversation(ctx, text)
                val msgs = MemoryRepository.messages(ctx, convId)
                if (msgs.none { it.role == "user" }) MemoryRepository.touchConversation(ctx, convId, text)
                MemoryRepository.addMessage(ctx, convId, "user", text)
                MemoryRepository.saveWorking(ctx, convId, goal = text, stepsJson = "[]", status = "running")
                publish(ctx, convId, AgentStatus.Thinking)
                if (fromSharingan) SharinganTaskNotifier.taskStarted(ctx, text)

                runLoop(ctx, convId, text)

                if (fromSharingan) {
                    // Every runLoop exit path saves an assistant row first
                    // (final answer / honest error / cancellation note) — surface
                    // THAT row verbatim, never a re-invented summary.
                    val last = MemoryRepository.messages(ctx, convId)
                        .lastOrNull { it.role == "assistant" && it.toolName == null }
                    if (last != null) SharinganTaskNotifier.taskDone(ctx, last.content)
                }
            } finally {
                TaskMemory.dropWake()
            }
        }
    }

    /**
     * r1416 — the Sharingan panel's DEDICATED entry (user report: commands
     * died silently; expert audit found staging ran on the PANEL's scope so
     * auto-hide could cancel a send mid-capture, and dropped sends were
     * invisible).
     *
     * Differences from [send]:
     *  - Returns FALSE (caller shows an honest toast) when busy or not
     *    configured — a command is NEVER accepted then silently dropped.
     *  - Context staging runs HERE, on the orchestrator's own scope: hiding
     *    the panel can no longer cancel it (F4).
     *  - try/finally GUARANTEES a terminal SharinganTaskNotifier.taskDone —
     *    even if persistence or anything before runLoop crashes (F7). The
     *    posted text is the REAL last assistant row, verbatim.
     */
    fun sendSharingan(context: Context, userText: String): Boolean {
        init(context)
        if (_state.value.busy) return false
        val text = userText.trim()
        if (text.isEmpty()) return false
        if (!providerReady(context)) {
            _state.value = _state.value.copy(status = AgentStatus.NotConfigured)
            return false
        }
        cancelled = false
        scope.launch {
            val ctx = appContext ?: return@launch
            TaskMemory.holdWake(ctx) // r1418 (M2): CPU lifeline for in-process waits
            // Stage REAL screen context inside THIS scope (TRACE TRUTH: last
            // trace or a fresh live snapshot — never invented). Staged moments
            // before the loop consumes it, so a concurrent chat send can no
            // longer steal it in practice.
            val id = moe.shizuku.manager.sharingan.SharinganState.state.value.lastTraceId
                ?.takeIf { moe.shizuku.manager.sharingan.TraceStore.exists(ctx, it) }
                ?: moe.shizuku.manager.sharingan.ScreenCapture.capture()?.let {
                    runCatching { moe.shizuku.manager.sharingan.TraceStore.snapshot(ctx, it) }.getOrNull()
                }
            if (id != null) moe.shizuku.manager.sharingan.SharinganContextHub.stage(id)

            var convId = -1L
            try {
                convId = _state.value.conversationId
                if (convId <= 0) convId = MemoryRepository.createConversation(ctx, text)
                val msgs = MemoryRepository.messages(ctx, convId)
                if (msgs.none { it.role == "user" }) MemoryRepository.touchConversation(ctx, convId, text)
                MemoryRepository.addMessage(ctx, convId, "user", text)
                MemoryRepository.saveWorking(ctx, convId, goal = text, stepsJson = "[]", status = "running")
                publish(ctx, convId, AgentStatus.Thinking)
                moe.shizuku.manager.sharingan.SharinganTaskNotifier.taskStarted(ctx, text)

                runLoop(ctx, convId, text)
            } catch (t: Throwable) {
                Log.e(TAG, "sharingan task crashed", t)
                runCatching {
                    MemoryRepository.addMessage(ctx, convId, "assistant",
                        "⚠️ " + (t.message ?: t.javaClass.simpleName))
                }
            } finally {
                TaskMemory.dropWake()
                // EVERY path ends visibly: answer, honest error, cancel note —
                // or this explicit fallback when no assistant row exists at all.
                val last = if (convId > 0) MemoryRepository.messages(ctx, convId)
                    .lastOrNull { it.role == "assistant" && it.toolName == null } else null
                moe.shizuku.manager.sharingan.SharinganTaskNotifier.taskDone(
                    ctx,
                    last?.content?.takeIf { it.isNotBlank() }
                        ?: "⚠️ The task ended without a result row (internal error) — reopen the chat to check the conversation."
                )
                // r1418 — auto-lesson (strong memory loop): the outcome of every
                // Sharingan task lands in episodic memory, failures included.
                runCatching {
                    last?.content?.let { c ->
                        MemoryRepository.addExperience(ctx, text,
                            if (c.startsWith("⚠️")) "fail" else "success",
                            "background task → ${c.take(220)}")
                    }
                }
            }
        }
        return true
    }

    private suspend fun runLoop(context: Context, convId: Long, userText: String) {
        val provider: LlmProvider = when (ApiKeysStore.provider(context)) {
            ApiKeysStore.PROVIDER_CLOUDFLARE -> CloudflareProvider
            ApiKeysStore.PROVIDER_OPENROUTER -> OpenRouterProvider
            ApiKeysStore.PROVIDER_AGENTROUTER -> AgentRouterProvider
            ApiKeysStore.PROVIDER_OPENAI_COMPAT -> OpenAiCompatProvider
            else -> GeminiProvider
        }
        val apiKey = SecureStore.get(context, "apikey_" + ApiKeysStore.provider(context))
        if (apiKey == null) {
            saveAssistant(context, convId, context.getString(R.string.agent_err_no_key))
            publish(context, convId, AgentStatus.Ready)
            return
        }
        val model = ApiKeysStore.model(context)
        val baseUrl = ApiKeysStore.baseUrl(context)

        val steps = JSONArray()
        try {
            var round = 0
            while (true) {
                if (cancelled) { saveCancelled(context, convId, steps); return }

                // r1418 (M5): PAIR-AWARE history window — a raw takeLast(12) could
                // start on a tool row whose assistant marker row was evicted, which
                // sends an orphaned role:"tool"/functionResponse to the provider
                // (hard 400 = another mid-task death). Drop leading orphans.
                var recentRows = MemoryRepository.messages(context, convId, HISTORY_LIMIT * 4)
                    .takeLast(HISTORY_LIMIT)
                while (recentRows.isNotEmpty() && recentRows.first().role == "tool") {
                    recentRows = recentRows.drop(1)
                }
                val history = toLlmHistory(recentRows)
                val systemPrompt = AgentIdentity.systemPrompt(
                    context,
                    AgentIdentity.contextBlock(context, userText) + taskMemoryBlock(context, convId))
                val decision = provider.chat(systemPrompt, history, ToolRegistry.specs(), apiKey, model, baseUrl)

                when (decision) {
                    is LlmDecision.Error -> {
                        // Real provider error - surfaced verbatim, never faked.
                        MemoryRepository.addMessage(context, convId, "assistant", "⚠️ ${decision.message}")
                        MemoryRepository.saveWorking(context, convId, userText, steps.toString(), "error")
                        publish(context, convId, AgentStatus.Ready)
                        return
                    }
                    is LlmDecision.Text -> {
                        val finalText = decision.text + ResultVerifier.verify(steps)
                        MemoryRepository.addMessage(context, convId, "assistant", finalText)
                        MemoryRepository.saveWorking(context, convId, userText, steps.toString(), "done")
                        publish(context, convId, AgentStatus.Ready)
                        return
                    }
                    is LlmDecision.ToolCalls -> {
                        if (round >= MAX_TOOL_ROUNDS) {
                            MemoryRepository.addMessage(context, convId, "assistant",
                                "⚠️ " + context.getString(R.string.agent_err_tool_rounds))
                            MemoryRepository.saveWorking(context, convId, userText, steps.toString(), "error")
                            publish(context, convId, AgentStatus.Ready)
                            return
                        }
                        round++
                        for (call in decision.calls) {
                            if (cancelled) { saveCancelled(context, convId, steps); return }
                            executeToolCall(context, convId, call, steps)
                            publish(context, convId, AgentStatus.Verifying)
                        }
                        // r1418 (M1/M3): a DURABLE continuation was armed this round
                        // (task_wait > 120s). Park the task honestly — the pending
                        // step lives in SQLite + AlarmManager now, NOT in a promise.
                        val sched = runCatching {
                            MemoryRepository.newestScheduled(context, convId)
                        }.getOrNull()
                        if (sched != null) {
                            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sched.fireAt))
                            val note = context.getString(R.string.sharingan_continuation_note,
                                sched.action.take(140), at)
                            MemoryRepository.addMessage(context, convId, "assistant", note)
                            MemoryRepository.saveWorking(context, convId, sched.goal, steps.toString(), "waiting")
                            publish(context, convId, AgentStatus.Ready)
                            return
                        }
                    }
                }
            }
        } catch (e: IOException) {
            MemoryRepository.addMessage(context, convId, "assistant",
                "⚠️ " + context.getString(R.string.agent_err_network, e.message ?: "network"))
            MemoryRepository.saveWorking(context, convId, userText, steps.toString(), "error")
            publish(context, convId, AgentStatus.Ready)
        } catch (e: Exception) {
            Log.e(TAG, "agent loop failed", e)
            MemoryRepository.addMessage(context, convId, "assistant",
                "⚠️ " + context.getString(R.string.agent_err_internal, e.message ?: e.javaClass.simpleName))
            MemoryRepository.saveWorking(context, convId, userText, steps.toString(), "error")
            publish(context, convId, AgentStatus.Ready)
        }
    }

    /**
     * DB rows -> neutral LLM history.
     * Assistant rows that carry a toolName are tool-call markers; their content IS the
     * arguments JSON (that is how args round-trip back to the provider).
     * r1379: "auto" rows (autonomous-loop shell results) are flattened into plain
     * assistant text so they NEVER desync the provider's tool_call/tool pairing.
     */
    private fun toLlmHistory(rows: List<ChatMessage>): List<LlmMessage> =
        rows.map { m ->
            when {
                m.role == "auto" ->
                    LlmMessage("assistant", "[shell] " + m.content)
                m.role == "assistant" && m.toolName != null ->
                    LlmMessage("assistant", m.content.ifBlank { "{}" }, m.toolName, m.content.ifBlank { "{}" }, null)
                else -> LlmMessage(m.role, m.content, m.toolName, null, m.toolOk)
            }
        }

    /**
     * r1418 (M4): working memory was WRITE-ONLY — persisted but never shown to
     * the model, so a resumed/long task lost its goal the moment history rows
     * were evicted. Now the active goal + every armed durable continuation are
     * injected into the system prompt EVERY round: the loop literally cannot
     * forget what it is doing or what is still pending.
     */
    private fun taskMemoryBlock(context: Context, convId: Long): String {
        val sb = StringBuilder()
        val wm = runCatching { MemoryRepository.working(context, convId) }.getOrNull()
        if (wm != null && wm.third == "running" && wm.first.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("[SHARINGAN MEMORY — ACTIVE TASK]")
            sb.appendLine("goal: ${wm.first.take(300)}")
        }
        val mine = runCatching { MemoryRepository.scheduledContinuations(context) }
            .getOrDefault(emptyList()).filter { it.conversationId == convId }
        if (mine.isNotEmpty()) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            sb.appendLine("durable continuations armed (they WILL fire even if the app closes):")
            for (c in mine) {
                val inSec = ((c.fireAt - System.currentTimeMillis()).coerceAtLeast(0)) / 1000
                sb.appendLine("- in ${inSec}s (at ${fmt.format(Date(c.fireAt))}): ${c.action.take(150)}")
            }
        }
        return sb.toString()
    }

    /**
     * r1418 — the durable memory WOKE UP: a scheduled continuation's alarm fired
     * (process may have been dead and even rebooted — the row survived in
     * SQLite; SharinganBootReceiver / TaskMemory.rescheduleAll re-armed it).
     *
     * Appends a [SHARINGAN MEMORY] continuation message to the SAME conversation
     * (the LLM sees the original goal + all prior steps in history) and re-enters
     * runLoop. If another task is mid-run, the resume is RE-ARMED (busy-retry,
     * M6) — a pending step is never dropped, never silently lost.
     */
    fun resumeScheduled(context: Context, continuationId: Long) {
        init(context)
        scope.launch {
            val ctx = appContext ?: return@launch
            val row = runCatching { MemoryRepository.continuation(ctx, continuationId) }.getOrNull()
                ?: return@launch
            if (row.status != "scheduled") return@launch

            if (_state.value.busy) {
                val attempts = (continuationRetries[continuationId] ?: 0) + 1
                if (attempts <= TaskMemory.MAX_RETRIES) {
                    continuationRetries[continuationId] = attempts
                    TaskMemory.armAlarm(ctx, continuationId,
                        System.currentTimeMillis() + TaskMemory.RETRY_DELAY_MS)
                    Log.i(TAG, "continuation #$continuationId deferred (busy), retry $attempts/${TaskMemory.MAX_RETRIES}")
                } else {
                    continuationRetries.remove(continuationId)
                    MemoryRepository.setContinuationStatus(ctx, continuationId, "cancelled")
                    val msg = ctx.getString(R.string.sharingan_continuation_giveup, row.action.take(140))
                    MemoryRepository.addMessage(ctx, row.conversationId, "assistant", msg)
                    SharinganTaskNotifier.taskDone(ctx, msg)
                }
                return@launch
            }
            continuationRetries.remove(continuationId)
            MemoryRepository.setContinuationStatus(ctx, continuationId, "fired")
            SharinganTaskNotifier.cancelScheduled(ctx)
            TaskMemory.holdWake(ctx)
            cancelled = false
            try {
                val convId = row.conversationId
                if (convId <= 0) return@launch
                MemoryRepository.addMessage(ctx, convId, "user",
                    ctx.getString(R.string.sharingan_continuation_prompt,
                        row.seconds, row.goal.take(300), row.action.take(300)))
                MemoryRepository.saveWorking(ctx, convId, goal = row.goal, stepsJson = "[]", status = "running")
                publish(ctx, convId, AgentStatus.Thinking)
                SharinganTaskNotifier.taskStarted(ctx,
                    ctx.getString(R.string.sharingan_continuation_resumed, row.action.take(80)))
                runLoop(ctx, convId, row.goal)
            } catch (t: Throwable) {
                Log.e(TAG, "continuation resume crashed", t)
                runCatching {
                    MemoryRepository.addMessage(ctx, row.conversationId, "assistant",
                        "⚠️ " + (t.message ?: t.javaClass.simpleName))
                }
            } finally {
                val last = if (row.conversationId > 0) MemoryRepository.messages(ctx, row.conversationId)
                    .lastOrNull { it.role == "assistant" && it.toolName == null } else null
                SharinganTaskNotifier.taskDone(
                    ctx,
                    last?.content?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.sharingan_continuation_no_result))
                MemoryRepository.setContinuationStatus(ctx, continuationId, "done")
                runCatching {
                    last?.content?.let { c ->
                        MemoryRepository.addExperience(ctx, row.goal,
                            if (c.startsWith("⚠️")) "fail" else "success",
                            "scheduled continuation «${row.action.take(120)}» → ${c.take(220)}")
                    }
                }
                TaskMemory.dropWake()
            }
        }
    }

    private suspend fun executeToolCall(context: Context, convId: Long, call: ToolCall, steps: JSONArray) {
        // Tool Router: only registered tools are executed - unknown names are rejected.
        val tool = ToolRegistry.get(call.name)
        if (tool == null) {
            val msg = "rejected_unknown_tool: ${call.name}"
            MemoryRepository.addMessage(context, convId, "tool", msg, call.name, false)
            steps.put(JSONObject().put("type", "result").put("tool", call.name)
                .put("ok", false).put("output", msg))
            return
        }

        publish(context, convId, AgentStatus.ExecutingTool(call.name))
        // tool-call marker row; content = args JSON (round-trips to the provider)
        MemoryRepository.addMessage(context, convId, "assistant", call.argsJson.toString(), call.name, null)

        val result = withContext(Dispatchers.IO) {
            try {
                tool.run(context, call.argsJson)
            } catch (e: Exception) {
                AgentTools.ToolResult(false, "tool_crash: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        MemoryRepository.addMessage(context, convId, "tool", result.output.take(9000), call.name, result.ok)
        steps.put(JSONObject().put("type", "result").put("tool", call.name)
            .put("ok", result.ok).put("output", result.output.take(1500)))
        val wm = MemoryRepository.working(context, convId)
        MemoryRepository.saveWorking(context, convId, wm?.first ?: "", steps.toString(), "running")
    }

    private suspend fun saveAssistant(context: Context, convId: Long, text: String) {
        MemoryRepository.addMessage(context, convId, "assistant", text)
        publish(context, convId, AgentStatus.Ready)
    }

    private suspend fun saveCancelled(context: Context, convId: Long, steps: JSONArray) {
        MemoryRepository.addMessage(context, convId, "assistant",
            context.getString(R.string.agent_cancelled_note))
        MemoryRepository.saveWorking(context, convId,
            MemoryRepository.working(context, convId)?.first ?: "", steps.toString(), "cancelled")
        publish(context, convId, AgentStatus.Ready)
    }

    private fun publish(context: Context, convId: Long, status: AgentStatus) {
        val msgs = MemoryRepository.messages(context, convId)
        _state.value = AgentUiState(
            msgs.map { it.toItem() },
            status,
            busy = status !is AgentStatus.Ready && status !is AgentStatus.NotConfigured,
            providerReady = providerReady(context),
            conversationId = convId
        )
    }
}

/**
 * Result Verifier: checks REAL tool results before the agent announces success.
 * If any tool step failed, an honest warning is appended to the final answer.
 */
object ResultVerifier {
    fun verify(steps: JSONArray): String {
        var failed = 0
        var total = 0
        for (i in 0 until steps.length()) {
            val o = steps.optJSONObject(i) ?: continue
            if (o.optString("type") == "result") {
                total++
                if (!o.optBoolean("ok", false)) failed++
            }
        }
        return when {
            total == 0 -> ""
            failed == 0 -> ""
            else -> "\n\n⚠️ $failed/$total tool step(s) failed — the result above may be incomplete."
        }
    }
}
