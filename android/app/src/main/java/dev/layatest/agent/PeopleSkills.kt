package dev.layatest.agent

import android.content.Intent
import android.net.Uri

/**
 * People & places skills: an intent when one finishes the job (navigate, web search, dial, email
 * draft), else open the app and let Laya drive short sub-goals. Sending, calling, booking and buying
 * ask the owner underneath (Safety). Slots come from the rules below; Laya picks a span only when
 * they find nothing. Mechanisms from research/10.
 */
fun peopleSkills(): List<Skill> = listOf(Navigate, WebSearch, Call, Message, Ride, Shopping, Photos)

private const val MAPS = "com.google.android.apps.maps"
private const val CHROME = "com.android.chrome"
private const val NO_APP = "I couldn't find an app for that"

/** "navigate to MG road", "take me home" -- Maps starts turn-by-turn at once. */
object Navigate : Skill {
    override val id = "navigate"

    override fun run(ctx: SkillContext): String {
        val dest = destination(ctx.request) ?: return "Where to? Try \"navigate to MG Road\""
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=d")).setPackage(MAPS)
        return if (ctx.launch(i)) "Navigating to $dest" else "I couldn't open Google Maps"
    }
}

/** "search for best pizza near me", "what's the weather today" -- Google results in Chrome. */
object WebSearch : Skill {
    override val id = "web_search"

    override fun run(ctx: SkillContext): String {
        val q = searchQuery(ctx.request) ?: return "What should I search for?"
        // a VIEW of the results page: ACTION_WEB_SEARCH only pre-fills Chrome's omnibox (research/10)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).setPackage(CHROME)
        return if (ctx.launch(i)) "Here's what Google found for \"$q\"" else "I couldn't open Chrome"
    }
}

/** "dial 98765 43210" -> the dialer, pre-filled; "call mom" -> Laya finds mom in the Phone app (no READ_CONTACTS). */
object Call : Skill {
    override val id = "call"

    override fun run(ctx: SkillContext): String {
        val who = callee(ctx.request) ?: pick(ctx) ?: return "Who should I call?"
        dialable(who)?.let { n ->
            val ok = ctx.launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)))
            return if (ok) "$n is ready — tap Call" else "I couldn't open the dialer"
        }
        val app = ctx.appFor(id) ?: return NO_APP
        ctx.openAndWait(app)
        return outcome(ctx.runLaya("call $who", typeText = who), "Calling $who")
    }
}

/** "message rahul that I'll be late", "text mom I'm on my way", "email john about the trip". */
object Message : Skill {
    override val id = "message"

    override fun run(ctx: SkillContext): String {
        val (rule, body) = messageParts(ctx.request)
        if (isEmail(ctx.request)) return email(ctx, rule, body)
        val to = rule ?: pick(ctx) ?: return "Who should I message?"
        val app = ctx.appFor(id) ?: return NO_APP
        ctx.openAndWait(app)
        // two sub-goals, because runLaya types the same text at every INPUT_TEXT
        val chat = ctx.runLaya("open the chat with $to", typeText = to)
        if (chat != Outcome.DONE || body == null) return outcome(chat, "The chat with $to is open — type your message")
        return outcome(ctx.runLaya("type the message and send it", typeText = body), "Sent to $to")
    }

    /** The mail app's compose screen, filled in; the owner checks it and taps Send. */
    private fun email(ctx: SkillContext, to: String?, body: String?): String {
        val addr = to?.takeIf { '@' in it }.orEmpty() // a bare name isn't an address: the owner picks the contact
        val ok = ctx.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr"))
            .putExtra(Intent.EXTRA_SUBJECT, emailSubject(ctx.request)).putExtra(Intent.EXTRA_TEXT, body))
        return when {
            !ok -> "I couldn't open an email app"
            to != null && addr.isEmpty() -> "Email ready — add $to, then tap Send"
            else -> "Email ready — check it and tap Send"
        }
    }
}

/** "book a cab to the airport" -- the ride app + Laya; the Book/Confirm tap waits for the owner. */
object Ride : Skill {
    override val id = "ride"

    override fun run(ctx: SkillContext): String {
        val app = ctx.appFor(id) ?: return NO_APP
        ctx.openAndWait(app)
        val dest = destination(ctx.request) ?: return "${app.label} is open — where to? I never book without your OK"
        return outcome(ctx.runLaya("book a ride to $dest", typeText = dest),
            "Your ride to $dest is set up in ${app.label} — I never book without your OK")
    }
}

/** "order milk" -> the shop's results for milk, stopping before Buy; "track my amazon order" -> its orders page. */
object Shopping : Skill {
    override val id = "shopping"

