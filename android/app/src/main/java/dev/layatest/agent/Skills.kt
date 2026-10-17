package dev.layatest.agent

import android.content.Context
import android.content.Intent

/** How a UI run ended. */
enum class Outcome { DONE, STOPPED, STUCK, MAX_STEPS }

/** The current screen: actionable candidates (what Laya scores) and every visible text (what extractors read). */
class Screen(val pkg: String?, val candidates: List<ScreenCandidate>, val texts: List<ScreenText>)

/** A skill turns one kind of request into actions and an answer, using only [SkillContext]. */
interface Skill {
    val id: String
    /** Do the request; return what to tell the owner (an answer, or what was done), or null for "Done". */
    fun run(ctx: SkillContext): String?
}

/**
 * Everything a skill may do. The safety policy (Safety.verdict, D-025/D-026) and the auth wait are
 * applied underneath, in Assistant, so a skill can't act around them.
 */
interface SkillContext {
    val android: Context
    val request: String
    val slots: Slots
    val apps: AppResolver
    val stopped: Boolean

    /** App for [skill]: one named in the request, the only candidate, the owner's remembered pick, or asked once. */
    fun appFor(skill: String): AppInfo?
    fun open(app: AppInfo): Boolean
    /** startActivity with NEW_TASK; false if nothing handles the intent. */
    fun launch(intent: Intent): Boolean
    fun foreground(): String?
    /** Poll [cond] every 300 ms until true, [timeoutMs] passes or the owner taps Stop. */
    fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean
    fun screen(): Screen
    /** Let Laya drive the UI toward [subgoal]; [typeText] is what INPUT_TEXT types. */
    fun runLaya(subgoal: String, maxSteps: Int = 12, typeText: String? = null): Outcome
    /** Laya picks one of [options] for this request (a tournament above 16); null if empty. */
    fun choose(instructions: String, options: List<String>): Pick?
    fun tap(target: ScreenCandidate): Boolean
    fun scroll(direction: String): Boolean
    fun back(): Boolean
    /** Progress on the status banner. */
    fun say(text: String)
    /** The owner picks one of [options]; null if they cancel. */
    fun ask(question: String, options: List<String>): Int?
}

/** The skill catalog Laya routes over (research/09: 81% on unseen phrasings). Keep it at 16 or fewer. */
val SKILLS: LinkedHashMap<String, String> = linkedMapOf(
    "open_app" to "open or launch an app the user names",
    "play_music" to "play songs, an artist, an album, a genre or a playlist",
    "play_video" to "watch or play a video, show or movie",
    "spend_query" to "find out how much money was spent or paid, or see past payments",
    "balance_query" to "check a bank account or wallet balance",
    "bill_query" to "check a credit card bill or amount due",
    "device_setting" to "turn a phone setting on or off or change it (Wi-Fi, Bluetooth, dark mode, brightness)",
    "device_status" to "check battery, storage or another phone status",
    "alarm_timer" to "set an alarm, timer or reminder",
    "call" to "make a phone call to a person or number",
    "message" to "send a message, text or email to someone",
    "navigate" to "get directions or navigate to a place",
    "web_search" to "search the web or look something up",
    "ride" to "book a cab, auto or bike ride",
    "shopping" to "buy, order or track something from a shop",
    "photos" to "show or find photos",
)

val SKILL_TITLES = mapOf(
    "open_app" to "Open an app", "play_music" to "Play music", "play_video" to "Play a video",
    "spend_query" to "Check spending", "balance_query" to "Check a balance", "bill_query" to "Check a card bill",
    "device_setting" to "Change a phone setting", "device_status" to "Check phone status",
    "alarm_timer" to "Set an alarm or timer", "call" to "Make a call", "message" to "Send a message",
    "navigate" to "Get directions", "web_search" to "Search the web", "ride" to "Book a ride",
    "shopping" to "Shop", "photos" to "Show photos",
)

/** The rule half of routing: precise on phrasings we know (first match wins). Laya covers the rest. */
private val RULES: List<Pair<String, Regex>> = listOf(
    "open_app" to "^open\\b",
    "spend_query" to "\\b(spen[dt]|expens\\w*|transactions?|paid|payments?)\\b|how much did i pay",
    "balance_query" to "\\bbalance\\b",
    "bill_query" to "\\b(bill|dues?)\\b",
    "alarm_timer" to "\\b(alarm|timer|remind\\w*|wake me)\\b",
    "device_status" to "\\b(battery|storage|space left)\\b",
    // media controls before settings: "turn off the music" is a pause, not a phone setting
    "play_music" to "\\b(pause|resume|skip)\\b|\\b(next|previous) (song|track)\\b|\\b(stop|turn off) the (music|song)\\b",
    "device_setting" to "\\b(turn (on|off)|enable|disable|brightness|dark mode|wi-?fi|bluetooth|flash ?light|torch)\\b",
    // rides before calls and shopping: "call me a cab", "order an uber"
    "ride" to "\\b(ride|cab|auto|taxi|uber|ola|rapido|namma yatri)\\b",
    "call" to "^(call|dial|phone)\\b",
    "web_search" to "^(search|look up|google)\\b",  // before message: "search for text editors"
    "message" to "\\b(message|text|whatsapp|e-?mail|mail)\\b",
    "navigate" to "\\b(navigate|directions?|get to|route to)\\b",
    "shopping" to "\\b(order|buy|deliver\\w*)\\b",
    "photos" to "\\b(photos?|pictures?|pics)\\b",
    "play_video" to "\\b(watch|videos?|movie|episode|season)\\b",
    "play_music" to "\\b(play|listen|put on)\\b",
    "web_search" to "\\b(search|look up|google|weather)\\b",
).map { (s, p) -> s to Regex(p, RegexOption.IGNORE_CASE) }

