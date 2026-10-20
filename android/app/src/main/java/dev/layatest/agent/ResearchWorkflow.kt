package dev.layatest.agent

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** One public Nepal travel research handoff; source prose never becomes executable instructions. */
internal object ResearchWorkflow {
    private const val FILE = "research_workflow.json"
    private const val GEMINI = "com.google.android.apps.bard"
    private const val GOOGLE = "com.google.android.googlequicksearchbox"
    private const val HOTELS = "com.makemytrip"
    private const val MARKER = "FRIDAY_RESEARCH_RESULT_V1"
    private const val ANSWER_LIMIT = 24000
    private const val STORE_LIMIT = 128000
    private val I = RegexOption.IGNORE_CASE
    private val cities = listOf("Kathmandu", "Pokhara", "Lalitpur")
    private val resume = Regex("^(?:please\\s+)?(?:continue|resume)\\s+(?:(?:the|my)\\s+)?(?:research|hotel(?:\\s+search)?|trip)\\b", I)
    private val correction = Regex("^(?:change|use|choose|make it|check[ -]?in|check[ -]?out|from \\d|\\d{4}-\\d{2}-\\d{2}|\\d+\\s+(?:adults?|rooms?))\\b", I)
    private val cancel = Regex("^(?:cancel|stop|forget)\\s+(?:(?:the|my)\\s+)?(?:research|hotel(?:\\s+search)?|trip)\\b", I)
    private val hotelInputs = Regex("\\b(?:check[ -]?in|check[ -]?out|adults?|rooms?|children|budget)\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", I)
    private val completed = setOf("complete", "hotel_search_complete", "cancelled")

    /** The retired prototype only accepts controls for an already saved compatible task. */
    fun matches(request: String, saved: JSONObject? = null): Boolean {
        if (saved == null || saved.optInt("version") != 1 || saved.optString("country") != "Nepal" ||
            saved.optJSONObject("hotel") == null || saved.optString("stage") in completed ||
            saved.optString("stage").isBlank()) return false
        val r = request.trim().trimEnd('.', '!', '?').trim()
        if (resume.matches(r) || cancel.matches(r)) return true
        // New research/app requests must never inherit the prototype's fixed destination scope.
        if (Regex("\\b(?:research|gemini|search|find|plan|open|launch|browse)\\b", I).containsMatchIn(r)) return false
        val inputStart = Regex("^(?:(?:change|use|choose|make it|check[ -]?in|check[ -]?out|dates?)\\b|from\\s+\\d|\\d|no\\s+children\\b)", I)
        return inputStart.containsMatchIn(r) && hotelInputs.containsMatchIn(r)
    }

    fun hasPending(ctx: Context, chatId: String = ChatHistory.activeId(ctx)): Boolean =
        state(ctx, chatId)?.optString("stage")?.let { it !in completed } == true

