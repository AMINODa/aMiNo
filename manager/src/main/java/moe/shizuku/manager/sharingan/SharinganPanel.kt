package moe.shizuku.manager.sharingan

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
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
import moe.shizuku.manager.shell.ShellSession

/**
 * The Sharingan Live floating panel v2 (PLAN_aMiNo2.md §2.2, r1413 redesign
 * from the user's field report: "لا يظهر حقل إدخال كما طلبت" — the panel is
 * now a DIRECT COMMAND BOX: type + send, with the real screen staged as
 * context automatically).
 *
 * Dark aMiNo styling: bg #F20B0B0E, accent #E53935, LED circle, drag anywhere
 * (title/status rows), auto-hide after 60s idle — never while typing.
 * Created inside the accessibility service window
 * (TYPE_ACCESSIBILITY_OVERLAY → no SYSTEM_ALERT_WINDOW permission needed).
 */
object SharinganPanel {

    // r1412: 60s (was 10s) — the panel was auto-hiding before the user ever
    // noticed it; r1413: while the input is focused the hide is POSTPONED, so
    // it can never vanish under a typing user.
    private const val AUTO_HIDE_MS = 60_000L

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var scope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI refs updated by state collection
    private var led: View? = null
    private var recordBtn: TextView? = null
    private var statusLine: TextView? = null
    private var input: EditText? = null
    // r1416: the panel SHOWS the result itself — the expert audit found the
    // outcome channel was notifications-only; with notifications off the
    // command succeeded and the user saw nothing anywhere (F2).
    private var resultView: TextView? = null
    private var hintView: TextView? = null

