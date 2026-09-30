package moe.shizuku.manager.agent

import moe.shizuku.manager.keys.ApiKeysStore
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * AgentRouter (agentrouter.org) — verified against the CURRENT public state of
 * the service before this provider was added (r1387, researched as the user
 * required — no assumptions):
 *
 *  - AgentRouter is a NewAPI/OneAPI-based AI gateway reselling access to
 *    Claude / DeepSeek / GLM / GPT models through an OpenAI-compatible wire
 *    format (community SDK + multiple public projects all document
 *    `baseURL = https://agentrouter.org/v1` and `/chat/completions`);
 *  - auth is a standard Bearer API key (sk-…) issued by their dashboard;
 *  - the site sits behind an Aliyun WAF that challenges generic HTTP-client
 *    User-Agents — the community SDK's own default header
 *    ("QwenCode/0.2.0 (linux; x64)") is used here for parity with the known-
 *    working clients;
 *  - model ids follow the upstream names, e.g. claude-opus-4-8, gpt-5.5,
 *    glm-5.2, kimi-k3 (the default below is the SDK's flagship default).
 *
 * The wire protocol is exactly OpenAI's chat/completions with function
 * calling, so the request/response machinery of [OpenAiCompatProvider] is
 * reused verbatim — only the headers and defaults differ. The API key is only
 * ever sent in the Authorization header and never logged.
 */
object AgentRouterProvider : LlmProvider {

    override val id = ApiKeysStore.PROVIDER_AGENTROUTER

    /** Documented working default of the agentrouter.org community SDK. */
    private const val USER_AGENT = "QwenCode/0.2.0 (linux; x64)"

    override fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision =
        chat("You are a connection test. Answer with exactly: OK", emptyList(), emptyList(), apiKey, model, baseUrl)

    override fun chat(systemPrompt: String, history: List<LlmMessage>, tools: List<ToolSpec>,
                      apiKey: CharArray, model: String, baseUrl: String?): LlmDecision {
        val base = (baseUrl ?: "https://agentrouter.org/v1").trimEnd('/')
        val messages = OpenAiCompatProvider.buildMessages(systemPrompt, history)
        val body = OpenAiCompatProvider.buildBody(model, messages, tools)

        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${String(apiKey)}")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(OpenAiCompatProvider.JSON))
            .build()

        val (code, text) = OpenAiCompatProvider.client.newCall(request).execute().use { resp ->
            Pair(resp.code, resp.body?.string().orEmpty())
        }
        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(text).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code: ${text.take(300)}")
            return LlmDecision.Error(msg, code)
        }
        return OpenAiCompatProvider.parse(text)
    }
}
