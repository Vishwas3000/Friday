package dev.layatest.agent

import android.content.Context
import dev.layatest.agent.coordinator.CapabilityResult
import dev.layatest.agent.coordinator.DeviceCapabilities as Contracts
import dev.layatest.agent.coordinator.DeviceStep
import dev.layatest.agent.coordinator.OutputBinding
import dev.layatest.agent.coordinator.ResearchDriver
import org.json.JSONObject

/** Research operations are ordinary saved steps; this adapter never starts another task loop. */
internal class ResearchCapabilities(private val ctx: SkillContext, private val driver: ResearchDriver) {
    fun inspect(capability: String, input: JSONObject): CapabilityResult? {
        val spec = GeminiResearch.specFromJson(input.getJSONObject("spec"))
        return when (capability) {
            "research.prepare" -> prepared(input, spec)
            "research.submit" -> if (driver.inspect(spec) == GeminiResearch.Submission.SUBMITTED) submitted(input, spec, true) else null
            else -> null
        }
    }

    fun execute(capability: String, input: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
        val spec = GeminiResearch.specFromJson(input.getJSONObject("spec"))
        if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before the research step.")
        return when (capability) {
            "research.prepare" -> {
                if (!beforeDispatch()) return CapabilityResult("CANCELLED", message = "Stopped before opening Gemini.")
                if (driver.prepare(spec)?.optBoolean("normal_chat_verified") != true)
                    CapabilityResult("BLOCKED", message = "I couldn't verify a normal new Gemini chat. No new question was sent.")
                else prepared(input, spec) ?: CapabilityResult("BLOCKED", message = "Gemini's normal chat changed before it could be verified.")
            }
            "research.submit" -> {
                when (driver.inspect(spec)) {
                    GeminiResearch.Submission.SUBMITTED -> return submitted(input, spec, true)
                    GeminiResearch.Submission.AUTH_REQUIRED -> return CapabilityResult("BLOCKED", message = "Unlock Gemini to continue this saved task.")
                    GeminiResearch.Submission.WRONG_APP -> return CapabilityResult("BLOCKED", message = "Gemini's foreground screen wasn't verified.")
                    GeminiResearch.Submission.ABSENT -> if (!GeminiChat.normalBlank(ctx.screen()))
                        return CapabilityResult("OUTCOME_UNKNOWN", message = "The saved question wasn't found in a normal blank Gemini chat. I didn't send it again.")
                    GeminiResearch.Submission.UNSENT -> Unit
                }
                if (!driver.submit(spec, beforeDispatch) || driver.inspect(spec) != GeminiResearch.Submission.SUBMITTED)
                    CapabilityResult("OUTCOME_UNKNOWN", message = "I couldn't verify the submitted question. Resume will inspect Gemini before any retry.")
                else submitted(input, spec, false)
            }
            "research.capture" -> {
                val receipt = receipt(input.getJSONObject("receipt"))
                val result = driver.capture(spec, input.optLong("timeout_ms", 300000).coerceIn(1, 300000), receipt)
                if (ctx.stopped || result.evidence.optString("status") == "cancelled")
                    CapabilityResult("CANCELLED", message = "Stopped. Any submitted question remains in Gemini.")
                else if (!result.evidence.optBoolean("full_response") || result.evidence.optString("status") != "ok")
                    CapabilityResult("BLOCKED", evidence = result.evidence, message = result.evidence.optString("pending_reason")
                        .ifBlank { "Gemini's complete matching answer is not available yet. Resume this saved task to collect it." })
                else CapabilityResult(output = JSONObject().put("answer", result.answer),
                    evidence = JSONObject(result.evidence.toString()).put("verified", true)
                        .put("input_bind", input.getString("_input_bind")).put("captured_at", System.currentTimeMillis()),
                    message = "Complete research answer collected.")
            }
            else -> CapabilityResult("UNSUPPORTED", message = "This research operation is not registered.")
        }
    }

