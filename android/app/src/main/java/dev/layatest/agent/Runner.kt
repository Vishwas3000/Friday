package dev.layatest.agent

/**
 * The payload rules Laya can't produce itself (scripts/live_adb.py pick_app / pick_text), the
 * "System 2" half: the app to OPEN_APP (an installed label named in the goal) and the text to
 * INPUT_TEXT (a quoted span in the goal). Pure, so they port straight across.
 */

/** Installed app whose label appears earliest in the goal (longer label wins ties). */
fun pickApp(goal: String, apps: List<Pair<String, String>>): Pair<String, String>? {
    val g = goal.lowercase()
    val hits = apps.filter { it.first.isNotEmpty() && Regex("\\b${Regex.escape(it.first.lowercase())}\\b").containsMatchIn(g) }
    return hits.minWithOrNull(compareBy({ g.indexOf(it.first.lowercase()) }, { -it.first.length }))
}

/** First quoted span in the goal not already typed (straight or curly quotes). */
fun pickText(goal: String, typed: List<String>): String? {
    for (m in Regex("[\"“]([^\"”]+)[\"”]").findAll(goal)) {
        val s = m.groupValues[1]
        if (s !in typed) return s
    }
    return null
}
