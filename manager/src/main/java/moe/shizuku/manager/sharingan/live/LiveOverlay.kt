package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.sharingan.AmaterasuEyeView
import moe.shizuku.manager.sharingan.SharinganState
import moe.shizuku.manager.sharingan.SharinganVoice

/**
 * aMiNo 2.0 (r1436) — LIVE OVERLAY: the immersive Gemini-Live-style broadcast
 * UI, Sharingan-themed. Research reference: during Gemini Live screen sharing
 * the whole screen stays usable and the ONLY chrome is a bottom dock with a
 * live waveform + state + an end control. This overlay reproduces exactly
 * that contract with the Amaterasu identity:
 *
 *  - a bottom dock (TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW):
 *      [Amaterasu eye] [phase + honest stats] [voice waveform] [⏹ end]
 *  - the waveform is a real Canvas animation driven by the mic level while
 *    listening and by the speaking state while TTS plays (Gemini's waveform);
 *  - the eye cries BLACK TEARS (Amaterasu) the whole broadcast, spins faster
 *    while thinking, and burns black flames while the executor works;
 *  - the stats line is the requested "screen share performance monitor":
 *    frames · changes · model turns · last latency in ms · frame bytes.
 *  - touches outside the dock pass straight through to the app underneath
 *    (FLAG_NOT_TOUCH_MODAL) — the user keeps using their phone normally.
 */
object LiveOverlay {

    private var windowManager: WindowManager? = null
    private var dock: LinearLayout? = null
    private var eye: AmaterasuEyeView? = null
    private var wave: WaveView? = null
    private var phaseLabel: TextView? = null
    private var statsLabel: TextView? = null
    private var stateScope: CoroutineScope? = null

    val visible: Boolean get() = dock != null

