package moe.shizuku.manager.sharingan

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
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
import moe.shizuku.manager.agent.AgentOrchestrator
import moe.shizuku.manager.agent.AgentStatus
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.sharingan.live.LiveBroadcastService
import moe.shizuku.manager.sharingan.live.LiveConsentActivity
import moe.shizuku.manager.sharingan.live.LivePermissionActivity

/**
 * The Sharingan floating panel v3 (r1437 — LIVE FIRST, the user's order:
 * «احذف اترك فقط live watching … بدون الضغط علا تسجيل و ارسال»).
 *
 * WHAT IS DELETED (r1413-r1436 structure):
 *  - the command input + ⚡ send (typed commands live in the full chat UI)
 *  - Record ⏺ / Execute (context staging) — the OLD text-trace path
 *  - Mic 🎤 button — voice is now PART of the broadcast (auto starts/stops)
 *  - SharinganRecorder usage — the old OCR-flavored trace loop
 *
 * WHAT REMAINS — ONE button:
 *  [ 👁 SHARINGAN            ● ]
 *  [      START LIVE ▶        ]  → permission trampoline → system projection
 *  [ status                    ]    consent → LiveBroadcastService (FGS)
 *  [ last real answer          ]  (dock = the Gemini-Live UI during broadcast)
 *
 * Auto-hide still exists (60s idle) but NEVER while a broadcast runs — the
 * r1436 «يختفي خلال ثواني» complaint is structurally impossible now: the
 * broadcast is an FGS, the dock self-heals, the panel can hide freely.
 */
object SharinganPanel {

    private const val AUTO_HIDE_MS = 60_000L

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var scope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var led: View? = null
    private var liveBtn: TextView? = null
    private var statusLine: TextView? = null
    private var resultView: TextView? = null
    private var eyeView: AmaterasuEyeView? = null

    private val autoHideRunnable: Runnable = object : Runnable {
        override fun run() {
            // Never hide under an active broadcast — the user is mid-session.
            if (SharinganState.state.value.liveActive || AgentOrchestrator.state.value.busy) {
                mainHandler.postDelayed(this, AUTO_HIDE_MS)
                return
            }
            hide()
        }
    }

