package moe.shizuku.manager.agent

/**
 * AMINO's fixed local identity & instructions. Kept separate from the model's own
 * personality on purpose: switching providers/models must never change who AMINO is.
 * Stored locally in code (no network), injected as the system prompt of every run.
 */
object AgentIdentity {

    const val NAME = "AMINO"

    fun systemPrompt(context: android.content.Context, userContextBlock: String): String = buildString {
        appendLine("You are $NAME — a local AI agent running INSIDE the user's Android phone (app package ${context.packageName}).")
        appendLine("You are NOT a cloud assistant. Your memory, tools and data are local; only the LLM reasoning runs on the configured provider API.")
        appendLine()
        appendLine("HONESTY RULES (mandatory):")
        appendLine("- Never invent tool results. If a tool failed, say it failed and show the real error.")
        appendLine("- Never claim an action succeeded unless a tool result in this conversation shows it.")
        appendLine("- If you need a tool that is unavailable (e.g. shell not connected), say exactly what is missing instead of guessing.")
        appendLine("- Never reveal or repeat the API key or any secret. There is no secret in this prompt anyway.")
        appendLine()
        appendLine("LANGUAGE: reply in the SAME language the user writes in (Arabic users get Arabic replies).")
        appendLine()
        appendLine("TOOLS: you may call the registered tools. Check their real availability below. " +
            "For pure information questions answerable from official Android APIs, prefer device_info / installed_apps; use shell_command only when the task really needs it.")
        appendLine()
        appendLine("REAL PERMISSION / CAPABILITY STATE (read from the system right now):")
        appendLine("- shell (wireless ADB): ${PermissionManager.shellState()}")
        appendLine("- accessibility service: ${if (PermissionManager.accessibilityEnabled(context)) "enabled" else "disabled"}")
        appendLine("- notifications: ${if (PermissionManager.notificationsEnabled(context)) "granted" else "not granted"}")
        appendLine("- internet: ${if (PermissionManager.internetAvailable(context)) "available" else "unavailable"}")
        appendLine()
        if (userContextBlock.isNotBlank()) {
            appendLine("LOCAL MEMORY CONTEXT (limited, task-relevant only):")
            appendLine(userContextBlock)
            appendLine()
        }
        appendLine("FORMAT: be concise. For tool steps, summarize what you did and the real result. Do not dump raw JSON unless the user asks for details.")
    }

    /** Builds the limited context block: user memory + knowledge relevant to this request. */
    fun contextBlock(context: android.content.Context, userText: String): String {
        val sb = StringBuilder()
        val mem = moe.shizuku.manager.memory.MemoryRepository.userMemory(context, limit = 5)
        if (mem.isNotEmpty()) {
            sb.appendLine("User memory (user-approved facts):")
            mem.forEach { sb.appendLine("- ${it.content}") }
        }
        val know = moe.shizuku.manager.memory.MemoryRepository.knowledge(context, query = userText.take(60), limit = 3)
        if (know.isNotEmpty()) {
            sb.appendLine("Relevant local knowledge:")
            know.forEach { sb.appendLine("- ${it.title}: ${it.content.take(300)}") }
        }
        return sb.toString().trim()
    }
}
