package moe.shizuku.manager.sharingan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * aMiNo 2.0 — THE AMATERASU EYE (r1435): the Sharingan eye that cries black
 * tears while the live session broadcasts — the user's exact ask: «ديزاين
 * sharingan مماثل لـ Gemini Live لكن بعين sharingan وهي تدمع دموع سوداء مثل
 * أنمي ناروتو، بالضبط تقنية أماتيراسو».
 *
 * Three modes, driven from SharinganState (the bubble swaps modes live):
 *  - MODE_IDLE       : the classic red Sharingan — iris ring + 3 tomoe slowly
 *                      rotating, gentle red glow breathing.
 *  - MODE_AMATERASU  : the LIVE/broadcast state — the eye darkens, the tomoe
 *                      spin faster, and BLACK TEARS (Amaterasu, the flame of
 *                      the copy-wheel eye) drip from the lower rim, with
 *                      wavering black flames flickering along it.
 *  - MODE_VOICE      : voice interaction — the eye gains a pulsing outer ring
 *                      driven by the mic amplitude (setAmplitude from RmsChanged).
 *  - MODE_THINKING   : r1436 — a live model turn is in flight (fast tomoe).
 *  - MODE_EXECUTING  : r1436 — the executor is acting: AMATERASU CASTING
 *                      (heavier tears, taller flames, fast spin).
 *
 * Pure Canvas — no drawables, scales to any view size (48dp bubble, panel,
 * future surfaces). The animation loop runs only while attached.
 */
class AmaterasuEyeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MODE_IDLE = 0
        const val MODE_AMATERASU = 1
        const val MODE_VOICE = 2
        // r1436 — Gemini-Live phases: the eye reacts to the broadcast state.
        const val MODE_THINKING = 3    // tomoe spin fast (model turn in flight)
        const val MODE_EXECUTING = 4   // AMATERASU CASTING: black flames dominate
    }

    @Volatile private var mode: Int = MODE_IDLE
    @Volatile private var amplitude: Float = 0f   // 0..1 mic rms (voice mode)

    // ── paints ──────────────────────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tomoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val tearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF8A80")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scleraGradient: RadialGradient? = null
    private var glowGradient: RadialGradient? = null

    init {
        // Amaterasu tears are PURE black (the user's spec: دموع سوداء بالضبط).
        tearPaint.alpha = 235
        flamePaint.alpha = 200
        tomoePaint.typeface = Typeface.DEFAULT_BOLD
    }

    fun setMode(newMode: Int) {
        if (mode != newMode) { mode = newMode; invalidate() }
    }

    /** Mic rms (dB-ish from RecognitionListener.onRmsChanged) → pulse. */
    fun setAmplitude(rms: Float) {
        val clamped = ((rms + 2f) / 12f).coerceIn(0f, 1f)
        if (Math.abs(clamped - amplitude) > 0.05f) {
            amplitude = clamped
            invalidate()
        }
    }

    /** r1436 — normalized 0..1 mic level (from SharinganState.micLevel). */
    fun setAmplitudeNorm(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        if (Math.abs(clamped - amplitude) > 0.05f) {
            amplitude = clamped
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = w.toFloat()
        scleraGradient = RadialGradient(s / 2f, s / 2f, s * 0.42f,
            intArrayOf(Color.parseColor("#FF5252"), Color.parseColor("#C62828"), Color.parseColor("#7F0000")),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        glowGradient = RadialGradient(s / 2f, s / 2f, s * 0.5f,
            intArrayOf(Color.parseColor("#4DE53935"), Color.parseColor("#00E53935")),
            floatArrayOf(0.62f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.elapsedRealtime()
        val s = width.toFloat()
        val c = s / 2f

        // ── 1. dark backdrop (the bubble body) ──────────────────────────────
        bgPaint.color = Color.parseColor("#0B0B0E")
        canvas.drawCircle(c, c, c - 1f, bgPaint)

        // ── 2. breathing red glow (stronger in Amaterasu/thinking/executing) ──
        val breathe = 0.5f + 0.5f * sin(t / 900.0)
        val glowBoost = when (mode) {
            MODE_AMATERASU -> 0.85f
            MODE_VOICE -> 0.6f + 0.4f * amplitude
            MODE_THINKING -> 0.95f
            MODE_EXECUTING -> 1.0f
            else -> 0.45f
        }
        glowPaint.shader = glowGradient
        glowPaint.alpha = ((0.35f + 0.45f * breathe) * glowBoost * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(c, c, c - 1f, glowPaint)

        // ── 3. voice pulse ring ────────────────────────────────────────────
        if (mode == MODE_VOICE) {
            val ringR = c * (0.62f + 0.30f * amplitude)
            pulsePaint.strokeWidth = s * (0.02f + 0.02f * amplitude)
            pulsePaint.alpha = (120 + 120 * amplitude).toInt().coerceIn(0, 255)
            canvas.drawCircle(c, c, ringR, pulsePaint)
        }

        // ── 4. sclera (red eye ball) ───────────────────────────────────────
        val scleraR = c * 0.62f
        scleraPaint.shader = scleraGradient
        canvas.drawCircle(c, c, scleraR, scleraPaint)

        // ── 5. iris ring (the Sharingan's dark rim) ────────────────────────
        val irisR = scleraR * 0.92f
        irisPaint.color = when (mode) {
            MODE_AMATERASU -> Color.parseColor("#4A0A0A")
            MODE_EXECUTING -> Color.parseColor("#3A0505")
            MODE_THINKING -> Color.parseColor("#551515")
            else -> Color.parseColor("#3E1010")
        }
        canvas.drawCircle(c, c, irisR, irisPaint)

        // ── 6. pupil ───────────────────────────────────────────────────────
        pupilPaint.color = when (mode) {
            MODE_AMATERASU -> Color.parseColor("#1A0000")
            MODE_EXECUTING -> Color.parseColor("#0D0000")
            else -> Color.parseColor("#2A0808")
        }
        canvas.drawCircle(c, c, scleraR * 0.42f, pupilPaint)

        // ── 7. three tomoe, rotating (Gemini-phase speeds) ──────────────────
        val spinSpeed = when (mode) {
            MODE_AMATERASU -> 70f
            MODE_THINKING -> 170f
            MODE_EXECUTING -> 120f
            else -> 14f
        }   // deg/sec
        val rot = (t / 1000f) * spinSpeed
        val tomoeOrbit = scleraR * 0.55f
        val tomoeR = scleraR * 0.16f
        for (i in 0 until 3) {
            val a = Math.toRadians((rot + i * 120.0)).toFloat()
            val px = c + tomoeOrbit * cos(a)
            val py = c + tomoeOrbit * sin(a)
            // comma head
            canvas.drawCircle(px, py, tomoeR, tomoePaint)
            // comma tail (curved stroke, round cap)
            tomoePaint.style = Paint.Style.STROKE
            tomoePaint.strokeWidth = tomoeR * 0.9f
            tomoePaint.strokeCap = Paint.Cap.ROUND
            val tailA = Math.toRadians((rot + i * 120.0 - 38.0)).toFloat()
            val tx = px + tomoeR * 2.0f * cos(tailA)
            val ty = py + tomoeR * 2.0f * sin(tailA)
            canvas.drawLine(px, py, tx, ty, tomoePaint)
            tomoePaint.style = Paint.Style.FILL
        }

        // ── 8. AMATERASU: black tears dripping from the lower rim ──────────
        if (mode == MODE_AMATERASU || mode == MODE_EXECUTING) {
            val tearCycle = if (mode == MODE_EXECUTING) 1_100f else 1_600f    // ms per tear drop
            for (i in 0 until 3) {
                val phase = ((t + i * tearCycle / 3f) % tearCycle) / tearCycle  // 0..1
                val sway = sin(phase * Math.PI.toFloat() * 2f + i * 2.1f) * s * 0.03f
                val dropX = (c + (i - 1) * s * 0.16f + sway).toFloat()
                val startY = c + scleraR * 0.86f
                val dropLen = if (mode == MODE_EXECUTING) s * 0.42f else s * 0.34f
                val dropY = startY + phase * dropLen
                val dropR = s * (0.045f + 0.02f * sin(phase * Math.PI.toFloat()))
                val alpha = (255 * (1f - phase)).toInt().coerceIn(0, 235)
                tearPaint.alpha = alpha
                // teardrop = head circle + tapered stem
                canvas.drawCircle(dropX, dropY, dropR, tearPaint)
                tearPaint.style = Paint.Style.STROKE
                tearPaint.strokeWidth = dropR * 0.85f
                tearPaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(dropX, dropY - dropR * 0.2f, dropX, dropY - dropR * 2.6f, tearPaint)
                tearPaint.style = Paint.Style.FILL
            }

            // ── 9. AMATERASU black flames flickering on the lower rim ──────
            val flames = if (mode == MODE_EXECUTING) 7 else 5
            for (i in 0 until flames) {
                val fx = c + (i - (flames - 1) / 2f) * s * 0.13f
                val baseY = c + scleraR * 0.92f
                val flick = 0.65f + 0.35f * sin(t / 130f + i * 1.7f)
                val fh = s * (if (mode == MODE_EXECUTING) 0.22f else 0.16f) * flick
                val fw = s * 0.055f
                flamePaint.alpha = (140 + 90 * flick).toInt().coerceIn(0, 220)
                val p = android.graphics.Path().apply {
                    moveTo(fx - fw, baseY)
                    quadTo(fx - fw * 0.6f, baseY - fh * 0.55f, fx, baseY - fh)
                    quadTo(fx + fw * 0.6f, baseY - fh * 0.55f, fx + fw, baseY)
                    close()
                }
                canvas.drawPath(p, flamePaint)
            }
        }

        // keep the loop alive while attached
        postInvalidateOnAnimation()
    }
}
