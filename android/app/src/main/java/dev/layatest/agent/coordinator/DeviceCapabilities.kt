package dev.layatest.agent.coordinator

import dev.layatest.agent.ActionCapabilities
import dev.layatest.agent.HotelCapabilities
import dev.layatest.agent.MessageCapabilities
import dev.layatest.agent.ResearchCapabilities
import dev.layatest.agent.RequestResolver
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal interface TaskDriver {
    val stopped: Boolean
    val interrupted: Boolean get() = false
    fun progress(message: String)
}

/** Only registered application code can execute or verify a capability. App/model text is data. */
internal interface CapabilityDriver : TaskDriver {
    /** Reconcile without repeating writes. Null means no verified result was found. */
    fun inspect(step: StepRecord, inputs: JSONObject): CapabilityResult?
    /** Every external write calls beforeDispatch immediately before writing; false means stop. */
    fun execute(step: StepRecord, inputs: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult
}

internal data class CapabilityResult(
    val state: String = "COMPLETED",
    val output: JSONObject = JSONObject(),
    val evidence: JSONObject = JSONObject(),
    val message: String = "",
)

internal object DeviceCapabilities {
    val registry = listOf(
        Capability("calendar.create", "Create calendar event", "external_write", "calendar.exact_saved_event", 90000),
        Capability("bills.read", "Read labeled bill amounts", "read", "bills.visible_labeled_amounts", 15000),
        Capability("app.open", "Open selected app", "navigation", "app.foreground", 10000),
        Capability("app.search", "Search selected app", "navigation", "app.exact_search_results", 30000),
        Capability("text.draft", "Generate requested text", "local_compute", "writer.generated_text", 90000),
        Capability("intent.resolve", "Resolve the request and chat references", "local_compute", "intent.grounded_source_spans", 35000),
        Capability("input.clarify", "Keep task details for your reply", "local_compute", "input.pending_only", 5000),
        Capability("research.prepare", "Prepare normal Gemini chat", "navigation", "gemini.normal_blank", 15000),
        Capability("research.submit", "Submit saved research question", "external_write", "gemini.exact_user_turn", 15000),
        Capability("research.capture", "Collect the complete research answer", "read", "gemini.full_response_footer", 300000),
        Capability("messages.read", "Read visible messages", "read", "messages.scoped_source", 90000),
        Capability("message.prepare", "Prepare the draft in its app", "external_write", "message.exact_unsent_composer", 90000),
        Capability("hotel.resolve_inputs", "Resolve supplied stay details", "local_compute", "hotel.owner_inputs", 5000),
        Capability("hotel.search", "Search and verify hotel results", "navigation", "hotel.exact_results", 120000),
        Capability("device.status", "Read device status", "read", "android.native_status", 10000),
        Capability("app.navigate", "Navigate the selected app", "navigation", "app.observed_destination", 90000),
        Capability("app.action", "Run the requested app action", "external_write", "app.observed_action", 90000),
        Capability("task.publish", "Save verified result to this chat", "local_write", "chat.publication_key", 5000),
    ).associateBy { it.id }

    val yieldStates = setOf("BLOCKED", "WAITING_INPUT", "UNSUPPORTED", "OUTCOME_UNKNOWN", "FAILED", "CANCELLED")
    private val dueLabel = Regex("^(?:amount due|total (?:amount )?due|minimum(?: amount)? due|payment due|due date|due on|next payment|overdue(?: amount)?|bill amount|amount payable|grand total(?: due)?|total (?:amount|outstanding|payable)|outstanding(?: amount| balance)?|statement balance|total (?:amount )?(?:due|outstanding|payable) (?:across|for) all (?:credit )?cards)$", RegexOption.IGNORE_CASE)
    private val totalLabel = Regex("^(?:grand total due|total (?:amount )?(?:due|outstanding|payable) (?:across|for) all (?:credit )?cards)$", RegexOption.IGNORE_CASE)

