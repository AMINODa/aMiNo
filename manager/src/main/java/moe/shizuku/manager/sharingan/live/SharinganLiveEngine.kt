package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.collectLatest
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.keys.SecureStore
import moe.shizuku.manager.agent.CloudflareProvider
import moe.shizuku.manager.agent.AgentRouterProvider
import moe.shizuku.manager.agent.OpenRouterProvider
import moe.shizuku.manager.agent.OpenAiCompatProvider
import moe.shizuku.manager.agent.GeminiProvider
import moe.shizuku.manager.agent.LlmMessage
import moe.shizuku.manager.agent.LlmDecision

/**
 * aMiNo 2.0 (r1436) — SHARINGAN LIVE ENGINE: the true Gemini-Live-style
 * broadcast loop. This REPLACES r1435's SharinganLiveSession (merged here,
 * then deleted): a passive 2.5s text-ring watcher is not what the user asked
 * for («اريداه مثل Gemini Live بنفس الفعالية»).
 *
 * WHAT GEMINI LIVE ACTUALLY DOES (researched from the official docs, 2026-11-12)
 * and what this engine now mirrors:
 *  1. SCREEN SHARE PERFORMANCE — video frames are JPEG, max 1 FPS. We capture
 *     through the proven shell path (screencap+md5, direct shell control stays
 *     in the background as the user demands) and compress on-device
 *     (LiveFrameCodec: 720px/JPEG-52 ≈ 60-160KB instead of r1435's 2-8MB PNG).
 *     Adaptive cadence: ~1s while the screen changes, ~3.5s heartbeat when
 *     static, 0.8s while the executor verifies a tool result.
 *  2. PROACTIVITY — "Gemini can proactively decide not to respond if the
 *     content is not relevant" (proactive audio). Equivalent: when a
 *     significant change lands (app switch / strong activity change) and the
 *     user has been quiet, a cheap narrate-or-stay-silent round runs; the
 *     persona answers exactly NO_REPLY unless a spoken remark is worth it.
 *  3. CONTINUOUS VOICE CONVERSATION — one persistent live dialogue with the
 *     newest frame attached to every turn, fast single provider call (NOT a
 *     full agent task per utterance), sentence-chunked TTS with barge-in.
 *  4. THE aMiNo SUPERPOWER — when the user asks for an ACTION, the model
 *     answers with [[ACT]]…[[/ACT]] and the command is routed to
 *     AgentOrchestrator: the REAL execution engine (shell/accessibility/ADB,
 *     Duration Guardian, task memory) — control stays direct via shell in the
 *     background, and every orchestrator round during the broadcast carries
 *     the newest frame (visual verification after tool execution).
 *
 * HONESTY: every observation in the ring is a real captured frame; every
 * counter in SharinganState is real; capture failures surface as errors;
 * proactive silence is counted, never faked as a reply.
 */
object SharinganLiveEngine {

    private const val TAG = "SharinganLive"
    private const val MAX_SESSION_MS = 15 * 60_000L   // battery hard cap
    private const val MAX_OBSERVATIONS = 12           // real-change ring
    private const val TICK_ACTIVE_MS = 1_000L         // changing → ≤1 FPS (Gemini spec)
    private const val TICK_IDLE_MS = 3_500L           // static → heartbeat
    private const val TICK_VERIFY_MS = 800L           // executor verifying → fresh frames
    private const val PROACTIVE_MIN_GAP_MS = 20_000L  // never narrate twice within 20s
    private const val PROACTIVE_MAX = 24              // per session
    private const val AFTER_TURN_QUIET_MS = 8_000L    // quiet window after any model turn
    private const val HISTORY_PAIRS = 6               // live dialogue memory
    private const val TEXT_EVERY_CHANGES = 3          // uiautomator cost control

    // phases published to SharinganState (eye/overlay/waveform react)
    const val PHASE_WATCHING = "watching"
    const val PHASE_THINKING = "thinking"
    const val PHASE_EXECUTING = "executing"

    data class Observation(
        val tMs: Long,          // ms since session start
        val activity: String?,  // real topResumedActivity line
        val text: String?       // real uiautomator text excerpt (cost-controlled)
    )

