package dev.layatest.agent

import org.json.JSONArray
import org.json.JSONObject

internal data class MessageReadRequest(
    val original: String,
    val apps: List<AppInfo>,
    val requestedApp: String?,
    val recipient: String?,
    val limit: Int = 8,
)

internal data class MessageReadCapture(
    val source: String, val count: Int, val scope: String, val truncated: Boolean,
    val evidence: JSONObject = JSONObject(),
)

/** Captures one verified visible app viewport. Summarization is a separate coordinator step. */
internal object ReadMessages {
    private const val SOURCE_LIMIT = 1450
    private val verbs = Regex("^(?:(?:please|can you|could you)\\s+)*(?:check|read|show|review|summari[sz]e|catch me up)\\b", RegexOption.IGNORE_CASE)
    private val messages = Regex("\\b(?:dms?|direct messages?|messages?|chats?|inbox|conversations?|emails?|mail)\\b", RegexOption.IGNORE_CASE)
    private val aliases = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "instagram" to listOf("com.instagram.android"),
        "insta" to listOf("com.instagram.android"),
        "gmail" to listOf("com.google.android.gm"),
        "google mail" to listOf("com.google.android.gm"),
        "sms" to listOf("com.google.android.apps.messaging"),
    )
    private val genericLabels = setOf("messages", "mail", "email", "chat", "messenger")
    private val search = Regex("\\b(?:search|query|find)\\b", RegexOption.IGNORE_CASE)
    private val messageRole = Regex("\\b(?:message|msg|bubble|email body|mail body)\\b", RegexOption.IGNORE_CASE)
    private val previewRole = Regex("\\b(?:snippet|preview|subject|last message|conversations? row message)\\b", RegexOption.IGNORE_CASE)
    private val headerRole = Regex("\\b(?:conversation|contact|chat|thread|toolbar|recipient|action bar|top bar|navigation bar)\\b.*\\b(?:name|title|header)\\b|\\brecipient chip\\b|^title$", RegexOption.IGNORE_CASE)
    private val controls = Regex("\\b(?:toolbar|header|title|menu|navigation|avatar|badge|timestamp|date|status|search|tab|button|recipient|sender|subject|input|entry|compose|editor)\\b", RegexOption.IGNORE_CASE)
    private val mutations = Regex("\\b(?:reply|react|reaction|like|heart|emoji|emoticon|vote|mute|unmute|archive|trash|delete|remove|forward|send|post|follow|unfollow|block|unblock|compose|record|attach|attachment|camera|microphone|sticker|gif|download|save|pin|unpin|report|clear|edit|join|leave|subscribe|unsubscribe|call|dial|voicecall|videocall)\\b|\\bmark\\b.*\\b(?:read|unread)\\b", RegexOption.IGNORE_CASE)

    private fun words(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun whole(needle: String, text: String) = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(needle)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun requestFrom(request: String, installed: List<AppInfo>): MessageReadRequest? {
        val originalCommand = request.substringBefore('"').substringBefore('“').substringBefore(':').trim()
        val action = taskPart(originalCommand).trim()
        if (Regex("^summari[sz]e\\s+(?:this|the following)\\b", RegexOption.IGNORE_CASE).containsMatchIn(action)) return null
        if (!verbs.containsMatchIn(action) || !messages.containsMatchIn(action)) return null
        val found = installed.filter { app ->
            app.label.isNotBlank() && whole(app.label, originalCommand) &&
                (app.label.lowercase() !in genericLabels || Regex("\\b(?:in|on|using)\\s+(?:the\\s+)?${Regex.escape(app.label)}\\b|\\b${Regex.escape(app.label)}\\s+app\\b", RegexOption.IGNORE_CASE).containsMatchIn(originalCommand))
        }.toMutableList()
        val longer = found.toList()
        found.removeAll { app -> longer.any { other -> other.label.length > app.label.length && whole(app.label, other.label) } }
        for ((alias, packages) in aliases) if (whole(alias, originalCommand) && found.none { it.pkg in packages }) {
            installed.filter { it.pkg in packages }.forEach { found += it }
        }
        val apps = found.distinctBy { it.pkg }
        val explicit = Regex("\\b(?:in|on|using)\\s+(?:the\\s+)?(.+?)(?=\\s+(?:app|with|from|for|about|by|and)\\b|$)", RegexOption.IGNORE_CASE)
            .find(originalCommand)?.groupValues?.get(1)?.trim()?.trimEnd(',', '.', '?')
        val missingAlias = aliases.keys.firstOrNull { whole(it, originalCommand) }
        val appName = apps.singleOrNull()?.label ?: explicit ?: missingAlias
        val person = Regex("\\b(?:from|with|by)\\s+(.+?)(?=\\s+(?:in|on|from|using|about|and|last|latest|recent|first)\\b|$)", RegexOption.IGNORE_CASE)
            .find(action)?.groupValues?.get(1)?.trim()?.replace(Regex("^(?:my|the)\\s+", RegexOption.IGNORE_CASE), "")
            ?.trimEnd(',', '.', '?')?.takeUnless { it.isBlank() || it.lowercase() in setOf("any app", "all apps", "everyone", "anyone") }
        val count = Regex("\\b(?:last|latest|recent|first)\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE).find(action)
            ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 12) ?: 8
        return MessageReadRequest(request, apps, appName, person, count)
    }

    private fun appNodes(screen: Screen): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        fun visit(node: JSONObject) {
            if (!node.optBoolean("visible") || node.optString("package") != screen.pkg) return
            found += node
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) visit(children.getJSONObject(i))
        }
        val windows = screen.state?.optJSONArray("windows") ?: return found
        for (i in 0 until windows.length()) windows.getJSONObject(i).let { if (it.optInt("type") == 1) visit(it.getJSONObject("root")) }
        return found
    }

    private fun ownText(node: JSONObject) = node.optString("text").ifBlank {
        if ((node.optJSONArray("children")?.length() ?: 0) == 0) node.optString("content_desc") else ""
    }.trim()
    private fun role(node: JSONObject) = words(node.optString("resource_id").substringAfterLast('/') + " " + node.optString("hint"))
    private fun isHeader(node: JSONObject) = headerRole.containsMatchIn(role(node)) &&
        !Regex("\\b(?:row|list|preview|snippet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(role(node))

    internal fun headerEvidenceValid(node: JSONObject) = isHeader(node)
    internal fun itemEvidenceValid(node: JSONObject, conversation: Boolean): Boolean = !node.optBoolean("editable") &&
        !Regex("Button|ImageButton|EditText", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("class")) &&
        if (conversation) messageRole.containsMatchIn(role(node)) && !controls.containsMatchIn(role(node))
        else previewRole.containsMatchIn(role(node))

    internal fun inboxEvidenceValid(node: JSONObject): Boolean {
        val inboxLabels = setOf("inbox", "chats", "messages", "direct", "mail")
        return Regex("\\b(?:inbox|(?:conversations?|chats?|threads?|messages?) list)\\b", RegexOption.IGNORE_CASE).containsMatchIn(role(node)) ||
            (isHeader(node) && ownText(node).lowercase() in inboxLabels) ||
            (node.optBoolean("selected") && Regex("\\b(?:tab|navigation)\\b", RegexOption.IGNORE_CASE).containsMatchIn(role(node)) && ownText(node).lowercase() in inboxLabels)
    }

    fun alreadyReadable(screen: Screen, app: AppInfo, recipient: String?, limit: Int): Boolean {
        if (screen.pkg != app.pkg) return false
        if (recipient != null) return recipientVisible(screen, recipient) &&
            extract(screen, app, recipient, limit).let { it.count > 0 && it.scope.startsWith("visible conversation text") }
        return inboxVisible(screen) && extract(screen, app, null, limit).count > 0
    }

    private fun inboxVisible(screen: Screen): Boolean {
        val nodes = appNodes(screen)
        val inboxLabels = setOf("inbox", "chats", "messages", "direct", "mail")
        if (nodes.any { isHeader(it) && ownText(it).isNotBlank() && ownText(it).lowercase() !in inboxLabels }) return false
        val inboxSurface = nodes.any(::inboxEvidenceValid)
        return inboxSurface && nodes.any { !it.optBoolean("editable") && previewRole.containsMatchIn(role(it)) && ownText(it).isNotBlank() }
    }

    private fun recipientHeader(screen: Screen, recipient: String): JSONObject? {
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return null
        return appNodes(screen).firstOrNull { node ->
            val top = node.optJSONArray("bounds")?.optInt(1, height) ?: height
            top < height / 3 && isHeader(node) &&
                ownText(node).removePrefix("@").equals(recipient.removePrefix("@"), true)
        }
    }

    fun recipientVisible(screen: Screen, recipient: String) = recipientHeader(screen, recipient) != null

    fun screenProblem(screen: Screen, app: AppInfo): String? = when {
        screen.pkg != app.pkg -> "The selected app's foreground screen wasn't verified."
        appNodes(screen).isEmpty() -> "Android isn't exposing the selected app's screen to Friday. No messages were read."
        Safety.isAuthPrompt(screen.pkg, appNodes(screen).map(::ownText)) -> "Unlock the selected app to read messages."
        else -> null
    }

    fun captureProblem(screen: Screen, app: AppInfo, recipient: String?, limit: Int): String? = when {
        screenProblem(screen, app) != null -> screenProblem(screen, app)
        recipient != null && !recipientVisible(screen, recipient) -> "The requested conversation header wasn't verified. No messages were read."
        !alreadyReadable(screen, app, recipient, limit) -> "Readable message boundaries weren't verified on this screen. No summary was produced."
        else -> null
    }

    fun observe(screen: Screen, app: AppInfo, recipient: String?, limit: Int = 8): MessageReadCapture? =
        if (captureProblem(screen, app, recipient, limit) == null) extract(screen, app, recipient, limit) else null

    private fun nodeEvidence(node: JSONObject) = JSONObject().put("text", ownText(node))
        .put("package", node.optString("package")).put("resource_id", node.optString("resource_id"))
        .put("hint", node.optString("hint")).put("class", node.optString("class"))
        .put("editable", node.optBoolean("editable")).put("selected", node.optBoolean("selected"))
        .put("bounds", node.optJSONArray("bounds"))

    fun extract(screen: Screen, app: AppInfo, recipient: String?, limit: Int = 8): MessageReadCapture {
        if (screen.pkg != app.pkg) return MessageReadCapture("", 0, "requested app not verified", false)
        val nodes = appNodes(screen).filter { node ->
            !node.optBoolean("editable") && ownText(node).isNotBlank() &&
                !Regex("Button|ImageButton|EditText", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("class"))
        }.sortedWith(compareBy({ it.optJSONArray("bounds")?.optInt(1) ?: 0 }, { it.optJSONArray("bounds")?.optInt(0) ?: 0 }))
            .distinctBy { it.optJSONArray("bounds")?.toString().orEmpty() + "|" + ownText(it) }
        val messageNodes = nodes.filter { messageRole.containsMatchIn(role(it)) && !controls.containsMatchIn(role(it)) }
        val previews = nodes.filter { previewRole.containsMatchIn(role(it)) }
        val (selected, scope) = when {
            recipient != null && messageNodes.isNotEmpty() -> messageNodes to "visible conversation text; requested contact header checked"
            recipient == null && previews.isNotEmpty() -> previews to "visible inbox previews only"
            recipient == null && messageNodes.isNotEmpty() -> messageNodes to "visible message-related screen text; inbox boundaries unverified"
            else -> nodes.filter { !controls.containsMatchIn(role(it)) && ownText(it) != recipient } to
                "visible screen text only; message boundaries unverified"
        }
        val items = JSONArray()
        val observedItems = JSONArray()
        val source = JSONObject().put("app", app.label).put("package", app.pkg).put("scope", scope)
            .put("order", "visible top-to-bottom screen order").put("speaker_attribution", "not verified; sent and received text may both be present")
            .put("items", items)
        recipient?.let { source.put("requested_conversation", it) }
        var truncated = selected.size > limit
        for (node in selected.take(limit.coerceIn(1, 12))) {
            val full = ownText(node)
            val text = full.take(360)
            if (text.length < full.length) truncated = true
            items.put(text)
            if (source.toString().length > SOURCE_LIMIT) {
                items.remove(items.length() - 1)
                truncated = true
                break
            }
            observedItems.put(nodeEvidence(node).put("captured_text", text))
        }
        val evidence = JSONObject().put("screen_text", appNodes(screen).map(::ownText).filter(String::isNotBlank).joinToString("\n"))
            .put("message_items", observedItems).put("screen_height", screen.state?.optJSONArray("screen")?.optInt(1))
            .put("inbox_visible", recipient == null && inboxVisible(screen))
            .put("inbox_fields", JSONArray().also { proof -> appNodes(screen).filter(::inboxEvidenceValid).forEach { proof.put(nodeEvidence(it)) } })
        recipient?.let { recipientHeader(screen, it)?.let { header -> evidence.put("header", nodeEvidence(header)) } }
        return MessageReadCapture(source.toString(), items.length(), scope, truncated, evidence)
    }

    /** Also used for remembered paths; caller must require typed text to equal recipientSearch, or forbid typing. */
    fun navigationProblem(pkg: String, foreground: String?, op: String, target: ScreenCandidate?): String? {
        val label = words(target?.label.orEmpty() + " " + target?.rid.orEmpty())
        return when {
            foreground != pkg -> "message reading must stay in the selected app"
            op in setOf("OPEN_APP", "HOME", "LONG_PRESS") -> "message reading permits navigation only"
            op == "INPUT_TEXT" && (target?.editable != true || !search.containsMatchIn(label)) -> "only the requested contact search may be typed"
            op == "CLICK" && (target == null || target.label.isBlank() || Safety.RISKY.containsMatchIn(label) || mutations.containsMatchIn(label)) -> "message reading stops before a changing or unlabelled action"
            else -> null
        }
    }

    fun selfCheck(): String {
        val apps = listOf(AppInfo("WhatsApp", "com.whatsapp", "main"), AppInfo("Instagram", "com.instagram.android", "main"), AppInfo("Gmail", "com.google.android.gm", "main"))
        check(requestFrom("check a whatsapp dm", apps)?.apps?.single()?.pkg == "com.whatsapp")
        check(requestFrom("read Instagram DMs from Rahul", apps)?.recipient == "Rahul")
        check(requestFrom("open Instagram and summarize messages with Rahul", apps)?.apps?.single()?.pkg == "com.instagram.android")
        check(requestFrom("Summarize this: supplied WhatsApp messages", apps) == null)
        check(requestFrom("write a message saying check WhatsApp DMs", apps) == null)
        check(requestFrom("check messages in MissingChat", apps)?.let { it.apps.isEmpty() && it.requestedApp == "MissingChat" } == true)
        fun node(pkg: String, text: String, id: String, top: Int) = JSONObject().put("package", pkg).put("text", text)
            .put("resource_id", id).put("hint", "").put("class", "TextView").put("visible", true).put("editable", false)
            .put("bounds", JSONArray(listOf(0, top, 500, top + 60))).put("children", JSONArray())
        val title = node("com.whatsapp", "Rahul", "conversation_contact_name", 100)
        val first = node("com.whatsapp", "First synthetic message.", "message_text", 700)
        val second = node("com.whatsapp", "Second synthetic message.", "message_text", 800)
        val root = node("com.whatsapp", "", "root", 0).put("children", JSONArray().put(title).put(first).put(second))
        val state = JSONObject().put("screen", JSONArray(listOf(1080, 2400))).put("windows", JSONArray()
            .put(JSONObject().put("type", 1).put("root", root))
            .put(JSONObject().put("type", 3).put("root", node("com.android.systemui", "NOTIFICATION_SECRET", "message_text", 10)))
            .put(JSONObject().put("type", 2).put("root", node("ime", "KEYBOARD_SECRET", "message_text", 20))))
        val screen = Screen("com.whatsapp", emptyList(), emptyList(), state)
        check(recipientVisible(screen, "Rahul"))
        check(alreadyReadable(screen, apps.first(), "Rahul", 8))
        check(!alreadyReadable(screen, apps.first(), null, 8))
        val capture = extract(screen, apps.first(), "Rahul")
        check(capture.count == 2 && capture.source.indexOf("First synthetic") < capture.source.indexOf("Second synthetic"))
        check("SECRET" !in capture.source && capture.source.length <= SOURCE_LIMIT)
        check(observe(screen, apps.first(), "Rahul") != null)
        check(observe(screen, apps[1], "Rahul") == null)
        check(observe(screen, apps.first(), null) == null)
        val input = JSONObject().put("package", "com.whatsapp").put("recipient", "Rahul").put("limit", 8)
        val output = JSONObject().put("package", "com.whatsapp").put("source", capture.source).put("count", capture.count)
            .put("scope", capture.scope).put("truncated", capture.truncated)
        val proof = JSONObject(capture.evidence.toString()).put("verified", true).put("source_package", "com.whatsapp")
            .put("capture_method", "accessibility_message_viewport").put("coverage", "visible_screen")
            .put("source_sha256", dev.layatest.agent.coordinator.DeviceCapabilities.digest(capture.source))
            .put("source_text_sha256", dev.layatest.agent.coordinator.DeviceCapabilities.digest(capture.evidence.getString("screen_text")))
        val verified = dev.layatest.agent.coordinator.CapabilityResult(output = output, evidence = proof)
        check(MessageCapabilities.verificationError("messages.read", input, verified) == null)
        check(MessageCapabilities.verificationError("messages.read", JSONObject(input.toString()).put("recipient", "Someone else"), verified) != null)
        proof.put("screen_text", "Changed after capture")
        check(MessageCapabilities.verificationError("messages.read", input, verified) != null)
        root.put("visible", false)
        check(observe(screen, apps.first(), "Rahul") == null)
        root.put("visible", true).put("package", "another.app")
        check(observe(screen, apps.first(), "Rahul") == null)
        root.put("package", "com.whatsapp")
        title.put("text", "Someone else")
        check(!recipientVisible(screen, "Rahul"))
        check(observe(screen, apps.first(), "Rahul") == null)
        title.put("text", "Rahul").put("resource_id", "conversations_row_contact_name")
        check(!recipientVisible(screen, "Rahul"))
        title.put("text", "Chats").put("resource_id", "toolbar_title")
        first.put("resource_id", "conversations_row_message")
        check(alreadyReadable(screen, apps.first(), null, 8))
        check(observe(screen, apps.first(), null) != null)
        first.put("text", "x".repeat(1000))
        check(extract(screen, apps.first(), "Rahul").truncated)
        val react = ScreenCandidate("Like", "Button", true, false, false, false, false, false, false, true, IntArray(4))
        check(navigationProblem("com.whatsapp", "com.whatsapp", "CLICK", react) != null)
        check(navigationProblem("com.whatsapp", "com.instagram.android", "CLICK", react) != null)
        val call = react.copy(label = "voicecall")
        check(navigationProblem("com.whatsapp", "com.whatsapp", "CLICK", call) != null)
        return "ok"
    }
}
