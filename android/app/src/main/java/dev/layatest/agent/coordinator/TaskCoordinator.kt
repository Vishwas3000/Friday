package dev.layatest.agent.coordinator

import android.content.Context
import android.os.SystemClock
import dev.layatest.agent.ChatHistory
import dev.layatest.agent.GeminiResearch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** App-specific operations. The service's existing request gate owns the foreground throughout a run. */
internal interface ResearchDriver : TaskDriver {
    fun prepare(spec: GeminiResearch.Spec): JSONObject?
    fun inspect(spec: GeminiResearch.Spec): GeminiResearch.Submission
    /** Must call beforeDispatch immediately before every Send, and stop when it returns false. */
    fun submit(spec: GeminiResearch.Spec, beforeDispatch: () -> Boolean): Boolean
    fun capture(spec: GeminiResearch.Spec, timeoutMs: Long, receipt: GeminiResearch.SubmissionReceipt): GeminiResearch.Result
}

/** Durable scheduling and verification; no topic rules, screen selectors, or model-generated actions. */
object TaskCoordinator {
    data class Outcome(val taskId: String, val state: String, val answer: String, val evidence: JSONObject)
    private data class Lease(val task: TaskRecord, val execution: String, val cancelled: AtomicBoolean = AtomicBoolean())
    private data class Attempt(val step: StepRecord, val record: AttemptRecord, val started: Long)
    private class Yield(val state: String, override val message: String) : RuntimeException(message)
    private val terminal = setOf("COMPLETED", "CANCELLED", "FAILED", "UNSUPPORTED")
    // Short database/publication critical sections only. No app/model/wait operations hold this lock.
    private val lock = Any()
    private val running = ConcurrentHashMap<String, Lease>()

    internal fun create(ctx: Context, chatId: String, request: String): String = synchronized(lock) {
        val plan = ResearchPlanner.plan(ctx, chatId, request)
        val store = TaskStore.get(ctx)
        store.transaction {
            store.dao.insertTask(plan.task)
            store.dao.insertSteps(plan.steps)
            store.dao.upsertFacts(listOf(FactRecord(plan.task.id, "research.question", "text", plan.task.request,
                "owner_request", "supplied")))
            event(store, plan.task, null, "PLAN_SAVED", "READY", "Validated four-step research plan saved before app actions")
        }
        plan.task.id
    }

    internal fun createDevice(ctx: Context, chatId: String, request: String, capability: String, inputs: JSONObject): String =
        createDevice(ctx, chatId, request, listOf(DeviceStep(capability, inputs)))

    internal fun createDevice(ctx: Context, chatId: String, request: String, steps: List<DeviceStep>): String = synchronized(lock) {
        val plan = DevicePlanner.plan(chatId, request, steps)
        val store = TaskStore.get(ctx)
        store.transaction {
            store.dao.insertTask(plan.task)
            store.dao.insertSteps(plan.steps)
            store.dao.upsertFacts(listOf(FactRecord(plan.task.id, "owner.request", "text", request, "owner_request", "supplied")))
            event(store, plan.task, null, "PLAN_SAVED", plan.task.state, plan.task.message)
        }
        plan.task.id
    }

    internal fun isDeviceTask(ctx: Context, taskId: String): Boolean = TaskStore.get(ctx).dao.task(taskId)?.capability == "device"

    /** Reverify a published result without repeating preparation or Send; the owner question is unchanged. */
    internal fun requestRecheck(ctx: Context, taskId: String): Boolean = synchronized(lock) {
        val store = TaskStore.get(ctx)
        val task = store.dao.task(taskId) ?: return false
        if (task.capability != "research" || task.state != "COMPLETED" || !task.published || task.answer.isNullOrBlank() || task.publicationKey.isNullOrBlank() ||
            running.containsKey(taskId)) return false
        PlanValidator.validate(store.snapshot(taskId)!!)
        val payload = JSONObject(task.payload).put("result_revision", JSONObject(task.payload).optInt("result_revision", 1) + 1)
            .put("replaces_publication_key", task.publicationKey).put("replaces_answer_sha256", hash(task.answer))
            .put("capture_deadline", now() + 300000)
        store.transaction {
            store.dao.updateTask(task.copy(state = "RECONCILING", answer = null, published = false, publicationKey = null,
                payload = payload.toString(), evidence = JSONObject().put("status", "pending").put("full_response", false).toString(),
                message = "Rechecking the complete saved Gemini response…", executionId = null, updatedAt = now()))
            store.dao.steps(taskId).filter { it.capability in setOf("research.capture", "research.publish") }.forEach {
                store.dao.updateStep(it.copy(state = "READY", output = "{}", evidence = "{}", updatedAt = now()))
            }
            event(store, task, null, "RESULT_RECHECK_REQUESTED", "RECONCILING", "Reverify whole response; preserve exact submitted question and replace its prior result atomically")
        }
        true
    }

