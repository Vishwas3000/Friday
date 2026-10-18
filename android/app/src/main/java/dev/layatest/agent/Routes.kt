package dev.layatest.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One remembered step: the op and the element it acted on, found again later by package, resource id,
 * class and label. [slot]: an INPUT_TEXT whose text came from the request, so it's re-filled each time.
 */
class RouteStep(val op: String, val pkg: String?, val rid: String, val label: String, val cls: String,
                val payload: String?, val slot: Boolean) {

    /** This step's element on [scr]: the same label among same-id elements, else the only element with that id. */
    fun find(scr: Screen): ScreenCandidate? {
        if (pkg != null && scr.pkg != pkg) return null
        val same = scr.candidates.filter { it.className == cls && (rid.isEmpty() || it.rid == rid) }
        // a lone same-id element may have changed its text (a counter, a time); without an id, the label must match
        return same.singleOrNull { it.label == label } ?: same.singleOrNull()?.takeIf { rid.isNotEmpty() }
    }

    val targeted get() = label.isNotEmpty() || rid.isNotEmpty()

    /** For the owner, on the answer card: "tap “Timers”". */
    fun describe() = when (op) {
        "CLICK" -> "tap “$label”"
        "LONG_PRESS" -> "long-press “$label”"
        "INPUT_TEXT" -> "type “${payload ?: "…"}”"
        "OPEN_APP" -> "open $payload"
        else -> op.lowercase().replace('_', ' ')
    }

    fun json(): JSONObject = JSONObject().put("op", op).put("pkg", pkg ?: JSONObject.NULL).put("rid", rid)
        .put("label", label).put("cls", cls).put("payload", payload ?: JSONObject.NULL).put("slot", slot)

    companion object {
        fun of(op: String, pkg: String?, c: ScreenCandidate?, payload: String?, slot: Boolean) =
            RouteStep(op, pkg, c?.rid.orEmpty(), c?.label.orEmpty(), c?.className.orEmpty(), payload, slot)

        fun from(j: JSONObject) = RouteStep(j.getString("op"), j.optString("pkg").ifEmpty { null }, j.getString("rid"),
            j.getString("label"), j.getString("cls"), if (j.isNull("payload")) null else j.getString("payload"), j.getBoolean("slot"))
    }
}

/**
 * An experience (D-048): a path that finished [goal], starting in app [pkg]. [source]: "teach" (the owner showed
 * it), "confirmed" (the owner tapped ✓), "verified" (the outcome checked out). [end]: the finished screen's words.
 */
class Route(val goal: String, val pkg: String, val key: String, val steps: List<RouteStep>, val source: String,
            val end: Set<String> = emptySet())

/**
 * UI memory (D-040): apps rarely change their screens, so a path that finished a goal once is replayed
 * without Laya -- each step's element is found on the live screen and acted on directly (~1 s a step,
 * vs 3-39 s for a Laya pass, measured 2026-10-17). Paths come only from Teach demonstrations and runs the
 * owner confirmed with "Remember". Stored in filesDir/routes.json; [wipe] deletes them all.
 */
object Routes {
    // request words that don't change what the task is; "on"/"off" stay ("turn on wifi" != "turn off wifi")
    private val STOP = setOf("a", "an", "the", "app", "tab", "please", "my", "me", "to", "and", "then", "for",
        "in", "at", "of", "with", "from", "can", "could", "would", "you", "i", "want", "just", "now", "hey", "friday")

    private fun words(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }  // "timers" == "timer"

    /** The words that say what a task or an element is about: lower-case, plurals folded, filler dropped. */
    fun contentWords(s: String) = words(s).filter { it !in STOP }.toSet()

    // how to get somewhere, not what the goal is about ("show the stopwatch" is about the stopwatch)
    private val NAV = setOf("open", "go", "show", "display", "check", "see", "view", "find", "look", "get", "take",
        "bring", "switch", "visit", "launch", "navigate", "tap", "click", "page", "screen", "section")

    /** What a goal is about: its content words without the app's name or navigation verbs. */
    fun aboutWords(goal: String, appLabel: String?) = contentWords(goal) - contentWords(appLabel.orEmpty()) - NAV

