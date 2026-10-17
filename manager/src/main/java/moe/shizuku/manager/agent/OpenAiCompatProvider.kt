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
 * OpenAI-compatible chat provider (works with OpenAI and any compatible endpoint:
 * /chat/completions with tools/function-calling).
 * The API key is only sent in the Authorization header and never logged.
 */
object OpenAiCompatProvider : LlmProvider {

    override val id = ApiKeysStore.PROVIDER_OPENAI_COMPAT

    internal val JSON = "application/json; charset=utf-8".toMediaType()
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Shared message builder (also used by Cloudflare/OpenRouter/AgentRouter).
     *
     * r1420 — UNIQUE TOOL CALL IDS (field error: "Duplicate tool response for
     * tool_call_id='call_shell_command'"). The old builder synthesized the id
     * from the tool NAME, so the SECOND call of the same tool inside one
     * history window (dumpsys then any other shell command — a normal second
     * round) produced two identical tool_call ids in one request and every
     * OpenAI-compatible gateway hard-rejected it with HTTP 400, killing the
     * task mid-run. Now:
     *  - every assistant tool-call marker gets a UNIQUE id ("call_<tool>_<n>"),
     *  - each tool row is paired with the OPEN marker of the same tool that
     *    directly precedes it (DB order is strictly marker→result pairs, and
     *    executeToolCall writes them back-to-back),
     *  - an ORPHAN tool row (its marker evicted by the history window) is
     *    flattened to plain assistant text — an unpaired tool message is never
     *    emitted, so "exactly one response per tool_call" always holds.
     */
    internal fun buildMessages(systemPrompt: String, history: List<LlmMessage>): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        val seq = HashMap<String, Int>()                  // tool name -> occurrence index
        val open = HashMap<String, ArrayDeque<String>>()  // tool name -> ids awaiting their result
        for (m in history) {
            when (m.role) {
                "user" -> messages.put(JSONObject().put("role", "user").put("content", m.text))
                "assistant" -> {
                    if (m.toolName != null) {
                        val n = seq[m.toolName] ?: 0
                        seq[m.toolName] = n + 1
                        val id = "call_${m.toolName}_$n"
                        open.getOrPut(m.toolName) { ArrayDeque() }.addLast(id)
                        val args = runCatching { JSONObject(m.toolArgsJson ?: "{}") }.getOrDefault(JSONObject())
                        messages.put(JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONObject.NULL)
                            .put("tool_calls", JSONArray().put(JSONObject()
                                .put("id", id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", m.toolName)
                                    .put("arguments", args.toString())))))
                    } else {
                        messages.put(JSONObject().put("role", "assistant").put("content", m.text))
                    }
                }
                "tool" -> {
                    val id = open[m.toolName ?: "tool"]?.removeFirstOrNull()
                    if (id == null) {
                        // Orphan result (marker outside the window): flatten to
                        // assistant text instead of emitting an unpaired tool row.
                        messages.put(JSONObject()
                            .put("role", "assistant")
                            .put("content", "[${m.toolName ?: "tool"}] ${m.text.take(2000)}"))
                    } else {
                        messages.put(JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", m.text))
                    }
                }
            }
        }
        return messages
    }

    /** Shared request-body builder (also used by CloudflareProvider). */
    internal fun buildBody(model: String, messages: JSONArray, tools: List<ToolSpec>): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parametersJson)))
            body.put("tools", arr)
        }
        return body
    }

    /** Executes a prepared body against {base}/chat/completions with a Bearer token. */
    internal fun execute(base: String, apiKey: CharArray, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${String(apiKey)}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            return Pair(resp.code, resp.body?.string().orEmpty())
        }
    }

    override fun testConnection(apiKey: CharArray, model: String, baseUrl: String?): LlmDecision =
        chat("You are a connection test. Answer with exactly: OK", emptyList(), emptyList(), apiKey, model, baseUrl)

    override fun chat(systemPrompt: String, history: List<LlmMessage>, tools: List<ToolSpec>,
                      apiKey: CharArray, model: String, baseUrl: String?): LlmDecision {
        val base = (baseUrl ?: "https://api.openai.com/v1").trimEnd('/')
        val messages = buildMessages(systemPrompt, history)
        val body = buildBody(model, messages, tools)

        val (code, text) = execute(base, apiKey, body)
        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(text).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code: ${text.take(300)}")
            return LlmDecision.Error(msg, code)
        }
        return parse(text)
    }

    internal fun parse(raw: String): LlmDecision {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices") ?: return LlmDecision.Error("no choices", 200)
        if (choices.length() == 0) return LlmDecision.Error("empty choices", 200)
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: return LlmDecision.Error("no message", 200)

        val calls = ArrayList<ToolCall>()
        val tcs = msg.optJSONArray("tool_calls")
        if (tcs != null) {
            for (i in 0 until tcs.length()) {
                val fn = tcs.getJSONObject(i).optJSONObject("function") ?: continue
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                calls.add(ToolCall(fn.getString("name"), args))
            }
        }
        val content = msg.optString("content", "")
        return if (calls.isNotEmpty()) LlmDecision.ToolCalls(calls)
        else LlmDecision.Text(content.ifBlank { "(empty reply)" })
    }
}
