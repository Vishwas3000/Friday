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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
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
        "selfcheck" -> JSONObject().put("result", candidatesSelfCheck()) // Phase-2d: candidate port vs the Python cases
        "run" -> { runGoal(c.getString("goal")); JSONObject().put("started", true) }
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

    /** Types into the focused field; unicode-safe, unlike `adb shell input text`. */
    private fun setText(text: String): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // ---- Phase 2d: the standalone on-device decide loop (type a goal in the app, no Mac) ----
    @Volatile private var running = false

    /** Start a run for [goal] on a background thread (one at a time). Called from MainActivity / the "run" cmd. */
    fun runGoal(goal: String) {
        if (goal.isBlank() || running) return
        running = true
        Thread({
            try { loop(goal.trim()) } catch (e: Throwable) { toast("error: ${e.message}") } finally { running = false }
        }, "laya-run").start()
    }

    private fun loop(goal: String) {
        val model = File(filesDir, "laya-android.fp16.onnx")
        val tokFile = File(filesDir, "tokenizer.json")
        if (!model.exists() || !tokFile.exists()) { toast("push the model + tokenizer first"); return }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            setCPUArenaAllocator(false)      // D-023: the arena, not the weights, is the RAM driver
            setMemoryPatternOptimization(false)
        }
        toast("loading Laya…")
        val session = env.createSession(model.absolutePath, opts)
        val tok = Tokenizer(tokFile)
        val apps = appsList()
        val history = ArrayList<String>()
        val typed = ArrayList<String>()
        val blocked = HashSet<String>()  // skipped targets, so the loop doesn't re-pick them
        var skips = 0
        try {
            for (step in 1..MAX_STEPS) {
                settle(300, 3000)
                val state = state()
                val screen = state.getJSONArray("screen")
                val cands = buildCandidates(state, screen.getInt(0), screen.getInt(1))
                    .filter { key(it) !in blocked }
                val built = buildInputs(tok::encode, goal, history, cands.map { it.toCandidate() })
                val tensors = linkedMapOf(
                    "input_ids" to OnnxTensor.createTensor(env, built.ort.inputIds),
                    "attention_mask" to OnnxTensor.createTensor(env, built.ort.attentionMask),
                    "marker_pos" to OnnxTensor.createTensor(env, built.ort.markerPos),
                    "marker_mask" to OnnxTensor.createTensor(env, built.ort.markerMask),
                    "qtype" to OnnxTensor.createTensor(env, built.ort.qtype),
                )
                val result = session.run(tensors)
                @Suppress("UNCHECKED_CAST") val logits = result.get(0).value as Array<FloatArray>
                @Suppress("UNCHECKED_CAST") val act = result.get(1).value as Array<FloatArray>
                result.close()
                tensors.values.forEach { it.close() }

                val ans = decode(logits, act, built)
                val op = ans.getValue("operation").choiceKey
                val target = ans["target"]?.let { cands.getOrNull(it.choiceIndex) }
                val payload: Any? = when (op) {
                    "OPEN_APP" -> pickApp(goal, apps)
                    "INPUT_TEXT" -> pickText(goal, typed)
                    else -> null
                }
                if (op == "DONE") { toast("done ✓"); break }
                if ((op == "OPEN_APP" || op == "INPUT_TEXT") && payload == null) {
                    toast("$op needs an app name / quoted text in the goal"); break
                }

                val shown = when {
                    (op == "CLICK" || op == "LONG_PRESS") && target != null -> target.label.ifEmpty { target.className }
                    op == "OPEN_APP" -> (payload as Pair<*, *>).first.toString()
                    op == "INPUT_TEXT" -> "\"$payload\""
                    else -> ""
                }
                val boundsJson = if ((op == "CLICK" || op == "LONG_PRESS") && target != null)
                    JSONArray(target.bounds.toList()) else null
                when (Overlay(this).ask("Step $step: $op $shown\nGoal: $goal", boundsJson)) {
                    "stop" -> break
                    "skip" -> {
                        skips++
                        if (target != null) blocked.add(key(target))
                        if (skips >= 3) { toast("stopped: 3 skips"); break }
                        continue
                    }
                }
                skips = 0
                act(op, target, payload, cands)
                if (op == "INPUT_TEXT") typed.add(payload as String)
                history.add(historyEntry(op, target?.toCandidate(),
                    if (op == "OPEN_APP") (payload as Pair<*, *>).first.toString() else payload as? String))
                if (history.size >= 3 && history.takeLast(3).toSet().size == 1) { toast("stopped: looping"); break }
                Thread.sleep(300)
            }
        } finally {
            tok.close()
            session.close()
        }
    }

    private fun key(c: ScreenCandidate) = "${c.label}@${c.bounds.joinToString(",")}"

    /** Perform one decided op, reusing the action primitives; geometry per scripts/live_adb.py act(). */
    private fun act(op: String, target: ScreenCandidate?, payload: Any?, cands: List<ScreenCandidate>) {
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

    private fun appsList(): List<Pair<String, String>> {
        val a = apps()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("label") to o.getString("package") } }
    }

    private fun screenW() = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.width()
    private fun screenH() = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.height()
    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        @Volatile var instance: AgentService? = null
        private const val MAX_STEPS = 15
        private val GLOBALS = mapOf(
            "back" to GLOBAL_ACTION_BACK, "home" to GLOBAL_ACTION_HOME, "recents" to GLOBAL_ACTION_RECENTS,
            "notifications" to GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }
}
