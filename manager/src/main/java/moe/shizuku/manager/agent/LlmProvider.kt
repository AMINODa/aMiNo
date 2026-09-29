package moe.shizuku.manager.agent

import org.json.JSONObject

/** One message in the agent conversation (role: "user" | "assistant" | "tool"). */
data class LlmMessage(
    val role: String,
    val text: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,   // for assistant tool-call messages
    val toolOk: Boolean? = null
)

/** A tool the model may call. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: JSONObject   // OpenAPI-style parameters object
)

/** What the model decided for one turn. */
sealed class LlmDecision {
    data class Text(val text: String) : LlmDecision()
    data class ToolCalls(val calls: List<ToolCall>) : LlmDecision()
    data class Error(val message: String, val httpCode: Int) : LlmDecision()
}

data class ToolCall(val name: String, val argsJson: JSONObject)

/**
 * Provider-independent LLM interface. AMINO's agent is not bound to one provider;
 * implementations translate the neutral message list to the provider's wire format.
 * Implementations MUST NOT log the API key.
 */
interface LlmProvider {

    /** Provider id, one of ApiKeysStore.PROVIDER_*. */
    val id: String

    /**
     * Blocking call (run on a background dispatcher). Returns the model's decision.
     * May throw java.io.IOException for network failures - the orchestrator converts
     * that into an honest error message.
     */
    fun chat(
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolSpec>,
        apiKey: CharArray,
        model: String,
        baseUrl: String?
    ): LlmDecision

    /** Cheap real connectivity test used by the Keys page (returns provider error text if any). */
    fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision
}
