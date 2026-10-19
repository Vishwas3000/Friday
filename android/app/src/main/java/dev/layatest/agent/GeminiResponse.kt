package dev.layatest.agent

import org.json.JSONObject

/** Copies a completed visible Gemini answer through its labelled response controls. */
internal object GeminiResponse {
    private const val MARKER = "FRIDAY_RESEARCH_RESULT_V1"
    private const val LIMIT = 24000
    private val copyLabels = setOf("copy", "copy response", "copy text")
    private val moreLabels = setOf("more", "more options", "response options", "more response options")
    private val responseRole = Regex("\\b(?:response|answer|model message|assistant message)\\b", RegexOption.IGNORE_CASE)
    private val userRole = Regex("\\b(?:user message|user query|query text|prompt|input|edit text)\\b", RegexOption.IGNORE_CASE)
    private val generating = Regex("\\bstop\\b.*\\b(?:response|generat)|\\bthinking\\b", RegexOption.IGNORE_CASE)
    private data class Entry(val node: JSONObject, val ancestors: List<JSONObject>)

    /** [readClipboard] may foreground Friday to read; it is called only after a verified Copy tap. */
    fun copy(ctx: SkillContext, readClipboard: () -> String?): String? {
        var openedResponseMenu = false
        var anchors = emptyList<String>()
        var scrolls = 0
        while (!ctx.stopped) {
            val screen = ctx.screen()
            if (!ResearchWorkflow.qualified(screen) || ctx.foreground() != screen.pkg) return null
            val entries = entries(screen)
            if (entries.any { generating.containsMatchIn(label(it.node)) }) return null
            val currentAnchors = responseAnchors(entries)
            if (currentAnchors.isNotEmpty()) anchors = currentAnchors
            val control = entries.filter { entry ->
                val value = label(entry.node).lowercase()
                usable(entry.node) && value in copyLabels &&
                    (openedResponseMenu || responseContext(entry) || value == "copy response")
            }.maxByOrNull { it.node.optJSONArray("bounds")?.optInt(1) ?: 0 }
            if (control != null) {
                if (anchors.isEmpty()) return null
                ctx.say("Copying the completed Gemini answer…")
                if (!tap(ctx, screen, control.node)) return null
                if (ctx.stopped) return null
                val copied = readClipboard()?.trim() ?: return null
                return copied.takeIf { validClipboard(it, anchors) }
            }
            if (!openedResponseMenu) {
                val more = entries.filter { usable(it.node) && label(it.node).lowercase() in moreLabels && responseContext(it) }
                    .maxByOrNull { it.node.optJSONArray("bounds")?.optInt(1) ?: 0 }
                if (more != null) {
                    if (!tap(ctx, screen, more.node)) return null
                    openedResponseMenu = true
                    continue
                }
            }
            if (openedResponseMenu || scrolls++ >= 2 || !ctx.scroll("down")) return null
        }
        return null
    }

    private fun words(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun metadata(node: JSONObject) = words(node.optString("resource_id").substringAfterLast('/') + " " + node.optString("hint"))
    private fun label(node: JSONObject) = node.optString("text").ifBlank { node.optString("content_desc") }.trim()
    private fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun responseContext(entry: Entry): Boolean {
        val lineage = entry.ancestors + entry.node
        val context = lineage.joinToString(" ") { metadata(it) }
        val knownResponse = lineage.any { it.optString("resource_id").substringAfterLast('/') == "assistant_robin_text" }
        return (knownResponse || responseRole.containsMatchIn(context)) && !userRole.containsMatchIn(context)
    }
    private fun usable(node: JSONObject) = node.optBoolean("visible") && node.optBoolean("enabled") &&
        !node.optBoolean("editable") && node.optBoolean("clickable")

    private fun entries(screen: Screen): List<Entry> {
        val found = ArrayList<Entry>()
        fun walk(node: JSONObject, ancestors: List<JSONObject>) {
            if (node.optBoolean("visible") && node.optString("package") == screen.pkg) found += Entry(node, ancestors)
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { walk(it, ancestors + node) }
        }
        val windows = screen.state?.optJSONArray("windows") ?: return found
        for (i in 0 until windows.length()) windows.optJSONObject(i)?.let {
            if (it.optInt("type") == 1) it.optJSONObject("root")?.let { root -> walk(root, emptyList()) }
        }
        return found
    }

    private fun responseAnchors(entries: List<Entry>): List<String> = entries.filter { entry ->
        val node = entry.node
        !node.optBoolean("editable") && !Regex("Button|EditText", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("class")) &&
            (node.optJSONArray("children")?.length() ?: 0) == 0 && responseContext(entry)
    }.map { normalized(label(it.node)).take(240) }.filter {
        it.length >= 24 && !it.startsWith("Research public travel information", true) &&
            !it.contains("At the end output $MARKER", true)
    }.distinct().takeLast(8)

    private fun validClipboard(copied: String, anchors: List<String>): Boolean {
        if (copied.length !in 100..LIMIT || copied.indexOf(MARKER) < 0 || copied.indexOf(MARKER) != copied.lastIndexOf(MARKER) ||
            !Regex("\\bNepal\\b", RegexOption.IGNORE_CASE).containsMatchIn(copied) ||
            copied.startsWith("Research public travel information", true) ||
            copied.contains("At the end output $MARKER", true) || ResearchWorkflow.parseProposal(copied) == null) return false
        val value = normalized(copied)
        return anchors.any { it.length >= 24 && value.contains(it) }
    }

    private fun tap(ctx: SkillContext, screen: Screen, node: JSONObject): Boolean {
        if (ctx.stopped || !ResearchWorkflow.qualified(screen) || ctx.foreground() != screen.pkg || !usable(node)) return false
        val bounds = node.optJSONArray("bounds") ?: return false
        if (bounds.length() != 4 || bounds.optInt(2) <= bounds.optInt(0) || bounds.optInt(3) <= bounds.optInt(1)) return false
        val target = ScreenCandidate(label(node), node.optString("class").substringAfterLast('.'), true,
            node.optBoolean("long_clickable"), false, node.optBoolean("scrollable"), node.optBoolean("checkable"),
            node.optBoolean("checked"), node.optBoolean("selected"), true, IntArray(4) { bounds.getInt(it) },
            node.optString("resource_id").substringAfterLast('/'))
        return ctx.tap(target)
    }
}
