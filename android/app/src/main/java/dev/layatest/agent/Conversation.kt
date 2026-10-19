package dev.layatest.agent

import android.os.SystemClock

/**
 * A conversation (D-061; owner, 2026-10-19: "I want to talk from this place and give it more tasks or clarity"): after
 * a request the task stays open for a few minutes, and the owner's next words carry on from the screen Friday left.
 */
class Session(
    val goal: String,           // the task being carried on
    val card: TaskCard,
    val pkg: String?,           // the app Friday left in front
    val finished: Boolean,      // false: it got stuck or ran out of steps
    val trail: List<String>,    // Laya's history (it reads the last 3)
    val path: List<RouteStep>,  // what it did toward [goal]: a guided success remembers all of it
    val typed: String?,
    val at: Long = SystemClock.elapsedRealtime(),
) {
    val fresh get() = SystemClock.elapsedRealtime() - at < OPEN_MS

    companion object { const val OPEN_MS = 5 * 60_000L }
}

/** What the owner's next words mean for the open task. */
sealed class FollowUp {
    object End : FollowUp()
    object Back : FollowUp()
    class Scroll(val dir: String) : FollowUp()
    class Tap(val what: String) : FollowUp()       // "tap See all orders": the element on screen now, else a nudge
    class Nudge(val where: String?) : FollowUp()   // "it's under the You tab", "keep going": carry on with the task
    class Next(val goal: String, val typed: String? = null) : FollowUp()  // "now track it": a new step from here
    override fun toString(): String = javaClass.simpleName
}

object Conversation {
    private val I = RegexOption.IGNORE_CASE
    private val LEAD = Regex("^(?:(?:ok(?:ay)?|yes|no|nope|and|so|now|then|next|also|please)\\b[,.]?\\s*)+", I)
    private val END = Regex("^(?:stop|cancel|that'?s (?:it|all)|done|thanks?|thank you|never ?mind)\\b", I)
    private val BACK = Regex("^(?:go )?back\\b", I)
    private val SCROLL = Regex("^(?:scroll|swipe) (up|down)\\b", I)
    private val GO_ON = Regex("^(?:continue|keep going|go on|try again|carry on)\\b", I)
    private val TAP = Regex("^(?:tap|click|press|hit|select|choose|pick)\\s+(?:on\\s+)?(?:the\\s+)?(.+)", I)
    private val NUDGE = Regex("^(?:it'?s|it is|try|look|check|go to)\\s+(?:in|under|on|at|inside)?\\s*(?:the\\s+)?(.+)", I)
    private val SEARCH = Regex("^search (?:for )?(.+)", I)
    private val REFERS = Regex("\\b(?:it|that|this|them|there|one|ones)\\b", I)
    private val PRONOUN = Regex("\\b(?:it|that|this|them)\\b", I)

    /** [request] as a follow-up to [prev]; null when it starts something new: it names another app, or reads as a task of its own. */
    fun read(request: String, prev: Session, named: (String) -> AppInfo?): FollowUp? {
        named(request)?.let { if (it.pkg != prev.pkg) return null }
        val cued = LEAD.containsMatchIn(request.trim())
        val r = request.trim().replace(LEAD, "").trim().trimEnd('.', '!', '?')
        fun group(re: Regex) = re.find(r)?.groupValues?.get(1)?.trim()
        return when {
            END.containsMatchIn(r) -> FollowUp.End
            BACK.containsMatchIn(r) -> FollowUp.Back
            SCROLL.containsMatchIn(r) -> FollowUp.Scroll(group(SCROLL)!!.lowercase())
            GO_ON.containsMatchIn(r) -> FollowUp.Nudge(null)
            TAP.containsMatchIn(r) -> FollowUp.Tap(group(TAP)!!)
            NUDGE.containsMatchIn(r) -> FollowUp.Nudge(group(NUDGE))
            SEARCH.containsMatchIn(r) -> group(SEARCH)!!.let { FollowUp.Next("search for $it", typed = it) }
            cued || REFERS.containsMatchIn(r) -> FollowUp.Next(resolve(r, prev))
            else -> null
        }
    }

    /** "track it" after "show my orders" -> "track the order": a pronoun stands for what the task was about. */
    private fun resolve(r: String, prev: Session): String {
        val subject = prev.card.query ?: prev.card.about.lastOrNull { w -> w.none(Char::isDigit) } ?: return r
        return r.replace(PRONOUN, "the $subject")
    }
}

fun conversationSelfCheck(): String {
    val amazon = "in.amazon.mShop.android.shopping"
    val card = TaskCard(request = "show me my last 5 orders in amazon", task = "show me my last 5 orders", skill = "shopping",
        app = amazon, about = linkedSetOf("last", "5", "order"))
    val prev = Session("open amazon and show my orders", card, amazon, false, emptyList(), emptyList(), null)
    val none: (String) -> AppInfo? = { null }
    fun read(r: String) = Conversation.read(r, prev, none)
    check((read("it's under the You tab") as? FollowUp.Nudge)?.where == "You tab") { "nudge" }
    check((read("ok, tap See all orders") as? FollowUp.Tap)?.what == "See all orders") { "tap" }
    check((read("now track it") as? FollowUp.Next)?.goal == "track the order") { "next: ${read("now track it")}" }
    check(read("open the second one") is FollowUp.Next && read("go back") is FollowUp.Back && read("stop") is FollowUp.End) { "next/back/end" }
    check((read("scroll down") as? FollowUp.Scroll)?.dir == "down" && read("keep going") is FollowUp.Nudge) { "scroll/go on" }
    check((read("search for headphones") as? FollowUp.Next)?.typed == "headphones") { "search" }
    check(read("set an alarm for 6 am") == null) { "a task of its own" }
    check(Conversation.read("open whatsapp", prev) { AppInfo("WhatsApp", "com.whatsapp", "") } == null) { "another app" }
    return "ok"
}
