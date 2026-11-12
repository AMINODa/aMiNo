package moe.shizuku.manager.sharingan

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.R

/**
 * r1414 — THE ONE BUTTON (the user's ask, verbatim:
 * «أريد زر واحد عند الضغط تظهر اللوحة العائمة»).
 *
 * A small floating eye bubble that SharinganAccessibilityService puts on
 * screen the moment it connects. It does NOT depend on the system nav-bar
 * accessibility button, OEM shortcut settings, or the system chooser dialog —
 * the app owns the whole flow: enable the toggle → the eye appears (over any
 * app) → tap the eye → the panel with the command input opens.
 *
 * aMiNo 2.0 (r1435) — the eye is ALIVE now: [AmaterasuEyeView] replaces the
 * static PNG. It follows SharinganState: MODE_IDLE normally, MODE_AMATERASU
 * (black tears, the user's exact ask) while the live screen-sharing session
 * runs, MODE_VOICE (mic pulse) while the voice loop listens/speaks. Drag
 * anywhere; long-press explains how to remove it. Built as
 * TYPE_ACCESSIBILITY_OVERLAY from the service context → no
 * SYSTEM_ALERT_WINDOW permission needed. NOT_FOCUSABLE so it never steals
 * the keyboard; the PANEL (input field) opens focusable separately.
 */
object SharinganBubble {

    private var windowManager: WindowManager? = null
    private var rootView: AmaterasuEyeView? = null
    private var stateScope: CoroutineScope? = null

    val visible: Boolean get() = rootView != null

    fun show(service: android.content.Context) {
        if (rootView != null) return
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val dm = service.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val eye = AmaterasuEyeView(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
                setStroke(dp(2), Color.parseColor("#FF8A80"))
            }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(4).toFloat()
            contentDescription = service.getString(R.string.sharingan_bubble_cd)
        }
        rootView = eye

        // r1435 — the eye reacts to the live/voice state in real time.
        stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { cs ->
            cs.launch {
                SharinganState.state.collect { s ->
                    eye.setMode(when {
                        s.liveActive -> AmaterasuEyeView.MODE_AMATERASU
                        s.voiceActive -> AmaterasuEyeView.MODE_VOICE
                        else -> AmaterasuEyeView.MODE_IDLE
                    })
                }
            }
        }

        val params = WindowManager.LayoutParams(
            dp(48), dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - dp(72)
            y = (dm.heightPixels * 0.35f).toInt()
        }

        // Drag with 12px slop (same feel as the panel); tap opens the panel;
        // long-press tells the user what this eye is and how to remove it.
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var longPress: Runnable? = null

        eye.setOnTouchListener(object : View.OnTouchListener {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        dragging = false
                        longPress = Runnable {
                            if (!dragging && rootView != null) {
                                Toast.makeText(
                                    service,
                                    service.getString(R.string.sharingan_bubble_hint),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.also { v.postDelayed(it, 600) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                            dragging = true
                            longPress?.let { v.removeCallbacks(it) }; longPress = null
                        }
                        if (dragging) {
                            params.x = Math.max(0, startX + dx)
                            params.y = Math.max(0, startY + dy)
                            wm.updateViewLayout(eye, params)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPress?.let { v.removeCallbacks(it) }; longPress = null
                        if (e.action == MotionEvent.ACTION_UP && !dragging) {
                            // THE ONE TAP: eye → panel with the command input.
                            hide()
                            SharinganPanel.show(service)
                        }
                    }
                }
                return true
            }
        })

        wm.addView(eye, params)
    }

    fun hide() {
        val v = rootView ?: return
        stateScope?.cancel(); stateScope = null
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        rootView = null
        windowManager = null
    }
}
