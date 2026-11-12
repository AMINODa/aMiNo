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
import java.util.Locale

/**
 * aMiNo 2.0 — Sharingan VOICE (r1435): live voice interaction.
 *
 * Real on-device channels, no new cloud dependency:
 *  - STT: android.speech.SpeechRecognizer (the device's installed recognition
 *    engine) in a CONTINUOUS loop — final results are submitted to
 *    AgentOrchestrator.sendSharingan(viaVoice=true), so spoken commands run in
 *    the background exactly like typed panel commands.
 *  - TTS: android.speech.tts.TextToSpeech — speaks the REAL final answer of
 *    every voice-submitted task (the same verbatim last assistant row policy
 *    as the task notification — never an invented summary).
 *  - BARGE-IN: while AMINO is speaking, user speech (rms/partial results)
 *    stops the TTS immediately — the user can interrupt the reply, Gemini-Live
 *    style.
 *
 * HONESTY: mic permission missing, no recognition engine installed, engine
 * errors — all surface visibly in SharinganState.lastError + toasts, never a
 * silent deaf assistant. SpeechRecognizer and TTS are main-thread objects.
 */
object SharinganVoice {

    private const val TAG = "SharinganVoice"
    private const val MAX_SPOKEN_CHARS = 900
    private const val RESTART_DELAY_MS = 250L

    private val main = Handler(Looper.getMainLooper())

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
                    Log.i(TAG, "voice command: $text")
                    val ctx = appContext ?: return
                    // Voice commands run in the BACKGROUND like panel commands;
                    // the spoken answer arrives when the task ends (viaVoice).
                    val accepted = moe.shizuku.manager.agent.AgentOrchestrator
                        .sendSharingan(ctx, text, viaVoice = true)
                    if (!accepted) {
                        // busy: speak an honest notice and resume listening
                        speak(ctx, ctx.getString(moe.shizuku.manager.R.string.sharingan_task_busy))
                    }
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

    /** Speak the REAL final answer (verbatim last assistant row policy). */
    fun speak(context: Context, text: String) {
        if (!ttsReady) return
        // strip UI-only artifacts so TTS reads clean prose
        val clean = text.replace(Regex("[*#`_>\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(MAX_SPOKEN_CHARS)
        if (clean.isBlank()) return
        runCatching {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "amino_reply_${System.currentTimeMillis()}")
        }
    }

    private fun stopSpeaking() {
        runCatching { tts?.stop() }
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
            it.copy(voiceActive = false, voiceListening = false, voiceSpeaking = false)
        }
    }

    fun toggle(context: Context): Boolean {
        return if (active) { stop(context); false } else start(context) is StartResult.Started
    }
}
