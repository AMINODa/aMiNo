package moe.shizuku.manager.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * aMiNo Agent local database (r1376).
 *
 * ALL agent data lives on the phone only - no backend, no cloud storage:
 *  - conversations / messages : Conversation Memory
 *  - user_memory              : User Memory (only what the user approved)
 *  - working_memory           : Agent Working Memory (current task state)
 *  - knowledge                : Knowledge memory (local text search)
 *  - experiences (v1.3)       : Episodic task memory — what worked, what failed,
 *                               lessons for future runs (the self-evolving loop:
 *                               Mobile-Agent-E / AppAgentX experience reuse)
 *
 * Implemented with plain SQLiteOpenHelper instead of Room on purpose: same local
 * isolation, zero annotation-processor/build risk, full SQL control.
 */
class AminoDb private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "amino_agent.db"
        const val DB_VERSION = 2

        const val T_CONVERSATIONS = "conversations"
        const val T_MESSAGES = "messages"
        const val T_USER_MEMORY = "user_memory"
        const val T_WORKING_MEMORY = "working_memory"
        const val T_KNOWLEDGE = "knowledge"
        const val T_EXPERIENCES = "experiences"

        @Volatile
        private var instance: AminoDb? = null

        val CREATE_EXPERIENCES = """
            CREATE TABLE $T_EXPERIENCES(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task TEXT NOT NULL,
                outcome TEXT NOT NULL,
                lessons TEXT,
                skill_id TEXT,
                created_at INTEGER NOT NULL
            )""".trimIndent()

        fun get(context: Context): AminoDb =
            instance ?: synchronized(this) {
                instance ?: AminoDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_CONVERSATIONS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""")
        db.execSQL("""
            CREATE TABLE $T_MESSAGES(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_name TEXT,
                tool_ok INTEGER,
                created_at INTEGER NOT NULL
            )""")
        db.execSQL("CREATE INDEX idx_messages_conv ON $T_MESSAGES(conversation_id, id)")
        db.execSQL("""
            CREATE TABLE $T_USER_MEMORY(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""")
        db.execSQL("""
            CREATE TABLE $T_WORKING_MEMORY(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL UNIQUE,
                goal TEXT,
                steps_json TEXT,
                status TEXT,
                updated_at INTEGER NOT NULL
            )""")
        db.execSQL("""
            CREATE TABLE $T_KNOWLEDGE(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""")
        db.execSQL(CREATE_EXPERIENCES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 (1.3): experiences table — additive only, nothing existing migrates.
        if (oldVersion < 2) db.execSQL(CREATE_EXPERIENCES)
    }
}
