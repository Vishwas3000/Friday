package dev.layatest.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One request, end to end (D-024): ROUTE (rules -> Laya -> ask the owner) -> the skill (slots, app,
 * do, read) -> the answer. Runs on its own thread. Laya loads only when a step needs it and is freed
 * when the request ends (D-016). Every UI action passes [Safety.verdict] here, under the skills (D-025/D-026).
 */
class Assistant(private val svc: AgentService, override val request: String) : SkillContext {
    override val android: Context get() = svc
    override val slots = Slots(taskPart(request))
    override val apps = AppResolver(svc)
    override val stopped get() = banner.stopped

    private val overlay = Overlay(svc)
    private val banner = overlay.banner()
    private var usedLaya = false
    /** The service's shared, warm engine; loaded on first use (D-016). */
    private val laya: LayaEngine
        get() {
            if (!usedLaya && !svc.layaWarm) banner.say("Waking up Laya…")
            usedLaya = true
            return svc.laya()
        }

    private val skills: Map<String, Skill> = (phoneSkills() + mediaSkills() + peopleSkills()).associateBy { it.id }

    /** What happened, step by step (route, apps opened, each Laya decision + verdict), each line stamped with the
     *  time since the request started ("+3.2s ..."): the timeline the request log keeps. */
    val trace = object : ArrayList<String>() {
        private val t0 = SystemClock.elapsedRealtime()
        override fun add(element: String) = super.add("+%.1fs %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, element))
    }

    /** Handle the request; returns what to tell the owner. Every request is logged with its timeline (D-045). */
    fun run(): String {
        val t0 = SystemClock.elapsedRealtime()
        val said = try {
            banner.say("Understanding…")
            val skill = route(request, { laya }) { q, options -> ask(q, options) }
            Log.i(TAG, "request routed to ${skill ?: "something else"}")
            trace += "route: ${skill ?: "something else"}"
            val result = if (stopped) null else (skill?.let { skills[it] ?: GenericSkill(it) } ?: GenericSkill("other")).run(this)
            if (stopped) "Stopped" else result ?: "Done"
        } catch (e: Throwable) {  // an Error (e.g. a failed class init) must not vanish silently either
            Log.e(TAG, "request failed", e)
            trace += "error: $e"
            "Something went wrong: ${e.message ?: e}"
        } finally {
            banner.remove()
            if (usedLaya) svc.layaIdle()
        }
        // the request log (D-045): what was asked and said, how long it took, and the timeline; text only, no screens
        runCatching {
            File(svc.filesDir, REQUEST_LOG).appendText(JSONObject().put("time", System.currentTimeMillis())
                .put("request", request).put("answer", said).put("ms", SystemClock.elapsedRealtime() - t0)
                .put("trace", JSONArray(trace)).toString() + "\n")
        }
        return said
    }

    override fun appFor(skill: String): AppInfo? {
        val c = apps.candidates(skill)
        apps.named(request)?.let { named -> if (c.any { it.pkg == named.pkg }) return named }
        return when (c.size) {
            0 -> null
            1 -> c[0]
            else -> apps.default(skill) ?: ask("Which app should I use to ${SKILL_TITLES[skill]?.lowercase() ?: skill}?",
                c.map { it.label })?.let { i -> c[i].also { apps.remember(skill, it) } }
        }
    }

    override fun open(app: AppInfo) = launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.activity))

    /**
     * What Friday did in this request, oldest first; Laya's `history` (it reads the last 3). Without
     * "OPEN_APP X" Laya tries to open the app it is already in (replay 2026-10-17: OPEN_APP 0.80-0.95,
     * vs the right next step at 0.96 once the history says so), and the policy then blocks it.
     */
    private val trail = ArrayList<String>()

    override fun launch(intent: Intent): Boolean = try {
        banner.hide()
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        launched(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Record the app an intent opened the way Laya-Android's training data does: "OPEN_APP <label>". */
    private fun launched(intent: Intent) {
        @Suppress("DEPRECATION")
        val pkg = intent.`package` ?: intent.component?.packageName
            ?: svc.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: return
        val label = apps.installed.firstOrNull { it.pkg == pkg }?.label ?: return
        historyEntry("OPEN_APP", null, label).let { if (trail.lastOrNull() != it) trail += it }
        trace += "opened: $label"
    }

    override fun foreground() = svc.foregroundPkg()

    override fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end && !stopped) {
            if (runCatching(cond).getOrDefault(false)) return true
            Thread.sleep(300)
        }
        return false
    }

    override fun screen(): Screen {
        banner.hide()
        val state = svc.readState().also { lastState = it }
        val size = state.getJSONArray("screen")
        return Screen(state.optString("package").ifEmpty { null },
            buildCandidates(state, size.getInt(0), size.getInt(1)), screenTexts(state))
    }

    override fun choose(instructions: String, options: List<String>): Pick? {
        if (options.isEmpty()) return null
        if (options.size == 1) return Pick(0, "0", 1.0, mapOf("0" to 1.0))
        var pool = options.indices.toList()
        // tournament like laya.predict_tournament: each group of 16 sends its winner on (research/09)
        while (pool.size > GROUP) pool = pool.chunked(GROUP).map { g -> g[pickAmong(instructions, g, options).index] }
        val p = pickAmong(instructions, pool, options)
        return Pick(pool[p.index], pool[p.index].toString(), p.confidence, p.probabilities)
    }

    private fun pickAmong(instructions: String, idx: List<Int>, options: List<String>) =
        laya.choose(request, instructions, idx.mapIndexed { i, o -> i.toString() to options[o] })

    override fun tap(target: ScreenCandidate) = guarded("CLICK", target, emptyList())
    override fun scroll(direction: String) = guarded("SCROLL_${direction.uppercase()}", null, screen().candidates)
    override fun back() = guarded("BACK", null, emptyList())
    override fun say(text: String) = banner.say(text)

    override fun ask(question: String, options: List<String>): Int? {
        banner.hide()
        return overlay.choose(question, options)
    }

    /** One action a skill asked for, through the same policy as Laya's own steps. */
    private fun guarded(op: String, target: ScreenCandidate?, cands: List<ScreenCandidate>): Boolean {
        val (verdict, why) = Safety.verdict(op, target, foreground())
        if (verdict == Safety.Verdict.BLOCK) { say(why); return false }
        if (verdict == Safety.Verdict.ASK && confirm(describe(op, target, null), why, target) != "run") return false
        banner.hide()
        svc.act(op, target, null, cands)
        trail += historyEntry(op, target?.toCandidate(), null)
        return true
    }

    // ---- UI memory (D-040): replay remembered paths; learn the ones the owner confirms ----
    private var lastState: JSONObject? = null   // the raw screen behind the last screen(): for training rows
    private val path = ArrayList<RouteStep>()   // what this runLaya did, as a path it could remember
    private val rows = ArrayList<JSONObject>()  // ...and as Teach-format rows for fine-tuning (research/14)
    private var goal = request
    private var typed: String? = null           // request text this run types, if any

    /** A path Laya just found, for the answer card's "Remember"; null if it came from memory or didn't finish. */
    var learned: Route? = null
        private set

    /** The owner confirmed [learned]: keep the path, and its screens as training rows. */
    fun remember() {
        Routes.save(svc, learned ?: return)
        Teacher.dir(svc).mkdirs()
        File(Teacher.dir(svc), SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-run.jsonl")
            .writeText(rows.joinToString("") { "$it\n" })
    }

    override fun runLaya(subgoal: String, maxSteps: Int, typeText: String?): Outcome {
        val startPkg = foreground()
        path.clear(); rows.clear(); goal = subgoal; typed = typeText
        Routes.find(svc, startPkg, subgoal, typeText)?.let { route ->
            replay(route, typeText)?.let { return it }  // DONE or STOPPED; null: the screen changed, Laya goes on from here
        }
        val blocked = HashSet<String>()  // targets skipped or refused, so Laya doesn't pick them again
        var misses = 0
        for (step in 1..maxSteps) {
            if (stopped) return Outcome.STOPPED
            val t0 = SystemClock.elapsedRealtime()
            val scr = screen()
            val readMs = SystemClock.elapsedRealtime() - t0
            if (Safety.isAuthPrompt(scr.pkg, scr.texts.map { it.text })) {
                say("Unlock with your fingerprint — I'll wait")
                val ok = waitUntil(90_000) { screen().let { !Safety.isAuthPrompt(it.pkg, it.texts.map { t -> t.text }) } }
                if (!ok) return Outcome.STOPPED
                continue
            }
            val cands = scr.candidates.filter { key(it) !in blocked }
            say("Thinking… (step $step)")
            val t1 = SystemClock.elapsedRealtime()
            val s = laya.step(subgoal, trail, cands)
            val took = " [read $readMs ms (settle ${svc.lastSettleMs}), laya ${SystemClock.elapsedRealtime() - t1} ms: " +
                "${cands.size} cands, ${s.tokens} tokens]"
            if (s.op == "DONE") {
                trace += "step $step on ${scr.pkg}: DONE (op %.2f)".format(s.opConfidence) + took
                rows += row("DONE", scr, null, null)
                if (path.isNotEmpty() && startPkg != null) {
                    // keyed without the typed text only if this path typed it (see Routes.find)
                    learned = Route(subgoal, startPkg, Routes.key(subgoal, typeText.takeIf { path.any { it.slot } }), path.toList(), "run")
                }
                return Outcome.DONE
            }
            val target = s.target?.let { cands.getOrNull(it) }
            val payload: Any? = when (s.op) {
                "OPEN_APP" -> apps.named(subgoal)
                "INPUT_TEXT" -> typeText ?: slots.quoted.firstOrNull()
                else -> null
            }
            val (verdict, why) = when {
                (s.op == "OPEN_APP" || s.op == "INPUT_TEXT") && payload == null ->
                    Safety.Verdict.BLOCK to "nothing to ${if (s.op == "OPEN_APP") "open" else "type"}"
                else -> Safety.verdict(s.op, target, scr.pkg)
            }
            trace += "step $step on ${scr.pkg}: ${s.op} \"${target?.label.orEmpty()}\" (op %.2f) -> $verdict %s".format(s.opConfidence, why) + took
            val skipped = when (verdict) {
                Safety.Verdict.BLOCK -> { say(why); true }
                Safety.Verdict.ASK -> when (confirm(describe(s.op, target, payload), why, target)) {
                    "stop" -> return Outcome.STOPPED
                    "skip" -> true
                    else -> false
                }
                Safety.Verdict.AUTO -> false
            }
            if (skipped) {
                if (target != null) blocked += key(target)
                if (++misses >= 3) { trace += "stuck: 3 skipped/blocked steps"; return Outcome.STUCK }
                continue
            }
            misses = 0
            perform(s.op, target, payload, scr)
            if (trail.size >= 3 && trail.takeLast(3).toSet().size == 1) { trace += "stuck: same action 3 times"; return Outcome.STUCK }
            Thread.sleep(300)
        }
        trace += "out of steps ($maxSteps)"
        return Outcome.MAX_STEPS
    }

    /** Replay a remembered path. Null when a step's element isn't on screen (or policy says no): Laya takes over there. */
    private fun replay(route: Route, typeText: String?): Outcome? {
        trace += "memory (${route.source}): ${route.steps.size} steps for \"${route.goal}\""
        for ((i, st) in route.steps.withIndex()) {
            if (stopped) return Outcome.STOPPED
            val t0 = SystemClock.elapsedRealtime()
            val scr = screen()
            val target = if (!st.targeted) null else st.find(scr) ?: run {
                trace += "memory step ${i + 1}: \"${st.label}\" isn't on this screen -> Laya"
                return null
            }
            val payload: Any? = when (st.op) {
                "INPUT_TEXT" -> (if (st.slot) typeText else st.payload) ?: return null
                "OPEN_APP" -> apps.installed.firstOrNull { it.label == st.payload } ?: return null
                else -> null
            }
            val (verdict, why) = Safety.verdict(st.op, target, scr.pkg)
            when (verdict) {
                Safety.Verdict.BLOCK -> return null
                Safety.Verdict.ASK -> when (confirm(describe(st.op, target, payload), why, target)) {
                    "run" -> {}
                    "stop" -> return Outcome.STOPPED
                    else -> return null
                }
                Safety.Verdict.AUTO -> {}
            }
            trace += "memory step ${i + 1} on ${scr.pkg}: ${st.op} \"${target?.label.orEmpty()}\" -> $verdict " +
                "[read ${SystemClock.elapsedRealtime() - t0} ms, no Laya]"
            perform(st.op, target, payload, scr)
            Thread.sleep(300)
        }
        return Outcome.DONE
    }

    /** Do one step (Laya's or remembered) and record it: Laya's history, the path, a training row. */
    private fun perform(op: String, target: ScreenCandidate?, payload: Any?, scr: Screen) {
        val text = (payload as? AppInfo)?.label ?: payload as? String
        rows += row(op, scr, target, text)
        say(describe(op, target, payload))
        banner.hide()
        if (op == "OPEN_APP") open(payload as AppInfo) else svc.act(op, target, payload, scr.candidates)
        // open() already wrote "OPEN_APP X"; any other step is written even when repeated (the stuck check counts those)
        historyEntry(op, target?.toCandidate(), text).let { if (op != "OPEN_APP" || trail.lastOrNull() != it) trail += it }
        path += RouteStep.of(op, scr.pkg, target, text, slot = op == "INPUT_TEXT" && text != null && text == typed)
    }

    /** A Teach-format row (D-033) for this step, so confirmed runs become training data too. */
    private fun row(op: String, scr: Screen, target: ScreenCandidate?, payload: String?) = JSONObject()
        .put("goal", goal).put("history", JSONArray(trail)).put("op", op).put("package", scr.pkg)
        .put("time", System.currentTimeMillis()).put("state", lastState).put("source", "run").apply {
            if (target != null) put("target", scr.candidates.indexOf(target)).put("target_label", target.label)
                .put("target_bounds", JSONArray(target.bounds.toList()))
            if (payload != null) put("payload", payload)
        }

    /** Ask the owner to approve one risky step (Run / Skip / Stop), outlining its target. */
    private fun confirm(step: String, why: String, target: ScreenCandidate?): String {
        banner.hide()
        val c = overlay.ask("$step\n($why)\nGoal: $request", target?.let { JSONArray(it.bounds.toList()) })
        if (c == "stop") banner.stop()
        return c
    }

    private fun describe(op: String, target: ScreenCandidate?, payload: Any?): String {
        val name = target?.let { it.label.ifEmpty { it.className } } ?: "?"
        return describeOp(op, name, payload)
    }

    private fun describeOp(op: String, name: String, payload: Any?) = when (op) {
        "CLICK" -> "Tap \"$name\""
        "LONG_PRESS" -> "Long-press \"$name\""
        "INPUT_TEXT" -> "Type \"$payload\""
        "OPEN_APP" -> "Open ${(payload as? AppInfo)?.label ?: "an app"}"
        "BACK" -> "Go back"
        "HOME" -> "Go home"
        "WAIT" -> "Wait for the screen"
        else -> op.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun key(c: ScreenCandidate) = "${c.label}@${c.bounds.joinToString(",")}"

    companion object {
        const val REQUEST_LOG = "requests.jsonl"
        private const val TAG = "FridayAssistant"
        private const val GROUP = 16
    }
}
