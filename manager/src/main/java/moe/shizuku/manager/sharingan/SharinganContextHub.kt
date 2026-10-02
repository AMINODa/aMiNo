package moe.shizuku.manager.sharingan

/**
 * Bridges the floating panel to the agent prompt builder.
 *
 * The panel stages a trace id (a recorded trace, or a live snapshot); the NEXT
 * agent message automatically consumes it via AgentIdentity.contextBlock() and
 * injects a [SHARINGAN CONTEXT] block into the system prompt.
 *
 * TRACE TRUTH (PLAN_aMiNo2.md §2.3): the injected block is built ONLY from real
 * captured entries stored by TraceStore — nothing is ever fabricated here.
 */
object SharinganContextHub {

    @Volatile
    private var pendingTraceId: String? = null

    /** Stage a trace id for the next agent message. null clears the staging. */
    fun stage(traceId: String?) {
        pendingTraceId = traceId
    }

    /** Consume the staged trace id (read-once semantics). */
    fun consume(): String? = pendingTraceId.also { pendingTraceId = null }

    fun peek(): String? = pendingTraceId
}
