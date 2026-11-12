package moe.shizuku.manager.sharingan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import java.util.Locale

/**
 * aMiNo 2.0 — Sharingan VOICE (r1435, rebuilt r1436): the Gemini-Live-style
 * voice channel over real on-device engines.
 *
 * r1435's model (every utterance = a full background agent task) is REPLACED
 * when a live broadcast is active — the user asked for Gemini Live's
 * effectiveness, and Gemini Live is a CONTINUOUS CONVERSATION, not a
 * command dispatcher:
 *
 *  - LIVE BROADCAST ACTIVE → each utterance goes to
 *    SharinganLiveEngine.say(): one fast provider call with the live history
 *    + the newest REAL frame, the spoken-style reply is TTS'd sentence by
 *    sentence (first sentence starts speaking immediately — streaming feel).
 *    If the reply carries [[ACT]]…[[/ACT]], the action is routed to
 *    AgentOrchestrator.sendSharingan(viaVoice=true) — real shell execution in
 *    the background — and its final verbatim answer is spoken when done.
 *  - NO LIVE BROADCAST → the r1435 background-command path (unchanged).
 *
 * BARGE-IN (Gemini Live's interruption): while AMINO speaks, user speech
 * (rms/partial/onBeginningOfSpeech) stops the TTS queue instantly — the whole
 * queued reply is dropped, exactly like the Live API cancels generation on
 * interruption. HONESTY: mic permission missing, no recognition engine,
 * engine errors — all surface visibly, never a silent deaf assistant.
 */
object SharinganVoice {

    private const val TAG = "SharinganVoice"
    private const val MAX_SPOKEN_CHARS = 900
    private const val CHUNK_MAX_CHARS = 180          // sentence-chunk target
    private const val RESTART_DELAY_MS = 250L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var appContext: Context? = null

    var active = false
        private set
    @Volatile var listening = false
        private set
    @Volatile var speaking = false
        private set

    /** Result of a start attempt — the panel turns this into honest UI. */
    sealed class StartResult {
        object Started : StartResult()
        data class Refused(val reasonRes: Int, val reasonArg: String? = null) : StartResult()
    }

    fun start(context: Context): StartResult {
        if (active) return StartResult.Started
        val perm = android.Manifest.permission.RECORD_AUDIO
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SharinganState.update { it.copy(lastError = "mic_permission_missing") }
            return StartResult.Refused(moe.shizuku.manager.R.string.sharingan_voice_mic_denied)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            SharinganState.update { it.copy(lastError = "no_recognition_engine") }
            return StartResult.Refused(moe.shizuku.manager.R.string.sharingan_voice_no_engine)
        }
        appContext = context.applicationContext
        active = true
        SharinganState.update { it.copy(voiceActive = true, lastError = null) }