    fun show(service: Context) {
        if (dock != null) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dm = service.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // ── dock container (Gemini Live bottom bar, aMiNo palette) ──────────
        val dockLayout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E60B0B0E"))   // aMiNo stage, 90% alpha
                setStroke(dp(1), Color.parseColor("#2B2B33"))
            }
            elevation = dp(6).toFloat()
        }

        // ── the Amaterasu eye ────────────────────────────────────────────────
        val eyeView = AmaterasuEyeView(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(10)
            }
        }
        eye = eyeView

        // ── phase + stats column ────────────────────────────────────────────
        val col = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val phase = TextView(service).apply {
            setTextColor(Color.parseColor("#F2F2F5"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = service.getString(R.string.sharingan_live_phase_watching)
        }
        val stats = TextView(service).apply {
            setTextColor(Color.parseColor("#9E9EA8"))
            textSize = 10.5f
            maxLines = 1
        }
        phaseLabel = phase; statsLabel = stats
        col.addView(phase); col.addView(stats)

        // ── the voice waveform (Gemini Live signature) ──────────────────────
        val waveView = WaveView(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(36)).apply {
                marginEnd = dp(10)
            }
        }
        wave = waveView

        // ── end button (the only control, like Gemini's stop) ───────────────
        val endBtn = TextView(service).apply {
            text = "⏹"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
                setStroke(dp(2), Color.parseColor("#FF867C"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = service.getString(R.string.sharingan_live_end_cd)
        }
        endBtn.setOnClickListener {
            val ctx = it.context
            try { SharinganVoice.stop(ctx) } catch (_: Throwable) {}
            try { hide() } catch (_: Throwable) {}
            try { SharinganLiveEngine.stop(ctx) } catch (_: Throwable) {}
            Toast.makeText(ctx, ctx.getString(R.string.sharingan_live_stopped), Toast.LENGTH_SHORT).show()
        }

        dockLayout.addView(eyeView)
        dockLayout.addView(col)
        dockLayout.addView(waveView)
        dockLayout.addView(endBtn)
        dock = dockLayout

        // ── window: bottom dock, passthrough everywhere else ────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }

        // ── live state → eye mode + phase text + waveform + stats ───────────
        stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { cs ->
            cs.launch {
                SharinganState.state.collect { s ->
                    val speaking = s.voiceSpeaking
                    val listening = s.voiceListening
                    val p = when {
                        speaking -> "speaking"
                        s.livePhase == SharinganLiveEngine.PHASE_EXECUTING -> "executing"
                        s.livePhase == SharinganLiveEngine.PHASE_THINKING -> "thinking"
                        listening -> "listening"
                        else -> "watching"
                    }
                    eyeView.setMode(when (p) {
                        "executing" -> AmaterasuEyeView.MODE_EXECUTING
                        "thinking" -> AmaterasuEyeView.MODE_THINKING
                        else -> AmaterasuEyeView.MODE_AMATERASU  // tears while live
                    })
                    phase.text = when (p) {
                        "speaking" -> service.getString(R.string.sharingan_live_phase_speaking)
                        "executing" -> service.getString(R.string.sharingan_live_phase_executing)
                        "thinking" -> service.getString(R.string.sharingan_live_phase_thinking)
                        "listening" -> service.getString(R.string.sharingan_live_phase_listening)
                        else -> service.getString(R.string.sharingan_live_phase_watching)
                    }
                    waveView.mode = when (p) {
                        "speaking" -> WaveView.MODE_SPEAKING
                        "listening" -> WaveView.MODE_LISTENING
                        "thinking" -> WaveView.MODE_THINKING
                        "executing" -> WaveView.MODE_EXECUTING
                        else -> WaveView.MODE_IDLE
                    }
                    stats.text = service.getString(
                        R.string.sharingan_live_stats,
                        s.liveFrames, s.liveObserved, s.liveCalls,
                        s.liveLatencyMs.toString()
                    )
                }
            }
        }

        try {
            wm.addView(dockLayout, params)
        } catch (t: Throwable) {
            dock = null
            Toast.makeText(service, service.getString(R.string.sharingan_live_overlay_failed), Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        stateScope?.cancel(); stateScope = null
        val v = dock ?: return
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        dock = null; eye = null; wave = null; phaseLabel = null; statsLabel = null
        windowManager = null
    }

    fun toggle(service: Context) {
        if (visible) hide() else show(service)
    }

    /**
     * The waveform: 22 bars animating like Gemini Live's voice indicator.
     * Amplitude source is the real mode: mic level while listening, a smooth
     * speech-like envelope while speaking, a soft pulse while thinking.
     */
    class WaveView(context: Context) : View(context) {

        companion object {
            const val MODE_IDLE = 0
            const val MODE_LISTENING = 1
            const val MODE_THINKING = 2
            const val MODE_SPEAKING = 3
            const val MODE_EXECUTING = 4
        }

        @Volatile var mode: Int = MODE_IDLE
            set(value) { field = value; postInvalidateOnAnimation() }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF8A80")
        }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A4A52")
        }
        private val bars = 22

        override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = System.currentTimeMillis()
            val w = width.toFloat(); val h = height.toFloat()
            val barW = w / bars * 0.55f
            val gap = w / bars
            val mid = h / 2f
            val amp = when (mode) {
                MODE_SPEAKING -> 0.9f
                MODE_LISTENING -> (0.25f + 0.65f * SharinganState.state.value.micLevel)
                MODE_THINKING -> 0.45f
                MODE_EXECUTING -> 0.6f
                else -> 0.12f
            }
            for (i in 0 until bars) {
                val x = i * gap + gap / 2f
                val envelope = 0.35f + 0.65f * Math.abs(Math.sin(i * 0.9)).toFloat()
                val waveA = Math.sin(t / 90.0 + i * 0.55).toFloat() * amp * envelope
                val bh = (h * 0.08f + (waveA + 0.5f * amp * envelope) * h * 0.42f)
                    .coerceIn(h * 0.06f, h * 0.95f)
                val p = if (mode == MODE_IDLE) dimPaint else paint
                canvas.drawRoundRect(
                    x - barW / 2f, mid - bh, x + barW / 2f, mid + bh,
                    barW / 2f, barW / 2f, p)
            }
            postInvalidateOnAnimation()
        }
    }
}
