package dev.layatest.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The agent's eyes and hands: exports the screen's window forest and performs actions, driven through [Bridge]. */
class AgentService : AccessibilityService() {
    val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = SystemClock.uptimeMillis()
    private var bridge: Bridge? = null

    override fun onServiceConnected() {
        instance = this
        bridge = Bridge(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != packageName) lastEventAt = SystemClock.uptimeMillis()
        teacher?.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        bridge?.stop()
        instance = null
        super.onDestroy()
    }

    fun handle(c: JSONObject): JSONObject = when (val cmd = c.getString("cmd")) {
        "state" -> {
            settle(c.optLong("quiet_ms", 300), c.optLong("timeout_ms", 3000))
            state()
        }
        "foreground" -> JSONObject().put("package", rootInActiveWindow?.packageName?.toString())
        "apps" -> JSONObject().put("apps", apps())
        "confirm" -> JSONObject().put("choice", Overlay(this).ask(c.getString("text"), c.optJSONArray("bounds")))
        "tap" -> acted(stroke(c.getDouble("x"), c.getDouble("y"), c.getDouble("x"), c.getDouble("y"), c.optLong("ms", 60)))
        "swipe" -> acted(stroke(c.getDouble("x"), c.getDouble("y"), c.getDouble("x2"), c.getDouble("y2"), c.optLong("ms", 400)))
        "text" -> acted(setText(c.getString("text")))
        "open_app" -> acted(openApp(c.getString("package")))
        "global" -> acted(performGlobalAction(GLOBALS[c.getString("action")] ?: error("unknown global action")))
        "bench" -> Bench.run(this, c) // Phase-2 gate: on-device ONNX latency/RAM
        "decide_test" -> Decide.test(this, c) // Phase-2c: full on-device tokenize+build+ORT+decode on the fixture
        "selfcheck" -> JSONObject().put("candidates", candidatesSelfCheck()).put("slots", slotsSelfCheck())
            .put("media", mediaSelfCheck()).put("people", peopleSelfCheck()).put("profiler", appCatalogSelfCheck())
            .put("routes", routesSelfCheck())
        "profiles" -> JSONObject(AppProfiler.profiles(packageManager) as Map<*, *>)  // D-031: auto app profiles, to evaluate
        "route" -> routeTest(c.getString("text"))
        "run" -> { runGoal(c.getString("goal")); JSONObject().put("started", true) }
        "teach" -> JSONObject().put("started", startTeaching(c.getString("goal"), c.optBoolean("raw")))
        // a real touch from scripts/teach_touches.py: {x, y, long} or {op: BACK|HOME}; says whether still teaching
        "teach_input" -> JSONObject().put("teaching", teacher?.let { t ->
            if (c.has("op")) t.pressed(c.getString("op")) else t.touched(c.getInt("x"), c.getInt("y"), c.optBoolean("long"))
            true
        } ?: false)
        "teach_done" -> { finishTeaching(c.optBoolean("keep", true)); JSONObject().put("ok", true) }
        "teach_list" -> Teacher.list(this)
        "teach_wipe" -> JSONObject().put("deleted", Teacher.wipe(this))
        // debugging: run a request on the bridge thread and return its answer + step trace
        "ask" -> Assistant(this, c.getString("goal")).also { lastAsk = it }.let { a ->
            JSONObject().put("answer", a.run()).put("trace", JSONArray(a.trace))
                .put("learned", a.learned?.steps?.joinToString(" → ") { it.describe() } ?: JSONObject.NULL)
        }
        // what the answer card's "Remember" does, for the last "ask" -- after checking its steps
        "remember" -> JSONObject().put("remembered", lastAsk?.learned != null).also { lastAsk?.remember() }
        "routes" -> Routes.all(this)  // UI memory (D-040), for debugging
        else -> error("unknown cmd: $cmd")
    }

    /** An action restarts the quiet window, so the next `state` can't return the screen from before it. */
    private fun acted(done: Boolean): JSONObject {
        lastEventAt = SystemClock.uptimeMillis()
        return JSONObject().put("ok", done)
    }

