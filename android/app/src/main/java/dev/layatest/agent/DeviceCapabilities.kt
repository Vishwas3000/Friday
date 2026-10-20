package dev.layatest.agent

import dev.layatest.agent.coordinator.CapabilityDriver
import dev.layatest.agent.coordinator.CapabilityResult
import dev.layatest.agent.coordinator.StepRecord
import org.json.JSONObject
import java.security.MessageDigest

/** App and model operations live here; the coordinator owns plans, retries and completion checks. */
internal class DeviceCapabilities(
    private val ctx: SkillContext,
    private val write: (WriterRequest) -> JSONObject,
    private val interruptedNow: () -> Boolean,
) : CapabilityDriver {
    override val stopped get() = ctx.stopped
    override val interrupted get() = interruptedNow()
    override fun progress(message: String) = ctx.say(message)

    override fun inspect(step: StepRecord, inputs: JSONObject): CapabilityResult? = when (step.capability) {
        "calendar.create" -> CalendarEvents.inspect(ctx, inputs, step.taskId)
        "app.open" -> opened(inputs)
        "app.search" -> searched(inputs)
        "bills.read" -> bills(inputs)
        else -> null
    }

    override fun execute(step: StepRecord, inputs: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
        if (stopped) return blocked("CANCELLED", "Stopped before the next action.")
        return when (step.capability) {
            "calendar.create" -> CalendarEvents.execute(ctx, inputs, step.taskId, beforeDispatch)
            "app.open" -> {
                val app = app(inputs) ?: return blocked("UNSUPPORTED", "The selected app is not installed.")
                if (!beforeDispatch() || stopped) return blocked("CANCELLED", "Stopped before opening " + app.label + ".")
                ctx.say("Opening " + app.label + "…")
                if (!ctx.open(app)) return blocked("BLOCKED", "I couldn't open " + app.label + ".")
                ctx.waitUntil(5000) { ctx.foreground() == app.pkg }
                opened(inputs) ?: blocked("BLOCKED", "I requested " + app.label + ", but couldn't verify its foreground screen.")
            }
            "app.search" -> {
                val app = app(inputs) ?: return blocked("UNSUPPORTED", "The selected app is not installed.")
                val query = inputs.getString("query")
                if (Safety.isMoneyApp(app.pkg)) return blocked("UNSUPPORTED", "Search text entry in money apps is unavailable.")
                ctx.say("Searching " + app.label + " for " + query + "…")
                for (intent in searchIntents(app.pkg, query)) {
                    if (!beforeDispatch() || stopped) return blocked("CANCELLED", "Stopped before searching.")
                    if (ctx.launch(intent)) {
                        var result: CapabilityResult? = null
                        ctx.waitUntil(3000) { searched(inputs).also { result = it } != null }
                        result?.let { return it }
                    }
                }
                blocked("BLOCKED", "I couldn't verify search results for the exact query in " + app.label + ".")
            }
            "bills.read" -> {
                val app = app(inputs) ?: return blocked("UNSUPPORTED", "Name the installed app that holds your credit card bills.")
                if (app.pkg !in AppResolver.MONEY) return blocked("UNSUPPORTED", "Bill reading is not registered for the selected app.")
                if (ctx.foreground() != app.pkg) {
                    if (!beforeDispatch() || stopped) return blocked("CANCELLED", "Stopped before opening " + app.label + ".")
                    ctx.say("Opening " + app.label + " to read bills…")
                    if (!ctx.open(app)) return blocked("BLOCKED", "I couldn't open " + app.label + ".")
                }
                var screen = ctx.screen()
                ctx.waitUntil(4000) {
                    screen = ctx.screen()
                    screen.pkg == app.pkg && screen.texts.any { it.text.isNotBlank() }
                }
                if (stopped) return blocked("CANCELLED", "Stopped before reading bills.")
                if (screen.pkg != app.pkg || screen.texts.none { it.text.isNotBlank() })
                    return blocked("BLOCKED", app.label + " was requested, but its screen isn't available to Friday through accessibility. I couldn't read your bills or calculate a total.")
                bills(inputs, screen)?.let { return it }
                ctx.say("Finding labeled credit card dues…")
                if (!beforeDispatch() || stopped) return blocked("CANCELLED", "Stopped before navigating to bills.")
                ctx.runLaya("show the credit card bills and amounts due", maxSteps = 6)
                if (stopped) return blocked("CANCELLED", "Stopped before reading bills.")
                bills(inputs) ?: blocked("BLOCKED", "I couldn't verify labeled bill amounts in " + app.label + ". No total was calculated.")
            }
            "text.draft" -> {
                if (inputs.optBoolean("verbatim")) {
                    val output = JSONObject().put("status", "ok").put("text", inputs.getString("text"))
                    CapabilityResult("COMPLETED", output, evidence(inputs, "local.writer")
                        .put("generated", false).put("capture_method", "verbatim_text"), inputs.getString("text"))
                } else {
                    ctx.say("Writing the saved draft…")
                    val result = write(Writer.fromJson(inputs))
                    if (result.optString("status") != "ok")
                        return CapabilityResult("BLOCKED", result, message = Writer.answer(result))
                    val text = result.optString("text").ifBlank { result.optString("body") }
                    val output = JSONObject(result.toString()).put("text", text)
                    CapabilityResult("COMPLETED", output, evidence(inputs, "local.writer")
                        .put("generated", true).put("model", inputs.optString("model", Writer.MODEL))
                        .put("instruction_sha256", hash(inputs.getString("instruction"))), Writer.answer(result))
                }
            }
            else -> blocked("UNSUPPORTED", "Friday has no registered executor for this capability.")
        }
    }

    private fun app(inputs: JSONObject) = ctx.apps.installed.firstOrNull { it.pkg == inputs.optString("package") }

    private fun opened(inputs: JSONObject): CapabilityResult? {
        val pkg = inputs.optString("package")
        if (pkg.isBlank() || ctx.foreground() != pkg) return null
        return CapabilityResult("COMPLETED", JSONObject().put("package", pkg),
            evidence(inputs, pkg).put("foreground_package", pkg),
            "Opened " + (app(inputs)?.label ?: "the selected app") + ". Foreground checked.")
    }

    private fun searched(inputs: JSONObject): CapabilityResult? {
        val pkg = inputs.optString("package")
        if (pkg.isBlank() || ctx.foreground() != pkg) return null
        val screen = ctx.screen()
        if (screen.pkg != pkg) return null
        val query = inputs.optString("query")
        val words = Routes.contentWords(query)
        val text = screen.texts.joinToString("\n") { it.text }
        if (words.isEmpty() || !Routes.contentWords(text).containsAll(words)) return null
        val nodes = appNodes(screen)
        val searchRole = Regex("search.*(?:box|bar|query|input|text)|(?:query|omnibox)", RegexOption.IGNORE_CASE)
        val observedQuery = nodes.filter { it.optBoolean("editable") || searchRole.containsMatchIn(it.optString("resource_id")) }
            .map { it.optString("text") }.firstOrNull { it.trim() == query.trim() } ?: return null
        // An editable query alone is not a result. Require matching noneditable app content as well.
        val matched = nodes.filter { !it.optBoolean("editable") && it.optString("class").endsWith("TextView") &&
                !searchRole.containsMatchIn(it.optString("resource_id")) }
            .map { it.optString("text").ifBlank { it.optString("content_desc") } }
            .firstOrNull { line -> (Routes.contentWords(line) intersect words).size >= minOf(2, words.size) }
            ?: return null
        return CapabilityResult("COMPLETED", JSONObject().put("package", pkg).put("query", query),
            evidence(inputs, pkg).put("query_visible", true).put("results_visible", true)
                .put("observed_query", observedQuery.trim())
                .put("screen_text", text).put("source_text_sha256", hash(text)).put("matched_result", matched),
            "Search results checked in " + (app(inputs)?.label ?: "the selected app") + " for “" + query + "”.")
    }

    private fun bills(inputs: JSONObject, screen: Screen = ctx.screen()): CapabilityResult? {
        val pkg = inputs.optString("package")
        if (pkg.isBlank() || screen.pkg != pkg) return null
        val found = observeBillAmounts(screen, inputs.optBoolean("total_requested")) ?: return null
        val output = JSONObject().put("items", found.getJSONArray("items")).put("answer", found.getString("answer"))
        if (found.has("total_amount")) output.put("total_amount", found.getString("total_amount"))
        if (inputs.optBoolean("total_requested") && !found.optBoolean("explicit_total"))
            return CapabilityResult("BLOCKED", output, found, found.getString("answer"))
        val proof = JSONObject(found.toString()).put("verified", true).put("input_bind", inputs.optString("_input_bind"))
        return CapabilityResult("COMPLETED", output, proof, found.getString("answer"))
    }

    private fun appNodes(screen: Screen): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(node: JSONObject) {
            if (node.optBoolean("visible") && node.optString("package") == screen.pkg) out += node
            node.optJSONArray("children")?.let { children ->
                for (i in 0 until children.length()) children.optJSONObject(i)?.let(::visit)
            }
        }
        screen.state?.optJSONArray("windows")?.let { windows ->
            for (i in 0 until windows.length()) windows.optJSONObject(i)?.let {
                if (it.optInt("type") == 1) it.optJSONObject("root")?.let(::visit)
            }
        }
        return out
    }

    private fun evidence(inputs: JSONObject, pkg: String) = JSONObject().put("verified", true)
        .put("source_package", pkg).put("captured_at", System.currentTimeMillis()).put("input_bind", inputs.optString("_input_bind"))
    private fun blocked(state: String, reason: String) = CapabilityResult(state, message = reason)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