    private fun dp(v: Int, panel: LinearLayout): Int =
        (v * panel.resources.displayMetrics.density).toInt()

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusDp }

    val visible: Boolean get() = rootView != null

    fun show(service: android.content.Context) {
        if (rootView != null) return
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F20B0B0E"), 18f)
            setPadding(dp(14, this), dp(12, this), dp(14, this), dp(12, this))
            elevation = dp(6, this).toFloat()
        }
        rootView = panel

        // ── Row 1: the LIVE eye + title + LED (draggable) ────────────────────
        val row1 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val eye = AmaterasuEyeView(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30, panel), dp(30, panel)).apply {
                marginEnd = dp(8, panel) }
        }
        eyeView = eye
        row1.addView(eye)
        val title = TextView(service).apply {
            text = "SHARINGAN"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(title)
        val ledView = View(service).apply {
            background = circle(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(dp(14, panel), dp(14, panel))
        }
        led = ledView
        row1.addView(ledView)
        panel.addView(row1)

        // ── Row 2: THE one button — START LIVE / STOP ────────────────────────
        val liveButton = TextView(service).apply {
            text = service.getString(R.string.sharingan_panel_live_start)
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#B71C1C"), 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46, panel)
            ).apply { topMargin = dp(10, panel) }
        }
        liveBtn = liveButton
        panel.addView(liveButton)

        // ── Row 3: status line ───────────────────────────────────────────────
        val status = TextView(service).apply {
            text = service.getString(R.string.sharingan_status_ready)
            setTextColor(Color.parseColor("#9E9EA8"))
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8, panel) }
        }
        statusLine = status
        panel.addView(status)

        // ── Row 4: last real answer (orchestrator) ───────────────────────────
        val result = TextView(service).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 12f
            maxLines = 6
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6, panel) }
        }
        resultView = result
        panel.addView(result)

        // ── window params ────────────────────────────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24, panel); y = dp(180, panel)
        }

        // ── drag (title row) + tap-to-collapse ───────────────────────────────
        val touch = object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            private var dragging = false
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) dragging = true
                        if (dragging) { params.x = startX + dx; params.y = startY + dy; wm.updateViewLayout(panel, params) }
                    }
                    MotionEvent.ACTION_UP -> if (!dragging) toggleCompact(panel)
                }
                resetAutoHide()
                return true
            }
        }
        row1.setOnTouchListener(touch)

        // ── THE button: one tap → permission → consent → FGS broadcast ──────
        liveButton.setOnClickListener {
            resetAutoHide()
            if (LiveBroadcastService.running || SharinganState.state.value.liveActive) {
                LiveBroadcastService.stopBroadcast(service)
                Toast.makeText(service, service.getString(R.string.sharingan_live_stopped), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ApiKeysStore.hasKey(service)) {
                statusLine?.text = service.getString(R.string.sharingan_no_apikey)
                Toast.makeText(service, service.getString(R.string.sharingan_no_apikey), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val micOk = LiveBroadcastService.micGranted(service)
            val notifOk = if (android.os.Build.VERSION.SDK_INT >= 33)
                androidx.core.content.ContextCompat.checkSelfPermission(
                    service, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            else true
            val intent = if (!micOk || !notifOk)
                Intent(service, LivePermissionActivity::class.java)
            else Intent(service, LiveConsentActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { service.startActivity(intent) }.onFailure {
                Toast.makeText(service, service.getString(R.string.sharingan_live_overlay_failed), Toast.LENGTH_SHORT).show()
            }
        }

        // ── state → UI ───────────────────────────────────────────────────────
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { cs ->
            cs.launch {
                SharinganState.state.collect { s ->
                    ledView.background = circle(
                        when {
                            s.liveActive -> Color.parseColor("#E53935")   // broadcasting
                            !s.serviceUp -> Color.parseColor("#9E9E9E")   // offline
                            else -> Color.parseColor("#4CAF50")           // ready
                        }
                    )
                    val live = s.liveActive || LiveBroadcastService.running
                    liveButton.text = service.getString(
                        if (live) R.string.sharingan_panel_live_stop else R.string.sharingan_panel_live_start)
                    liveButton.background = rounded(
                        if (live) Color.parseColor("#455A64") else Color.parseColor("#B71C1C"), 14f)
                    // the eye mirrors the broadcast mode (black tears while live)
                    eyeView?.setMode(when {
                        !live && s.voiceActive -> AmaterasuEyeView.MODE_VOICE
                        live && s.livePhase == SharinganLiveEnginePhase.EXECUTING -> AmaterasuEyeView.MODE_EXECUTING
                        live && s.livePhase == SharinganLiveEnginePhase.THINKING -> AmaterasuEyeView.MODE_THINKING
                        live -> AmaterasuEyeView.MODE_AMATERASU
                        else -> AmaterasuEyeView.MODE_IDLE
                    })
                    if (!AgentOrchestrator.state.value.busy) {
                        statusLine?.text = when {
                            live && s.lastError != null -> errorHuman(service, s.lastError!!)
                            live -> service.getString(
                                if (s.liveSource == "shell") R.string.sharingan_status_live_shell
                                else R.string.sharingan_status_live,
                                s.liveFrames, s.liveObserved
                            ) + if (s.liveStatic) " · " + service.getString(R.string.sharingan_live_static) else ""
                            !s.serviceUp -> service.getString(R.string.sharingan_status_offline)
                            s.lastError != null -> errorHuman(service, s.lastError!!)
                            else -> service.getString(R.string.sharingan_status_ready)
                        }
                    }
                }
            }
            cs.launch {
                AgentOrchestrator.state.collect { s ->
                    if (s.busy) {
                        statusLine?.text = when (val st = s.status) {
                            is AgentStatus.ExecutingTool ->
                                service.getString(R.string.sharingan_status_exec, st.name)
                            is AgentStatus.Verifying ->
                                service.getString(R.string.sharingan_status_verifying)
                            else -> service.getString(R.string.sharingan_status_thinking)
                        }
                    }
                    val last = s.items.lastOrNull {
                        it.role == "assistant" && it.toolName == null && it.text.isNotBlank()
                    }
                    if (last != null) {
                        resultView?.visibility = View.VISIBLE
                        resultView?.text = last.text.take(600) +
                            if (last.text.length > 600) "…" else ""
                    }
                }
            }
        }

        runCatching { wm.addView(panel, params) }.onFailure {
            // r1437-expert-fix: a failed addView must not leave rootView set
            // (visible==true forever → every later show() early-returns and
            // the panel dies permanently).
            rootView = null; led = null; statusLine = null; liveBtn = null
            resultView = null; eyeView = null
            scope?.cancel(); scope = null
            SharinganState.update { it.copy(panelVisible = false) }
            return
        }
        SharinganState.update { it.copy(panelVisible = true) }
        resetAutoHide()
    }

    /** Honest human text for the real error codes (never a raw dump). */
    private fun errorHuman(service: android.content.Context, code: String): String = when (code) {
        "live_no_source_projection_denied_shell_down" ->
            service.getString(R.string.sharingan_err_no_source)
        "live_mic_denied_vision_only" ->
            service.getString(R.string.sharingan_live_vision_only)
        "live_notifications_denied" ->
            service.getString(R.string.sharingan_err_notifications_denied)
        "live_projection_denied_shell_mode" ->
            service.getString(R.string.sharingan_status_live_shell_fallback_note)
        "live_capture_failed" ->
            service.getString(R.string.sharingan_err_capture)
        "live_projection_stopped_by_system" ->
            service.getString(R.string.sharingan_err_projection_stopped)
        else -> code
    }

    fun hide() {
        val v = rootView ?: return
        scope?.cancel(); scope = null
        mainHandler.removeCallbacks(autoHideRunnable)
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        rootView = null; led = null; statusLine = null; liveBtn = null
        resultView = null; eyeView = null
        SharinganState.update { it.copy(panelVisible = false) }
        // The eye bubble always comes back (r1414 rule, unchanged).
        SharinganAccessibilityService.instance?.let {
            try { SharinganBubble.show(it) } catch (_: Throwable) {}
        }
    }

    fun toggle(service: android.content.Context) {
        if (rootView != null) hide() else show(service)
    }

    private fun resetAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
    }

    private fun toggleCompact(panel: LinearLayout) {
        for (i in 1 until panel.childCount) {
            val child = panel.getChildAt(i)
            child.visibility = if (child.visibility == View.GONE) View.VISIBLE else View.GONE
        }
    }
}

/** Phase constants bridge (avoids importing the engine object into the panel). */
object SharinganLiveEnginePhase {
    const val THINKING = "thinking"
    const val EXECUTING = "executing"
}
