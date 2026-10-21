package dev.layatest.agent

import dev.layatest.agent.coordinator.DeviceStep
import dev.layatest.agent.coordinator.TaskRecord
import dev.layatest.agent.coordinator.TaskStore
import org.json.JSONObject

/** Restore owner inputs before routing. A reply never becomes a new device action by accident. */
internal object TaskIntake {
    private val pending = setOf("WAITING_INPUT", "BLOCKED", "OUTCOME_UNKNOWN", "FAILED", "UNSUPPORTED")

    fun prepare(ctx: SkillContext, chatId: String, write: (WriterRequest) -> JSONObject): List<DeviceStep>? {
        val store = TaskStore.get(ctx.android)
        val records = store.dao.recentForChat(chatId, 12)
        val latestId = records.firstOrNull()?.id
        val externalSteps = latestId?.let { id -> store.dao.steps(id).filter {
            dev.layatest.agent.coordinator.DeviceCapabilities.registry[it.capability]?.effect == "external_write"
        }.map { it.id }.toSet() }.orEmpty()
        val dispatched = latestId?.let { store.dao.attempts(it).any { attempt ->
            attempt.stepId in externalSteps && attempt.dispatchState != "NOT_DISPATCHED"
        } } == true
        return plan(ctx.request, chatId, records, ctx.apps.search(ctx.request), dispatched, write)
    }

    internal fun plan(request: String, chatId: String, records: List<TaskRecord>, search: AppSearch? = null,
                      dispatched: Boolean = false, write: (WriterRequest) -> JSONObject): List<DeviceStep>? {
        val previous = restore(chatId, records)
        val source = TaskInputContext.source(request)
        val calendar = CalendarRequest.matches(request) || CalendarRequest.mentionsCalendar(request)
        val continuing = previous != null && (!calendar || TaskInputContext.referencesPrevious(request)) && search == null
        if (continuing && TaskInputContext.cancels(request)) {
            return listOf(DeviceStep("input.clarify", JSONObject().put("state", "CANCELLED")
                .put("question", "Cancelled the pending task.").put("cancel_task_id", previous!!.previousTask)
                .put("chat_id", chatId)))
        }
        if (continuing && !calendar && explicitNew(request)) return null
        val draft = when {
            calendar -> if (continuing && previous!!.capability == "calendar.create") previous
                else TaskInputContext.start(chatId, "calendar.create", source)
            search?.app != null -> TaskInputContext.start(chatId, "app.search", source).copy(packageName = search.app.pkg)
            continuing -> previous
            else -> null
        } ?: return null
        if (draft.previousTask != null && dispatched) {
            val question = "The earlier action may already have happened. Resume that task to check its result before retrying."
            return clarification(draft.append(source).copy(blocker = question), question, "BLOCKED")
        }
        if (draft.blocker != null) return clarification(draft.append(source), draft.blocker)

        var updated = when (draft.capability) {
            "calendar.create" -> CalendarInputAdapter.update(draft, source)
            "app.search" -> {
                val query = search?.query ?: request.trim().removeSurrounding("\"").takeUnless {
                    TaskInputContext.referencesPrevious(it)
                }.orEmpty()
                val start = source.text.indexOf(query)
                if (query.isNotBlank() && start >= 0) draft.append(source).merge(mapOf("query" to
                    TaskInputContext.value(source, start until start + query.length))) else draft.append(source)
            }
            else -> return null
        }
        val changed = updated.values != draft.values
        if (continuing && !changed && updated.blocker == null && !calendar) {
            val result = write(TaskInputContext.writerRequest(updated, source))
            if (result.optString("status") != "ok") return clarification(updated,
                "I kept the task details, but couldn't interpret this reply. " + Writer.answer(result), "BLOCKED")
            val patch = JSONObject().put("operation", result.opt("operation")).put("updates", result.opt("updates"))
            if (TaskInputContext.patchErrors(TaskInputContext.writerRequest(updated, source).text, patch).isNotEmpty())
                return clarification(updated, "I kept the task details, but couldn't safely interpret this reply. Please restate the detail to change.")
            when (patch.optString("operation")) {
                "new" -> return null
                "update" -> updated = TaskInputContext.applyPatch(updated, source, patch)
                else -> return clarification(updated, updated.question.ifBlank { "Which task detail should I change?" })
            }
        }
        return when (updated.capability) {
            "calendar.create" -> listOf(DeviceStep("calendar.create", CalendarInputAdapter.inputs(updated).first))
            "app.search" -> {
                val pkg = updated.packageName
                val query = updated.values["query"]?.value
                val saved = updated.copy(missing = if (query == null) setOf("query") else emptySet(),
                    question = if (query == null) "What should I search for in the selected app?" else "")
                listOf(DeviceStep("app.search", JSONObject().put("package", pkg.orEmpty()).put("query", query.orEmpty())
                    .put(TaskInputContext.KEY, saved.json())))
            }
            else -> null
        }
    }

    private fun clarification(draft: TaskInputContext.Draft, question: String, state: String = "WAITING_INPUT") =
        listOf(DeviceStep("input.clarify", JSONObject().put("question", question).put("state", state)
            .put(TaskInputContext.KEY, draft.copy(question = question).json())))

    private fun explicitNew(text: String) = Writer.requestFrom(taskPart(text)) != null ||
            Regex("^(?:please\\s+)?(?:open|launch|play|send|call|search|find|research|book|set|read|check)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text.trim())

    /** Old unfinished turns are owner data even when the old router selected the wrong capability. */
    fun restore(chatId: String, records: List<TaskRecord>): TaskInputContext.Draft? {
        val latest = records.firstOrNull()?.takeIf { it.chatId == chatId && !it.deleted && it.state in pending } ?: return null
        TaskInputContext.saved(latest, chatId)?.let { return it }
        var draft: TaskInputContext.Draft? = null
        for (record in records.takeWhile { it.chatId == chatId && !it.deleted && it.state in pending }.asReversed()) {
            val source = TaskInputContext.source(record)
            if (CalendarRequest.matches(record.request) || CalendarRequest.mentionsCalendar(record.request)) {
                if (draft == null || !TaskInputContext.referencesPrevious(record.request))
                    draft = TaskInputContext.start(chatId, "calendar.create", source)
            } else if (draft == null || explicitNew(record.request)) {
                draft = null
                continue
            }
            draft = CalendarInputAdapter.inputs(CalendarInputAdapter.update(draft!!, source), source.date).second
        }
        return draft?.copy(previousTask = latest.id)
    }
}