        // --- TTS first (so the first reply can be spoken) ---
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching { tts?.language = Locale.getDefault() }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                        SharinganState.update { it.copy(voiceSpeaking = true, voiceListening = false) }
                    }
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        SharinganState.update { it.copy(voiceSpeaking = false) }
                        if (active) main.postDelayed({ startListeningInternal() }, RESTART_DELAY_MS)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        SharinganState.update { it.copy(voiceSpeaking = false, lastError = "tts_error") }
                        if (active) main.postDelayed({ startListeningInternal() }, RESTART_DELAY_MS)
                    }
                })
            } else {
                SharinganState.update { it.copy(lastError = "tts_init_failed") }
            }
            if (active) startListeningInternal()
        }

        // --- recognizer ---
        recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    SharinganState.update { it.copy(voiceListening = true) }
                }
                override fun onBeginningOfSpeech() {
                    // BARGE-IN: the user started talking while AMINO speaks.
                    if (speaking) stopSpeaking()
                }
                override fun onRmsChanged(rmsdB: Float) {
                    // real mic level → waveform + eye pulse (throttled to 0.06 steps)
                    val lvl = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    val st = SharinganState.state.value
                    if (Math.abs(lvl - st.micLevel) > 0.06f) {
                        SharinganState.update { it.copy(micLevel = lvl) }
                    }
                    if (speaking && rmsdB > 6f) stopSpeaking()
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listening = false
                    SharinganState.update { it.copy(voiceListening = false) }
                }
                override fun onError(error: Int) {
                    listening = false
                    SharinganState.update { it.copy(voiceListening = false) }
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            stop(context.applicationContext)
                            SharinganState.update { it.copy(lastError = "mic_permission_missing") }
                            return
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            main.postDelayed({ if (active) startListeningInternal() }, 1_000L)
                        // no match / speech timeout / network hiccups → keep the loop alive
                        else -> main.postDelayed({ if (active) startListeningInternal() }, RESTART_DELAY_MS)
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    SharinganState.update { it.copy(voiceListening = false) }
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) {
                        main.postDelayed({ if (active) startListeningInternal() }, RESTART_DELAY_MS)
                        return
                    }
                    Log.i(TAG, "voice utterance: $text")
                    handleUtterance(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    // BARGE-IN (partial path): any partial while speaking cuts the reply
                    if (speaking) stopSpeaking()
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        return StartResult.Started
    }

    /**
     * The Gemini-Live routing decision:
     *  - live broadcast active → continuous conversation (fast path)
     *  - otherwise → background command via the orchestrator (r1435 path)
     */
    private fun handleUtterance(text: String) {
        val ctx = appContext ?: return
        if (moe.shizuku.manager.sharingan.live.SharinganLiveEngine.active) {
            // ── LIVE CONVERSATION: fast turn against the live session ──────
            scope.launch {
                val result = moe.shizuku.manager.sharingan.live.SharinganLiveEngine.say(ctx, text)
                when {
                    result.refused && result.error == "busy" ->
                        speak(ctx, ctx.getString(R.string.sharingan_task_busy))
                    result.error != null && result.reply == null ->
                        speak(ctx, ctx.getString(R.string.sharingan_live_turn_error, result.error))
                    else -> {
                        // speak the conversational reply (chunked, barge-in-able)
                        speak(ctx, result.reply ?: "")
                        // the model requested a REAL action → route to the engine
                        val act = result.actionCommand
                        if (!act.isNullOrBlank()) {
                            Log.i(TAG, "live ACT routed to orchestrator: ${act.take(80)}")
                            val accepted = moe.shizuku.manager.agent.AgentOrchestrator
                                .sendSharingan(ctx, act, viaVoice = true)
                            if (!accepted) {
                                speak(ctx, ctx.getString(R.string.sharingan_task_busy))
                            } else {
                                moe.shizuku.manager.sharingan.live.SharinganLiveEngine.noteSpoken()
                            }
                        }
                    }
                }
            }
        } else {
            // ── NO BROADCAST: voice commands run in the BACKGROUND like panel
            // commands; the spoken answer arrives when the task ends (viaVoice).
            val accepted = moe.shizuku.manager.agent.AgentOrchestrator
                .sendSharingan(ctx, text, viaVoice = true)
            if (!accepted) {
                speak(ctx, ctx.getString(R.string.sharingan_task_busy))
            }
        }
    }

    private fun startListeningInternal() {
        if (!active || recognizer == null) return
        if (speaking) return   // never listen while talking (barge-in handles the cut)
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
        }.onFailure { Log.w(TAG, "startListening failed: ${it.message}") }
    }

    /**
     * Speak text — SENTENCE-CHUNKED (r1436): the first chunk starts speaking
     * immediately and the rest is queued, so a long answer starts audible
     * while later chunks are still being synthesized (the Gemini Live
     * streaming feel with the on-device TTS). Barge-in drops the whole queue.
     */
    fun speak(context: Context, text: String) {
        if (!ttsReady) return
        // strip UI-only artifacts so TTS reads clean prose
        val clean = text.replace(Regex("[*#`_>\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(MAX_SPOKEN_CHARS)
        if (clean.isBlank()) return
        runCatching {
            val chunks = splitSentences(clean)
            val base = "amino_reply_${System.currentTimeMillis()}"
            chunks.forEachIndexed { i, chunk ->
                val params = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "${base}_$i")
                }
                tts?.speak(chunk,
                    if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    params, "${base}_$i")
            }
        }
    }

    /** Split into spoken-friendly sentence chunks (Arabic + Latin punctuation). */
    private fun splitSentences(text: String): List<String> {
        val raw = text.split(Regex("(?<=[.!؟?،;؛:])\\s+"))
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (s in raw) {
            val piece = s.trim()
            if (piece.isEmpty()) continue
            if (buf.length + piece.length + 1 <= CHUNK_MAX_CHARS) {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(piece)
            } else {
                if (buf.isNotEmpty()) out.add(buf.toString())
                if (piece.length <= CHUNK_MAX_CHARS) buf.clear().append(piece)
                else {
                    // hard-wrap oversized chunk (URLs / long tokens)
                    var i = 0
                    while (i < piece.length) {
                        out.add(piece.substring(i, minOf(i + CHUNK_MAX_CHARS, piece.length)))
                        i += CHUNK_MAX_CHARS
                    }
                    buf.clear()
                }
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.ifEmpty { listOf(text) }
    }

    private fun stopSpeaking() {
        runCatching { tts?.stop() }   // QUEUE_FLUSH + QUEUE_ADD chunks all die
        speaking = false
        SharinganState.update { it.copy(voiceSpeaking = false) }
    }

    fun stop(context: Context) {
        active = false
        listening = false
        speaking = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        appContext = null
        SharinganState.update {
            it.copy(voiceActive = false, voiceListening = false, voiceSpeaking = false, micLevel = 0f)
        }
    }

    fun toggle(context: Context): Boolean {
        return if (active) { stop(context); false } else start(context) is StartResult.Started
    }
}
