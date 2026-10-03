package moe.shizuku.manager.agent

import moe.shizuku.manager.keys.ApiKeysStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Gemini provider (generativelanguage.googleapis.com).
 *
 * Wire format (verified against the official API):
 *  - systemInstruction.parts[0].text
 *  - contents[]: role "user" | "model"; tool results are sent as role "user" with
 *    parts[0].functionResponse {name, response:{result}}
 *  - tools[0].functionDeclarations[]: {name, description, parameters}
 *  - answer parts: {text: "..."} or {functionCall: {name, args}}
 *
 * The API key is only sent in the X-goog-api-key header. It is never logged and
 * never placed inside prompts or tool payloads.
 */
object GeminiProvider : LlmProvider {

    override val id = ApiKeysStore.PROVIDER_GEMINI

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"

    override fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision =
        chat("You are a connection test. Answer with exactly: OK", emptyList(), emptyList(), apiKey, model, baseUrl)

    override fun chat(systemPrompt: String, history: List<LlmMessage>, tools: List<ToolSpec>,
                      apiKey: CharArray, model: String, baseUrl: String?): LlmDecision {
        val contents = JSONArray()
        for (m in history) {
            when (m.role) {
                "user" -> contents.put(obj("role", "user").put("parts", arr(obj("text", m.text))))
                "assistant" -> {
                    if (m.toolName != null) {
                        // model's tool call turn
                        val args = runCatching { JSONObject(m.toolArgsJson ?: "{}") }.getOrDefault(JSONObject())
                        contents.put(obj("role", "model").put("parts", arr(
                            JSONObject().put("functionCall", JSONObject().put("name", m.toolName).put("args", args))
                        )))
                    } else {
                        contents.put(obj("role", "model").put("parts", arr(obj("text", m.text))))
                    }
                }
                "tool" -> {
                    // tool result turn: Gemini expects role "user" with functionResponse
                    val result = JSONObject().put("result", m.text).put("ok", m.toolOk == true)
                    contents.put(obj("role", "user").put("parts", arr(
                        JSONObject().put("functionResponse", JSONObject()
                            .put("name", m.toolName ?: "tool")
                            .put("response", result))
                    )))
                }
            }
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", arr(JSONObject().put("text", systemPrompt))))
        if (contents.length() > 0) body.put("contents", contents)
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            for (t in tools) decls.put(
                JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parametersJson)
            )
            body.put("tools", arr(JSONObject().put("functionDeclarations", decls)))
        }
        // r1418 (M8): 2048 silently truncated long self-contained reports
        // (notification replies MUST be complete) — raised + finishReason now
        // surfaces truncation honestly instead of mid-sentence cuts.
        body.put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 4096))

        val base = (baseUrl?.trim()?.trimEnd('/')).takeIf { !it.isNullOrBlank() } ?: DEFAULT_BASE
        val url = "$base/models/${model}:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("X-goog-api-key", String(apiKey))
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault("HTTP ${resp.code}: ${text.take(300)}")
                return LlmDecision.Error(msg, resp.code)
            }
            return parse(text)
        }
    }

    internal fun parse(raw: String): LlmDecision {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
            ?: return LlmDecision.Error(root.optJSONObject("error")?.optString("message", "empty response") ?: "empty response", 200)
        if (candidates.length() == 0) return LlmDecision.Error("empty candidates", 200)
        val parts = candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts")
            ?: return LlmDecision.Error("no content parts", 200)

        val texts = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                calls.add(ToolCall(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
            } else {
                val t = p.optString("text", "")
                if (t.isNotEmpty()) texts.append(t)
            }
        }
        return if (calls.isNotEmpty()) LlmDecision.ToolCalls(calls)
        else LlmDecision.Text(
            texts.toString().ifBlank { "(empty reply)" } +
                // r1418 (M8): honest truncation marker — the user must know when
                // a reply was cut by the model's token limit.
                (if (candidates.getJSONObject(0).optString("finishReason", "") == "MAX_TOKENS")
                    "\n\n⚠️ (reply cut by the model's token limit — may be incomplete)" else "")
        )
    }

    private fun obj(k: String, v: String) = JSONObject().put(k, v)
    private fun arr(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }
}
