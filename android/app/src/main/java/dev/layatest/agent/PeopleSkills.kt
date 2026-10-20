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
// Slots come from the task part: "open google maps to navigate to MG road" went to "navigate to MG road" (research/23 §1.2)
object Navigate : Skill {
    override val id = "navigate"

    override fun run(ctx: SkillContext): String {
        val dest = destination(taskPart(ctx.request)) ?: return "Where to? Try \"navigate to MG Road\""
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=d")).setPackage(MAPS)
        return if (ctx.launch(i)) "Navigating to $dest" else "I couldn't open Google Maps"
    }
}

/** "search for best pizza near me", "what's the weather today" -- Google results in Chrome. */
object WebSearch : Skill {
    override val id = "web_search"

    override fun run(ctx: SkillContext): String {
        if (ctx.stopped) return "Stopped"
        if (!webProviderAllowed(ctx.card.app))
            return "I can't open this web search in the requested app. Choose Chrome, or use Research for Gemini."
        val q = ctx.webQuery()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return if (ctx.stopped) "Stopped" else "I could not prepare a search query; enter the search topic."
        if (ctx.stopped) return "Stopped"
        // a VIEW of the results page: ACTION_WEB_SEARCH only pre-fills Chrome's omnibox (research/10)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).setPackage(CHROME)
        ctx.say("Opening Google search…")
        if (!ctx.launch(i)) return "I couldn't open Chrome"
        val opened = ctx.waitUntil(4000) { ctx.foreground() == CHROME }
        return when {
            ctx.stopped -> "Stopped"
            opened -> "Opened Google search for \"$q\"."
            else -> "I couldn't verify that Chrome opened for the search."
        }
    }
}

private fun webProviderAllowed(pkg: String?): Boolean =
    pkg == null || pkg == CHROME || pkg == "com.google.android.googlequicksearchbox"

/** "dial 98765 43210" -> the dialer, pre-filled; "call mom" -> Laya finds mom in the Phone app (no READ_CONTACTS). */
object Call : Skill {
    override val id = "call"

    override fun run(ctx: SkillContext): String {
        val who = callee(taskPart(ctx.request)) ?: pick(ctx) ?: return "Who should I call?"
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
        val task = taskPart(ctx.request)
        val (_, body) = messageParts(task)
        val exact = exactDraftText(task)
        val kind = if (isEmail(task)) "email" else "message"
        if (exact == null && body.isNullOrBlank()) return "What should the ${if (kind == "email") "email" else "message"} say?"
        return ctx.draft(kind, ctx.request, exact ?: body.orEmpty(), exact != null, emailSubject(task).orEmpty())
    }
}

/** "book a cab to the airport" -- the ride app + Laya; the Book/Confirm tap waits for the owner. */
object Ride : Skill {
    override val id = "ride"

    override fun run(ctx: SkillContext): String {
        val app = ctx.appFor(id) ?: return NO_APP
        ctx.openAndWait(app)
        val dest = destination(taskPart(ctx.request)) ?: return "${app.label} is open — where to? I never book without your OK"
        return outcome(ctx.runLaya("book a ride to $dest", typeText = dest),
            "Your ride to $dest is set up in ${app.label} — I never book without your OK")
    }
}

/** "order milk" -> the shop's results for milk, stopping before Buy; "track my amazon order" -> its orders page. */
object Shopping : Skill {
    override val id = "shopping"

