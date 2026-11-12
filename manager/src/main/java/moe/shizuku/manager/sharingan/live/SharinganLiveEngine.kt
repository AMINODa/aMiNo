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
 * aMiNo 2.0 (r1437) — SHARINGAN LIVE ENGINE, the Gemini-Live broadcast loop,
 * rebuilt on the r1436 researched contract with the field failures removed:
 *
 *  FIXED r1436→r1437 (user verbatim: «زر live لايعمل و يختفي خلال ثواني»):
 *   - NO shell gate: start() no longer refuses when wireless-ADB is down.
 *     Frames come from LiveScreenSource — MediaProjection (primary, Gemini's
 *     own mechanism) or shell screencap (fallback).
 *   - NO uiautomator ring: the loop is IMAGE-FIRST (the user: «تحلل الصورة
 *     كما هي ولا تعتمد فقط على ocr»). Screen text stays the orchestrator's
 *     screen_read tool — auxiliary, never the broadcast's dependency.
 *   - The session lives in LiveBroadcastService (FGS) — not bound to the
 *     accessibility service lifecycle.
 *   - Static screens are reported honestly ("static — no updates") instead
 *     of a frozen 0-frames counter.
 *
 * WHAT STAYS THE GEMINI-LIVE CONTRACT (r1436 research, unchanged):
 *  1. Screen-share performance: JPEG frames ≤1 FPS (1s changing / 3.5s
 *     heartbeat / 0.8s while the executor verifies), 720px q52 on-device.
 *  2. Proactivity: app-switch → cheap narrate-or-stay-silent round
 *     (NO_REPLY discipline) — Gemini's proactive audio equivalent.
 *  3. Continuous conversation: one persistent live dialogue, the newest
 *     frame attached to EVERY turn, one fast provider call per utterance,
 *     sentence-chunked TTS with barge-in (SharinganVoice).
 *  4. The aMiNo superpower: [[ACT]]…[[/ACT]] → AgentOrchestrator — the REAL
 *     shell/execution engine runs in the background (direct shell control
 *     stays, as the user demands) with visual verification from fresh frames.
 *
 * HONESTY: every frame is a real capture; counters are real; capture failures
 * surface in the state (never a blind "watching"); proactive silence is
 * counted, never faked as a reply.
 */
object SharinganLiveEngine {

    private const val TAG = "SharinganLive"
    private const val MAX_SESSION_MS = 30 * 60_000L   // r1437: 15→30 min (the 15-min cap read as "it disappeared")
    private const val MAX_OBSERVATIONS = 12           // real-change ring
    private const val TICK_ACTIVE_MS = 1_000L         // changing → ≤1 FPS (Gemini spec)
    private const val TICK_IDLE_MS = 3_500L           // static → heartbeat
    private const val TICK_VERIFY_MS = 800L           // executor verifying → fresh frames
    private const val STATIC_AFTER_MS = 8_000L        // no new frame → honest "static" flag
    private const val PROACTIVE_MIN_GAP_MS = 20_000L
    private const val PROACTIVE_MAX = 24
    private const val AFTER_TURN_QUIET_MS = 8_000L
    private const val HISTORY_PAIRS = 6

    // phases published to SharinganState (eye/overlay/waveform react)
    const val PHASE_WATCHING = "watching"
    const val PHASE_THINKING = "thinking"
    const val PHASE_EXECUTING = "executing"

    data class Observation(
        val tMs: Long,          // ms since session start
        val activity: String?   // real topResumedActivity line (null when shell down)
    )

