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

/** A path that finished [goal], starting in app [pkg]; [source] is "teach" (the owner showed it) or "run" (confirmed). */
class Route(val goal: String, val pkg: String, val key: String, val steps: List<RouteStep>, val source: String)

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

    private fun route(j: JSONObject) = Route(j.getString("goal"), j.getString("pkg"), j.getString("key"),
        j.getJSONArray("steps").let { a -> List(a.length()) { RouteStep.from(a.getJSONObject(it)) } }, j.getString("source"))

    @Synchronized fun save(c: Context, r: Route) {
        val routes = all(c)
        routes.put("${r.pkg}|${r.key}", JSONObject().put("goal", r.goal).put("pkg", r.pkg).put("key", r.key)
            .put("source", r.source).put("time", System.currentTimeMillis())
            .put("steps", JSONArray().also { a -> r.steps.forEach { a.put(it.json()) } }))
        file(c).writeText(routes.toString())
    }

    /** "Delete all learned data": every remembered path. Returns how many were removed. */
    @Synchronized fun wipe(c: Context): Int = all(c).length().also { file(c).delete() }
}

/** Asserts task keys and element matching. */
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
    return "ok"
}
