package dev.layatest.agent

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * What each installed app is for, read from what the app itself declares (research/12): its label, package-name
 * words, manifest category, intent-filter capabilities, and static launcher shortcuts with their App Actions
 * capabilities. No hand-written per-app knowledge. All of it is readable without a permission for any app we can
 * see; dynamic and pinned shortcuts are not (default launcher or ACCESS_SHORTCUTS only).
 */
object AppProfiler {
    /** Laya caps each option at 48 tokens. */
    // shortcut: words stand in for tokens; comma-heavy profiles hit 48 tokens near 30 words (measured), so Laya drops
    // the tail (shortcuts go first). Count with the on-device tokenizer if that tail matters.
    const val MAX_WORDS = 35

    /** Capability kind -> how a profile says it, in the order profiles list them. */
    private val DOES = mapOf(
        "music" to "play music", "maps" to "navigation", "calls" to "phone calls", "email" to "email",
        "upi" to "UPI payments", "clock" to "alarms", "camera" to "take photos", "photos" to "view images",
        "browser" to "web browsing",
    )
    private val CATEGORY = mapOf(
        ApplicationInfo.CATEGORY_GAME to "game", ApplicationInfo.CATEGORY_AUDIO to "audio",
        ApplicationInfo.CATEGORY_VIDEO to "video", ApplicationInfo.CATEGORY_IMAGE to "photos",
        ApplicationInfo.CATEGORY_SOCIAL to "social", ApplicationInfo.CATEGORY_NEWS to "news",
        ApplicationInfo.CATEGORY_MAPS to "maps", ApplicationInfo.CATEGORY_PRODUCTIVITY to "productivity",
    )
    /** Package-id words that say nothing about the app: domains, platform, build flavours, audience. */
    private val NOISE = setOf("com", "org", "net", "android", "google", "app", "apps", "application", "androidapp",
        "mobile", "mobileapp", "client", "main", "free", "lite", "pro", "beta", "release", "prod", "user", "customer",
        "consumer", "activity", "sec")
    /** App Actions intents nearly every adopter declares; they don't say what the app is for. */
    private val GENERIC_ACTIONS = setOf("OPEN_APP_FEATURE", "GET_THING")
    private val CAMEL = Regex("(?<=[a-z])(?=[A-Z])")
    // ascending ids, as obtainAttributes requires
    private val ATTRS = intArrayOf(android.R.attr.name, android.R.attr.enabled, android.R.attr.shortcutShortLabel,
        android.R.attr.shortcutLongLabel)