    fun inputError(capability: String, input: JSONObject): String? {
        if (input.optString("validation_error").isNotBlank()) return input.getString("validation_error")
        if (capability !in registry) return "Missing capability: $capability"
        if (input.has("_input_bind")) return "Reserved input binding cannot be supplied by a plan"
        if (capability in setOf("calendar.create", "bills.read", "app.open", "app.search", "app.navigate", "app.action", "messages.read", "message.prepare", "hotel.search") &&
            (!string(input, "package") || !Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(input.getString("package"))))
            return "Which installed app or provider should I use?"
        return when (capability) {
            "input.clarify" -> if (string(input, "question") && input.optString("state") in setOf("WAITING_INPUT", "BLOCKED", "CANCELLED")) null
                else "A pending task needs a question and an explicit state."
            "research.prepare", "research.submit", "research.capture" -> ResearchCapabilities.inputError(capability, input)
            "intent.resolve" -> RequestResolver.inputError(input)
            "messages.read", "message.prepare" -> MessageCapabilities.inputError(capability, input)
            "hotel.resolve_inputs" -> null // The resolver saves missing fields as output for the next turn.
            "hotel.search" -> HotelCapabilities.prepare(input).takeIf { it.stay == null }?.message
            "device.status", "app.navigate", "app.action" -> ActionCapabilities.inputError(capability, input)
            "calendar.create" -> when {
                !string(input, "title") -> "What title should I give the calendar event?"
                !integer(input, "start_ms") || !integer(input, "end_ms") || input.optLong("end_ms") <= input.optLong("start_ms") -> "What exact start and end date/time should I use?"
                input.opt("all_day") !is Boolean || !string(input, "zone") -> "Please specify whether this is all day and confirm the time zone."
                runCatching { ZoneId.of(input.getString("zone")) }.isFailure -> "Please provide a valid time zone."
                input.getBoolean("all_day") && runCatching {
                    val start = Instant.ofEpochMilli(input.getLong("start_ms")).atZone(ZoneOffset.UTC)
                    val end = Instant.ofEpochMilli(input.getLong("end_ms")).atZone(ZoneOffset.UTC)
                    start.toLocalTime().toSecondOfDay() == 0 && start.nano == 0 &&
                        end.toLocalTime().toSecondOfDay() == 0 && end.nano == 0 && end.toLocalDate() == start.toLocalDate().plusDays(1) &&
                        (!input.has("local_date") || LocalDate.parse(input.getString("local_date")) == start.toLocalDate())
                }.getOrDefault(false).not() -> "An all-day event needs UTC midnight dates with the following date as its exclusive end."
                else -> null
            }
            "app.search" -> if (!string(input, "query")) "What should I search for in that app?" else null
            "text.draft" -> if (input.optBoolean("verbatim")) {
                if (!string(input, "text")) "What exact text should I use?" else null
            } else if (!string(input, "instruction")) "What text should I generate?" else null
            else -> null
        }
    }