    override fun run(ctx: SkillContext): String {
        val app = ctx.appFor(id) ?: return NO_APP
        val track = isTracking(ctx.request)
        val item = if (track) null else shoppingItem(ctx.request) ?: pick(ctx)
        ctx.openAndWait(app)
        return when {
            track -> outcome(ctx.runLaya("open my orders"), "Your ${app.label} orders are on screen")
            item == null -> "Opened ${app.label}"
            else -> outcome(ctx.runLaya("search for $item", typeText = item), "Here's $item in ${app.label} — pick one and buy it yourself")
        }
    }
}

/** "show me photos of my dog", "photos from last week" -- the photos app, searched when there's a subject or time. */
object Photos : Skill {
    override val id = "photos"

    override fun run(ctx: SkillContext): String {
        val app = ctx.appFor(id) ?: return NO_APP
        val q = photoQuery(ctx.request) ?: pick(ctx)
        ctx.openAndWait(app)
        if (q == null) return "Opened ${app.label}"
        return outcome(ctx.runLaya("search for $q", typeText = q), "Here are your photos of $q")
    }
}

private fun SkillContext.openAndWait(app: AppInfo) {
    say("Opening ${app.label}…")
    open(app)
    waitUntil(5000) { foreground() == app.pkg }
}

/** No rule found the slot: Laya picks among the request's spans (free with one span; otherwise it wakes Laya, ~18 s cold). */
private fun pick(ctx: SkillContext): String? {
    val spans = ctx.slots.spans.filterNot { SKILL_WORDS.containsMatchIn(it) }
    return ctx.choose(SLOT_Q, spans)?.let { spans[it.index] }
}

private fun outcome(o: Outcome, done: String) = when (o) {
    Outcome.DONE -> done
    Outcome.STOPPED -> "Stopped"
    Outcome.STUCK -> "I got stuck — the screen is where I stopped"
    Outcome.MAX_STEPS -> "I ran out of steps before finishing"
}

// ---- Slot rules (pure; peopleSelfCheck asserts them) ----

/** Words that name the skill, not the slot: "show my photos" has no subject. */
private val SKILL_WORDS = Regex("\\b(dial|phone|ring|whatsapp|mail|tell|deliver\\w*|shop\\w*|purchase|photos?|pictures?|pics?|images?|gallery)\\b",
    RegexOption.IGNORE_CASE)
/** Leading words that aren't part of a name or item: "to my wife" -> "wife", "some milk" -> "milk". */
private val FILLERS = setOf("to", "my", "me", "the", "a", "an", "some", "our")
/** Words between "to" and the place: "to go to X", "to get home", "to be at X". */
private val DEST_LEAD = setOf("go", "get", "reach", "head", "drive", "walk", "be", "at", "to", "my")
/** Where a name ends: a command or filler word, a greeting or a pronoun ("text mom I'm on my way"). */
private val NAME_END = Slots.STOP + Slots.CUT + setOf("about", "you", "we", "hi", "hey", "hello", "good", "happy", "see", "can")
/** Words after a name that don't start a message: "message rahul on whatsapp", "email john about the trip". */
private val LINKS = Slots.CUT + setOf("about", "to", "for", "from", "in", "at")
private val SEARCH_CMD = Regex("^(please |can you |could you )?(search( the web| google| online)?( for)?|look up|google)\\b[\\s,:]*",
    RegexOption.IGNORE_CASE)
private val MSG_CMD = Regex("\\b(message|text|whatsapp|e-?mail|mail)\\b", RegexOption.IGNORE_CASE)
private val TRACK = Regex("\\b(track\\w*|my orders?|where('s| is)|arriv\\w*|shipped)\\b", RegexOption.IGNORE_CASE)

private fun dropFillers(s: String?) = s?.split(' ')?.dropWhile { it.lowercase() in FILLERS }?.joinToString(" ")?.ifEmpty { null }

/** "navigate to MG road" -> "MG road", "I want to go to X" -> "X", "take me home" -> "home". */
private fun destination(request: String): String? =
    Slots(request).after("to", cutAt = Slots.CUT + setOf("from", "for", "by"))
        ?.split(' ')?.dropWhile { it.lowercase() in DEST_LEAD }?.joinToString(" ")?.ifEmpty { null }
        ?: Regex("\\b(home|work)\\b", RegexOption.IGNORE_CASE).find(request)?.value?.lowercase()

/** "search for best pizza near me" -> "best pizza near me"; a question is searched as asked. */
private fun searchQuery(request: String) = request.trim().replace(SEARCH_CMD, "").ifEmpty { null }

/** "call my mom" -> "mom", "dial 98765 43210" -> "98765 43210". */
private fun callee(request: String) = dropFillers(Slots(request).after("call", "dial", "phone", "ring"))

