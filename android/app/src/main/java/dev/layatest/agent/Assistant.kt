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
 *
 * For the task suite (D-049): [memory] "on" uses and keeps experiences, "read" only uses them, "off" neither;
 * [unattended] skips every step that would wait for the owner; [shortlistK] is the target row's size (0: all).
 */
class Assistant(private val svc: AgentService, override val request: String, private val memory: String = "on",
                private val unattended: Boolean = false, private val shortlistK: Int = SHORTLIST) : SkillContext {
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
        // an app the request names counts if it's one for this skill, or the request opens it by name: "Open bistro
        // and order a dessert" went to Swiggy, Bistro not being on the shopping list (2026-10-18)
        apps.named(request)?.let { named ->
            if (c.any { it.pkg == named.pkg } || Regex("^\\s*(please\\s+)?(open|launch|start)\\s+(the\\s+)?${Regex.escape(named.label)}\\b",
                    RegexOption.IGNORE_CASE).containsMatchIn(request)) return named
        }
        return when (c.size) {
            0 -> null
            1 -> c[0]
            else -> apps.default(skill) ?: ask("Which app should I use to ${SKILL_TITLES[skill]?.lowercase() ?: skill}?",
                c.map { it.label })?.let { i -> c[i].also { apps.remember(skill, it) } }
        }
    }

    override fun open(app: AppInfo, fresh: Boolean) = launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .apply { if (fresh) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) }
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
        val pkg = state.optString("package").ifEmpty { null }
        val known = Meanings.all(svc)  // unnamed elements Friday has tapped before are named by where they led
        val cands = buildCandidates(state, size.getInt(0), size.getInt(1)).map { e ->
            if (e.label.isNotEmpty()) e else known.optString(Meanings.key(pkg, e)).ifEmpty { null }?.let { e.copy(label = it) } ?: e
        }
        return Screen(pkg, cands, screenTexts(state), state)
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
        if (unattended) { trace += "unattended: not asked \"$question\""; return null }
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
    private val path = ArrayList<RouteStep>()   // what this runLaya did, as a path it could remember
    private val rows = ArrayList<JSONObject>()  // ...and as Teach-format rows for fine-tuning (research/14)
    private var goal = request
    private var typed: String? = null           // request text this run types, if any

    /** What Laya just did that finished the task, until it's kept or rejected; null if it came from memory or failed. */
    var learned: Route? = null
        private set

    /** The exact path replayed in this request, if any: forgotten if the owner says the result was wrong. */
    var replayed: Route? = null
        private set

    /** Whether this request's experience was kept (its outcome checked out, or the owner confirmed it). */
    var kept = false
        private set

    /** Keep this request's experience (D-048): its path for hints and replay, its screens as training rows. */
    fun keep(source: String) {
        val r = learned ?: return
        if (kept || memory != "on") return
        kept = true
        Routes.save(svc, Route(r.goal, r.pkg, r.key, r.steps, source, r.end))
        writeRows(source)
    }

    /** The owner said the result was wrong: its screens become negative rows, and a path that led here is forgotten. */
    fun reject() {
        replayed?.let { Routes.forget(svc, it) }
        if (kept) learned?.let { Routes.forget(svc, it) }
        writeRows("rejected")
    }

    override fun succeeded() = keep("verified")

    private fun writeRows(outcome: String) {
        if (rows.isEmpty()) return
        Teacher.dir(svc).mkdirs()
        File(Teacher.dir(svc), SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-$outcome.jsonl")
            .writeText(rows.joinToString("") { it.put("outcome", outcome).toString() + "\n" })
    }

    override fun runLaya(subgoal: String, maxSteps: Int, typeText: String?, pickText: (() -> String?)?): Outcome {
        val startPkg = foreground()
        path.clear(); rows.clear(); goal = subgoal; typed = typeText
        Routes.find(svc, startPkg, subgoal, typeText)?.takeIf { memory != "off" }?.let { route ->
            replayed = route
            replay(route, typeText)?.let { return it }  // DONE or STOPPED; null: the screen changed, Laya goes on from here
        }
        // a past success with a similar goal in this app (D-048): its steps are a hint for Laya, which still decides
        // (so a renamed or moved element still works), and its finished screen means this task is done
        val hint = Routes.similar(svc, startPkg, subgoal, apps.installed.firstOrNull { it.pkg == startPkg }?.label)
            ?.takeIf { memory != "off" }
        val lead = hint?.let { h -> "$subgoal (last time: ${h.steps.joinToString(", then ") { it.describe() }})" } ?: subgoal
        hint?.let { trace += "hint (${it.source}): \"${it.goal}\" -> ${it.steps.joinToString(" → ") { s -> s.describe() }}" }
        val blocked = HashSet<String>()  // targets skipped or refused, so Laya doesn't pick them again
        var misses = 0
        var skimmed = false
        var startWords: Set<String>? = null  // the first screen's fingerprint: the finished screen is told apart by what changed
        // what the goal is about, without the words it will type: "search for screenshots" is about the search box
        // (the skim scrolled Photos 4 pages for "screenshots", 2026-10-18); the shortlist still looks for those words
        val query = Routes.contentWords(typeText.orEmpty())
        val about = Routes.aboutWords(subgoal, apps.installed.firstOrNull { it.pkg == startPkg }?.label) - query
        val want = about + query + hint?.steps.orEmpty().flatMap { Routes.contentWords(it.label) }
        val toggle = wantOn(subgoal)
        var didType = false
        var tapped: Triple<String?, ScreenCandidate, Set<String>>? = null  // an unnamed element just tapped: app, it, words before
        for (step in 1..maxSteps) {
            if (stopped) return Outcome.STOPPED
            val t0 = SystemClock.elapsedRealtime()
            var scr = screen()
            if (Safety.isAuthPrompt(scr.pkg, scr.texts.map { it.text })) {
                say("Unlock with your fingerprint — I'll wait")
                val ok = waitUntil(90_000) { screen().let { !Safety.isAuthPrompt(it.pkg, it.texts.map { t -> t.text }) } }
                if (!ok) return Outcome.STOPPED
                continue
            }
            tapped?.let { (pkg, e, before) -> if (scr.pkg == pkg) Meanings.learn(svc, pkg, e, Meanings.gained(before, scr.candidates)) }
            tapped = null
            if (hint != null && (query.isEmpty() || didType) && looksFinished(scr, hint)) {
                trace += "step $step on ${scr.pkg}: DONE — looks like where \"${hint.goal}\" finished"
                return finish(scr, startPkg, subgoal, typeText, startWords)
            }
            if (startWords == null) startWords = Routes.screenWords(scr.candidates)
            // the selected tab or item already names what the goal is about: there (Clock reopened on Stopwatch;
            // its selected tab isn't clickable, so it isn't a candidate -- read it from the whole screen)
            // (a search isn't there before it has typed: the Play Store's Search tab ended "search for whatsapp", 2026-10-18)
            scr.state?.let(::selectedLabels)?.takeIf { query.isEmpty() || didType }
                ?.firstOrNull { about.isNotEmpty() && Routes.contentWords(it).containsAll(about) }?.let {
                trace += "step $step on ${scr.pkg}: DONE — “$it” is already selected"
                return finish(scr, startPkg, subgoal, typeText, startWords)
            }
            // not on a loading screen (GPay), nor for a search: its box is on screen, and looking down Photos for
            // one cost 16 s before Laya tapped a photo anyway (2026-10-18)
            if (!skimmed && scr.candidates.isNotEmpty() && query.isEmpty()) {
                skimmed = true
                // no need to look further down when an element the hint names is right here
                val hinted = hint?.steps?.any { st -> st.label.isNotEmpty() && scr.candidates.any { it.label == st.label } } == true
                if (!hinted) skim(about, scr)?.let { scr = it }
            }
            val readMs = SystemClock.elapsedRealtime() - t0
            // what policy would block (Pay/Send in a money app) never reaches Laya: picking it only wastes a pass
            // (GPay "check my last payment", 2026-10-17: three 15 s passes on "Pay anyone", all blocked)
            val cands = scr.candidates.filter { key(it) !in blocked && Safety.verdict("CLICK", it, scr.pkg).first != Safety.Verdict.BLOCK }
            say("Thinking… (step $step)")
            val t1 = SystemClock.elapsedRealtime()
            val shown = shortlist(cands, want, shortlistK)
            val s = laya.step(lead, trail, cands, shown)
            val took = " [read $readMs ms (settle ${svc.lastSettleMs}), laya ${SystemClock.elapsedRealtime() - t1} ms: " +
                "${cands.size} cands, ${s.tokens} tokens${if (shown.size < cands.size) ", target from ${shown.size}" else ""}]"
            if (s.op == "DONE") {
                trace += "step $step on ${scr.pkg}: DONE (op ${"%.2f".format(s.opConfidence)})" + took
                return finish(scr, startPkg, subgoal, typeText, startWords)
            }
            val target = s.target?.let { cands.getOrNull(it) }
            val payload: Any? = when (s.op) {
                "OPEN_APP" -> apps.named(subgoal)
                "INPUT_TEXT" -> typeText ?: pickText?.invoke()?.also { typed = it } ?: slots.quoted.firstOrNull()
                else -> null
            }
            val (verdict, why) = when {
                (s.op == "OPEN_APP" || s.op == "INPUT_TEXT") && payload == null ->
                    Safety.Verdict.BLOCK to "nothing to ${if (s.op == "OPEN_APP") "open" else "type"}"
                else -> Safety.verdict(s.op, target, scr.pkg)
            }
            // the label goes in as text, not into a format string: "49% used" threw (Settings > Storage, 2026-10-18)
            trace += "step $step on ${scr.pkg}: ${s.op} \"${target?.label.orEmpty()}\" (op ${"%.2f".format(s.opConfidence)}) -> $verdict $why" + took
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
            if (s.op == "INPUT_TEXT") didType = true
            if (s.op == "CLICK" && target != null && target.label.isEmpty()) tapped = Triple(scr.pkg, target, Routes.screenWords(scr.candidates))
            // a switch for "turn on/off X": its new state settles it -- a second tap undid the first (dark mode, 2026-10-18)
            if (s.op == "CLICK" && target != null && toggle != null) {
                Thread.sleep(300)
                val now = screen()
                now.candidates.firstOrNull { it.checkable && it.checked == toggle && it.bounds[1] < target.bounds[3] && it.bounds[3] > target.bounds[1] }?.let {
                    trace += "done: “${it.label}” is ${if (toggle) "on" else "off"}"
                    return finish(now, startPkg, subgoal, typeText, startWords)
                }
            }
            // the tap named everything the goal is about ("show the stopwatch" -> "Stopwatch"): reached. Laya isn't
            // asked again -- it went on and pressed Start on a real stopwatch, twice (2026-10-17). A task that types
            // isn't there until it has typed ("Refine search to …" ended a Play Store search early, 2026-10-18)
            if (s.op == "CLICK" && target != null && about.isNotEmpty() && (query.isEmpty() || didType) &&
                Routes.contentWords(target.label).containsAll(about)) {
                trace += "done: tapped “${target.label}”, what the goal is about"
                Thread.sleep(300)
                return finish(screen(), startPkg, subgoal, typeText, startWords)
            }
            if (trail.size >= 3 && trail.takeLast(3).toSet().size == 1) { trace += "stuck: same action 3 times"; return Outcome.STUCK }
            Thread.sleep(300)
        }
        trace += "out of steps ($maxSteps)"
        return Outcome.MAX_STEPS
    }

    /** "turn off dark mode" -> false, "enable bluetooth" -> true; null when the goal isn't switching something. */
    private fun wantOn(goal: String): Boolean? = Regex("\\b(?:turn|switch|toggle) (on|off)\\b|\\b(enable|disable)\\b", RegexOption.IGNORE_CASE)
        .find(goal)?.groupValues?.let { g -> g[1].ifEmpty { g[2] }.lowercase() in setOf("on", "enable") }

    /**
     * The task is done on [scr]: its DONE row, and what Laya did becomes [learned] (until kept or rejected). The
     * finished screen is remembered by what changed since [startWords]: in tab apps the tab bar is on every screen.
     */
    private fun finish(scr: Screen, startPkg: String?, subgoal: String, typeText: String?, startWords: Set<String>?): Outcome {
        rows += row("DONE", scr, null, null)
        if (path.isNotEmpty() && startPkg != null) {
            // keyed without the typed text only if this path typed it (see Routes.find)
            learned = Route(subgoal, startPkg, Routes.key(subgoal, typeText.takeIf { path.any { it.slot } }), path.toList(),
                "run", Routes.screenWords(scr.candidates) - startWords.orEmpty())
        }
        return Outcome.DONE
    }

    /**
     * [scr] shows most (70%, and at least 2) of the words [hint]'s finished screen gained: "I'm already there"
     * (D-048). The whole screen's words were too weak: Clock's Alarms tab looked 70% like its Stopwatch tab.
     */
    private fun looksFinished(scr: Screen, hint: Route): Boolean {
        val hit = (Routes.screenWords(scr.candidates) intersect hint.end).size
        return hint.end.size >= 2 && hit >= maxOf(2.0, 0.7 * hint.end.size)
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
        val before = row(op, scr, target, text(payload))  // open() writes "OPEN_APP X" to the history itself
        say(describe(op, target, payload))
        banner.hide()
        if (op == "OPEN_APP") open(payload as AppInfo) else svc.act(op, target, payload, scr.candidates)
        record(op, target, payload, scr, before)
    }

    /** The book-keeping for a step that was done: a training row, Laya's history, the path. */
    private fun record(op: String, target: ScreenCandidate?, payload: Any?, scr: Screen,
                       row: JSONObject = row(op, scr, target, text(payload))) {
        val text = text(payload)
        rows += row
        // open() already wrote "OPEN_APP X"; any other step is written even when repeated (the stuck check counts those)
        historyEntry(op, target?.toCandidate(), text).let { if (op != "OPEN_APP" || trail.lastOrNull() != it) trail += it }
        path += RouteStep.of(op, scr.pkg, target, text, slot = op == "INPUT_TEXT" && text != null && text == typed)
    }

    private fun text(payload: Any?) = (payload as? AppInfo)?.label ?: payload as? String

    /**
     * Skim the page before asking Laya (owner, 2026-10-17: "search for a UI where it needs to scroll down"): if no
     * element on screen matches what the goal is about ([want]: two words, or the one word it has), scroll down up to
     * [SKIM_PAGES] pages looking for one that does ("payment history" -> "Show transaction history"), only reading -- about 1 s a page, vs 10-15 s for a
     * Laya pass that would guess ("More"). Found: those scrolls become steps (history, path, rows) and Laya decides
     * on that page. Not found: back to the top, as if nothing happened (null).
     */
    private fun skim(want: Set<String>, first: Screen): Screen? {
        fun match(c: ScreenCandidate) = (Routes.contentWords(c.label) intersect want).size
        fun best(s: Screen) = s.candidates.maxOfOrNull(::match) ?: 0
        val enough = minOf(2, want.size)  // "stopwatch" alone: one word is the whole goal
        if (want.isEmpty() || best(first) >= enough || first.candidates.none { it.scrollable }) return null
        val pages = mutableListOf(first)
        while (pages.size <= SKIM_PAGES && !stopped) {
            say("Looking further down…")
            svc.act("SCROLL_DOWN", null, null, pages.last().candidates)
            Thread.sleep(400)
            val next = screen()
            if (next.candidates.map { it.label } == pages.last().candidates.map { it.label }) break  // the page ended
            pages += next
            if (best(next) >= enough) {
                trace += "skim: \"${next.candidates.maxBy(::match).label}\" ${pages.size - 1} page(s) down"
                pages.dropLast(1).forEach { record("SCROLL_DOWN", null, null, it) }
                return next
            }
        }
        repeat(pages.size - 1) { svc.act("SCROLL_UP", null, null, pages.last().candidates); Thread.sleep(400) }
        trace += "skim: nothing matching in ${pages.size - 1} page(s) below"
        return null
    }

    /** A Teach-format row (D-033) for this step, so confirmed runs become training data too. */
    private fun row(op: String, scr: Screen, target: ScreenCandidate?, payload: String?) = JSONObject()
        .put("goal", goal).put("history", JSONArray(trail)).put("op", op).put("package", scr.pkg)
        .put("time", System.currentTimeMillis()).put("state", scr.state).put("source", "run").apply {
            if (target != null) put("target", scr.candidates.indexOf(target)).put("target_label", target.label)
                .put("target_bounds", JSONArray(target.bounds.toList()))
            if (payload != null) put("payload", payload)
        }

    /** Ask the owner to approve one risky step (Run / Skip / Stop), outlining its target. */
    private fun confirm(step: String, why: String, target: ScreenCandidate?): String {
        if (unattended) { trace += "unattended: skipped \"$step\" ($why)"; return "skip" }
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
        private const val SKIM_PAGES = 4
        /** The target row's size on crowded screens (D-049, research/18 §9: no worse pick at 16, 0.63× the time); 0: all. */
        const val SHORTLIST = 16
        private const val TAG = "FridayAssistant"
        private const val GROUP = 16
    }
}