    /** Blocks until no UI events have arrived for [quietMs], or [timeoutMs] has passed. */
    private fun settle(quietMs: Long, timeoutMs: Long) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && SystemClock.uptimeMillis() - lastEventAt < quietMs) Thread.sleep(20)
    }

    /** Every window's tree, in the shape Laya-Android's candidate builder expects (android_env-style fields). */
    private fun state(): JSONObject {
        val wins = JSONArray()
        for (w in windows) {
            val root = w.root ?: continue
            if (root.packageName == packageName) continue // our own confirmation overlay
            wins.put(JSONObject().put("type", w.type).put("root", node(root)))
        }
        val size = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return JSONObject()
            .put("screen", JSONArray().put(size.width()).put(size.height()))
            .put("package", rootInActiveWindow?.packageName?.toString())
            .put("windows", wins)
    }

    private fun node(n: AccessibilityNodeInfo): JSONObject {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val kids = JSONArray()
        for (i in 0 until n.childCount) n.getChild(i)?.let { kids.put(node(it)) }
        @Suppress("DEPRECATION") // isChecked: still correct for two-state widgets, which is all Laya models
        return JSONObject()
            .put("text", n.text?.toString() ?: "")
            .put("content_desc", n.contentDescription?.toString() ?: "")
            .put("hint", n.hintText?.toString() ?: "")
            .put("class", n.className?.toString() ?: "")
            .put("package", n.packageName?.toString() ?: "")
            .put("resource_id", n.viewIdResourceName ?: "")
            .put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
            .put("visible", n.isVisibleToUser)
            .put("clickable", n.isClickable)
            .put("long_clickable", n.isLongClickable)
            .put("editable", n.isEditable)
            .put("scrollable", n.isScrollable)
            .put("checkable", n.isCheckable)
            .put("checked", n.isChecked)
            .put("selected", n.isSelected)
            .put("enabled", n.isEnabled)
            .put("children", kids)
    }

    private fun apps(): JSONArray {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        for (ri in packageManager.queryIntentActivities(launcher, 0)) {
            out.put(JSONObject().put("label", ri.loadLabel(packageManager).toString()).put("package", ri.activityInfo.packageName))
        }
        return out
    }

    /** One-finger stroke: a tap or long-press when start == end, otherwise a swipe. Waits for it to finish. */
    private fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val done = CountDownLatch(1)
        var completed = false
        main.post {
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { completed = true; done.countDown() }
                override fun onCancelled(g: GestureDescription) = done.countDown()
            }, null)
            if (!accepted) done.countDown()
        }
        done.await(ms + 2000, TimeUnit.MILLISECONDS)
        return completed
    }

    /**
     * Types into the focused field; unicode-safe, unlike `adb shell input text`. A search box is then
     * submitted (Enter), or the search never runs; a chat box never is -- there Enter can send a message
     * the owner only approved typing.
     */
    private fun setText(text: String): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val search = listOfNotNull(field.hintText, field.contentDescription, field.viewIdResourceName).any { SEARCH_BOX.containsMatchIn(it) }
        if (ok && search) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return ok
    }

    private fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // ---- Friday the assistant: a typed request runs through Assistant (route -> skill -> answer) ----
    @Volatile private var running = false

    /** Handle one typed request on a background thread (one at a time) and show the answer as a card. */
    fun runGoal(goal: String) {
        if (goal.isBlank() || running || teacher != null) return  // while teaching, Friday's own taps would be recorded as the owner's
        running = true
        Thread({
            try {
                val a = Assistant(this, goal.trim())
                val said = a.run()
                // the owner sees the steps before keeping them: Laya sometimes overshoots (an extra tap after the goal)
                val steps = a.learned?.steps?.joinToString(" → ") { it.describe() }
                Overlay(this).answer(if (steps == null) said else "$said\n\nI did: $steps",
                    a.learned?.let { { a.remember(); toast("Remembered — next time it skips the thinking") } })
            } catch (e: Throwable) {
                toast("error: ${e.message}")
            } finally {
                running = false
            }
        }, "friday-request").start()
    }

    // ---- Laya stays warm between requests and is freed after IDLE_MS unused (D-016: load on demand, free when idle) ----
    private var warm: LayaEngine? = null
    // off the main thread: releasing waits on the lock that laya() holds while loading (~10 s)
    private val idleRelease = Runnable { Thread(::releaseLaya, "laya-release").start() }

    internal val layaWarm get() = warm != null

    /** The shared engine, loading it if needed (~10 s). Callers must not close it; call [layaIdle] when done. */
    @Synchronized internal fun laya(): LayaEngine {
        main.removeCallbacks(idleRelease)
        return warm ?: LayaEngine(this).also { warm = it }
    }

    internal fun layaIdle() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_MS)
    }

    @Synchronized private fun releaseLaya() {
        if (running) return  // never under a request; it re-arms the timer when it finishes
        warm?.close()
        warm = null
    }

    /** Load Laya in the background when the owner opens Friday, so it's warm by the time they tap Run. */
    fun prewarm() {
        if (warm == null) Thread({ runCatching { laya(); layaIdle() } }, "laya-prewarm").start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) idleRelease.run()
    }

    // ---- Teach mode (D-033): taps and typing are subscribed to only while the owner is teaching ----
    @Volatile private var teacher: Teacher? = null
    @Volatile private var lastAsk: Assistant? = null  // the bridge's last "ask", for "remember"

    fun startTeaching(goal: String, rawTouches: Boolean = false): Boolean {
        if (goal.isBlank() || teacher != null || running) return false
        listenForTeaching(true)
        teacher = Teacher(this, goal.trim(), rawTouches)
        return true
    }

    internal fun finishTeaching(keep: Boolean) {
        teacher?.let { if (keep) it.done() else it.cancel() }
    }

    internal fun teachingEnded(message: String) {
        teacher = null
        listenForTeaching(false)
        toast(message)
    }

    private fun listenForTeaching(on: Boolean) {
        serviceInfo = serviceInfo.apply { eventTypes = if (on) eventTypes or TEACH_EVENTS else eventTypes and TEACH_EVENTS.inv() }
    }

    /** The screen once it settles: the forest that Laya and the extractors read. */
    internal fun readState(): JSONObject {
        val t = SystemClock.elapsedRealtime()
        settle(300, 3000)
        lastSettleMs = SystemClock.elapsedRealtime() - t
        return state()
    }

    /** How long the last [readState] waited for the screen to go quiet (step timings in the trace). */
    @Volatile var lastSettleMs = 0L
        private set

    internal fun foregroundPkg(): String? = rootInActiveWindow?.packageName?.toString()

    /** Bridge test: how a request routes on the phone (rule hit, or Laya's pick + confidence); compare research/09. */
    private fun routeTest(text: String): JSONObject {
        val out = JSONObject().put("rule", ruleSkill(text) ?: JSONObject.NULL).put("was_warm", layaWarm)
        val t0 = SystemClock.elapsedRealtime()
        val laya = laya()
        val t1 = SystemClock.elapsedRealtime()
        val p = laya.choose(text, "What does the user want Friday to do?", SKILLS.toList())
        layaIdle()
        return out.put("laya", p.key).put("confidence", p.confidence).put("get_engine_ms", t1 - t0)
            .put("session_ms", laya.loadMs.first).put("tokenizer_ms", laya.loadMs.second)
            .put("choose_ms", SystemClock.elapsedRealtime() - t1)
    }

    /** Perform one decided op, reusing the action primitives; geometry per scripts/live_adb.py act(). */
    internal fun act(op: String, target: ScreenCandidate?, payload: Any?, cands: List<ScreenCandidate>) {
        when {
            op == "CLICK" || op == "LONG_PRESS" -> target?.let {
                val cx = (it.bounds[0] + it.bounds[2]) / 2.0
                val cy = (it.bounds[1] + it.bounds[3]) / 2.0
                stroke(cx, cy, cx, cy, if (op == "LONG_PRESS") 1000 else 60)
            }
            op.startsWith("SCROLL_") -> {
                val box = cands.filter { it.scrollable }
                    .maxByOrNull { (it.bounds[2] - it.bounds[0]) * (it.bounds[3] - it.bounds[1]) }?.bounds
                    ?: intArrayOf(0, 300, screenW(), screenH() - 300)
                val cx = (box[0] + box[2]) / 2.0
                val cy = (box[1] + box[3]) / 2.0
                val dx = (box[2] - box[0]) * 0.3
                val dy = (box[3] - box[1]) * 0.3
                // SCROLL_DOWN reveals content below, so the finger moves up (AndroidControl convention).
                val (sx, sy) = when (op) {
                    "SCROLL_DOWN" -> 0.0 to 1.0
                    "SCROLL_UP" -> 0.0 to -1.0
                    "SCROLL_RIGHT" -> 1.0 to 0.0
                    else -> -1.0 to 0.0
                }
                stroke(cx + sx * dx, cy + sy * dy, cx - sx * dx, cy - sy * dy, 400)
            }
            op == "OPEN_APP" -> (payload as? Pair<*, *>)?.let { openApp(it.second.toString()) }
            op == "INPUT_TEXT" -> (payload as? String)?.let { setText(it) }
            op == "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            op == "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            op == "WAIT" -> Thread.sleep(2000)
        }
    }

    private fun screenW() = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.width()
    private fun screenH() = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.height()
    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        @Volatile var instance: AgentService? = null
        private const val IDLE_MS = 3 * 60_000L  // ~700 MB stays resident this long after a request (D-016)
        private val SEARCH_BOX = Regex("search|query|find|look ?up", RegexOption.IGNORE_CASE)
        private val TEACH_EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        private val GLOBALS = mapOf(
            "back" to GLOBAL_ACTION_BACK, "home" to GLOBAL_ACTION_HOME, "recents" to GLOBAL_ACTION_RECENTS,
            "notifications" to GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }
}
