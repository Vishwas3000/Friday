package dev.layatest.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** How a UI run ended. */
enum class Outcome { DONE, STOPPED, STUCK, MAX_STEPS }

/** The current screen: actionable candidates (what Laya scores) and every visible text (what extractors read). */
class Screen(val pkg: String?, val candidates: List<ScreenCandidate>, val texts: List<ScreenText>,
             val state: org.json.JSONObject? = null)  // the raw forest, for training rows

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
    /** The request read once (D-051); skills move from re-parsing to this one by one (step b). */
    val card: TaskCard

    /** App for [skill]: one named in the request, the only candidate, the owner's remembered pick, or asked once. */
    fun appFor(skill: String): AppInfo?
    /** [fresh]: start the app from its home screen (clear its task) rather than resuming where it was left. */
    fun open(app: AppInfo, fresh: Boolean = false): Boolean
    /** startActivity with NEW_TASK; false if nothing handles the intent. */
    fun launch(intent: Intent): Boolean
    /** Authorize immediately before a native mutation; the coordinator records its dispatch receipt. */
    fun authorizeMutation(): Boolean = !stopped
    fun foreground(): String?
    /** Poll [cond] every 300 ms until true, [timeoutMs] passes or the owner taps Stop. */
    fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean
    fun screen(): Screen
    /** Let Laya drive the UI toward [subgoal]; [typeText] is what INPUT_TEXT types. */
    /** [pickText]: finds the text to type only when a step types (choosing it from the request costs a Laya pass). */
    fun runLaya(subgoal: String, maxSteps: Int = 12, typeText: String? = null, pickText: (() -> String?)? = null): Outcome

    /** The skill checked the outcome (playing, transactions on screen): keep what this request learned (D-048). */
    fun succeeded()
    /** Laya picks one of [options] for this request (a tournament above 16); null if empty. */
    fun choose(instructions: String, options: List<String>): Pick?
    fun tap(target: ScreenCandidate): Boolean
    fun scroll(direction: String): Boolean
    fun back(): Boolean
    /** Progress on the status banner. */
    fun say(text: String)
    /** A focused web query; app-control instructions must not become Google keywords. */
    fun webQuery(): String?
    /** The owner picks one of [options]; null if they cancel. */
    fun ask(question: String, options: List<String>): Int?
    /** One draft object, then owner preview and the same app handoff for writing and messaging requests. */
    fun draft(kind: String, instruction: String, text: String, verbatim: Boolean = false, subject: String = ""): String
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
    "navigate" to "Get directions", "web_search" to "Search the web", "app_search" to "Search an app", "ride" to "Book a ride",
    "shopping" to "Shop", "photos" to "Show photos", "calendar_event" to "Block the calendar",
)

/** The rule half of routing: precise on phrasings we know (first match wins). Laya covers the rest. */
private val RULES: List<Pair<String, Regex>> = listOf(
    "open_app" to "^open\\b",
    "spend_query" to "\\b(spen[dt]|expens\\w*|transactions?|paid|payments?)\\b|how much did i pay",
    "bill_query" to "\\b(bills?|dues?|outstanding)\\b|\\b(?:credit ?cards?|cards?)\\b.{0,32}\\b(?:owe|owing|payable)\\b|\\b(?:owe|owing|payable)\\b.{0,32}\\b(?:credit ?cards?|cards?)\\b",
    "balance_query" to "\\bbalance\\b",
    // a question about the alarm is a status, not a new one: "when's my next alarm" asked what time to set it
    "device_status" to "\\b(next|upcoming) alarm\\b|\\bwhen('?s| is) my (next )?alarm\\b",
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
    "shopping" to "\\b(orders?|buy|deliver\\w*)\\b",
    "photos" to "\\b(photos?|pictures?|pics)\\b",
    "play_video" to "\\b(watch|videos?|movie|episode|season)\\b",
    "play_music" to "\\b(play|listen|put on)\\b",
    "web_search" to "\\b(search|look up|google|weather)\\b",
).map { (s, p) -> s to Regex(p, RegexOption.IGNORE_CASE) }