    /** A screen's fingerprint: its elements' words, minus anything with a digit (amounts, dates, times change). */
    fun screenWords(cands: List<ScreenCandidate>) = cands.flatMap { contentWords(it.label) }.filter { w -> w.none(Char::isDigit) }.toSet()

    /** What identifies a task: its content words, order-free, minus the words of the text it types (that varies). */
    fun key(goal: String, text: String?): String {
        val typed = words(text.orEmpty()).toSet()
        return words(goal).filter { it !in STOP && it !in typed }.toSortedSet().joinToString(" ")
    }

    private fun file(c: Context) = File(c.filesDir, "routes.json")

    @Synchronized fun all(c: Context): JSONObject =
        runCatching { JSONObject(file(c).readText()) }.getOrElse { JSONObject() }

    /**
     * The remembered path for [goal] starting in [pkg]: one with fixed steps, keyed on all the goal's words;
     * else one that types request text, keyed without [text]'s words. Skills guess [text] even for tasks
     * that type nothing, so it never shortens a fixed path's key (that let "open clock and …" match anything).
     */
    fun find(c: Context, pkg: String?, goal: String, text: String?): Route? {
        val routes = all(c)
        routes.optJSONObject("$pkg|${key(goal, null)}")?.let(::route)?.takeIf { r -> r.steps.none { it.slot } }?.let { return it }
        return text?.let { routes.optJSONObject("$pkg|${key(goal, it)}") }?.let(::route)?.takeIf { r -> r.steps.any { it.slot } }
    }

    /**
     * The experience in app [pkg] whose goal is most like [goal] -- at least half of what they're about shared
     * (no app name, no navigation verbs) -- to hint Laya with (D-048), unlike [find]'s exact replay. Ties go to one
     * that knows its finished screen, then the newest: a tie once picked an old taught path without one, and Laya
     * pressed Start on the stopwatch it had reached.
     */
    fun similar(c: Context, pkg: String?, goal: String, appLabel: String?): Route? {
        val want = aboutWords(goal, appLabel)
        val routes = all(c)
        return routes.keys().asSequence().map { routes.getJSONObject(it) }.filter { it.getString("pkg") == pkg }
            .map { j -> Triple(route(j), jaccard(want, aboutWords(j.getString("goal"), appLabel)), j.optLong("time")) }
            .filter { it.second >= 0.5 }
            .maxWithOrNull(compareBy({ it.second }, { it.first.end.isNotEmpty() }, { it.third }))?.first
    }

    fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    private fun route(j: JSONObject) = Route(j.getString("goal"), j.getString("pkg"), j.getString("key"),
        j.getJSONArray("steps").let { a -> List(a.length()) { RouteStep.from(a.getJSONObject(it)) } }, j.getString("source"),
        j.optJSONArray("end")?.let { a -> List(a.length()) { a.getString(it) }.toSet() }.orEmpty())

    /** The owner said a run that used [r] went wrong: forget it. */
    @Synchronized fun forget(c: Context, r: Route) {
        val routes = all(c)
        routes.remove("${r.pkg}|${r.key}")
        file(c).writeText(routes.toString())
    }

    @Synchronized fun save(c: Context, r: Route) {
        val routes = all(c)
        routes.put("${r.pkg}|${r.key}", JSONObject().put("goal", r.goal).put("pkg", r.pkg).put("key", r.key)
            .put("source", r.source).put("time", System.currentTimeMillis()).put("end", JSONArray(r.end.toList()))
            .put("steps", JSONArray().also { a -> r.steps.forEach { a.put(it.json()) } }))
        file(c).writeText(routes.toString())
    }

    /** "Delete all learned data": every remembered path. Returns how many were removed. */
    @Synchronized fun wipe(c: Context): Int = all(c).length().also { file(c).delete() }
}

/** Asserts task keys and element matching. */
/**
 * What an unnamed element does, learned from where it led (icon labels, step 2; research/24 §4): after Friday taps an
 * element with no label, the first words the next screen gained become its label from then on -- an icon that opened
 * "Settings · Help · Profile" reads "settings help profile". Per app, by resource id, else by class and place (toolbar
 * icons don't move). filesDir/meanings.json; "Delete all learned data" removes it.
 */
