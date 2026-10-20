package dev.layatest.agent.coordinator

import android.content.Context
import dev.layatest.agent.GeminiResearch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Registered code owns verification and authority. Stored plans contain data, never executable code. */
internal data class Capability(
    val id: String,
    val title: String,
    val effect: String,
    val verifier: String,
    val timeoutMs: Long,
)

internal object Capabilities {
    const val GEMINI = "com.google.android.apps.bard"
    val research = listOf(
        Capability("research.prepare", "Prepare normal Gemini chat", "navigation", "gemini.normal_blank", 15000),
        Capability("research.submit", "Submit the saved question", "public_question", "gemini.exact_user_turn", 15000),
        Capability("research.capture", "Collect the complete answer", "read", "gemini.full_response_footer", 300000),
        Capability("research.publish", "Return the answer to this chat", "local_write", "chat.publication_key", 5000),
    ).associateBy { it.id }
    val all = research + DeviceCapabilities.registry
}

internal interface TaskPlanner {
    fun plan(ctx: Context, chatId: String, request: String): TaskSnapshot
}

/** The explicit Research entry already supplies the capability; no model call is needed to plan it. */
internal object ResearchPlanner : TaskPlanner {
    override fun plan(ctx: Context, chatId: String, request: String): TaskSnapshot {
        require(chatId.isNotBlank()) { "A task needs its originating chat" }
        val spec = GeminiResearch.prepareSpec(ctx, chatId, request)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = TaskRecord(id, chatId, spec.query, payload = JSONObject()
            .put("version", 1).put("spec", spec.json()).toString(),
            state = "READY", message = "Research plan saved. Preparing Gemini…", createdAt = now, updatedAt = now)
        val steps = Capabilities.research.values.mapIndexed { index, capability ->
            StepRecord("$id:$index", id, index, capability.id, capability.title,
                dependsOn = JSONArray(if (index == 0) emptyList<String>() else listOf("$id:${index - 1}")).toString(), updatedAt = now)
        }
        return TaskSnapshot(task, steps, emptyList()).also(PlanValidator::validate)
    }
}

internal object PlanValidator {
    fun validate(plan: TaskSnapshot) {
        val task = plan.task
        if (task.capability == "device") return validateDevice(plan)
        require(task.capability == "research" && task.provider == Capabilities.GEMINI) { "Unsupported capability or provider" }
        require(task.id.isNotBlank() && task.chatId.isNotBlank() && task.revision > 0 && task.cancelEpoch >= 0)
        val payload = JSONObject(task.payload)
        require(payload.getInt("version") == 1) { "Unsupported plan version" }
        val spec = GeminiResearch.specFromJson(payload.getJSONObject("spec"))
        require(spec.query == task.request) { "Saved question differs from the owner request" }
        val steps = plan.steps.sortedBy { it.position }
        require(steps.size == 4 && steps.map { it.id }.distinct().size == steps.size)
        require(steps.map { it.capability } == Capabilities.research.keys.toList()) { "Unregistered research plan" }
        val seen = mutableSetOf<String>()
        var unfinished = false
        steps.forEachIndexed { index, step ->
            require(step.taskId == task.id && step.position == index)
            require(Capabilities.research.containsKey(step.capability))
            val dependencies = JSONArray(step.dependsOn).let { a -> (0 until a.length()).map { a.getString(it) } }
            require(dependencies == if (index == 0) emptyList() else listOf(steps[index - 1].id)) { "Invalid dependencies" }
            require(dependencies.all { it in seen }) { "Cyclic or missing dependency" }
            require(!unfinished || step.state != "COMPLETED") { "Completed step has an unfinished dependency" }
            if (step.state != "COMPLETED") unfinished = true
            seen += step.id
        }
    }

    private fun validateDevice(plan: TaskSnapshot) {
        val task = plan.task
        require(task.provider == "local.device" && task.id.isNotBlank() && task.chatId.isNotBlank() && task.request.isNotBlank())
        require(task.revision > 0 && task.cancelEpoch >= 0)
        val payload = JSONObject(task.payload)
        require(payload.getInt("version") == 2 && payload.getString("origin_chat_id") == task.chatId && payload.getString("owner_request") == task.request)
        val definitions = payload.getJSONArray("steps")
        if (plan.steps.isEmpty()) {
            require(task.state == "UNSUPPORTED" && payload.optString("validation_error").isNotBlank())
            return
        }
        val steps = plan.steps.sortedBy { it.position }
        require(steps.size == definitions.length() && steps.map { it.id }.distinct().size == steps.size)
        require(steps.last().capability == "task.publish" && steps.dropLast(1).none { it.capability == "task.publish" })
        val seen = mutableMapOf<String, StepRecord>()
        steps.forEachIndexed { index, step ->
            val definition = definitions.getJSONObject(index)
            require(step.taskId == task.id && step.position == index && step.id == definition.getString("id"))
            require(step.capability in DeviceCapabilities.registry && step.capability == definition.getString("capability")) { "Unregistered device capability" }
            val dependencies = JSONArray(step.dependsOn).strings()
            require(dependencies == definition.getJSONArray("depends_on").strings() && dependencies.distinct().size == dependencies.size)
            require(dependencies.all { it in seen }) { "Cyclic or missing dependency" }
            require(step.state != "COMPLETED" || dependencies.all { seen.getValue(it).state == "COMPLETED" }) { "Completed step has an unfinished dependency" }
            val bindings = definition.getJSONObject("bindings")
            bindings.keys().forEach { target ->
                require(target !in setOf("package", "validation_error", "_input_bind")) { "App authority cannot come from observed app/model output" }
                val source = bindings.getJSONObject(target)
                require(source.getString("step_id") in dependencies)
                require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*").matches(source.getString("field")))
            }
            val inputs = definition.getJSONObject("inputs")
            require(!inputs.has("_input_bind"))
            if (bindings.length() == 0) {
                val error = DeviceCapabilities.inputError(step.capability, inputs)
                require(error == null || inputs.optString("validation_error") == error) { error.orEmpty() }
            }
            seen[step.id] = step
        }
        require(JSONArray(steps.last().dependsOn).strings().toSet() == steps.dropLast(1).map { it.id }.toSet())
    }
}

