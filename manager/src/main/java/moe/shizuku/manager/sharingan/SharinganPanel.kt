package moe.shizuku.manager.sharingan

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
import moe.shizuku.manager.ui.AgentHomeActivity

/**
 * The Sharingan Live floating panel (PLAN_aMiNo2.md §2.2) — created
 * programmatically inside the accessibility service window
 * (TYPE_ACCESSIBILITY_OVERLAY → no SYSTEM_ALERT_WINDOW permission needed).
 *
 * Dark aMiNo styling: bg #F20B0B0E, accent #E53935, LED circle, drag anywhere,
 * auto-hide after 10s idle.
 */
object SharinganPanel {

    // r1412: 60s (was 10s) — field report: the panel was never noticed before
    // auto-hiding; one full minute gives the user time to see and grab it.
    private const val AUTO_HIDE_MS = 60_000L

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var scope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI refs updated by state collection
    private var led: View? = null
    private var recordBtn: TextView? = null
    private var statusLine: TextView? = null

    private val autoHideRunnable = Runnable { hide() }

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

        // ── Row 2: Execute ⚡ + Record ⏺ ─────────────────────────────────────
        val row2 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val execBtn = TextView(service).apply {
            text = service.getString(R.string.sharingan_btn_execute)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#E53935"), 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44, panel), 1f).apply { marginEnd = dp(8, panel); topMargin = dp(10, panel) }
        }
        val recBtn = TextView(service).apply {
            text = service.getString(R.string.sharingan_btn_record)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#37474F"), 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44, panel), 1f).apply { topMargin = dp(10, panel) }
        }
        recordBtn = recBtn
        row2.addView(execBtn); row2.addView(recBtn)
        panel.addView(row2)

        // ── Row 3: status line ───────────────────────────────────────────────
        val status = TextView(service).apply {
            text = service.getString(R.string.sharingan_status_ready)
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8, panel) }
        }
        statusLine = status
        panel.addView(status)

        // ── window params (top-right start) ──────────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24, panel); y = dp(180, panel)
        }

        // ── drag + tap-to-collapse + auto-hide ───────────────────────────────
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
        panel.setOnTouchListener(touch)

        // ── buttons ──────────────────────────────────────────────────────────
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
                    statusLine?.text = when {
                        !s.serviceUp -> service.getString(R.string.sharingan_status_offline)
                        s.recording -> service.getString(
                            R.string.sharingan_status_recording,
                            s.framesCaptured, s.framesChanged
                        )
                        s.lastError != null -> s.lastError
                        else -> service.getString(R.string.sharingan_status_ready)
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
        rootView = null; led = null; recordBtn = null; statusLine = null
        SharinganState.update { it.copy(panelVisible = false) }
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
     * نفذ ⚡ — the alpha Execute semantics (PLAN §2.3):
     * stage the current/last real trace (or take a live snapshot when none),
     * open the agent chat, and let the NEXT message consume the context.
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
                val i = Intent(service, AgentHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(i)
            }
        }
    }
}