const val CONFIDENT = 0.7  // research/09: routing at >= 0.7 was 93% right; below it, ask the owner
const val SLOT_Q = "Which words in the request name the song, artist, show, person, place or item?"

fun ruleSkill(request: String): String? {
    // A message's contents can mention bills, payments or alarms. Its command still selects messaging.
    if (Regex("^(?:please\\s+)?(?:send\\s+(?:an?\\s+)?)?(?:message|text|whatsapp|e-?mail|mail)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(taskPart(request).trim())) return "message"
    if (CalendarRequest.matches(request)) return "calendar_event"
    val skill = RULES.firstOrNull { it.second.containsMatchIn(request) }?.first
    // "open X and <task>": the task picks the skill, which then uses the app named ("open youtube and play
    // despacito" -> play_video in YouTube: straight to its results); a task no rule covers ("and go to the
    // timers tab") stays open_app, for Laya inside X. Was: every "open X and ..." went to Laya (2026-10-17).
    // A search stays inside the app opened: "open youtube and search for lofi" had Chrome google the whole sentence.
    // A status question too: "open settings and show the battery page" answered "61%" without opening Settings.
    if (skill == "open_app") OPEN_AND.find(request)?.let { m ->
        ruleSkill(m.groupValues[1])?.takeIf { it !in setOf("open_app", "web_search", "device_status") }?.let { return it }
    }
    return skill
}

/**
 * Screens an app opens straight to, by what the request asks for there. The Play Store's account menu, the way to
 * its updates, isn't exposed to accessibility at all (2026-10-18), so neither Laya nor Teach could get there.
 */
private val DEEP_LINKS: List<Triple<String, Regex, String>> = listOf(
    Triple("com.android.vending", Regex("\\b(updates?|update (my|all) apps|manage (my )?apps|my apps)\\b", RegexOption.IGNORE_CASE),
        "com.google.android.finsky.VIEW_MY_DOWNLOADS"),
)

fun deepLink(pkg: String, request: String): Intent? =
    DEEP_LINKS.firstOrNull { (p, re, _) -> p == pkg && re.containsMatchIn(request) }?.let { Intent(it.third).setPackage(pkg) }

private val SEARCH_TASK = Regex("^(?:search|look (?:for|up)|find)\\b", RegexOption.IGNORE_CASE)

/** The app's own search for [q]: a store's market: or a map's geo: query, else Android's search request (39 apps on the
 *  owner's phone: YouTube, Meesho, Spotify, Gmail…; the Play Store's opened without results). launch() skips what the
 *  app doesn't handle. */
fun searchIntents(pkg: String, q: String) = listOf(
    Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q))).setPackage(pkg),
    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))).setPackage(pkg),
    Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q),
)

/** Money questions: when Laya reaches the screen, Friday reads the amounts out instead of saying "Done". */
internal val READ_SKILLS = setOf("spend_query", "balance_query", "bill_query")
/** "last 10 transactions", "5 payments": how many to read. */
private val COUNT = Regex("\\b(?:last|latest|recent|past|top)\\s+(\\d{1,2})\\b|\\b(\\d{1,2})\\s+(?:transactions?|payments?)\\b",
    RegexOption.IGNORE_CASE)

/** "last 10 transactions" -> 10 (1..20); null when no count is asked for. */
internal fun countAsked(request: String) =
    COUNT.find(request)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt()?.coerceIn(1, 20)

