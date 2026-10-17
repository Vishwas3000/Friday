package dev.layatest.agent

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Looper
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
import java.util.concurrent.CountDownLatch
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

    /** The owner picks one option (or Cancel). Blocks the calling thread; null if cancelled or ignored for 5 min. */
    fun choose(title: String, options: List<String>): Int? {
        val picked = ArrayBlockingQueue<Int>(1)
        val views = mutableListOf<View>()
        svc.main.post {
            val ctx = theme()
            val panel = card(ctx).apply {
                addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
                options.forEachIndexed { i, o ->
                    addView(Button(ctx).apply { text = o; isAllCaps = false; setOnClickListener { picked.offer(i) } })
                }
                addView(Button(ctx).apply { text = "Cancel"; setOnClickListener { picked.offer(-1) } })
            }
            add(panel, params(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER, 0, 0, touchable = true), views)
        }
        val i = picked.poll(5, TimeUnit.MINUTES) ?: -1
        svc.main.post { views.forEach { runCatching { wm.removeView(it) } } }
        Thread.sleep(250)
        return i.takeIf { it >= 0 }
    }

    /**
     * A result card until the owner taps a button (or a minute passes). The feedback buttons teach Friday (D-048):
     * [right] keeps what it did, [wrong] marks it wrong, [showMe] starts Teach mode on the same goal. Doesn't block.
     */
    fun answer(text: String, right: (() -> Unit)? = null, wrong: (() -> Unit)? = null, showMe: (() -> Unit)? = null) = svc.main.post {
        val ctx = theme()
        lateinit var panel: View
        fun button(label: String, then: (() -> Unit)?) = Button(ctx).apply {
            this.text = label
            setOnClickListener { runCatching { wm.removeView(panel) }; then?.let { Thread(it, "friday-feedback").start() } }
        }
        panel = card(ctx).apply {
            addView(TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 18f })
            addView(LinearLayout(ctx).apply {
                addView(button("OK", null))
                right?.let { addView(button("✓ Right", it)) }
                wrong?.let { addView(button("✗ Wrong", it)) }
                showMe?.let { addView(button("Show me", it)) }
            })
        }
        wm.addView(panel, params(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER, 0, 0, touchable = true))
        svc.main.postDelayed({ runCatching { wm.removeView(panel) } }, 60_000)
    }

    fun banner() = Banner()

    fun teachBanner(goal: String, onDone: () -> Unit, onCancel: () -> Unit) = TeachBanner(goal, onDone, onCancel)

    /** Teach mode's red "recording" pill (D-033): what's being taught, steps so far, Done and Cancel. */
    inner class TeachBanner(goal: String, onDone: () -> Unit, onCancel: () -> Unit) {
        private var view: View? = null
        private var label: TextView? = null

        init {
            svc.main.post {
                val ctx = theme()
                val l = TextView(ctx).apply { text = "● Teaching: $goal"; setTextColor(Color.WHITE); textSize = 13f; maxLines = 1 }
                label = l
                val pill = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 4, 4, 4)
                    background = GradientDrawable().apply { setColor(0xEEB3261E.toInt()); cornerRadius = 60f }
                    addView(l)
                    addView(Button(ctx).apply { text = "Done"; setOnClickListener { onDone() } })
                    addView(Button(ctx).apply { text = "Cancel"; setOnClickListener { onCancel() } })
                }
                view = pill
                wm.addView(pill, params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 110, touchable = true))
            }
        }

        fun count(steps: Int) = svc.main.post { label?.text = "● Teaching · $steps steps" }

        fun remove() = svc.main.post { view?.let { runCatching { wm.removeView(it) } }; view = null }

        /** Is the screen point (x, y) on this banner? Read from the bridge thread: a benign race with layout. */
        fun contains(x: Int, y: Int): Boolean {
            val v = view ?: return false
            val at = IntArray(2).also { v.getLocationOnScreen(it) }
            return x in at[0]..at[0] + v.width && y in at[1]..at[1] + v.height
        }
    }

    /**
     * A small status pill with Stop while Friday works. [hide] removes it and waits until it's gone,
     * before every screen read and tap: some apps ignore touches while another window covers them.
     */
    inner class Banner {
        @Volatile var stopped = false
            private set
        @Volatile private var msg = ""
        private var view: View? = null
        private var label: TextView? = null

        fun say(text: String) {
            msg = text
            svc.main.post { ensure(); label?.text = text }
        }

        fun stop() { stopped = true }

        fun hide() {
            onMain { view?.let { runCatching { wm.removeView(it) } }; view = null }
            Thread.sleep(120)
        }

        fun remove() = onMain { view?.let { runCatching { wm.removeView(it) } }; view = null }

        private fun ensure() {
            if (view != null) return
            val ctx = theme()
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(36, 6, 6, 6)
                background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 60f }
                val l = TextView(ctx).apply { text = msg; setTextColor(Color.WHITE); textSize = 14f }
                label = l
                addView(l)
                addView(Button(ctx).apply { text = "Stop"; setOnClickListener { stopped = true; label?.text = "Stopping…" } })
            }
            view = pill
            wm.addView(pill, params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 110, touchable = true))
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        svc.main.post { try { block() } finally { done.countDown() } }
        done.await(2, TimeUnit.SECONDS)
    }

    private fun theme() = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)

    private fun card(ctx: android.content.Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 32, 40, 24)
        background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 36f }
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
