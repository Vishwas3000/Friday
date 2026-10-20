package dev.layatest.agent

import android.graphics.Color
import android.graphics.PixelFormat
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
     * [right] keeps what it did, [wrong] marks it wrong, [showMe] starts Teach mode on the same goal, [reply] carries the
 * task on from here. Doesn't block.
     */
    fun answer(text: String, right: (() -> Unit)? = null, wrong: (() -> Unit)? = null, showMe: (() -> Unit)? = null,
               reply: (() -> Unit)? = null) = svc.main.post {
        val ctx = theme()
        lateinit var panel: View
        fun button(label: String, then: (() -> Unit)?) = Button(ctx).apply {
            this.text = label
            setOnClickListener { runCatching { wm.removeView(panel) }; then?.let { Thread(it, "friday-feedback").start() } }
        }
        panel = card(ctx).apply {
            // Full research responses must remain readable without pushing the dismissal buttons offscreen.
            addView(object : ScrollView(ctx) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = (wm.maximumWindowMetrics.bounds.height() * 0.55).toInt()
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                addView(TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 18f })
            })
            // carry on from here by voice or text: "it's under the You tab", "now open the second one" (D-061)
            reply?.let { addView(button("Reply: tell me what next", it)) }
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

    fun banner(onStop: (() -> Unit)? = null) = Banner(onStop)

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
     * Active work has a spinner, current status, elapsed time and Stop. [hide] removes the pill before
     * actions because some apps ignore covered touches; [resume] restores it during subsequent waiting.
     */
    inner class Banner(private val onStop: (() -> Unit)? = null) {
        @Volatile var stopped = false
            private set
        @Volatile private var msg = ""
        @Volatile private var removed = false
        private val startedAt = SystemClock.elapsedRealtime()
        private var view: View? = null
        private var label: TextView? = null
        private var elapsed: TextView? = null
        private var spinner: ProgressBar? = null
        private val tick = object : Runnable {
            override fun run() {
                if (removed || view == null) return
                updateElapsed()
                svc.main.postDelayed(this, 1000)
            }
        }

        fun say(text: String) {
            if (removed) return
            msg = text
            svc.main.post { if (!removed) { ensure(); updateStatus() } }
        }

        fun stop() {
            stopped = true
            onStop?.invoke()
            svc.main.post { updateStatus() }
        }

        /** Restore the last status after an action; read-only polling should leave this visible. */
        fun resume() = svc.main.post { if (!removed && msg.isNotEmpty()) { ensure(); updateStatus() } }

        fun hide() {
            onMain { detach() }
            Thread.sleep(120)
        }

        fun remove() {
            removed = true
            onMain { detach() }
        }

        private fun detach() {
            svc.main.removeCallbacks(tick)
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            label = null
            elapsed = null
            spinner = null
        }

        private fun updateStatus() {
            label?.text = if (stopped) "Stopping…" else msg
            spinner?.visibility = if (stopped) View.GONE else View.VISIBLE
            updateElapsed()
        }

        private fun updateElapsed() {
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
            elapsed?.text = "${if (stopped) "Stopping" else "Working"} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }

        private fun ensure() {
            if (removed || view != null) return
            val ctx = theme()
            val density = svc.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 60f }
                addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Friday is working"
                    spinner = this
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply {
                        setTextColor(Color.WHITE); textSize = 14f; maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                        label = this
                    })
                    addView(TextView(ctx).apply {
                        setTextColor(0xFFBDC1C6.toInt()); textSize = 12f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        elapsed = this
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(ctx).apply { text = "Stop"; setOnClickListener { stop() } })
            }
            wm.addView(pill, params((wm.maximumWindowMetrics.bounds.width() - dp(24)).coerceAtLeast(dp(200)), LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 110, touchable = true))
            view = pill
            updateStatus()
            updateElapsed()
            svc.main.postDelayed(tick, 1000)
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