const val CONFIDENT = 0.7  // research/09: routing at >= 0.7 was 93% right; below it, ask the owner
const val SLOT_Q = "Which words in the request name the song, artist, show, person, place or item?"

fun ruleSkill(request: String): String? {
    val skill = RULES.firstOrNull { it.second.containsMatchIn(request) }?.first
    // "open X and <task>": the task picks the skill, which then uses the app named ("open youtube and play
    // despacito" -> play_video in YouTube: straight to its results); a task no rule covers ("and go to the
    // timers tab") stays open_app, for Laya inside X. Was: every "open X and ..." went to Laya (2026-10-17).
    if (skill == "open_app") OPEN_AND.find(request)?.let { m -> ruleSkill(m.groupValues[1])?.takeIf { it != "open_app" }?.let { return it } }
    return skill
}

/** The task in "open X and <task>", else the whole request: what slots and media queries are read from. */
fun taskPart(request: String) = OPEN_AND.find(request)?.groupValues?.get(1) ?: request

private val OPEN_AND = Regex("^\\s*(?:please\\s+)?(?:open|launch|start)\\s+.+?\\s+(?:and|then|to)\\s+(.+)$", RegexOption.IGNORE_CASE)

/** Asserts the "open X and <task>" routing. */
fun routingSelfCheck(): String {
    check(ruleSkill("open youtube and play despacito") == "play_music")  // PlayMusic hands a video app to PlayVideo
    check(ruleSkill("open the clock app and go to the timers tab") == "open_app")
    check(ruleSkill("open the clock app and set an alarm for 7") == "alarm_timer")
    check(ruleSkill("open settings and turn on wifi") == "device_setting")
    check(ruleSkill("open spotify") == "open_app")
    return "ok"
}

/** What's left to do after "open <app>" ("and go to the stopwatch tab"), or null if opening is the whole request. */
fun taskAfterOpening(request: String, appLabel: String): String? {
    val rest = request.replace(Regex("^\\s*(please\\s+)?(open|launch|start)\\s+(the\\s+)?", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b${Regex.escape(appLabel)}\\b(\\s+app)?", RegexOption.IGNORE_CASE), " ")
        .trim(' ', ',', '.')
    return rest.takeIf { r -> Regex("[\\w']+").findAll(r).any { it.value.lowercase() !in Slots.STOP } }
}

/** Rules first, then Laya, then the owner if Laya is unsure. Null = "something else" (Laya works from the screen). */
fun route(request: String, laya: () -> LayaEngine, ask: (String, List<String>) -> Int?): String? {
    ruleSkill(request)?.let { return it }
    val p = laya().choose(request, "What does the user want Friday to do?", SKILLS.toList())
    if (p.confidence >= CONFIDENT) return p.key
    val top = p.probabilities.entries.sortedByDescending { it.value }.take(3).map { it.key }
    val i = ask("Not sure — do you want me to…", top.map { SKILL_TITLES.getValue(it) } + "Something else") ?: return null
    return top.getOrNull(i)
}

/**
 * Any skill without its own implementation (and "something else"): open the skill's app if it has
 * one, pick the slot to type, and let Laya work toward the request on screen.
 */
class GenericSkill(override val id: String) : Skill {
    override fun run(ctx: SkillContext): String? {
        val app = if (id == "open_app") ctx.apps.named(ctx.request) else ctx.appFor(id)
        if (app != null) {
            ctx.say("Opening ${app.label}…")
            ctx.open(app)
            ctx.waitUntil(5000) { ctx.foreground() == app.pkg }
        }
        if (id == "open_app") {
            if (app == null) return "I couldn't find that app"
            // "open X" alone is done; "open X and <task>" goes on inside X -- stopping here was why Friday
            // "only opened apps" (2026-10-17). Laya knows X is open: the trail says OPEN_APP X.
            if (taskAfterOpening(ctx.request, app.label) == null) return "Opened ${app.label}"
        }
        val text = ctx.slots.quoted.firstOrNull()
            ?: ctx.slots.after("search for", "search", "look for", "find", "type", "play", "watch", "listen to")
            ?: ctx.slots.spans.takeIf { it.isNotEmpty() }?.let { sp -> ctx.choose(SLOT_Q, sp)?.let { sp[it.index] } }
        return when (ctx.runLaya(ctx.request, typeText = text)) {
            Outcome.DONE -> "Done"
            Outcome.STOPPED -> "Stopped"
            Outcome.STUCK -> "I got stuck — the screen is where I stopped"
            Outcome.MAX_STEPS -> "I ran out of steps before finishing"
        }
    }
}
