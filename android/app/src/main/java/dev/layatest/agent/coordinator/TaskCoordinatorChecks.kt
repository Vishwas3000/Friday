package dev.layatest.agent.coordinator

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import dev.layatest.agent.ChatHistory
import dev.layatest.agent.GeminiResearch
import dev.layatest.agent.HotelCapabilities
import dev.layatest.agent.RequestResolver
import dev.layatest.agent.ResearchCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Synthetic Room/fake-driver checks. Never opens an app or changes the selected chat. */
internal object TaskCoordinatorChecks {
    @Synchronized fun run(ctx: Context): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return JSONObject()
            .put("status", "invalid").put("error", "Run coordinator checks on a worker thread")
        val started = SystemClock.elapsedRealtime()
        val checks = Checks(ctx)
        try { checks.run() } finally { checks.cleanup() }
        return checks.report().put("total_ms", SystemClock.elapsedRealtime() - started)
    }

    private class Checks(private val ctx: Context) {
        private val store = TaskStore.get(ctx)
        private val selectedChat = ChatHistory.activeId(ctx)
        private val fixturePrefs = ctx.getSharedPreferences("friday_coordinator_checks", Context.MODE_PRIVATE)
        private val chats = fixturePrefs.getStringSet("chat_ids", emptySet()).orEmpty()
            .filter { it.startsWith("coordinator_check_") && runCatching {
                UUID.fromString(it.removePrefix("coordinator_check_"))
            }.isSuccess }.toMutableSet()
        private val origins = mutableMapOf<String, String>()
        private val results = JSONArray()

        fun run() {
            case("C01_topic_independent_completion") {
                for (question in listOf("Explain volcanic island formation", "Compare two battery chemistries", "What causes auroras?")) {
                    val id = create(question)
                    val driver = FakeDriver()
                    val outcome = TaskCoordinator.run(ctx, id, driver)
                    val saved = store.snapshot(id)!!
                    check(outcome.state == "COMPLETED" && outcome.answer == driver.answer)
                    check(saved.task.published && saved.steps.all { it.state == "COMPLETED" })
                    check(saved.events.first().kind == "PLAN_SAVED")
                    check(driver.sends == 1 && driver.captures == 1)
                    check(entries(id).single().getString("answer") == driver.answer)
                    check(store.dao.facts(id).any { it.key == "research.answer" && it.status == "observed_untrusted" })
                }
            }
            case("C02_plan_validation") {
                val id = create()
                val plan = store.snapshot(id)!!
                PlanValidator.validate(plan)
                rejects { PlanValidator.validate(plan.copy(task = plan.task.copy(provider = "synthetic.invalid"))) }
                rejects { PlanValidator.validate(plan.copy(steps = plan.steps.mapIndexed { index, step ->
                    if (index == 0) step.copy(dependsOn = JSONArray(listOf(plan.steps.last().id)).toString()) else step
                })) }
                rejects { PlanValidator.validate(plan.copy(steps = plan.steps.mapIndexed { index, step ->
                    if (index == 1) step.copy(capability = "unregistered.operation") else step
                })) }
                rejects { PlanValidator.validate(plan.copy(task = plan.task.copy(request = "A different saved question"))) }
                val tampered = JSONObject(plan.task.payload)
                tampered.getJSONObject("spec").put("marker", "FRIDAY_RESEARCH_END_" + "f".repeat(32))
                rejects { PlanValidator.validate(plan.copy(task = plan.task.copy(payload = tampered.toString()))) }
            }
            case("C03_saved_tamper_rejected_before_driver") {
                val id = create()
                store.transaction {
                    val task = store.dao.task(id)!!
                    store.dao.updateTask(task.copy(provider = "synthetic.invalid"))
                }
                val driver = FakeDriver()
                check(TaskCoordinator.run(ctx, id, driver).state == "UNSUPPORTED")
                check(driver.calls == 0)
                check(store.dao.recoverableTasks().none { it.id == id })
                unpublished(id)
            }
            case("C04_empty_request_not_persisted") {
                val chat = chat()
                rejects { TaskCoordinator.create(ctx, chat, "   ") }
                check(store.latestForChat(chat) == null)
            }
            case("C05_room_rollback") {
                val id = create()
                try {
                    store.transaction {
                        store.dao.upsertFacts(listOf(FactRecord(id, "fixture.rollback", "text", "synthetic", "fixture", "supplied")))
                        throw FixtureRollback()
                    }
                } catch (_: FixtureRollback) { }
                check(store.dao.facts(id).none { it.key == "fixture.rollback" })
                check(store.dao.facts(id).any { it.key == "research.question" })
            }
            case("C06_interrupted_send_reconciles_without_duplicate") {
                val id = create()
                val driver = FakeDriver()
                driver.afterSend = { driver.interruptedFlag = true }
                check(TaskCoordinator.run(ctx, id, driver).state == "RECONCILING")
                check(driver.sends == 1 && driver.captures == 0)
                check(store.dao.attempts(id).any { it.dispatchState == "DISPATCHED" })
                driver.interruptedFlag = false
                driver.afterSend = null
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                check(driver.sends == 1 && driver.captures == 1)
                check(entries(id).size == 1)
            }
            case("C07_persisted_orphan_send_recovery") {
                val id = create()
                val attempt = orphanSend(id)
                TaskCoordinator.recoverInterrupted(ctx, setOf(id))
                val recovered = store.dao.task(id)!!
                check(recovered.state == "OUTCOME_UNKNOWN" && recovered.executionId == null)
                val savedAttempt = store.dao.attempts(id).single { it.id == attempt }
                check(savedAttempt.state == "INTERRUPTED" && savedAttempt.finishedAt != null)
                val driver = FakeDriver().apply { submission = GeminiResearch.Submission.SUBMITTED }
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                check(driver.sends == 0 && driver.prepares == 0 && driver.captures == 1)
            }
            case("C08_ambiguous_send_never_resent") {
                val id = create()
                val driver = FakeDriver()
                driver.afterSend = {
                    driver.submission = GeminiResearch.Submission.ABSENT
                    driver.interruptedFlag = true
                }
                TaskCoordinator.run(ctx, id, driver)
                driver.interruptedFlag = false
                driver.afterSend = null
                repeat(2) { check(TaskCoordinator.run(ctx, id, driver).state == "OUTCOME_UNKNOWN") }
                check(driver.sends == 1 && driver.captures == 0)
                unpublished(id)
            }
            case("C09_partial_capture_resumes_without_send") {
                val id = create()
                val driver = FakeDriver().apply {
                    captureResult = { GeminiResearch.Result("", JSONObject().put("status", "pending")
                        .put("pending_reason", "synthetic incomplete response")) }
                }
                check(TaskCoordinator.run(ctx, id, driver).state == "OUTCOME_UNKNOWN")
                unpublished(id)
                driver.captureResult = null
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                check(driver.sends == 1 && driver.captures == 2)
            }
            case("C10_capture_evidence_must_match") {
                for (field in listOf("status", "full_response", "matching_user_turn_verified", "completion_marker", "answer_sha256")) {
                    val id = create()
                    val driver = FakeDriver().apply {
                        captureResult = { spec ->
                            validResult(spec, answer).also { result ->
                                result.evidence.put(field, if (field in setOf("full_response", "matching_user_turn_verified")) false else "mismatch")
                            }
                        }
                    }
                    check(TaskCoordinator.run(ctx, id, driver).state == "OUTCOME_UNKNOWN")
                    unpublished(id)
                }
            }
            case("C11_owner_stop_during_wait") {
                val id = create()
                val driver = FakeDriver()
                driver.beforeCapture = { driver.stoppedFlag = true }
                check(TaskCoordinator.run(ctx, id, driver).state == "CANCELLED")
                check(driver.sends == 1 && driver.captures == 1)
                unpublished(id)
                val untouched = FakeDriver()
                check(TaskCoordinator.run(ctx, id, untouched).state == "CANCELLED" && untouched.calls == 0)
            }
            case("C12_cancelled_worker_cannot_publish") {
                for (stage in listOf("capture", "publication", "banner_stop")) {
                    val id = create()
                    val epoch = store.dao.task(id)!!.cancelEpoch
                    val driver = FakeDriver()
                    if (stage == "capture") driver.beforeCapture = { TaskCoordinator.cancel(ctx, id) }
                    else driver.onProgress = {
                        if (store.dao.task(id)?.let { it.answer != null && it.state != "CANCELLED" } == true) {
                            if (stage == "banner_stop") driver.stoppedFlag = true else TaskCoordinator.cancel(ctx, id)
                        }
                    }
                    check(TaskCoordinator.run(ctx, id, driver).state == "CANCELLED")
                    check(store.dao.task(id)!!.cancelEpoch == epoch + 1)
                    unpublished(id)
                }
            }
            case("C13_deleted_worker_cannot_publish") {
                val id = create()
                val driver = FakeDriver().apply { beforeCapture = { TaskCoordinator.clearChat(ctx, origins.getValue(id)) } }
                check(TaskCoordinator.run(ctx, id, driver).state == "CANCELLED")
                check(store.snapshot(id) == null && entries(id).isEmpty())
                check(store.dao.steps(id).isEmpty() && store.dao.attempts(id).isEmpty())
                check(store.dao.evidence(id).isEmpty() && store.dao.facts(id).isEmpty() && store.dao.events(id).isEmpty())
            }
            case("C14_stale_epoch_cannot_publish") {
                val id = create()
                val driver = FakeDriver().apply { beforeCapture = {
                    store.transaction {
                        val task = store.dao.task(id)!!
                        store.dao.updateTask(task.copy(cancelEpoch = task.cancelEpoch + 1, state = "READY"))
                    }
                } }
                TaskCoordinator.run(ctx, id, driver)
                unpublished(id)
                check(store.dao.task(id)!!.answer == null)
            }
            case("C15_stale_execution_lease_cannot_publish") {
                val id = create()
                val driver = FakeDriver().apply { beforeCapture = {
                    store.transaction {
                        val task = store.dao.task(id)!!
                        store.dao.updateTask(task.copy(executionId = "synthetic_new_lease", state = "READY"))
                    }
                } }
                TaskCoordinator.run(ctx, id, driver)
                unpublished(id)
                check(store.dao.task(id)!!.executionId == "synthetic_new_lease")
            }
            case("C16_publication_origin_and_replay") {
                val id = create()
                val driver = FakeDriver()
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                val saved = store.snapshot(id)!!
                val key = saved.task.publicationKey!!
                check(entries(id).single().optString("chat_id") == origins.getValue(id))
                check(ChatHistory.activeId(ctx) == selectedChat)
                ChatHistory.appendTaskResult(ctx, JSONObject().put("chat_id", origins.getValue(id))
                    .put("task_id", id).put("request", saved.task.request).put("answer", "duplicate fixture"), key)
                check(entries(id).size == 1 && entries(id).single().getString("answer") == driver.answer)
                replayPublicationCheckpoint(id)
                check(entries(id).size == 1)
                val replay = FakeDriver()
                check(TaskCoordinator.run(ctx, id, replay).state == "COMPLETED" && replay.calls == 0)
            }
            case("C17_clear_is_scoped_and_cascades") {
                val cleared = create()
                val retained = create()
                check(TaskCoordinator.run(ctx, cleared, FakeDriver()).state == "COMPLETED")
                TaskCoordinator.clearChat(ctx, origins.getValue(cleared))
                check(ChatHistory.clear(ctx, origins.getValue(cleared)))
                check(store.snapshot(cleared) == null && store.snapshot(retained) != null)
                check(store.dao.steps(cleared).isEmpty() && store.dao.attempts(cleared).isEmpty())
                check(store.dao.evidence(cleared).isEmpty() && store.dao.facts(cleared).isEmpty() && store.dao.events(cleared).isEmpty())
                check(entries(cleared).isEmpty())
            }
            case("C19_expired_deadline_before_first_capture") {
                val id = create()
                val snapshot = store.snapshot(id)!!
                check(store.dao.attempts(id).none { it.stepId == snapshot.steps[2].id })
                store.transaction {
                    store.dao.updateTask(snapshot.task.copy(state = "RECONCILING", payload = JSONObject(snapshot.task.payload)
                        .put("capture_deadline", System.currentTimeMillis() - 60000).toString()))
                }
                completedSubmission(id)
                val driver = FakeDriver().apply { submission = GeminiResearch.Submission.SUBMITTED }
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                check(driver.prepares == 0 && driver.submissions == 0 && driver.sends == 0 && driver.captures == 1)
                check(driver.captureTimeouts.single() in 1L..Capabilities.research.getValue("research.capture").timeoutMs)
                check(entries(id).size == 1)
            }
            case("C20_cancel_immediately_before_dispatch_guard") {
                for (signalOnly in listOf(true, false)) {
                    val id = create()
                    val driver = FakeDriver().apply { beforeSend = {
                        if (signalOnly) TaskCoordinator.signalCancel(id) else TaskCoordinator.cancel(ctx, id)
                    } }
                    check(TaskCoordinator.run(ctx, id, driver).state == "CANCELLED")
                    check(store.dao.task(id)!!.state == "CANCELLED")
                    check(driver.submissions == 1 && driver.sends == 0 && driver.captures == 0)
                    check(store.dao.attempts(id).all { it.dispatchState == "NOT_DISPATCHED" })
                    check(store.dao.events(id).none { it.kind == "DISPATCH_INTENT" })
                    unpublished(id)
                }
            }
            case("C21_stale_revision_cannot_publish") {
                val id = create()
                val revision = store.dao.task(id)!!.revision
                val driver = FakeDriver().apply { beforeCapture = {
                    store.transaction {
                        val task = store.dao.task(id)!!
                        store.dao.updateTask(task.copy(revision = task.revision + 1, state = "READY"))
                    }
                } }
                TaskCoordinator.run(ctx, id, driver)
                unpublished(id)
                check(store.dao.task(id)!!.let { it.revision == revision + 1 && it.answer == null })
            }
            case("C22_invalid_persisted_submission_proof_blocks_capture") {
                for (variant in listOf("digest", "revision", "epoch")) {
                    val id = create()
                    store.transaction {
                        val task = store.dao.task(id)!!
                        store.dao.updateTask(task.copy(state = "RECONCILING",
                            revision = if (variant == "revision") 2 else task.revision,
                            cancelEpoch = if (variant == "epoch") 1 else task.cancelEpoch))
                    }
                    completedSubmission(id,
                        digest = if (variant == "digest") "0".repeat(64) else null,
                        proofRevision = if (variant == "revision") 1 else null,
                        proofEpoch = if (variant == "epoch") 0 else null)
                    PlanValidator.validate(store.snapshot(id)!!)
                    val driver = FakeDriver().apply { submission = GeminiResearch.Submission.SUBMITTED }
                    check(TaskCoordinator.run(ctx, id, driver).state != "COMPLETED")
                    check(driver.captures == 0 && driver.sends == 0 && driver.submissions == 0)
                    unpublished(id)
                    check(store.dao.task(id)!!.answer == null)
                }
            }
            case("C23_recheck_replaces_answer_without_resending") {
                val id = create()
                val driver = FakeDriver().apply { answer = "Synthetic original research answer." }
                check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                var previous = store.snapshot(id)!!
                val spec = JSONObject(previous.task.payload).getJSONObject("spec").toString()
                for (resultRevision in 2..3) {
                    val oldKey = previous.task.publicationKey!!
                    val oldAnswer = previous.task.answer!!
                    check(TaskCoordinator.requestRecheck(ctx, id))
                    val pending = store.snapshot(id)!!
                    val payload = JSONObject(pending.task.payload)
                    check(pending.task.state == "RECONCILING" && pending.task.answer == null && !pending.task.published)
                    check(payload.getInt("result_revision") == resultRevision)
                    check(payload.getString("replaces_publication_key") == oldKey)
                    check(payload.getString("replaces_answer_sha256") == hash(oldAnswer))
                    check(payload.getJSONObject("spec").toString() == spec)
                    check(pending.steps.take(2) == previous.steps.take(2))
                    check(pending.steps.drop(2).all { it.state == "READY" })
                    check(entries(id).single().getString("answer") == oldAnswer)
                    driver.answer = "Synthetic revised research answer $resultRevision."
                    check(TaskCoordinator.run(ctx, id, driver).state == "COMPLETED")
                    val completed = store.snapshot(id)!!
                    val row = entries(id).single()
                    check(driver.sends == 1 && driver.prepares == 1 && driver.captures == resultRevision)
                    check(completed.task.publicationKey != oldKey)
                    check(row.getString("publication_key") == completed.task.publicationKey)
                    check(row.getString("answer") == driver.answer && row.getString("chat_id") == origins.getValue(id))
                    check(ChatHistory.activeId(ctx) == selectedChat)
                    replayPublicationCheckpoint(id)
                    check(entries(id).single().let {
                        it.getString("publication_key") == completed.task.publicationKey && it.getString("answer") == driver.answer
                    })
                    previous = store.snapshot(id)!!
                }
            }
            case("C24_replacement_identity_hash_and_replay_guards") {
                val id = create()
                val other = create()
                check(TaskCoordinator.run(ctx, id, FakeDriver()).state == "COMPLETED")
                check(TaskCoordinator.run(ctx, other, FakeDriver()).state == "COMPLETED")
                val old = entries(id).single()
                val otherRow = entries(other).single().toString()
                val ownedChats = setOf(origins.getValue(id), origins.getValue(other))
                fun ownedRows() = ChatHistory.entriesAll(ctx).filter { it.optString("chat_id") in ownedChats }.map { it.toString() }
                val before = ownedRows()
                val oldKey = old.getString("publication_key")
                val newKey = "$id:synthetic_replacement"
                val oldHash = hash(old.getString("answer"))
                val replacement = JSONObject().put("time", System.currentTimeMillis()).put("chat_id", origins.getValue(id))
                    .put("task_id", id).put("request", old.getString("request")).put("answer", "Synthetic guarded replacement.")
                for (variant in listOf("chat", "task", "hash", "missing_key")) {
                    val record = JSONObject(replacement.toString())
                    if (variant == "chat") record.put("chat_id", origins.getValue(other))
                    if (variant == "task") record.put("task_id", other)
                    check(!ChatHistory.replaceTaskResult(ctx, record, newKey,
                        if (variant == "missing_key") "$id:missing_publication" else oldKey,
                        if (variant == "hash") "0".repeat(64) else oldHash))
                    check(ownedRows() == before)
                }
                check(ChatHistory.replaceTaskResult(ctx, replacement, newKey, oldKey, oldHash))
                check(entries(id).single().let {
                    it.getString("publication_key") == newKey && it.getString("answer") == replacement.getString("answer")
                })
                val replaced = ownedRows()
                check(ChatHistory.replaceTaskResult(ctx, replacement, newKey, oldKey, oldHash))
                check(ownedRows() == replaced && replaced.size == before.size)
                for (field in listOf("chat_id", "task_id", "answer")) {
                    val mismatch = JSONObject(replacement.toString()).put(field, "synthetic_mismatch")
                    check(!ChatHistory.replaceTaskResult(ctx, mismatch, newKey, oldKey, oldHash))
                    check(ownedRows() == replaced)
                }
                check(entries(other).single().toString() == otherRow)
            }
            case("C25_device_calendar_exact_saved_completion") {
                val id = createDevice("calendar.create", calendarInput())
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                check(driver.writes == 1 && entries(id).size == 1)
                check(store.dao.attempts(id).first().dispatchState == "VERIFIED")
                check(store.dao.facts(id).any { it.key == "step.0.output" && it.status == "observed_untrusted" })
                val detail = JSONObject(store.dao.attempts(id).first().detail)
                check(detail.getJSONObject("inputs").getString("title") == "Synthetic calendar check")
                check(detail.getString("input_bind").length == 64)
            }
            case("C26_device_unsupported_and_missing_inputs_never_dispatch") {
                val unsupported = createDevice("unregistered.operation", JSONObject())
                val incomplete = createDevice("calendar.create", JSONObject().put("validation_error", "What time should I use?"))
                val missing = createDevice("app.search", JSONObject().put("package", "synthetic.app"))
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, unsupported, driver).state == "UNSUPPORTED")
                check(TaskCoordinator.runDevice(ctx, incomplete, driver).state == "WAITING_INPUT")
                check(TaskCoordinator.runDevice(ctx, missing, driver).state == "WAITING_INPUT")
                check(driver.executions == 0 && driver.inspections == 0)
                listOf(unsupported, incomplete, missing).forEach { unpublished(it) }
            }
            case("C27_device_verifier_rejects_wrong_app_inputs_and_stale_evidence") {
                for (variant in listOf("app", "date", "binding", "timestamp", "verified")) {
                    val id = createDevice("calendar.create", calendarInput())
                    val driver = FakeCapabilityDriver().apply { result = { step, input ->
                        deviceResult(step, input).also { r -> when (variant) {
                            "app" -> r.evidence.put("source_package", "other.app")
                            "date" -> r.output.put("start_ms", input.getLong("start_ms") + 86400000)
                            "binding" -> r.evidence.put("input_bind", "wrong")
                            "timestamp" -> r.evidence.put("captured_at", 1)
                            else -> r.evidence.put("verified", false)
                        } }
                    } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED")
                    unpublished(id)
                    check(store.dao.steps(id).none { it.state == "COMPLETED" })
                }
            }
            case("C28_uncertain_calendar_write_is_reconciled_never_recreated") {
                val id = createDevice("calendar.create", calendarInput())
                val driver = FakeCapabilityDriver().apply { afterWrite = { interruptedFlag = true } }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "RECONCILING")
                check(driver.writes == 1)
                driver.interruptedFlag = false
                driver.afterWrite = null
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "OUTCOME_UNKNOWN")
                check(driver.writes == 1)
                driver.foundOnInspection = true
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                check(driver.writes == 1 && driver.executions == 1 && entries(id).size == 1)
            }
            case("C29_device_dependency_output_is_bound_and_persisted") {
                val calendar = calendarInput().apply { remove("title") }
                val id = createDevice(listOf(
                    DeviceStep("text.draft", JSONObject().put("instruction", "Write a title")),
                    DeviceStep("calendar.create", calendar, dependsOn = listOf(0), bindings = mapOf("title" to OutputBinding(0, "text"))),
                ))
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                val calendarAttempt = store.dao.attempts(id).first { it.stepId.endsWith(":1") }
                check(JSONObject(calendarAttempt.detail).getJSONObject("inputs").getString("title") == "Synthetic event title")
                check(driver.writes == 1 && store.dao.evidence(id).size == 3)
            }
            case("C30_device_proposals_reject_cycles_and_observed_app_authority") {
                rejects { DevicePlanner.plan("synthetic_chat", "synthetic", listOf(DeviceStep("app.open", JSONObject().put("package", "synthetic.app"), dependsOn = listOf(1)))) }
                rejects { DevicePlanner.plan("synthetic_chat", "synthetic", listOf(
                    DeviceStep("text.draft", JSONObject().put("instruction", "Write a package")),
                    DeviceStep("app.open", dependsOn = listOf(0), bindings = mapOf("package" to OutputBinding(0, "text"))),
                )) }
                val id = createDevice("app.open", JSONObject().put("package", "synthetic.app"))
                val saved = store.snapshot(id)!!
                rejects { PlanValidator.validate(saved.copy(task = saved.task.copy(chatId = "different_chat"))) }
            }
            case("C31_device_origin_and_publication_recovery_are_idempotent") {
                val id = createDevice("app.open", JSONObject().put("package", "synthetic.app"))
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                check(entries(id).single().getString("chat_id") == origins.getValue(id))
                replayPublicationCheckpoint(id)
                check(entries(id).size == 1 && ChatHistory.activeId(ctx) == selectedChat)
                val replay = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, replay).state == "COMPLETED" && replay.executions == 0)
            }
            case("C32_device_stop_and_stale_revision_block_dispatch") {
                for (variant in listOf("stop", "revision", "epoch", "lease")) {
                    val id = createDevice("calendar.create", calendarInput())
                    val driver = FakeCapabilityDriver().apply { beforeWrite = {
                        if (variant == "stop") TaskCoordinator.signalCancel(id) else store.transaction {
                            val task = store.dao.task(id)!!
                            store.dao.updateTask(task.copy(state = "READY", revision = if (variant == "revision") task.revision + 1 else task.revision,
                                cancelEpoch = if (variant == "epoch") task.cancelEpoch + 1 else task.cancelEpoch,
                                executionId = if (variant == "lease") "new_execution" else task.executionId))
                        }
                    } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state != "COMPLETED")
                    check(driver.writes == 0 && store.dao.events(id).none { it.kind == "DISPATCH_INTENT" })
                    unpublished(id)
                }
            }
            case("C33_bills_only_labeled_visible_dues_and_explicit_totals") {
                val id = createDevice("bills.read", JSONObject().put("package", "synthetic.bills").put("total_requested", true))
                check(TaskCoordinator.runDevice(ctx, id, FakeCapabilityDriver()).state == "COMPLETED")
                val partial = createDevice("bills.read", JSONObject().put("package", "synthetic.bills").put("total_requested", true))
                val driver = FakeCapabilityDriver().apply { result = { step, input -> deviceResult(step, input).also {
                    it.output.remove("total_amount"); it.evidence.put("explicit_total", false)
                } } }
                check(TaskCoordinator.runDevice(ctx, partial, driver).state == "BLOCKED")
                unpublished(partial)
            }
            case("C34_generation_unavailable_is_blocked_and_exact_text_is_unchanged") {
                val id = createDevice("text.draft", JSONObject().put("instruction", "Write a greeting"))
                val driver = FakeCapabilityDriver().apply { result = { _, _ -> CapabilityResult("BLOCKED", message = "memory_pressure") } }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED")
                unpublished(id)
                val text = "CHECK-73: ₹1,200 on 28 October 2026."
                val exact = createDevice("text.draft", JSONObject().put("verbatim", true).put("text", text))
                check(TaskCoordinator.runDevice(ctx, exact, FakeCapabilityDriver()).answer == text)
                check(entries(exact).single().getString("answer") == text)
            }
            case("C35_search_needs_exact_query_readback_and_independent_results") {
                fun input() = JSONObject().put("package", "synthetic.search").put("query", "hotel 7 January 2027")
                val valid = createDevice("app.search", input())
                check(TaskCoordinator.runDevice(ctx, valid, FakeCapabilityDriver()).state == "COMPLETED")
                for (variant in listOf("wrong_date", "missing_control", "no_result")) {
                    val id = createDevice("app.search", input())
                    val driver = FakeCapabilityDriver().apply { result = { step, fields -> deviceResult(step, fields).also {
                        when (variant) {
                            "wrong_date" -> it.evidence.put("observed_query", "hotel 7 January 2026")
                            "missing_control" -> it.evidence.remove("observed_query")
                            else -> it.evidence.remove("matched_result")
                        }
                    } } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED")
                    unpublished(id)
                }
            }
            case("C36_v2_research_graph_binds_receipt_and_publishes_once") {
                val id = createDeviceResearch()
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                val saved = store.snapshot(id)!!
                check(JSONObject(saved.task.payload).getInt("version") == 2 && saved.task.capability == "device")
                check(saved.steps.map { it.capability } == listOf("research.prepare", "research.submit", "research.capture", "task.publish"))
                check(saved.steps.all { it.state == "COMPLETED" } && driver.writes == 1 && driver.executions == 3)
                val receipt = JSONObject(saved.steps[1].output).getJSONObject("receipt")
                val capturedInputs = JSONObject(store.dao.attempts(id).first { it.stepId == saved.steps[2].id }.detail).getJSONObject("inputs")
                check(capturedInputs.getJSONObject("receipt").toString() == receipt.toString())
                check(entries(id).single().getString("answer").contains("Synthetic research answer"))
                check(store.dao.facts(id).any { it.key == "step.2.output" && it.status == "observed_untrusted" })
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED" && driver.writes == 1)
                replayPublicationCheckpoint(id)
                check(entries(id).size == 1 && ChatHistory.activeId(ctx) == selectedChat)
            }
            case("C37_v2_research_rejects_changed_owner_app_hash_and_receipt") {
                val valid = store.snapshot(createDeviceResearch())!!
                val payload = JSONObject(valid.task.payload).put("owner_request", "A different owner question")
                rejects { PlanValidator.validate(valid.copy(task = valid.task.copy(request = "A different owner question", payload = payload.toString()))) }
                for (variant in listOf("wrong_app", "prompt_hash", "answer_hash", "completion_marker", "receipt")) {
                    val id = createDeviceResearch()
                    val driver = FakeCapabilityDriver().apply { result = { step, input -> deviceResult(step, input).also {
                        if (variant == "receipt" && step.capability == "research.submit")
                            it.output.getJSONObject("receipt").put("prompt_sha256", hash("a different submitted question"))
                        if (step.capability == "research.capture") when (variant) {
                            "wrong_app" -> it.evidence.put("source_package", "synthetic.other")
                            "prompt_hash" -> it.evidence.put("prompt_sha256", hash("another prompt"))
                            "answer_hash" -> it.evidence.put("answer_sha256", hash("another answer"))
                            "completion_marker" -> it.evidence.put("completion_marker", "invalid_fixture_marker")
                            else -> Unit
                        }
                    } } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED") { variant }
                    check(driver.writes == 1)
                    unpublished(id)
                }
            }
            case("C38_generated_draft_is_bound_to_exact_unsent_app_composer") {
                val id = createDevice(draftSteps())
                val driver = FakeCapabilityDriver()
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                val saved = store.snapshot(id)!!
                val generated = JSONObject(saved.steps[0].output).getString("text")
                val handoff = JSONObject(saved.steps[1].output)
                val inputs = JSONObject(store.dao.attempts(id).first { it.stepId == saved.steps[1].id }.detail).getJSONObject("inputs")
                check(inputs.getString("text") == generated && handoff.getString("text") == generated)
                check(handoff.getString("recipient") == "Fixture Contact" && handoff.getBoolean("prepared") && !handoff.getBoolean("sent"))
                check(driver.writes == 1 && entries(id).size == 1)
            }
            case("C39_handoff_rejects_wrong_app_recipient_hash_and_extra_recipient") {
                for (variant in listOf("wrong_app", "wrong_recipient", "screen_hash", "extra_recipient")) {
                    val id = createDevice(draftSteps())
                    val driver = FakeCapabilityDriver().apply { result = { step, input -> deviceResult(step, input).also {
                        if (step.capability == "message.prepare") when (variant) {
                            "wrong_app" -> it.evidence.put("source_package", "synthetic.other")
                            "screen_hash" -> it.evidence.put("source_text_sha256", hash("another screen"))
                            "wrong_recipient" -> {
                                it.evidence.getJSONObject("recipient_field").put("text", "Different Fixture")
                                val screen = it.evidence.getString("screen_text") + "\nDifferent Fixture"
                                it.evidence.put("screen_text", screen).put("source_text_sha256", hash(screen))
                            }
                            "extra_recipient" -> {
                                it.evidence.getJSONArray("recipient_fields").put(messageField(input, "Different Fixture", "recipient_chip", 140))
                                val screen = it.evidence.getString("screen_text") + "\nDifferent Fixture"
                                it.evidence.put("screen_text", screen).put("source_text_sha256", hash(screen))
                            }
                        }
                    } } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED") { variant }
                    check(driver.writes == 1)
                    unpublished(id)
                }
            }
            case("C40_visible_message_source_is_bound_to_summary_and_saved_as_untrusted") {
                val id = createDevice(summarySteps())
                var summarizedSource: String? = null
                val driver = FakeCapabilityDriver().apply { result = { step, input -> deviceResult(step, input).also {
                    if (step.capability == "text.draft") {
                        summarizedSource = input.getString("text")
                        val source = JSONObject(summarizedSource!!)
                        check(source.getString("package") == "synthetic.messages" && source.getJSONArray("items").getString(0) == fixtureMessage)
                        it.output.put("text", "Visible fixture text mentions CHECK-84 and 7 January 2027.")
                    }
                } } }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "COMPLETED")
                val saved = store.snapshot(id)!!
                val read = JSONObject(saved.steps[0].output)
                check(summarizedSource == read.getString("source") && read.getInt("count") == 1)
                val proof = JSONObject(saved.steps[0].evidence)
                check(proof.getString("coverage") == "visible_screen" && proof.getString("source_sha256") == hash(summarizedSource!!))
                check(store.dao.facts(id).any { it.key == "step.0.output" && it.status == "observed_untrusted" })
                check(driver.writes == 0 && entries(id).single().getString("answer").contains("One screen only"))
            }
            case("C41_bad_message_source_or_contact_never_reaches_summary") {
                for (variant in listOf("wrong_app", "source_hash", "screen_hash", "wrong_recipient")) {
                    val id = createDevice(summarySteps())
                    val driver = FakeCapabilityDriver().apply { result = { step, input -> deviceResult(step, input).also {
                        if (step.capability == "messages.read") when (variant) {
                            "wrong_app" -> it.evidence.put("source_package", "synthetic.other")
                            "source_hash" -> it.evidence.put("source_sha256", hash("another message source"))
                            "screen_hash" -> it.evidence.put("source_text_sha256", hash("another screen"))
                            "wrong_recipient" -> {
                                it.evidence.getJSONObject("header").put("text", "Different Fixture")
                                val screen = it.evidence.getString("screen_text") + "\nDifferent Fixture"
                                it.evidence.put("screen_text", screen).put("source_text_sha256", hash(screen))
                            }
                        }
                    } } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED") { variant }
                    check(driver.executions == 1 && driver.writes == 0)
                    unpublished(id)
                }
            }
            case("C42_research_recommendations_never_fill_missing_owner_stay_choices") {
                val question = "Research a good season for hotels in Example City"
                val origin = chat()
                val owner = JSONObject().put("destination", "Example City")
                val research = ResearchCapabilities.steps(ctx, origin, question)
                val resolver = DeviceStep("hotel.resolve_inputs", JSONObject().put("package", HotelCapabilities.PACKAGE).put("owner_facts", owner),
                    dependsOn = listOf(2), bindings = mapOf("research_answer" to OutputBinding(2, "answer")))
                val search = DeviceStep("hotel.search", JSONObject().put("package", HotelCapabilities.PACKAGE), dependsOn = listOf(3),
                    bindings = listOf("destination", "check_in", "check_out", "adults", "rooms", "children", "child_ages").associateWith { OutputBinding(3, it) })
                val id = TaskCoordinator.createDevice(ctx, origin, question, research + resolver + search).also { origins[it] = origin }
                val recommendation = "An example is 2027-11-12 to 2027-11-14, 2 adults, 1 room, 0 children. This is a recommendation, not an owner choice."
                val driver = FakeCapabilityDriver().apply { result = { step, input ->
                    if (step.capability == "hotel.resolve_inputs") HotelCapabilities.resolve(input)
                    else deviceResult(step, input).also {
                        if (step.capability == "research.capture") {
                            it.output.put("answer", recommendation)
                            it.evidence.put("answer_sha256", hash(recommendation))
                        }
                    }
                } }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "WAITING_INPUT")
                val saved = store.snapshot(id)!!
                val attempt = store.dao.attempts(id).first { it.stepId == saved.steps[3].id }
                val detail = JSONObject(attempt.detail)
                val candidate = detail.getJSONObject("candidate_output")
                val missing = candidate.getJSONArray("missing_fields").let { a -> (0 until a.length()).map { a.getString(it) } }
                check(missing.containsAll(listOf("check_in", "check_out", "adults", "rooms", "children")))
                check(detail.getJSONObject("inputs").getString("research_answer") == recommendation)
                check(detail.getJSONObject("inputs").getJSONObject("owner_facts").toString() == owner.toString())
                check(listOf("check_in", "check_out", "adults", "rooms", "children").none { candidate.has(it) })
                check(driver.executions == 4 && store.dao.attempts(id).none { it.stepId == saved.steps[4].id })
                check(!detail.getJSONObject("candidate_evidence").getBoolean("research_used_for_choices"))
                unpublished(id)
            }
            case("C43_grounded_intent_binding_survives_save_and_rejects_changed_source") {
                val origin = chat()
                val id = TaskCoordinator.createDevice(ctx, origin, fixtureIntentRequest, intentSteps()).also { origins[it] = origin }
                var received: JSONObject? = null
                val driver = FakeCapabilityDriver().apply { result = { step, input ->
                    if (step.capability == "intent.resolve") resolvedIntentResult(input, intentAnswer())
                    else {
                        check(step.capability == "app.action")
                        received = JSONObject(input.toString())
                        CapabilityResult("BLOCKED", message = "Synthetic consumer has no live media completion oracle.")
                    }
                } }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED")
                val saved = store.snapshot(id)!!
                val intent = JSONObject(saved.steps[0].output).getJSONObject("intent")
                val actionInputs = JSONObject(store.dao.attempts(id).first { it.stepId == saved.steps[1].id }.detail).getJSONObject("inputs")
                check(saved.steps[0].state == "COMPLETED" && driver.executions == 2 && driver.writes == 1)
                check(received!!.getString("package") == "synthetic.media" && received!!.getString("request") == fixtureIntentRequest)
                check(DeviceCapabilities.canonical(actionInputs.getJSONObject("intent")) == DeviceCapabilities.canonical(intent))
                check(intent.getString("target") == "Amber Horizon" && intent.getString("source_id") == "fixture-source")
                check(intent.getJSONObject("proof").getString("source_text_sha256") == hash(fixtureIntentSource))
                check(RequestResolver.validate(fixtureIntentRequest, intentSources(intentInput()), intent) == null)
                val changed = JSONObject(saved.task.payload)
                changed.getJSONArray("steps").getJSONObject(0).getJSONObject("inputs").getJSONArray("sources").getJSONObject(0)
                    .put("text", fixtureIntentSource + "Changed source content.")
                store.transaction { store.dao.updateTask(saved.task.copy(payload = changed.toString())) }
                check(TaskCoordinator.runDevice(ctx, id, driver).state == "BLOCKED")
                check(driver.executions == 2 && driver.writes == 1)
                check(RequestResolver.validate(fixtureIntentRequest,
                    intentSources(changed.getJSONArray("steps").getJSONObject(0).getJSONObject("inputs")), intent) != null)
                unpublished(id)
            }
            case("C44_malformed_unresolved_or_forged_intent_never_dispatches_action") {
                for (variant in listOf("malformed", "unresolved", "missing_source", "forged_proof")) {
                    val origin = chat()
                    val id = TaskCoordinator.createDevice(ctx, origin, fixtureIntentRequest, intentSteps()).also { origins[it] = origin }
                    val driver = FakeCapabilityDriver().apply { result = { step, input ->
                        check(step.capability == "intent.resolve") { "Invalid intent reached the action driver" }
                        val answer = intentAnswer()
                        when (variant) {
                            "malformed" -> answer.put("target", 7)
                            "unresolved" -> answer.put("target", "any one of above").put("source_id", "request")
                            "missing_source" -> answer.put("source_id", "unavailable-source")
                        }
                        resolvedIntentResult(input, answer).also {
                            if (variant == "forged_proof") {
                                val intent = it.output.getJSONObject("intent")
                                intent.getJSONObject("proof").put("source_text_sha256", hash("a different source"))
                                it.evidence.put("intent_sha256", DeviceCapabilities.digest(DeviceCapabilities.canonical(intent)))
                            }
                        }
                    } }
                    check(TaskCoordinator.runDevice(ctx, id, driver).state == if (variant == "forged_proof") "BLOCKED" else "WAITING_INPUT") { variant }
                    check(driver.executions == 1 && driver.writes == 0)
                    check(store.dao.attempts(id).none { it.stepId.endsWith(":1") })
                    unpublished(id)
                }
            }
            case("C45_selected_app_recipient_sources_and_request_cannot_be_output_bound") {
                val origin = chat()
                for (field in listOf("package", "recipient", "sources", "request")) {
                    val steps = intentSteps().toMutableList()
                    steps[1] = steps[1].copy(bindings = steps[1].bindings + (field to OutputBinding(0, "intent.target")))
                    val error = runCatching { DevicePlanner.plan(origin, fixtureIntentRequest, steps) }.exceptionOrNull()
                    check(error is IllegalArgumentException && error.message.orEmpty().contains("authority")) { "$field authority binding was accepted" }
                }
                check(store.latestForChat(origin) == null)
            }
        }

        private fun chat(): String = ("coordinator_check_" + UUID.randomUUID()).also {
            chats += it
            check(fixturePrefs.edit().putStringSet("chat_ids", chats.toSet()).commit()) { "Could not retain fixture ownership" }
        }
        private fun create(question: String = "Explain how a compass works"): String {
            val chat = chat()
            return TaskCoordinator.create(ctx, chat, question).also { origins[it] = chat }
        }
        private fun createDevice(capability: String, inputs: JSONObject): String = createDevice(listOf(DeviceStep(capability, inputs)))
        private fun createDevice(steps: List<DeviceStep>): String {
            val chat = chat()
            return TaskCoordinator.createDevice(ctx, chat, "Synthetic capability fixture", steps).also { origins[it] = chat }
        }
        private fun createDeviceResearch(): String {
            val question = "Explain tides for a synthetic coordinator fixture"
            val origin = chat()
            return TaskCoordinator.createDevice(ctx, origin, question, ResearchCapabilities.steps(ctx, origin, question)).also { origins[it] = origin }
        }
        private fun entries(id: String) = ChatHistory.entriesAll(ctx).filter {
            it.optString("chat_id") == origins.getValue(id) && it.optString("task_id") == id
        }
        private fun unpublished(id: String) {
            check(store.dao.task(id)?.published != true && entries(id).isEmpty()) { "Unexpected result publication" }
        }
        private fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid input was accepted" } }

        private fun replayPublicationCheckpoint(id: String) {
            val saved = store.snapshot(id)!!
            store.transaction {
                store.dao.updateTask(saved.task.copy(published = false, state = "RUNNING", executionId = "synthetic_publication_crash"))
                store.dao.updateStep(saved.steps.last().copy(state = "READY"))
            }
            TaskCoordinator.recoverInterrupted(ctx, setOf(id))
            check(store.dao.task(id)!!.let { it.published && it.state == "COMPLETED" })
        }

        private fun completedSubmission(id: String, digest: String? = null,
                                        proofRevision: Int? = null, proofEpoch: Int? = null) {
            val snapshot = store.snapshot(id)!!
            val spec = GeminiResearch.specFromJson(JSONObject(snapshot.task.payload).getJSONObject("spec"))
            val step = snapshot.steps[1]
            val finished = System.currentTimeMillis()
            val evidence = JSONObject().put("matching_user_turn_verified", true)
                .put("provider_package", snapshot.task.provider).put("prompt_sha256", digest ?: hash(spec.prompt))
                .put("synthetic_driver", true)
            store.transaction {
                store.dao.updateStep(snapshot.steps[0].copy(state = "COMPLETED",
                    evidence = JSONObject().put("normal_chat_verified", true).put("synthetic_driver", true).toString()))
                store.dao.updateStep(step.copy(state = "COMPLETED", attempts = 1, evidence = evidence.toString(),
                    output = JSONObject().put("submission_verified", true).toString()))
                store.dao.insertAttempt(AttemptRecord(UUID.randomUUID().toString(), id, step.id,
                    proofRevision ?: snapshot.task.revision, proofEpoch ?: snapshot.task.cancelEpoch,
                    1, finished - 1, finishedAt = finished, durationMs = 1,
                    state = "COMPLETED", dispatchState = "VERIFIED"))
            }
        }

        private fun orphanSend(id: String): String {
            val snapshot = store.snapshot(id)!!
            val step = snapshot.steps[1]
            val attemptId = UUID.randomUUID().toString()
            store.transaction {
                store.dao.updateTask(snapshot.task.copy(state = "RUNNING", executionId = "synthetic_interrupted_process"))
                store.dao.updateStep(snapshot.steps[0].copy(state = "COMPLETED"))
                store.dao.updateStep(step.copy(state = "RUNNING", attempts = 1))
                store.dao.insertAttempt(AttemptRecord(attemptId, id, step.id, snapshot.task.revision,
                    snapshot.task.cancelEpoch, 1, System.currentTimeMillis(), dispatchState = "DISPATCHED"))
            }
            return attemptId
        }

        private fun case(name: String, block: () -> Unit) {
            try { block(); results.put(JSONObject().put("id", name).put("status", "passed")) }
            catch (error: Exception) { results.put(JSONObject().put("id", name).put("status", "failed")
                .put("error", error.javaClass.simpleName).put("detail", error.message.orEmpty().take(300))) }
        }

        fun cleanup() {
            case("C18_fixture_cleanup_and_selection") {
                val remaining = mutableSetOf<String>()
                for (chat in chats) {
                    val tasksCleared = runCatching {
                        TaskCoordinator.clearChat(ctx, chat)
                        store.latestForChat(chat) == null
                    }.getOrDefault(false)
                    val historyCleared = runCatching { ChatHistory.clear(ctx, chat) }.getOrDefault(false)
                    if (!tasksCleared || !historyCleared) remaining += chat
                }
                check(fixturePrefs.edit().putStringSet("chat_ids", remaining).commit()) { "Could not retain fixture cleanup state" }
                check(remaining.isEmpty()) { "Synthetic fixture cleanup failed; owned IDs retained for the next run" }
                check(ChatHistory.activeId(ctx) == selectedChat) { "Selected chat changed during checks" }
            }
        }

        fun report(): JSONObject {
            val passed = (0 until results.length()).count { results.getJSONObject(it).getString("status") == "passed" }
            return JSONObject().put("status", if (passed == results.length()) "ok" else "failed")
                .put("passed", passed).put("failed", results.length() - passed).put("checks", results)
                .put("scope", "Synthetic Room records and fake research/capability driver callbacks; no device apps, models or real process kill")
        }
    }

    private class FixtureRollback : RuntimeException()

    private fun calendarInput() = JSONObject().put("package", "com.android.providers.calendar").put("title", "Synthetic calendar check")
        .put("start_ms", 1799280000000L).put("end_ms", 1799366400000L).put("all_day", true).put("zone", "Asia/Kolkata").put("local_date", "2027-01-07")

    private const val fixtureMessage = "CHECK-84: The fixture appointment is on 7 January 2027."
    private fun draftSteps() = listOf(
        DeviceStep("text.draft", JSONObject().put("kind", "message").put("instruction", "Write a brief synthetic fixture greeting")),
        DeviceStep("message.prepare", JSONObject().put("package", "synthetic.messages").put("recipient", "Fixture Contact").put("kind", "message"),
            dependsOn = listOf(0), bindings = mapOf("text" to OutputBinding(0, "text"))),
    )
    private fun summarySteps() = listOf(
        DeviceStep("messages.read", JSONObject().put("package", "synthetic.messages").put("recipient", "Fixture Contact").put("limit", 1)),
        DeviceStep("text.draft", JSONObject().put("kind", "summary").put("instruction", "Summarize the captured source without adding facts"),
            dependsOn = listOf(0), bindings = mapOf("text" to OutputBinding(0, "source"))),
    )
    private fun messageField(input: JSONObject, text: String, role: String, top: Int, editable: Boolean = false) = JSONObject()
        .put("text", text).put("package", input.getString("package")).put("resource_id", input.getString("package") + ":id/" + role)
        .put("hint", "").put("editable", editable).put("class", if (editable) "android.widget.EditText" else "android.widget.TextView")
        .put("bounds", JSONArray(listOf(10, top, 1000, top + 60)))

    private const val fixtureIntentRequest = "play any one of above in my collection"
    private const val fixtureIntentSource = "Verified fixture choices:\n1. Amber Horizon\n2. Quiet River\n"
    private fun intentInput() = JSONObject().put("request", fixtureIntentRequest).put("sources", JSONArray()
        .put(JSONObject().put("id", "fixture-source").put("text", fixtureIntentSource)))
    private fun intentSteps() = listOf(
        DeviceStep("intent.resolve", intentInput()),
        DeviceStep("app.action", JSONObject().put("package", "synthetic.media").put("request", fixtureIntentRequest).put("skill", "play_music"),
            dependsOn = listOf(0), bindings = mapOf("intent" to OutputBinding(0, "intent"))),
    )
    private fun intentAnswer() = JSONObject().put("status", "ok").put("mode", "act").put("action", "play")
        .put("target", "Amber Horizon").put("scope", "my collection").put("source_id", "fixture-source")
    private fun intentSources(input: JSONObject) = input.getJSONArray("sources").let { a -> (0 until a.length()).map { index ->
        a.getJSONObject(index).let { RequestResolver.Source(it.getString("id"), it.getString("text")) }
    } }
    private fun resolvedIntentResult(input: JSONObject, answer: JSONObject): CapabilityResult {
        val resolution = RequestResolver.resolve(input.getString("request"), intentSources(input)) { writer ->
            check(writer.kind == "intent" && writer.instruction == input.getString("request") && writer.errors().isEmpty())
            check(JSONArray(writer.text).getJSONObject(0).getString("text") == input.getJSONArray("sources").getJSONObject(0).getString("text"))
            JSONObject(answer.toString())
        }
        val intent = resolution.intent ?: return CapabilityResult("WAITING_INPUT", message = resolution.error.orEmpty())
        return CapabilityResult(output = JSONObject().put("intent", intent), evidence = JSONObject().put("verified", true)
            .put("captured_at", System.currentTimeMillis()).put("input_bind", input.getString("_input_bind"))
            .put("source_package", "local.intent").put("capture_method", "grounded_intent")
            .put("intent_sha256", DeviceCapabilities.digest(DeviceCapabilities.canonical(intent))))
    }

    private class FakeCapabilityDriver : CapabilityDriver {
        var stoppedFlag = false
        var interruptedFlag = false
        var foundOnInspection = false
        var beforeWrite: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        var result: ((StepRecord, JSONObject) -> CapabilityResult)? = null
        var writes = 0
        var executions = 0
        var inspections = 0
        override val stopped get() = stoppedFlag
        override val interrupted get() = interruptedFlag
        override fun progress(message: String) {}
        override fun inspect(step: StepRecord, inputs: JSONObject): CapabilityResult? {
            inspections++
            return if (foundOnInspection) deviceResult(step, inputs) else null
        }
        override fun execute(step: StepRecord, inputs: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
            executions++
            if (DeviceCapabilities.registry[step.capability]?.effect == "external_write") {
                beforeWrite?.invoke()
                if (!beforeDispatch()) return CapabilityResult("CANCELLED", message = "Dispatch refused")
                writes++
                afterWrite?.invoke()
            }
            return result?.invoke(step, inputs) ?: deviceResult(step, inputs)
        }
    }

    private fun deviceResult(step: StepRecord, input: JSONObject): CapabilityResult {
        val evidence = JSONObject().put("verified", true).put("captured_at", System.currentTimeMillis())
            .put("input_bind", input.getString("_input_bind")).put("source_package", input.optString("package", "local.writer"))
        val output = JSONObject()
        when (step.capability) {
            "calendar.create" -> {
                listOf("title", "start_ms", "end_ms", "all_day", "zone").forEach { output.put(it, input.get(it)); evidence.put(it, input.get(it)) }
                output.put("event_id", 73L).put("calendar_id", 1L).put("busy", true)
                evidence.put("event_id", 73L).put("capture_method", "calendar_provider_readback")
            }
            "app.open" -> { output.put("package", input.getString("package")); evidence.put("foreground_package", input.getString("package")) }
            "app.search" -> { output.put("package", input.getString("package")).put("query", input.getString("query")); evidence.put("query_visible", true).put("results_visible", true)
                .put("observed_query", input.getString("query")).put("matched_result", "Synthetic independent result") }
            "text.draft" -> {
                output.put("status", "ok").put("text", if (input.optBoolean("verbatim")) input.getString("text") else "Synthetic event title")
                evidence.put("generated", !input.optBoolean("verbatim"))
                if (input.optBoolean("verbatim")) evidence.put("capture_method", "verbatim_text")
                else evidence.put("model", "synthetic_fixture").put("instruction_sha256", hash(input.getString("instruction")))
            }
            "research.prepare", "research.submit", "research.capture" -> {
                val spec = GeminiResearch.specFromJson(input.getJSONObject("spec"))
                evidence.put("source_package", Capabilities.GEMINI).put("prompt_sha256", hash(spec.prompt))
                when (step.capability) {
                    "research.prepare" -> {
                        output.put("prepared", true)
                        evidence.put("normal_chat_verified", true).put("capture_method", "normal_new_chat_readback")
                    }
                    "research.submit" -> {
                        output.put("receipt", JSONObject().put("prompt_sha256", hash(spec.prompt)).put("marker", spec.marker)
                            .put("verified_at", System.currentTimeMillis())).put("reused", false)
                        evidence.put("exact_user_turn_verified", true).put("capture_method", "exact_user_turn_readback")
                    }
                    "research.capture" -> {
                        val answer = "Synthetic research answer. No app or model was used."
                        output.put("answer", answer)
                        evidence.put("full_response", true).put("matching_user_turn_verified", true).put("status", "ok")
                            .put("capture_method", "provider_copy_response").put("completion_marker", spec.marker)
                            .put("owner_query_sha256", hash(spec.query)).put("answer_sha256", hash(answer))
                    }
                }
            }
            "message.prepare" -> {
                val recipient = messageField(input, input.getString("recipient"), "conversation_name", 70)
                val body = messageField(input, input.getString("text"), "compose_body", 600, editable = true)
                val screen = input.getString("recipient") + "\n" + input.getString("text")
                output.put("package", input.getString("package")).put("recipient", input.getString("recipient"))
                    .put("kind", input.getString("kind")).put("text", input.getString("text")).put("subject", input.optString("subject"))
                    .put("prepared", true).put("sent", false).put("reused", false)
                evidence.put("capture_method", "accessibility_draft_fields").put("screen_text", screen).put("source_text_sha256", hash(screen))
                    .put("screen_height", 1920).put("recipient_field", recipient).put("recipient_fields", JSONArray().put(recipient)).put("body_field", body)
            }
            "messages.read" -> {
                val scope = "visible conversation text; requested contact header checked"
                val source = JSONObject().put("package", input.getString("package")).put("scope", scope)
                    .put("requested_conversation", input.getString("recipient")).put("items", JSONArray().put(fixtureMessage)).toString()
                val screen = input.getString("recipient") + "\n" + fixtureMessage
                output.put("package", input.getString("package")).put("source", source).put("count", 1).put("scope", scope).put("truncated", false)
                evidence.put("capture_method", "accessibility_message_viewport").put("coverage", "visible_screen")
                    .put("screen_text", screen).put("source_text_sha256", hash(screen)).put("source_sha256", hash(source)).put("screen_height", 1920)
                    .put("header", messageField(input, input.getString("recipient"), "conversation_name", 70))
                    .put("message_items", JSONArray().put(messageField(input, fixtureMessage, "message_text", 300).put("captured_text", fixtureMessage)))
            }
            "bills.read" -> {
                val text = "Grand total due: ₹75"
                val items = JSONArray().put(JSONObject().put("label", "Grand total due").put("amount", "₹75"))
                output.put("items", items).put("total_amount", "₹75")
                evidence.put("screen_text", text).put("source_text_sha256", hash(text)).put("coverage", "visible_screen").put("explicit_total", true)
            }
        }
        return CapabilityResult(output = output, evidence = evidence)
    }

    private class FakeDriver : ResearchDriver {
        var stoppedFlag = false
        var interruptedFlag = false
        var submission = GeminiResearch.Submission.ABSENT
        var answer = "Synthetic fixture answer. No app or model was used."
        var onProgress: (() -> Unit)? = null
        var beforeSend: (() -> Unit)? = null
        var afterSend: (() -> Unit)? = null
        var beforeCapture: (() -> Unit)? = null
        var captureResult: ((GeminiResearch.Spec) -> GeminiResearch.Result)? = null
        var prepares = 0
        var inspections = 0
        var submissions = 0
        var sends = 0
        var captures = 0
        val captureTimeouts = mutableListOf<Long>()
        val calls get() = prepares + inspections + submissions + captures
        override val stopped get() = stoppedFlag
        override val interrupted get() = interruptedFlag
        override fun progress(message: String) { onProgress?.invoke() }
        override fun prepare(spec: GeminiResearch.Spec): JSONObject {
            prepares++
            return JSONObject().put("normal_chat_verified", true).put("source_package", Capabilities.GEMINI)
                .put("synthetic_driver", true)
        }
        override fun inspect(spec: GeminiResearch.Spec): GeminiResearch.Submission { inspections++; return submission }
        override fun submit(spec: GeminiResearch.Spec, beforeDispatch: () -> Boolean): Boolean {
            submissions++
            beforeSend?.invoke()
            if (!beforeDispatch()) return false
            sends++
            submission = GeminiResearch.Submission.SUBMITTED
            afterSend?.invoke()
            return true
        }
        override fun capture(spec: GeminiResearch.Spec, timeoutMs: Long,
                             receipt: GeminiResearch.SubmissionReceipt): GeminiResearch.Result {
            captures++
            check(receipt.matches(spec)) { "Capture received a mismatched submission receipt" }
            check(timeoutMs in 1L..300000L) { "Capture received an invalid wait budget" }
            captureTimeouts += timeoutMs
            beforeCapture?.invoke()
            return captureResult?.invoke(spec) ?: validResult(spec, answer)
        }
    }

    private fun validResult(spec: GeminiResearch.Spec, answer: String) = GeminiResearch.Result(answer, JSONObject()
        .put("status", "ok").put("full_response", true).put("matching_user_turn_verified", true)
        .put("completion_marker", spec.marker).put("answer_sha256", hash(answer))
        .put("source_package", Capabilities.GEMINI).put("scope", "one synthetic fixture response")
        .put("source_untrusted", true).put("synthetic_driver", true))

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
