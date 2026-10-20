package dev.layatest.agent

import android.content.Intent
import android.net.Uri
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
    private val chatApps = setOf("com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging")

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

    fun run(ctx: SkillContext, kind: String, written: JSONObject,
            approve: (String) -> String,
            navigate: (AppInfo, String) -> Outcome,
            typeBody: (AppInfo, String, ScreenCandidate, String) -> Boolean): String {
        val draft = Writer.answer(written)
        if (written.optString("status") != "ok") return draft
        fun result(status: String) = "$draft\n\n$status"
        val parsed = parse(ctx.request, kind, ctx.apps.installed)
        if (!parsed.requested) return draft
        val dest = if (!parsed.email && parsed.appName == null && parsed.recipient != null) {
            val choices = ctx.apps.candidates("message").distinctBy { it.pkg }
            val app = ctx.apps.default("message") ?: choices.singleOrNull()
                ?: ctx.ask("Which app should receive the draft for ${parsed.recipient}?", choices.map { it.label })?.let { choices.getOrNull(it) }
            if (app == null) return result("Choose a messaging app to continue. The draft is kept here; nothing was sent.")
            parsed.copy(app = app, appName = app.label)
        } else parsed
        if (dest.appName != null && dest.app == null) return result("I couldn't find the named app “${dest.appName}”. The draft is kept here.")
        if (Safety.isMoneyApp(dest.app?.pkg)) return result("Friday never types drafts in money apps. The draft is kept here.")
        val body = if (kind == "email") written.getString("body") else written.getString("text")
        val target = dest.app?.label ?: if (dest.email) "your email app" else "the app chooser"
        when (approve("Review this draft before putting it in $target${dest.recipient?.let { " for $it" }.orEmpty()}:\n\n$draft\n\nThis prepares a draft; it does not send it.")) {
            "run" -> {}
            "stop" -> return result("Stopped before handoff. The draft is kept here.")
            else -> return result("Handoff skipped. The draft is kept here.")
        }
        if (ctx.stopped) return result("Stopped before handoff. The draft is kept here.")

        if (!dest.email && dest.app?.pkg in chatApps && dest.recipient != null && phone(dest.recipient) == null) {
            val app = dest.app!!
            ctx.say("Opening ${app.label}…")
            if (!ctx.open(app) || !ctx.waitUntil(5000) { ctx.foreground() == app.pkg })
                return result("I couldn't open ${app.label}. The draft is kept here.")
            ctx.say("Finding ${dest.recipient}…")
            val outcome = navigate(app, dest.recipient)
            if (ctx.stopped || outcome == Outcome.STOPPED) return result("Stopped. The draft is kept here.")
            val screen = ctx.screen()
            val field = bodyField(screen)
            if (screen.pkg == app.pkg && field != null && recipientVisible(screen, dest.recipient)) {
                ctx.say("Filling draft…")
                if (typeBody(app, dest.recipient, field, body)) {
                    ctx.say("Checking draft…")
                    val readback = ctx.screen()
                    if (readback.pkg == app.pkg && recipientVisible(readback, dest.recipient) && bodyVisible(readback, body))
                        return result("Draft text and recipient checked in ${app.label}. Review it there and tap Send when ready; nothing was sent.")
                }
                return result("The draft was not confirmed in ${app.label}; existing text is replaced only with your approval. Check the recipient and text there; the original is kept here. Nothing was sent.")
            }
            return share(ctx, dest, body, draft)
        }

        val intent = try { when {
            dest.email -> emailIntent(dest, written.getString("subject"), body)
            whatsappIntent(dest, body) != null -> whatsappIntent(dest, body)!!
            dest.app?.pkg == "com.google.android.apps.messaging" && phone(dest.recipient) != null ->
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone(dest.recipient), null))
                    .setPackage(dest.app.pkg).putExtra("sms_body", body)
            else -> return share(ctx, dest, body, draft)
        } } catch (invalid: IllegalArgumentException) {
            return result("I couldn't prepare the email: ${invalid.message}. The draft is kept here; nothing was sent.")
        }
        ctx.say("Opening $target…")
        if (!ctx.launch(intent)) return result("I couldn't open a matching composer in $target. The draft is kept here.")
        ctx.say("Checking draft…")
        ctx.waitUntil(3000) { dest.app == null || ctx.foreground() == dest.app.pkg }
        val screen = ctx.screen()
        val correctApp = dest.app == null || screen.pkg == dest.app.pkg
        val bodyChecked = correctApp && bodyVisible(screen, body)
        val recipientChecked = dest.recipient != null && correctApp && recipientVisible(screen, dest.recipient)
        val subjectChecked = !dest.email || subjectVisible(screen, written.getString("subject"))
        return result(when {
            bodyChecked && recipientChecked && subjectChecked -> "Draft fields and recipient checked in $target. Review it there and tap Send when ready; nothing was sent."
            bodyChecked && recipientChecked -> "The body and recipient are visible in $target; I couldn't verify the subject. Check it there before sending. Nothing was sent."
            bodyChecked -> "Draft text is visible in $target. ${dest.recipient?.let { "Select and check $it as the recipient." } ?: "Choose a recipient."} Nothing was sent."
            else -> "Composer handoff requested in $target. I couldn't verify the populated fields; check the recipient and text there. The original draft is kept here. Nothing was sent."
        })
    }

    private fun share(ctx: SkillContext, dest: DraftDestination, body: String, draft: String): String {
        if (ctx.stopped) return "$draft\n\nStopped before handoff."
        val send = shareIntent(dest, body)
        val intent = if (dest.app == null) Intent.createChooser(send, "Choose an app for the draft") else send
        val target = dest.app?.label ?: "the app chooser"
        ctx.say("Opening $target…")
        if (!ctx.launch(intent)) return "$draft\n\n$target couldn't accept the draft. The original is kept here."
        return "$draft\n\nDraft handoff opened in $target. ${dest.recipient?.let { "Choose and check $it as the recipient." } ?: "Choose a recipient or destination."} Text and recipient are not yet verified. Nothing was sent."
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
            if (node.optBoolean("visible") && node.optString("package") == screen.pkg) out += node
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

    private fun subjectVisible(screen: Screen, subject: String) = appNodes(screen).any {
        it.optString("text") == subject && Regex("subject", RegexOption.IGNORE_CASE)
            .containsMatchIn(it.optString("resource_id") + " " + it.optString("hint") + " " + it.optString("content_desc"))
    }

    internal fun recipientVisible(screen: Screen, recipient: String): Boolean {
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return false
        val number = phone(recipient)
        return appNodes(screen).any { node ->
            val top = node.optJSONArray("bounds")?.optInt(1, height) ?: height
            val role = node.optString("resource_id") + " " + node.optString("hint")
            val text = node.optString("text").ifBlank { node.optString("content_desc") }
            top < height / 3 && recipientRole.containsMatchIn(role) && if (number != null) phone(text) == number
                else if (address(recipient) != null) email.findAll(text).any { it.value.equals(recipient, true) }
                else text.trim().equals(recipient, true)
        }
    }

    /** Used by Assistant before every action in a recipient-only navigation subgoal, including remembered paths. */
    internal fun navigationProblem(pkg: String, foreground: String?, op: String, target: ScreenCandidate?): String? = when {
        foreground != pkg -> "recipient navigation left the selected app"
        op in setOf("OPEN_APP", "HOME", "LONG_PRESS") -> "recipient navigation must stay in the selected app"
        op == "INPUT_TEXT" && (target?.editable != true || !search.containsMatchIn(target.label + " " + target.rid)) -> "only recipient search text may be entered before the chat is checked"
        op == "CLICK" && (target == null || target.label.isBlank() || Safety.RISKY.containsMatchIn(target.label + " " + target.rid.replace('_', ' '))) -> "recipient navigation stops before a sending or unlabelled action"
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
        title.put("text", "Someone else")
        check(!recipientVisible(screen, "Rahul"))
        entry.put("resource_id", "recipient_entry")
        check(!bodyVisible(screen, body))
        entry.put("resource_id", "entry").put("package", "com.android.systemui")
        check(!bodyVisible(screen, body))
        return "ok"
    }
}
