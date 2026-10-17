package dev.layatest.agent

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Teach mode (D-033): the owner does a task once while Friday records, for each step, the settled screen
 * they were looking at (the raw forest, so the training format can change later) and what they did --
 * tap, long-press, scroll, typed text, opening an app -- plus Done, which marks where the task ends
 * (Laya's weak spot). Rows go to filesDir/teach/<time>.jsonl; scripts/pull_teach.py turns them into
 * laya-train rows. A red banner shows while recording; Cancel keeps nothing, and wipe() deletes it all.
 *
 * Taps come from click events, which Compose apps and self-hiding buttons never send (measured: Play Store
 * tabs, Clock's Reset). With [rawTouches] the owner's real touches come from the Mac instead
 * (scripts/teach_touches.py reads the touchscreen) and click events are ignored, so nothing counts twice.
 */
class Teacher(private val svc: AgentService, private val goal: String, private val rawTouches: Boolean = false) {
    private val file = File(dir(svc), SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl")
    private val io = Executors.newSingleThreadExecutor()  // screen reads and file writes, in order
    private val history = ArrayList<String>()             // touched only on [io]
    @Volatile private var settled: JSONObject? = null     // the screen the owner is looking at
    @Volatile var steps = 0
        private set
    private var changingSince = 0L                        // main thread: when the screen started changing; 0 = settled
    private val refresh = Runnable { changingSince = 0; io.execute { settled = runCatching { svc.readState() }.getOrNull() } }
    private val banner = Overlay(svc).teachBanner(goal, onDone = ::done, onCancel = ::cancel)
    private val appLabels by lazy { AppResolver(svc).installed.map { it.label }.toSet() }  // read on [io]

    // scrolls and typing arrive as bursts of events: one step each, once the burst ends (main thread only)
    private var scrollFrom: JSONObject? = null
    private var dx = 0
    private var dy = 0
    private val flushScroll = Runnable {
        val from = scrollFrom
        val (x, y) = dx to dy
        scrollFrom = null; dx = 0; dy = 0
        if (from == null || (x == 0 && y == 0)) return@Runnable
        // content moving up means the finger swiped up: SCROLL_DOWN reveals what's below (AndroidControl)
        val op = if (abs(y) >= abs(x)) (if (y > 0) "SCROLL_DOWN" else "SCROLL_UP") else (if (x > 0) "SCROLL_RIGHT" else "SCROLL_LEFT")
        io.execute { write(from, op); history += op }
    }
    private var typing: Pair<JSONObject?, String>? = null
    private val flushTyping = Runnable {
        val (from, text) = typing ?: return@Runnable
        typing = null
        if (from == null || text.isBlank()) return@Runnable
        io.execute { write(from, "INPUT_TEXT", payload = text); history += historyEntry("INPUT_TEXT", null, text) }
    }

    init {
        dir(svc).mkdirs()
        svc.main.post(refresh)
    }

    fun onEvent(e: AccessibilityEvent) {
        if (e.packageName == svc.packageName) return  // our own banner
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> if (!rawTouches) tapped(e)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (scrollFrom == null) scrollFrom = settled
                if (e.scrollDeltaX != -1) dx += e.scrollDeltaX  // -1: the view doesn't report a delta
                if (e.scrollDeltaY != -1) dy += e.scrollDeltaY
                svc.main.removeCallbacks(flushScroll); svc.main.postDelayed(flushScroll, BURST_MS)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (!e.isPassword) {
                typing = (typing?.first ?: settled) to e.text.joinToString("")
                svc.main.removeCallbacks(flushTyping); svc.main.postDelayed(flushTyping, TYPING_MS)
            }
        }
        // the screen is changing: read it again once it settles, ready for the owner's next step --
        // but no later than MAX_WAIT_MS, or a screen that never settles (a running stopwatch) is never read
        val now = SystemClock.uptimeMillis()
        if (changingSince == 0L) changingSince = now
        if (now - changingSince < MAX_WAIT_MS) { svc.main.removeCallbacks(refresh); svc.main.postDelayed(refresh, SETTLE_MS) }
    }

    private fun tapped(e: AccessibilityEvent) {
        val r = Rect().also { e.source?.getBoundsInScreen(it) ?: return }
        record(if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) "LONG_PRESS" else "CLICK",
            intArrayOf(r.left, r.top, r.right, r.bottom))
    }

    /** A real tap or long-press at (x, y), forwarded from the Mac; touches on our own banner aren't steps. */
    fun touched(x: Int, y: Int, long: Boolean) {
        if (!banner.contains(x, y)) record(if (long) "LONG_PRESS" else "CLICK", intArrayOf(x, y, x, y))
    }

    /** BACK or HOME, from a real edge swipe forwarded from the Mac. */
    fun pressed(op: String) {
        require(op == "BACK" || op == "HOME") { "teach_input op must be BACK or HOME" }
        val from = settled ?: return
        if (!io.isShutdown) io.execute { write(from, op); history += historyEntry(op) }
    }

    private fun record(op: String, box: IntArray) {
        val from = settled ?: return
        if (io.isShutdown) return  // a touch forwarded after Done
        io.execute {
            val size = from.getJSONArray("screen")
            val cands = buildCandidates(from, size.getInt(0), size.getInt(1))
            val i = match(cands, box) ?: return@execute  // not something Laya could have chosen on that screen
            val c = cands[i]
            if ("launcher" in from.optString("package") && c.label in appLabels) {
                // tapping an app icon is how AndroidControl's OPEN_APP looks from outside (not the search box)
                write(from, "OPEN_APP", payload = c.label); history += historyEntry("OPEN_APP", null, c.label)
            } else {
                write(from, op, i, c); history += historyEntry(op, c.toCandidate(), null)
            }
        }
    }

    /** The candidate touched: the same box, else the smallest candidate containing the touched box's centre. */
    private fun match(cands: List<ScreenCandidate>, b: IntArray): Int? {
        cands.indexOfFirst { it.bounds.contentEquals(b) }.takeIf { it >= 0 }?.let { return it }
        val cx = (b[0] + b[2]) / 2
        val cy = (b[1] + b[3]) / 2
        return cands.withIndex().filter { (_, c) -> cx in c.bounds[0]..c.bounds[2] && cy in c.bounds[1]..c.bounds[3] }
            .minByOrNull { (_, c) -> (c.bounds[2] - c.bounds[0]).toLong() * (c.bounds[3] - c.bounds[1]) }?.index
    }

    private fun write(screen: JSONObject, op: String, target: Int? = null, c: ScreenCandidate? = null, payload: String? = null) {
        val row = JSONObject().put("goal", goal).put("history", JSONArray(history)).put("op", op)
            .put("package", screen.optString("package")).put("time", System.currentTimeMillis()).put("state", screen)
        if (target != null && c != null) {
            row.put("target", target).put("target_label", c.label).put("target_bounds", JSONArray(c.bounds.toList()))
        }
        if (payload != null) row.put("payload", payload)
        file.appendText(row.toString() + "\n")
        banner.count(++steps)
    }

    /** The owner tapped Done: record the final screen as DONE -- where this task ends. */
    fun done() = svc.main.post {
        flushScroll.run(); flushTyping.run()
        io.execute {
            runCatching { svc.readState() }.getOrNull()?.let { write(it, "DONE") }
            finish("Saved $steps steps for training")
        }
    }

    fun cancel() = svc.main.post {
        svc.main.removeCallbacks(flushScroll); svc.main.removeCallbacks(flushTyping)
        io.execute { file.delete(); finish("Teaching cancelled — nothing kept") }
    }

    private fun finish(message: String) {
        svc.main.removeCallbacks(refresh)
        banner.remove()
        svc.teachingEnded(message)
        io.shutdown()
    }

    companion object {
        private const val SETTLE_MS = 500L
        private const val MAX_WAIT_MS = 1500L
        private const val BURST_MS = 600L
        private const val TYPING_MS = 1500L

        fun dir(c: Context) = File(c.filesDir, "teach")

        /** Recordings on the phone: file name -> steps. */
        fun list(c: Context): JSONObject = JSONObject().also { out ->
            dir(c).listFiles()?.sortedBy { it.name }?.forEach { f -> out.put(f.name, f.readLines().count { it.isNotBlank() }) }
        }

        /** "Delete all teaching data": every recording on the phone. Returns how many files were removed. */
        fun wipe(c: Context): Int = dir(c).listFiles()?.count { it.delete() } ?: 0
    }
}
