package dev.layatest.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import dev.layatest.agent.coordinator.CapabilityResult
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The platform calendar provider saves and verifies events without a model or screen coordinates. */
internal object CalendarEvents {
    val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    @Volatile var permissionResult: Boolean? = null

    fun hasAccess(context: Context) = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Read-only test oracle: a fresh scoped query, independent of a task's claimed result/evidence. */
    fun observe(context: Context, title: String, from: Long, until: Long): JSONObject {
        require(title.isNotBlank() && title.length <= 200 && title.none(Char::isISOControl) && until > from)
        val observation = JSONObject().put("source_package", "com.android.providers.calendar")
            .put("captured_at", System.currentTimeMillis()).put("query_complete", false).put("events", JSONArray())
        if (!hasAccess(context)) return observation.put("status", "unavailable").put("reason", "Calendar permission is required")
        return try {
            val events = JSONArray()
            val cursor = context.contentResolver.query(Events.CONTENT_URI, projection,
                Events.TITLE + " = ? AND " + Events.DTSTART + " >= ? AND " + Events.DTSTART + " < ? AND " + Events.DELETED + " = 0",
                arrayOf(title, from.toString(), until.toString()), null)
                ?: return observation.put("status", "unavailable").put("reason", "Calendar query unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    if (events.length() >= 50) return observation.put("status", "incomplete").put("events", events)
                    val row = record(it)
                    if (row.status != Events.STATUS_CANCELED) events.put(JSONObject().put("event_id", row.id)
                        .put("title", row.title).put("start_ms", row.start).put("end_ms", row.end)
                        .put("all_day", row.allDay == 1).put("zone", row.zone).put("busy", row.availability == Events.AVAILABILITY_BUSY))
                }
            }
            observation.put("status", "ok").put("query_complete", true).put("events", events)
        } catch (_: Exception) { observation.put("status", "unavailable").put("reason", "Calendar query failed") }
    }

    fun metadata(event: CalendarRequest.Event) = JSONObject().put("title", event.title)
        .put("start", event.start.toString()).put("end_exclusive", event.end.toString())
        .put("all_day", event.allDay).put("time_zone", event.start.zone.id).put("availability", "busy")
        .put("inferred_year", event.inferredYear)

    fun inputs(event: CalendarRequest.Event): JSONObject {
        val fields = values(event, 0)
        return JSONObject().put("package", "com.android.providers.calendar").put("title", event.title)
            .put("start_ms", fields.getAsLong(Events.DTSTART)).put("end_ms", fields.getAsLong(Events.DTEND))
            .put("all_day", event.allDay).put("zone", fields.getAsString(Events.EVENT_TIMEZONE))
            .put("local_date", event.start.toLocalDate().toString()).put("inferred_year", event.inferredYear)
    }

    private fun event(inputs: JSONObject) = CalendarRequest.Event(inputs.getString("title"),
        Instant.ofEpochMilli(inputs.getLong("start_ms")).atZone(ZoneId.of(inputs.getString("zone"))),
        Instant.ofEpochMilli(inputs.getLong("end_ms")).atZone(ZoneId.of(inputs.getString("zone"))),
        inputs.getBoolean("all_day"), inputs.optBoolean("inferred_year"))

    /** Recovery only observes the provider; it never inserts or opens a composer. */
    fun inspect(ctx: SkillContext, inputs: JSONObject, taskId: String): CapabilityResult? {
        if (!hasAccess(ctx.android)) return null
        return try {
            val target = targetCalendar(ctx, taskId, choose = false) ?: return null
            val event = event(inputs)
            val expected = values(event, target.id)
            existing(ctx.android, expected).firstOrNull { matches(it, expected) }
                ?.let { completed(event, it, expected, true, inputs) }
        } catch (_: Exception) {
            CapabilityResult("BLOCKED", message = "Calendar couldn't be inspected. I won't repeat an uncertain save.")
        }
    }

    fun execute(ctx: SkillContext, inputs: JSONObject, taskId: String, beforeDispatch: () -> Boolean): CapabilityResult {
        val event = event(inputs)
        if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before saving the calendar event.")
        if (!hasAccess(ctx.android)) {
            permissionResult = null
            ctx.say("Allow Calendar access on your phone to save and check the event…")
            if (!ctx.launch(Intent(ctx.android, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.ASK_CALENDAR, true)))
                return CapabilityResult("BLOCKED", message = "I couldn't request Calendar access. The event has not been saved.")
            ctx.waitUntil(60_000) { hasAccess(ctx.android) || permissionResult != null }
            if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before saving the calendar event.")
            if (!hasAccess(ctx.android)) return CapabilityResult("BLOCKED", message = "Calendar access wasn't granted. Allow Calendar access on the phone, then Resume this saved task.")
        }
        var insertionAttempted = false
        return try {
            val selected = targetCalendar(ctx, taskId, choose = true)
                ?: return CapabilityResult("WAITING_INPUT", message = "I need a visible, writable calendar selection before saving this event.")
            val expected = values(event, selected.id)
            ctx.say("Checking for an existing calendar block…")
            val existing = existing(ctx.android, expected)
            if (existing.isNotEmpty() && existing.none { matches(it, expected) })
                return CapabilityResult("BLOCKED", message = "An event with this title and time already exists, but its Busy status or time zone differs. I didn't create a duplicate; check it in Calendar.")
            if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before saving the calendar event.")
            val reused = existing.firstOrNull { matches(it, expected) }
            val uri = if (reused != null) ContentUris.withAppendedId(Events.CONTENT_URI, reused.id) else {
                ctx.say("Saving calendar block: " + event.title + "…")
                if (!beforeDispatch() || ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before saving the calendar event.")
                insertionAttempted = true
                ctx.android.contentResolver.insert(Events.CONTENT_URI, expected)
                    ?: return CapabilityResult("OUTCOME_UNKNOWN", message = "Calendar didn't return a saved event. I couldn't verify a save; check Calendar before retrying.")
            }
            ctx.say("Checking the saved title, date and Busy status…")
            val found = ctx.android.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) record(it) else null
            }
            if (found == null || !matches(found, expected))
                return CapabilityResult("OUTCOME_UNKNOWN", message = "Calendar may have saved the event, but readback didn't match all requested fields. Check Calendar before retrying; I didn't submit it again.")
            val result = completed(event, found, expected, reused != null, inputs)
            // Persisted provider readback proves success even when the app cannot expose its screen.
            val opened = !ctx.stopped && ctx.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/event")
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, expected.getAsLong(Events.DTSTART))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, expected.getAsLong(Events.DTEND)))
            if (opened || ctx.stopped) result else result.copy(message = result.message + " I couldn't open the saved event in a calendar app.")
        } catch (_: SecurityException) {
            CapabilityResult(if (insertionAttempted) "OUTCOME_UNKNOWN" else "BLOCKED",
                message = "Calendar access was unavailable. I couldn't complete or verify the save; check Calendar before retrying.")
        } catch (_: Exception) {
            CapabilityResult(if (insertionAttempted) "OUTCOME_UNKNOWN" else "BLOCKED",
                message = "I couldn't complete or verify the calendar save. Check Calendar before retrying.")
        }
    }

    private fun completed(event: CalendarRequest.Event, found: Record, expected: ContentValues, reused: Boolean, inputs: JSONObject): CapabilityResult {
        val output = JSONObject().put("title", found.title).put("event_id", found.id).put("calendar_id", found.calendar)
            .put("start_ms", found.start).put("end_ms", found.end).put("all_day", found.allDay == 1)
            .put("zone", found.zone).put("busy", found.availability == Events.AVAILABILITY_BUSY).put("reused", reused)
            .put("inferred_year", event.inferredYear)
        check(matches(found, expected))
        val evidence = JSONObject(output.toString()).put("verified", true).put("saved", true)
            .put("source_package", "com.android.providers.calendar").put("capture_method", "calendar_provider_readback")
            .put("captured_at", System.currentTimeMillis()).put("input_bind", inputs.optString("_input_bind"))
        val date = event.start.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH))
        val time = if (event.allDay) "all day" else event.start.format(DateTimeFormatter.ofPattern("HH:mm")) +
            "–" + event.end.format(DateTimeFormatter.ofPattern("d MMM HH:mm")) + " " + event.start.zone.id
        val message = (if (reused) "Already blocked; no duplicate created: " else "Calendar blocked: ") +
            event.title + " — " + date + " (" + time + "; Busy). Saved fields verified." +
            (if (event.inferredYear) " I used the next occurrence of that date." else "")
        return CapabilityResult("COMPLETED", output, evidence, message)
    }

    private data class TargetCalendar(val id: Long, val name: String, val primary: Boolean)
    private fun targetCalendar(ctx: SkillContext, taskId: String, choose: Boolean): TargetCalendar? {
        val calendars = writableCalendars(ctx.android)
        val prefs = ctx.android.getSharedPreferences("friday", Context.MODE_PRIVATE)
        val key = "calendar_target." + taskId
        val bound = prefs.getLong(key, -1)
        if (bound > 0) return calendars.firstOrNull { it.id == bound }
        val selected = calendars.firstOrNull { it.id == prefs.getLong("calendar_id", -1) }
            ?: calendars.singleOrNull() ?: calendars.filter { it.primary }.singleOrNull()
            ?: if (choose) ctx.ask("Which calendar should I block?", calendars.mapIndexed { i, c -> safeLabel(c.name, i) })
                ?.let { calendars.getOrNull(it) } else null
        if (selected != null) check(prefs.edit().putLong(key, selected.id).putLong("calendar_id", selected.id).commit()) { "Calendar binding wasn't saved" }
        return selected
    }
    private fun writableCalendars(context: Context): List<TargetCalendar> {
        val out = ArrayList<TargetCalendar>()
        context.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.IS_PRIMARY),
            Calendars.VISIBLE + " = 1 AND " + Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) out += TargetCalendar(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2) == 1)
        } ?: error("Calendar query unavailable")
        return out
    }

    private fun safeLabel(name: String, index: Int) = name.replace(Regex("""[\w.+-]+@[\w.-]+"""), "Personal calendar")
        .ifBlank { "Calendar " + (index + 1) } + " (" + (index + 1) + ")"

    internal fun values(event: CalendarRequest.Event, calendarId: Long) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, event.title)
        // Provider all-day dates are UTC midnight; DTEND is the following date, exclusive.
        put(Events.DTSTART, if (event.allDay) event.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else event.start.toInstant().toEpochMilli())
        put(Events.DTEND, if (event.allDay) event.end.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else event.end.toInstant().toEpochMilli())
        put(Events.ALL_DAY, if (event.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else event.start.zone.id)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
    }

    private val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND,
        Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.AVAILABILITY, Events.DELETED, Events.STATUS)
    private data class Record(val id: Long, val calendar: Long, val title: String, val start: Long, val end: Long,
                              val allDay: Int, val zone: String, val availability: Int, val deleted: Int, val status: Int)
    private fun record(cursor: Cursor) = Record(cursor.getLong(0), cursor.getLong(1), cursor.getString(2).orEmpty(),
        cursor.getLong(3), cursor.getLong(4), cursor.getInt(5), cursor.getString(6).orEmpty(), cursor.getInt(7), cursor.getInt(8), cursor.getInt(9))
    private fun matches(record: Record, values: ContentValues) = record.id > 0 && record.deleted == 0 &&
        record.status != Events.STATUS_CANCELED && record.calendar == values.getAsLong(Events.CALENDAR_ID) &&
        record.title == values.getAsString(Events.TITLE) && record.start == values.getAsLong(Events.DTSTART) &&
        record.end == values.getAsLong(Events.DTEND) && record.allDay == values.getAsInteger(Events.ALL_DAY) &&
        record.zone == values.getAsString(Events.EVENT_TIMEZONE) && record.availability == Events.AVAILABILITY_BUSY

    private fun existing(context: Context, values: ContentValues): List<Record> {
        val keys = arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY)
        val selection = keys.joinToString(" AND ") { it + " = ?" } + " AND " + Events.DELETED + " = 0"
        return context.contentResolver.query(Events.CONTENT_URI, projection, selection,
            keys.map { values.getAsString(it) }.toTypedArray(), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) record(cursor).takeIf { it.status != Events.STATUS_CANCELED }?.let { add(it) } }
        } ?: error("Existing event query unavailable")
    }

    fun selfCheck(): String {
        CalendarRequest.selfCheck()
        val event = CalendarRequest.parse("block my calendar on Jan 7 for my QA review",
            LocalDate.of(2026, 10, 20), ZoneId.of("Asia/Kolkata")).event ?: error("calendar parse")
        val values = values(event, 73)
        check(values.getAsLong(Events.DTSTART) == LocalDate.of(2027, 1, 7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        check(values.getAsLong(Events.DTEND) - values.getAsLong(Events.DTSTART) == 86_400_000L)
        check(values.getAsInteger(Events.ALL_DAY) == 1 && values.getAsString(Events.EVENT_TIMEZONE) == "UTC")
        val saved = Record(12, 73, event.title, values.getAsLong(Events.DTSTART), values.getAsLong(Events.DTEND),
            1, "UTC", Events.AVAILABILITY_BUSY, 0, Events.STATUS_CONFIRMED)
        check(matches(saved, values))
        check(!matches(saved.copy(start = saved.start + 86_400_000), values))
        check(!matches(saved.copy(end = saved.end - 1), values))
        check(!matches(saved.copy(title = "Different"), values))
        check(!matches(saved.copy(calendar = 74), values))
        check(!matches(saved.copy(availability = Events.AVAILABILITY_FREE), values))
        check(!matches(saved.copy(deleted = 1), values))
        check(!matches(saved.copy(status = Events.STATUS_CANCELED), values))
        check(!matches(saved.copy(allDay = 0), values))
        check(!matches(saved.copy(zone = "Asia/Kolkata"), values))
        check(!safeLabel("person@example.test", 0).contains("@"))
        return "ok"
    }
}
