package dev.layatest.agent

import org.json.JSONObject

/**
 * What one request means, read once (the task card, D-051, research/23). Step (a): today's own rules gathered into
 * one place and logged with every request, so a trace shows what Friday understood. Nothing reads it yet: the skills
 * still parse for themselves until step (b), and Laya's goal and the remembered paths' keys are unchanged.
 *
 * [done] says how Friday will know it has finished: playing, alarm:HH:MM, amount, switch:on|off, front, subject,
 * owner (the last tap is the owner's), api, none (nothing to check).
 */
data class TaskCard(
    val request: String,
    val task: String,          // what to do once the app is open
    val skill: String?,        // by the rules; null: Laya or the owner routes it (Assistant fills it in)
    val app: String?,          // the package the request names
    val about: Set<String>,    // what the finished screen should be about
    val query: String? = null, // what to play, search, order or find
    val person: String? = null,
    val text: String? = null,  // the owner's own words to type or say
    val place: String? = null,
    val clock: String? = null, // "06:00"
    val seconds: Int? = null,
    val range: String? = null,
    val count: Int? = null,    // "last 10"
    val control: String? = null, // on | off
    val mode: String = "act",  // read: look and report | act
    val risk: String = "none", // money: read-only app | commit: the last tap is the owner's | none
    val done: String = "subject",
    val calendarEvent: JSONObject? = null,
) {
    fun json(): JSONObject = JSONObject().apply {
        put("task", task); put("skill", skill ?: "?"); app?.let { put("app", it) }; put("about", about.joinToString(" "))
        query?.let { put("query", it) }; person?.let { put("person", it) }; text?.let { put("text", it) }
        place?.let { put("place", it) }; clock?.let { put("clock", it) }; seconds?.let { put("seconds", it) }
        range?.let { put("range", it) }; count?.let { put("count", it) }; control?.let { put("control", it) }
        put("mode", mode); put("risk", risk); put("done", done)
        calendarEvent?.let { put("calendar_event", it) }
    }
}

private val COMMIT = setOf("call", "message", "ride", "shopping", "calendar_event")
private val ON_OFF = Regex("\\b(?:turn|switch) (on|off)\\b|\\b(enable|disable)\\b", RegexOption.IGNORE_CASE)

/** The card for [request], from the rules alone; [named] is the app it names (AppResolver.named on the phone). */
fun understand(request: String, named: (String) -> AppInfo?): TaskCard {
    val task = taskPart(request)
    val skill = ruleSkill(request)
    val app = named(request)
    val s = Slots(task)
    val control = ON_OFF.find(request)?.groupValues?.let { g -> if (g[1].ifEmpty { g[2] }.lowercase() in setOf("on", "enable")) "on" else "off" }
    val (person, body) = if (skill == "message") messageParts(task) else null to null
    val query = when (skill) {
        "play_music", "play_video" -> mediaQuery(request, app?.label).ifEmpty { null }
        "web_search" -> searchQuery(task)
        "shopping" -> if (isTracking(task)) null else shoppingItem(task)
        "photos" -> photoQuery(task)
        else -> s.quoted.firstOrNull() ?: s.after("search for", "search", "look for", "find", "type", "play", "watch", "listen to")
    }
    val timer = bareTimer(request, s.clock)  // "a timer for 5": 5 minutes, not 5:00
    val clock = s.clock?.takeIf { timer == null }?.let { (h, m) -> "%02d:%02d".format(h, m) }
    val money = app?.pkg in AppResolver.MONEY || skill in READ_SKILLS
    val calendar = if (skill == "calendar_event") CalendarRequest.parse(request).event?.let(CalendarEvents::metadata) else null
    return TaskCard(
        request = request, task = task, skill = skill, app = app?.pkg,
        about = Routes.aboutWords(task, app?.label), query = query,
        person = if (skill == "call") callee(task) else person,
        text = body ?: if (skill == "alarm_timer") reminderText(s) else null,
        place = if (skill == "navigate" || skill == "ride") destination(task) else null,
        clock = clock, seconds = s.durationSec ?: timer, range = s.timeRange?.label, count = countAsked(request), control = control,
        mode = if (skill in READ_SKILLS || skill == "device_status" || (skill == "shopping" && isTracking(task))) "read" else "act",
        risk = when { money -> "money"; skill in COMMIT -> "commit"; else -> "none" },
        calendarEvent = calendar,
        done = when (skill) {
            "play_music", "play_video" -> "playing"
            "alarm_timer" -> if (Regex("\\btimer\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)) "none" else clock?.let { "alarm:$it" } ?: "none"
            "spend_query", "balance_query", "bill_query" -> "amount"
            "device_setting" -> control?.let { "switch:$it" } ?: "front"
            "device_status" -> "api"
            "navigate", "web_search" -> "front"
            "calendar_event" -> "calendar_provider_readback"
            in COMMIT -> "owner"
            else -> "subject"
        },
    )
}

/** Asserts the card for a few requests (research/23 §2 examples); wired into the "selfcheck" bridge command. */
fun cardSelfCheck(): String {
    val apps = listOf(AppInfo("YouTube", "com.google.android.youtube", ""), AppInfo("GPay", "com.google.android.apps.nbu.paisa.user", ""),
        AppInfo("Clock", "com.google.android.deskclock", ""))
    fun card(r: String) = understand(r) { req -> apps.firstOrNull { Regex("\\b${it.label}\\b", RegexOption.IGNORE_CASE).containsMatchIn(req) } }
    card("open youtube and play despacito").let { check(it.skill == "play_music" && it.query == "despacito" && it.done == "playing") { "card: $it" } }
    card("show me the last 10 transactions in gpay").let {
        check(it.skill == "spend_query" && it.count == 10 && it.mode == "read" && it.risk == "money" && it.done == "amount") { "card: $it" }
    }
    card("open clock and show the stopwatch").let { check(it.skill == "open_app" && it.about == setOf("stopwatch") && it.done == "subject") { "card: $it" } }
    card("set an alarm for 6 am").let { check(it.skill == "alarm_timer" && it.clock == "06:00" && it.done == "alarm:06:00") { "card: $it" } }
    card("open google maps to navigate to MG road").let { check(it.skill == "navigate" && it.place == "MG road") { "card: $it" } }
    card("set a timer for 5").let { check(it.seconds == 300 && it.clock == null && it.done == "none") { "card: $it" } }
    card("turn off dark mode").let { check(it.skill == "device_setting" && it.control == "off" && it.done == "switch:off") { "card: $it" } }
    return "ok"
}
