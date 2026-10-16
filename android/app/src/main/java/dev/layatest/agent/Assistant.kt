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
    private var engine: LayaEngine? = null
    private val laya: LayaEngine
        get() = engine ?: run { banner.say("Waking up Laya…"); LayaEngine(svc).also { engine = it } }

    private val skills: Map<String, Skill> = phoneSkills().associateBy { it.id }

    /** Handle the request; returns what to tell the owner. */
    fun run(): String = try {
        banner.say("Understanding…")
        val skill = route(request, { laya }) { q, options -> ask(q, options) }
        Log.i(TAG, "request routed to ${skill ?: "something else"}")
        val result = if (stopped) null else (skill?.let { skills[it] ?: GenericSkill(it) } ?: GenericSkill("other")).run(this)
        if (stopped) "Stopped" else result ?: "Done"
    } catch (e: Exception) {
        Log.w(TAG, "request failed", e)
        "Something went wrong: ${e.message}"
    } finally {
        banner.remove()
        engine?.close()
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

    override fun launch(intent: Intent): Boolean = try {
        banner.hide()
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
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
        return true
    }

    override fun runLaya(subgoal: String, maxSteps: Int, typeText: String?): Outcome {
        val history = ArrayList<String>()
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
            val s = laya.step(subgoal, history, cands)
            if (s.op == "DONE") return Outcome.DONE
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
                history += "SKIP ${s.op}"
                if (++misses >= 3) return Outcome.STUCK
                continue
            }
            misses = 0
            say(describe(s.op, target, payload))
            banner.hide()
            if (s.op == "OPEN_APP") open(payload as AppInfo) else svc.act(s.op, target, payload, scr.candidates)
            history += historyEntry(s.op, target?.toCandidate(), (payload as? AppInfo)?.label ?: payload as? String)
            if (history.size >= 3 && history.takeLast(3).toSet().size == 1) return Outcome.STUCK
            Thread.sleep(300)
        }
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