    /** Result of one conversational turn (say). */
    data class TurnResult(
        val reply: String?,
        val actionCommand: String?,
        val latencyMs: Long,
        val refused: Boolean = false,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var watcher: Job? = null
    private var appContext: Context? = null
    private var source: LiveScreenSource? = null
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
    private val liveHistory = ArrayDeque<Pair<String, String>>()
    @Volatile private var latestFrame: LiveFrameCodec.JpegFrame? = null
    @Volatile private var latestActivity: String? = null
    @Volatile private var latestFrameAtMs = 0L
    @Volatile private var lastHash: String? = null
    @Volatile private var verifyBoost = false

    val active: Boolean get() = loop?.isActive == true
    fun observationCount(): Int = synchronized(observations) { observations.size }
    fun framesChangedCount(): Int = framesChanged

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** r1437: the source decides everything — no shell requirement here. */
    fun start(context: Context, screenSource: LiveScreenSource): Boolean {
        if (active) return true
        appContext = context.applicationContext
        source = screenSource
        synchronized(observations) { observations.clear() }
        liveHistory.clear()
        framesCaptured = 0; framesChanged = 0; modelCalls = 0
        proactiveTurns = 0; proactiveSilent = 0; lastLatencyMs = 0
        lastModelTurnAt = 0; lastProactiveAt = 0; lastSpokenAt = 0
        latestFrame = null; latestActivity = null; latestFrameAtMs = 0
        lastHash = null
        startedAt = System.currentTimeMillis()
        moe.shizuku.manager.sharingan.SharinganVisionHub.clear()
        SharinganStateUpdate.update {
            it.copy(
                lastError = null,
                liveActive = true, liveFrames = 0, liveObserved = 0, liveStatic = false,
                livePhase = PHASE_WATCHING, liveCalls = 0, liveProactive = 0,
                liveLatencyMs = 0
            )
        }
        loop = scope.launch { perceptionLoop() }
        watcher = scope.launch { orchestratorWatcher() }
        Log.i(TAG, "live engine started (source=${screenSource.kind} gemini-spec jpeg<=1fps)")
        return true
    }

    fun stop(context: Context, autoTimedOut: Boolean = false) {
        val wasActive = active
        if (!wasActive && !autoTimedOut) return   // idempotent — never wipe an already-clean state
        loop?.cancel(); loop = null
        watcher?.cancel(); watcher = null
        verifyBoost = false
        source?.close(); source = null
        moe.shizuku.manager.sharingan.SharinganVisionHub.clear()
        SharinganStateUpdate.update {
            it.copy(
                liveActive = false, liveStatic = false, livePhase = PHASE_WATCHING,
                lastError = if (autoTimedOut) "live_session_auto_stopped"
                            else if (wasActive) null else moe.shizuku.manager.sharingan.SharinganState.state.value.lastError
            )
        }
        if (wasActive) {
            Log.i(TAG, "live engine stopped (auto=$autoTimedOut frames=$framesCaptured " +
                "changed=$framesChanged calls=$modelCalls proactive=$proactiveTurns silent=$proactiveSilent)")
            // r1437-expert-fix: EVERY stop path closes the whole broadcast —
            // FGS notification, overlay dock, voice loop. Engine-side stops
            // (projection lost by system / crash / source null) previously
            // left the FGS + notification + mic running with a dead engine.
            // Re-entrancy is safe: service teardown → engine.stop returns
            // early here (wasActive already false).
            runCatching {
                LiveOverlay.hide()
                if (autoTimedOut) {
                    SharinganTaskNotifierViaLive.notifyAutoStop(context)
                }
                LiveBroadcastService.stopBroadcast(context)
            }
        }
        scope.launch { runCatching { moe.shizuku.manager.sharingan.ScreenCapture.pruneOld() } }
    }

    // ── perception loop (the screen-share performance core) ────────────────

    private suspend fun CoroutineScope.perceptionLoop() {
        var lastActivityPkg: String? = null
        var lastCaptureAt = System.currentTimeMillis()
        try {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= MAX_SESSION_MS) {
                    stop(appContext ?: return, autoTimedOut = true)
                    return
                }

                val src = source
                if (src == null) { stop(appContext ?: return); return }

                // ── take a frame (projection: non-blocking; shell: suspend) ──
                val frame: LiveFrameCodec.JpegFrame? = when (src) {
                    is LiveScreenSource.ProjectionSource -> src.captureJpeg()
                    is LiveScreenSource.ShellSource -> src.captureJpegSuspending()
                }

                if (src is LiveScreenSource.ProjectionSource && src.lost) {
                    Log.w(TAG, "media projection lost — ending broadcast honestly")
                    stop(appContext ?: return, autoTimedOut = false)
                    SharinganStateUpdate.update { it.copy(lastError = "live_projection_stopped_by_system") }
                    return
                }

                if (frame == null) {
                    if (src is LiveScreenSource.ShellSource) {
                        // Shell captures run every tick — a null IS a failure here.
                        SharinganStateUpdate.update { it.copy(lastError = "live_capture_failed") }
                    }
                    // Projection null = screen had no update (NOT a failure):
                    // honest static flag after 8s of nothing new.
                    if (System.currentTimeMillis() - latestFrameAtMs > STATIC_AFTER_MS && framesCaptured > 0) {
                        SharinganStateUpdate.update { it.copy(liveStatic = true) }
                    }
                } else {
                    SharinganStateUpdate.update { it.copy(liveStatic = false, lastError = null) }
                    framesCaptured++
                    lastCaptureAt = System.currentTimeMillis()
                    val changed = lastHash == null || frame.base64 != lastHash
                    if (changed) {
                        lastHash = frame.base64
                        framesChanged++
                        latestFrame = frame
                        latestFrameAtMs = System.currentTimeMillis()
                        val activity = src.topActivity()
                        latestActivity = activity
                        val pkg = packageOf(activity)
                        val activitySwitched = pkg != null && pkg != lastActivityPkg
                        if (activitySwitched) lastActivityPkg = pkg

                        synchronized(observations) {
                            observations.addFirst(Observation(elapsed, activity))
                            while (observations.size > MAX_OBSERVATIONS) observations.removeLast()
                        }

                        moe.shizuku.manager.sharingan.SharinganVisionHub.setLiveFrame(
                            base64 = frame.base64, mime = frame.mime,
                            activity = activity, frameHash = hashTag(src, frame),
                            source = "live_engine_${src.kind}"
                        )

                        SharinganStateUpdate.update {
                            it.copy(liveFrames = framesCaptured, liveObserved = framesChanged)
                        }

                        // PROACTIVITY (Gemini Live behaviour): a real app switch
                        // is the strongest "worth noticing" signal we have.
                        if (activitySwitched) maybeProactive(appContext ?: ctxFallback(), activity)
                        if (framesChanged % 20 == 0) {
                            moe.shizuku.manager.sharingan.ScreenCapture.pruneOld()
                        }
                    } else {
                        SharinganStateUpdate.update { it.copy(liveFrames = framesCaptured) }
                    }
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
            // r1437-expert-fix: an external stop() lands here as a
            // CancellationException — rethrow it, never treat a normal stop
            // as a crash (the old path double-stopped and could kill a NEW
            // session started right after).
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "perception loop ended: ${t.message}")
            SharinganStateUpdate.update { it.copy(lastError = "live_session_crashed") }
            stop(appContext ?: return)
        }
    }

