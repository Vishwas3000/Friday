package dev.layatest.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/** One launcher entry. [activity] matters for packages with several entries (Amazon / Amazon Pay share one). */
data class AppInfo(val label: String, val pkg: String, val activity: String)

/** An error retains an explicit search intent; callers must not replace it with a web search. */
data class AppSearch(val appName: String, val app: AppInfo?, val query: String, val error: String? = null)

private val APP_SEARCH_START = Regex("""^\s*(?:please\s+)?(?:search|find|look\s+(?:for|up))\b\s*""", RegexOption.IGNORE_CASE)
private val OPEN_SEARCH = Regex("""^\s*(?:please\s+)?(?:open|launch|start)\s+(.+?)\s+(?:and\s+then|and|then|to)\s+((?:search|find|look\s+(?:for|up))\b[\s\S]*)$""", RegexOption.IGNORE_CASE)
private val LEADING_SEARCH_APP = Regex("""^\s*(?:in|on)\s+(.+?)[,:]?\s+(?:please\s+)?((?:search|find|look\s+(?:for|up))\b[\s\S]*)$""", RegexOption.IGNORE_CASE)
private val SEARCH_FOR = Regex("""\s+for\s+""", RegexOption.IGNORE_CASE)
private val SEARCH_IN = Regex("""\s+(?:in|on)\s+""", RegexOption.IGNORE_CASE)

private fun unquote(value: String): String {
    val text = value.trim()
    return if (text.length >= 2 && (text.first() to text.last()) in setOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’'))
        text.substring(1, text.lastIndex) else text
}

private fun appName(value: String): String = unquote(value.trim().replace(Regex("""^the\s+|\s+app$""", RegexOption.IGNORE_CASE), "").trim())

/** Quote ranges protect query/source text from app-name and delimiter matching. */
private fun quotedRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var end: Char? = null
    var start = 0
    text.forEachIndexed { i, c ->
        if (i > 0 && text[i - 1] == '\\') return@forEachIndexed
        if (end != null) {
            if (c == end) { ranges += start..i; end = null }
        } else {
            val closing = when (c) {
                '"' -> '"'
                '“' -> '”'
                '‘' -> '’'
                '\'' -> '\''.takeIf { (i == 0 || !text[i - 1].isLetterOrDigit()) && text.indexOf('\'', i + 1) >= 0 }
                else -> null
            }
            if (closing != null) { start = i; end = closing }
        }
    }
    if (end != null) ranges += start..text.lastIndex
    return ranges
}

/**
 * Slice app/search grammar from the original request, retaining query punctuation, spacing, numbers and order.
 * Null means an ordinary search or another task; an error means an explicit unresolved app search.
 */
fun parseAppSearch(request: String, named: (String) -> AppInfo?): AppSearch? =
    parseAppSearch(request, named) { AppResolver.isAliasName(it) }

