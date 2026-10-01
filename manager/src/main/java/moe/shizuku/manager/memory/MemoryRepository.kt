package moe.shizuku.manager.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

/** One chat message as stored in the local database. */
data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: String,          // "user" | "assistant" | "tool"
    val content: String,
    val toolName: String? = null,
    val toolOk: Boolean? = null,
    val createdAt: Long
)

data class Conversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class UserMemoryItem(val id: Long, val content: String, val updatedAt: Long)
data class KnowledgeItem(val id: Long, val title: String, val content: String, val createdAt: Long)

/** v1.3: one episodic record — a task the agent ran, its outcome, and the lesson kept. */
data class ExperienceItem(
    val id: Long, val task: String, val outcome: String, val lessons: String,
    val skillId: String?, val createdAt: Long
)

/**
 * Local-first memory repository. Everything reads/writes the on-device SQLite db.
 * Nothing here talks to the network.
 */
object MemoryRepository {

    fun db(context: Context): AminoDb = AminoDb.get(context)

    // ---------- Conversation Memory ----------

    fun createConversation(context: Context, title: String): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title.take(60))
            put("created_at", now)
            put("updated_at", now)
        }
        return db(context).writableDatabase.insert(AminoDb.T_CONVERSATIONS, null, cv)
    }

    fun touchConversation(context: Context, conversationId: Long, newTitle: String? = null) {
        val cv = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
            newTitle?.let { put("title", it.take(60)) }
        }
        db(context).writableDatabase.update(AminoDb.T_CONVERSATIONS, cv, "id=?", arrayOf(conversationId.toString()))
    }

    fun listConversations(context: Context, query: String? = null): List<Conversation> {
        val sel = if (!query.isNullOrBlank()) "title LIKE ?" else null
        val args = if (!query.isNullOrBlank()) arrayOf("%$query%") else null
        return db(context).readableDatabase.query(
            AminoDb.T_CONVERSATIONS, null, sel, args, null, null, "updated_at DESC"
        ).use { it.toList { c -> Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)) } }
    }

    fun deleteConversation(context: Context, conversationId: Long) {
        val w = db(context).writableDatabase
        w.delete(AminoDb.T_MESSAGES, "conversation_id=?", arrayOf(conversationId.toString()))
        w.delete(AminoDb.T_WORKING_MEMORY, "conversation_id=?", arrayOf(conversationId.toString()))
        w.delete(AminoDb.T_CONVERSATIONS, "id=?", arrayOf(conversationId.toString()))
    }

    fun addMessage(context: Context, conversationId: Long, role: String, content: String,
                   toolName: String? = null, toolOk: Boolean? = null): ChatMessage {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            toolName?.let { put("tool_name", it) }
            toolOk?.let { put("tool_ok", if (it) 1 else 0) }
            put("created_at", now)
        }
        val id = db(context).writableDatabase.insert(AminoDb.T_MESSAGES, null, cv)
        touchConversation(context, conversationId)
        return ChatMessage(id, conversationId, role, content, toolName, toolOk, now)
    }

    fun messages(context: Context, conversationId: Long, limit: Int = 500): List<ChatMessage> {
        return db(context).readableDatabase.query(
            AminoDb.T_MESSAGES, null, "conversation_id=?", arrayOf(conversationId.toString()),
            null, null, "id DESC", limit.toString()
        ).use { cur ->
            cur.toList { c ->
                ChatMessage(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                    c.getString(4)?.takeIf { it.isNotEmpty() },
                    when (c.getInt(5)) { 1 -> true; 0 -> false; else -> null },
                    c.getLong(6)
                )
            }.reversed()
        }
    }

    fun lastMessage(context: Context, conversationId: Long): ChatMessage? =
        messages(context, conversationId, 1).firstOrNull()

    /** Plain-text export of a conversation (user-initiated share). */
    fun exportConversation(context: Context, conversationId: Long): String {
        val conv = listConversations(context).firstOrNull { it.id == conversationId }
        val sb = StringBuilder("AMINO — ${conv?.title ?: conversationId}\n\n")
        for (m in messages(context, conversationId)) {
            sb.append(when (m.role) {
                "user" -> "👤 "
                "assistant" -> "🤖 "
                else -> "🔧 [${m.toolName}] "
            }).append(m.content).append("\n\n")
        }
        return sb.toString()
    }

    // ---------- User Memory ----------

    fun addUserMemory(context: Context, content: String): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("content", content.trim()); put("created_at", now); put("updated_at", now)
        }
        return db(context).writableDatabase.insert(AminoDb.T_USER_MEMORY, null, cv)
    }

    fun userMemory(context: Context, query: String? = null, limit: Int = 50): List<UserMemoryItem> {
        val sel = if (!query.isNullOrBlank()) "content LIKE ?" else null
        val args = if (!query.isNullOrBlank()) arrayOf("%$query%") else null
        return db(context).readableDatabase.query(
            AminoDb.T_USER_MEMORY, null, sel, args, null, null, "updated_at DESC", limit.toString()
        ).use { it.toList { c -> UserMemoryItem(c.getLong(0), c.getString(1), c.getLong(2)) } }
    }

    fun updateUserMemory(context: Context, id: Long, content: String) {
        db(context).writableDatabase.update(
            AminoDb.T_USER_MEMORY,
            ContentValues().apply { put("content", content.trim()); put("updated_at", System.currentTimeMillis()) },
            "id=?", arrayOf(id.toString())
        )
    }

    fun deleteUserMemory(context: Context, id: Long) {
        db(context).writableDatabase.delete(AminoDb.T_USER_MEMORY, "id=?", arrayOf(id.toString()))
    }

    // ---------- Knowledge / semantic-ish memory ----------

    fun addKnowledge(context: Context, title: String, content: String): Long {
        val cv = ContentValues().apply {
            put("title", title.trim()); put("content", content.trim()); put("created_at", System.currentTimeMillis())
        }
        return db(context).writableDatabase.insert(AminoDb.T_KNOWLEDGE, null, cv)
    }

    fun knowledge(context: Context, query: String? = null, limit: Int = 50): List<KnowledgeItem> {
        val sel = if (!query.isNullOrBlank()) "(title LIKE ? OR content LIKE ?)" else null
        val args = if (!query.isNullOrBlank()) arrayOf("%$query%", "%$query%") else null
        return db(context).readableDatabase.query(
            AminoDb.T_KNOWLEDGE, null, sel, args, null, null, "id DESC", limit.toString()
        ).use { it.toList { c -> KnowledgeItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) } }
    }

    fun deleteKnowledge(context: Context, id: Long) {
        db(context).writableDatabase.delete(AminoDb.T_KNOWLEDGE, "id=?", arrayOf(id.toString()))
    }

    // ---------- Experience Memory (v1.3 — episodic, self-evolving) ----------

    /**
     * Records a finished task with its real outcome and the lesson learned.
     * Keeps at most the newest 200 records — the journal never grows unbounded.
     */
    fun addExperience(
        context: Context, task: String, outcome: String,
        lessons: String, skillId: String? = null
    ): Long {
        val cv = ContentValues().apply {
            put("task", task.trim().take(300))
            put("outcome", if (outcome.equals("success", true)) "success" else "fail")
            put("lessons", lessons.trim().take(600))
            skillId?.takeIf { it.isNotBlank() }?.let { put("skill_id", it.take(80)) }
            put("created_at", System.currentTimeMillis())
        }
        val w = db(context).writableDatabase
        val id = w.insert(AminoDb.T_EXPERIENCES, null, cv)
        w.execSQL(
            "DELETE FROM ${AminoDb.T_EXPERIENCES} WHERE id NOT IN " +
                "(SELECT id FROM ${AminoDb.T_EXPERIENCES} ORDER BY id DESC LIMIT 200)"
        )
        return id
    }

    /** Newest-first retrieval; LIKE over task+lessons (local, no embeddings by design). */
    fun experiences(context: Context, query: String? = null, limit: Int = 5): List<ExperienceItem> {
        val sel = if (!query.isNullOrBlank()) "(task LIKE ? OR lessons LIKE ?)" else null
        val args = if (!query.isNullOrBlank()) arrayOf("%$query%", "%$query%") else null
        return db(context).readableDatabase.query(
            AminoDb.T_EXPERIENCES, null, sel, args, null, null, "id DESC", limit.coerceIn(1, 20).toString()
        ).use { c ->
            c.toList { r ->
                ExperienceItem(
                    r.getLong(0), r.getString(1), r.getString(2),
                    r.getString(3) ?: "", r.getString(4)?.takeIf { it.isNotEmpty() }, r.getLong(5)
                )
            }
        }
    }

    // ---------- Agent Working Memory ----------

    fun saveWorking(context: Context, conversationId: Long, goal: String, stepsJson: String, status: String) {
        val w = db(context).writableDatabase
        val cv = ContentValues().apply {
            put("conversation_id", conversationId); put("goal", goal)
            put("steps_json", stepsJson); put("status", status); put("updated_at", System.currentTimeMillis())
        }
        val updated = w.update(AminoDb.T_WORKING_MEMORY, cv, "conversation_id=?", arrayOf(conversationId.toString()))
        if (updated == 0) w.insert(AminoDb.T_WORKING_MEMORY, null, cv)
    }

    fun working(context: Context, conversationId: Long): Triple<String, String, String>? {
        return db(context).readableDatabase.query(
            AminoDb.T_WORKING_MEMORY, null, "conversation_id=?", arrayOf(conversationId.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "") else null
        }
    }

    // ---------- utils ----------

    private fun <T> Cursor.toList(map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        while (moveToNext()) out.add(map(this))
        return out
    }
}
