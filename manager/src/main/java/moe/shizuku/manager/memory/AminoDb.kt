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
 *  - skills (v1.4)            : Skills Center metadata index — the skill FILES in
 *                               filesDir/skills/ are the source of truth; this table
 *                               is a searchable index (rebuildable via reconcile()).
 *  - task_continuations (r1418): DURABLE TASK MEMORY — timed continuations the
 *                               agent scheduled via task_wait(seconds>120). Each row
 *                               is a pending step of a RUNNING task ("stop the
 *                               recording at T"); fires through AlarmManager and
 *                               survives process death AND reboot (boot receiver
 *                               re-arms). This is what makes multi-step timed
 *                               commands actually finish (the LangGraph-checkpointer
 *                               pattern: persist the pending step, resume from it).
 *
 * Implemented with plain SQLiteOpenHelper instead of Room on purpose: same local
 * isolation, zero annotation-processor/build risk, full SQL control.
 */
class AminoDb private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "amino_agent.db"
        const val DB_VERSION = 4

        const val T_CONVERSATIONS = "conversations"
        const val T_MESSAGES = "messages"
        const val T_USER_MEMORY = "user_memory"
        const val T_WORKING_MEMORY = "working_memory"
        const val T_KNOWLEDGE = "knowledge"
        const val T_EXPERIENCES = "experiences"
        const val T_SKILLS = "skills"
        const val T_CONTINUATIONS = "task_continuations"

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

        val CREATE_CONTINUATIONS = """
            CREATE TABLE $T_CONTINUATIONS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                goal TEXT NOT NULL,
                action TEXT NOT NULL,
                seconds INTEGER NOT NULL DEFAULT 0,
                fire_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()

        val CREATE_SKILLS = """
            CREATE TABLE $T_SKILLS(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT,
                category TEXT,
                description TEXT,
                enabled INTEGER DEFAULT 1,
                source_type TEXT,
                has_steps INTEGER DEFAULT 0,
                has_scripts INTEGER DEFAULT 0,
                scripts_approved INTEGER DEFAULT 0,
                sha256 TEXT,
                tags TEXT,
                success INTEGER DEFAULT 0,
                fail INTEGER DEFAULT 0,
                installed_at INTEGER,
                updated_at INTEGER
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
        db.execSQL(CREATE_SKILLS)
        db.execSQL(CREATE_CONTINUATIONS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 (1.3): experiences table — additive only, nothing existing migrates.
        if (oldVersion < 2) db.execSQL(CREATE_EXPERIENCES)
        // v3 (1.4): skills metadata index — additive only (files remain the truth).
        if (oldVersion < 3) db.execSQL(CREATE_SKILLS)
        // v4 (r1418): durable timed continuations — additive only.
        if (oldVersion < 4) db.execSQL(CREATE_CONTINUATIONS)
    }
}
