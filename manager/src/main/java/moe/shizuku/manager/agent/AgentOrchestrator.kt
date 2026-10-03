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

    /**
     * r1419 — DURATION GUARDIAN state. The field truth (user tried r1418 and the
     * 10s recording STILL never stopped) proved the timed step cannot depend on
     * the model VOLUNTARILY calling task_wait: a text promise, a 12-round UI
     * burnout or a mid-task provider error all ended the task with the pending
     * step dead. The guardian arms a REAL continuation row (SQLite + alarm) at
     * send time and promotes it at task end if the model never honored the
     * timing — the pending step fires at the deadline NO MATTER WHAT.
     */
    data class TimedGuard(
        val rowId: Long,
        val conversationId: Long,
        val durationSec: Int,
        @Volatile var fireAt: Long,
        @Volatile var taskWaits: Int = 0,
        @Volatile var toolRounds: Int = 0
    )

    @Volatile
    private var activeGuard: TimedGuard? = null

    /** Called by AgentTools.taskWait — a real task_wait call means the model
     *  honored the timing; the guardian then stands down at task end. */
    fun noteTaskWait() {
        activeGuard?.taskWaits?.let { w -> activeGuard?.taskWaits = w + 1 }
    }

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
            var guard: TimedGuard? = null
            try {
                var convId = _state.value.conversationId
                if (convId <= 0) convId = MemoryRepository.createConversation(ctx, text)
                val msgs = MemoryRepository.messages(ctx, convId)
                if (msgs.none { it.role == "user" }) MemoryRepository.touchConversation(ctx, convId, text)
                MemoryRepository.addMessage(ctx, convId, "user", text)

                // r1419 — DURATION GUARDIAN: detect timing locally BEFORE the loop.
                when (val plan = TimedCommandParser.detect(text)) {
                    is TimedCommandParser.DelayedStart -> {
                        // «بعد 10 ثواني افعل X» — nothing runs now; the whole
                        // command fires at the deadline (SQLite + alarm, survives
                        // app close / process death / reboot).
                        scheduleDelayedStart(ctx, convId, text, plan.seconds)
                        return@launch
                    }
                    is TimedCommandParser.DurationStop -> {
                        // Start now; the deferred stop is ALREADY armed in durable
                        // memory — even a text promise cannot kill it anymore.
                        guard = armGuard(ctx, convId, text, plan.seconds)
                    }
                    null -> {}
                }

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
                finalizeGuard(ctx, guard, cancelled)
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
            var guard: TimedGuard? = null
            var delayedOnly = false
            try {
                convId = _state.value.conversationId
                if (convId <= 0) convId = MemoryRepository.createConversation(ctx, text)
                val msgs = MemoryRepository.messages(ctx, convId)
                if (msgs.none { it.role == "user" }) MemoryRepository.touchConversation(ctx, convId, text)
                MemoryRepository.addMessage(ctx, convId, "user", text)

                // r1419 — DURATION GUARDIAN (the panel is the user's real path):
                // detect timing locally BEFORE the loop; never trust the model
                // to represent the timed step by itself again.
                when (val plan = TimedCommandParser.detect(text)) {
                    is TimedCommandParser.DelayedStart -> {
                        scheduleDelayedStart(ctx, convId, text, plan.seconds)
                        delayedOnly = true
                        return@launch
                    }
                    is TimedCommandParser.DurationStop -> {
                        guard = armGuard(ctx, convId, text, plan.seconds)
                    }
                    null -> {}
                }

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
                // r1419 — guardian verdict BEFORE reading the final row: a
                // promoted guard appends its own honest note row, which then
                // becomes the taskDone notification (visible proof of memory).
                finalizeGuard(ctx, guard, cancelled)
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
                // (skipped for delayed-start — nothing executed yet)
                if (!delayedOnly) runCatching {
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

    /**
     * r1419 — arms the Duration Guardian: a REAL continuation row (status
     * 'guard') in SQLite + an AlarmManager wake-up at t0 + N. While the task
     * runs it is INVISIBLE to the runLoop park check (different status). At
     * task end [finalizeGuard] decides its fate.
     */
    private fun armGuard(ctx: Context, convId: Long, goal: String, seconds: Int): TimedGuard? = runCatching {
        val fireAt = System.currentTimeMillis() + seconds * 1000L
        val rowId = MemoryRepository.addContinuation(ctx, convId, goal,
            "Execute the deferred completion step of the original command NOW " +
                "(screen_read FIRST to verify the real state, then act — e.g. stop the recording / " +
                "finalize what was started), then report the real result.", seconds, fireAt)
        MemoryRepository.setContinuationStatus(ctx, rowId, "guard")
        TaskMemory.armAlarm(ctx, rowId, fireAt)
        val g = TimedGuard(rowId, convId, seconds, fireAt)
        activeGuard = g
        Log.i(TAG, "duration guardian armed #$rowId in ${seconds}s")
        g
    }.getOrNull()

    /** r1419 — «بعد N وحدة + أمر»: defer the WHOLE command (nothing runs now). */
    private fun scheduleDelayedStart(ctx: Context, convId: Long, text: String, seconds: Int) {
        runCatching {
            val fireAt = System.currentTimeMillis() + seconds * 1000L
            val rowId = MemoryRepository.addContinuation(ctx, convId, text, text, seconds, fireAt)
            MemoryRepository.setContinuationStatus(ctx, rowId, "scheduled")
            TaskMemory.armAlarm(ctx, rowId, fireAt)
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fireAt))
            MemoryRepository.addMessage(ctx, convId, "assistant",
                ctx.getString(R.string.guardian_delayed_note, seconds, at))
            SharinganTaskNotifier.taskScheduled(ctx, text.take(120), fireAt)
            refresh(ctx)
            Log.i(TAG, "delayed start #$rowId in ${seconds}s")
        }.onFailure { Log.w(TAG, "scheduleDelayedStart failed: ${it.message}") }
    }

    /**
     * r1419 — the guardian's verdict at task end. THE NET:
     *  - user cancelled OR the model called task_wait ≥1 time  → stand down
     *    (cancel row + alarm; the timing was honored by the normal path).
     *  - the model ran tools but never scheduled the timed step (text promise,
     *    round burnout, mid-task error) → PROMOTE: the row becomes 'scheduled',
     *    the alarm stays, an honest note row tells the user the stop fires at
     *    HH:MM — the pending step runs even if the app closes next second.
     *  - nothing executed at all (round-0 provider error) → stand down; the
     *    real error is the outcome and there is nothing pending to protect.
     */
    private fun finalizeGuard(ctx: Context, guard: TimedGuard?, userCancelled: Boolean) {
        if (guard == null) return
        if (activeGuard === guard) activeGuard = null
        runCatching {
            when {
                userCancelled || guard.taskWaits > 0 -> {
                    MemoryRepository.setContinuationStatus(ctx, guard.rowId, "cancelled")
                    TaskMemory.cancelAlarm(ctx, guard.rowId)
                    Log.i(TAG, "guard #${guard.rowId} stands down (taskWaits=${guard.taskWaits}, cancelled=$userCancelled)")
                }
                guard.toolRounds > 0 -> {
                    val now = System.currentTimeMillis()
                    if (guard.fireAt <= now + 1_000) guard.fireAt = now + 2_000
                    MemoryRepository.setContinuationFireAt(ctx, guard.rowId, guard.fireAt)
                    MemoryRepository.setContinuationStatus(ctx, guard.rowId, "scheduled")
                    TaskMemory.armAlarm(ctx, guard.rowId, guard.fireAt)
                    val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(guard.fireAt))
                    MemoryRepository.addMessage(ctx, guard.conversationId, "assistant",
                        ctx.getString(R.string.guardian_scheduled_note, guard.durationSec, at))
                    Log.i(TAG, "guard #${guard.rowId} PROMOTED — model skipped task_wait; fires at $at")
                }
                else -> {
                    MemoryRepository.setContinuationStatus(ctx, guard.rowId, "cancelled")
                    TaskMemory.cancelAlarm(ctx, guard.rowId)
                    Log.i(TAG, "guard #${guard.rowId} stands down (nothing executed)")
                }
            }
        }.onFailure { Log.w(TAG, "finalizeGuard failed: ${it.message}") }
        runCatching { refresh(ctx) }
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
        // r1419 — park-check guard: only a continuation CREATED BY THIS RUN may
        // park the task. Previously ANY still-scheduled row of the conversation
        // (e.g. a delayed-start waiting to fire) parked every subsequent task
        // after its first tool round — a stale-memory landmine.
        val preExistingScheduledId = runCatching {
            MemoryRepository.newestScheduled(context, convId)?.id
        }.getOrNull()
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
                        // r1419: ONLY rows created by THIS run may park it.
                        val sched = runCatching {
                            MemoryRepository.newestScheduled(context, convId)
                        }.getOrNull()?.takeIf { it.id != preExistingScheduledId }
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
        // r1419 — the guardian's directive, injected EVERY round while its task
        // runs (never persisted into chat rows). The model is told the net
        // exists — compliance rises, and non-compliance no longer matters.
        activeGuard?.takeIf { it.conversationId == convId }?.let { g ->
            sb.appendLine()
            sb.appendLine("[MEMORY DIRECTIVE — timed command, NON-NEGOTIABLE]")
            sb.appendLine("The user's command carries a timed constraint: ${g.durationSec}s anchored at command start.")
            sb.appendLine("After the start action you MUST call task_wait(seconds=${g.durationSec}, then=\"the exact pending step\") BEFORE any final reply.")
            sb.appendLine("If your final reply has no task_wait for this command, the app auto-schedules the pending step itself and reports it — never rely on that; call task_wait.")
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
            // r1419: 'guard' rows (Duration Guardian, process died mid-task) are
            // also fireable — the alarm is their only remaining lifeline.
            if (row.status != "scheduled" && row.status != "guard") return@launch

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
        // r1419 — the guardian counts REAL executed tool rounds (a text promise
        // with zero executed rounds never arms a phantom stop).
        if (tool != null) activeGuard?.takeIf { it.conversationId == convId }?.toolRounds++
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
