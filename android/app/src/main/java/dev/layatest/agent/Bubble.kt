package dev.layatest.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import kotlin.math.abs

/**
 * Quick access (owner, 2026-10-18): a small round "F" floating over every app. Tap it for Friday's request box,
 * keyboard up; drag it anywhere and it settles at the nearer side. An accessibility overlay, so it needs no extra
 * permission. It steps aside while Friday works or is taught, so Friday's own taps never land on it. On/off in
 * Friday's main screen.
 */
class Bubble(private val svc: AgentService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dm = svc.resources.displayMetrics
    private val size = (56 * dm.density).toInt()
    private val params = LayoutParams(size, size, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dm.widthPixels - size
        y = dm.heightPixels / 3
    }
    private var view: View? = null

    fun show() = svc.main.post {
        if (view == null && isOn(svc)) view = create().also { runCatching { wm.addView(it, params) } }
    }

    fun hide() = svc.main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")  // the tap is performClick(), so TalkBack can still use it
    private fun create(): View = TextView(svc).apply {
        text = "F"
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61A73E8.toInt()) }
        elevation = 8 * dm.density
        contentDescription = "Friday: tap to ask, drag to move"
        setOnClickListener {
            svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        params.x = startX + dx.toInt()
                        params.y = (startY + dy.toInt()).coerceIn(0, dm.heightPixels - size)
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) v.performClick()
                    else {
                        params.x = if (params.x + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
                        wm.updateViewLayout(v, params)
                    }
                }
            }
            true
        }
    }

    companion object {
        private const val KEY = "bubble"
        fun isOn(c: Context) = c.getSharedPreferences("friday", Context.MODE_PRIVATE).getBoolean(KEY, false)
        fun setOn(c: Context, on: Boolean) = c.getSharedPreferences("friday", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
