package dev.layatest.agent

import android.app.AlarmManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import dev.layatest.agent.coordinator.CapabilityResult
import dev.layatest.agent.coordinator.DeviceCapabilities as Contracts
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Reuses bounded app executors; a return sentence or model DONE is never a completion receipt. */
internal class ActionCapabilities(private val ctx: SkillContext,
                                 private val act: (String, JSONObject, () -> Boolean) -> String?) {
    fun inspect(capability: String, input: JSONObject): CapabilityResult? =
        if (capability == "app.action" && input.optString("skill") in MediaActions.skills) MediaActions.inspect(ctx, input)
        else observe(capability, input)

    fun execute(capability: String, input: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
        if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped before the app action.")
        if (capability == "device.status") return status(input)
        if (input.getString("request") != ctx.request)
            return CapabilityResult("UNSUPPORTED", message = "The saved action differs from the owner request.")
        if (capability == "app.navigate") navigationModeError(input)?.let {
            return CapabilityResult("UNSUPPORTED", message = it)
        }
        if (capability == "app.action" && input.optString("skill") in MediaActions.skills)
            return MediaActions.execute(ctx, input, beforeDispatch)
        val report = if (capability == "app.navigate") {
            val app = ctx.apps.installed.firstOrNull { it.pkg == input.getString("package") }
                ?: return CapabilityResult("UNSUPPORTED", message = "The selected app is not installed.")
            if (!beforeDispatch()) return CapabilityResult("CANCELLED", message = "Stopped before opening the app.")
            if (ctx.foreground() != app.pkg && !ctx.open(app)) return CapabilityResult("BLOCKED", message = "The selected app couldn't be opened.")
            ctx.waitUntil(4000) { ctx.foreground() == app.pkg }
            val intent = input.optJSONObject("intent")
            val goal = if (intent == null) input.getString("goal") else MediaActions.goal(intent)
            ctx.runLaya(goal, maxSteps = 8, typeText = if (intent == null) input.optString("type_text").ifBlank { null }
                else intent.optString("target").takeIf { intent.optString("mode") == "search" && it.isNotBlank() })
            null
        } else act(input.getString("skill"), input, beforeDispatch)
        if (ctx.stopped) return CapabilityResult("CANCELLED", message = "Stopped during the app action.")
        return observe(capability, input) ?: CapabilityResult("BLOCKED", evidence = proof(input, ctx.screen()),
            message = if (report.isNullOrBlank() || report in setOf("Done", UNVERIFIED_TASK))
                "I stopped in the requested app, but couldn't verify the requested outcome. Resume will inspect it before repeating an uncertain action."
            else "$report\n\nThe requested outcome is still unverified; I haven't marked the task complete.")
    }

    private fun status(input: JSONObject): CapabilityResult {
        val request = input.getString("request").lowercase(Locale.ROOT)
        val values = JSONObject()
        val lines = mutableListOf<String>()
        if (Regex("batter|charg|power").containsMatchIn(request) || !Regex("storage|space|memory|alarm").containsMatchIn(request)) {
            val battery = ctx.android.getSystemService(BatteryManager::class.java)
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent !in 0..100) return CapabilityResult("BLOCKED", message = "Battery status is unavailable.")
            values.put("battery_percent", percent).put("charging", battery.isCharging)
            lines += "Battery $percent%" + if (battery.isCharging) ", charging" else ""
        }
        if (Regex("storage|space|memory").containsMatchIn(request)) {
            val storage = StatFs(Environment.getDataDirectory().path)
            values.put("available_bytes", storage.availableBytes).put("total_bytes", storage.totalBytes)
            lines += "Storage: %.1f GB free of %.0f GB".format(storage.availableBytes / 1e9, storage.totalBytes / 1e9)
        }
        if ("alarm" in request) {
            val next = ctx.android.getSystemService(AlarmManager::class.java).nextAlarmClock
            values.put("next_alarm_ms", next?.triggerTime ?: JSONObject.NULL)
            lines += if (next == null) "No alarm set" else "Next alarm: " +
                Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()).toString()
        }
        val output = JSONObject().put("answer", lines.joinToString("\n")).put("values", values)
        return CapabilityResult(output = output, evidence = base(input, "local.android")
            .put("capture_method", "native_device_status").put("values", JSONObject(values.toString())))
    }

    private fun observe(capability: String, input: JSONObject): CapabilityResult? {
        if (capability == "device.status") return status(input)
        if (capability == "app.navigate" && inputError(capability, input) != null) return null
        val screen = ctx.screen()
        if (screen.pkg != input.optString("package") || screen.texts.none { it.text.isNotBlank() }) return null
        val nodes = nodes(screen)
        if (capability == "app.navigate") {
            val target = normalized(destinationTarget(input))
            val found = nodes.firstOrNull { node ->
                normalized(node.optString("text").ifBlank { node.optString("content_desc") }) == target &&
                    (node.optBoolean("selected") || Regex("(?:toolbar|header|title)", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("resource_id")))
            } ?: return null
            val label = ctx.apps.installed.firstOrNull { it.pkg == screen.pkg }?.label ?: "the selected app"
            val output = JSONObject().put("answer", "Opened " + destinationTarget(input) + " in " + label + ".")
                .put("package", screen.pkg).put("target", destinationTarget(input))
            return CapabilityResult(output = output, evidence = proof(input, screen).put("capture_method", "selected_destination")
                .put("destination", found).put("observed_target", destinationTarget(input)))
        }
        if (input.optString("skill") == "device_setting" && input.optString("control") in setOf("on", "off")) {
            val setting = input.optString("setting")
            val expected = input.getString("control") == "on"
            val matched = screen.candidates.firstOrNull { it.checkable && it.checked == expected &&
                Routes.contentWords(it.label).containsAll(Routes.contentWords(setting)) && setting.isNotBlank() } ?: return null
            return CapabilityResult(output = JSONObject().put("answer", "$setting ${if (expected) "on" else "off"}. State verified.")
                .put("package", screen.pkg).put("control", input.getString("control")),
                evidence = proof(input, screen).put("capture_method", "setting_state_readback")
                    .put("setting", setting).put("observed_control", expected).put("control_label", matched.label))
        }
        if (input.optString("skill") == "alarm_timer" && input.has("hour") && input.has("minute")) {
            val next = ctx.android.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
            val time = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
            if (time.hour != input.getInt("hour") || time.minute != input.getInt("minute")) return null
            return CapabilityResult(output = JSONObject().put("answer", "Alarm verified for %02d:%02d.".format(time.hour, time.minute))
                .put("package", screen.pkg).put("hour", time.hour).put("minute", time.minute),
                evidence = proof(input, screen).put("capture_method", "next_alarm_readback").put("next_alarm_ms", next.triggerTime)
                    .put("hour", time.hour).put("minute", time.minute))
        }
        return null
    }

    private fun base(input: JSONObject, source: String?) = JSONObject().put("verified", true).put("source_package", source)
        .put("captured_at", System.currentTimeMillis()).put("input_bind", input.getString("_input_bind")).apply {
            input.optJSONObject("intent")?.let { put("intent_sha256", Contracts.digest(Contracts.canonical(it))) }
        }

    private fun proof(input: JSONObject, screen: Screen): JSONObject {
        val text = screen.texts.joinToString("\n") { it.text }
        return base(input, screen.pkg).put("screen_text", text).put("source_text_sha256", Contracts.digest(text))
    }

    companion object {
        val actionSkills = setOf("device_setting", "alarm_timer", "play_music", "play_video", "call", "navigate", "ride", "shopping", "photos", "spend_query", "balance_query")

        fun inputError(capability: String, input: JSONObject): String? = when {
            input.optString("request").isBlank() -> "The saved owner request is missing."
            capability == "device.status" -> null
            input.has("intent") && input.optJSONObject("intent")?.optJSONObject("proof")?.optString("request_sha256") != Contracts.digest(input.getString("request")) ->
                "The saved interpretation belongs to a different request."
            capability == "app.navigate" && navigationModeError(input) != null -> navigationModeError(input)
            capability == "app.navigate" && (destinationTarget(input).isBlank() ||
                (!input.has("intent") && input.optString("goal").isBlank())) ->
                "Which screen or tab should I open in that app?"
            capability == "app.action" && input.optString("skill") !in actionSkills -> "This app action is not registered."
            capability == "app.action" && input.optString("skill") in MediaActions.skills -> MediaActions.inputError(input)
            else -> null
        }

        fun verificationError(capability: String, input: JSONObject, result: CapabilityResult): String? {
            if (capability == "app.action" && input.optString("skill") in MediaActions.skills)
                return MediaActions.verificationError(input, result)
            if (capability == "app.navigate") navigationModeError(input)?.let { return it }
            val proof = result.evidence
            if (result.output.optString("answer").isBlank()) return "No verified result was returned."
            if (capability == "device.status") return if (proof.optString("source_package") == "local.android" &&
                proof.optString("capture_method") == "native_device_status" && (result.output.optJSONObject("values")?.length() ?: 0) > 0 &&
                Contracts.canonical(result.output.getJSONObject("values")) == Contracts.canonical(proof.optJSONObject("values"))) null else "Native device status was not verified."
            val text = proof.optString("screen_text")
            if (input.has("intent") && proof.optString("intent_sha256") != Contracts.digest(Contracts.canonical(input.getJSONObject("intent"))))
                return "The observation belongs to a different saved interpretation."
            if (text.isBlank() || proof.optString("source_text_sha256") != Contracts.digest(text) ||
                result.output.optString("package") != input.getString("package")) return "App observation is missing or belongs to another app."
            return when (proof.optString("capture_method")) {
                "selected_destination" -> if (capability == "app.navigate" && result.output.optString("target") == destinationTarget(input) &&
                    proof.optString("observed_target") == destinationTarget(input) &&
                    normalized(proof.getJSONObject("destination").optString("text").ifBlank { proof.getJSONObject("destination").optString("content_desc") }) == normalized(destinationTarget(input))) null else "Requested destination was not verified."
                "setting_state_readback" -> if (input.optString("skill") == "device_setting" && proof.optString("setting") == input.optString("setting") &&
                    proof.opt("observed_control") == (input.optString("control") == "on")) null else "Requested setting state was not verified."
                "next_alarm_readback" -> if (input.optString("skill") == "alarm_timer" && result.output.optInt("hour", -1) == input.optInt("hour", -2) &&
                    result.output.optInt("minute", -1) == input.optInt("minute", -2) && proof.optInt("hour", -1) == input.optInt("hour", -2) &&
                    proof.optInt("minute", -1) == input.optInt("minute", -2)) null else "Requested alarm time was not verified."
                else -> "No completion verifier is available for this app action; its outcome remains unverified."
            }
        }

        fun answer(capability: String, output: JSONObject) = output.getString("answer")
        private fun navigationModeError(input: JSONObject): String? =
            if (input.has("intent") && input.optJSONObject("intent")?.optString("mode") !in setOf("navigate", "search"))
                "This navigation step cannot execute an act-mode interpretation. Use a registered action capability."
            else null

        private fun destinationTarget(input: JSONObject): String {
            val intent = input.optJSONObject("intent") ?: return input.optString("target")
            return intent.optString("target").ifBlank { intent.optString("scope") }
        }

        /** Pure navigation contract fixtures; no Context, dispatcher or app operation is used. */
        fun selfCheck(): String {
            val request = "open my collection"
            val intent = JSONObject().put("mode", "navigate").put("action", "open").put("target", "")
                .put("scope", "my collection").put("source_id", "request")
                .put("proof", JSONObject().put("request_sha256", Contracts.digest(request)))
            val input = JSONObject().put("request", request).put("package", "synthetic.app")
                .put("target", "stale legacy target").put("intent", intent)
            check(inputError("app.navigate", input) == null)
            check(destinationTarget(input) == "my collection")
            val rejected = JSONObject(input.toString())
            rejected.getJSONObject("intent").put("mode", "act")
            check(inputError("app.navigate", rejected) != null && navigationModeError(rejected) != null)
            rejected.getJSONObject("intent").put("mode", "unknown")
            check(inputError("app.navigate", rejected) != null)
            val search = JSONObject(input.toString())
            search.getJSONObject("intent").put("mode", "search").put("target", "Amber Horizon")
            check(inputError("app.navigate", search) == null && destinationTarget(search) == "Amber Horizon")
            val destination = JSONObject().put("text", "my collection").put("selected", true)
                .put("resource_id", "section_title").put("package", "synthetic.app").put("visible", true)
            val evidence = JSONObject().put("screen_text", "my collection")
                .put("source_text_sha256", Contracts.digest("my collection")).put("capture_method", "selected_destination")
                .put("observed_target", "my collection").put("destination", destination)
                .put("intent_sha256", Contracts.digest(Contracts.canonical(intent)))
            val result = CapabilityResult(output = JSONObject().put("answer", "Opened my collection.")
                .put("package", "synthetic.app").put("target", "my collection"), evidence = evidence)
            check(verificationError("app.navigate", input, result) == null)
            val wrongTarget = result.copy(output = JSONObject(result.output.toString()).put("target", "stale legacy target"))
            check(verificationError("app.navigate", input, wrongTarget) != null)
            rejected.getJSONObject("intent").put("mode", "act")
            check(verificationError("app.navigate", rejected, result) != null)
            return "ok"
        }
        private fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        private fun nodes(screen: Screen): List<JSONObject> {
            val result = mutableListOf<JSONObject>()
            fun visit(node: JSONObject) {
                if (!node.optBoolean("visible") || node.optString("package") != screen.pkg) return
                result += node
                node.optJSONArray("children")?.let { children -> for (index in 0 until children.length()) children.optJSONObject(index)?.let(::visit) }
            }
            screen.state?.optJSONArray("windows")?.let { windows ->
                for (index in 0 until windows.length()) windows.optJSONObject(index)?.takeIf { it.optInt("type") == 1 }?.optJSONObject("root")?.let(::visit)
            }
            return result
        }
    }
}