/** A slot that is a number or code, as the dialer takes it: "+91 98765-43210" -> "+919876543210". */
private fun dialable(slot: String) = slot.replace(Regex("[\\s()-]"), "").takeIf { it.matches(Regex("\\+?[\\d*#]{3,}")) }

/** The command word is email ("email john…", "send a mail to…"), not a chat word that comes first. */
private fun isEmail(request: String) = MSG_CMD.find(request)?.value?.endsWith("mail", ignoreCase = true) == true

/** "email john about the trip saying …" -> "the trip". */
private fun emailSubject(request: String) = Slots(request).after("about", cutAt = setOf("that", "saying"))

/**
 * (recipient, message). "message rahul that I'll be late" -> ("rahul", "I'll be late"). With no quotes,
 * "that" or "saying", the words after the name are the message ("text mom I'm on my way").
 * shortcut: a name ends at the first filler, greeting, pronoun or comma, so "text rahul lunch at 1" reads
 * "rahul lunch" as the name; upgrade: match the names in the app's chat list on screen.
 */
private fun messageParts(request: String): Pair<String?, String?> {
    val s = Slots(request)
    val words = Slots(request.substringBefore('"').substringBefore('“')) // a quoted message ends the name
        .after("message", "text", "tell", "whatsapp", "let", "email", "e-mail", "mail", cutAt = emptySet())
        ?.split(' ')?.dropWhile { it.lowercase() in FILLERS }.orEmpty()
    val name = words.takeWhile { it.lowercase().trim(',', '.', '!', '?', ':') !in NAME_END }
        .let { n -> n.take(n.indexOfFirst { it.endsWith(',') || it.endsWith(':') } + 1).ifEmpty { n } } // "rahul, running late"
    val rest = words.drop(name.size).let { if (it.firstOrNull().equals("know", ignoreCase = true)) it.drop(1) else it }
    val body = s.quoted.firstOrNull() ?: s.after("that", "saying", cutAt = emptySet())
        ?: rest.takeIf { it.isNotEmpty() && it[0].lowercase() !in LINKS }?.joinToString(" ")
    return name.joinToString(" ").trim(',', '.', '!', '?', ':').ifEmpty { null } to body
}

/** "order some milk from blinkit" -> "milk". */
private fun shoppingItem(request: String) =
    dropFillers(Slots(request).after("order", "buy", "purchase", cutAt = Slots.CUT + setOf("from", "for")))

/** "track my amazon order", "where is my order" -- the orders page, not a search. */
private fun isTracking(request: String) = TRACK.containsMatchIn(request)

/** "show me photos of my dog" -> "dog"; "photos I took yesterday" -> "yesterday". */
private fun photoQuery(request: String): String? {
    val s = Slots(request)
    return dropFillers(s.after("of", "from", "with", "in", "at")) ?: s.timeRange?.label
}

/** Asserts for the people & places slot rules; wire into the "selfcheck" bridge command. */
fun peopleSelfCheck(): String {
    check(destination("navigate to MG road") == "MG road")
    check(destination("I want to go to Indiranagar") == "Indiranagar")
    check(destination("book a cab to the airport for 6 am") == "the airport")
    check(destination("take me home") == "home")
    check(destination("I need to get home now") == "home")
    check(searchQuery("search for best pizza near me") == "best pizza near me")
    check(searchQuery("Look up the population of India") == "the population of India")
    check(searchQuery("what's the weather today") == "what's the weather today")
    check(callee("call my mom") == "mom")
    check(dialable(callee("call +91 98765-43210 now")!!) == "+919876543210")
    check(dialable("rahul") == null)
    check(messageParts("message rahul that I'll be late") == ("rahul" to "I'll be late"))
    check(messageParts("text mom I'm on my way") == ("mom" to "I'm on my way"))
    check(messageParts("message rahul, running late") == ("rahul" to "running late"))
    check(messageParts("send a message to my wife saying running late") == ("wife" to "running late"))
    check(messageParts("whatsapp rahul \"see you at 8\"") == ("rahul" to "see you at 8"))
    check(messageParts("let rahul know I'm running late") == ("rahul" to "I'm running late"))
    check(messageParts("message riley jordan on whatsapp") == ("riley jordan" to null))
    check(messageParts("send rahul a message") == (null to null))
    check(isEmail("send an email to john@example.com about the trip") && !isEmail("text mom that I sent the email"))
    check(messageParts("email john@example.com about the trip") == ("john@example.com" to null))
    check(emailSubject("email john about the trip saying tickets are booked") == "the trip")
    check(shoppingItem("order some milk from blinkit") == "milk")
    check(isTracking("track my amazon order") && !isTracking("order milk"))
    check(photoQuery("show me photos of my dog") == "dog")
    check(photoQuery("photos from last week") == "last week")
    check(photoQuery("show my photos") == null)
    return "ok"
}
