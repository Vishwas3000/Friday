package dev.layatest.agent

import dev.layatest.agent.coordinator.TaskStore
import org.json.JSONObject

/** Request interpretation produces data for registered capabilities; it never dispatches app actions. */
internal object DevicePlans {
    data class Plan(val capability: String, val inputs: JSONObject)

    fun from(ctx: SkillContext, writer: WriterRequest?, chatId: String): Plan? {
        if (CalendarRequest.matches(ctx.request)) {
            val parsed = CalendarRequest.parse(ctx.request)
            return Plan("calendar.create", parsed.event?.let(CalendarEvents::inputs)
                ?: JSONObject().put("validation_error", parsed.error ?: "Give the calendar event details."))
        }
        if (ctx.card.skill == "bill_query") {
            val named = ctx.apps.named(ctx.request)
            val previous = if (named == null) previousBillPackage(ctx, chatId) else null
            val app = named ?: previous?.let { pkg -> ctx.apps.installed.firstOrNull { it.pkg == pkg } }
                ?: ctx.appFor("bill_query")
            return Plan("bills.read", JSONObject().put("package", app?.pkg.orEmpty())
                .put("total_requested", totalBillAsked(ctx.request)))
        }
        if (writer != null && !DraftHandoff.parse(ctx.request, writer.kind, ctx.apps.installed).requested) {
            val exact = exactDraftText(taskPart(ctx.request)).takeIf { writer.kind in setOf("message", "email") }
            val inputs = JSONObject().put("instruction", writer.instruction).put("kind", writer.kind).put("text", exact ?: writer.text)
                .put("max_tokens", writer.maxTokens).put("timeout_ms", writer.timeoutMs).put("model", writer.model)
            if (exact != null) inputs.put("verbatim", true)
            writer.errors().firstOrNull()?.let { inputs.put("validation_error", it) }
            return Plan("text.draft", inputs)
        }
        if (ctx.card.skill == "open_app") {
            val app = ctx.apps.named(ctx.request) ?: return null
            if (taskAfterOpening(ctx.request, app.label) == null)
                return Plan("app.open", JSONObject().put("package", app.pkg))
            val task = taskPart(ctx.request).trim()
            if (Regex("^(?:search|look (?:for|up)|find)\\b", RegexOption.IGNORE_CASE).containsMatchIn(task)) {
                val query = ctx.card.query?.takeIf { it.isNotBlank() } ?: return null
                return Plan("app.search", JSONObject().put("package", app.pkg).put("query", query))
            }
        }
        return null
    }

    private fun previousBillPackage(ctx: SkillContext, chatId: String): String? {
        val latest = TaskStore.get(ctx.android).latestForChat(chatId)?.task ?: return null
        if (latest.capability != "device") return null
        val definitions = JSONObject(latest.payload).optJSONArray("steps") ?: return null
        for (index in 0 until definitions.length()) {
            val definition = definitions.getJSONObject(index)
            if (definition.optString("capability") == "bills.read")
                return definition.getJSONObject("inputs").optString("package").takeIf { it in AppResolver.MONEY }
        }
        return null
    }
}