    private val autoHideRunnable: Runnable = object : Runnable {
        override fun run() {
            // r1416: a running background task ALSO postpones the hide — the
            // user must be able to come back and read the result HERE.
            if (input?.hasFocus() == true || AgentOrchestrator.state.value.busy) {
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
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp
        }

    val visible: Boolean get() = rootView != null

    fun show(service: android.content.Context) {
        if (rootView != null) return
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F20B0B0E"), 16f)
            setPadding(dp(14, this), dp(10, this), dp(14, this), dp(12, this))
            elevation = dp(6, this).toFloat()
        }
        rootView = panel

        // ── Row 1: eye + title + LED ─────────────────────────────────────────
        val row1 = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val eye = ImageView(service).apply {
            setImageResource(R.drawable.ic_sharingan)
            layoutParams = LinearLayout.LayoutParams(dp(26, panel), dp(26, panel)).apply { marginEnd = dp(8, panel) }
        }
        row1.addView(eye)
        val title = TextView(service).apply {
            text = "SHARINGAN"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
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

        // ── Row 2: THE COMMAND INPUT + send (r1413 — the user's ask) ─────────
        val row2 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cmdInput = EditText(service).apply {
            hint = service.getString(R.string.sharingan_hint_command)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#78909C"))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 1
            isSingleLine = true
            background = rounded(Color.parseColor("#263238"), 12f)
            setPadding(dp(10, panel), dp(8, panel), dp(10, panel), dp(8, panel))
            layoutParams = LinearLayout.LayoutParams(0, dp(44, panel), 1f).apply {
                marginEnd = dp(8, panel); topMargin = dp(10, panel)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { resetAutoHide() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { resetAutoHide(); sendCommand(service); true } else false
            }
        }
        input = cmdInput
        row2.addView(cmdInput)
        val sendBtn = TextView(service).apply {
            text = "⚡"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 18f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#E53935"), 12f)
            layoutParams = LinearLayout.LayoutParams(dp(48, panel), dp(44, panel)).apply { topMargin = dp(10, panel) }
        }
        row2.addView(sendBtn)
        panel.addView(row2)

        // -- r1416: HOW IT WORKS hint (user: "ولا أفهم كيف يعمل") ----------------
        val hint = TextView(service).apply {
            text = service.getString(R.string.sharingan_panel_hint)
            setTextColor(Color.parseColor("#78909C"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6, panel) }
        }
        hintView = hint
        panel.addView(hint)

        // ── Row 3: Record ⏺ / Stop + context-only Execute (kept compact) ─────
        val row3 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val recBtn = TextView(service).apply {
            text = service.getString(R.string.sharingan_btn_record)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#37474F"), 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(34, panel), 1f).apply { marginEnd = dp(8, panel); topMargin = dp(8, panel) }
        }
        recordBtn = recBtn
        val execBtn = TextView(service).apply {
            text = service.getString(R.string.sharingan_btn_execute)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#37474F"), 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(34, panel), 1f).apply { topMargin = dp(8, panel) }
        }
        row3.addView(recBtn); row3.addView(execBtn)
        panel.addView(row3)

        // ── Row 4: status line ───────────────────────────────────────────────
        val status = TextView(service).apply {
            text = service.getString(R.string.sharingan_status_ready)
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6, panel) }
        }
        statusLine = status
        panel.addView(status)

        // -- r1416: RESULT AREA — the agent's real final answer, in the panel --
        val result = TextView(service).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 12f
            maxLines = 8
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6, panel) }
        }
        resultView = result
        panel.addView(result)

        // ── window params (top-right start) ──────────────────────────────────
        // r1413: NOT_FOCUSABLE is GONE — the input must be typeable. The panel
        // still lets touches pass to apps behind it (NOT_TOUCH_MODAL).
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

        // ── drag (title/status rows) + tap-to-collapse + auto-hide ───────────
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
        // r1413: drag lives on the title + status rows only — the EditText and
        // the buttons must own their own touches (typing/tap), the body stays
        // tap-to-collapse. Row1/Row4 are pure display views, safe to drag.
        row1.setOnTouchListener(touch)
        status.setOnTouchListener(touch)

        // ── buttons ──────────────────────────────────────────────────────────
        sendBtn.setOnClickListener { resetAutoHide(); sendCommand(service) }
        execBtn.setOnClickListener {
            resetAutoHide()
            executeStaged(service)
        }
        recBtn.setOnClickListener {
            resetAutoHide()
            if (SharinganRecorder.recording) {
                SharinganRecorder.stop(service)
                Toast.makeText(service, service.getString(R.string.sharingan_toast_record_stopped), Toast.LENGTH_SHORT).show()
            } else {
                val ok = SharinganRecorder.start(service)
                Toast.makeText(
                    service,
                    service.getString(if (ok) R.string.sharingan_toast_record_started else R.string.sharingan_toast_offline),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ── state → UI ───────────────────────────────────────────────────────
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { cs ->
            cs.launch {
                SharinganState.state.collect { s ->
                    ledView.background = circle(
                        when {
                            !s.serviceUp -> Color.parseColor("#9E9E9E")   // offline
                            s.recording -> Color.parseColor("#E53935")    // recording
                            else -> Color.parseColor("#4CAF50")           // ready
                        }
                    )
                    recBtn.text = service.getString(
                        if (s.recording) R.string.sharingan_btn_stop else R.string.sharingan_btn_record
                    )
                    // r1416: recording/offline/error keep this line; the IDLE
                    // line is owned by the agent collector below (shell note).
                    if (!(s.serviceUp && !s.recording && s.lastError == null &&
                        !AgentOrchestrator.state.value.busy)) {
                        statusLine?.text = when {
                            !s.serviceUp -> service.getString(R.string.sharingan_status_offline)
                            s.recording -> service.getString(
                                R.string.sharingan_status_recording,
                                s.framesCaptured, s.framesChanged
                            )
                            else -> s.lastError ?: service.getString(R.string.sharingan_status_ready)
                        }
                    }
                }
            }
            // r1416: agent state → live progress + the REAL result, in-panel
            // (audit F2: notifications-only outcomes were invisible when
            // notifications were disabled — now the panel shows everything).
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
                    } else if (s.status is AgentStatus.NotConfigured) {
                        statusLine?.text = service.getString(R.string.sharingan_no_apikey)
                    } else {
                        val shellOk = ShellSession.state.value is ShellSession.ConnectionState.Connected
                        statusLine?.text = service.getString(R.string.sharingan_status_ready) +
                            if (!shellOk) " • " + service.getString(R.string.sharingan_shell_down) else ""
                    }
                    val last = s.items.lastOrNull {
                        it.role == "assistant" && it.toolName == null && it.text.isNotBlank()
                    }
                    if (last != null) {
                        hintView?.visibility = View.GONE
                        resultView?.visibility = View.VISIBLE
                        resultView?.text = last.text.take(600) +
                            if (last.text.length > 600) "…" else ""
                    }
                }
            }
        }

        wm.addView(panel, params)
        SharinganState.update { it.copy(panelVisible = true) }
        resetAutoHide()
    }

    fun hide() {
        val v = rootView ?: return
        scope?.cancel(); scope = null
        mainHandler.removeCallbacks(autoHideRunnable)
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        rootView = null; led = null; recordBtn = null; statusLine = null; input = null
        resultView = null; hintView = null
        SharinganState.update { it.copy(panelVisible = false) }
        // r1414 — the ONE button always comes back: the moment the panel goes
        // away (auto-hide, toggle), the eye bubble returns so the user is
        // never left without an on-screen entry point. While the service is
        // shutting down, instance is already null → no re-show there.
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
        // tap on the body collapses/expands rows 2+3 (title row always visible)
        val target = panel.childCount
        for (i in 1 until target) {
            val child = panel.getChildAt(i)
            child.visibility = if (child.visibility == View.GONE) View.VISIBLE else View.GONE
        }
    }

    /**
     * ⚡ SEND (r1416 — honest pre-flight, nothing silent):
     * expert audit found two silent deaths: no-API-key and busy dropped the
     * command AFTER the optimistic toast; staging ran on the panel's scope so
     * auto-hide could cancel a send mid-capture. Now: pre-flight REJECTS
     * visibly, and [AgentOrchestrator.sendSharingan] owns staging + task
     * notifications + a guaranteed terminal result. The outcome is visible
     * THREE ways: live status + result area HERE, notification, and the chat.
     */
    private fun sendCommand(service: android.content.Context) {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!ApiKeysStore.hasKey(service)) {
            statusLine?.text = service.getString(R.string.sharingan_no_apikey)
            Toast.makeText(service, service.getString(R.string.sharingan_no_apikey), Toast.LENGTH_LONG).show()
            return
        }
        if (AgentOrchestrator.state.value.busy) {
            Toast.makeText(service, service.getString(R.string.sharingan_task_busy), Toast.LENGTH_LONG).show()
            return
        }
        input?.setText("")
        resetAutoHide()
        val accepted = AgentOrchestrator.sendSharingan(service, text)
        if (!accepted) {
            // busy/not-configured race between the check and the call — still visible
            Toast.makeText(service, service.getString(R.string.sharingan_task_busy), Toast.LENGTH_LONG).show()
            return
        }
        // Honest channel messaging — notifications state FIRST (audit F2: the
        // old toast promised a notification even when notifications were off).
        val notifOk = androidx.core.app.NotificationManagerCompat
            .from(service).areNotificationsEnabled()
        Toast.makeText(
            service,
            service.getString(
                if (notifOk) R.string.sharingan_toast_bg_sent
                else R.string.sharingan_task_no_notif
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * نفذ ⚡ — context-only path (r1415: stays ON SCREEN too):
     * stage the current/last real trace (or take a live snapshot when none)
     * and tell the user to type the command — no activity launch, the next
     * panel send consumes the context in the background.
     */
    private fun executeStaged(service: android.content.Context) {
        scope?.launch(Dispatchers.IO) {
            val id = SharinganState.state.value.lastTraceId
                ?.takeIf { TraceStore.exists(service, it) }
                ?: run {
                    val f = ScreenCapture.capture() ?: return@run null
                    runCatching { TraceStore.snapshot(service, f) }.getOrNull()
                }
            if (id == null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(service, service.getString(R.string.sharingan_toast_offline), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            SharinganContextHub.stage(id)
            launch(Dispatchers.Main) {
                Toast.makeText(service, service.getString(R.string.sharingan_context_ready), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