    /** Returns a reason, never a permissive default verifier. */
    fun verificationError(capability: String, input: JSONObject, result: CapabilityResult, attemptStarted: Long): String? {
        val output = result.output
        val evidence = result.evidence
        if (result.state != "COMPLETED") return "Capability did not return a completed result"
        if (!evidence.optBoolean("verified") || evidence.optString("input_bind") != input.optString("_input_bind") ||
            !integer(evidence, "captured_at") || evidence.optLong("captured_at") < attemptStarted || evidence.optLong("captured_at") > System.currentTimeMillis())
            return "Fresh evidence is not bound to these saved inputs"
        if (input.has("package") && capability != "hotel.resolve_inputs" && evidence.optString("source_package") != input.getString("package")) return "Evidence came from a different app"
        return when (capability) {
            "intent.resolve" -> {
                val sources = input.getJSONArray("sources").let { a -> (0 until a.length()).map { index ->
                    a.getJSONObject(index).let { RequestResolver.Source(it.getString("id"), it.getString("text")) }
                } }
                val intent = output.optJSONObject("intent") ?: return "No interpreted intent was returned."
                if (evidence.optString("resolution_method") == "visible_navigation") {
                    val destination = RequestResolver.destinationFrom(input) ?: return "The visible navigation has no selected app."
                    val label = evidence.optString("observed_label")
                    val labels = evidence.optJSONArray("navigation_labels") ?: return "Visible navigation labels were not captured."
                    if (label.isBlank() || evidence.optString("screen_package") != destination.packageName ||
                        evidence.optString("screen_sha256") != digest(evidence.optString("screen_text")) ||
                        intent.optString("mode") != "navigate" || intent.optString("target").isNotEmpty() || intent.optString("source_id") != "request" ||
                        RequestResolver.canonicalSectionName(label) != RequestResolver.canonicalSectionName(intent.optString("scope")) ||
                        (0 until labels.length()).count { RequestResolver.canonicalSectionName(labels.optString(it)) == RequestResolver.canonicalSectionName(label) } != 1)
                        return "The exact visible navigation label was not bound to the selected app and request."
                }
                if (evidence.optString("source_package") != "local.intent" || evidence.optString("capture_method") != "grounded_intent" ||
                    evidence.optString("intent_sha256") != digest(canonical(intent))) "Intent evidence is missing or changed."
                else RequestResolver.validate(input.getString("request"), sources, intent, RequestResolver.destinationFrom(input))
            }
            "research.prepare", "research.submit", "research.capture" -> ResearchCapabilities.verificationError(capability, input, result)
            "messages.read", "message.prepare" -> MessageCapabilities.verificationError(capability, input, result)
            "hotel.resolve_inputs", "hotel.search" -> HotelCapabilities.verificationError(capability, input, result)
            "device.status", "app.navigate", "app.action" -> ActionCapabilities.verificationError(capability, input, result)
            "calendar.create" -> when {
                !string(output, "event_id") || evidence.optString("event_id") != output.getString("event_id") -> "No matching saved event identifier was verified"
                evidence.optString("capture_method") != "calendar_provider_readback" || !output.optBoolean("busy") -> "Saved busy event readback was not verified"
                listOf("title", "zone").any { output.optString(it) != input.getString(it) || evidence.optString(it) != input.getString(it) } -> "Saved calendar text differs from the requested event"
                listOf("start_ms", "end_ms").any { !integer(output, it) || !integer(evidence, it) || output.getLong(it) != input.getLong(it) || evidence.getLong(it) != input.getLong(it) } -> "Saved calendar dates differ from the requested event"
                output.opt("all_day") != input.getBoolean("all_day") || evidence.opt("all_day") != input.getBoolean("all_day") -> "Saved all-day setting differs from the requested event"
                else -> null
            }
            "app.open" -> if (output.optString("package") == input.getString("package") && evidence.optString("foreground_package") == input.getString("package") &&
                (!input.optBoolean("dismiss_keyboard") || evidence.optBoolean("keyboard_dismissed"))) null else "Selected app and requested navigation setup were not verified"
            "app.search" -> if (output.optString("package") == input.getString("package") && output.optString("query") == input.getString("query") &&
                sameQuery(evidence.optString("observed_query"), input.getString("query")) && string(evidence, "matched_result") &&
                evidence.optBoolean("query_visible") && evidence.optBoolean("results_visible")) null else "Exact query and independent results were not verified in the selected app"
            "bills.read" -> billError(input, output, evidence)
            "text.draft" -> when {
                !string(output, "text") || (output.has("status") && output.optString("status") != "ok") -> "No generated text was returned"
                input.optBoolean("verbatim") -> if (output.getString("text") == input.getString("text") && evidence.opt("generated") == false &&
                    evidence.optString("capture_method") == "verbatim_text") null else "Verbatim output differs from the exact supplied text"
                !evidence.optBoolean("generated") || !string(evidence, "model") || evidence.optString("instruction_sha256") != digest(input.getString("instruction")) -> "Text generation evidence does not match the saved instruction"
                else -> null
            }
            else -> "No verifier is registered for $capability"
        }
    }

