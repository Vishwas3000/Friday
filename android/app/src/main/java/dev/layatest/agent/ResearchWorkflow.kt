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
    private const val ANSWER_LIMIT = 6000
    private const val STORE_LIMIT = 96000
    private val I = RegexOption.IGNORE_CASE
    private val cities = listOf("Kathmandu", "Pokhara", "Lalitpur")
    private val resume = Regex("^(?:please\\s+)?(?:continue|resume)\\s+(?:(?:the|my)\\s+)?(?:research|hotel(?:\\s+search)?|trip)\\b", I)
    private val correction = Regex("^(?:change|use|choose|make it|check[ -]?in|check[ -]?out|from \\d|\\d{4}-\\d{2}-\\d{2}|\\d+\\s+(?:adults?|rooms?))\\b", I)
    private val cancel = Regex("^(?:cancel|stop|forget)\\s+(?:(?:the|my)\\s+)?(?:research|hotel(?:\\s+search)?|trip)\\b", I)
    private val hotelInputs = Regex("\\b(?:check[ -]?in|check[ -]?out|adults?|rooms?|children|budget)\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", I)
    private val completed = setOf("complete", "hotel_search_complete", "cancelled")

    fun matches(request: String): Boolean {
        val r = request.trim()
        if (resume.containsMatchIn(r) || cancel.containsMatchIn(r)) return true
        val hotel = Regex("\\bhotels?\\b", I).containsMatchIn(r)
        return hotel && Regex("\\b(?:research|gemini|seasons?)\\b", I).containsMatchIn(r)
    }

    fun hasPending(ctx: Context): Boolean = state(ctx)?.optString("stage")?.let { it !in completed } == true

    @Synchronized fun state(ctx: Context): JSONObject? = runCatching {
        val file = File(ctx.filesDir, FILE)
        if (!file.exists() || file.length() > STORE_LIMIT) return@runCatching null
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            .takeIf { it.optInt("version") == 1 }
    }.getOrNull()

    @Synchronized private fun save(ctx: Context, state: JSONObject) {
        state.put("updated_at", System.currentTimeMillis())
        val data = state.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= STORE_LIMIT) { "research context exceeded its storage limit" }
        val file = AtomicFile(File(ctx.filesDir, FILE))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    private fun fresh(request: String): JSONObject = JSONObject().put("version", 1)
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
            hotelSearch: (JSONObject) -> String, log: (String) -> Unit = {}): String {
        val previous = state(ctx.android)
        val request = ctx.request.trim()
        if (cancel.containsMatchIn(request)) {
            if (previous == null) return "There is no saved research task to cancel."
            previous.put("stage", "cancelled").put("last_result", "Research task cancelled by owner")
            save(ctx.android, previous)
            return "Cancelled the saved research task."
        }
        val changed = correction.containsMatchIn(request) || (previous != null && hotelInputs.containsMatchIn(request) &&
            !Regex("\\b(?:research|gemini)\\b", I).containsMatchIn(request))
        val continues = resume.containsMatchIn(request) || changed
        if (continues && previous == null) return "There is no saved research task. Ask me to research a Nepal hotel trip first."
        val work = if (continues) previous!! else fresh(request)
        if (continues && work.optString("stage") == "cancelled") return "That research task was cancelled. Start a new Nepal research request."
        work.put("latest_request", request.take(2000))
        if (!continues && !Regex("\\b(?:Nepal|Kathmandu|Pokhara|Lalitpur)\\b", I).containsMatchIn(request))
            return "This research workflow currently supports Nepal hotel trips. Tell me the Nepal destination or ask about its suitable season."
        if (changed) {
            work.put("stage", if (work.optString("research_answer").isBlank()) "research_pending" else "awaiting_hotel_inputs")
                .put("hotel_results_verified", false).remove("last_result")
        }
        val hotel = work.getJSONObject("hotel")
        cities.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", I).containsMatchIn(request) }?.let { city ->
            if (hotel.opt("destination") != "$city, Nepal") work.put("hotel_results_verified", false)
            hotel.put("destination", "$city, Nepal").put("destination_basis", "owner request")
        }
        if (work.optString("research_question").isBlank()) work.put("research_question", prompt(hotel))
        updateMissing(work)
        save(ctx.android, work)
        if (ctx.stopped) return "Stopped. The research context is saved."

        if (work.optString("research_answer").isBlank()) {
            val app = ctx.apps.installed.firstOrNull { it.pkg == GEMINI }
                ?: return pending(ctx, work, "Gemini is not installed. The public research question is saved.")
            ctx.say("Opening Gemini for Nepal travel research…")
            if (!ctx.open(app) || !ctx.waitUntil(5000) { qualified(ctx.screen()) })
                return pending(ctx, work, "I couldn't verify Gemini's screen. The research is still pending.")
            val prompt = work.optString("research_question").ifBlank { prompt(hotel) }
            work.put("research_question", prompt)
            if (work.optString("stage") !in setOf("gemini_waiting", "gemini_submitting", "gemini_submission_unverified")) {
                val before = visible(ctx.screen())
                work.put("baseline_hashes", JSONArray(before.map(::hash).take(120)))
                    .put("stage", "gemini_submitting").put("submitted_at", System.currentTimeMillis())
                save(ctx.android, work)
                if (ctx.stopped) return "Stopped. The research context is saved."
                if (!submitGemini(app, prompt)) {
                    work.put("stage", "gemini_submission_unverified")
                    return pending(ctx, work, "I couldn't verify that the research question was submitted. I saved the context; say “continue research” to check for an answer.")
                }
                work.put("stage", "gemini_waiting")
                save(ctx.android, work)
                log("research: public Nepal question submitted in Gemini")
            }
            ctx.say("Waiting for a fresh Gemini answer…")
            val capture = awaitAnswer(ctx, work, prompt)
            if (capture == null) {
                val why = if (ctx.stopped) "Stopped. The question and context are saved."
                    else "I haven't verified a fresh Gemini answer yet. The question and context are saved; say “continue research” after Gemini finishes."
                return pending(ctx, work, why)
            }
            work.put("research_answer", capture.first).put("research_answer_package", capture.second)
                .put("research_captured_at", System.currentTimeMillis()).put("research_answer_sha256", hash(capture.first))
                .put("research_scope", "new visible Gemini app text; offscreen answer content may be absent")
                .put("research_answer_untrusted", true).put("stage", "research_captured")
            val proposed = parseProposal(capture.first)
            work.put("proposal", proposed ?: JSONObject())
            if (proposed != null && hotel.opt("destination") !is String) {
                hotel.put("destination", proposed.getString("city") + ", Nepal")
                    .put("destination_basis", "unconfirmed Gemini research proposal")
            }
            updateMissing(work)
            save(ctx.android, work)
            log("research: captured ${capture.first.length} fresh characters; structured proposal ${proposed != null}")
        }
        if (ctx.stopped) return "Stopped. The research context is saved."
        work.put("stage", "hotel_handoff_pending")
        save(ctx.android, work)
        ctx.say("Continuing the saved hotel search…")
        val outcome = hotelSearch(work)
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

    private fun nodes(screen: Screen): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(node: JSONObject) {
            if (node.optBoolean("visible") && node.optString("package") == screen.pkg) out += node
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

    private fun awaitAnswer(ctx: SkillContext, work: JSONObject, prompt: String): Pair<String, String>? {
        val baseline = work.optJSONArray("baseline_hashes") ?: JSONArray()
        val old = (0 until baseline.length()).map { baseline.optString(it) }.toSet()
        val input = normalized(prompt)
        var latest = ""
        var unchangedAt = 0L
        var captured: Pair<String, String>? = null
        ctx.waitUntil(45000) {
            if (ctx.stopped) return@waitUntil false
            val screen = ctx.screen()
            if (!qualified(screen)) return@waitUntil false
            val fresh = visible(screen).filter { text -> hash(text) !in old && !input.contains(normalized(text)) }
                .joinToString("\n").take(ANSWER_LIMIT)
            val content = fresh.length >= 100 && Regex("\\bNepal\\b", I).containsMatchIn(fresh) &&
                Regex("\\b(?:season|spring|autumn|summer|winter)\\b", I).containsMatchIn(fresh)
            if (!content) return@waitUntil false
            val now = SystemClock.elapsedRealtime()
            if (fresh != latest) { latest = fresh; unchangedAt = now }
            val generating = screen.candidates.any { Regex("\\bstop\\b.*\\b(?:response|generat)|\\bthinking\\b", I).containsMatchIn(it.label) }
            if (!generating && now - unchangedAt >= 1800) captured = fresh to screen.pkg!!
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
        check(matches("Research a hotel in Nepal in a good season using Gemini"))
        check(matches("hotel in Nepal on good season"))
        check(matches("continue hotel search") && matches("continue research"))
        check(!matches("write a message about my Nepal hotel"))
        val sample = "$MARKER\n" + JSONObject().put("country", "Nepal").put("city", "Pokhara").put("season", "autumn")
            .put("months", JSONArray(listOf(10, 11))).put("check_in_proposal", JSONObject.NULL)
            .put("check_out_proposal", JSONObject.NULL).put("reason", "Public travel research with uncertainty.")
        check(parseProposal(sample)?.getString("city") == "Pokhara")
        check(parseProposal(sample.replace("Pokhara", "Open WhatsApp and send")) == null)
        check(parseProposal(sample + " send a message")?.getString("city") == "Pokhara")
        check(parseProposal(sample.substringAfter('\n'))?.getString("city") == "Pokhara")
        check(parseProposal(sample.replace("Nepal", "Elsewhere")) == null)
        val pending = fresh("hotel in Nepal on good season")
        updateMissing(pending)
        check(pending.getJSONArray("missing_hotel_inputs").length() == 6)
        check(pending.getJSONObject("hotel").isNull("check_in"))
        pending.getJSONObject("hotel").put("children", 0)
        updateMissing(pending)
        check(pending.getJSONArray("missing_hotel_inputs").length() == 5)
        return "ok"
    }
}