    /** Package -> capability kinds (eval_apps.py's vocabulary) from the intent filters apps declare; needs the <queries>. */
    fun discover(pm: PackageManager): Map<String, Set<String>> {
        val probes = listOf(
            "music" to Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
            "maps" to Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=0,0")),
            "maps" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")),
            "calls" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")),
            "email" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            "upi" to Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")),
            "clock" to Intent(AlarmClock.ACTION_SET_ALARM),
            "camera" to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            "photos" to Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media/0"), "image/*"),
            "browser" to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        val out = HashMap<String, MutableSet<String>>()
        // MATCH_ALL: without it a web query returns only the default browser
        for ((kind, intent) in probes) for (r in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
            out.getOrPut(r.activityInfo.packageName) { HashSet() } += kind
        }
        return out
    }

    /** Package -> profile for every launcher app. Loads each app's resources: run it off the main thread, once, and cache. */
    fun profiles(pm: PackageManager): Map<String, String> {
        val kinds = discover(pm)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.GET_META_DATA).groupBy { it.activityInfo.packageName }
            .mapValues { (pkg, entries) ->
                val shortcuts = ArrayList<String>()
                val capabilities = ArrayList<String>()
                for (e in entries) {
                    val xml = e.activityInfo.metaData?.getInt("android.app.shortcuts") ?: 0
                    // fail soft: an app whose resources won't load or parse keeps its other signals
                    if (xml != 0) runCatching {
                        readShortcuts(pm.getResourcesForApplication(e.activityInfo.applicationInfo), xml, shortcuts, capabilities)
                    }
                }
                profileOf(entries.map { it.loadLabel(pm).toString() }.distinct().joinToString(" / "), pkg,
                    entries[0].activityInfo.applicationInfo.category, kinds[pkg].orEmpty(), capabilities, shortcuts)
            }
    }

    /** Enabled static shortcuts' labels and App Actions capability names from one android.app.shortcuts XML. */
    private fun readShortcuts(res: Resources, xml: Int, shortcuts: MutableList<String>, capabilities: MutableList<String>) {
        res.getXml(xml).use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG || (p.name != "shortcut" && p.name != "capability")) continue
                // by attribute id, like the system's ShortcutParser: survives stripped attribute names
                val a = res.obtainAttributes(p, ATTRS)
                try {
                    if (p.name == "capability") a.getString(0)?.let { capabilities += it }
                    else if (a.getBoolean(1, true)) (a.getString(2) ?: a.getString(3))?.let { shortcuts += it }
                } finally {
                    a.recycle()
                }
            }
        }
    }

    /**
     * "Fit — fitness; does: start exercise; shortcuts: Start workout, Track activity": the label, then what the app
     * declares in priority order, cut at a whole item to stay within [MAX_WORDS].
     */
    fun profileOf(label: String, pkg: String, category: Int, kinds: Set<String>, capabilities: List<String>, shortcuts: List<String>): String {
        val name = label.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val about = (packageWords(pkg) + listOfNotNull(CATEGORY[category])).distinct().filter { it !in name }
        val does = (DOES.filterKeys { it in kinds }.values + capabilities.mapNotNull { action(it) }).distinct()
        var left = MAX_WORDS - words(label)
        // [head] plus the leading [items] that fit in what's left; null if none does
        fun fit(head: String, items: List<String>, sep: String): String? {
            var used = words(head)
            val kept = ArrayList<String>()
            for (i in items) {
                if (used + words(i) > left) break
                used += words(i)
                kept += i
            }
            if (kept.isEmpty()) return null
            left -= used
            return head + kept.joinToString(sep)
        }
        val head = listOfNotNull(label, fit("— ", about, " ")).joinToString(" ")
        val labels = shortcuts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
        return listOfNotNull(head, fit("does: ", does, ", "), fit("shortcuts: ", labels, ", ")).joinToString("; ")
    }

    /** "com.google.android.apps.fitness" -> [fitness]; "in.amazon.mShop.android.shopping" -> [amazon, shop, shopping]. */
    fun packageWords(pkg: String): List<String> = pkg.split('.', '_').flatMap { it.split(CAMEL) }
        .map { it.lowercase(Locale.ROOT) }
        .filter { it.length >= 3 && it.none(Char::isDigit) && it !in NOISE }
        .distinct()

    /** "actions.intent.START_EXERCISE" (or custom.actions.intent.X) -> "start exercise". */
    private fun action(name: String): String? = name.substringAfterLast('.').uppercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() && it !in GENERIC_ACTIONS }?.replace('_', ' ')?.lowercase(Locale.ROOT)

    private fun words(s: String) = s.split(' ').count { it.isNotEmpty() }
}

/** Asserts for the pure profile parts; the "selfcheck" bridge command can run it on the phone. */
fun appCatalogSelfCheck(): String {
    val p = AppProfiler
    val none = ApplicationInfo.CATEGORY_UNDEFINED
    check(p.packageWords("com.google.android.apps.fitness") == listOf("fitness"))
    check(p.packageWords("in.amazon.mShop.android.shopping") == listOf("amazon", "shop", "shopping"))
    check(p.packageWords("com.fitbit.FitbitMobile") == listOf("fitbit"))
    check(p.packageWords("net.one97.paytm") == listOf("paytm"))
    check(p.packageWords("com.csam.icici.bank.imobile") == listOf("csam", "icici", "bank", "imobile"))
    check(p.profileOf("Fit", "com.google.android.apps.fitness", none, emptySet(),
        listOf("actions.intent.OPEN_APP_FEATURE", "actions.intent.START_EXERCISE"), listOf("Start workout", "Track activity"))
        == "Fit — fitness; does: start exercise; shortcuts: Start workout, Track activity")
    // words already in the label go; capabilities come in DOES order, whatever the set's order
    check(p.profileOf("iMobile", "com.csam.icici.bank.imobile", none, setOf("upi"), emptyList(), emptyList())
        == "iMobile — csam icici bank; does: UPI payments")
    check(p.profileOf("Spotify", "com.spotify.music", ApplicationInfo.CATEGORY_AUDIO, setOf("photos", "music"),
        emptyList(), listOf("Search", " search ", "")) == "Spotify — music audio; does: play music, view images; shortcuts: Search")
    check(p.profileOf("Maps", "com.google.android.apps.maps", ApplicationInfo.CATEGORY_MAPS, emptySet(), emptyList(), emptyList()) == "Maps")
    // trimming: whole items only, never past MAX_WORDS (9 words before the shortcuts, 3 per shortcut)
    val long = p.profileOf("Pay", "com.example.pay", none, setOf("upi", "calls"), emptyList(), (1..20).map { "Shortcut number $it" })
    check(long.split(' ').size == 33 && long.endsWith("; shortcuts: Shortcut number 1, Shortcut number 2, Shortcut number 3, " +
        "Shortcut number 4, Shortcut number 5, Shortcut number 6, Shortcut number 7, Shortcut number 8"))
    check(long.startsWith("Pay — example; does: phone calls, UPI payments; "))
    return "ok"
}