internal data class OutputBinding(val step: Int, val field: String)
internal data class DeviceStep(
    val capability: String,
    val inputs: JSONObject = JSONObject(),
    val dependsOn: List<Int> = emptyList(),
    val bindings: Map<String, OutputBinding> = emptyMap(),
)

/** Deterministic callers select a registered capability; arbitrary requests never become executable guesses. */
internal object DevicePlanner {
    fun plan(chatId: String, request: String, capability: String, inputs: JSONObject): TaskSnapshot =
        plan(chatId, request, listOf(DeviceStep(capability, inputs)))

    fun plan(chatId: String, request: String, proposed: List<DeviceStep>): TaskSnapshot {
        require(chatId.isNotBlank() && request.isNotBlank())
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("version", 2).put("origin_chat_id", chatId).put("owner_request", request)
        val unsupported = proposed.firstOrNull { it.capability !in DeviceCapabilities.registry || it.capability == "task.publish" }
        if (unsupported != null || proposed.isEmpty()) {
            val reason = "Missing capability: ${unsupported?.capability ?: "no supported plan"}"
            return TaskSnapshot(TaskRecord(id, chatId, request, capability = "device", provider = "local.device", state = "UNSUPPORTED",
                payload = payload.put("steps", JSONArray()).put("validation_error", reason).toString(), message = reason, createdAt = now, updatedAt = now), emptyList(), emptyList()).also(PlanValidator::validate)
        }
        val definitions = JSONArray()
        var firstError: String? = null
        val steps = (proposed + DeviceStep("task.publish")).mapIndexed { index, proposal ->
            val stepId = "$id:$index"
            val dependencies = if (proposal.capability == "task.publish") proposed.indices.toList() else proposal.dependsOn
            require(dependencies.distinct().size == dependencies.size && dependencies.all { it in 0 until index }) { "Dependencies must precede their consumer" }
            val input = JSONObject(proposal.inputs.toString())
            if (proposal.bindings.isEmpty()) DeviceCapabilities.inputError(proposal.capability, input)?.let { error ->
                input.put("validation_error", error)
                if (firstError == null) firstError = error
            }
            val bindings = JSONObject()
            proposal.bindings.forEach { (target, source) ->
                require(source.step in dependencies) { "An output binding needs an explicit dependency" }
                bindings.put(target, JSONObject().put("step_id", "$id:${source.step}").put("field", source.field))
            }
            val dependencyIds = JSONArray(dependencies.map { "$id:$it" })
            definitions.put(JSONObject().put("id", stepId).put("capability", proposal.capability).put("inputs", input)
                .put("depends_on", dependencyIds).put("bindings", bindings))
            StepRecord(stepId, id, index, proposal.capability, DeviceCapabilities.registry.getValue(proposal.capability).title,
                dependsOn = dependencyIds.toString(), state = if (input.has("validation_error")) "WAITING_INPUT" else "READY", updatedAt = now)
        }
        return TaskSnapshot(TaskRecord(id, chatId, request, capability = "device", provider = "local.device",
            state = if (firstError == null) "READY" else "WAITING_INPUT", payload = payload.put("steps", definitions).toString(),
            message = firstError ?: "Validated plan saved before app actions.", createdAt = now, updatedAt = now), steps, emptyList()).also(PlanValidator::validate)
    }

    fun inputs(task: TaskRecord, step: StepRecord, steps: List<StepRecord>): JSONObject {
        val definition = JSONObject(task.payload).getJSONArray("steps").getJSONObject(step.position)
        val input = JSONObject(definition.getJSONObject("inputs").toString())
        val bindings = definition.getJSONObject("bindings")
        bindings.keys().forEach { target ->
            val source = bindings.getJSONObject(target)
            val upstream = steps.first { it.id == source.getString("step_id") }
            require(upstream.state == "COMPLETED") { "Input source is not verified" }
            var value: Any = JSONObject(upstream.output)
            source.getString("field").split('.').forEach { field -> value = (value as? JSONObject)?.get(field) ?: error("Missing dependency output: $field") }
            require(value != JSONObject.NULL) { "Dependency output is empty" }
            input.put(target, value)
        }
        return input
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