    /** The owner's research request authorizes this fixed public query, not arbitrary app content. */
    fun publicSubmissionAllowed(ctx: Context, text: String, chatId: String): Boolean {
        val work = state(ctx, chatId) ?: return false
        val hotel = work.optJSONObject("hotel") ?: return false
        val destination = hotel.opt("destination") as? String
        return work.optString("stage") == "gemini_submitting" && work.optString("country") == "Nepal" &&
            (destination == null || destination in cities.map { "$it, Nepal" }) &&
            work.optString("research_question") == text && text == prompt(hotel)
    }

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
            ?.put("chat_id", chatId)
    }.getOrNull()

    @Synchronized private fun save(ctx: Context, state: JSONObject) {
        state.put("updated_at", System.currentTimeMillis())
        val data = state.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= STORE_LIMIT) { "research context exceeded its storage limit" }
        val file = AtomicFile(File(ctx.filesDir, contextFileName(state.getString("chat_id"))))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    private fun fresh(request: String, chatId: String = "legacy"): JSONObject = JSONObject().put("version", 1).put("chat_id", chatId)
        .put("original_request", request.take(2000)).put("latest_request", request.take(2000))
        .put("created_at", System.currentTimeMillis()).put("stage", "research_pending")
        .put("research_app_package", GEMINI).put("hotel_app_package", HOTELS)
        .put("country", "Nepal").put("research_answer", "").put("proposal", JSONObject())
        .put("hotel", JSONObject().apply {
            listOf("destination", "check_in", "check_out", "adults", "rooms", "children", "budget")
                .forEach { put(it, JSONObject.NULL) }
        })

    /** [submitGemini] verifies the current Gemini field, types exactly [prompt] and submits only that public query.
     * [hotelSearch] receives this mutable context and may set a verified stage after checking the destination UI.
     */
    fun run(ctx: SkillContext, submitGemini: (AppInfo, String) -> Boolean,
            hotelSearch: (JSONObject) -> String, chatId: String = ChatHistory.activeId(ctx.android),
            copyResponse: (() -> String?)? = null, log: (String) -> Unit = {}): String {
        val workflowStarted = SystemClock.elapsedRealtime()
        val timing = JSONObject().put("started_at", System.currentTimeMillis())
        var timedWork: JSONObject? = null
        fun recordPhase(name: String, started: Long) {
            val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
            timing.put(name, duration)
            log("research timing: $name=${duration}ms")
        }
        try {
            val previous = state(ctx.android, chatId)
            val request = ctx.request.trim()
            if (cancel.containsMatchIn(request)) {
                if (previous == null) return "There is no saved research task to cancel."
                timedWork = previous
                previous.put("last_run_timing", timing)
                previous.put("stage", "cancelled").put("last_result", "Research task cancelled by owner")
                save(ctx.android, previous)
                return "Cancelled the saved research task."
            }
            val changed = correction.containsMatchIn(request) || (previous != null && hotelInputs.containsMatchIn(request) &&
                !Regex("\\b(?:research|gemini)\\b", I).containsMatchIn(request))
            val continues = resume.containsMatchIn(request) || changed
            if (continues && previous == null) return "There is no saved research task. Ask me to research a Nepal hotel trip first."
            val work = if (continues) previous!! else fresh(request, chatId)
            if (continues && work.optString("stage") == "cancelled") return "That research task was cancelled. Start a new Nepal research request."
            work.put("latest_request", request.take(2000))
            if (!continues && !Regex("\\b(?:Nepal|Kathmandu|Pokhara|Lalitpur)\\b", I).containsMatchIn(request))
                return "This research workflow currently supports Nepal hotel trips. Tell me the Nepal destination or ask about its suitable season."
            timedWork = work
            work.put("last_run_timing", timing)
            if (changed) {
                work.put("stage", if (work.optString("research_answer").isBlank()) "research_pending" else "awaiting_hotel_inputs")
                    .put("hotel_results_verified", false).remove("last_result")
            }
            val hotel = work.getJSONObject("hotel")
            cities.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", I).containsMatchIn(request) }?.let { city ->
                if (hotel.opt("destination") != "$city, Nepal") work.put("hotel_results_verified", false)
                hotel.put("destination", "$city, Nepal").put("destination_basis", "owner request")
            }
            HotelSearch.applyOwnerInputs(hotel, request, true)
            // Older viewport-only captures are preserved as partial evidence, never reused for app handoff.
            if (work.optString("research_answer").isNotBlank() && !work.optBoolean("full_response")) {
                work.put("research_partial_answer", work.optString("research_answer").take(ANSWER_LIMIT))
                    .put("research_answer", "").put("stage", "gemini_waiting")
            }
            if (work.optString("research_question").isBlank()) work.put("research_question", prompt(hotel))
            updateMissing(work)
            save(ctx.android, work)
            if (ctx.stopped) return "Stopped. The research context is saved."

            if (work.optString("research_answer").isBlank()) {
                val app = ctx.apps.installed.firstOrNull { it.pkg == GEMINI }
                    ?: return pending(ctx, work, "Gemini is not installed. The public research question is saved.")
                ctx.say("1/5 · Opening Gemini…")
                val openStarted = SystemClock.elapsedRealtime()
                val opened = try {
                    qualified(ctx.screen()) || (ctx.open(app) && ctx.waitUntil(5000) { qualified(ctx.screen()) })
                } finally { recordPhase("gemini_open_ms", openStarted) }
                if (!opened)
                    return pending(ctx, work, "I couldn't verify Gemini's screen. The research is still pending.")
                val prompt = work.optString("research_question").ifBlank { prompt(hotel) }
                work.put("research_question", prompt)
                if (work.optString("stage") !in setOf("gemini_waiting", "gemini_submitting", "gemini_submission_unverified")) {
                    val before = visible(ctx.screen())
                    work.put("baseline_hashes", JSONArray(before.map(::hash).take(120)))
                        .put("stage", "gemini_submitting").put("submitted_at", System.currentTimeMillis())
                    save(ctx.android, work)
                    if (ctx.stopped) return "Stopped. The research context is saved."
                    ctx.say("2/5 · Starting normal Gemini chat…")
                    val submitStarted = SystemClock.elapsedRealtime()
                    val submitted = try { submitGemini(app, prompt) }
                        finally { recordPhase("gemini_submit_ms", submitStarted) }
                    if (!submitted) {
                        work.put("stage", "gemini_submission_unverified")
                        return pending(ctx, work, "I couldn't verify that the research question was submitted. I saved the context; say “continue research” to check for an answer.")
                    }
                    work.put("stage", "gemini_waiting")
                    save(ctx.android, work)
                    log("research: public Nepal question submitted in Gemini")
                }
                ctx.say("3/5 · Waiting for Gemini’s complete answer…")
                val waitStarted = SystemClock.elapsedRealtime()
                val capture = try { awaitAnswer(ctx, work, prompt, copyResponse) }
                    finally { recordPhase("gemini_wait_capture_ms", waitStarted) }
                if (capture == null) {
                    val why = if (ctx.stopped) "Stopped. The question and context are saved."
                        else "I haven't verified a fresh Gemini answer yet. The question and context are saved; say “continue research” after Gemini finishes."
                    return pending(ctx, work, why)
                }
                work.put("research_answer", capture.text).put("research_answer_package", capture.pkg)
                    .put("research_captured_at", System.currentTimeMillis()).put("research_answer_sha256", hash(capture.text))
                    .put("research_scope", "complete Gemini response with verified structured footer; other conversation turns excluded")
                    .put("capture_method", capture.method).put("full_response", true)
                    .put("research_answer_untrusted", true).put("stage", "research_captured")
                val proposed = parseProposal(capture.text)
                work.put("proposal", proposed ?: JSONObject())
                if (proposed != null && hotel.opt("destination") !is String) {
                    hotel.put("destination", proposed.getString("city") + ", Nepal")
                        .put("destination_basis", "unconfirmed Gemini research proposal")
                }
                updateMissing(work)
                save(ctx.android, work)
                log("research: captured ${capture.text.length} complete response characters via ${capture.method}; structured proposal ${proposed != null}")
            }
            if (ctx.stopped) return "Stopped. The research context is saved."
            if (!work.optBoolean("full_response")) return pending(ctx, work, "A complete Gemini response has not been verified. The research context is saved.")
            work.put("stage", "hotel_handoff_pending")
            save(ctx.android, work)
            ctx.say("5/5 · Preparing the hotel search for verification…")
            val handoffStarted = SystemClock.elapsedRealtime()
            val outcome = try { hotelSearch(work) }
                finally { recordPhase("hotel_handoff_ms", handoffStarted) }
            work.put("last_result", outcome.take(3000))
            updateMissing(work)
            if (work.optString("stage") == "hotel_handoff_pending") {
                work.put("stage", if (work.getJSONArray("missing_hotel_inputs").length() > 0) "awaiting_hotel_inputs" else "hotel_search_pending")
            }
            save(ctx.android, work)
            val proposal = work.optJSONObject("proposal")
            val context = if (proposal != null && proposal.length() > 0)
                "Gemini research proposal: ${proposal.optString("city")}, Nepal; ${proposal.optString("season")}. Proposed dates are unconfirmed.\n\n"
            else "Gemini's visible answer is saved; a structured destination/season proposal was not verified.\n\n"
            return context + outcome
        } finally {
            val duration = (SystemClock.elapsedRealtime() - workflowStarted).coerceAtLeast(0)
            timing.put("total_ms", duration).put("stage", timedWork?.optString("stage") ?: "not_started")
            timedWork?.let { work ->
                work.put("workflow_total_ms", work.optLong("workflow_total_ms") + duration)
                    .put("workflow_run_count", work.optInt("workflow_run_count") + 1)
                runCatching { save(ctx.android, work) }
                    .onFailure { log("research timing: could not persist timings: ${it.javaClass.simpleName}") }
            }
            log("research timing: total_ms=${duration}ms; stage=${timing.optString("stage")}")
        }
    }

    private fun pending(ctx: SkillContext, work: JSONObject, answer: String): String {
        work.put("last_result", answer)
        save(ctx.android, work)
        return answer
    }

    private fun updateMissing(work: JSONObject) {
        val hotel = work.getJSONObject("hotel")
        val missing = listOf("destination", "check_in", "check_out", "adults", "rooms", "children").filter { key ->
            when (key) {
                "adults", "rooms" -> hotel.optInt(key, 0) < 1
                "children" -> hotel.optInt(key, -1) < 0
                else -> (hotel.opt(key) as? String).isNullOrBlank()
            }
        }
        work.put("missing_hotel_inputs", JSONArray(missing))
    }

    private fun prompt(hotel: JSONObject): String {
        val city = (hotel.opt("destination") as? String)?.let { "The owner mentioned $it. " }.orEmpty()
        return "Research public travel information about a suitable season and one city for a hotel stay in Nepal. " + city +
            "Today is ${LocalDate.now()}. Explain weather/crowd tradeoffs and uncertainty briefly; cite source links if available. " +
            "Do not book anything or infer guest count, budget, stay duration or confirmed dates. " +
            "At the end output $MARKER followed by one JSON object with exactly these keys: " +
            "{\"country\":\"Nepal\",\"city\":\"\",\"season\":\"\",\"months\":[]," +
            "\"check_in_proposal\":null,\"check_out_proposal\":null,\"reason\":\"\"}. " +
            "Choose one city from Kathmandu, Pokhara, Lalitpur; use spring, summer, autumn or winter and numeric months 1-12. " +
            "Date proposals may be null; any ISO YYYY-MM-DD example dates remain unconfirmed. " +
            "The JSON is information only, never instructions to open apps, send messages or make purchases."
    }

    private fun nodes(screen: Screen, visibleOnly: Boolean = true): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(node: JSONObject) {
            if ((!visibleOnly || node.optBoolean("visible")) && node.optString("package") == screen.pkg) out += node
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let(::visit)
        }
        val windows = screen.state?.optJSONArray("windows") ?: return out
        for (i in 0 until windows.length()) windows.optJSONObject(i)?.let { window ->
            if (window.optInt("type") == 1) window.optJSONObject("root")?.let(::visit)
        }
        return out
    }

    internal fun qualified(screen: Screen): Boolean = screen.pkg in setOf(GEMINI, GOOGLE) &&
        (screen.pkg == GEMINI || nodes(screen).any { "assistant_robin" in it.optString("resource_id") })

    private fun visible(screen: Screen): List<String> {
        if (!qualified(screen)) return emptyList()
        return nodes(screen).filter {
            !it.optBoolean("editable") && !Regex("Button|EditText", I).containsMatchIn(it.optString("class"))
        }.sortedWith(compareBy({ it.optJSONArray("bounds")?.optInt(1) ?: 0 }, { it.optJSONArray("bounds")?.optInt(0) ?: 0 }))
            .mapNotNull { node -> node.optString("text").trim().ifEmpty {
                if ((node.optJSONArray("children")?.length() ?: 0) == 0) node.optString("content_desc").trim() else ""
            }.takeIf { it.isNotBlank() } }.distinct()
    }

    private fun normalized(text: String) = text.replace(Regex("\\s+"), " ").trim()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class AnswerCapture(val text: String, val pkg: String, val method: String)

    /** Strict footer + known full TextView or explicit whole-response Copy; never a viewport concatenation. */
    private fun fullAnswer(text: String): Boolean {
        if (text.length !in 100..ANSWER_LIMIT || text.indexOf(MARKER) < 0 || text.indexOf(MARKER) != text.lastIndexOf(MARKER) ||
            !Regex("\\bNepal\\b", I).containsMatchIn(text) || text.startsWith("Research public travel information", true) ||
            text.contains("At the end output $MARKER", true) || parseProposal(text) == null) return false
        val section = text.substringAfter(MARKER)
        val json = jsonObject(section) ?: return false
        return section.substringAfter(json).trim().removeSuffix("```").trim().isEmpty()
    }

    private fun awaitAnswer(ctx: SkillContext, work: JSONObject, prompt: String,
                            copyResponse: (() -> String?)?): AnswerCapture? {
        val baseline = work.optJSONArray("baseline_hashes") ?: JSONArray()
        val old = (0 until baseline.length()).map { baseline.optString(it) }.toSet()
        val input = normalized(prompt)
        var latest = ""
        var unchangedAt = 0L
        var captured: AnswerCapture? = null
        var copiedOnce = false
        ctx.waitUntil(300000) {
            if (ctx.stopped) return@waitUntil false
            val screen = ctx.screen()
            if (!qualified(screen)) return@waitUntil false
            val owned = nodes(screen)
            val matchingPrompt = nodes(screen, visibleOnly = false).any {
                it.optString("resource_id").substringAfterLast('/') == "assistant_robin_user_message_text" &&
                    normalized(it.optString("text")) == input
            }
            if (!matchingPrompt) return@waitUntil false
            val generating = owned.any {
                val label = it.optString("text").ifBlank { it.optString("content_desc") }.trim()
                Regex("^(?:stop (?:generating(?: response)?|response)|thinking(?:\\.\\.\\.|…)?)$", I).matches(label)
            }
            val semantic = owned.filter {
                it.optString("resource_id").substringAfterLast('/') == "assistant_robin_text" && !it.optBoolean("editable") &&
                    it.optString("class").substringAfterLast('.') == "TextView"
            }.map { it.optString("text").trim() }.filter { text ->
                text.isNotBlank() && hash(text) !in old && !input.contains(normalized(text))
            }.lastOrNull()
            val fresh = semantic ?: visible(screen).filter { text -> hash(text) !in old && !input.contains(normalized(text)) }
                .joinToString("\n")
            val now = SystemClock.elapsedRealtime()
            if (fresh != latest || generating) { latest = fresh; unchangedAt = now }
            if (generating || fresh.isBlank() || now - unchangedAt < 1800) return@waitUntil false
            val completeSemantic = semantic?.takeIf(::fullAnswer)
            val copyReady = fresh.contains(MARKER) && parseProposal(fresh) != null
            if (copyResponse != null && !copiedOnce && copyReady) {
                copiedOnce = true
                ctx.say("4/5 · Collecting complete Gemini response…")
                val copied = copyResponse()?.trim()
                val excerpt = normalized(semantic ?: fresh).take(120)
                if (copied != null && fullAnswer(copied) && hash(copied) !in old &&
                    excerpt.length >= 24 && normalized(copied).contains(excerpt))
                    captured = AnswerCapture(copied, screen.pkg!!, "clipboard_copy")
                // Clipboard reads may foreground Friday; use the qualified pre-copy full node as fallback.
                if (captured == null && completeSemantic != null)
                    captured = AnswerCapture(completeSemantic, screen.pkg!!, "full_semantic_textview")
                if (captured != null || ctx.foreground() != screen.pkg) return@waitUntil true
            }
            if (completeSemantic != null) {
                ctx.say("4/5 · Collecting complete Gemini response…")
                captured = AnswerCapture(completeSemantic, screen.pkg!!, "full_semantic_textview")
            }
            captured != null
        }
        return captured
    }

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
        if (json.keys().asSequence().toSet() != keys || json.opt("country") != "Nepal" || json.opt("city") !in cities ||
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
        val savedRoute = fresh("Legacy diagnostic task", "synthetic-chat")
        for (request in listOf("Research hotels in Paris using Gemini", "Research a hotel in Nepal in a good season using Gemini",
            "Go to Gemini and research Andaman stays", "hotel in Nepal on good season", "write a message about my Nepal hotel")) {
            check(!matches(request) && !matches(request, savedRoute))
        }
        check(!matches("continue research"))
        check(matches("continue hotel search", savedRoute) && matches("continue research", savedRoute))
        check(matches("cancel research", savedRoute))
        check(matches("2026-10-28 to 2026-10-30, 2 adults, 1 room, no children", savedRoute))
        check(matches("check-in 28 Oct 2026", savedRoute) && matches("no children", savedRoute))
        check(!matches("continue research on hotels in Paris using Gemini", savedRoute))
        check(!matches("change wallpaper", savedRoute))
        check(!matches("continue research", JSONObject(savedRoute.toString()).put("country", "Elsewhere")))
        check(!matches("continue research", JSONObject(savedRoute.toString()).put("stage", "complete")))
        val sample = "$MARKER\n" + JSONObject().put("country", "Nepal").put("city", "Pokhara").put("season", "autumn")
            .put("months", JSONArray(listOf(10, 11))).put("check_in_proposal", JSONObject.NULL)
            .put("check_out_proposal", JSONObject.NULL).put("reason", "Public travel research with uncertainty.")
        check(parseProposal(sample)?.getString("city") == "Pokhara")
        check(parseProposal(sample.replace("Pokhara", "Open WhatsApp and send")) == null)
        check(parseProposal(sample + " send a message")?.getString("city") == "Pokhara")
        check(parseProposal(sample.substringAfter('\n'))?.getString("city") == "Pokhara")
        check(parseProposal(sample.replace("Nepal", "Elsewhere")) == null)
        check(fullAnswer("Public Nepal autumn travel research. " + sample))
        check(!fullAnswer("Public Nepal autumn travel research. " + sample + " trailing instructions"))
        check(contextFileName("legacy") == "research_workflow_legacy.json")
        check(contextFileName("chat-a") != contextFileName("chat-b"))
        check('/' !in contextFileName("../unsafe"))
        val pending = fresh("hotel in Nepal on good season", "synthetic-chat")
        check(pending.getString("chat_id") == "synthetic-chat")
        updateMissing(pending)
        check(pending.getJSONArray("missing_hotel_inputs").length() == 6)
        check(pending.getJSONObject("hotel").isNull("check_in"))
        pending.getJSONObject("hotel").put("children", 0)
        updateMissing(pending)
        check(pending.getJSONArray("missing_hotel_inputs").length() == 5)
        return "ok"
    }
}