    fun isCancelled(taskId: String?): Boolean = taskId != null && running[taskId]?.cancelled?.get() == true

    /** Fast signal for a UI thread. Persist cancellation on a worker with cancel(). */
    fun signalCancel(taskId: String) = synchronized(lock) { running[taskId]?.cancelled?.set(true) }

    /** Worker-thread observations join the same ordered timeline without making app text executable. */
    internal fun note(ctx: Context, taskId: String?, message: String, progress: Boolean = false) {
        val lease = taskId?.let { running[it] } ?: return
        synchronized(lock) {
            val store = TaskStore.get(ctx)
            if (!valid(store, lease)) return
            store.transaction {
                val task = requireValid(store, lease)
                val step = store.dao.steps(task.id).firstOrNull { it.state == "RUNNING" }
                event(store, task, step?.id, if (progress) "PROGRESS" else "ACTION_NOTE", task.state, message.take(2000))
                if (progress) store.dao.updateTask(task.copy(message = message.take(2000), updatedAt = now()))
            }
        }
    }

    fun cancel(ctx: Context, taskId: String) = synchronized(lock) {
        signalCancel(taskId)
        val store = TaskStore.get(ctx)
        store.transaction {
            val task = store.dao.task(taskId) ?: return@transaction
            if (task.state in terminal) return@transaction
            val cancelled = task.copy(state = "CANCELLED", cancelEpoch = task.cancelEpoch + 1,
                executionId = null, message = stoppedMessage(task), updatedAt = now())
            store.dao.updateTask(cancelled)
            store.dao.steps(taskId).filter { it.state != "COMPLETED" }.forEach {
                store.dao.updateStep(it.copy(state = "CANCELLED", updatedAt = now()))
            }
            store.dao.attempts(taskId).filter { it.finishedAt == null }.forEach {
                store.dao.updateAttempt(it.copy(state = "CANCELLED", finishedAt = now(),
                    detail = JSONObject().put("reason", "owner stopped task").toString()))
            }
            event(store, cancelled, null, "CANCELLED", "CANCELLED", cancelled.message)
        }
    }

    /** Called off main before clearing that exact chat's transcript. Deleted tasks can never republish. */
    fun clearChat(ctx: Context, chatId: String): Boolean = synchronized(lock) {
        require(chatId.isNotBlank())
        running.values.filter { it.task.chatId == chatId }.forEach { it.cancelled.set(true) }
        val store = TaskStore.get(ctx)
        store.transaction { store.dao.deleteForChat(chatId) }
        ChatHistory.clear(ctx, chatId)
    }

