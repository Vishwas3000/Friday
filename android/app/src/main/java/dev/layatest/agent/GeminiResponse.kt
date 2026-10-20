package dev.layatest.agent

import org.json.JSONObject
import org.json.JSONArray

/** Copies a completed visible Gemini answer through its labelled response controls. */
internal object GeminiResponse {
    private const val MARKER = "FRIDAY_RESEARCH_RESULT_V1"
    private const val LIMIT = 24000
    private const val GENERIC_LIMIT = 48000
    private val copyLabels = setOf("copy", "copy response", "copy text")
    private val moreLabels = setOf("more", "more options", "response options", "more response options")
    private val responseRole = Regex("\\b(?:response|answer|model message|assistant message)\\b", RegexOption.IGNORE_CASE)
    private val userRole = Regex("\\b(?:user message|user query|query text|prompt|input|edit text)\\b", RegexOption.IGNORE_CASE)
    private val generating = Regex("^(?:stop (?:generating(?: response)?|response)|thinking(?:\\.\\.\\.|…)?)$", RegexOption.IGNORE_CASE)
    private data class Entry(val node: JSONObject, val ancestors: List<JSONObject>)

    /** [readClipboard] verifies a clip timestamp at least as recent as the supplied Copy tap time. */
    fun copy(ctx: SkillContext, readClipboard: (Long) -> String?, expectedAnchors: List<String>? = null,
             accept: ((String, List<String>) -> Boolean)? = null): String? {
        var openedResponseMenu = false
        var keyboardDismissed = false
        var anchors = expectedAnchors?.filter { it.isNotBlank() }.orEmpty()
        var scrolls = 0
        while (!ctx.stopped) {
            val screen = ctx.screen()
            if (!GeminiChat.qualified(screen) || ctx.foreground() != screen.pkg) return null
            val entries = entries(screen)
            if (entries.any { busyControl(it) }) return null
            if (!keyboardDismissed && keyboardShowing(screen)) {
                keyboardDismissed = true
                ctx.say("Closing the keyboard to collect Gemini's answer…")
                if (!ctx.back() || !ctx.waitUntil(1800) {
                        val current = ctx.screen()
                        GeminiChat.qualified(current) && !keyboardShowing(current)
                    }) return null
                continue
            }
            val currentAnchors = responseAnchors(entries)
            if (expectedAnchors == null && currentAnchors.isNotEmpty()) anchors = (anchors + currentAnchors).distinct().take(32)
            val control = entries.filter { entry ->
                val value = label(entry.node).lowercase()
                actionNode(entry) != null && value in copyLabels &&
                    (openedResponseMenu || responseActionContext(entry))
            }.maxByOrNull { it.node.optJSONArray("bounds")?.optInt(1) ?: 0 }
            if (control != null) {
                if (anchors.isEmpty()) return null
                ctx.say("Copying the completed Gemini answer…")
                val notBefore = System.currentTimeMillis()
                if (!tap(ctx, screen, actionNode(control) ?: return null, label(control.node))) return null
                if (ctx.stopped) return null
                val raw = readClipboard(notBefore) ?: return null
                val copied = if (accept == null) raw.trim() else raw
                return copied.takeIf { acceptedClipboard(it, anchors, accept) }
            }
            if (!openedResponseMenu) {
                val more = entries.filter { actionNode(it) != null && label(it.node).lowercase() in moreLabels && responseActionContext(it) }
                    .maxByOrNull { it.node.optJSONArray("bounds")?.optInt(1) ?: 0 }
                if (more != null) {
                    if (!tap(ctx, screen, actionNode(more) ?: return null, label(more.node))) return null
                    openedResponseMenu = true
                    continue
                }
            }
            if (openedResponseMenu || scrolls++ >= 2 || !ctx.scroll("down")) return null
        }
        return null
    }

    fun isGenerating(screen: Screen): Boolean = entries(screen).any(::busyControl)

    fun clipboardTimestampMatches(copiedAt: Long, notBefore: Long, readAt: Long): Boolean =
        notBefore > 0 && readAt >= notBefore && copiedAt in notBefore..readAt

    private fun keyboardShowing(screen: Screen): Boolean {
        val windows = screen.state?.optJSONArray("windows") ?: return false
        return (0 until windows.length()).any { index -> windows.optJSONObject(index)?.let {
            it.optInt("type") == 2 && it.optJSONObject("root")?.optBoolean("visible") == true
        } == true }
    }

