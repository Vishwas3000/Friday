package dev.layatest.agent

import dev.layatest.agent.coordinator.DevicePlanner
import dev.layatest.agent.coordinator.DeviceStep
import dev.layatest.agent.coordinator.OutputBinding
import dev.layatest.agent.coordinator.TaskRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic owner turns only. These checks never create events, alarms or app drafts. */
internal object TaskIntakeChecks {
    fun run(): String = try { checks() } catch (error: Exception) {
        val location = error.stackTrace.firstOrNull { it.className.startsWith("dev.layatest.agent") }
        throw IllegalStateException("Task context check at $location: ${error.message}", error)
    }

    private fun checks(): String {
        check(TaskInputContext.selfCheck() == "ok")
        val chat = "fixture-inputs"
        val zone = ZoneId.of("Asia/Kolkata")
        val at = Instant.parse("2026-10-21T10:43:25Z").toEpochMilli()
        val today = LocalDate.of(2026, 10, 21)
        fun source(text: String) = TaskInputContext.source(text, zone, at)
        fun start(text: String): TaskInputContext.Draft {
            val owner = source(text)
            return CalendarInputAdapter.update(TaskInputContext.start(chat, "calendar.create", owner), owner)
        }
        fun row(text: String, offset: Long) = TaskRecord("fixture-$offset", chat, text, capability = "device", state = "WAITING_INPUT",
            createdAt = at + offset, updatedAt = at + offset)
        val legacy = listOf(
            row("this Friday, 2:00 p.m. Indian time", 2),
            row("block my calendar for the above task", 1),
            row("log my calendar on this Friday at 2:00 p.m. Indian time", 0),
        )
        val restored = TaskIntake.restore(chat, legacy)!!
        check(restored.values.getValue("date").value == "2026-10-23") { "Lost the earlier date" }
        check(restored.values.getValue("start").value == "14:00") { "Lost the earlier time" }
        check(restored.values.getValue("zone").text == "Indian time")
        check(restored.values.getValue("action").text == "block")
        check("title" !in restored.values) { "A reference became an event title" }
        check(restored.missing == setOf("end"))
        val pending = CalendarInputAdapter.inputs(restored, today).first
        check(pending.has("validation_error") && !pending.has("start_ms"))
        val completed = CalendarInputAdapter.inputs(CalendarInputAdapter.update(restored, source("30 minutes.")), today).first
        check(completed.getLong("end_ms") - completed.getLong("start_ms") == 1800000L)
        check(ZoneId.of(completed.getString("zone")).rules == zone.rules)
        check(completed.getString("title") == "Busy")
        check(Instant.ofEpochMilli(completed.getLong("start_ms")).atZone(zone).hour == 14)

        val noDate = CalendarInputAdapter.inputs(start("block my calendar"), today).second
        val combined = CalendarInputAdapter.inputs(CalendarInputAdapter.update(noDate,
            source("this Friday at 2pm Indian time for 45 minutes to meet the designer")), today)
        check(combined.second.missing.isEmpty()) { combined.first.toString() }
        check(combined.first.getString("title") == "meet the designer")
        val corrected = CalendarInputAdapter.inputs(CalendarInputAdapter.update(combined.second, source("actually at 3pm")), today).first
        check(corrected.getLong("end_ms") - corrected.getLong("start_ms") == 2700000L)
        check(Instant.ofEpochMilli(corrected.getLong("start_ms")).atZone(zone).hour == 15)
        val repeated = CalendarInputAdapter.inputs(CalendarInputAdapter.update(restored, source("2pm IST")), today).second
        check(repeated.missing == setOf("end") && "end" !in repeated.values)
        val noTitle = CalendarInputAdapter.inputs(start("create an event on this Friday at 2pm for 30 minutes"), today).second
        check(noTitle.missing == setOf("title"))
        check(CalendarInputAdapter.inputs(CalendarInputAdapter.update(noTitle, source("QA meeting")), today).first.getString("title") == "QA meeting")
        check(CalendarInputAdapter.inputs(CalendarInputAdapter.update(noTitle, source("Weekly review")), today).first.getString("title") == "Weekly review")
        for (answer in listOf("October 32 at 3pm", "01/07/2027 at 3pm", "at 25:00", "for 0 minutes", "for 1 hour and", "not 2pm", "for 30 minutes and 10 seconds", "30 minutes and open WhatsApp")) {
            check(CalendarInputAdapter.inputs(CalendarInputAdapter.update(combined.second, source(answer)), today).first.has("validation_error")) { "Ignored invalid correction: $answer" }
        }
        for (answer in listOf("30 minutes", "for 30 minutes", "until 2:30pm", "to 14:30")) {
            val completedReply = CalendarInputAdapter.inputs(CalendarInputAdapter.update(restored, source(answer)), today).first
            check(completedReply.getLong("end_ms") - completedReply.getLong("start_ms") == 1800000L) { answer }
        }
        check(CalendarInputAdapter.inputs(start("block my calendar on this Friday"), today).first.getBoolean("all_day"))
        check(CalendarInputAdapter.inputs(start("block my calendar on 2020-01-01 at 2pm for 30 minutes"), today).first.has("validation_error"))
        for (constraint in listOf("but do not save yet", "every week", "invite the team", "at the office", "Indian time at the office")) {
            check(CalendarInputAdapter.inputs(start("block my calendar on this Friday at 2pm for 30 minutes $constraint"), today).first.has("validation_error")) { constraint }
        }
        check(CalendarInputAdapter.inputs(start("block my calendar on Friday or Saturday at 2pm for 30 minutes"), today).first.has("validation_error"))
        check(CalendarInputAdapter.inputs(start("block my calendar on this Friday all day at 2pm for 30 minutes"), today).first.has("validation_error"))
        val overnight = CalendarInputAdapter.inputs(start("block my calendar on this Friday from 11pm to 1am next day"), today).first
        check(overnight.getLong("end_ms") - overnight.getLong("start_ms") == 7200000L)

        fun saved(request: String, draft: TaskInputContext.Draft, capability: String = "input.clarify"): TaskRecord {
            val input = if (capability == "app.search") JSONObject().put("package", draft.packageName).put("query", "")
                else JSONObject().put("state", "WAITING_INPUT").put("question", draft.question.ifBlank { "Give the missing detail." })
            return DevicePlanner.plan(chat, request, listOf(DeviceStep(capability, input.put(TaskInputContext.KEY, draft.json())))).task
                .copy(state = "WAITING_INPUT")
        }
        val stored = saved(legacy.first().request, restored)
        check(TaskInputContext.saved(stored, chat)?.values == restored.values)
        check(TaskIntake.restore("different-chat", listOf(stored)) == null)
        check(TaskIntake.restore(chat, listOf(stored.copy(state = "COMPLETED"), stored)) == null)
        check(TaskIntake.plan("open Spotify", chat, listOf(stored), write = { error("New task loaded Writer") }) == null)
        check(TaskIntake.plan("write a message saying I cannot attend that meeting", chat, listOf(stored),
            write = { error("Message contents became calendar inputs") }) == null)
        val cancelled = TaskIntake.plan("cancel that", chat, listOf(stored), write = { error("Cancellation loaded Writer") })!!.single()
        check(cancelled.inputs.getString("cancel_task_id") == stored.id && cancelled.inputs.getString("state") == "CANCELLED")
        val safe = TaskIntake.plan("30 minutes", chat, listOf(stored), dispatched = true, write = { error("Unsafe retry loaded Writer") })!!.single()
        check(safe.capability == "input.clarify" && safe.inputs.getString("state") == "BLOCKED")
        check(TaskIntake.plan("30 minutes", chat, listOf(stored.copy(state = "OUTCOME_UNKNOWN")), dispatched = true,
            write = { error("Uncertain external action repeated") })!!.single().capability == "input.clarify")
        val blockedRecord = DevicePlanner.plan(chat, "30 minutes", listOf(safe)).task.copy(state = "BLOCKED")
        check(TaskIntake.plan("45 minutes", chat, listOf(blockedRecord), write = { error("Repeated unsafe retry") })!!.single().capability == "input.clarify")

        val searchSource = source("search in Spotify")
        val searchDraft = TaskInputContext.start(chat, "app.search", searchSource).copy(packageName = "com.spotify.music", missing = setOf("query"))
        val searchRecord = saved(searchSource.text, searchDraft, "app.search")
        val searchPlan = TaskIntake.plan("weather in Bangalore", chat, listOf(searchRecord), write = { error("Literal query loaded Writer") })!!
        check(searchPlan.single().inputs.getString("query") == "weather in Bangalore")
        check(searchPlan.single().inputs.getString("package") == "com.spotify.music")
        DevicePlanner.plan(chat, "weather in Bangalore", searchPlan)
        val unresolved = TaskIntake.plan("same as above", chat, listOf(searchRecord), write = {
            JSONObject().put("status", "unavailable").put("error_code", "memory_pressure")
        })!!.single()
        check(unresolved.capability == "input.clarify" && unresolved.inputs.getString("state") == "BLOCKED")
        check(TaskInputContext.decode(unresolved.inputs.getJSONObject(TaskInputContext.KEY), chat).packageName == "com.spotify.music")

        val reply = source("make it half an hour")
        val patch = JSONObject("""{"operation":"update","updates":[{"field":"duration","text":"half an hour","value":"30"}]}""")
        val writer = TaskInputContext.writerRequest(restored, reply)
        check(writer.errors().isEmpty()) { writer.errors().toString() }
        check(Writer.validate(writer, patch.toString()).second.isEmpty())
        check(TaskInputContext.applyPatch(restored, reply, patch).values.getValue("duration").value == "30")
        check(TaskInputContext.applyPatch(restored, reply, patch).values.getValue("duration").decoder == "writer")
        for (bad in listOf(
            JSONObject(patch.toString()).apply { getJSONArray("updates").getJSONObject(0).put("field", "package") },
            JSONObject(patch.toString()).apply { getJSONArray("updates").getJSONObject(0).put("field", "action") },
            JSONObject(patch.toString()).apply { getJSONArray("updates").getJSONObject(0).put("text", "two hours") },
            JSONObject(patch.toString()).apply { getJSONArray("updates").getJSONObject(0).put("value", "-30") },
        )) check(Writer.validate(writer, bad.toString()).second.isNotEmpty())
        val numeric = TaskInputContext.writerRequest(restored, source("for 45 minutes"))
        val wrong = JSONObject(patch.toString()).apply { getJSONArray("updates").getJSONObject(0).put("text", "45 minutes") }
        check(Writer.validate(numeric, wrong.toString()).second.isNotEmpty())
        val modelPlan = TaskIntake.plan(reply.text, chat, listOf(stored), write = { patch.put("status", "ok") })!!.single()
        check(modelPlan.inputs.getJSONObject(TaskInputContext.KEY).getJSONObject("fields").getJSONObject("duration").getString("text") == "half an hour")
        val forged = TaskIntake.plan(reply.text, chat, listOf(stored), write = {
            JSONObject().put("status", "ok").put("operation", "update").put("updates", JSONArray().put(JSONObject()
                .put("field", "duration").put("text", "invented").put("value", "99")))
        })!!.single()
        check(forged.capability == "input.clarify")
        check(runCatching { DevicePlanner.plan(chat, searchSource.text, listOf(
            DeviceStep("text.draft", JSONObject().put("verbatim", true).put("text", "fixture")),
            DeviceStep("app.search", JSONObject().put("package", "com.spotify.music").put("query", "test"), listOf(0),
                mapOf(TaskInputContext.KEY to OutputBinding(0, "text")))
        )) }.isFailure)
        return "ok"
    }
}