/** Up to the count the request asks for (else 3), scrolling down up to 3 pages when the screen shows fewer. */
private fun readAll(ctx: SkillContext, skill: String): String {
    if (skill == "bill_query") {
        val expectedPackage = ctx.card.app ?: ctx.foreground()
        if (expectedPackage == null || !ctx.waitUntil(5000) { ctx.screen().let { it.pkg == expectedPackage && it.texts.any { text -> text.text.isNotBlank() } } })
            return unreadableMoneyScreen(ctx.apps.installed.firstOrNull { it.pkg == expectedPackage }?.label)
        // Bill labels and amounts must come from one screen: concatenating pages can pair different cards or offers.
        return readBillAmounts(ctx.screen().texts, totalRequested = totalBillAsked(ctx.request))
            ?: "I couldn't read a labeled bill amount on this screen. I haven't calculated a total."
    }
    val dated = skill == "spend_query"
    return readList(ctx, countAsked(ctx.request) ?: 3) { t, n -> readAmounts(t, n, dated) }
        ?.let { ctx.succeeded(); "Here's what I see:\n$it" }
        ?: if (dated) "I didn't find any dated payments on this screen" else "I couldn't read a balance or amount on this screen."
}

/** What a finished read task reads out (money, orders); null for the rest. A follow-up that finishes one reads too (D-061). */
internal fun readFor(ctx: SkillContext, card: TaskCard): String? = when {
    card.skill in READ_SKILLS -> readAll(ctx, card.skill!!)
    card.skill == "shopping" && card.mode == "read" -> readList(ctx, card.count ?: 3, ::readOrders)?.let { "Your latest orders:\n$it" }
    else -> null
}

/** Up to [n] items as [read] finds them, once the list has loaded, scrolling down up to 3 pages when fewer show. */
internal fun readList(ctx: SkillContext, n: Int, read: (List<ScreenText>, Int) -> String?): String? {
    // the list loads after the tap: Amazon's orders weren't there yet when read at once (2026-10-19)
    ctx.waitUntil(5000) { read(ctx.screen().texts, 1) != null }
    var texts = ctx.screen().texts
    var pages = 0
    while (pages++ < 3 && (read(texts, n)?.lines()?.size ?: 0) < n && ctx.scroll("down")) texts = texts + ctx.screen().texts
    return read(texts, n)
}
private val AMOUNT = Regex("(₹|rs\\.?|inr)\\s?[\\d,]+(\\.\\d{1,2})?", RegexOption.IGNORE_CASE)

private val DATE = Regex("\\b(\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*|" +
    "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}|today|yesterday)\\b", RegexOption.IGNORE_CASE)

/**
 * The first [max] transactions on screen: each amount with the text before (payee, account) and after (date).
 * Amounts with a date beside them come first -- offers ("Up to ₹40 lakh") carry none; GPay also exposes each
 * row twice, so a text repeated at the same place counts once (GPay history, 2026-10-17).
 */
