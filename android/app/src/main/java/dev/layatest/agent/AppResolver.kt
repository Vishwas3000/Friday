package dev.layatest.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/** One launcher entry. [activity] matters for packages with several entries (Amazon / Amazon Pay share one). */
data class AppInfo(val label: String, val pkg: String, val activity: String)

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

    /** An app named in the request ("open spotify", "on YT Music"): an alias, else the longest installed label as whole words. */
    fun named(request: String): AppInfo? {
        ALIASES.entries.firstOrNull { Regex("\\b${it.key}\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) }
            ?.let { (_, pkg) -> installed.firstOrNull { it.pkg == pkg }?.let { return it } }
        return installed
            .filter { it.label.isNotBlank() && Regex("\\b${Regex.escape(it.label)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) }
            .maxByOrNull { it.label.length }
            ?: typo(request)
    }

    /** "amazzon" -> Amazon: a one-word label one letter off (two for 8+ letters), for words of 5+ letters that aren't
     *  everyday words ("alarm" must not find an app called "Alarmy"). The closest wins. */
    private fun typo(request: String): AppInfo? = Regex("[a-z]{5,}").findAll(request.lowercase()).map { it.value }
        .filter { it !in EVERYDAY }
        .flatMap { w -> installed.filter { ' ' !in it.label && it.label.length >= 5 }.map { a -> a to editDistance(w, a.label.lowercase()) }
            .filter { (a, d) -> d in 1..(if (a.label.length >= 8) 2 else 1) } }
        .minByOrNull { it.second }?.first

    private fun category(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)

    companion object {
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
            "youtube music" to "com.google.android.apps.youtube.music",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
        )

        private val SYSTEM_CATEGORY = mapOf(
            "play_music" to ApplicationInfo.CATEGORY_AUDIO, "play_video" to ApplicationInfo.CATEGORY_VIDEO,
            "navigate" to ApplicationInfo.CATEGORY_MAPS, "photos" to ApplicationInfo.CATEGORY_IMAGE,
        )
    }
}