    /** Result of one conversational turn (say). */
    data class TurnResult(
        val reply: String?,          // the SPOKEN answer (ACT block stripped)
        val actionCommand: String?,  // non-null → route to AgentOrchestrator
        val latencyMs: Long,
        val refused: Boolean = false,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var watcher: Job? = null
    private var appContext: Context? = null
    private var startedAt = 0L

    private val llmMutex = Mutex()

    // session truth (volatile: read by UI + orchestrator threads)
    @Volatile private var framesCaptured = 0
    @Volatile private var framesChanged = 0
    @Volatile private var modelCalls = 0
    @Volatile private var proactiveTurns = 0
    @Volatile private var proactiveSilent = 0
    @Volatile private var lastLatencyMs = 0L
    @Volatile private var lastModelTurnAt = 0L
    @Volatile private var lastProactiveAt = 0L
    @Volatile private var lastSpokenAt = 0L

    private val observations = ArrayDeque<Observation>()
    private val liveHistory = ArrayDeque<Pair<String, String>>() // (role,text) pairs
    @Volatile private var latestFrame: LiveFrameCodec.JpegFrame? = null
    @Volatile private var latestActivity: String? = null
    @Volatile private var latestFrameAtMs = 0L
    @Volatile private var verifyBoost = false
    @Volatile private var changesSinceText = 0

    val active: Boolean get() = loop?.isActive == true
    fun observationCount(): Int = synchronized(observations) { observations.size }
    fun framesChangedCount(): Int = framesChanged

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun start(context: Context): Boolean {
        if (active) return true
        if (!moe.shizuku.manager.terminal.linux.ShizukuExec.available()) {
            SharinganStateUpdate.update { it.copy(lastError = "shizuku_service_unavailable") }
            return false
        }
        appContext = context.applicationContext
        synchronized(observations) { observations.clear() }
        liveHistory.clear()
        framesCaptured = 0; framesChanged = 0; modelCalls = 0
        proactiveTurns = 0; proactiveSilent = 0; lastLatencyMs = 0
        lastModelTurnAt = 0; lastProactiveAt = 0; lastSpokenAt = 0
        changesSinceText = 0
        latestFrame = null; latestActivity = null; latestFrameAtMs = 0
        startedAt = System.currentTimeMillis()
        moe.shizuku.manager.sharingan.SharinganVisionHub.clear()
        SharinganStateUpdate.update {
            it.copy(
                lastError = null,
                liveActive = true, liveFrames = 0, liveObserved = 0,
                livePhase = PHASE_WATCHING, liveCalls = 0, liveProactive = 0,
                liveLatencyMs = 0
            )
        }
        loop = scope.launch { perceptionLoop() }
        watcher = scope.launch { orchestratorWatcher() }
        // Gemini-style UX: end the broadcast only through the user or the cap.
        Log.i(TAG, "live engine started (gemini-spec jpeg<=1fps)")
        return true
    }

    fun stop(context: Context, autoTimedOut: Boolean = false) {
        val wasActive = active
        loop?.cancel(); loop = null
        watcher?.cancel(); watcher = null
        verifyBoost = false
        moe.shizuku.manager.sharingan.SharinganVisionHub.clear()
        SharinganStateUpdate.update {
            it.copy(
                liveActive = false, livePhase = PHASE_WATCHING,
                lastError = if (autoTimedOut) "live_session_auto_stopped" else null
            )
        }
        if (wasActive) Log.i(TAG, "live engine stopped (auto=$autoTimedOut frames=$framesCaptured " +
            "changed=$framesChanged calls=$modelCalls proactive=$proactiveTurns silent=$proactiveSilent)")
        scope.launch { runCatching { moe.shizuku.manager.sharingan.ScreenCapture.pruneOld() } }
    }

    // ── perception loop (the screen-share performance core) ────────────────

    private suspend fun CoroutineScope.perceptionLoop() {
        val ctx = appContext ?: return
        var lastHash: String? = null
        var lastActivityPkg: String? = null
        var lastCaptureAt = 0L
        try {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= MAX_SESSION_MS) {
                    stop(ctx, autoTimedOut = true)
                    moe.shizuku.manager.sharingan.SharinganPanel.maybeNotifyAutoStop(ctx)
                    return
                }

                val frame = moe.shizuku.manager.sharingan.ScreenCapture.capture()
                if (frame == null) {
                    SharinganStateUpdate.update { it.copy(lastError = "live_capture_failed") }
                } else {
                    framesCaptured++
                    lastCaptureAt = System.currentTimeMillis()
                    val changed = frame.hash != lastHash
                    if (changed) {
                        lastHash = frame.hash
                        framesChanged++
                        changesSinceText++
                        val pkg = packageOf(frame.activity)
                        val activitySwitched = pkg != null && pkg != lastActivityPkg
                        if (activitySwitched) lastActivityPkg = pkg

                        // uiautomator is 1-2s of shell work — run it only when the
                        // activity switched or every TEXT_EVERY_CHANGES changes.
                        val wantText = activitySwitched || changesSinceText >= TEXT_EVERY_CHANGES
                        var text: String? = null
                        if (wantText) {
                            text = moe.shizuku.manager.sharingan.ScreenCapture
                                .screenText(maxChars = 300)?.take(260)
                            if (text != null) changesSinceText = 0
                        }
                        synchronized(observations) {
                            observations.addFirst(Observation(elapsed, frame.activity, text))
                            while (observations.size > MAX_OBSERVATIONS) observations.removeLast()
                        }

                        // THE PERFORMANCE FIX: compress to Gemini-spec JPEG before
                        // anything leaves the device.
                        val jpeg = LiveFrameCodec.jpegFromPngPath(frame.pngPath)
                        if (jpeg != null) {
                            latestFrame = jpeg
                            latestActivity = frame.activity
                            latestFrameAtMs = System.currentTimeMillis()
                            moe.shizuku.manager.sharingan.SharinganVisionHub.setLiveFrame(
                                base64 = jpeg.base64, mime = jpeg.mime,
                                activity = frame.activity, frameHash = frame.hash,
                                source = "live_engine"
                            )
                        }
                        SharinganStateUpdate.update { it.copy(liveFrames = framesCaptured, liveObserved = framesChanged) }

                        // PROACTIVITY (Gemini Live behaviour): a real app switch
                        // is the strongest "worth noticing" signal we have.
                        if (activitySwitched) maybeProactive(ctx, frame.activity)
                        if (framesChanged % 10 == 0) {
                            moe.shizuku.manager.sharingan.ScreenCapture.pruneOld()
                        }
                    }
                    SharinganStateUpdate.update { it.copy(liveFrames = framesCaptured) }
                }

                // adaptive cadence (never above 1 FPS effective rate)
                val sinceChange = System.currentTimeMillis() - lastCaptureAt
                val next = when {
                    verifyBoost -> TICK_VERIFY_MS
                    sinceChange <= 4_000 -> TICK_ACTIVE_MS
                    else -> TICK_IDLE_MS
                }
                delay(next)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "perception loop ended: ${t.message}")
            SharinganStateUpdate.update { it.copy(lastError = "live_session_crashed") }
            stop(ctx)
        }
    }

    private fun packageOf(activityLine: String?): String? =
        activityLine?.let { line ->
            val m = Regex("u0 ([\\w.]+)/").find(line) ?: Regex("([\\w.]+)/(?:[\\w.]+\\.)?[\\w$]+\\s*[,}]").find(line)
            m?.groupValues?.get(1)?.takeIf { it.contains('.') }
        }

    // ── orchestrator watcher (EXECUTING phase + verify boost) ──────────────

    private suspend fun orchestratorWatcher() {
        val orch = moe.shizuku.manager.agent.AgentOrchestrator
        orch.state.collectLatest { st ->
            when {
                st.busy && st.status is moe.shizuku.manager.agent.AgentStatus.ExecutingTool -> {
                    verifyBoost = true
                    SharinganStateUpdate.update { it.copy(livePhase = PHASE_EXECUTING) }
                }
                st.busy -> {
                    verifyBoost = true
                    if (SharinganStateUpdate.state.value.livePhase != PHASE_EXECUTING) {
                        SharinganStateUpdate.update { it.copy(livePhase = PHASE_WATCHING) }
                    }
                }
                else -> {
                    verifyBoost = false
                    if (SharinganStateUpdate.state.value.livePhase == PHASE_EXECUTING) {
                        SharinganStateUpdate.update { it.copy(livePhase = PHASE_WATCHING) }
                    }
                }
            }
        }
    }

    // ── conversational turn (the fast Gemini-Live dialogue) ────────────────

    /**
     * One live dialogue turn: user text + live history + the newest REAL frame
     * → ONE provider call (no tools, no agent loop) → spoken-style reply.
     * If the reply carries [[ACT]]…[[/ACT]], actionCommand is returned and the
     * CALLER routes it to AgentOrchestrator (real execution in background).
     */
    suspend fun say(context: Context, userText: String): TurnResult {
        if (!active) return TurnResult(null, null, 0, refused = true, error = "not_active")
        if (!llmMutex.tryLock()) return TurnResult(null, null, 0, refused = true, error = "busy")
        try {
            setPhase(PHASE_THINKING)
            val t0 = System.currentTimeMillis()
            val decision = callProvider(
                context,
                systemPrompt = LivePersonas.conversation(ringSummary(), sessionSeconds()),
                history = dialogueHistory() + LlmMessage("user", userText),
                attachFrame = true
            )
            val latency = System.currentTimeMillis() - t0
            modelCalls++
            lastLatencyMs = latency
            lastModelTurnAt = System.currentTimeMillis()
            SharinganStateUpdate.update {
                it.copy(liveCalls = modelCalls, liveLatencyMs = latency)
            }
            val result = when (decision) {
                is LlmDecision.Error -> TurnResult(null, null, latency, error = decision.message)
                is LlmDecision.Text -> parseLiveReply(decision.text, userText)
                is LlmDecision.ToolCalls -> TurnResult(null, null, latency,
                    error = "live_mode_tool_call") // personas carry no tools; defensive
            }
            setPhase(PHASE_WATCHING)
            return result
        } catch (e: java.io.IOException) {
            setPhase(PHASE_WATCHING)
            return TurnResult(null, null, 0, error = e.message ?: "network")
        } catch (t: Throwable) {
            Log.w(TAG, "say failed: ${t.message}")
            setPhase(PHASE_WATCHING)
            return TurnResult(null, null, 0, error = t.message ?: "internal")
        } finally {
            llmMutex.unlock()
        }
    }

    private fun parseLiveReply(text: String, userText: String): TurnResult {
        var reply = text.trim()
        var action: String? = null
        val open = reply.indexOf(LivePersonas.ACT_OPEN)
        if (open >= 0) {
            val close = reply.indexOf(LivePersonas.ACT_CLOSE, open)
            if (close > open) {
                action = reply.substring(open + LivePersonas.ACT_OPEN.length, close)
                    .trim().take(600)
                reply = (reply.substring(0, open) + reply.substring(close + LivePersonas.ACT_CLOSE.length))
                    .trim()
            }
        }
        if (reply.isBlank()) reply = if (action != null)
            "أنفّذ الآن — سأخبرك بالنتيجة الحقيقية عند الانتهاء." else "(empty reply)"
        // dialogue memory (spoken turns only, no ACT payload)
        synchronized(liveHistory) {
            liveHistory.addLast("user" to userText)
            liveHistory.addLast("assistant" to reply.take(400))
            while (liveHistory.size > HISTORY_PAIRS * 2) liveHistory.removeFirst()
        }
        return TurnResult(reply = reply, actionCommand = action, latencyMs = lastLatencyMs)
    }

    // ── proactive narration (Gemini "proactive audio" equivalent) ──────────

    private suspend fun maybeProactive(ctx: Context, activityLine: String?) {
        val st = SharinganStateUpdate.state.value
        val now = System.currentTimeMillis()
        if (!st.voiceActive) return                          // narration is SPOKEN
        if (st.voiceSpeaking) return                          // never talk over itself
        if (moe.shizuku.manager.agent.AgentOrchestrator.state.value.busy) return // executor owns the mic moment
        if (proactiveTurns + proactiveSilent >= PROACTIVE_MAX * 2) return
        if (proactiveTurns >= PROACTIVE_MAX) return
        if (now - lastProactiveAt < PROACTIVE_MIN_GAP_MS) return
        if (now - lastModelTurnAt < AFTER_TURN_QUIET_MS) return
        if (now - lastSpokenAt < AFTER_TURN_QUIET_MS) return
        if (!llmMutex.tryLock()) return
        try {
            val t0 = System.currentTimeMillis()
            val decision = callProvider(
                ctx,
                systemPrompt = LivePersonas.proactive(ringSummary(), sessionSeconds()),
                history = emptyList(),
                attachFrame = true
            )
            val latency = System.currentTimeMillis() - t0
            modelCalls++; lastLatencyMs = latency
            lastProactiveAt = now; lastModelTurnAt = now
            when (decision) {
                is LlmDecision.Text -> {
                    val clean = decision.text.trim()
                    if (clean.equals(LivePersonas.NO_REPLY, ignoreCase = true) || clean.isBlank()) {
                        proactiveSilent++   // honest: chose silence, counted
                    } else {
                        proactiveTurns++
                        lastSpokenAt = System.currentTimeMillis()
                        Log.i(TAG, "proactive remark (${latency}ms): ${clean.take(80)}")
                        moe.shizuku.manager.sharingan.SharinganVoice.speak(ctx, clean)
                    }
                }
                else -> proactiveSilent++
            }
            SharinganStateUpdate.update {
                it.copy(liveCalls = modelCalls, liveProactive = proactiveTurns, liveLatencyMs = latency)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "proactive failed: ${t.message}")
        } finally {
            llmMutex.unlock()
        }
    }

    /** The orchestrator just spoke a final answer (viaVoice) — respect the quiet window. */
    fun noteSpoken() { lastSpokenAt = System.currentTimeMillis() }

    // ── provider plumbing (same providers, same keys — no new dependency) ──

    private fun callProvider(
        context: Context,
        systemPrompt: String,
        history: List<LlmMessage>,
        attachFrame: Boolean
    ): LlmDecision {
        val providerId = ApiKeysStore.provider(context)
        val provider: moe.shizuku.manager.agent.LlmProvider = when (providerId) {
            ApiKeysStore.PROVIDER_CLOUDFLARE -> CloudflareProvider
            ApiKeysStore.PROVIDER_OPENROUTER -> OpenRouterProvider
            ApiKeysStore.PROVIDER_AGENTROUTER -> AgentRouterProvider
            ApiKeysStore.PROVIDER_OPENAI_COMPAT -> OpenAiCompatProvider
            else -> GeminiProvider
        }
        val apiKey = SecureStore.get(context, "apikey_$providerId")
            ?: throw java.io.IOException("no_api_key for $providerId")
        val model = ApiKeysStore.model(context)
        val baseUrl = ApiKeysStore.baseUrl(context)
        var msgs = history
        if (attachFrame) {
            val f = latestFrame
            if (f != null) {
                msgs = listOf(
                    LlmMessage(
                        role = "user",
                        text = "[LIVE FRAME] real JPEG screenshot of the CURRENT screen " +
                            "(md5-verified capture, downscaled to 720px, captured " +
                            "${(System.currentTimeMillis() - latestFrameAtMs) / 1000}s ago, " +
                            "activity=${latestActivity?.take(120) ?: "unknown"}). " +
                            "Analyze it visually; never invent content not present in it.",
                        imagePngBase64 = f.base64,
                        imageMime = f.mime
                    )
                ) + msgs
            }
        }
        return provider.chat(systemPrompt, msgs, emptyList(), apiKey, model, baseUrl)
    }

    // ── context blocks ──────────────────────────────────────────────────────

    private fun dialogueHistory(): List<LlmMessage> = synchronized(liveHistory) {
        liveHistory.map { LlmMessage(it.first, it.second) }
    }

    private fun ringSummary(): String = synchronized(observations) {
        if (observations.isEmpty()) ""
        else observations.joinToString("\n") { o ->
            val sb = StringBuilder("- t=+${"%.1f".format(o.tMs / 1000.0)}s activity=${o.activity ?: "?"}")
            if (!o.text.isNullOrBlank()) sb.append(" | screen_text: \"${o.text}\"")
            sb.toString()
        }
    }

    private fun sessionSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000

    /**
     * [SHARINGAN LIVE — ACTIVE SESSION] block injected into EVERY orchestrator
     * round while the broadcast runs (replaces r1435's session block; the
     * newest JPEG frame is attached to the round via SharinganVisionHub).
     */
    fun contextBlock(): String {
        if (!active) return ""
        val list = synchronized(observations) { observations.toList() }
        val sb = StringBuilder()
        sb.appendLine("[SHARINGAN LIVE — ACTIVE SESSION] the user is sharing their screen LIVE " +
            "(${sessionSeconds()}s, $framesCaptured frames captured, $framesChanged visual changes, " +
            "$modelCalls model turns, last turn ${lastLatencyMs}ms):")
        if (list.isEmpty()) {
            sb.appendLine("no visual changes captured yet — the screen has been static since the session started.")
        } else {
            list.forEach { o ->
                sb.appendLine("- t=+${"%.1f".format(o.tMs / 1000.0)}s activity=${o.activity ?: "?"}")
                if (!o.text.isNullOrBlank()) sb.appendLine("  screen_text: \"${o.text}\"")
            }
        }
        sb.appendLine("The NEWEST frame is attached as a real JPEG image in this round — analyze it visually. " +
            "It may be slightly stale by the time you act: verify with screen_read before precise taps. " +
            "Never invent screen content not shown here.")
        return sb.toString().trim()
    }

    private fun setPhase(p: String) {
        SharinganStateUpdate.update { it.copy(livePhase = p) }
    }

    /** TraceStore-style alias used by the orchestrator while the session runs. */
    fun framesChangedTotal(): Int = framesChanged
}

/** Small indirection so the engine can update UI state without a circular import. */
internal object SharinganStateUpdate {
    val state get() = moe.shizuku.manager.sharingan.SharinganState.state
    fun update(transform: (moe.shizuku.manager.sharingan.SharinganUiState) -> moe.shizuku.manager.sharingan.SharinganUiState) {
        moe.shizuku.manager.sharingan.SharinganState.update(transform)
    }
}
