package dev.layatest.agent

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** Read-only compatibility for the retired prototype. New work belongs to the Room coordinator. */
internal object ResearchWorkflow {
    private const val FILE = "research_workflow.json"
    private const val STORE_LIMIT = 128000
    private const val MARKER = "FRIDAY_RESEARCH_RESULT_V1"

    fun migrationMessage(): String = "This saved research/hotel task uses the retired workflow. Its data is preserved for review. Start a new coordinator task with the stay details; no question, search or booking was repeated."

    @Suppress("UNUSED_PARAMETER")
    fun matches(request: String, saved: JSONObject? = null): Boolean = false
    @Suppress("UNUSED_PARAMETER")
    fun hasPending(ctx: Context, chatId: String = ChatHistory.activeId(ctx)): Boolean = false
    @Suppress("UNUSED_PARAMETER")
    fun publicSubmissionAllowed(ctx: Context, text: String, chatId: String): Boolean = false

    private fun contextFileName(chatId: String): String {
        val safe = if (Regex("[A-Za-z0-9_-]{1,128}").matches(chatId)) chatId else hash(chatId)
        return "research_workflow_$safe.json"
    }

    @Synchronized fun state(ctx: Context, chatId: String = ChatHistory.activeId(ctx)): JSONObject? = runCatching {
        val scoped = File(ctx.filesDir, contextFileName(chatId))
        val file = if (scoped.exists()) scoped else if (chatId == "legacy") File(ctx.filesDir, FILE) else scoped
        if (!file.exists() || file.length() > STORE_LIMIT) return@runCatching null
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            .takeIf { it.optInt("version") == 1 && it.optString("chat_id", "legacy") == chatId }
            ?.put("chat_id", chatId)?.put("migration_status", "legacy_read_only")?.put("migration_message", migrationMessage())
    }.getOrNull()

    /** Kept for binary/source compatibility while old callers migrate; never invokes either callback. */
    @Suppress("UNUSED_PARAMETER")
    fun run(ctx: SkillContext, submitGemini: (AppInfo, String) -> Boolean, hotelSearch: (JSONObject) -> String,
            chatId: String = ChatHistory.activeId(ctx.android), copyResponse: (() -> String?)? = null,
            log: (String) -> Unit = {}): String = migrationMessage()

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun parseProposal(answer: String): JSONObject? = runCatching {
        val marker = answer.lastIndexOf(MARKER)
        // Gemini can expose the marker as a separate node identical to the submitted prompt's node.
        val section = if (marker >= 0) answer.substring(marker + MARKER.length) else answer
        val tail = jsonObject(section) ?: return@runCatching null
        if (tail.length > 1800) return@runCatching null
        val parser = JSONTokener(tail)
        val json = parser.nextValue() as? JSONObject ?: return@runCatching null
        if (parser.nextClean() != 0.toChar()) return@runCatching null
        val keys = setOf("country", "city", "season", "months", "check_in_proposal", "check_out_proposal", "reason")
        if (json.keys().asSequence().toSet() != keys || (json.opt("country") as? String).isNullOrBlank() || (json.opt("city") as? String).isNullOrBlank() ||
            json.opt("season") !in setOf("spring", "summer", "autumn", "winter")) return@runCatching null
        val months = json.optJSONArray("months") ?: return@runCatching null
        if (months.length() !in 1..12 || (0 until months.length()).any { months.opt(it) !is Int || months.getInt(it) !in 1..12 }) return@runCatching null
        val reason = json.opt("reason") as? String ?: return@runCatching null
        if (reason.isBlank() || reason.length > 500) return@runCatching null
        val first = date(json.opt("check_in_proposal"))
        val last = date(json.opt("check_out_proposal"))
        if ((!json.isNull("check_in_proposal") && first == null) || (!json.isNull("check_out_proposal") && last == null) ||
            (first == null) != (last == null) || (first != null && !last!!.isAfter(first))) return@runCatching null
        json.put("source", "untrusted visible Gemini answer").put("confirmation_required", true)
    }.getOrNull()

    private fun jsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '"') { quoted = !quoted; continue }
            if (!quoted) {
                if (c == '{') depth++
                if (c == '}' && --depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun date(value: Any?): LocalDate? = (value as? String)?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun selfCheck(): String {
        check(!matches("continue research") && !matches("find hotels in any destination"))
        check(!matches("resume hotel search", JSONObject().put("version", 1).put("stage", "hotel_search_pending")))
        check(contextFileName("chat-a") != contextFileName("chat-b") && '/' !in contextFileName("../unsafe"))
        check(migrationMessage().contains("preserved") && migrationMessage().contains("no question"))
        return "ok"
    }
}
