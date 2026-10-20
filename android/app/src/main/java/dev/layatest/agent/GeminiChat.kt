package dev.layatest.agent

import org.json.JSONArray
import org.json.JSONObject

/** Selectors for the observed Gemini surface; toolbar "New chat" is deliberately ambiguous. */
internal object GeminiChat {
    private const val DRAWER = "assistant_robin_side_nav_host_drawer_container"
    private const val ACCOUNT = "assistant_action_robin_og_account_menu"
    private const val INPUT = "assistant_robin_input_collapsed_text_half_sheet"
    private val temporary = Regex("\\btemporary chats?\\b", RegexOption.IGNORE_CASE)
    private data class Entry(val node: JSONObject, val ancestors: List<JSONObject>)

    fun qualified(screen: Screen): Boolean = screen.pkg in setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox") &&
        entries(screen).any { it.node.optString("resource_id").contains("assistant_robin") }

    fun normalBlank(screen: Screen, ownedDraft: String? = null): Boolean {
        if (!qualified(screen)) return false
        val nodes = entries(screen).map { it.node }
        if (nodes.any { id(it) == DRAWER || temporary.containsMatchIn(own(it)) }) return false
        if (nodes.none { id(it) == ACCOUNT && it.optBoolean("enabled") }) return false
        if (nodes.any { id(it) in setOf("assistant_robin_user_message_text", "assistant_robin_text") && it.optString("text").isNotBlank() }) return false
        val inputs = nodes.filter { id(it) == INPUT && it.optBoolean("editable") && it.optBoolean("enabled") }
        return inputs.size == 1 && (blankInput(inputs.single()) ||
            (ownedDraft != null && inputs.single().optString("text") == ownedDraft))
    }

    /** At most Open sidebar → sidebar text New chat. No typing, toolbar toggle, or account access. */
    fun openNormalNew(ctx: SkillContext, ownedDraft: String? = null): Boolean {
        var screen = ctx.screen()
        if (!qualified(screen) || ctx.stopped) return false
        if (normalBlank(screen)) return true
        // Only this workflow's exact public prompt may be discarded when restarting its own new chat.
        if (entries(screen).any { id(it.node) == INPUT && it.node.optBoolean("editable") && !blankInput(it.node) &&
                (ownedDraft == null || it.node.optString("text") != ownedDraft) }) return false
        if (entries(screen).none { id(it.node) == DRAWER }) {
            val entry = entries(screen).firstOrNull { own(it.node).equals("Open sidebar", true) } ?: return false
            val target = target(screen, entry, "Open sidebar") ?: return false
            ctx.say("Opening Gemini's normal chat menu…")
            if (!tap(ctx, screen, target)) return false
            if (!ctx.waitUntil(3000) { screen = ctx.screen(); entries(screen).any { id(it.node) == DRAWER } }) return false
        }
        screen = ctx.screen()
        val newChat = normalNewChatTarget(screen) ?: return false
        ctx.say("Opening a normal Gemini chat…")
        if (!tap(ctx, screen, newChat)) return false
        return ctx.waitUntil(4000) { normalBlank(ctx.screen(), ownedDraft) }
    }

    /** Only the explicit text row under the observed sidebar container is a normal New chat action. */
    fun normalNewChatTarget(screen: Screen): ScreenCandidate? {
        if (!qualified(screen)) return null
        val entry = entries(screen).firstOrNull { item ->
            item.node.optString("text").trim().equals("New chat", true) &&
                item.ancestors.any { id(it) == DRAWER && it.optBoolean("visible") }
        } ?: return null
        return target(screen, entry, "New chat")
    }