    /** Gemini can restore a long conversation at its beginning instead of its completed footer. */
    fun scrollToEnd(ctx: SkillContext): Boolean {
        val screen = ctx.screen()
        if (!GeminiChat.qualified(screen) || ctx.stopped) return false
        val button = entries(screen).firstOrNull {
            it.node.optString("resource_id").substringAfterLast('/') == "assistant_robin_scroll_to_bottom_button" &&
                actionNode(it) != null
        } ?: return false
        ctx.say("Finding the end of Gemini's answer…")
        return tap(ctx, screen, actionNode(button) ?: return false, "Scroll to bottom")
    }

    private fun words(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun metadata(node: JSONObject) = words(node.optString("resource_id").substringAfterLast('/') + " " + node.optString("hint"))
    private fun label(node: JSONObject) = node.optString("text").ifBlank { node.optString("content_desc") }.trim()
    private fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun responseActionContext(entry: Entry): Boolean = (entry.ancestors + entry.node).any {
        it.optString("resource_id").substringAfterLast('/') in setOf(
            "assistant_robin_content_actions_scroll_view",
            "assistant_robin_message_content_actions_options_copy_content",
            "assistant_robin_message_content_actions_options_button")
    }
    private fun responseContext(entry: Entry): Boolean {
        val lineage = entry.ancestors + entry.node
        val context = lineage.joinToString(" ") { metadata(it) }
        val knownResponse = lineage.any { node ->
            when (node.optString("resource_id").substringAfterLast('/')) {
                "assistant_robin_text", "assistant_robin_message_content" -> true
                "assistant_robin_content_message_container", "content_message_container" -> hasResponseText(node)
                else -> false
            }
        }
        return (knownResponse || responseRole.containsMatchIn(context)) && !userRole.containsMatchIn(context)
    }
    private fun hasResponseText(node: JSONObject): Boolean {
        if (node.optString("resource_id").substringAfterLast('/') == "assistant_robin_text") return true
        val children = node.optJSONArray("children") ?: return false
        return (0 until children.length()).any { children.optJSONObject(it)?.let(::hasResponseText) == true }
    }
    private fun usable(node: JSONObject) = node.optBoolean("visible") && node.optBoolean("enabled") &&
        !node.optBoolean("editable") && node.optBoolean("clickable")

    /** Compose often puts the explicit label on a non-clickable child of its actual button. */
    private fun actionNode(entry: Entry): JSONObject? {
        val labelBounds = entry.node.optJSONArray("bounds") ?: return null
        val labelHeight = labelBounds.optInt(3) - labelBounds.optInt(1)
        if (labelHeight <= 0) return null
        return (listOf(entry.node) + entry.ancestors.asReversed()).firstOrNull { node ->
            val bounds = node.optJSONArray("bounds")
            usable(node) && bounds != null && bounds.length() == 4 &&
                bounds.optInt(2) > bounds.optInt(0) && bounds.optInt(3) > bounds.optInt(1) &&
                bounds.optInt(3) - bounds.optInt(1) <= labelHeight * 3
        }
    }
    private fun busyControl(entry: Entry): Boolean = generating.matches(label(entry.node)) &&
        (entry.ancestors + entry.node).none { it.optString("resource_id").substringAfterLast('/') in
            setOf("assistant_robin_text", "assistant_robin_user_message_text") } &&
        !entry.node.optBoolean("editable") && !userRole.containsMatchIn(metadata(entry.node)) &&
        (actionNode(entry) != null || Regex("\\b(?:stop|generating|thinking)\\b", RegexOption.IGNORE_CASE).containsMatchIn(metadata(entry.node)))

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
            (node.optString("resource_id").substringAfterLast('/') == "assistant_robin_text" || (node.optJSONArray("children")?.length() ?: 0) == 0) && responseContext(entry)
    }.map { normalized(label(it.node)).take(240) }.filter {
        it.length >= 24 && !it.startsWith("Research public travel information", true) &&
            !it.contains("At the end output $MARKER", true) &&
            !it.replace(Regex("[\\p{Cf}\\s]"), "").matches(Regex("FRIDAY_RESEARCH_(?:END_[a-f0-9]+|RESULT_V1)"))
    }.distinct().take(32)

    private fun acceptedClipboard(copied: String, anchors: List<String>, accept: ((String, List<String>) -> Boolean)?): Boolean {
        if (accept == null) return validClipboard(copied, anchors)
        return copied.isNotBlank() && copied.length <= GENERIC_LIMIT && anchors.isNotEmpty() &&
            runCatching { accept(copied, anchors) }.getOrDefault(false)
    }