fun readAmounts(texts: List<ScreenText>, max: Int = 3, onlyDated: Boolean = false): String? {
    val lines = texts.distinctBy { it.text.trim() to it.bounds.toList() }.map { it.text.trim() }.filter { it.isNotEmpty() }
    fun around(i: Int) = listOfNotNull(lines.getOrNull(i - 1), lines[i], lines.getOrNull(i + 1)).distinct()
    val hits = lines.indices.filter { AMOUNT.containsMatchIn(lines[it]) }
    val dated = hits.filter { i -> around(i).any { DATE.containsMatchIn(it) } }
    return (if (onlyDated) dated else dated.ifEmpty { hits }).map { around(it).joinToString(" · ") }.distinct().take(max)
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** The task in "open X and <task>", else the whole request: what slots and media queries are read from. */
fun taskPart(request: String) = OPEN_AND.find(request)?.groupValues?.get(1) ?: request

private val OPEN_AND = Regex("^\\s*(?:please\\s+)?(?:open|launch|start)\\s+.+?\\s+(?:and|then|to)\\s+(.+)$", RegexOption.IGNORE_CASE)

/** Asserts the "open X and <task>" routing. */
fun routingSelfCheck(): String {
    check(ruleSkill("message Rahul on WhatsApp saying I paid the bill") == "message")
    check(ruleSkill("email alex@example.test saying set an alarm tomorrow") == "message")
    check(ruleSkill("open WhatsApp and message Rahul saying I paid the bill") == "message")
    check(ruleSkill("open youtube and play despacito") == "play_music")  // PlayMusic hands a video app to PlayVideo
    check(ruleSkill("open the clock app and go to the timers tab") == "open_app")
    check(ruleSkill("open the clock app and set an alarm for 7") == "alarm_timer")
    check(ruleSkill("open settings and turn on wifi") == "device_setting")
    check(ruleSkill("open spotify") == "open_app")
    check(ruleSkill("open youtube and search for lofi music") == "open_app" && ruleSkill("search the web for weather") == "web_search")
    check(ruleSkill("open settings and show the battery page") == "open_app" && ruleSkill("how much battery is left") == "device_status")
    check(ruleSkill("when's my next alarm") == "device_status" && ruleSkill("set an alarm for 6") == "alarm_timer")
    check(ruleSkill("show mem last 5 orders in amazzon") == "shopping" && isTracking("Show my last order that I did on Amazon") &&
        isTracking("show mem last 5 orders in amazzon") && !isTracking("order some milk from swiggy"))
    check(AppResolver.editDistance("amazzon", "amazon") == 1 && AppResolver.editDistance("some", "home") == 1)
    check(readOrders(listOf("Milky Mist Paneer, 200 g, Order placed 15 October", "Buy again", "Lamp, Delivered 9 October")
        .map { ScreenText(it, intArrayOf(0, 0, 1, 1)) }, 5) == "Milky Mist Paneer, 200 g, Order placed 15 October\nLamp, Delivered 9 October")
    check(bareTimer("set a timer for 5", 5 to 0) == 300 && bareTimer("set a timer for 1:30", 1 to 30) == 90 &&
        bareTimer("set an alarm for 5", 5 to 0) == null && bareTimer("set a timer for 5 pm", 17 to 0) == null)
    check(deepLink("com.android.vending", "open the play store and show app updates") != null &&
        deepLink("com.android.vending", "open the play store and search for whatsapp") == null)
    check(SEARCH_TASK.containsMatchIn("search for coffee") && !SEARCH_TASK.containsMatchIn("show my orders") &&
        searchIntents("p", "lofi music")[1].data.toString() == "geo:0,0?q=lofi%20music")
    val t = listOf("Paid to", "Ravi Kumar", "₹250", "16 Oct").map { ScreenText(it, intArrayOf(0, 0, 1, 1)) }
    check(readAmounts(t) == "Ravi Kumar · ₹250 · 16 Oct")
    val promo = listOf("Personal loan", "Up to ₹40 lakh", "Check details", "Ravi Kumar", "₹250 debited", "16 October")
        .mapIndexed { i, x -> ScreenText(x, intArrayOf(0, i, 1, i + 1)) }
    check(readAmounts(promo + promo.drop(3)) == "Ravi Kumar · ₹250 debited · 16 October")  // the offer and the repeat drop out
    check(ruleSkill("check my last payment in gpay") == "spend_query")
    check(ruleSkill("Just a last 10 transaction from Gpay") == "spend_query")
    listOf("open cred and list my credit card bills", "check my creditcard bills in CRED", "what is the total bill I need to oay",
        "list my card dues", "how much do I owe on my card", "check my card outstanding").forEach {
        check(ruleSkill(it) == "bill_query") { "bill route: $it" }
    }
    check(ruleSkill("message Rahul saying check my card bills") == "message")
    check(ruleSkill("open WhatsApp and message Rahul saying block my calendar") == "message")
    check(SKILLS.size == 16)
    check(billReaderSelfCheck() == "ok")
    check(COUNT.find("Just a last 10 transaction from Gpay")?.groupValues?.get(1) == "10")
    check(COUNT.find("show me 5 payments")?.groupValues?.get(2) == "5" && COUNT.find("show the payment history") == null)
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
        if (app == null && id in READ_SKILLS) return "I couldn't identify the app to read. Please name the app that holds these bills or amounts."
        // a screen the app opens straight to: no steps, and no menu Friday can't read
        app?.let { a -> deepLink(a.pkg, ctx.request) }?.let { i ->
            if (ctx.launch(i)) return "Here it is in ${app.label}"
        }
        if (app != null) {
            // "search for X": the app's own search opens on X's results in one launch, where Laya took 3-4 steps of
            // ~10 s and Maps failed once (suite, 2026-10-19); kept only when the results show X
            ctx.card.query?.takeIf { id !in READ_SKILLS && SEARCH_TASK.containsMatchIn(ctx.card.task) }?.let { q ->
                val words = Routes.contentWords(q)
                val shown = { ctx.foreground() == app.pkg && Routes.contentWords(ctx.screen().texts.joinToString(" ") { it.text }).containsAll(words) }
                if (searchIntents(app.pkg, q).any { ctx.launch(it) && ctx.waitUntil(5000, shown) }) return "Here's $q in ${app.label}"
            }
            ctx.say("Opening ${app.label}…")
            // a task in the app starts from its home screen: GPay reopened on the page the last run left, so the
            // remembered step wasn't there and Laya wandered into a loans page (2026-10-17); "open X" alone resumes
            ctx.open(app, fresh = id != "open_app" || taskAfterOpening(ctx.request, app.label) != null)
            ctx.waitUntil(5000) { ctx.foreground() == app.pkg }
            if (id in READ_SKILLS || Safety.isMoneyApp(app.pkg)) {
                ctx.say("Checking whether ${app.label}'s screen can be read…")
                if (!ctx.waitUntil(5000) { ctx.screen().let { it.pkg == app.pkg && it.texts.any { text -> text.text.isNotBlank() } } })
                    return unreadableMoneyScreen(app.label)
                if (id == "bill_query") readBillAmounts(ctx.screen().texts, totalBillAsked(ctx.request))?.let { return it }
            }
        }
        if (id == "open_app") {
            if (app == null) return "I couldn't find that app"
            // "open X" alone is done; "open X and <task>" goes on inside X -- stopping here was why Friday
            // "only opened apps" (2026-10-17). Laya knows X is open: the trail says OPEN_APP X.
            if (taskAfterOpening(ctx.request, app.label) == null) return "Opened ${app.label}"
        }
        val text = ctx.slots.quoted.firstOrNull()
            ?: ctx.slots.after("search for", "search", "look for", "find", "type", "play", "watch", "listen to")
        // picking a span is a Laya pass (~2-4 s): only if a step types -- most tasks don't (Clock, 2026-10-17)
        val pick = { ctx.slots.spans.takeIf { it.isNotEmpty() }?.let { sp -> ctx.choose(SLOT_Q, sp)?.let { sp[it.index] } } }
        // a payments question is one task however it's asked, so what worked last time applies to every phrasing:
        // "Just a last 10 transaction from Gpay" shared no words with "payment history", got no hint and wandered
        // into the You tab (2026-10-18)
        val goal = if (id == "spend_query") "show the transaction history" else ctx.request
        return when (ctx.runLaya(goal, typeText = text, pickText = pick)) {
            Outcome.DONE -> if (id !in READ_SKILLS) UNVERIFIED_TASK else readAll(ctx, id)
            Outcome.STOPPED -> "Stopped"
            Outcome.STUCK -> Assistant.STUCK
            Outcome.MAX_STEPS -> Assistant.OUT_OF_STEPS
        }
    }
}

/** A model's DONE token is a proposed stopping point, not evidence that a task succeeded. */
internal const val UNVERIFIED_TASK = "I stopped, but I couldn't verify that the task was completed."

private fun unreadableMoneyScreen(appLabel: String?): String =
    "${appLabel?.let { "Opened $it, but " } ?: ""}Android isn't exposing this app's screen to Friday. I couldn't read bills or calculate a total."