    private fun id(node: JSONObject) = node.optString("resource_id").substringAfterLast('/')
    private fun own(node: JSONObject) = node.optString("text").ifBlank { node.optString("content_desc") }.trim()
    private fun blankInput(node: JSONObject): Boolean {
        val value = node.optString("text").trim()
        return value.isBlank() || value.equals("Ask Gemini", true) ||
            (node.optString("hint").isNotBlank() && value == node.optString("hint").trim())
    }
    private fun entries(screen: Screen): List<Entry> {
        val found = ArrayList<Entry>()
        fun visit(node: JSONObject, ancestors: List<JSONObject>) {
            if (node.optBoolean("visible") && node.optString("package") == screen.pkg) found += Entry(node, ancestors)
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { visit(it, ancestors + node) }
        }
        val windows = screen.state?.optJSONArray("windows") ?: return found
        for (i in 0 until windows.length()) windows.optJSONObject(i)?.let {
            if (it.optInt("type") == 1) it.optJSONObject("root")?.let { root -> visit(root, emptyList()) }
        }
        return found
    }
    private fun target(screen: Screen, entry: Entry, label: String): ScreenCandidate? {
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return null
        val action = (listOf(entry.node) + entry.ancestors.asReversed()).firstOrNull { node ->
            val b = node.optJSONArray("bounds")
            node.optString("package") == screen.pkg && node.optBoolean("visible") && node.optBoolean("enabled") &&
                node.optBoolean("clickable") && !node.optBoolean("editable") && b != null && b.length() == 4 &&
                b.optInt(2) > b.optInt(0) && b.optInt(3) > b.optInt(1) && b.optInt(3) - b.optInt(1) < height / 4
        } ?: return null
        val b = action.getJSONArray("bounds")
        return ScreenCandidate(label, action.optString("class").substringAfterLast('.'), true, false, false, false,
            false, false, false, true, IntArray(4) { b.getInt(it) }, id(action))
    }
    private fun tap(ctx: SkillContext, screen: Screen, target: ScreenCandidate): Boolean =
        !ctx.stopped && qualified(screen) && ctx.foreground() == screen.pkg && ctx.tap(target)

    fun selfCheck(): String {
        val pkg = "com.google.android.googlequicksearchbox"
        fun node(rid: String, text: String = "", desc: String = "", clickable: Boolean = false,
                 editable: Boolean = false, children: JSONArray = JSONArray()) = JSONObject()
            .put("package", pkg).put("resource_id", rid).put("text", text).put("content_desc", desc)
            .put("clickable", clickable).put("editable", editable).put("visible", true).put("enabled", true)
            .put("class", "android.view.View").put("bounds", JSONArray(listOf(0, 100, 900, 220))).put("children", children)
        fun screen(children: JSONArray) = Screen(pkg, emptyList(), emptyList(), JSONObject().put("screen", JSONArray(listOf(1080, 2400)))
            .put("windows", JSONArray().put(JSONObject().put("type", 1).put("root", node("assistant_robin_root", children = children)))))
        val input = node(INPUT, "Ask Gemini", editable = true)
        val toolbarToggle = node("", desc = "New chat", clickable = true)
        val home = screen(JSONArray().put(node(ACCOUNT)).put(input).put(toolbarToggle))
        check(normalBlank(home))
        check(normalNewChatTarget(home) == null)
        input.put("text", "Synthetic unsent draft")
        check(!normalBlank(home))
        check(normalBlank(home, "Synthetic unsent draft"))
        check(!normalBlank(home, "Another workflow's draft"))
        input.put("text", "Ask Gemini")
        val temp = screen(JSONArray().put(node(ACCOUNT)).put(input).put(node("", "Temporary chats are not saved.")))
        check(!normalBlank(temp))
        val leaf = node("", "New chat")
        val row = node("", clickable = true, children = JSONArray().put(leaf))
        val sidebar = screen(JSONArray().put(node(DRAWER, children = JSONArray().put(row))).put(toolbarToggle))
        check(!normalBlank(sidebar))
        check(normalNewChatTarget(sidebar)?.label == "New chat")
        check(normalNewChatTarget(screen(JSONArray().put(row))) == null)
        return "ok"
    }
}
