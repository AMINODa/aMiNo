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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    private const val MAX_TOOL_ROUNDS = 6
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

    fun send(context: Context, userText: String) {
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
            var convId = _state.value.conversationId
            if (convId <= 0) convId = MemoryRepository.createConversation(ctx, text)
            val msgs = MemoryRepository.messages(ctx, convId)
            if (msgs.none { it.role == "user" }) MemoryRepository.touchConversation(ctx, convId, text)
            MemoryRepository.addMessage(ctx, convId, "user", text)
            MemoryRepository.saveWorking(ctx, convId, goal = text, stepsJson = "[]", status = "running")
            publish(ctx, convId, AgentStatus.Thinking)

            runLoop(ctx, convId, text)
        }
    }

    private suspend fun runLoop(context: Context, convId: Long, userText: String) {
        val provider: LlmProvider = when (ApiKeysStore.provider(context)) {
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

                val history = toLlmHistory(MemoryRepository.messages(context, convId, HISTORY_LIMIT))
                val systemPrompt = AgentIdentity.systemPrompt(context, AgentIdentity.contextBlock(context, userText))
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
     */
    private fun toLlmHistory(rows: List<ChatMessage>): List<LlmMessage> =
        rows.map { m ->
            when {
                m.role == "assistant" && m.toolName != null ->
                    LlmMessage("assistant", m.content.ifBlank { "{}" }, m.toolName, m.content.ifBlank { "{}" }, null)
                else -> LlmMessage(m.role, m.content, m.toolName, null, m.toolOk)
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

        MemoryRepository.addMessage(context, convId, "tool", result.output.take(6000), call.name, result.ok)
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
