package dev.layatest.agent

import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class DraftDestination(val app: AppInfo?, val appName: String?, val recipient: String?, val email: Boolean) {
    val requested get() = appName != null || recipient != null
}

/** App/recipient come from the command, never from generated prose. Handoffs remain unsent drafts. */
internal object DraftHandoff {
    private val email = Regex("[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9.-]*[A-Z0-9])?\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val sourceStart = Regex("\\b(?:saying|that)\\b|\\b(?:verbatim|exactly)\\s*:|[\"“:]", RegexOption.IGNORE_CASE)
    private val appLink = Regex("\\b(?:in|on|via|using|through)\\s+(?:the\\s+)?(.+?)(?=\\s+(?:app\\b|to\\b|for\\b|with\\b|about\\b|asking\\b)|$)", RegexOption.IGNORE_CASE)
    private val style = setOf("a", "an", "the", "very", "short", "brief", "polite", "friendly", "formal", "natural", "concise", "clear", "new", "casual", "professional", "simple", "warm", "kind", "funny")
    private val search = Regex("search|query|find|look ?up", RegexOption.IGNORE_CASE)
    private val composer = Regex("message|compose|body|entry", RegexOption.IGNORE_CASE)
    private val otherField = Regex("search|query|subject|recipient|address|(?:^|[/_ ])(?:to|cc|bcc)(?:$|[_ ])", RegexOption.IGNORE_CASE)
    private val recipientRole = Regex("recipient|chip|contact.*name|conversation.*(?:name|title)|chat.*title|toolbar.*title|(?:^|[/_])to(?:$|_)", RegexOption.IGNORE_CASE)
    private val changingAction = Regex("\\b(?:reply|react|reaction|like|heart|vote|mute|unmute|archive|trash|delete|remove|forward|send|post|follow|unfollow|block|unblock|record|attach|camera|microphone|download|save|pin|unpin|report|clear|edit|join|leave|subscribe|unsubscribe|call|dial|voicecall|videocall)\\b", RegexOption.IGNORE_CASE)

    internal fun header(request: String): String {
        sourceStart.find(request)?.range?.first?.let { return request.take(it) }
        val body = messageParts(taskPart(request)).second
        return body?.takeIf { it.isNotBlank() }?.let { request.lastIndexOf(it).takeIf { index -> index >= 0 } }
            ?.let { request.take(it) } ?: request
    }

    internal fun parse(request: String, kind: String, installed: List<AppInfo>): DraftDestination {
        val command = header(request).trim()
        val appCommand = Regex("\\b(?:about|asking)\\b", RegexOption.IGNORE_CASE).find(command)?.range?.first?.let { command.take(it) } ?: command
        val link = appLink.findAll(appCommand).map { it.groupValues[1].trim().trimEnd('.', ',') }
            .firstOrNull { !Regex("^(?:\\d|(?:a |an )?(?:friendly|polite|formal|concise)|English\\b)|\\b(?:words?|sentences?|lines?|paragraphs?|tone|style)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val modifierWords = Regex("^(?:please\\s+)?(?:write|draft|compose|create|generate|suggest)\\s+(.+?)\\b(?:message|text|reply|e-?mail)\\b", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))
            ?.filter { it.lowercase() !in style }?.joinToString(" ")?.ifBlank { null }
        val modifier = modifierWords?.let { words -> installed.sortedByDescending { it.label.length }.firstOrNull {
            normalize(it.label) == normalize(words) || Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it.label)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(words)
        }?.label }
        val opened = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?=\\s+(?:and|then|to)\\b|$)", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.get(1)?.trim()
        val direct = Regex("^(?:please\\s+)?whatsapp\\b", RegexOption.IGNORE_CASE).find(command)?.let { "WhatsApp" }
        val named = modifier ?: opened ?: direct ?: link
        val app = named?.let { name -> installed.filter { normalize(it.label) == normalize(name) }.distinctBy { it.pkg }.singleOrNull() }
        val to = if (kind in setOf("message", "email", "rewrite")) {
            messageParts(taskPart(command)).first ?: Regex("\\bto\\s+(.+?)(?=\\s+(?:in|on|via|using|through|about|asking|with|and|for)\\b|$)", RegexOption.IGNORE_CASE)
                .find(taskPart(command))?.groupValues?.get(1)?.trim()?.removePrefix("my ")?.trimEnd(',', '.', ':')?.ifBlank { null }
        } else null
        return DraftDestination(app, named, to, kind == "email")
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+app$"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun address(s: String?) = s?.takeIf { email.matches(it) }
    internal fun emailRecipient(s: String) = address(s) != null
    private fun phone(s: String?): String? = s?.takeIf { it.matches(Regex("\\+?[\\d ()-]+")) }
        ?.replace(Regex("[ ()-]"), "")?.takeIf { it.count(Char::isDigit) in 3..15 }

    /** Android common intents + RFC 6068 subject/body fields; extras retain the approved text. */
    internal fun emailIntent(dest: DraftDestination, subject: String, body: String): Intent {
        require(subject.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            "Email subject must be one line without control characters; the approved subject was not changed"
        }
        val to = address(dest.recipient)
        // RFC 6068 requires CRLF line endings in the URI. Extras keep the original newline spelling.
        val uriBody = body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n")
        val uri = Uri.parse("mailto:${Uri.encode(to.orEmpty(), "@")}?subject=${Uri.encode(subject)}&body=${Uri.encode(uriBody)}")
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            dest.app?.let { setPackage(it.pkg) }
            if (to != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }

    internal fun shareIntent(dest: DraftDestination, body: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
        dest.app?.let { setPackage(it.pkg) }
    }

    /** https://faq.whatsapp.com/5913398998672934 — full international number, with no guessed country code. */
    internal fun whatsappIntent(dest: DraftDestination, body: String): Intent? {
        val app = dest.app ?: return null
        if (app.pkg !in setOf("com.whatsapp", "com.whatsapp.w4b")) return null
        val number = phone(dest.recipient)?.takeIf { it.startsWith('+') && it.length >= 8 } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${number.drop(1)}?text=${Uri.encode(body)}")).setPackage(app.pkg)
    }

    private fun bodyRole(id: String, hint: String): Boolean = when {
        otherField.containsMatchIn(id) -> false
        composer.containsMatchIn(id) -> true
        else -> !otherField.containsMatchIn(hint) && hint.trim().lowercase() in setOf("message", "text message", "type a message", "message body", "body", "compose message")
    }

    internal fun bodyField(screen: Screen) = screen.candidates.firstOrNull { it.editable && bodyRole(it.rid, it.label) }

    private fun appNodes(screen: Screen): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(node: JSONObject) {
            if (!node.optBoolean("visible") || node.optString("package") != screen.pkg) return
            out += node
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) walk(children.getJSONObject(i))
        }
        val windows = screen.state?.optJSONArray("windows") ?: return out
        for (i in 0 until windows.length()) windows.getJSONObject(i).let { if (it.optInt("type") == 1) walk(it.getJSONObject("root")) }
        return out
    }

    internal fun bodyVisible(screen: Screen, body: String): Boolean {
        return appNodes(screen).any { it.optBoolean("editable") && it.optString("text") == body &&
            bodyRole(it.optString("resource_id"), it.optString("hint").ifBlank { it.optString("content_desc") }) }
    }

    /** Exact field evidence only; app launch or a share intent is not proof of a prepared draft. */
    internal fun preparedEvidence(screen: Screen, inputs: JSONObject): JSONObject? {
        if (screen.pkg != inputs.optString("package")) return null
        val nodes = appNodes(screen)
        if (nodes.isEmpty() || Safety.isAuthPrompt(screen.pkg, nodes.map { it.optString("text") })) return null
        val text = inputs.optString("text")
        val body = nodes.firstOrNull { it.optBoolean("editable") && it.optString("text") == text &&
            bodyRole(it.optString("resource_id"), it.optString("hint").ifBlank { it.optString("content_desc") }) } ?: return null
        val recipient = inputs.optString("recipient")
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return null
        val recipients = nodes.filter { node ->
            val top = node.optJSONArray("bounds")?.optInt(1, height) ?: height
            top < height / 3 && fieldRoleValid(node, body = false) &&
                node.optString("text").ifBlank { node.optString("content_desc") }.isNotBlank()
        }
        val header = recipients.firstOrNull { node ->
            recipientMatches(node.optString("text").ifBlank { node.optString("content_desc") }, recipient)
        } ?: return null
        // Duplicate nodes for one recipient are common; additional concrete addresses/chips aren't safe.
        if (recipients.any { additionalRecipient(it, recipient) }) return null
        val subject = if (inputs.optString("kind") == "email") nodes.firstOrNull {
            it.optBoolean("editable") && it.optString("text") == inputs.optString("subject") &&
                Regex("subject", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("resource_id") + " " + it.optString("hint"))
        } ?: return null else null
        fun field(node: JSONObject) = JSONObject().put("text", node.optString("text").ifBlank { node.optString("content_desc") })
            .put("package", node.optString("package")).put("resource_id", node.optString("resource_id"))
            .put("hint", node.optString("hint").ifBlank { node.optString("content_desc") }).put("editable", node.optBoolean("editable"))
            .put("bounds", node.optJSONArray("bounds"))
        return JSONObject().put("screen_text", nodes.map { it.optString("text").ifBlank { it.optString("content_desc") } }
            .filter(String::isNotBlank).joinToString("\n"))
            .put("screen_height", height).put("recipient_field", field(header)).put("body_field", field(body))
            .put("recipient_fields", JSONArray().also { proof -> recipients.forEach { proof.put(field(it)) } })
            .also { proof -> subject?.let { proof.put("subject_field", field(it)) } }
    }

    internal fun recipientMatches(observed: String, recipient: String): Boolean {
        val number = phone(recipient)
        return when {
            number != null -> phone(observed) == number
            address(recipient) != null -> email.findAll(observed).toList().let { matches ->
                matches.size == 1 && matches.single().value.equals(recipient, true)
            }
            else -> observed.trim().removePrefix("@").equals(recipient.trim().removePrefix("@"), true)
        }
    }

    internal fun additionalRecipient(node: JSONObject, recipient: String): Boolean {
        val value = node.optString("text").ifBlank { node.optString("content_desc") }
        return (email.containsMatchIn(value) || Regex("chip", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("resource_id"))) &&
            !recipientMatches(value, recipient)
    }

    internal fun fieldRoleValid(field: JSONObject, body: Boolean): Boolean = if (body)
        field.optBoolean("editable") && bodyRole(field.optString("resource_id"), field.optString("hint"))
        else recipientRole.containsMatchIn(field.optString("resource_id") + " " + field.optString("hint")) &&
            !Regex("(?:^|[/_ ])(?:row|list|preview|snippet)(?:$|[_ ])", RegexOption.IGNORE_CASE).containsMatchIn(field.optString("resource_id"))

    internal fun draftText(screen: Screen): String? = appNodes(screen).firstOrNull { it.optBoolean("editable") &&
        bodyRole(it.optString("resource_id"), it.optString("hint").ifBlank { it.optString("content_desc") }) }?.optString("text")

    internal fun screenProblem(screen: Screen, pkg: String): String? = when {
        screen.pkg != pkg -> "The selected app's foreground screen wasn't verified. The saved draft is kept here."
        appNodes(screen).isEmpty() -> "Android isn't exposing the selected app's screen to Friday. The saved draft is kept here."
        Safety.isAuthPrompt(screen.pkg, appNodes(screen).map { it.optString("text").ifBlank { it.optString("content_desc") } }) ->
            "Unlock the selected app to prepare the saved draft."
        else -> null
    }

    internal fun recipientVisible(screen: Screen, recipient: String): Boolean {
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return false
        return appNodes(screen).any { node ->
            val top = node.optJSONArray("bounds")?.optInt(1, height) ?: height
            val role = node.optString("resource_id") + " " + node.optString("hint")
            val text = node.optString("text").ifBlank { node.optString("content_desc") }
            top < height / 3 && recipientRole.containsMatchIn(role) && fieldRoleValid(node, body = false) && recipientMatches(text, recipient)
        }
    }

    /** Used by Assistant before every action in a recipient-only navigation subgoal, including remembered paths. */
    internal fun navigationProblem(pkg: String, foreground: String?, op: String, target: ScreenCandidate?): String? = when {
        foreground != pkg -> "recipient navigation left the selected app"
        op in setOf("OPEN_APP", "HOME", "LONG_PRESS") -> "recipient navigation must stay in the selected app"
        op == "INPUT_TEXT" && (target?.editable != true || !search.containsMatchIn(target.label + " " + target.rid)) -> "only recipient search text may be entered before the chat is checked"
        op == "CLICK" && (target == null || target.label.isBlank() || Safety.RISKY.containsMatchIn(target.label + " " + target.rid.replace('_', ' ')) ||
            changingAction.containsMatchIn((target.label + " " + target.rid).replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[^\\p{L}\\p{N}]+"), " "))) -> "recipient navigation stops before a changing or unlabelled action"
        else -> null
    }

    fun selfCheck(): String {
        val apps = listOf(AppInfo("WhatsApp", "com.whatsapp", "main"), AppInfo("Gmail", "com.google.android.gm", "main"))
        val wa = parse("write a WhatsApp message to Rahul saying I will be late", "message", apps)
        check(wa.app?.pkg == "com.whatsapp" && wa.recipient == "Rahul")
        val mail = parse("draft an email to alex@example.test in Gmail saying Hello", "email", apps)
        check(mail.app?.pkg == "com.google.android.gm" && mail.recipient == "alex@example.test")
        check(!parse("write a message saying I saw WhatsApp and Gmail", "message", apps).requested)
        check(parse("write a WhatsApp message to Rahul saying I saw Gmail", "message", apps).app?.pkg == "com.whatsapp")
        check(parse("write a message in MissingChat to Rahul saying Hello", "message", apps).let { it.app == null && it.appName == "MissingChat" })
        check(parse("draft an email to alex@example.test saying Hello", "email", apps).let { it.app == null && it.recipient == "alex@example.test" })
        check(parse("message Rahul on WhatsApp saying Hello", "message", apps).let { it.app?.pkg == "com.whatsapp" && it.recipient == "Rahul" })
        check(parse("whatsapp Rahul saying Hello", "message", apps).let { it.app?.pkg == "com.whatsapp" && it.recipient == "Rahul" })
        check(parse("open WhatsApp and write a message to Rahul saying Hello", "message", apps).app?.pkg == "com.whatsapp")
        check(Writer.requestFrom(taskPart("open WhatsApp and write a message to Rahul saying Hello"))?.kind == "message")
        check(!parse("write a very polite message saying I saw Gmail", "message", apps).requested)
        check(!parse("write an apologetic message saying Sorry", "message", apps).requested)
        check(parse("write an apologetic WhatsApp message to Rahul saying Sorry", "message", apps).app?.pkg == "com.whatsapp")
        check(parse("write a WhatsApp message to Rahul about the meeting on Friday", "message", apps + AppInfo("Friday", "dev.layatest.agent", "main")).app?.pkg == "com.whatsapp")
        check(!parse("write a message in a warm tone saying Hello", "message", apps).requested)
        check(parse("open WhatsApp to write a message to Rahul saying Hello", "message", apps).recipient == "Rahul")
        check(parse("write a WhatsApp message to Rahul asking for leave", "message", apps).recipient == "Rahul")
        check(parse("message to Rahul I'm on my way", "message", apps).recipient == "Rahul")
        check(parse("message Rahul I'm checking Gmail", "message", apps).app == null)
        check(!parse("write a title for a trip to Bengaluru", "title", apps).requested)
        val body = "Don't change ₹1,250 or 28/10.\nEmoji 🙂 and & + = ? stay exact."
        val intent = emailIntent(mail, "Exact subject", body)
        check(intent.`package` == "com.google.android.gm" && intent.getStringExtra(Intent.EXTRA_TEXT) == body)
        check(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.contentEquals(arrayOf("alex@example.test")))
        check(intent.action == Intent.ACTION_SENDTO && intent.getStringExtra(Intent.EXTRA_SUBJECT) == "Exact subject")
        fun mailFields(intent: Intent): Map<String, String> = intent.data!!.encodedSchemeSpecificPart.substringAfter('?')
            .split('&').associate { field -> Uri.decode(field.substringBefore('=')) to Uri.decode(field.substringAfter('=')) }
        val specialSubject = "Review & + ? # % \"quoted\" café 🙂"
        val specialBody = "First & + ? # % \"quoted\".\nUnicode café 日本語 🙂\r\nAlready CRLF\rFinal line"
        val specialRecipient = "qa+tag&x?y#z%@example.test"
        val encoded = emailIntent(mail.copy(recipient = specialRecipient), specialSubject, specialBody)
        val encodedUri = encoded.data!!.toString()
        val fields = mailFields(encoded)
        check(encoded.`package` == "com.google.android.gm" && encoded.data!!.fragment == null)
        check(Uri.decode(encoded.data!!.encodedSchemeSpecificPart.substringBefore('?')) == specialRecipient)
        check(encoded.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.contentEquals(arrayOf(specialRecipient)))
        check(fields.keys == setOf("subject", "body") && fields["subject"] == specialSubject)
        check(fields["body"] == "First & + ? # % \"quoted\".\r\nUnicode café 日本語 🙂\r\nAlready CRLF\r\nFinal line")
        check(encoded.getStringExtra(Intent.EXTRA_SUBJECT) == specialSubject && encoded.getStringExtra(Intent.EXTRA_TEXT) == specialBody)
        check(listOf("%26", "%2B", "%3F", "%23", "%25", "%22", "%20", "%0D%0A", "%C3%A9", "%F0%9F%99%82").all { it in encodedUri })
        check(mailFields(emailIntent(mail, "Literal %0D%0ABcc:other@example.test", "A\r\nB"))["subject"] == "Literal %0D%0ABcc:other@example.test")
        check(mailFields(emailIntent(mail, "One line", "A\r\nB"))["body"] == "A\r\nB")
        check(emailIntent(mail.copy(app = null, appName = null, recipient = null), "Subject", "Body").let {
            it.`package` == null && it.getStringArrayExtra(Intent.EXTRA_EMAIL) == null && it.data!!.toString().startsWith("mailto:?")
        })
        for (badSubject in listOf("Hi\r\nBcc:other@example.test", "Hi\nNext", "Hi\rNext", "Hi\u0000", "Hi\tNext", "Hi\u2028Next", "Hi\u2029Next")) {
            check(runCatching { emailIntent(mail, badSubject, body) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(shareIntent(wa, body).getStringExtra(Intent.EXTRA_TEXT) == body)
        val numbered = wa.copy(recipient = "+91 98765 43210")
        check(whatsappIntent(numbered, body)!!.data!!.getQueryParameter("text") == body)
        check(whatsappIntent(wa.copy(recipient = "9876543210"), body) == null)
        check(navigationProblem("com.whatsapp", "com.google.android.gm", "CLICK", null) != null)
        check(navigationProblem("com.whatsapp", "com.whatsapp", "CLICK", ScreenCandidate("Send", "Button", true, false, false, false, false, false, false, true, IntArray(4))) != null)
        fun node(pkg: String, text: String, id: String, edit: Boolean = false) = JSONObject()
            .put("package", pkg).put("text", text).put("resource_id", id).put("hint", "").put("content_desc", "")
            .put("visible", true).put("editable", edit).put("bounds", org.json.JSONArray(listOf(0, 100, 500, 200)))
            .put("children", org.json.JSONArray())
        val title = node("com.whatsapp", "Rahul", "conversation_contact_name")
        val entry = node("com.whatsapp", body, "entry", true)
        val root = node("com.whatsapp", "", "root").put("children", org.json.JSONArray().put(title).put(entry))
        val state = JSONObject().put("screen", org.json.JSONArray(listOf(1080, 2400))).put("windows", org.json.JSONArray()
            .put(JSONObject().put("type", 1).put("root", root))
            .put(JSONObject().put("type", 3).put("root", node("com.android.systemui", "Rahul", "conversation_contact_name")))
            .put(JSONObject().put("type", 2).put("root", node("ime", "Rahul", "conversation_contact_name"))))
        val screen = Screen("com.whatsapp", emptyList(), emptyList(), state)
        check(recipientVisible(screen, "Rahul") && bodyVisible(screen, body))
        val input = JSONObject().put("package", "com.whatsapp").put("recipient", "Rahul").put("kind", "message").put("text", body)
        val proof = preparedEvidence(screen, input)!!
            .put("verified", true).put("source_package", "com.whatsapp").put("capture_method", "accessibility_draft_fields")
        proof.put("source_text_sha256", dev.layatest.agent.coordinator.DeviceCapabilities.digest(proof.getString("screen_text")))
        val output = JSONObject(input.toString()).put("subject", "").put("prepared", true).put("sent", false)
        val verified = dev.layatest.agent.coordinator.CapabilityResult(output = output, evidence = proof)
        check(MessageCapabilities.verificationError("message.prepare", input, verified) == null)
        output.put("text", "A different draft")
        check(MessageCapabilities.verificationError("message.prepare", input, verified) != null)
        output.put("text", body).put("sent", true)
        check(MessageCapabilities.verificationError("message.prepare", input, verified) != null)
        output.put("sent", false)
        check(preparedEvidence(screen, JSONObject(input.toString()).put("kind", "email").put("subject", "Missing subject")) == null)
        root.put("visible", false)
        check(preparedEvidence(screen, input) == null)
        root.put("visible", true)
        check(!recipientMatches("alex@example.test, other@example.test", "alex@example.test"))
        check(MessageCapabilities.inputError("message.prepare", JSONObject(input.toString()).put("kind", "email").put("subject", "Title")) != null)
        title.put("text", "Someone else")
        check(!recipientVisible(screen, "Rahul"))
        check(preparedEvidence(screen, input) == null)
        entry.put("resource_id", "recipient_entry")
        check(!bodyVisible(screen, body))
        entry.put("resource_id", "entry").put("package", "com.android.systemui")
        check(!bodyVisible(screen, body))
        return "ok"
    }
}
