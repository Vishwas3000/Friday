package dev.layatest.agent

import dev.layatest.agent.coordinator.TaskRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Owner inputs remain available while a task is incomplete; observed app answers have separate provenance. */
internal object TaskInputContext {
    const val KEY = "task_context"
    val fields = mapOf(
        "calendar.create" to setOf("date", "start", "end", "duration", "title", "zone", "all_day", "overnight", "action"),
        "app.search" to setOf("query"),
    )

    data class Source(val id: String, val text: String, val at: Long, val date: LocalDate, val zone: ZoneId) {
        fun json() = JSONObject().put("id", id).put("text", text).put("at", at)
            .put("date", date.toString()).put("zone", zone.id)
    }

    data class Value(val text: String, val value: String, val source: String, val start: Int, val end: Int, val decoder: String = "native") {
        fun json() = JSONObject().put("text", text).put("value", value).put("source", source).put("start", start).put("end", end).put("decoder", decoder)
    }

    data class Draft(
        val chatId: String,
        val capability: String,
        val goal: String,
        val sources: List<Source>,
        val values: Map<String, Value> = emptyMap(),
        val missing: Set<String> = emptySet(),
        val question: String = "",
        val previousTask: String? = null,
        val blocker: String? = null,
        val packageName: String? = null,
    ) {
        fun json() = JSONObject().put("version", 1).put("chat_id", chatId).put("capability", capability)
            .put("goal", goal).put("sources", JSONArray(sources.map(Source::json)))
            .put("fields", JSONObject().also { rows -> values.forEach { (key, value) -> rows.put(key, value.json()) } })
            .put("missing", JSONArray(missing.toList())).put("question", question).apply {
                previousTask?.let { put("previous_task_id", it) }
                blocker?.let { put("blocker", it) }
                packageName?.let { put("destination_package", it) }
            }

        fun append(source: Source): Draft {
            require(sources.none { it.id == source.id && it != source }) { "An owner source changed" }
            if (sources.any { it.id == source.id }) return this
            val appended = sources + source
            val retained = values.values.map { it.source }.toSet() + appended.filter { it.text == goal }.map { it.id } + appended.takeLast(8).map { it.id }
            return copy(sources = appended.filter { it.id in retained })
        }

        fun merge(updates: Map<String, Value>): Draft {
            val allowed = fields.getValue(capability)
            updates.forEach { (key, value) ->
                require(key in allowed) { "Unregistered input field: $key" }
                val source = sources.single { it.id == value.source }
                require(value.start >= 0 && value.end > value.start && value.end <= source.text.length &&
                    source.text.substring(value.start, value.end) == value.text && value.value.isNotBlank() && value.decoder in setOf("native", "writer")) {
                    "An input must cite an exact owner span"
                }
            }
            return copy(values = values + updates)
        }
    }

