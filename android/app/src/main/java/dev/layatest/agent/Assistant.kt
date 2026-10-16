package dev.layatest.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray

/**
 * One request, end to end (D-024): ROUTE (rules -> Laya -> ask the owner) -> the skill (slots, app,
 * do, read) -> the answer. Runs on its own thread. Laya loads only when a step needs it and is freed
 * when the request ends (D-016). Every UI action passes [Safety.verdict] here, under the skills (D-025/D-026).
 */
class Assistant(private val svc: AgentService, override val request: String) : SkillContext {
    override val android: Context get() = svc
    override val slots = Slots(request)
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

    /** What happened, step by step (route, apps opened, each Laya decision + verdict, outcome): for debugging. */
    val trace = ArrayList<String>()

    /** Handle the request; returns what to tell the owner. */
    fun run(): String = try {
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
        val state = svc.readState()
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

    override fun runLaya(subgoal: String, maxSteps: Int, typeText: String?): Outcome {
        val blocked = HashSet<String>()  // targets skipped or refused, so Laya doesn't pick them again
        var misses = 0
        for (step in 1..maxSteps) {
            if (stopped) return Outcome.STOPPED
            val scr = screen()
            if (Safety.isAuthPrompt(scr.pkg, scr.texts.map { it.text })) {
                say("Unlock with your fingerprint — I'll wait")
                val ok = waitUntil(90_000) { screen().let { !Safety.isAuthPrompt(it.pkg, it.texts.map { t -> t.text }) } }
                if (!ok) return Outcome.STOPPED
                continue
            }
            val cands = scr.candidates.filter { key(it) !in blocked }
            say("Thinking… (step $step)")
            val s = laya.step(subgoal, trail, cands)
            if (s.op == "DONE") { trace += "step $step on ${scr.pkg}: DONE (op %.2f)".format(s.opConfidence); return Outcome.DONE }
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
            trace += "step $step on ${scr.pkg}: ${s.op} \"${target?.label.orEmpty()}\" (op %.2f) -> $verdict %s".format(s.opConfidence, why)
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
            say(describe(s.op, target, payload))
            banner.hide()
            if (s.op == "OPEN_APP") open(payload as AppInfo) else svc.act(s.op, target, payload, scr.candidates)
            trail += historyEntry(s.op, target?.toCandidate(), (payload as? AppInfo)?.label ?: payload as? String)
            if (trail.size >= 3 && trail.takeLast(3).toSet().size == 1) { trace += "stuck: same action 3 times"; return Outcome.STUCK }
            Thread.sleep(300)
        }
        trace += "out of steps ($maxSteps)"
        return Outcome.MAX_STEPS
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
        private const val TAG = "FridayAssistant"
        private const val GROUP = 16
    }
}