object Meanings {
    private fun file(c: Context) = File(c.filesDir, "meanings.json")
    @Synchronized fun all(c: Context): JSONObject = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())

    fun key(pkg: String?, e: ScreenCandidate) =
        "$pkg|" + if (e.rid.isNotEmpty()) "id:${e.rid}" else "${e.className}@${e.bounds.joinToString(",")}"

    /** The first [max] words [after] shows that [before] didn't, top of the screen first (where titles are). */
    fun gained(before: Set<String>, after: List<ScreenCandidate>, max: Int = 4) =
        after.flatMap { Routes.contentWords(it.label) }.filter { w -> w !in before && w.none(Char::isDigit) }.distinct().take(max)

    @Synchronized fun learn(c: Context, pkg: String?, e: ScreenCandidate, words: List<String>) {
        if (words.isEmpty()) return
        file(c).writeText(all(c).put(key(pkg, e), words.joinToString(" ")).toString())
    }

    @Synchronized fun wipe(c: Context): Int = all(c).length().also { file(c).delete() }
}

fun routesSelfCheck(): String {
    check(Routes.key("Open the Clock app and go to the Timers tab", null) == Routes.key("open clock and go to timer", null))
    check(Routes.key("turn on wifi", null) != Routes.key("turn off wifi", null))
    check(Routes.key("search for weather in bangalore in chrome", "weather in bangalore") ==
        Routes.key("search for pizza in chrome", "pizza"))
    fun c(label: String, rid: String) = ScreenCandidate(label, "Button", true, false, false, false, false, false, false,
        true, intArrayOf(0, 0, 10, 10), rid)
    val scr = Screen("p", listOf(c("Start", "fab"), c("Reset", "reset"), c("Lap", ""), c("Lap", "")), emptyList())
    check(RouteStep("CLICK", "p", "reset", "Reset", "Button", null, false).find(scr)?.label == "Reset")
    check(RouteStep("CLICK", "p", "fab", "Stop", "Button", null, false).find(scr)?.label == "Start")  // same id, new text
    check(RouteStep("CLICK", "p", "", "Lap", "Button", null, false).find(scr) == null)              // two look-alikes
    check(RouteStep("CLICK", "q", "reset", "Reset", "Button", null, false).find(scr) == null)       // another app
    // the skim's match (Assistant.skim): two shared content words, the app's own name left out
    val want = Routes.contentWords("show me the payment history in gpay") - Routes.contentWords("GPay")
    check((Routes.contentWords("Show transaction history") intersect want).size == 2)
    check((Routes.contentWords("Pay anyone") intersect want).isEmpty())
    // similar goals (D-048): "check my payment history" ~ "show me the payment history", app name left out
    check(Routes.jaccard(Routes.contentWords("check my payment history in gpay") - Routes.contentWords("GPay"), want) >= 0.5)
    check(Routes.jaccard(Routes.contentWords("set an alarm") , want) == 0.0)
    check(Routes.screenWords(listOf(c("₹250 debited", ""), c("Transaction history", ""))) == setOf("debited", "transaction", "history"))
    check(Routes.aboutWords("open clock and show the stopwatch", "Clock") == setOf("stopwatch"))
    check(Routes.aboutWords("open the clock app and go to the timers tab", "Clock") == setOf("timer"))
    check(Routes.aboutWords("show me the payment history in gpay", "GPay") == setOf("payment", "history"))
    // an unnamed icon is named by what its tap revealed, titles first, nothing the screen already had
    check(Meanings.gained(setOf("home"), listOf(c("Settings", ""), c("Help & feedback", ""), c("Home", ""), c("2 new", ""))) ==
        listOf("setting", "help", "feedback", "new"))  // plurals folded, as everywhere in Routes
    check(Meanings.key("p", c("", "menu")) == "p|id:menu" && Meanings.key("p", c("", "")) == "p|Button@0,0,10,10")
    return "ok"
}