    private fun prepared(input: JSONObject, spec: GeminiResearch.Spec): CapabilityResult? {
        val screen = ctx.screen()
        if (!GeminiChat.normalBlank(screen, spec.prompt)) return null
        return CapabilityResult(output = JSONObject().put("prepared", true), evidence = evidence(input, screen.pkg)
            .put("normal_chat_verified", true).put("prompt_sha256", Contracts.digest(spec.prompt))
            .put("capture_method", "normal_new_chat_readback"), message = "Normal Gemini chat verified.")
    }

    private fun submitted(input: JSONObject, spec: GeminiResearch.Spec, reused: Boolean): CapabilityResult {
        val now = System.currentTimeMillis()
        val proof = JSONObject().put("prompt_sha256", Contracts.digest(spec.prompt)).put("marker", spec.marker).put("verified_at", now)
        return CapabilityResult(output = JSONObject().put("receipt", proof).put("reused", reused),
            evidence = evidence(input, ctx.screen().pkg).put("exact_user_turn_verified", true)
                .put("prompt_sha256", Contracts.digest(spec.prompt)).put("capture_method", "exact_user_turn_readback"),
            message = "Exact research question verified in Gemini.")
    }

    private fun evidence(input: JSONObject, source: String?) = JSONObject().put("verified", true)
        .put("input_bind", input.getString("_input_bind")).put("captured_at", System.currentTimeMillis()).put("source_package", source)

    companion object {
        val hosts = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")

        fun steps(context: Context, chatId: String, request: String): List<DeviceStep> {
            val spec = GeminiResearch.prepareSpec(context, chatId, request).json()
            return listOf(
                DeviceStep("research.prepare", JSONObject().put("spec", spec)),
                DeviceStep("research.submit", JSONObject().put("spec", spec), dependsOn = listOf(0)),
                DeviceStep("research.capture", JSONObject().put("spec", spec), dependsOn = listOf(1),
                    bindings = mapOf("receipt" to OutputBinding(1, "receipt"))),
            )
        }

        fun inputError(capability: String, input: JSONObject): String? = runCatching {
            val spec = GeminiResearch.specFromJson(input.getJSONObject("spec"))
            if (capability == "research.capture") {
                require(receipt(input.getJSONObject("receipt")).matches(spec)) { "The research submission receipt doesn't match this question." }
                require(input.optLong("timeout_ms", 300000) in 1..300000) { "Invalid research capture timeout." }
            }
        }.exceptionOrNull()?.let { it.message ?: "Saved research inputs are invalid." }

        fun verificationError(capability: String, input: JSONObject, result: CapabilityResult): String? {
            val spec = GeminiResearch.specFromJson(input.getJSONObject("spec"))
            val proof = result.evidence
            if (proof.optString("source_package") !in hosts) return "Research evidence came from an unrecognized Gemini host."
            if (proof.optString("prompt_sha256") != Contracts.digest(spec.prompt)) return "Research evidence differs from the saved question."
            return when (capability) {
                "research.prepare" -> if (result.output.optBoolean("prepared") && proof.optBoolean("normal_chat_verified") &&
                    proof.optString("capture_method") == "normal_new_chat_readback") null else "Normal new Gemini chat was not verified."
                "research.submit" -> if (proof.optBoolean("exact_user_turn_verified") &&
                    runCatching { receipt(result.output.getJSONObject("receipt")).matches(spec) }.getOrDefault(false)) null else "Exact submitted question was not verified."
                "research.capture" -> {
                    val answer = result.output.optString("answer")
                    if (answer.isNotBlank() && proof.optBoolean("full_response") && proof.optBoolean("matching_user_turn_verified") &&
                        proof.optString("status") == "ok" && proof.optString("capture_method") == "provider_copy_response" &&
                        proof.optString("completion_marker") == spec.marker && proof.optString("owner_query_sha256") == Contracts.digest(spec.query) &&
                        proof.optString("answer_sha256") == Contracts.digest(answer)) null else "A complete answer to this exact question was not verified."
                }
                else -> "Unknown research capability."
            }
        }

        private fun receipt(value: JSONObject) = GeminiResearch.SubmissionReceipt(value.getString("prompt_sha256"),
            value.getString("marker"), value.getLong("verified_at"))
    }
}