    /** Recover local state only. An interrupted external action must be reconciled by run(), never replayed here. */
    fun recoverInterrupted(ctx: Context, taskIds: Set<String>? = null) {
        val store = TaskStore.get(ctx)
        synchronized(lock) {
            store.transaction {
                for (task in store.dao.recoverableTasks()) {
                    if (taskIds != null && task.id !in taskIds) continue
                    if (running.containsKey(task.id) || task.executionId == null) continue
                    val unfinished = store.dao.attempts(task.id).filter { it.finishedAt == null }
                    val unknown = unfinished.any { it.dispatchState != "NOT_DISPATCHED" }
                    val state = if (unknown) "OUTCOME_UNKNOWN" else "RECONCILING"
                    val message = "Friday restarted. Resume will inspect saved progress before continuing; uncertain writes will not be repeated."
                    store.dao.updateTask(task.copy(executionId = null, state = state, message = message, updatedAt = now()))
                    unfinished.forEach {
                        store.dao.updateAttempt(it.copy(state = "INTERRUPTED", finishedAt = now(),
                            detail = JSONObject(it.detail).put("reason", "process interrupted; reconciliation required").toString()))
                    }
                    event(store, task, null, "RECOVERED", state, message)
                }
            }
        }
        // A complete saved answer can be published without foreground access. The key makes this replay safe.
        store.dao.pendingPublications().filter { it.capability == "research" && (taskIds == null || it.id in taskIds) && it.state !in terminal &&
            store.dao.steps(it.id).take(3).all { s -> s.state == "COMPLETED" } }.forEach {
            run(ctx, it.id, object : ResearchDriver {
                override val stopped = false
                override fun progress(message: String) {}
                override fun prepare(spec: GeminiResearch.Spec): JSONObject? = error("Publication cannot operate an app")
                override fun inspect(spec: GeminiResearch.Spec): GeminiResearch.Submission = error("Publication cannot operate an app")
                override fun submit(spec: GeminiResearch.Spec, beforeDispatch: () -> Boolean): Boolean = error("Publication cannot operate an app")
                override fun capture(spec: GeminiResearch.Spec, timeoutMs: Long, receipt: GeminiResearch.SubmissionReceipt): GeminiResearch.Result = error("Publication cannot operate an app")
            })
        }
        store.dao.pendingPublications().filter { it.capability == "device" && (taskIds == null || it.id in taskIds) && it.state !in terminal &&
            store.dao.steps(it.id).dropLast(1).all { s -> s.state == "COMPLETED" } }.forEach {
            runDevice(ctx, it.id, object : CapabilityDriver {
                override val stopped = false
                override fun progress(message: String) {}
                override fun inspect(step: StepRecord, inputs: JSONObject): CapabilityResult? = error("Publication cannot inspect an app")
                override fun execute(step: StepRecord, inputs: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult = error("Publication cannot operate an app")
            })
        }
    }

    internal fun run(ctx: Context, taskId: String, driver: ResearchDriver,
                     log: (String) -> Unit = {}): Outcome = runPlan(ctx, taskId, "research", driver, log) { store, lease, attempt, prior ->
        researchStep(ctx, store, lease, attempt, prior, driver)
    }

    internal fun runDevice(ctx: Context, taskId: String, driver: CapabilityDriver,
                           log: (String) -> Unit = {}): Outcome = runPlan(ctx, taskId, "device", driver, log) { store, lease, attempt, prior ->
        deviceStep(ctx, store, lease, attempt, prior, driver)
    }

    private fun runPlan(ctx: Context, taskId: String, kind: String, driver: TaskDriver, log: (String) -> Unit,
                        execute: (TaskStore, Lease, Attempt, List<AttemptRecord>) -> Unit): Outcome {
        val store = TaskStore.get(ctx)
        val lease = synchronized(lock) {
            val task = store.dao.task(taskId) ?: return Outcome(taskId, "CANCELLED", "This task was cleared.", JSONObject())
            if (task.state in terminal || task.state == "WAITING_INPUT") return outcome(task)
            if (running.containsKey(taskId)) return Outcome(taskId, task.state, "This task is already running.", JSONObject(task.evidence))
            try {
                require(task.capability == kind) { "Wrong capability driver for saved plan" }
                PlanValidator.validate(store.snapshot(taskId)!!)
            } catch (error: Exception) {
                store.transaction {
                    store.dao.updateTask(task.copy(state = "UNSUPPORTED", executionId = null, updatedAt = now(),
                        message = "Saved plan could not be validated: ${error.message}"))
                    event(store, task, null, "PLAN_REJECTED", "UNSUPPORTED", error.message.orEmpty())
                }
                return outcome(store.dao.task(taskId)!!)
            }
            Lease(task, UUID.randomUUID().toString()).also { lease ->
                running[taskId] = lease
                store.transaction {
                    store.dao.updateTask(task.copy(executionId = lease.execution, state = "RUNNING", updatedAt = now(),
                        message = "Continuing saved plan…"))
                    event(store, task, null, "EXECUTION_STARTED", "RUNNING", "Rechecking saved progress")
                }
            }
        }
        var current: Attempt? = null
        try {
            for (step in store.dao.steps(taskId)) {
                checkActive(store, lease, driver)
                if (step.state == "COMPLETED") continue
                val savedSteps = store.dao.steps(taskId)
                val dependencies = JSONArray(step.dependsOn).let { a -> (0 until a.length()).map { a.getString(it) } }
                if (dependencies.any { id -> savedSteps.none { it.id == id && it.state == "COMPLETED" } })
                    throw Yield("BLOCKED", "A required earlier step has not been verified.")
                if (kind == "device") dependencies.forEach { id -> verifyDeviceCheckpoint(store, requireValid(store, lease), savedSteps.first { it.id == id }) }
                val prior = store.dao.attempts(taskId).filter { it.stepId == step.id }
                val input = if (kind == "device" && step.capability != "task.publish") DevicePlanner.inputs(requireValid(store, lease), step, savedSteps) else null
                if (input != null) DeviceCapabilities.inputError(step.capability, input)?.let { throw Yield("WAITING_INPUT", it) }
                current = beginAttempt(store, lease, step, input)
                driver.progress("${step.position + 1}/${savedSteps.size} · ${step.title}…")
                checkActive(store, lease, driver)
                log("coordinator ${step.capability}: attempt ${current.record.number}")
                execute(store, lease, current, prior)
                current = null
            }
        } catch (stop: Yield) {
            pause(store, lease, current, stop.state, stop.message)
            driver.progress(stop.message)
            log("coordinator ${stop.state}: ${stop.message}")
        } catch (error: Exception) {
            pause(store, lease, current, "OUTCOME_UNKNOWN", "Execution interrupted (${error.javaClass.simpleName}). Resume checks saved progress before retrying.")
            log("coordinator error: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            synchronized(lock) {
                store.transaction {
                    store.dao.task(taskId)?.takeIf { it.executionId == lease.execution }?.let {
                        store.dao.updateTask(it.copy(executionId = null, updatedAt = now()))
                    }
                }
                running.remove(taskId, lease)
            }
        }
        return store.dao.task(taskId)?.let(::outcome) ?: Outcome(taskId, "CANCELLED", "This task was cleared.", JSONObject())
    }

    private fun researchStep(ctx: Context, store: TaskStore, lease: Lease, attempt: Attempt,
                             priorAttempts: List<AttemptRecord>, driver: ResearchDriver) {
        val taskId = lease.task.id
        val step = attempt.step
        val spec = GeminiResearch.specFromJson(JSONObject(lease.task.payload).getJSONObject("spec"))
        when (step.capability) {
            "research.prepare" -> {
                val evidence = driver.prepare(spec)
                checkActive(store, lease, driver)
                if (evidence?.optBoolean("normal_chat_verified") != true) throw Yield("OUTCOME_UNKNOWN", "Could not verify a normal new Gemini chat. Resume will inspect the app.")
                finishStep(store, lease, attempt, evidence, JSONObject().put("normal_chat_verified", true))
            }
            "research.submit" -> {
                var observed = driver.inspect(spec)
                checkActive(store, lease, driver)
                val hadDispatch = priorAttempts.any { it.dispatchState != "NOT_DISPATCHED" }
                val maySubmit = observed == GeminiResearch.Submission.UNSENT ||
                    (!hadDispatch && observed == GeminiResearch.Submission.ABSENT)
                if (observed != GeminiResearch.Submission.SUBMITTED && maySubmit) {
                    driver.submit(spec) {
                        synchronized(lock) {
                            if (!valid(store, lease) || driver.stopped || driver.interrupted) false else {
                                store.transaction {
                                    val saved = store.dao.attempts(taskId).first { it.id == attempt.record.id }
                                    store.dao.updateAttempt(saved.copy(dispatchState = "DISPATCHED"))
                                    event(store, lease.task, step.id, "DISPATCH_INTENT", "VERIFYING", "Exact saved public question authorized for Send")
                                }
                                true
                            }
                        }
                    }
                    checkActive(store, lease, driver)
                    observed = driver.inspect(spec)
                }
                if (observed != GeminiResearch.Submission.SUBMITTED) {
                    val reason = when (observed) {
                        GeminiResearch.Submission.AUTH_REQUIRED -> "Gemini needs authentication. Resume after completing it."
                        GeminiResearch.Submission.WRONG_APP -> "The saved Gemini conversation is not accessible. Resume after returning to it."
                        GeminiResearch.Submission.UNSENT -> "The exact question is still unsent in Gemini. Resume will verify it before retrying."
                        else -> "The previous Send outcome is uncertain. Open the saved Gemini conversation and Resume; Friday will not send a duplicate question."
                    }
                    throw Yield("OUTCOME_UNKNOWN", reason)
                }
                finishStep(store, lease, attempt, JSONObject().put("matching_user_turn_verified", true)
                    .put("provider_package", lease.task.provider).put("prompt_sha256", hash(spec.prompt)),
                    JSONObject().put("submission_verified", true)) { task ->
                    // Checkpoint the capture deadline with Send verification, including a crash between steps.
                    val payload = JSONObject(task.payload)
                    if (!payload.has("capture_deadline"))
                        payload.put("capture_deadline", now() + Capabilities.research.getValue("research.capture").timeoutMs)
                    task.copy(payload = payload.toString())
                }
            }
            "research.capture" -> {
                checkActive(store, lease, driver)
                val task = store.dao.task(taskId)!!
                val payload = JSONObject(task.payload)
                val deadline = payload.optLong("capture_deadline", now() + 300000)
                // An explicit Resume after timeout permits one fresh bounded capture, never a new Send.
                val remaining = (deadline - now()).coerceAtLeast(0)
                val timeout = if (remaining == 0L) 300000L else remaining.coerceAtMost(300000L)
                synchronized(lock) {
                    store.transaction {
                        val latest = requireValid(store, lease)
                        store.dao.updateTask(latest.copy(state = "WAITING_EXTERNAL", updatedAt = now(),
                            payload = JSONObject(latest.payload).put("capture_deadline", now() + timeout).toString(),
                            message = "Gemini is generating. Waiting automatically for its complete matching answer…"))
                        event(store, latest, step.id, "EXTERNAL_WAIT", "WAITING_EXTERNAL", "Waiting for complete response and stable final marker")
                    }
                }
                val submitted = store.dao.steps(taskId).first { it.capability == "research.submit" }
                val submissionEvidence = JSONObject(submitted.evidence)
                val verifiedAttempt = store.dao.attempts(taskId).lastOrNull {
                    it.stepId == submitted.id && it.state == "COMPLETED" && it.revision == task.revision && it.cancelEpoch == task.cancelEpoch
                }
                if (submitted.state != "COMPLETED" || !submissionEvidence.optBoolean("matching_user_turn_verified") ||
                    submissionEvidence.optString("prompt_sha256") != hash(spec.prompt) || verifiedAttempt == null)
                    throw Yield("OUTCOME_UNKNOWN", "The saved submission receipt could not be verified. No question was resent.")
                val receipt = GeminiResearch.SubmissionReceipt(submissionEvidence.getString("prompt_sha256"), spec.marker, submitted.updatedAt)
                val result = driver.capture(spec, timeout, receipt)
                checkActive(store, lease, driver)
                if (result.evidence.optString("status") != "ok" || !result.evidence.optBoolean("full_response") ||
                    !result.evidence.optBoolean("matching_user_turn_verified") || result.answer.isBlank() ||
                    result.evidence.optString("completion_marker") != spec.marker ||
                    result.evidence.optString("answer_sha256") != hash(result.answer))
                    throw Yield("OUTCOME_UNKNOWN", result.evidence.optString("pending_reason", "A complete matching answer was not verified.") +
                        " The saved question will not be resent; Resume checks this response again.")
                finishStep(store, lease, attempt, result.evidence, JSONObject().put("answer_chars", result.answer.length)) { latest ->
                    store.dao.upsertFacts(listOf(FactRecord(taskId, "research.answer", "text", result.answer,
                        "gemini_response:${result.evidence.optString("answer_sha256")}", "observed_untrusted", "owner")))
                    val resultRevision = JSONObject(latest.payload).optInt("result_revision", 1)
                    val key = "$taskId:${latest.revision}" + if (resultRevision > 1) ":result:$resultRevision" else ""
                    latest.copy(answer = result.answer, evidence = result.evidence.toString(), publicationKey = key)
                }
            }
            "research.publish" -> publish(ctx, store, lease, attempt)
            else -> throw Yield("UNSUPPORTED", "This saved capability is not supported.")
        }
    }

    private fun deviceStep(ctx: Context, store: TaskStore, lease: Lease, attempt: Attempt,
                           prior: List<AttemptRecord>, driver: CapabilityDriver) {
        val step = attempt.step
        if (step.capability == "task.publish") return publishDevice(ctx, store, lease, attempt)
        val detail = JSONObject(attempt.record.detail)
        val inputs = JSONObject(detail.getJSONObject("inputs").toString()).put("_input_bind", detail.getString("input_bind"))
        val capability = DeviceCapabilities.registry.getValue(step.capability)
        val uncertainWrite = capability.effect == "external_write" && prior.any { it.dispatchState != "NOT_DISPATCHED" }
        var result = if (prior.isNotEmpty()) driver.inspect(step, inputs) else null
        checkActive(store, lease, driver)
        if (result == null && uncertainWrite)
            throw Yield("OUTCOME_UNKNOWN", "A previous ${step.title.lowercase()} may have been written. No verified saved result was found; Friday will not create it again.")
        var executed = false
        if (result == null) {
            executed = true
            result = driver.execute(step, inputs) {
                synchronized(lock) {
                    if (!valid(store, lease) || driver.stopped || driver.interrupted) false else store.transaction {
                        val task = requireValid(store, lease)
                        val currentInputs = DevicePlanner.inputs(task, step, store.dao.steps(task.id))
                        val saved = store.dao.attempts(task.id).first { it.id == attempt.record.id }
                        if (DeviceCapabilities.digest(DeviceCapabilities.canonical(currentInputs)) != detail.getString("input_bind") ||
                            (capability.effect == "external_write" && saved.dispatchState != "NOT_DISPATCHED")) false else {
                            store.dao.updateAttempt(saved.copy(dispatchState = "DISPATCHED"))
                            event(store, task, step.id, "DISPATCH_INTENT", "VERIFYING", "${step.capability}: exact saved inputs authorized; awaiting registered verifier")
                            true
                        }
                    }
                }
            }
        }
        checkActive(store, lease, driver)
        val observed = checkNotNull(result)
        synchronized(lock) {
            store.transaction {
                val task = requireValid(store, lease)
                val saved = store.dao.attempts(task.id).first { it.id == attempt.record.id }
                store.dao.updateAttempt(saved.copy(detail = JSONObject(saved.detail).put("result_state", observed.state)
                    .put("candidate_output", observed.output).put("candidate_evidence", observed.evidence).toString()))
            }
        }
        if (observed.state != "COMPLETED") {
            if (observed.state !in DeviceCapabilities.yieldStates) throw Yield("FAILED", "Capability returned an unrecognized state.")
            throw Yield(observed.state, observed.message.ifBlank { "${step.title} could not be verified." })
        }
        if (executed && capability.effect == "external_write" && !observed.output.optBoolean("reused") &&
            store.dao.attempts(lease.task.id).first { it.id == attempt.record.id }.dispatchState == "NOT_DISPATCHED")
            throw Yield("FAILED", "The mutation has no saved dispatch authorization; it cannot be marked complete.")
        DeviceCapabilities.verificationError(step.capability, inputs, observed, attempt.record.startedAt)?.let {
            throw Yield("BLOCKED", "Verification failed: $it")
        }
        finishStep(store, lease, attempt, observed.evidence, observed.output) { task ->
            store.dao.upsertFacts(listOf(
                FactRecord(task.id, "step.${step.position}.inputs", "object", detail.getJSONObject("inputs").toString(), "owner_request:${detail.getString("input_bind")}", "supplied"),
                FactRecord(task.id, "step.${step.position}.output", "object", observed.output.toString(), "${step.capability}:${hash(observed.evidence.toString())}", "observed_untrusted"),
            ))
            val completed = store.dao.steps(task.id).filter { it.capability != "task.publish" && it.state == "COMPLETED" }
            val answer = completed.filter { it.capability != "text.draft" || completed.none { s -> s.capability == "message.prepare" } }
                .map { DeviceCapabilities.answer(it.capability, JSONObject(it.output)) }.filter { it.isNotBlank() }.joinToString("\n\n")
            val proofs = JSONArray(completed.map { s -> JSONObject().put("step_id", s.id).put("capability", s.capability)
                .put("evidence_sha256", hash(s.evidence)).put("input_bind", JSONObject(s.evidence).getString("input_bind")) })
            task.copy(answer = answer, publicationKey = "${task.id}:${task.revision}", evidence = JSONObject().put("status", "verified_steps")
                .put("origin_chat_id", task.chatId).put("steps", proofs).put("answer_sha256", hash(answer)).toString())
        }
    }

    private fun verifyDeviceCheckpoint(store: TaskStore, task: TaskRecord, step: StepRecord) {
        val proof = store.dao.attempts(task.id).lastOrNull { it.stepId == step.id && it.state == "COMPLETED" &&
            it.revision == task.revision && it.cancelEpoch == task.cancelEpoch }
            ?: throw Yield("BLOCKED", "A dependency has no completion receipt for the current task revision.")
        val input = DevicePlanner.inputs(task, step, store.dao.steps(task.id))
        val bind = DeviceCapabilities.digest(DeviceCapabilities.canonical(input))
        if (JSONObject(proof.detail).optString("input_bind") != bind) throw Yield("BLOCKED", "A dependency's saved input binding changed.")
        input.put("_input_bind", bind)
        DeviceCapabilities.verificationError(step.capability, input, CapabilityResult(output = JSONObject(step.output), evidence = JSONObject(step.evidence)), proof.startedAt)?.let {
            throw Yield("BLOCKED", "Dependency verification failed: $it")
        }
    }

    private fun publishDevice(ctx: Context, store: TaskStore, lease: Lease, attempt: Attempt) = synchronized(lock) {
        val task = requireValid(store, lease)
        val steps = store.dao.steps(task.id).filter { it.capability != "task.publish" }
        require(steps.isNotEmpty() && steps.all { it.state == "COMPLETED" })
        steps.forEach { verifyDeviceCheckpoint(store, task, it) }
        require(!task.answer.isNullOrBlank() && !task.publicationKey.isNullOrBlank())
        val evidence = JSONObject(task.evidence)
        require(evidence.getString("origin_chat_id") == task.chatId && evidence.getString("answer_sha256") == hash(task.answer))
        val record = JSONObject().put("time", now()).put("chat_id", task.chatId).put("task_id", task.id)
            .put("request", task.request).put("answer", task.answer).put("ms", task.activeMs).put("task_mode", "device")
            .put("task_evidence", evidence).put("trace", JSONArray(store.dao.events(task.id).map { "${it.kind}: ${it.message} [${it.durationMs}ms]" }))
        if (!ChatHistory.appendTaskResult(ctx, record, task.publicationKey))
            throw Yield("OUTCOME_UNKNOWN", "The verified result could not be saved to its originating chat.")
        finishStep(store, lease, attempt, JSONObject().put("publication_key", task.publicationKey).put("origin_chat_id", task.chatId),
            JSONObject().put("published", true), completeTask = true)
    }

    private fun beginAttempt(store: TaskStore, lease: Lease, step: StepRecord, inputs: JSONObject? = null): Attempt = synchronized(lock) {
        val started = SystemClock.elapsedRealtime()
        store.transaction {
            val task = requireValid(store, lease)
            val saved = store.dao.steps(task.id).first { it.id == step.id }
            val record = AttemptRecord(UUID.randomUUID().toString(), task.id, step.id, task.revision, task.cancelEpoch,
                saved.attempts + 1, now(), detail = JSONObject().put("capability", step.capability)
                    .put("verifier", Capabilities.all.getValue(step.capability).verifier)
                    .put("inputs", inputs).put("input_bind", inputs?.let { DeviceCapabilities.digest(DeviceCapabilities.canonical(it)) }).toString())
            store.dao.insertAttempt(record)
            val updated = saved.copy(state = "RUNNING", attempts = record.number, updatedAt = now())
            store.dao.updateStep(updated)
            store.dao.updateTask(task.copy(state = "RUNNING", message = "${step.position + 1}/${store.dao.steps(task.id).size} · ${step.title}", updatedAt = now()))
            event(store, task, step.id, "ATTEMPT_STARTED", "RUNNING", step.title)
            Attempt(updated, record, started)
        }
    }

    private fun finishStep(store: TaskStore, lease: Lease, attempt: Attempt, evidence: JSONObject, output: JSONObject,
                           completeTask: Boolean = false,
                           transform: (TaskRecord) -> TaskRecord = { it }) = synchronized(lock) {
        store.transaction {
            val task = requireValid(store, lease)
            val duration = SystemClock.elapsedRealtime() - attempt.started
            val saved = store.dao.attempts(task.id).first { it.id == attempt.record.id }
            store.dao.updateAttempt(saved.copy(state = "COMPLETED", finishedAt = now(), durationMs = duration,
                dispatchState = if (saved.dispatchState == "DISPATCHED") "VERIFIED" else saved.dispatchState))
            store.dao.updateStep(attempt.step.copy(state = "COMPLETED", output = output.toString(), evidence = evidence.toString(),
                activeMs = attempt.step.activeMs + duration, updatedAt = now()))
            val content = evidence.toString()
            store.dao.insertEvidence(EvidenceRecord(UUID.randomUUID().toString(), task.id, attempt.step.id, saved.id,
                evidence.optString("source_package").takeIf { it.isNotBlank() }, now(), "${attempt.step.capability}.verification", content, hash(content)))
            store.dao.updateTask(transform(task).copy(activeMs = task.activeMs + duration, updatedAt = now(),
                state = if (completeTask) "COMPLETED" else "RUNNING", published = if (completeTask) true else task.published,
                message = if (completeTask) "Complete answer saved to this Friday chat." else "Verified: ${attempt.step.title}"))
            event(store, task, attempt.step.id, "VERIFIED", "COMPLETED", attempt.step.title, duration)
            if (completeTask) event(store, task, attempt.step.id, "TASK_COMPLETED", "COMPLETED",
                "Answer published to its originating chat", task.activeMs + duration)
        }
    }

    private fun publish(ctx: Context, store: TaskStore, lease: Lease, attempt: Attempt) = synchronized(lock) {
        val task = requireValid(store, lease)
        require(!task.answer.isNullOrBlank() && !task.publicationKey.isNullOrBlank()) { "No verified answer to publish" }
        val evidence = JSONObject(task.evidence)
        require(evidence.optBoolean("full_response") && evidence.optString("answer_sha256") == hash(task.answer))
        val trace = store.dao.events(task.id).map { "${it.kind}: ${it.message} [${it.durationMs}ms]" }
        val record = JSONObject().put("time", now()).put("chat_id", task.chatId)
            .put("task_id", task.id).put("request", task.request).put("answer", task.answer).put("ms", task.activeMs)
            .put("research_mode", true).put("research_evidence", evidence).put("trace", JSONArray(trace))
        val payload = JSONObject(task.payload)
        val replaced = payload.optString("replaces_publication_key")
        val published = if (replaced.isBlank()) ChatHistory.appendTaskResult(ctx, record, task.publicationKey)
            else ChatHistory.replaceTaskResult(ctx, record, task.publicationKey, replaced, payload.getString("replaces_answer_sha256"))
        if (!published) throw Yield("OUTCOME_UNKNOWN", "The existing chat result changed; its replacement could not be verified.")
        // If the process dies after append, recovery appends by the same key and then finishes this checkpoint.
        finishStep(store, lease, attempt, JSONObject().put("publication_key", task.publicationKey)
            .put("origin_chat_id", task.chatId), JSONObject().put("published", true), completeTask = true)
    }

    private fun pause(store: TaskStore, lease: Lease, attempt: Attempt?, state: String, reason: String) = synchronized(lock) {
        store.transaction {
            if (!valid(store, lease, checkCancelled = false)) return@transaction
            val task = store.dao.task(lease.task.id)!!
            val next = if (lease.cancelled.get()) "CANCELLED" else state
            val message = if (lease.cancelled.get()) stoppedMessage(task) else reason
            val duration = attempt?.let { SystemClock.elapsedRealtime() - it.started } ?: 0
            attempt?.let {
                val saved = store.dao.attempts(task.id).first { a -> a.id == it.record.id }
                store.dao.updateAttempt(saved.copy(state = next, finishedAt = now(), durationMs = duration,
                    detail = JSONObject(saved.detail).put("reason", message).toString()))
                store.dao.updateStep(it.step.copy(state = next, activeMs = it.step.activeMs + duration, updatedAt = now()))
            }
            store.dao.updateTask(task.copy(state = next, message = message, activeMs = task.activeMs + duration, updatedAt = now(),
                cancelEpoch = if (next == "CANCELLED") task.cancelEpoch + 1 else task.cancelEpoch))
            event(store, task, attempt?.step?.id, "EXECUTION_YIELDED", next, message, duration)
        }
    }

    private fun checkActive(store: TaskStore, lease: Lease, driver: TaskDriver) {
        if (driver.interrupted) throw Yield("RECONCILING", "Friday's service was interrupted. Resume will inspect the saved progress.")
        if (lease.cancelled.get() || driver.stopped) throw Yield("CANCELLED", stoppedMessage(lease.task))
        synchronized(lock) { if (!valid(store, lease)) throw Yield("CANCELLED", "This task was stopped or cleared.") }
    }

    private fun valid(store: TaskStore, lease: Lease, checkCancelled: Boolean = true): Boolean {
        val task = store.dao.task(lease.task.id) ?: return false
        return (!checkCancelled || !lease.cancelled.get()) && task.revision == lease.task.revision && task.cancelEpoch == lease.task.cancelEpoch &&
            task.executionId == lease.execution && task.state !in terminal
    }

    private fun requireValid(store: TaskStore, lease: Lease): TaskRecord {
        if (!valid(store, lease)) throw Yield("CANCELLED", "This execution was invalidated.")
        return store.dao.task(lease.task.id)!!
    }

    private fun outcome(task: TaskRecord) = Outcome(task.id, task.state,
        if (task.state == "COMPLETED") task.answer.orEmpty() else task.message, JSONObject(task.evidence))

    private fun stoppedMessage(task: TaskRecord) = if (task.capability == "research") "Stopped. Any question already sent remains in Gemini."
        else "Stopped. Any change already made in another app remains there; it will not be repeated automatically."

    private fun event(store: TaskStore, task: TaskRecord, step: String?, kind: String, state: String, message: String,
                      duration: Long = 0) = store.dao.insertEvent(EventRecord(taskId = task.id, stepId = step, revision = task.revision,
        kind = kind, state = state, message = message, durationMs = duration, createdAt = now()))
    private fun now() = System.currentTimeMillis()
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