    override fun run(ctx: SkillContext): String {
        val app = ctx.appFor(id) ?: return NO_APP
        val track = isTracking(taskPart(ctx.request))
        val item = if (track) null else shoppingItem(taskPart(ctx.request)) ?: pick(ctx)
        ctx.openAndWait(app)
        return when {
            // the goal a taught path is keyed on, so "open amazon and show my orders" replays without Laya
            track -> ctx.runLaya("open ${app.label.lowercase()} and show my orders").let { o ->
                if (o != Outcome.DONE) outcome(o, "")
                else readList(ctx, countAsked(ctx.request) ?: 3, ::readOrders)?.let { ctx.succeeded(); "Your latest ${app.label} orders:\n$it" }
                    ?: "Your ${app.label} orders are on screen"
            }
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
        val q = photoQuery(taskPart(ctx.request)) ?: pick(ctx)
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
    Outcome.STUCK -> Assistant.STUCK
    Outcome.MAX_STEPS -> Assistant.OUT_OF_STEPS
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
private val NAME_END = Slots.STOP + Slots.CUT + setOf("about", "asking", "you", "we", "hi", "hey", "hello", "good", "happy", "see", "can")
/** Words after a name that don't start a message: "message rahul on whatsapp", "email john about the trip". */
private val LINKS = Slots.CUT + setOf("about", "to", "for", "from", "in", "at")
private val SEARCH_CMD = Regex("^(please |can you |could you )?(search( the web| google| online)?( for)?|look up|google)\\b[\\s,:]*",
    RegexOption.IGNORE_CASE)
private val MSG_CMD = Regex("\\b(message|text|whatsapp|e-?mail|mail)\\b", RegexOption.IGNORE_CASE)
// viewing orders, not buying: "Show my last order that I did on Amazon" searched Amazon for "Amazon" (2026-10-18)
private val TRACK = Regex("\\b(track\\w*|where('s| is)|arriv\\w*|shipped|orders|order (history|status|details)|" +
    "(my|last|latest|recent|past|previous)( \\w+)? order)\\b", RegexOption.IGNORE_CASE)

private fun dropFillers(s: String?) = s?.split(' ')?.dropWhile { it.lowercase() in FILLERS }?.joinToString(" ")?.ifEmpty { null }

/** "navigate to MG road" -> "MG road", "I want to go to X" -> "X", "take me home" -> "home". */
internal fun destination(request: String): String? =
    Slots(request).after("to", cutAt = Slots.CUT + setOf("from", "for", "by"))
        ?.split(' ')?.dropWhile { it.lowercase() in DEST_LEAD }?.joinToString(" ")?.ifEmpty { null }
        ?: Regex("\\b(home|work)\\b", RegexOption.IGNORE_CASE).find(request)?.value?.lowercase()

/** "search for best pizza near me" -> "best pizza near me"; a question is searched as asked. */
internal fun searchQuery(request: String) = request.trim().replace(SEARCH_CMD, "").ifEmpty { null }

/** "call my mom" -> "mom", "dial 98765 43210" -> "98765 43210". */
internal fun callee(request: String) = dropFillers(Slots(request).after("call", "dial", "phone", "ring"))

/** A slot that is a number or code, as the dialer takes it: "+91 98765-43210" -> "+919876543210". */
private fun dialable(slot: String) = slot.replace(Regex("[\\s()-]"), "").takeIf { it.matches(Regex("\\+?[\\d*#]{3,}")) }

/** The command word is email ("email john…", "send a mail to…"), not a chat word that comes first. */
internal fun isEmail(request: String) = MSG_CMD.find(request)?.value?.endsWith("mail", ignoreCase = true) == true

/** "email john about the trip saying …" -> "the trip". */
internal fun emailSubject(request: String) = Slots(request).after("about", cutAt = setOf("that", "saying", "in", "on", "via", "using", "through"))

internal fun exactDraftText(request: String): String? {
    if (Regex("^(?:please\\s+)?(?:do not|don't|never|avoid)\\b", RegexOption.IGNORE_CASE).containsMatchIn(taskPart(request))) return null
    val start = request.indexOfFirst { it == '"' || it == '“' }
    val composing = Regex("^(?:please\\s+)?(?:write|draft|compose|create|generate|suggest|polish|rewrite|rephrase)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(taskPart(request))
    fun directiveAt(index: Int): Boolean {
        val before = request.take(index)
        val source = Regex("\\b(?:saying|that)\\s+", RegexOption.IGNORE_CASE).find(before)
        val body = messageParts(request).second
        val bodyAt = body?.let { request.indexOf(it) }?.takeIf { it >= 0 }
        val inSource = (source != null && before.substring(source.range.last + 1).isNotBlank()) ||
            (bodyAt != null && bodyAt < index && before.substring(bodyAt).isNotBlank())
        val negated = Regex("\\b(?:not|never|don't|do not|without|avoid)\\s+(?:(?:copy|use|write|say|saying|be|it|this|text)\\s+)*$", RegexOption.IGNORE_CASE)
            .containsMatchIn(before)
        return (start < 0 || index < start) && !inSource && !negated
    }
    Regex("\\b(?:verbatim|exactly)\\s*:\\s*(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(request)?.let { directive ->
            if (directiveAt(directive.range.first)) {
                val value = directive.groupValues[1]
                val outer = value.trim()
                return if (outer.length >= 2 && ((outer.first() == '"' && outer.last() == '"') ||
                        (outer.first() == '“' && outer.last() == '”'))) outer.substring(1, outer.lastIndex) else value
            }
        }
    val quotedDirective = if (start < 0) null else Regex("\\b(?:verbatim|exactly)\\s*$", RegexOption.IGNORE_CASE).find(request.take(start))
    if (composing && (quotedDirective == null || !directiveAt(quotedDirective.range.first))) return null
    if (start < 0) return null
    val before = request.take(start)
    if (messageParts(before).first == null && !Regex("\\b(?:saying|that)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(before)) return null
    return Slots(request).quoted.firstOrNull()
}

/**
 * (recipient, message). "message rahul that I'll be late" -> ("rahul", "I'll be late"). With no quotes,
 * "that" or "saying", the words after the name are the message ("text mom I'm on my way").
 * shortcut: a name ends at the first filler, greeting, pronoun or comma, so "text rahul lunch at 1" reads
 * "rahul lunch" as the name; upgrade: match the names in the app's chat list on screen.
 */
internal fun messageParts(request: String): Pair<String?, String?> {
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

/** The first [max] orders on an orders page: lines that say what happened to them ("Order placed 15 October"). */
internal fun readOrders(texts: List<ScreenText>, max: Int): String? =
    texts.map { it.text.trim() }.filter { ORDER_LINE.containsMatchIn(it) }.distinct().take(max).takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { it.take(120) }

private val ORDER_LINE = Regex("\\b(order placed|ordered on|delivered|arriving|shipped|cancelled|returned)\\b", RegexOption.IGNORE_CASE)

/** "order some milk from blinkit" -> "milk". */
internal fun shoppingItem(request: String) =
    dropFillers(Slots(request).after("order", "buy", "purchase", cutAt = Slots.CUT + setOf("from", "for")))

/** "track my amazon order", "where is my order" -- the orders page, not a search. */
internal fun isTracking(request: String) = TRACK.containsMatchIn(request)

/** "show me photos of my dog" -> "dog"; "photos I took yesterday" -> "yesterday". */
internal fun photoQuery(request: String): String? {
    val s = Slots(request)
    return dropFillers(s.after("of", "from", "with", "in", "at")) ?: s.timeRange?.label
}

/** Asserts for the people & places slot rules; wire into the "selfcheck" bridge command. */
fun peopleSelfCheck(): String {
    check(DraftHandoff.selfCheck() == "ok")
    check(exactDraftText("write a WhatsApp message to Rahul saying The label reads exactly: Handle with care.") == null)
    check(exactDraftText("write a WhatsApp message to Rahul exactly: Handle with care.") == "Handle with care.")
    check(exactDraftText("message Rahul saying exactly: Handle with care.") == "Handle with care.")
    check(exactDraftText("Draft a message exactly: \"QA: Keep ₹1,250.\"") == "QA: Keep ₹1,250.")
    check(exactDraftText("Draft a message exactly: “Don't change this.”") == "Don't change this.")
    check(exactDraftText("Draft a message exactly: \" Keep \"quoted\" punctuation!\nNext line. \"") == " Keep \"quoted\" punctuation!\nNext line. ")
    check(exactDraftText("Draft a message exactly: \"An unmatched quote.") == "\"An unmatched quote.")
    check(exactDraftText("Draft a message not exactly: \"Make this friendly\"") == null)
    check(exactDraftText("Don't draft a message exactly: \"This is not a command\"") == null)
    check(destination("navigate to MG road") == "MG road")
    check(destination("I want to go to Indiranagar") == "Indiranagar")
    check(destination("book a cab to the airport for 6 am") == "the airport")
    check(destination("take me home") == "home")
    check(destination("I need to get home now") == "home")
    check(searchQuery("search for best pizza near me") == "best pizza near me")
    check(searchQuery("Look up the population of India") == "the population of India")
    check(searchQuery("what's the weather today") == "what's the weather today")
    check(webProviderAllowed(null) && webProviderAllowed(CHROME))
    check(webProviderAllowed("com.google.android.googlequicksearchbox"))
    check(!webProviderAllowed("com.google.android.apps.bard"))
    check(!webProviderAllowed("org.example.requestedapp") && !webProviderAllowed(""))
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
    check(exactDraftText("write a polite WhatsApp message to Rahul saying \"cannot come\"") == null)
    check(exactDraftText("write a WhatsApp message to Rahul saying exactly \"Keep ₹1,250.\"") == "Keep ₹1,250.")
    check(exactDraftText("message Rahul verbatim: Don't change this.") == "Don't change this.")
    check(exactDraftText("message Rahul saying I will be late") == null)
    check(exactDraftText("write a message saying \"Keep this exact.\"") == null)
    check(exactDraftText("message Rahul \"Keep this exact.\"") == "Keep this exact.")
    check(messageParts("message Rahul on WhatsApp saying I will be 15 minutes late").second == "I will be 15 minutes late")
    check(messageParts("email alex@example.test in Gmail saying I will review the plan").second == "I will review the plan")
    check(exactDraftText("email alex@example.test in Gmail saying I will review the plan") == null)
    check(emailSubject("email john about the trip saying tickets are booked") == "the trip")
    check(shoppingItem("order some milk from blinkit") == "milk")
    check(isTracking("track my amazon order") && !isTracking("order milk"))
    check(photoQuery("show me photos of my dog") == "dog")
    check(photoQuery("photos from last week") == "last week")
    check(photoQuery("show my photos") == null)
    return "ok"
}