private fun parseAppSearch(request: String, named: (String) -> AppInfo?, recognizes: (String) -> Boolean): AppSearch? {
    fun result(rawName: String, rawQuery: String): AppSearch {
        val name = appName(rawName.trimEnd(',', ':'))
        val app = named(name)?.takeIf { AppResolver.matchesName(name, it) }
        val query = unquote(rawQuery)
        val error = when {
            app == null -> "I couldn't uniquely identify the installed app “$name”. Please name the app to search."
            query.isBlank() -> "What should I search for in $name?"
            else -> null
        }
        return AppSearch(name, app, query, error)
    }
    fun queryAfterVerb(text: String) = APP_SEARCH_START.find(text)?.let { text.substring(it.range.last + 1) }
        ?.replace(Regex("""^for\s+""", RegexOption.IGNORE_CASE), "")
    OPEN_SEARCH.matchEntire(request)?.let { return result(it.groupValues[1], queryAfterVerb(it.groupValues[2]).orEmpty()) }
    LEADING_SEARCH_APP.matchEntire(request)?.let { return result(it.groupValues[1], queryAfterVerb(it.groupValues[2]).orEmpty()) }
    val command = APP_SEARCH_START.find(request) ?: return null
    val body = request.substring(command.range.last + 1)
    val quotes = quotedRanges(body)
    fun outside(match: MatchResult) = quotes.none { match.range.first in it }
    val scoped = Regex("""^(?:in|on)\s+""", RegexOption.IGNORE_CASE).find(body)
    val start = scoped?.range?.last?.plus(1) ?: 0
    if (!Regex("""^for\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
        val names = SEARCH_FOR.findAll(body).filter { outside(it) && it.range.first >= start }
            .map { it to body.substring(start, it.range.first) }.toList()
        val chosen = names.filter { (_, name) -> named(appName(name))?.let { AppResolver.matchesName(name, it) } == true }
            .maxByOrNull { it.second.length } ?: names.firstOrNull()?.takeIf { scoped != null ||
                recognizes(it.second) || Regex("""\bapp$""", RegexOption.IGNORE_CASE).containsMatchIn(it.second.trim()) }
        chosen?.let { (delimiter, name) -> return result(name, body.substring(delimiter.range.last + 1)) }
    }
    // Resolve only the app suffix; another app name inside the query cannot select the destination.
    for (delimiter in SEARCH_IN.findAll(body).filter(::outside).toList().asReversed()) {
        val name = body.substring(delimiter.range.last + 1)
        val app = named(appName(name))
        if ((app != null && AppResolver.matchesName(name, app)) || recognizes(name) ||
            Regex("""\bapp$""", RegexOption.IGNORE_CASE).containsMatchIn(name.trim())) {
            val query = body.substring(0, delimiter.range.first).replace(Regex("""^for\s+""", RegexOption.IGNORE_CASE), "")
            return result(name, query)
        }
    }
    if (!Regex("""^for\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
        val name = body.substring(start)
        if (named(appName(name))?.let { AppResolver.matchesName(name, it) } == true || recognizes(name))
            return result(name, "")
    }
    return null
}

/**
 * Which app does what: a lookup table plus the owner's remembered choice (D-027). Not a model call --
 * research/09 measured Laya picking the right app from the full launcher only 6-26% of the time,
 * because it matches words ("wake" -> a sleep tracker), not purpose.
 */
class AppResolver(ctx: Context) {
    private val pm = ctx.packageManager
    private val prefs = ctx.getSharedPreferences("friday", Context.MODE_PRIVATE)

    val installed: List<AppInfo> by lazy {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
    }

    /** Installed apps that can do [skill], catalog order first, then apps the system tags with the skill's category. */
    fun candidates(skill: String): List<AppInfo> {
        val byPkg = installed.groupBy { it.pkg }
        val out = (CATALOG[skill] ?: emptyList()).mapNotNull { byPkg[it]?.first() }.toMutableList()
        SYSTEM_CATEGORY[skill]?.let { cat ->
            installed.filter { a -> out.none { it.pkg == a.pkg } && category(a.pkg) == cat }.forEach { out += it }
        }
        return out
    }

    fun default(skill: String): AppInfo? =
        prefs.getString("app.$skill", null)?.let { p -> candidates(skill).firstOrNull { it.pkg == p } }

    fun remember(skill: String, app: AppInfo) = prefs.edit().putString("app.$skill", app.pkg).apply()

    /** An explicit destination wins over names embedded in its query or source text. */
    fun named(request: String): AppInfo? = named(request, installed)

    fun search(request: String): AppSearch? = search(request, installed)

    private fun category(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)

    companion object {
        private fun normalize(value: String) = appName(value).lowercase().replace(Regex("""\s+"""), " ")
        internal fun isAliasName(value: String) = normalize(value) in ALIASES
        internal fun matchesName(value: String, app: AppInfo): Boolean =
            normalize(value) == normalize(app.label) || ALIASES[normalize(value)] == app.pkg

        /** Pure exact lookup; labels shared by different installed packages are ambiguous. */
        fun exactName(value: String, installed: List<AppInfo>): AppInfo? {
            val name = normalize(value)
            val alias = ALIASES[name]
            return installed.filter { normalize(it.label) == name || it.pkg == alias }.distinctBy { it.pkg }.singleOrNull()
        }

        fun search(request: String, installed: List<AppInfo>): AppSearch? =
            parseAppSearch(request, { exactName(it, installed) }) { name ->
                isAliasName(name) || installed.any { normalize(it.label) == normalize(name) }
            }

        private data class NameSpan(val range: IntRange, val apps: List<AppInfo>)
        private fun nameSpans(request: String, installed: List<AppInfo>): List<NameSpan> {
            val names = installed.filter { it.label.isNotBlank() }.groupBy { normalize(it.label) }.toMutableMap()
            ALIASES.forEach { (name, pkg) -> names[name] = (names[name].orEmpty() + installed.filter { it.pkg == pkg }).distinctBy { it.pkg } }
            val quotes = quotedRanges(request)
            val spans = names.flatMap { (name, apps) ->
                val words = name.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                Regex("""(?<![\p{L}\p{N}])$words(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE).findAll(request)
                    .filter { match -> quotes.none { match.range.first in it } }.map { NameSpan(it.range, apps) }.toList()
            }
            return spans.filter { span -> spans.none { other ->
                other.range.first <= span.range.first && other.range.last >= span.range.last && other.range != span.range
            } }
        }

        /** Pure counterpart for fixtures and callers that already have a launcher inventory. */
        fun named(request: String, installed: List<AppInfo>): AppInfo? {
            search(request, installed)?.let { return it.app }
            if (APP_SEARCH_START.containsMatchIn(request)) return null
            val opened = Regex("""^\s*(?:please\s+)?(?:open|launch|start)\s+(.+?)(?=\s+(?:and|then|to)\b|$)""", RegexOption.IGNORE_CASE)
                .find(request)?.groupValues?.get(1)
            val scope = opened ?: request
            val spans = nameSpans(scope, installed)
            if (spans.isNotEmpty()) {
                if (spans.any { it.apps.isEmpty() }) return null
                return spans.flatMap { it.apps }.distinctBy { it.pkg }.singleOrNull()
            }
            // Typo assistance retains only an unambiguous closest app, excluding everyday task words.
            val close = Regex("[a-z]{5,}").findAll(scope.lowercase()).map { it.value }.filter { it !in EVERYDAY }
                .flatMap { word -> installed.filter { ' ' !in it.label && it.label.length >= 5 }.map { app ->
                    app to editDistance(word, app.label.lowercase())
                }.filter { (app, distance) -> distance in 1..(if (app.label.length >= 8) 2 else 1) } }.toList()
            val distance = close.minOfOrNull { it.second } ?: return null
            return close.filter { it.second == distance }.map { it.first }.distinctBy { it.pkg }.singleOrNull()
        }

        fun selfCheck(): String {
            val maps = AppInfo("Maps", "com.google.android.apps.maps", "maps")
            val google = AppInfo("Google", "com.google.android.googlequicksearchbox", "google")
            val music = AppInfo("YouTube Music", "com.google.android.apps.youtube.music", "music")
            val pay = AppInfo("GPay", "com.google.android.apps.nbu.paisa.user", "pay")
            val gmail = AppInfo("Gmail", "com.google.android.gm", "gmail")
            val whatsapp = AppInfo("WhatsApp", "com.whatsapp", "whatsapp")
            val notes = AppInfo("Notes", "synthetic.notes", "notes")
            val apps = listOf(maps, google, music, pay, gmail, whatsapp, notes,
                AppInfo("YouTube", "com.google.android.youtube", "youtube"), AppInfo("Amazon", "synthetic.amazon", "amazon"))
            check(named("Open Google Maps", apps) == maps)
            check(named("Open Google", apps) == google)
            check(named("Open YouTube Music", apps) == music && named("Play jazz on YT Music", apps) == music)
            check(named("Open Google Pay", apps) == pay)
            check(named("Open Google Maps", apps - maps) == null)
            check(named("Open Gmail and draft a message on WhatsApp", apps) == gmail)
            check(named("Draft a message on WhatsApp saying \"Google Pay and Gmail\"", apps) == whatsapp)
            check(named("Compare Google and Gmail", apps) == null)
            check(named("Open amazzon", apps)?.pkg == "synthetic.amazon")
            val shorter = AppInfo("Map Viewer", "synthetic.viewer", "viewer")
            val longer = AppInfo("Map Viewer Pro", "synthetic.viewer.pro", "pro")
            check(named("Open Map Viewer Pro", apps + shorter + longer) == longer)
            val shop = AppInfo("Shop", "synthetic.shop", "shop")
            val shopPay = AppInfo("Shop Pay", "synthetic.shop", "pay")
            check(named("Open Shop Pay", apps + shop + shopPay) == shopPay)
            fun query(request: String, expected: String, app: AppInfo = maps) {
                val parsed = search(request, apps)
                check(parsed != null && parsed.error == null && parsed.app == app && parsed.query == expected) { "app search: $request -> $parsed" }
                check(named(request, apps) == app)
            }
            query("Search Google Maps for Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            query("Search in Google Maps for Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            query("Search on the Maps app for Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            query("Search for Cubbon Park Bengaluru in Google Maps", "Cubbon Park Bengaluru")
            query("Find Cubbon Park Bengaluru on Google Maps", "Cubbon Park Bengaluru")
            query("In Google Maps, search for Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            query("Open Google Maps and search for Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            query("Open Google Maps then look up Cubbon Park Bengaluru", "Cubbon Park Bengaluru")
            val exact = "JOB-731: 2 rooms, 07/01/2027 at 09:30; ₹1,200.50 + 8% / GST"
            query("Search Google Maps for \"$exact\"", exact)
            query("Search for “$exact” on Google Maps", exact)
            query("Search in Google Maps for hotels  on 28 October 2027, 2 rooms", "hotels  on 28 October 2027, 2 rooms")
            query("Search Google Maps for Google Pay offices", "Google Pay offices")
            query("Search for \"Google Pay on 7 January 2027\" in Google Maps", "Google Pay on 7 January 2027")
            query("Search Gmail for \"JOB-731 in Maps for 2 people\"", "JOB-731 in Maps for 2 people", gmail)
            query("Search Notes for 'Joe’s Cafe, 28 Oct 2027 / 2 seats'", "Joe’s Cafe, 28 Oct 2027 / 2 seats", notes)
            check(search("Search Google Maps for Cubbon Park", apps - maps)?.let { it.app == null && it.error != null && it.query == "Cubbon Park" } == true)
            check(search("Search in Mercury app for A-9, 07/01/2027", apps)?.let { it.app == null && it.error != null && it.query == "A-9, 07/01/2027" } == true)
            val ambiguous = apps + AppInfo("Notes", "synthetic.other.notes", "other")
            check(named("Open Notes", ambiguous) == null)
            check(search("Search Notes for JOB-731", ambiguous)?.let { it.app == null && it.error != null && it.query == "JOB-731" } == true)
            check(search("Search in Notes for JOB-731", ambiguous)?.error != null)
            check(search("Search Google Maps", apps)?.let { it.query.isEmpty() && it.error != null } == true)
            check(search("Search for hotels in Nepal on 28 October 2027", apps) == null)
            check(search("Search for \"Google Maps alternatives\"", apps) == null)
            check(named("Search for \"Google Maps alternatives\"", apps) == null)
            check(search("Read Gmail inbox and draft a reply on WhatsApp", apps) == null)
            check(search("Write a WhatsApp message saying \"search Gmail for JOB-731\"", apps) == null)
            return "ok"
        }

        private val EVERYDAY = setOf("alarm", "alarms", "timer", "timers", "music", "photo", "photos", "video", "videos",
            "order", "orders", "search", "phone", "message", "messages", "clock", "camera", "settings", "store", "notes",
            "weather", "calendar", "files", "contacts", "maps", "email", "emails", "mail", "chrome", "money", "books", "games")

        /** Levenshtein distance: inserts, deletes and substitutions to turn [a] into [b]. */
        fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1).also { it[0] = i }
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }

        private val UPI = listOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", // PhonePe, Paytm, BHIM
        )
        private val BANKS = listOf(
            "com.sbi.lotusintouch", "com.hdfcbank.android.now", "com.snapwork.hdfc", "com.csam.icici.bank.imobile",
            "com.axis.mobile", "com.msf.kbank.mobile", "com.idfcfirstbank.optimus",
        )
        private val CARDS = listOf("com.dreamplug.androidapp", "com.ge.capital.konysbiapp", "com.creditcard.onecard") // CRED, SBI Card, OneCard
        private val TRACKERS = listOf("com.droid4you.application.wallet", "in.indwealth") // Wallet, INDmoney

        /** Skill -> well-known apps that can do it, most common first. Public app ids, matched against what's installed. */
        val CATALOG: Map<String, List<String>> = mapOf(
            "play_music" to listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats",
                "com.gaana", "com.bsbportal.music", "com.amazon.mp3", "com.apple.android.music"),
            "play_video" to listOf("com.google.android.youtube", "com.amazon.avod.thirdpartyclient", "com.netflix.mediaclient",
                "in.startv.hotstar", "com.jio.media.ondemand", "tv.accedo.airtel.wynk"),
            "spend_query" to UPI + BANKS + CARDS + TRACKERS,
            "balance_query" to UPI + BANKS,
            "bill_query" to CARDS + BANKS,
            "device_setting" to listOf("com.android.settings"),
            "device_status" to listOf("com.android.settings"),
            "alarm_timer" to listOf("com.google.android.deskclock"),
            "call" to listOf("com.google.android.dialer"),
            "message" to listOf("com.whatsapp", "com.google.android.apps.messaging", "com.google.android.gm"),
            "navigate" to listOf("com.google.android.apps.maps"),
            "web_search" to listOf("com.android.chrome", "com.google.android.googlequicksearchbox"),
            "ride" to listOf("in.juspay.nammayatri", "com.ubercab", "com.olacabs.customer", "com.rapido.passenger"),
            "shopping" to listOf("in.amazon.mShop.android.shopping", "com.flipkart.android", "com.grofers.customerapp",
                "com.zeptoconsumer", "in.swiggy.android", "com.bigbasket.mobileapp"),
            "photos" to listOf("com.google.android.apps.photos"),
        )

        /** Apps where Friday is read-only (D-026): it never types, and never taps Pay/Send/Transfer and the like. */
        val MONEY: Set<String> = (UPI + BANKS + CARDS + TRACKERS).toSet()

        /** Names people say that aren't the launcher label ("youtube music" would otherwise match "YouTube"). */
        private val ALIASES = mapOf(
            "google maps" to "com.google.android.apps.maps",
            "youtube music" to "com.google.android.apps.youtube.music",
            "yt music" to "com.google.android.apps.youtube.music",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
        )

        private val SYSTEM_CATEGORY = mapOf(
            "play_music" to ApplicationInfo.CATEGORY_AUDIO, "play_video" to ApplicationInfo.CATEGORY_VIDEO,
            "navigate" to ApplicationInfo.CATEGORY_MAPS, "photos" to ApplicationInfo.CATEGORY_IMAGE,
        )
    }
}