    fun source(text: String, zone: ZoneId = ZoneId.systemDefault(), at: Long = System.currentTimeMillis()) =
        Source("owner-${UUID.randomUUID()}", text, at, Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), zone)

    fun source(record: TaskRecord, zone: ZoneId = ZoneId.systemDefault()): Source =
        Source("owner-${record.id}", record.request, record.createdAt,
            Instant.ofEpochMilli(record.createdAt).atZone(zone).toLocalDate(), zone)

    fun value(source: Source, range: IntRange, normalized: String? = null): Value {
        val text = source.text.substring(range)
        return Value(text, normalized ?: text, source.id, range.first, range.last + 1)
    }

    fun start(chatId: String, capability: String, source: Source): Draft {
        require(chatId.isNotBlank() && capability in fields && source.text.isNotBlank())
        return Draft(chatId, capability, source.text, listOf(source))
    }

    fun saved(record: TaskRecord, chatId: String): Draft? {
        if (record.chatId != chatId || record.deleted) return null
        return runCatching {
            val steps = JSONObject(record.payload).getJSONArray("steps")
            val contexts = (0 until steps.length()).mapNotNull {
                steps.getJSONObject(it).optJSONObject("inputs")?.optJSONObject(KEY)
            }
            val context = contexts.firstOrNull() ?: return null
            require(contexts.all { it.toString() == context.toString() }) { "Conflicting task contexts" }
            decode(context, chatId).copy(previousTask = record.id)
        }.getOrNull()
    }

    fun decode(json: JSONObject, chatId: String): Draft {
        require(json.getInt("version") == 1 && json.getString("chat_id") == chatId)
        val capability = json.getString("capability")
        require(capability in fields)
        val array = json.getJSONArray("sources")
        require(array.length() in 1..32)
        val sources = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            Source(row.getString("id"), row.getString("text"), row.getLong("at"),
                LocalDate.parse(row.getString("date")), ZoneId.of(row.getString("zone")))
        }
        require(sources.map { it.id }.distinct().size == sources.size && sources.all { it.text.isNotBlank() })
        val supplied = json.getJSONObject("fields")
        val values = supplied.keys().asSequence().associateWith { key ->
            val row = supplied.getJSONObject(key)
            Value(row.getString("text"), row.getString("value"), row.getString("source"), row.getInt("start"), row.getInt("end"), row.optString("decoder", "native"))
        }
        val missing = json.getJSONArray("missing").let { a -> (0 until a.length()).map(a::getString).toSet() }
        require(missing.all { it in fields.getValue(capability) })
        val draft = Draft(chatId, capability, json.getString("goal"), sources, missing = missing,
            question = json.optString("question"), previousTask = json.optString("previous_task_id").takeIf(String::isNotBlank),
            blocker = json.optString("blocker").takeIf(String::isNotBlank),
            packageName = json.optString("destination_package").takeIf(String::isNotBlank))
        require(sources.any { it.text == draft.goal }) { "The goal has no owner source" }
        return draft.merge(values)
    }

    fun referencesPrevious(text: String) = Regex("\\b(?:above|previous|earlier|same|that|it|continue|instead|actually|change|correct)\\b",
        RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun cancels(text: String) = Regex("^(?:please\\s+)?(?:cancel|stop|never\\s*mind|forget\\s+it)(?:\\s+(?:that|this|the\\s+task))?[.!]?\\s*$",
        RegexOption.IGNORE_CASE).matches(text.trim())

    // The model may interpret fields, but it cannot select an app or authorize a device action.
    private fun modelFields(capability: String) = fields.getValue(capability) - setOf("action", "all_day", "overnight")

    fun writerRequest(draft: Draft, source: Source): WriterRequest {
        val envelope = JSONObject().put("capability", draft.capability).put("fields", JSONArray(modelFields(draft.capability).toList()))
            .put("current", source.text).put("date", source.date.toString()).put("zone", source.zone.id)
            .put("missing", JSONArray(draft.missing.toList())).put("question", draft.question.take(200))
            .put("known", JSONObject().also { known -> draft.values.forEach { (key, value) -> known.put(key, value.value.take(160)) } })
        return WriterRequest("task_update", draft.goal.take(400), envelope.toString(), maxTokens = 192, timeoutMs = 20000)
    }

    fun modelInputError(text: String): String? = runCatching {
        val input = JSONObject(text)
        val allowed = modelFields(input.getString("capability"))
        val listed = input.getJSONArray("fields").let { a -> (0 until a.length()).map(a::getString).toSet() }
        require(listed == allowed && input.getString("current").isNotBlank())
        LocalDate.parse(input.getString("date"))
        ZoneId.of(input.getString("zone"))
        null
    }.getOrElse { "Task updates need a registered field schema and current owner input." }

    fun patchErrors(text: String, patch: JSONObject): List<String> = runCatching {
        require(modelInputError(text) == null)
        val input = JSONObject(text)
        require(patch.keys().asSequence().toSet() == setOf("operation", "updates"))
        require(patch.getString("operation") in setOf("update", "new", "clarify"))
        val updates = patch.getJSONArray("updates")
        require(updates.length() in 0..8)
        require((patch.getString("operation") == "update") == (updates.length() > 0))
        val owner = source(input.getString("current"), ZoneId.of(input.getString("zone")))
            .copy(date = LocalDate.parse(input.getString("date")))
        val missing = input.optJSONArray("missing")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
        val native = if (input.getString("capability") == "calendar.create")
            CalendarRequest.capture(owner, missing, input.optJSONObject("known")?.optString("start")).also {
                require(it.error == null) { it.error.orEmpty() }
            }.fields else emptyMap()
        val seen = HashSet<String>()
        for (index in 0 until updates.length()) {
            val row = updates.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("field", "text", "value"))
            val field = row.getString("field")
            val span = row.getString("text")
            val value = row.getString("value")
            require(field in modelFields(input.getString("capability")) && seen.add(field))
            val start = owner.text.indexOf(span)
            require(span.isNotBlank() && value.isNotBlank() && start >= 0 && owner.text.indexOf(span, start + 1) < 0)
            if (input.getString("capability") == "calendar.create") require(CalendarRequest.constraintError(owner.text) == null)
            native[field]?.let { require(it.value == value) { "The patch contradicts a decoded owner field" } }
            when (field) {
                "date" -> require(!LocalDate.parse(value).isBefore(owner.date))
                "start", "end" -> { require(Regex("\\d{2}:\\d{2}").matches(value)); LocalTime.parse(value) }
                "duration" -> require(value.toLongOrNull()?.let { it > 0 } == true)
                "query", "title", "zone" -> require(value == span.trim())
                else -> error("Unsupported task field")
            }
        }
        emptyList<String>()
    }.getOrElse { listOf(it.message ?: "Invalid task field patch") }

    fun applyPatch(draft: Draft, source: Source, patch: JSONObject): Draft {
        require(patchErrors(writerRequest(draft, source).text, patch).isEmpty())
        val rows = patch.getJSONArray("updates")
        val updates = (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            val span = row.getString("text")
            val start = source.text.indexOf(span)
            row.getString("field") to value(source, start until start + span.length, row.getString("value")).copy(decoder = "writer")
        }
        val previous = if ("start" in updates && draft.values["all_day"]?.value == "implicit") draft.copy(values = draft.values - "all_day") else draft
        return previous.append(source).merge(updates)
    }

    fun selfCheck(): String {
        val source = source("block my calendar on Friday at 2pm", ZoneId.of("Asia/Kolkata"), 1792579405000)
        val draft = start("chat-a", "calendar.create", source).merge(mapOf("start" to value(source,
            source.text.indexOf("2pm")..source.text.lastIndex, "14:00")))
        check(decode(draft.json(), "chat-a") == draft)
        check(runCatching { decode(draft.json(), "chat-b") }.isFailure)
        check(runCatching { draft.merge(mapOf("package" to draft.values.getValue("start"))) }.isFailure)
        check(runCatching { draft.merge(mapOf("start" to draft.values.getValue("start").copy(text = "3pm"))) }.isFailure)
        check(referencesPrevious("block my calendar for the above task") && cancels("cancel that"))
        check(!cancels("do not cancel that"))
        return "ok"
    }
}
