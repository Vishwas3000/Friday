package dev.layatest.agent

import android.content.Intent
import android.net.Uri
import dev.layatest.agent.coordinator.CapabilityResult
import dev.layatest.agent.coordinator.DeviceCapabilities as Contracts
import org.json.JSONObject

/** Typed message operations. The saved coordinator owns generation, summaries, retries and publication. */
internal class MessageCapabilities(
    private val ctx: SkillContext,
    private val navigateRead: (AppInfo, String, String?, () -> Boolean) -> Outcome,
    private val navigateDraft: (AppInfo, String, () -> Boolean) -> Outcome,
    private val typeBody: (AppInfo, String, ScreenCandidate, String, () -> Boolean) -> Boolean,
    private val approve: (String) -> String,
) {
    fun inspect(capability: String, inputs: JSONObject): CapabilityResult? {
        if (ctx.stopped || inputError(capability, inputs) != null) return null
        val app = app(inputs) ?: return null
        if (capability == "message.prepare" && Safety.isMoneyApp(app.pkg)) return null
        return when (capability) {
            "messages.read" -> readback(inputs, app)
            "message.prepare" -> prepared(inputs, ctx.screen(), reused = true)
            else -> null
        }
    }

    fun execute(capability: String, inputs: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
        inputError(capability, inputs)?.let { return blocked("WAITING_INPUT", it) }
        if (ctx.stopped) return stopped()
        val app = app(inputs) ?: return blocked("UNSUPPORTED", "The saved target app isn't installed. Choose an installed app.")
        // One dispatch receipt covers this attempt's search/body writes. Each later action still checks stop.
        var dispatchTried = false
        var authorized = false
        val gate = {
            if (ctx.stopped) false else if (dispatchTried) authorized else {
                dispatchTried = true
                beforeDispatch().also { authorized = it }
            }
        }
        return when (capability) {
            "messages.read" -> read(inputs, app, gate)
            "message.prepare" -> prepare(inputs, app, gate)
            else -> blocked("UNSUPPORTED", "This message operation is not registered.")
        }
    }

    private fun read(inputs: JSONObject, app: AppInfo, gate: () -> Boolean): CapabilityResult {
        if (ctx.foreground() != app.pkg) {
            ctx.say("Opening ${app.label}…")
            if (ctx.stopped || !ctx.open(app) || !ctx.waitUntil(5000) { ctx.foreground() == app.pkg })
                return if (ctx.stopped) stopped() else blocked("BLOCKED", "I couldn't verify the selected app. No messages were read.")
        }
        var screen = ctx.screen()
        ReadMessages.screenProblem(screen, app)?.let { return blocked("BLOCKED", it) }
        readback(inputs, app, screen)?.let { return it }
        val recipient = inputs.optString("recipient").ifBlank { null }
        val goal = if (recipient == null) "open the messages inbox in ${app.label}; stop once visible inbox previews are readable"
            else "open the existing conversation with $recipient in ${app.label}; stop when its header and messages are visible"
        ctx.say(if (recipient == null) "Finding the message inbox…" else "Finding the requested conversation…")
        val outcome = navigateRead(app, goal, recipient, gate)
        if (ctx.stopped || outcome == Outcome.STOPPED) return stopped()
        screen = ctx.screen()
        return readback(inputs, app, screen) ?: blocked("BLOCKED",
            ReadMessages.captureProblem(screen, app, recipient, inputs.optInt("limit", 8))
                ?: "The requested message source wasn't verified. No summary was produced.")
    }

    private fun readback(inputs: JSONObject, app: AppInfo, screen: Screen = ctx.screen()): CapabilityResult? {
        val capture = ReadMessages.observe(screen, app, inputs.optString("recipient").ifBlank { null }, inputs.optInt("limit", 8)) ?: return null
        val output = JSONObject().put("source", capture.source).put("count", capture.count).put("scope", capture.scope)
            .put("truncated", capture.truncated).put("package", app.pkg)
        val proof = evidence(inputs, capture.evidence, "accessibility_message_viewport")
            .put("source_sha256", Contracts.digest(capture.source)).put("coverage", "visible_screen")
        return CapabilityResult(output = output, evidence = proof, message = answer("messages.read", output))
    }

    private fun prepare(inputs: JSONObject, app: AppInfo, gate: () -> Boolean): CapabilityResult {
        if (Safety.isMoneyApp(app.pkg)) return blocked("UNSUPPORTED", "Friday doesn't type drafts in money apps. The saved draft is kept here.")
        prepared(inputs, ctx.screen(), reused = true)?.let { return it }
        val recipient = inputs.getString("recipient")
        val text = inputs.getString("text")
        val email = inputs.getString("kind") == "email"
        val preview = if (email) "Subject: ${inputs.getString("subject")}\n\n$text" else text
        when (approve("Review this draft before putting it in ${app.label} for $recipient:\n\n$preview\n\nThis prepares an unsent draft.")) {
            "run" -> Unit
            "stop" -> return stopped()
            else -> return blocked("WAITING_INPUT", "Draft handoff skipped. The saved draft remains in this chat; nothing was sent.")
        }
        if (ctx.stopped) return stopped()
        if (ctx.foreground() != app.pkg) {
            ctx.say("Opening ${app.label}…")
            if (!ctx.open(app) || !ctx.waitUntil(5000) { ctx.foreground() == app.pkg })
                return blocked("BLOCKED", "I couldn't verify the selected app. The saved draft is kept here.")
        }
        if (ctx.stopped) return stopped()
        DraftHandoff.screenProblem(ctx.screen(), app.pkg)?.let { return blocked("BLOCKED", it) }
        val dest = DraftDestination(app, app.label, recipient, email)
        val intent = when {
            email -> DraftHandoff.emailIntent(dest, inputs.getString("subject"), text)
            DraftHandoff.whatsappIntent(dest, text) != null -> DraftHandoff.whatsappIntent(dest, text)
            app.pkg == "com.google.android.apps.messaging" && Regex("\\+?[\\d ()-]+").matches(recipient) ->
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient.replace(Regex("[ ()-]"), ""), null))
                    .setPackage(app.pkg).putExtra("sms_body", text)
            else -> null
        }
        if (intent != null) {
            val existing = DraftHandoff.draftText(ctx.screen())
            if (!existing.isNullOrEmpty() && existing != text)
                return blocked("BLOCKED", "An existing composer contains different text. Review that draft first; the saved draft is kept here.")
            ctx.say("Preparing the unsent draft…")
            if (!gate() || ctx.stopped) return stopped()
            if (!ctx.launch(intent)) return blocked("BLOCKED", "The selected app couldn't open a matching composer. The saved draft is kept here.")
            var found: CapabilityResult? = null
            ctx.waitUntil(3000) { if (ctx.stopped) true else prepared(inputs, ctx.screen(), reused = false).also { found = it } != null }
            if (ctx.stopped) return stopped()
            return found ?: blocked("BLOCKED", "The composer opened, but the exact recipient, text and subject weren't all verified. Nothing was sent. Review the app before retrying.")
        }
        ctx.say("Finding the requested recipient…")
        var screen = ctx.screen()
        DraftHandoff.screenProblem(screen, app.pkg)?.let { return blocked("BLOCKED", it) }
        if (!DraftHandoff.recipientVisible(screen, recipient) || DraftHandoff.bodyField(screen) == null) {
            val outcome = navigateDraft(app, recipient, gate)
            if (ctx.stopped || outcome == Outcome.STOPPED) return stopped()
            screen = ctx.screen()
        }
        val field = DraftHandoff.bodyField(screen)
        if (screen.pkg != app.pkg || field == null || !DraftHandoff.recipientVisible(screen, recipient))
            return blocked("BLOCKED", "The exact recipient and message composer weren't verified in the selected app. The saved draft is kept here.")
        ctx.say("Filling the unsent draft…")
        // typeBody invokes gate immediately before INPUT_TEXT, after any approval to replace old text.
        if (!typeBody(app, recipient, field, text, gate))
            return if (ctx.stopped) stopped() else blocked("BLOCKED", "Draft entry wasn't confirmed. Existing text is replaced only with approval; the saved draft is kept here.")
        if (ctx.stopped) return stopped()
        return prepared(inputs, ctx.screen(), reused = false)
            ?: blocked("BLOCKED", "The exact draft fields weren't verified after entry. Nothing was sent. Review the app before retrying.")
    }

    private fun prepared(inputs: JSONObject, screen: Screen, reused: Boolean): CapabilityResult? {
        val fields = DraftHandoff.preparedEvidence(screen, inputs) ?: return null
        val output = JSONObject().put("package", inputs.getString("package")).put("recipient", inputs.getString("recipient"))
            .put("kind", inputs.getString("kind")).put("text", inputs.getString("text")).put("subject", inputs.optString("subject"))
            .put("prepared", true).put("sent", false).put("reused", reused)
        return CapabilityResult(output = output, evidence = evidence(inputs, fields, "accessibility_draft_fields"),
            message = answer("message.prepare", output))
    }

    private fun app(inputs: JSONObject) = ctx.apps.installed.firstOrNull { it.pkg == inputs.optString("package") }
    private fun evidence(inputs: JSONObject, fields: JSONObject, method: String) = JSONObject(fields.toString())
        .put("verified", true).put("input_bind", inputs.optString("_input_bind")).put("captured_at", System.currentTimeMillis())
        .put("source_package", inputs.getString("package")).put("capture_method", method)
        .put("source_text_sha256", Contracts.digest(fields.getString("screen_text")))
    private fun blocked(state: String, message: String) = CapabilityResult(state, message = message)
    private fun stopped() = blocked("CANCELLED", "Stopped. Any saved or prepared draft remains unsent.")

    companion object {
        fun inputError(capability: String, input: JSONObject): String? = when {
            capability !in setOf("messages.read", "message.prepare") -> "Unknown message capability."
            !string(input, "package") || !Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(input.optString("package")) -> "Choose one installed app for this saved step."
            capability == "messages.read" -> when {
                input.has("recipient") && !string(input, "recipient") -> "Specify the exact conversation name, or omit recipient for the inbox."
                input.has("limit") && (input.opt("limit") !is Int || input.optInt("limit") !in 1..12) -> "The message read limit must be an integer from 1 to 12."
                else -> null
            }
            !string(input, "recipient") -> "Which exact recipient should receive the draft?"
            input.optString("kind") !in setOf("message", "email") -> "Choose message or email for this draft."
            !string(input, "text") -> "The saved draft has no text to prepare."
            input.optString("kind") == "email" && (!input.has("subject") || input.opt("subject") !is String) -> "The email needs an explicit subject field."
            input.optString("kind") == "email" && !DraftHandoff.emailRecipient(input.getString("recipient")) -> "Provide the exact email address for this saved draft."
            input.optString("kind") == "email" && input.getString("subject").any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> "The email subject must be one line without control characters."
            input.has("subject") && input.opt("subject") !is String -> "The subject must be text."
            else -> null
        }

        /** Additional checks after the shared verifier has checked freshness and the saved input binding. */
        fun verificationError(capability: String, input: JSONObject, result: CapabilityResult): String? {
            inputError(capability, input)?.let { return it }
            if (capability == "message.prepare" && Safety.isMoneyApp(input.getString("package"))) return "Draft preparation in money apps is unavailable."
            val output = result.output
            val proof = result.evidence
            val text = proof.optString("screen_text")
            if (result.state != "COMPLETED" || !proof.optBoolean("verified") || proof.optString("source_package") != input.getString("package") ||
                text.isBlank() || proof.optString("source_text_sha256") != Contracts.digest(text)) return "Scoped app evidence is missing or changed."
            if (output.optString("package") != input.getString("package")) return "The output came from a different app."
            return when (capability) {
                "messages.read" -> readError(input, output, proof)
                "message.prepare" -> draftError(input, output, proof)
                else -> "Unknown message capability."
            }
        }

        private fun readError(input: JSONObject, output: JSONObject, proof: JSONObject): String? {
            if (proof.optString("capture_method") != "accessibility_message_viewport" || proof.optString("coverage") != "visible_screen")
                return "Message viewport coverage wasn't recorded."
            val sourceText = output.optString("source")
            val source = runCatching { JSONObject(sourceText) }.getOrNull() ?: return "No structured message source was captured."
            val items = source.optJSONArray("items") ?: return "No visible message items were captured."
            val observed = proof.optJSONArray("message_items") ?: return "No message item evidence was captured."
            val count = output.optInt("count")
            if (proof.optString("source_sha256") != Contracts.digest(sourceText) || source.optString("package") != input.getString("package") ||
                count !in 1..input.optInt("limit", 8) || count != items.length() || count != observed.length() || output.opt("truncated") !is Boolean ||
                source.optString("scope") != output.optString("scope")) return "Message source, count or coverage changed."
            val recipient = input.optString("recipient")
            val scope = output.optString("scope")
            if (recipient.isNotBlank()) {
                val header = proof.optJSONObject("header") ?: return "The requested conversation header wasn't captured."
                if (scope != "visible conversation text; requested contact header checked" || source.optString("requested_conversation") != recipient ||
                    !ReadMessages.headerEvidenceValid(header) || !field(input, proof, header, top = true) ||
                    !header.optString("text").removePrefix("@").equals(recipient.removePrefix("@"), true)) return "The captured conversation doesn't match the saved recipient."
            } else {
                val inboxFields = proof.optJSONArray("inbox_fields") ?: return "No inbox boundary evidence was captured."
                if (scope != "visible inbox previews only" || !proof.optBoolean("inbox_visible") || (0 until inboxFields.length()).none { i ->
                    inboxFields.optJSONObject(i)?.let { node -> ReadMessages.inboxEvidenceValid(node) &&
                        field(input, proof, node, allowBlank = true) } == true
                }) return "Inbox boundaries weren't verified."
            }
            for (i in 0 until count) {
                val item = observed.optJSONObject(i) ?: return "Invalid message evidence."
                val captured = items.opt(i) as? String ?: return "Invalid message source text."
                if (captured.isBlank() || item.optString("captured_text") != captured || !item.optString("text").startsWith(captured) ||
                    !field(input, proof, item) || !ReadMessages.itemEvidenceValid(item, recipient.isNotBlank())) return "A source message isn't supported by the captured screen."
            }
            return null
        }

        private fun draftError(input: JSONObject, output: JSONObject, proof: JSONObject): String? {
            if (proof.optString("capture_method") != "accessibility_draft_fields" || output.opt("prepared") != true || output.opt("sent") != false ||
                listOf("recipient", "kind", "text").any { output.optString(it) != input.getString(it) } ||
                output.optString("subject") != input.optString("subject")) return "The prepared draft differs from the saved inputs."
            val body = proof.optJSONObject("body_field") ?: return "No editable draft body was captured."
            val recipient = proof.optJSONObject("recipient_field") ?: return "No recipient field was captured."
            if (!field(input, proof, body) || !DraftHandoff.fieldRoleValid(body, body = true) || body.optString("text") != input.getString("text"))
                return "The exact draft body wasn't verified in an editable composer."
            if (!field(input, proof, recipient, top = true) || !DraftHandoff.fieldRoleValid(recipient, body = false) ||
                !DraftHandoff.recipientMatches(recipient.optString("text"), input.getString("recipient"))) return "The exact draft recipient wasn't verified."
            val recipients = proof.optJSONArray("recipient_fields") ?: return "Recipient coverage wasn't captured."
            for (i in 0 until recipients.length()) {
                val item = recipients.optJSONObject(i) ?: return "Invalid recipient evidence."
                if (!field(input, proof, item, top = true) || !DraftHandoff.fieldRoleValid(item, body = false)) return "Recipient evidence differs from the selected app."
                if (DraftHandoff.additionalRecipient(item, input.getString("recipient"))) return "Additional draft recipients were observed."
            }
            if (input.getString("kind") == "email") {
                val subject = proof.optJSONObject("subject_field") ?: return "No email subject field was captured."
                if (!field(input, proof, subject, allowBlank = true) || !subject.optBoolean("editable") || subject.optString("text") != input.getString("subject") ||
                    !Regex("subject", RegexOption.IGNORE_CASE).containsMatchIn(subject.optString("resource_id") + " " + subject.optString("hint"))) return "The exact email subject wasn't verified."
            }
            return null
        }

        private fun field(input: JSONObject, proof: JSONObject, node: JSONObject, top: Boolean = false, allowBlank: Boolean = false): Boolean {
            val bounds = node.optJSONArray("bounds") ?: return false
            val value = node.optString("text")
            return node.optString("package") == input.getString("package") && bounds.length() == 4 &&
                (0..3).all { bounds.opt(it) is Int } && bounds.getInt(0) >= 0 && bounds.getInt(1) >= 0 &&
                bounds.getInt(2) > bounds.getInt(0) && bounds.getInt(3) > bounds.getInt(1) &&
                (!top || proof.optInt("screen_height") > 0 && bounds.getInt(1) < proof.getInt("screen_height") / 3) &&
                (allowBlank && value.isEmpty() || value.isNotBlank() && proof.optString("screen_text").contains(value))
        }

        fun answer(capability: String, output: JSONObject): String = when (capability) {
            "messages.read" -> "Source: ${output.getString("package")}; ${output.getString("scope")}; ${output.getInt("count")} visible text units${if (output.optBoolean("truncated")) "; truncated" else ""}. One screen only; older/offscreen messages, images and audio weren't read. Speaker and delivery status are unverified."
            "message.prepare" -> "Unsent draft text and recipient${if (output.optString("kind") == "email") ", including subject," else ""} verified in ${output.getString("package")}. Review it there before sending."
            else -> "Unknown message result."
        }

        private fun string(value: JSONObject, key: String) = value.opt(key) is String && value.getString(key).isNotBlank()
    }
}