    private fun billError(input: JSONObject, output: JSONObject, evidence: JSONObject): String? {
        val text = evidence.optString("screen_text", evidence.optString("source_text"))
        if (evidence.optString("coverage") != "visible_screen" || text.isBlank() || evidence.optString("source_text_sha256") != digest(text)) return "Visible app evidence is unavailable"
        val items = output.optJSONArray("items") ?: return "No labeled due amounts were observed"
        if (items.length() == 0) return "No labeled due amounts were observed"
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: return "Invalid labeled due item"
            val label = item.optString("label").trim().trimEnd(':').replace(Regex("\\s+"), " ")
            val amount = item.optString("amount").trim()
            val snippet = item.optString("evidence_text")
            if (!dueLabel.matches(label) || amount.isBlank() || !text.contains(label, true) || !text.contains(amount) ||
                (snippet.isNotEmpty() && (!text.contains(snippet) || !snippet.contains(label, true) || !snippet.contains(amount))))
                return "A due label or amount was not present in the captured app evidence"
        }
        val total = output.optString("total_amount")
        if (total.isNotEmpty() && (!evidence.optBoolean("explicit_total") || !text.contains(total) ||
                (0 until items.length()).none { i -> val item = items.getJSONObject(i); totalLabel.matches(item.optString("label").trim().trimEnd(':')) && item.optString("amount") == total }))
            return "A total must be explicitly labeled by the source app; visible items cannot be summed"
        if (input.optBoolean("total_requested") && total.isBlank()) return "The app does not show an explicit total. The visible screen is incomplete evidence for an overall total."
        return null
    }

    fun answer(capability: String, output: JSONObject): String = when (capability) {
        "research.capture" -> output.getString("answer")
        "research.prepare", "research.submit", "intent.resolve" -> ""
        "messages.read", "message.prepare" -> MessageCapabilities.answer(capability, output)
        "hotel.resolve_inputs", "hotel.search" -> HotelCapabilities.answer(capability, output)
        "device.status", "app.navigate", "app.action" -> ActionCapabilities.answer(capability, output)
        "calendar.create" -> {
            val allDay = output.getBoolean("all_day")
            val zone = if (allDay) ZoneOffset.UTC else ZoneId.of(output.getString("zone"))
            val start = Instant.ofEpochMilli(output.getLong("start_ms")).atZone(zone)
            val end = Instant.ofEpochMilli(output.getLong("end_ms")).atZone(zone)
            val date = start.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH))
            val time = if (allDay) "all day" else start.format(DateTimeFormatter.ofPattern("HH:mm")) + "–" +
                end.format(DateTimeFormatter.ofPattern("d MMMM uuuu HH:mm", Locale.ENGLISH)) + " " + zone.id
            (if (output.optBoolean("reused")) "Already blocked; no duplicate created: " else "Calendar blocked: ") +
                output.getString("title") + " — " + date + " (" + time + "; Busy). Saved fields verified." +
                if (output.optBoolean("inferred_year")) " I used the next occurrence of that date." else ""
        }
        "app.open" -> "Opened ${output.optString("label").ifBlank { "the selected app" }}."
        "app.search" -> "Search results verified for “${output.getString("query")}”."
        "text.draft" -> output.getString("text")
        "bills.read" -> buildString {
            append("Visible labeled dues:\n")
            val items = output.optJSONArray("items") ?: JSONArray()
            append((0 until items.length()).joinToString("\n") { i -> items.getJSONObject(i).let { "${it.getString("label")}: ${it.getString("amount")}" } })
            if (output.has("total_amount")) append("\nExplicit total: ${output.getString("total_amount")}")
            append("\nCoverage: current visible screen.")
        }
        else -> "Verified $capability."
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun string(value: JSONObject, key: String) = value.has(key) && !value.isNull(key) && value.optString(key).isNotBlank()
    private fun integer(value: JSONObject, key: String) = value.opt(key).let { it is Int || it is Long }
    private fun sameQuery(observed: String, expected: String) = observed.trim().replace(Regex("\\s+"), " ")
        .equals(expected.trim().replace(Regex("\\s+"), " "), ignoreCase = true)
}
