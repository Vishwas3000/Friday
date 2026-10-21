package dev.layatest.agent

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Calendar supplies its field decoder; task ownership and source merging are shared with other capabilities. */
internal object CalendarInputAdapter {
    fun update(draft: TaskInputContext.Draft, source: TaskInputContext.Source): TaskInputContext.Draft {
        val captured = CalendarRequest.capture(source, draft.missing, draft.values["start"]?.value)
        val fields = captured.fields.toMutableMap()
        val zoneValue = fields["zone"]
        val checkedText = zoneValue?.let { source.text.replaceRange(it.start, it.end, " ".repeat(it.end - it.start)) } ?: source.text
        val parsed = CalendarRequest.parse(checkedText, source.date, zone(zoneValue, source.zone) ?: source.zone)
        if (parsed.event?.allDay == true && "all_day" !in fields)
            fields["action"]?.let { fields["all_day"] = it.copy(value = "implicit") }
        val literalTitle = draft.missing == setOf("title") && fields.keys == setOf("title")
        val error = if (literalTitle) null else
            CalendarRequest.constraintError(source.text) ?: captured.error ?: parsed.error.takeIf { parsed.missing == null }
        val previous = if ("start" in fields && draft.values["all_day"]?.value == "implicit")
            draft.copy(values = draft.values - "all_day") else draft
        return previous.append(source).merge(fields).copy(blocker = error ?: draft.blocker)
    }

    private fun zone(value: TaskInputContext.Value?, fallback: ZoneId): ZoneId? {
        if (value == null) return fallback
        val raw = value.text.trim()
        if (raw.matches(Regex("(?:local|device)\\s+time", RegexOption.IGNORE_CASE))) return fallback
        runCatching { ZoneId.of(raw) }.getOrNull()?.let { return it }
        val localShort = java.util.TimeZone.getTimeZone(fallback).getDisplayName(false, java.util.TimeZone.SHORT, Locale.US)
        if (raw.equals(localShort, true)) return fallback
        runCatching { ZoneId.of(raw.uppercase(Locale.ROOT), ZoneId.SHORT_IDS) }.getOrNull()?.let { return it }
        val country = raw.lowercase(Locale.ROOT).replace(Regex("\\b(?:standard|daylight|summer|time)\\b"), "").trim()
        val countries = Locale.getISOCountries().filter { code ->
            val name = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
            country in setOf(name, name + "n", name + "an", name + "i")
        }
        if (countries.size != 1) return null
        val ids = android.icu.util.TimeZone.getAvailableIDs(countries.single()).mapNotNull {
            runCatching { ZoneId.of(it) }.getOrNull()
        }
        if (ids.map { it.rules }.distinct().size != 1) return null
        return ids.firstOrNull { it.rules == fallback.rules }?.let { fallback } ?: ids.firstOrNull()
    }

    fun inputs(draft: TaskInputContext.Draft, today: LocalDate? = null): Pair<JSONObject, TaskInputContext.Draft> {
        val values = draft.values
        fun pending(field: String, question: String): Pair<JSONObject, TaskInputContext.Draft> {
            val saved = draft.copy(missing = setOf(field), question = question)
            return JSONObject().put("validation_error", question).put(TaskInputContext.KEY, saved.json()) to saved
        }
        draft.blocker?.let { return pending("action", it) }
        if (values["action"] == null) return pending("action", "Do you want me to create a calendar event for these details? Say ‘block my calendar’ to confirm the action.")
        val date = values["date"]?.value ?: return pending("date", "What date should this calendar event use? The details already supplied are kept.")
        val fallback = draft.sources.first().zone
        val resolvedZone = zone(values["zone"], fallback)
            ?: return pending("zone", "Which exact time zone should I use? Reply with a city zone such as Asia/Kolkata; the other event details are kept.")
        val allDay = values["all_day"]?.value in setOf("true", "implicit")
        val start = values["start"]?.value
        if (!allDay && start == null) return pending("start", "What start time should I use, or should this be all day?")
        if (!allDay && values["end"] == null && values["duration"] == null)
            return pending("end", "I have $date at $start (${resolvedZone.id}). What end time or duration should I use?")
        if (values["action"]?.value == "create" && values["title"] == null)
            return pending("title", "What title should I give this event? The date and times are kept.")
        val request = buildString {
            append("block my calendar on ").append(date)
            values["title"]?.value?.let { append(" called ").append(JSONObject.quote(it)) }
            if (allDay) append(" all day")
            if (start != null) {
                append(" at ").append(start)
                values["end"]?.value?.let { append(" until ").append(it) }
                values["duration"]?.value?.let { append(" for ").append(it).append(" minutes") }
            }
            if (values["overnight"]?.value == "true") append(" next day")
        }
        val parsed = CalendarRequest.parse(request, today ?: LocalDate.now(resolvedZone), resolvedZone)
        if (parsed.event == null) return pending(parsed.missing ?: "end", parsed.error ?: "Please clarify the calendar event details.")
        val saved = draft.copy(missing = emptySet(), question = "")
        return CalendarEvents.inputs(parsed.event).put(TaskInputContext.KEY, saved.json()) to saved
    }
}