    private fun validClipboard(copied: String, anchors: List<String>): Boolean {
        if (copied.length !in 100..LIMIT || copied.indexOf(MARKER) < 0 || copied.indexOf(MARKER) != copied.lastIndexOf(MARKER) ||
            !Regex("\\bNepal\\b", RegexOption.IGNORE_CASE).containsMatchIn(copied) ||
            copied.startsWith("Research public travel information", true) ||
            copied.contains("At the end output $MARKER", true) || ResearchWorkflow.parseProposal(copied) == null) return false
        val value = normalized(copied)
        return anchors.any { it.length >= 24 && value.contains(it) }
    }

    private fun tap(ctx: SkillContext, screen: Screen, node: JSONObject, controlLabel: String): Boolean {
        if (ctx.stopped || !GeminiChat.qualified(screen) || ctx.foreground() != screen.pkg || !usable(node)) return false
        val bounds = node.optJSONArray("bounds") ?: return false
        if (bounds.length() != 4 || bounds.optInt(2) <= bounds.optInt(0) || bounds.optInt(3) <= bounds.optInt(1)) return false
        val target = ScreenCandidate(controlLabel, node.optString("class").substringAfterLast('.'), true,
            node.optBoolean("long_clickable"), false, node.optBoolean("scrollable"), node.optBoolean("checkable"),
            node.optBoolean("checked"), node.optBoolean("selected"), true, IntArray(4) { bounds.getInt(it) },
            node.optString("resource_id").substringAfterLast('/'))
        return ctx.tap(target)
    }

    fun selfCheck(): String {
        check(clipboardTimestampMatches(100, 100, 200))
        check(clipboardTimestampMatches(200, 100, 200))
        check(!clipboardTimestampMatches(99, 100, 200))
        check(!clipboardTimestampMatches(201, 100, 200))
        check(!clipboardTimestampMatches(0, 100, 200))
        check(!clipboardTimestampMatches(100, 100, 99))
        check(!clipboardTimestampMatches(100, 0, 200))
        val anchor = "A substantive response about Mongolia travel."
        val genericAnswer = "$anchor\nFRIDAY_RESEARCH_END_" + "a".repeat(32)
        val accept: (String, List<String>) -> Boolean = { copied, anchors -> anchors.all { copied.contains(it) } }
        check(acceptedClipboard(genericAnswer, listOf(anchor), accept))
        check(!acceptedClipboard(genericAnswer, listOf(anchor), null))
        check(!acceptedClipboard(genericAnswer, emptyList(), accept))
        check(!acceptedClipboard("x".repeat(GENERIC_LIMIT + 1), listOf(anchor), { _, _ -> true }))
        check(!acceptedClipboard(genericAnswer, listOf(anchor), { _, _ -> error("rejected") }))
        fun node(rid: String, text: String) = JSONObject().put("resource_id", rid).put("text", text)
            .put("visible", true).put("enabled", true).put("editable", false).put("children", JSONArray())
        val response = Entry(node("assistant_robin_text", anchor), emptyList())
        check(responseAnchors(listOf(response)) == listOf(anchor))
        check(responseAnchors(listOf(Entry(node("assistant_robin_text", "FRIDAY_RESEARCH_END_" + "a".repeat(32)), emptyList()))).isEmpty())
        check(!busyControl(Entry(node("assistant_robin_text", "Thinking carefully about the topic is useful."), emptyList())))
        check(busyControl(Entry(node("stop_button", "Stop generating response"), emptyList())))
        check(!busyControl(Entry(node("assistant_robin_text", "Thinking"), emptyList())))
        check(responseActionContext(Entry(node("assistant_robin_message_content_actions_options_copy_content", "Copy"), emptyList())))
        check(responseActionContext(Entry(node("", "Copy"), listOf(node("assistant_robin_content_actions_scroll_view", "")))))
        check(!responseActionContext(Entry(node("code_block_copy", "Copy"), listOf(node("assistant_robin_message_content", "")))))
        check(!responseActionContext(Entry(node("toolbar_more", "More options"), emptyList())))
        val ime = JSONObject().put("type", 2).put("root", node("keyboard", ""))
        val keyboardScreen = Screen("com.google.android.googlequicksearchbox", emptyList(), emptyList(),
            JSONObject().put("windows", JSONArray().put(ime)))
        check(keyboardShowing(keyboardScreen))
        ime.getJSONObject("root").put("visible", false)
        check(!keyboardShowing(keyboardScreen))
        return "ok"
    }
}
