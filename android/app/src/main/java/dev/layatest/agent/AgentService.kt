package dev.layatest.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
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
import dev.layatest.agent.coordinator.TaskCoordinator
import dev.layatest.agent.coordinator.TaskCoordinatorChecks
import dev.layatest.agent.coordinator.TaskStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** The agent's eyes and hands: exports the screen's window forest and performs actions, driven through [Bridge]. */
class AgentService : AccessibilityService() {
    val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = SystemClock.uptimeMillis()
    private var bridge: Bridge? = null
    internal var wake: WakeWord? = null  // "Hey Friday" (D-035)
    internal var bubble: Bubble? = null  // quick access over every app
    // UI requests, bridge model diagnostics and prewarm share ownership of model lifetimes.
    private val modelLock = ReentrantLock()
    private val requestState = Any()
    @Volatile private var destroyed = false
    internal val stopping get() = destroyed
    @Volatile private var writing = false

    override fun onServiceConnected() {
        destroyed = false
        instance = this
        bridge = Bridge(this).also { it.start() }
        wake = WakeWord(this).also { it.start() }
        bubble = Bubble(this).also { it.show() }
        Thread({
            runCatching { TaskCoordinator.recoverInterrupted(this) }
                .onFailure { android.util.Log.e("Friday", "Task recovery failed", it) }
        }, "friday-recovery").start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != packageName) lastEventAt = SystemClock.uptimeMillis()
        teacher?.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        destroyed = true  // the Writer monitor cancels an in-flight load/generation
        bridge?.stop()
        wake?.close()
        bubble?.hide()
        Thread({ modelLock.lock(); try { freeLaya() } finally { modelLock.unlock() } }, "models-destroy").start()
        instance = null
        Thread({ TaskStore.get(this).changed() }, "friday-service-state").start()
        super.onDestroy()
    }

    fun handle(c: JSONObject): JSONObject {
        val cmd = c.getString("cmd")
        if (cmd !in MODEL_COMMANDS) return command(c)
        if (!beginRequest()) return JSONObject().put("status", "busy").put("error", "Friday is already working")
        if (!modelLock.tryLock()) {
            endRequest()
            return JSONObject().put("status", "busy").put("error", "A model is loading or running")
        }
        return try {
            if (cmd == "bench" || cmd == "decide_test") freeLaya()
            command(c)
        } finally {
            layaIdle()
            modelLock.unlock()
            endRequest()
        }
    }

    private fun command(c: JSONObject): JSONObject = when (val cmd = c.getString("cmd")) {
        "state" -> {
            settle(c.optLong("quiet_ms", 300), c.optLong("timeout_ms", 3000))
            state(c.optBoolean("include_own"))
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
            .put("routes", routesSelfCheck()).put("routing", routingSelfCheck()).put("wake", wakeSelfCheck(this))
            .put("shortlist", shortlistSelfCheck()).put("card", cardSelfCheck()).put("icons", iconNamerSelfCheck())
            .put("conversation", conversationSelfCheck()).put("writer", Writer.selfCheck()).put("message_read", ReadMessages.selfCheck())
            .put("research", ResearchWorkflow.selfCheck()).put("gemini_chat", GeminiChat.selfCheck())
            .put("gemini_research", GeminiResearch.selfCheck()).put("hotel", HotelSearch.selfCheck())
            .put("calendar", CalendarEvents.selfCheck())
        "calendar_selfcheck" -> JSONObject().put("calendar", CalendarEvents.selfCheck()).put("routing", routingSelfCheck())
        "calendar_observe" -> CalendarEvents.observe(this, c.getString("title"), c.getLong("from_ms"), c.getLong("until_ms"))
        "workflow_state" -> JSONObject().put("research", ResearchWorkflow.state(this) ?: JSONObject.NULL)
        "coordinator_selfcheck" -> TaskCoordinatorChecks.run(this)
        "research_selfcheck" -> JSONObject().put("gemini_chat", GeminiChat.selfCheck())
            .put("gemini_research", GeminiResearch.selfCheck()).put("gemini_copy", GeminiResponse.selfCheck()).put("hotel", HotelSearch.selfCheck())
        "task_state" -> {
            val store = TaskStore.get(this)
            val snapshot = if (c.has("task_id")) store.snapshot(c.getString("task_id")) else store.latestForChat(ChatHistory.activeId(this))
            JSONObject().put("task", snapshot?.let { s -> JSONObject().put("id", s.task.id).put("chat_id", s.task.chatId)
                .put("capability", s.task.capability).put("provider", s.task.provider)
                .put("state", s.task.state).put("message", s.task.message).put("request", s.task.request)
                .put("published", s.task.published).put("ms", s.task.activeMs).put("revision", s.task.revision)
                .put("answer_chars", s.task.answer?.length ?: 0).put("evidence", JSONObject(s.task.evidence))
                .put("steps", JSONArray(s.steps.map { step -> JSONObject().put("id", step.id).put("capability", step.capability)
                    .put("state", step.state).put("attempts", step.attempts).put("ms", step.activeMs).apply {
                        if (c.optBoolean("include_evidence")) {
                            put("output", JSONObject(step.output)); put("evidence", JSONObject(step.evidence))
                        }
                    } }))
                .put("events", JSONArray(s.events.map { event -> JSONObject().put("kind", event.kind).put("state", event.state)
                    .put("message", event.message).put("ms", event.durationMs).put("time", event.createdAt) }))
            } ?: JSONObject.NULL)
        }
        "resume_task" -> JSONObject().put("started", resumeTask(c.getString("task_id")))
        "recheck_task" -> JSONObject().put("started", runGoal("", research = true, resumeTaskId = c.getString("task_id"), recheck = true))
        "cancel_task" -> { cancelTask(c.getString("task_id")); JSONObject().put("stopping", true) }
        "wake" -> wake?.command(c) ?: error("the wake word isn't set up yet")  // {"on": bool} optional; listening stats
        "wake_test" -> wakeTest(this, java.io.File(filesDir, c.getString("wav")))  // max score of a WAV in filesDir
        "profiles" -> JSONObject(AppProfiler.profiles(packageManager) as Map<*, *>)  // D-031: auto app profiles, to evaluate
        "route" -> routeTest(c.getString("text"))
        "run" -> JSONObject().put("started", runGoal(c.getString("goal"), c.optBoolean("research")))
        "writer" -> when (c.optString("action", "generate")) {
            "status", "unload" -> JSONObject().put("status", "ok").put("writer_loaded", false)
                .put("laya_loaded", layaWarm).put("cold", true)
            "generate" -> {
                val banner = Overlay(this).banner()
                try {
                    banner.say("Writing a draft…")
                    write(Writer.fromJson(c)) { banner.stopped }
                } finally { banner.remove() }
            }
            else -> JSONObject().put("status", "invalid").put("validation_errors", JSONArray(listOf("unsupported action")))
        }
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
        // the task suite's options (D-049): memory on/read/off, unattended (never wait for a tap), shortlist size
        // "follow": true carries on the open task, as the owner's next words on the phone do (D-061)
        "ask" -> Assistant(this, c.getString("goal"), c.optString("memory", "on"), c.optBoolean("unattended"),
            c.optInt("shortlist", Assistant.SHORTLIST), session?.takeIf { c.optBoolean("follow") && it.fresh }, c.optBoolean("research")).also { lastAsk = it }.let { a ->
            JSONObject().put("answer", a.run().also { session = a.session() }).put("trace", JSONArray(a.trace)).put("kept", a.kept)
                .put("learned", a.learned?.steps?.joinToString(" → ") { it.describe() } ?: JSONObject.NULL)
                .put("session", session?.let { s -> JSONObject().put("goal", s.goal).put("pkg", s.pkg).put("finished", s.finished).put("steps", s.path.size) } ?: JSONObject.NULL)
        }
        // the answer card's ✓ Right / ✗ Wrong, for the last "ask" -- after checking its steps
        "remember" -> JSONObject().put("remembered", lastAsk?.learned != null).also { lastAsk?.keep("confirmed") }
        "reject" -> JSONObject().put("rejected", lastAsk != null).also { lastAsk?.reject() }
        "routes" -> Routes.all(this)  // UI memory (D-040), for debugging
        "step_parity" -> stepParity(c.getString("goal"))
        "shortlist_eval" -> shortlistEval(c)
        // E4 (research/26): the current screen's unnamed icons as the icon model names them, without Laya
        "icons" -> {
            val st = readState()
            val size = st.getJSONArray("screen")
            val notes = JSONArray()
            val before = buildCandidates(st, size.getInt(0), size.getInt(1))
            val after = IconNamer.relabel(this, st.optString("package").ifEmpty { null }, st, before) { notes.put(it) }
            JSONObject().put("package", st.optString("package")).put("notes", notes).put("icons", JSONArray(before.indices
                .filter { IconNamer.isIcon(before[it], minOf(size.getInt(0), size.getInt(1)) / 4) }
                .map { i -> JSONObject().put("bounds", JSONArray(before[i].bounds.toList())).put("rid", before[i].rid)
                    .put("name", after[i].label.ifEmpty { JSONObject.NULL }) }))
        }
        "screenshot" -> screenshotTest()  // icon labels step 3: can Friday see this app's pixels?
        // the request log's last entries (default 20), timelines included
        "requests" -> JSONObject().put("requests", JSONArray(java.io.File(filesDir, Assistant.REQUEST_LOG)
            .takeIf { it.exists() }?.readLines()?.takeLast(c.optInt("last", 20))?.map { JSONObject(it) } ?: emptyList<JSONObject>()))
        "laya_pin" -> JSONObject().put("pin", c.getBoolean("on").also { on -> laya().pinFastCores = on; layaIdle() })
            .put("fast_cores", Integer.toBinaryString(Native.fastCores))
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
    private fun state(includeOwn: Boolean = false): JSONObject {
        val wins = JSONArray()
        for (w in windows) {
            val root = w.root ?: continue
            if (!includeOwn && root.packageName == packageName) continue // our own confirmation overlay
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
    /** Types [text] into the focused field; a search box ([label] is the field as Laya saw it) is then submitted. */
    private fun setText(text: String, label: String? = null): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // Compose fields often expose no hint or id: the Play Store's showed only as "Search Books" (2026-10-18)
        val search = listOfNotNull(field.hintText, field.contentDescription, field.viewIdResourceName, label).any { SEARCH_BOX.containsMatchIn(it) }
        if (ok && search) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return ok
    }

    /** An approved workflow types into the field it just checked, without submitting a keyboard action. */
    internal fun setFocusedTextExact(pkg: String, bounds: IntArray, old: String, text: String): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val actual = Rect().also(field::getBoundsInScreen)
        if (!field.isEditable || !field.isEnabled || !field.isVisibleToUser || field.packageName?.toString() != pkg ||
            field.text?.toString().orEmpty() != old ||
            !bounds.contentEquals(intArrayOf(actual.left, actual.top, actual.right, actual.bottom))) return false
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }

    private fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // ---- Friday the assistant: a typed request runs through Assistant (route -> skill -> answer) ----
    @Volatile private var running = false

    private fun beginRequest(): Boolean = synchronized(requestState) {
        if (running || teacher != null || destroyed) false else { running = true; true }
    }

    private fun endRequest() {
        synchronized(requestState) { running = false }
        TaskStore.get(this).changed() // Enables Resume after the last state commit releases the request gate.
    }

    /** Running a request or being taught: the wake word must not pop the request box over either. */
    internal val busy get() = running || teacher != null

    /** The task the owner can carry on with, by voice or text, for a few minutes after a request (D-061). */
    @Volatile internal var session: Session? = null

    /** Reply to the answer card: the bubble listens where the owner is, or the typed box opens. */
    private fun reply() {
        if (Bubble.isOn(this)) bubble?.listenNow()
        else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Handle one typed request on a background thread (one at a time) and show the answer as a card. */
    fun resumeTask(taskId: String): Boolean = runGoal("", research = true, resumeTaskId = taskId)

    fun cancelTask(taskId: String) {
        TaskCoordinator.signalCancel(taskId)
        Thread({ TaskCoordinator.cancel(this, taskId) }, "friday-cancel").start()
    }

    /** Worker-only; clearing and request intake share the same ownership gate. */
    internal fun clearChat(chatId: String): Boolean {
        if (!beginRequest()) return false
        return try { TaskCoordinator.clearChat(this, chatId) } finally { endRequest() }
    }

    fun runGoal(goal: String, research: Boolean = false, resumeTaskId: String? = null, recheck: Boolean = false): Boolean {
        if ((goal.isBlank() && resumeTaskId == null) || !beginRequest()) return false
        val originChatId = ChatHistory.activeId(this)
        Thread({
            modelLock.lock()
            try {
                val saved = resumeTaskId?.let { TaskStore.get(this).dao.task(it) }
                if (resumeTaskId != null && saved == null) { toast("This task was cleared."); return@Thread }
                if (recheck && (resumeTaskId == null || !TaskCoordinator.requestRecheck(this, resumeTaskId))) {
                    toast("This result cannot be rechecked now."); return@Thread
                }
                val a = Assistant(this, saved?.request ?: goal.trim(), prev = session?.takeIf { it.fresh }, research = research,
                    resumeTaskId = resumeTaskId, originChatId = saved?.chatId ?: originChatId)
                val startedAt = SystemClock.elapsedRealtime()
                val said = a.run()
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                session = a.session()
                // the owner sees the steps and says right or wrong: Laya sometimes overshoots (D-048 feedback)
                val did = (a.learned ?: a.replayed)?.steps?.joinToString(" → ") { it.describe() }
                val text = buildString {
                    append(said)
                    if (did != null) append("\n\nI did: $did")
                    if (a.kept) append("\n(It checked out, so I'll remember how)")
                    append("\n\nTime: ${seconds / 60}m ${seconds % 60}s")
                }
                Overlay(this).answer(text,
                    right = a.learned?.takeIf { !a.kept }?.let { { a.keep("confirmed"); toast("Got it — I'll do it this way next time") } },
                    wrong = if (did != null) { { a.reject(); toast("Noted — I won't do it that way again") } } else null,
                    showMe = if (did != null) { { startTeaching(goal.trim()) } } else null,
                    reply = { reply() })
            } catch (e: Throwable) {
                toast("error: ${e.message}")
            } finally {
                modelLock.unlock()
                endRequest()
            }
        }, "friday-request").start()
        return true
    }

    // ---- Laya stays warm between requests and is freed after IDLE_MS unused (D-016: load on demand, free when idle) ----
    @Volatile private var warm: LayaEngine? = null
    // off the main thread: releasing waits on the lock that laya() holds while loading (~10 s)
    private val idleRelease = Runnable { Thread(::releaseLaya, "laya-release").start() }

    internal val layaWarm get() = warm != null

    /** The shared engine, loading it if needed (~10 s). Callers must not close it; call [layaIdle] when done. */
    internal fun laya(): LayaEngine {
        check(modelLock.isHeldByCurrentThread && !writing) { "Laya needs exclusive model ownership" }
        main.removeCallbacks(idleRelease)
        return warm ?: LayaEngine(this).also { warm = it }
    }

    internal fun layaIdle() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_MS)
    }

    private fun freeLaya() {
        check(modelLock.isHeldByCurrentThread)
        main.removeCallbacks(idleRelease)
        warm?.close()
        warm = null
    }

    private fun releaseLaya() {
        if (running || !modelLock.tryLock()) return
        try { if (!running) freeLaya() } finally { modelLock.unlock() }
    }

    /** Writer requests are text-only; it is always freed before this model lock can pass to Laya. */
    internal fun write(request: WriterRequest, stopped: () -> Boolean = { false }): JSONObject {
        check(modelLock.isHeldByCurrentThread)
        if (request.errors().isEmpty()) freeLaya()
        writing = true
        return try { Writer.run(this, request) { destroyed || stopped() } }
        finally { writing = false }
    }

    /** Load Laya in the background when the owner opens Friday, so it's warm by the time they tap Run. */
    fun prewarm() {
        if (warm == null && !busy && !destroyed) Thread({
            if (modelLock.tryLock()) try {
                if (!busy && !destroyed) runCatching { laya(); layaIdle() }
            } finally { modelLock.unlock() }
        }, "laya-prewarm").start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) idleRelease.run()
    }

    // ---- Teach mode (D-033): taps and typing are subscribed to only while the owner is teaching ----
    @Volatile private var teacher: Teacher? = null
    @Volatile private var lastAsk: Assistant? = null  // the bridge's last "ask", for "remember"

    fun startTeaching(goal: String, rawTouches: Boolean = false): Boolean = synchronized(requestState) {
        if (goal.isBlank() || teacher != null || running || destroyed) return@synchronized false
        listenForTeaching(true)
        bubble?.hide()
        teacher = Teacher(this, goal.trim(), rawTouches)
        true
    }

    internal fun finishTeaching(keep: Boolean) {
        teacher?.let { if (keep) it.done() else it.cancel() }
    }

    internal fun teachingEnded(message: String) {
        teacher = null
        listenForTeaching(false)
        bubble?.show()
        toast(message)
    }

    private fun listenForTeaching(on: Boolean) {
        serviceInfo = serviceInfo.apply { eventTypes = if (on) eventTypes or TEACH_EVENTS else eventTypes and TEACH_EVENTS.inv() }
    }

    /** The screen once it settles: the forest that Laya and the extractors read. */
    internal fun readState(): JSONObject {
        val t = SystemClock.elapsedRealtime()
        settle(300, 1500)  // a screen that never goes quiet (a running stopwatch, a video) cost 3 s a read
        lastSettleMs = SystemClock.elapsedRealtime() - t
        return state()
    }

    /** How long the last [readState] waited for the screen to go quiet (step timings in the trace). */
    @Volatile var lastSettleMs = 0L
        private set

    internal fun foregroundPkg(): String? = rootInActiveWindow?.packageName?.toString()

    /** Bridge check (research/19): on this screen, the split step() must answer like the one-batch step, faster. */
    private fun stepParity(goal: String): JSONObject {
        val st = readState()
        val sz = st.getJSONArray("screen")
        val cands = buildCandidates(st, sz.getInt(0), sz.getInt(1))
        val e = laya()
        fun Step.text() = "$op %.4f target=$target %.4f".format(opConfidence, targetConfidence ?: 0.0)
        val t0 = SystemClock.elapsedRealtime()
        val split = e.step(goal, emptyList(), cands)
        val t1 = SystemClock.elapsedRealtime()
        val both = e.stepBatched(goal, emptyList(), cands)
        val t2 = SystemClock.elapsedRealtime()
        layaIdle()
        val same = split.op == both.op && (split.target == null || split.target == both.target) &&
            kotlin.math.abs(split.opConfidence - both.opConfidence) < 1e-3
        return JSONObject().put("same", same).put("candidates", cands.size)
            .put("split", split.text()).put("split_ms", t1 - t0).put("split_tokens", split.tokens)
            .put("batched", both.text()).put("batched_ms", t2 - t1).put("batched_tokens", both.tokens)
    }

    /**
     * Bridge test (D-049): Laya's step on a recorded screen ([c] "cands": option fields as scripts/speed_eval.py
     * rebuilds them) with every element as a target option, then with the shortlist of each size in "ks".
     */
    private fun shortlistEval(c: JSONObject): JSONObject {
        val arr = c.getJSONArray("cands")
        val cands = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { j ->
            ScreenCandidate(j.getString("label"), j.getString("class"), j.optBoolean("clickable"), j.optBoolean("long_clickable"),
                j.optBoolean("editable"), j.optBoolean("scrollable"), false, j.optBoolean("checked"), j.optBoolean("selected"),
                j.optBoolean("enabled", true), IntArray(4), j.optString("rid"))
        } }
        val goal = c.getString("goal")
        val history = c.optJSONArray("history")?.let { h -> (0 until h.length()).map(h::getString) }.orEmpty()
        val want = Routes.aboutWords(goal, null)
        val e = laya()
        fun run(s: List<Int>): JSONObject {
            val t = SystemClock.elapsedRealtime()
            val st = e.step(goal, history, cands, s)
            return JSONObject().put("op", st.op).put("target", st.target ?: -1).put("conf", st.targetConfidence ?: 0.0)
                .put("ms", SystemClock.elapsedRealtime() - t).put("tokens", st.tokens).put("shown", JSONArray(s))
        }
        val out = JSONObject().put("full", run(cands.indices.toList()))
        // the operation row on a shortlist (research/18 §11): same goal and history, fewer elements
        c.optJSONArray("op_ks")?.let { ks ->
            for (i in -1 until ks.length()) {  // -1: every element, timed alone for a fair comparison
                val shown = if (i < 0) cands.indices.toList() else shortlist(cands, want, ks.getInt(i))
                val t = SystemClock.elapsedRealtime()
                val (op, tokens) = e.operation(goal, history, shown.map { cands[it].toCandidate() })
                out.put(if (i < 0) "op_all" else "op${ks.getInt(i)}", JSONObject().put("op", op.choiceKey).put("conf", op.answerConfidence)
                    .put("ms", SystemClock.elapsedRealtime() - t).put("tokens", tokens).put("size", shown.size))
            }
        }
        // a step without a target (scroll, back, done) is the same either way
        if (out.getJSONObject("full").getInt("target") >= 0) {
            val ks = c.getJSONArray("ks")
            for (i in 0 until ks.length()) out.put("k${ks.getInt(i)}", run(shortlist(cands, want, ks.getInt(i))))
        }
        layaIdle()
        return out
    }

    /**
     * Icon labels, step 3: a screenshot of what is on screen now, reduced to numbers -- its size and the share of
     * pure-black pixels. An app that sets FLAG_SECURE (money apps) should come back black or refused. Nothing is saved
     * or sent anywhere.
     */
    private fun screenshotTest(): JSONObject {
        val out = JSONObject().put("package", rootInActiveWindow?.packageName?.toString())
        val bmp = screenshot().getOrElse { return out.put("error", it.message) }
        var black = 0
        var n = 0
        for (y in 0 until bmp.height step 8) for (x in 0 until bmp.width step 8) {
            val p = bmp.getPixel(x, y)
            n++
            if ((p shr 16 and 0xff) < 8 && (p shr 8 and 0xff) < 8 && (p and 0xff) < 8) black++
        }
        out.put("width", bmp.width).put("height", bmp.height).put("black", black.toDouble() / n)
        bmp.recycle()
        return out
    }

    private var lastShotAt = 0L
    private val shotLock = Any()  // not the service's own: laya() holds that for ~10 s while Laya loads

    /**
     * The display now as a software ARGB_8888 bitmap (icon labels, research/25 §4): a HARDWARE bitmap can't be read, and
     * cropping one copies the whole screen each time, so it's copied once. Waits out the system's 333 ms between shots
     * (sooner fails with error 3). A secure window comes back black, not as a failure (GPay, research/26). Never saved.
     */
    internal fun screenshot(): Result<Bitmap> = synchronized(shotLock) {
        val wait = lastShotAt + 350 - SystemClock.uptimeMillis()  // 333 ms on the system's clock, plus the call's latency
        if (wait > 0) Thread.sleep(wait)
        lastShotAt = SystemClock.uptimeMillis()
        var got = Result.failure<Bitmap>(IllegalStateException("no screenshot within 2 s"))
        val done = CountDownLatch(1)
        // the callback runs on the binder thread, not main: main may be waiting on a lock the caller holds (IconNamer.wipe)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, { it.run() }, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                got = runCatching {
                    Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)!!.let { hw ->
                        hw.copy(Bitmap.Config.ARGB_8888, false)!!.also { hw.recycle() }
                    }
                }
                shot.hardwareBuffer.close()
                done.countDown()
            }

            override fun onFailure(code: Int) {
                // 3: too soon after the last one; 6: a secure window (5 is a bad window id)
                got = Result.failure(IllegalStateException("screenshot error $code"))
                done.countDown()
            }
        })
        done.await(2, TimeUnit.SECONDS)
        got
    }

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
    /**
     * [c]'s node in the live tree: the element with the same label, class and id nearest where [c] was (the page may
     * have moved since), found again among the live nodes by its box clipped to the screen, as the candidates were.
     */
    private fun liveNode(c: ScreenCandidate): AccessibilityNodeInfo? {
        val w = screenW()
        val h = screenH()
        // the live tree has no learned names (Meanings, IconNamer): an element named by them is unnamed there
        val fresh = buildCandidates(state(), w, h).filter { it.className == c.className && it.rid == c.rid }
        val now = (fresh.filter { it.label == c.label }.ifEmpty { fresh.filter { it.label.isEmpty() } })
            .minByOrNull { kotlin.math.abs(it.bounds[0] - c.bounds[0]) + kotlin.math.abs(it.bounds[1] - c.bounds[1]) } ?: return null
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val r = Rect().also(n::getBoundsInScreen)
            if (r.intersect(0, 0, w, h) && n.className?.toString()?.substringAfterLast('.') == now.className &&
                r.left == now.bounds[0] && r.top == now.bounds[1] && r.right == now.bounds[2] && r.bottom == now.bounds[3]) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::find)?.let { return it }
            return null
        }
        return windows.firstNotNullOfOrNull { win -> win.root?.takeIf { it.packageName != packageName }?.let(::find) }
    }

    /** Retry a confirmed unchanged target when an app reports a click but has not changed its UI. */
    internal fun gestureClick(pkg: String, target: ScreenCandidate): Boolean {
        if (foregroundPkg() != pkg) return false
        val node = liveNode(target) ?: return false
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.packageName?.toString() != pkg || !node.isVisibleToUser || !node.isEnabled ||
            !target.bounds.contentEquals(intArrayOf(rect.left, rect.top, rect.right, rect.bottom))) return false
        return stroke(rect.exactCenterX().toDouble(), rect.exactCenterY().toDouble(),
            rect.exactCenterX().toDouble(), rect.exactCenterY().toDouble(), 60)
    }

    internal fun act(op: String, target: ScreenCandidate?, payload: Any?, cands: List<ScreenCandidate>) {
        when {
            op == "CLICK" || op == "LONG_PRESS" -> target?.let {
                // Click the element itself: a tap at its centre lands on whatever is drawn over it (GPay's floating
                // "Scan any QR code" took a tap meant for "See transaction history", 2026-10-18) or moved there since
                // the screen was read. A finger tap where it is now only if the app doesn't take the click.
                val node = liveNode(it)
                val action = if (op == "CLICK") AccessibilityNodeInfo.ACTION_CLICK else AccessibilityNodeInfo.ACTION_LONG_CLICK
                var n = node
                while (n != null && !(if (op == "CLICK") n.isClickable else n.isLongClickable)) n = n.parent
                if (n?.performAction(action) == true) return
                val b = node?.let { v -> Rect().also(v::getBoundsInScreen) }?.takeIf { r -> !r.isEmpty }
                val cx = b?.exactCenterX()?.toDouble() ?: ((it.bounds[0] + it.bounds[2]) / 2.0)
                val cy = b?.exactCenterY()?.toDouble() ?: ((it.bounds[1] + it.bounds[3]) / 2.0)
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
            op == "INPUT_TEXT" -> (payload as? String)?.let { setText(it, target?.label) }
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
        private val MODEL_COMMANDS = setOf("ask", "writer", "route", "bench", "decide_test", "step_parity", "shortlist_eval", "laya_pin", "coordinator_selfcheck")
        private val SEARCH_BOX = Regex("search|query|find|look ?up", RegexOption.IGNORE_CASE)
        private val TEACH_EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        private val GLOBALS = mapOf(
            "back" to GLOBAL_ACTION_BACK, "home" to GLOBAL_ACTION_HOME, "recents" to GLOBAL_ACTION_RECENTS,
            "notifications" to GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }
}
