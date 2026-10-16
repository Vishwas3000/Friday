package dev.layatest.agent

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Shows the proposed step on the phone, outlines its target, and waits for the owner to tap Run, Skip or Stop. */
class Overlay(private val svc: AgentService) {
    private val wm = svc.getSystemService(WindowManager::class.java)

    fun ask(text: String, bounds: JSONArray?): String {
        val choice = ArrayBlockingQueue<String>(1)
        val views = mutableListOf<View>()
        svc.main.post { show(text, bounds, views) { choice.offer(it) } }
        val picked = choice.poll(10, TimeUnit.MINUTES) ?: "stop" // nobody answered: fail closed
        svc.main.post { views.forEach { runCatching { wm.removeView(it) } } }
        Thread.sleep(250) // let the overlay leave the screen before the action's gesture lands
        return picked
    }

    private fun show(text: String, bounds: JSONArray?, views: MutableList<View>, pick: (String) -> Unit) {
        val ctx = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val screenH = wm.maximumWindowMetrics.bounds.height()
        var targetBelowMiddle = false

        if (bounds != null) {
            val (l, t, r, b) = List(4) { bounds.getInt(it) }
            targetBelowMiddle = (t + b) / 2 > screenH / 2
            val box = View(ctx).apply {
                background = GradientDrawable().apply { setStroke(8, Color.MAGENTA); setColor(Color.TRANSPARENT) }
            }
            add(box, params(r - l, b - t, Gravity.TOP or Gravity.START, l, t, touchable = false), views)
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 24)
            background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 36f }
            addView(TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 16f })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.END
                for ((label, value) in listOf("Stop" to "stop", "Skip" to "skip", "Run" to "run")) {
                    addView(Button(ctx).apply { this.text = label; setOnClickListener { pick(value) } })
                }
            })
        }
        // keep the panel away from the element it is asking about
        add(panel, params(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            if (targetBelowMiddle) Gravity.TOP else Gravity.BOTTOM, 0, 160, touchable = true), views)
    }

    private fun add(v: View, p: LayoutParams, views: MutableList<View>) {
        wm.addView(v, p)
        views += v
    }

    private fun params(w: Int, h: Int, gravity: Int, x: Int, y: Int, touchable: Boolean) = LayoutParams(
        w, h, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
        fitInsetsTypes = 0 // place the outline in raw screen coordinates, like the node bounds
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
}
