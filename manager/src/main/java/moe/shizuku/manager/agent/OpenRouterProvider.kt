package moe.shizuku.manager.agent

import moe.shizuku.manager.keys.ApiKeysStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenRouter provider (r1380) — one key, hundreds of models (OpenAI-compatible).
 *
 *   POST https://openrouter.ai/api/v1/chat/completions
 *   Authorization: Bearer {key}          (key format: sk-or-v1-...)
 *   HTTP-Referer / X-Title: recommended app attribution headers.
 *
 * OpenRouter works worldwide (no geo-block) and offers free-tier models
 * (ids ending in ":free"). The wire format is OpenAI's, so message/body building
 * and response parsing are reused from OpenAiCompatProvider; only the request
 * headers differ. If a model rejects the tools parameter, the request is retried
 * once WITHOUT tools so the agent still answers.
 *
 * Verified live (r1380): models catalog + 401 {"error":{"message":"User not found.","code":401}}
 * on a bad key — endpoint and error shape confirmed before shipping.
 */
object OpenRouterProvider : LlmProvider {

    override val id = ApiKeysStore.PROVIDER_OPENROUTER

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision =
        chat("You are a connection test. Answer with exactly: OK", emptyList(), emptyList(), apiKey, model, baseUrl)

    override fun chat(systemPrompt: String, history: List<LlmMessage>, tools: List<ToolSpec>,
                      apiKey: CharArray, model: String, baseUrl: String?): LlmDecision {
        val base = (baseUrl ?: ApiKeysStore.PROVIDERS.first { it.id == id }.defaultBaseUrl
            ?: "https://openrouter.ai/api/v1").trimEnd('/')
        val messages = OpenAiCompatProvider.buildMessages(systemPrompt, history)

        var (code, text) = execute(base, apiKey, OpenAiCompatProvider.buildBody(model, messages, tools))

        // Some models reject the tools parameter outright. Retry once without tools
        // so the user still gets a real answer instead of an error.
        if (code !in 200..299 && tools.isNotEmpty() && looksLikeToolsRejection(code, text)) {
            val (code2, text2) = execute(base, apiKey, OpenAiCompatProvider.buildBody(model, messages, emptyList()))
            code = code2
            text = text2
        }

        if (code !in 200..299) return error(code, text)
        return OpenAiCompatProvider.parse(text)
    }

    /** OpenAI-shaped request + OpenRouter attribution headers. */
    private fun execute(base: String, apiKey: CharArray, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${String(apiKey)}")
            .header("HTTP-Referer", "https://github.com/AMINODa/aMiNo")
            .header("X-Title", "aMiNo")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            return Pair(resp.code, resp.body?.string().orEmpty())
        }
    }

    private fun looksLikeToolsRejection(code: Int, text: String): Boolean {
        if (code !in 400..499) return false
        val t = text.lowercase()
        return t.contains("tool") || t.contains("function") || t.contains("invalid argument") ||
                t.contains("unsupported") || t.contains("not supported") || t.contains("unknown keyword")
    }

    /** OpenRouter error shape: {"error":{"message":...,"code":401}} (verified live). */
    private fun error(code: Int, text: String): LlmDecision {
        val msg = runCatching {
            JSONObject(text).getJSONObject("error").getString("message")
        }.getOrNull() ?: "HTTP $code: ${text.take(300)}"
        return LlmDecision.Error(msg, code)
    }
}
