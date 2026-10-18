package dev.layatest.agent

/**
 * Every action Friday takes goes through [verdict] (D-025, D-026):
 *  - AUTO  safe navigation (scroll, back, ordinary taps) runs on its own;
 *  - ASK   anything that could send, pay, delete, call or commit waits for the owner's Run. Typing runs on its
 *          own (owner, 2026-10-17: "can the app input the texts"): it commits nothing until a Send/Pay tap, which asks;
 *  - BLOCK inside money apps Friday is read-only: no typing, no Pay/Send/Transfer, whatever the owner taps.
 * Auth prompts (fingerprint, screen lock, UPI PIN) are the owner's: Friday waits and never acts on them.
 */
object Safety {
    enum class Verdict { AUTO, ASK, BLOCK }

    val RISKY = Regex(
        "\\b(send|pay|paid|buy|purchase|order|checkout|book|delete|remove|uninstall|erase|reset|call|dial|post|add|" +
            // "dismiss" an upcoming alarm, pick another delivery "address" (Clock and Zepto, suite 2026-10-18)
            "dismiss|address|" +
            "share|confirm|transfer|subscribe|install|accept|allow|proceed|request|withdraw|invest|recharge|" +
            "place order|sign out|log ?out|unlink|block)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val AUTH = Regex(
        "(fingerprint|use screen lock|verify (it's|its|it is) you|confirm (it's|its|it is) you|enter (your )?(upi )?pin|" +
            "enter (your )?passcode|use your pin|touch the (fingerprint )?sensor|face unlock|biometric)",
        RegexOption.IGNORE_CASE,
    )

    /** Apps where a plain tap commits: in the dialer, tapping a contact or favourite places the call. */
    private val COMMIT_ON_TAP = setOf("com.google.android.dialer")

    fun isMoneyApp(pkg: String?) = pkg != null && pkg in AppResolver.MONEY

    fun verdict(op: String, target: ScreenCandidate?, foreground: String?): Pair<Verdict, String> {
        val label = target?.label.orEmpty()
        val tap = op == "CLICK" || op == "LONG_PRESS"
        if (isMoneyApp(foreground)) {
            if (op == "INPUT_TEXT") return Verdict.BLOCK to "Friday never types in money apps"
            if (tap && RISKY.containsMatchIn(label)) return Verdict.BLOCK to "Friday never taps \"$label\" in a money app"
            if (tap && label.isBlank()) return Verdict.ASK to "unlabelled button in a money app"
        }
        if (tap && foreground in COMMIT_ON_TAP) return Verdict.ASK to "a tap here can place a call"
        // an alarm's time opens its picker, and OK there saves it: "go to bedtime" got that far (2026-10-18)
        if (tap && foreground == "com.google.android.deskclock" && Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(label))
            return Verdict.ASK to "this could change an alarm"
        if (tap && RISKY.containsMatchIn(label)) return Verdict.ASK to "\"$label\" could send, pay or change something"
        return Verdict.AUTO to ""
    }

    /** A screen asking the owner to authenticate: the system's own prompt, or an app's lock/PIN screen. */
    fun isAuthPrompt(foreground: String?, texts: List<String>): Boolean =
        texts.any { AUTH.containsMatchIn(it) } ||
            (foreground == "com.android.systemui" && texts.any { it.contains("unlock", ignoreCase = true) })
}
