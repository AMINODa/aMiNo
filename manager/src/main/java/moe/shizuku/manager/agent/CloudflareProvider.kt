package moe.shizuku.manager.agent

import moe.shizuku.manager.keys.ApiKeysStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloudflare Workers AI provider (r1377) - aMiNo's DEFAULT provider.
 *
 * Uses the official OpenAI-compatible REST endpoint (no geo-restriction, works
 * from every region - unlike Gemini which is blocked in many countries):
 *
 *   POST https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1/chat/completions
 *   Authorization: Bearer {api_token}
 *
 * The user pastes a Workers AI API token (dash.cloudflare.com -> Workers AI)
 * and their Account ID. The token is ONLY sent in the Authorization header and
 * is never logged. Error responses use Cloudflare's own JSON shape:
 *   {"result":null,"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}
 *
 * Models: @cf/meta/llama-3.1-8b-instruct (fast default), @cf/meta/llama-3.3-70b-instruct-fp8-fast,
 * @cf/openai/gpt-oss-120b, @cf/qwen/qwen2.5-32b-instruct (strong Arabic support).
 * If the selected model rejects the tools parameter, the request is retried once
 * WITHOUT tools so the agent still answers (honestly, without tool actions).
 */
object CloudflareProvider : LlmProvider {

    override val id = ApiKeysStore.PROVIDER_CLOUDFLARE

    private const val PLACEHOLDER = "ACCOUNT_ID"

    override fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision =
        chat("You are a connection test. Answer with exactly: OK", emptyList(), emptyList(), apiKey, model, baseUrl)

    override fun chat(systemPrompt: String, history: List<LlmMessage>, tools: List<ToolSpec>,
                      apiKey: CharArray, model: String, baseUrl: String?): LlmDecision {
        val base = (baseUrl ?: ApiKeysStore.PROVIDERS.first { it.id == id }.defaultBaseUrl ?: "").trimEnd('/')

        // The template ships with an ACCOUNT_ID placeholder - a real request without
        // replacing it would fail with Cloudflare error 7003, so surface a clear instruction.
        if (base.contains(PLACEHOLDER)) {
            return LlmDecision.Error(
                "Cloudflare setup incomplete: replace ACCOUNT_ID in the Base URL with your Cloudflare Account ID " +
                "(dash.cloudflare.com > Workers & Pages > right side 'Account ID'). " +
                "/ إعداد Cloudflare غير مكتمل: استبدل ACCOUNT_ID في الرابط بمعرف حسابك.", 0)
        }

        val messages = OpenAiCompatProvider.buildMessages(systemPrompt, history)

        // First attempt: full tool schema (model decides when to call a tool).
        var (code, text) = OpenAiCompatProvider.execute(base, apiKey, OpenAiCompatProvider.buildBody(model, messages, tools))

        // Some Workers AI models reject the tools parameter outright. Retry once
        // without tools so the user still gets a real answer instead of an error.
        if (code !in 200..299 && tools.isNotEmpty() && looksLikeToolsRejection(code, text)) {
            val (code2, text2) = OpenAiCompatProvider.execute(base, apiKey, OpenAiCompatProvider.buildBody(model, messages, emptyList()))
            code = code2
            text = text2
        }

        if (code !in 200..299) return error(code, text)
        return OpenAiCompatProvider.parse(text)
    }

    /** True when the failure looks like "this model does not support tools". */
    private fun looksLikeToolsRejection(code: Int, text: String): Boolean {
        if (code !in 400..499) return false
        val t = text.lowercase()
        return t.contains("tool") || t.contains("function") || t.contains("invalid argument") ||
                t.contains("unsupported") || t.contains("not supported") || t.contains("unknown keyword")
    }

    /** Parses Cloudflare's error envelope; falls back to OpenAI's error shape. */
    private fun error(code: Int, text: String): LlmDecision {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null && root.optBoolean("success", true) == false) {
            val errs = root.optJSONArray("errors")
            if (errs != null && errs.length() > 0) {
                val parts = ArrayList<String>()
                for (i in 0 until errs.length()) {
                    val e = errs.optJSONObject(i) ?: continue
                    parts.add("[${e.optInt("code")}] ${e.optString("message")}")
                }
                if (parts.isNotEmpty()) return LlmDecision.Error(parts.joinToString(" | "), code)
            }
        }
        val msg = runCatching {
            root?.getJSONObject("error")?.getString("message")
        }.getOrNull() ?: "HTTP $code: ${text.take(300)}"
        return LlmDecision.Error(msg, code)
    }
}