    private fun hashTag(src: LiveScreenSource, frame: LiveFrameCodec.JpegFrame): String =
        try { src.hashOf(frame).take(12) } catch (_: Throwable) { "n${frame.bytes}" }

    private fun ctxFallback(): Context = appContext ?: throw IllegalStateException("no context")

    private val ACTIVITY_PAT_1 = Regex("u0 ([\\w.]+)/")
    private val ACTIVITY_PAT_2 = Regex("([\\w.]+)/(?:[\\w.]+\\.)?[\\w$]+\\s*[,}]")

    private fun packageOf(activityLine: String?): String? =
        activityLine?.let { line ->
            val m = ACTIVITY_PAT_1.find(line) ?: ACTIVITY_PAT_2.find(line)
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
     * → ONE provider call → spoken-style reply. [[ACT]] → routed by the caller.
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
        if (!st.voiceActive) return
        if (st.voiceSpeaking) return
        if (moe.shizuku.manager.agent.AgentOrchestrator.state.value.busy) return
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
                        proactiveSilent++
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
                            "(captured ${(System.currentTimeMillis() - latestFrameAtMs) / 1000}s ago, " +
                            "activity=${latestActivity?.take(120) ?: "unknown"}). " +
                            "Analyze it visually: layout, buttons, icons, colors — even what has no text. " +
                            "Never invent content not present in it.",
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
            "- t=+${"%.1f".format(o.tMs / 1000.0)}s activity=${o.activity ?: "unknown"}"
        }
    }

    private fun sessionSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000

    /**
     * [SHARINGAN LIVE — ACTIVE SESSION] block injected into EVERY orchestrator
     * round while the broadcast runs; the newest JPEG frame rides with it.
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
                sb.appendLine("- t=+${"%.1f".format(o.tMs / 1000.0)}s activity=${o.activity ?: "unknown"}")
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

/** Auto-stop notification bridge (kept out of the engine's import tree). */
internal object SharinganTaskNotifierViaLive {
    fun notifyAutoStop(context: Context) {
        moe.shizuku.manager.sharingan.SharinganTaskNotifier.taskDone(
            context,
            context.getString(moe.shizuku.manager.R.string.sharingan_live_auto_stopped, 30)
        )
    }
}
