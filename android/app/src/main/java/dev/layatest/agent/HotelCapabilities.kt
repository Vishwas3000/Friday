package dev.layatest.agent

import dev.layatest.agent.coordinator.CapabilityResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/** Stay choices are owner data. Research prose is retained as provenance, never promoted to a choice. */
internal object HotelCapabilities {
    const val PACKAGE = "com.makemytrip"
    private val keys = listOf("destination", "check_in", "check_out", "adults", "rooms", "children", "child_ages")
    private val i = RegexOption.IGNORE_CASE
    private val iso = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val namedDay = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTH)\\.?\\s*,?\\s+(\\d{4})\\b", i)
    private val namedMonth = Regex("\\b($MONTH)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s*,?\\s+(\\d{4})\\b", i)

    data class Stay(val packageName: String, val destination: String, val checkIn: LocalDate, val checkOut: LocalDate,
                    val adults: Int, val rooms: Int, val children: Int, val childAges: List<Int>) {
        fun json() = JSONObject().put("package", packageName).put("destination", destination)
            .put("check_in", checkIn.toString()).put("check_out", checkOut.toString()).put("adults", adults)
            .put("rooms", rooms).put("children", children).put("child_ages", JSONArray(childAges))
    }
    data class Prepared(val stay: Stay? = null, val missing: List<String> = emptyList(), val errors: List<String> = emptyList(),
                        val unsupported: List<String> = emptyList()) {
        val message get() = buildList {
            val labels = mapOf("package" to "the hotel app", "check_in" to "the check-in date including year", "check_out" to "the check-out date including year",
                "check_out or nights" to "the check-out date or number of nights", "adults" to "the number of adults", "rooms" to "the number of rooms",
                "children" to "the number of children (including zero)", "child_ages" to "each child's age")
            if (missing.isNotEmpty()) add("Please provide ${missing.joinToString(", ") { labels[it] ?: it }}.")
            addAll(errors)
            if (unsupported.isNotEmpty()) add("These requested constraints are not supported: ${unsupported.joinToString(", ")}.")
        }.joinToString(" ")
    }

    fun isHotelRequest(request: String): Boolean {
        val text = request.trim().replace(Regex("^(?:please\\s+|(?:can|could) you\\s+)+", i), "")
        if (Regex("^(?:write|draft|message|text|send|e-?mail|summari[sz]e|tell)\\b", i).containsMatchIn(text)) return false
        return Regex("\\b(?:hotels?|stays?|accommodation)\\b", i).containsMatchIn(text) &&
            (Regex("\\b(?:search|find|look|book|reserve|research|plan|need|want|show|open)\\b", i).containsMatchIn(text) ||
                Regex("^(?:(?:a|an|the)\\s+)?(?:hotels?|stays?|accommodation)\\s+(?:in|near|around|at)\\b", i).containsMatchIn(text))
    }
    fun requestsResearch(request: String): Boolean = isHotelRequest(request) &&
        Regex("\\b(?:research|gemini|best season|good season|suitable season|when to (?:go|visit))\\b", i).containsMatchIn(request)

    fun suppliedInputs(request: String, packageName: String = PACKAGE, savedOwnerFacts: JSONObject = JSONObject()): JSONObject {
        val facts = JSONObject(savedOwnerFacts.toString())
        applyOwnerInputs(facts, request, true)
        return JSONObject().put("package", packageName).put("owner_facts", facts)
    }

    /** Pure validation shared by the planner, resolver and UI adapter. No app defaults fill missing values. */
    fun prepare(inputs: JSONObject, today: LocalDate = LocalDate.now()): Prepared {
        val facts = inputs.optJSONObject("owner_facts") ?: inputs
        val missing = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val pkg = inputs.opt("package") as? String
        if (pkg.isNullOrBlank()) missing += "package" else if (pkg != PACKAGE) unsupported += "hotel provider $pkg"
        val destination = facts.opt("destination") as? String
        if (destination.isNullOrBlank()) missing += "destination"
        fun date(key: String): LocalDate? {
            val value = facts.opt(key)
            if (value == null || value == JSONObject.NULL || value == "") { missing += key; return null }
            val parsed = (value as? String)?.takeIf { iso.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (parsed == null) errors += "$key must be an existing date in YYYY-MM-DD format, including its year."
            return parsed
        }
        val checkIn = date("check_in")
        val hasCheckout = facts.has("check_out") && !facts.isNull("check_out") && facts.optString("check_out").isNotBlank()
        var checkOut = if (!hasCheckout && facts.has("nights")) null else date("check_out")
        if (facts.has("nights")) {
            val nights = facts.opt("nights")
            if (nights !is Int || nights < 1) errors += "nights must be a positive whole number." else if (checkIn != null) {
                val derived = runCatching { checkIn.plusDays(nights.toLong()) }.getOrNull()
                if (derived == null) errors += "The requested stay duration is outside the supported date range."
                else if (checkOut != null && checkOut != derived) errors += "check_out and nights disagree; please choose the intended stay."
                else checkOut = derived
            }
        }
        if (checkOut == null && "check_out" !in missing && !hasCheckout) missing += "check_out or nights"
        fun count(key: String, minimum: Int): Int? {
            val value = facts.opt(key)
            if (value == null || value == JSONObject.NULL) { missing += key; return null }
            if (value !is Int || value < minimum) { errors += "$key must be a whole number of at least $minimum."; return null }
            return value
        }
        val adults = count("adults", 1)
        val rooms = count("rooms", 1)
        val children = count("children", 0)
        if (checkIn != null && checkIn.isBefore(today)) errors += "check_in is in the past; please choose new stay dates."
        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) errors += "check_out must be after check_in."
        if (adults != null && rooms != null && adults < rooms) errors += "Each room needs at least one adult; please confirm rooms and adults."
        var ages = emptyList<Int>()
        val suppliedAges = facts.optJSONArray("child_ages")
        if (children != null && children > 0 && suppliedAges == null) missing += "child_ages"
        if (suppliedAges != null) {
            if ((0 until suppliedAges.length()).any { suppliedAges.opt(it) !is Int || suppliedAges.getInt(it) !in 0..17 })
                errors += "child_ages must contain whole ages from 0 to 17."
            else ages = (0 until suppliedAges.length()).map { suppliedAges.getInt(it) }
            if (children != null && suppliedAges.length() != children) errors += "Provide one age for each child."
        }
        facts.optJSONArray("unsupported_constraints")?.let { a -> (0 until a.length()).forEach { unsupported += a.getString(it) } }
        listOf("budget", "max_price", "star_rating", "amenities", "property_name").filter { facts.has(it) && !facts.isNull(it) }.forEach { unsupported += it }
        facts.optJSONArray("input_errors")?.let { a -> (0 until a.length()).forEach { errors += a.getString(it) } }
        if (missing.isNotEmpty() || errors.isNotEmpty() || unsupported.isNotEmpty()) return Prepared(missing = missing.distinct(), errors = errors.distinct(), unsupported = unsupported.distinct())
        return Prepared(Stay(pkg!!, destination!!.trim(), checkIn!!, checkOut!!, adults!!, rooms!!, children!!, ages))
    }

    fun resolve(inputs: JSONObject, today: LocalDate = LocalDate.now()): CapabilityResult {
        val parsed = prepare(inputs, today)
        val facts = inputs.optJSONObject("owner_facts") ?: inputs
        val evidence = JSONObject().put("source_package", "local.owner_inputs").put("captured_at", System.currentTimeMillis())
            .put("input_bind", inputs.optString("_input_bind")).put("owner_facts_sha256", hash(canonicalFacts(facts)))
            .put("research_used_for_choices", false).put("research_available", inputs.optString("research_answer").isNotBlank())
        if (parsed.stay == null) return CapabilityResult(if (parsed.unsupported.isNotEmpty()) "UNSUPPORTED" else "WAITING_INPUT",
            JSONObject().put("missing_fields", JSONArray(parsed.missing)).put("invalid_fields", JSONArray(parsed.errors))
                .put("unsupported_constraints", JSONArray(parsed.unsupported)), evidence, parsed.message)
        return CapabilityResult(output = parsed.stay.json().put("missing_fields", JSONArray()), evidence = evidence.put("verified", true),
            message = "Supplied hotel stay details validated. Research recommendations have not replaced owner choices.")
    }

    fun execute(ctx: SkillContext, capability: String, inputs: JSONObject,
                typeDestination: (ScreenCandidate, String) -> Boolean,
                navigate: (AppInfo, String, String?) -> Outcome, beforeDispatch: () -> Boolean): CapabilityResult = when (capability) {
        "hotel.resolve_inputs" -> resolve(inputs)
        "hotel.search" -> prepare(inputs).let { parsed ->
            parsed.stay?.let { HotelSearch.execute(ctx, it, inputs, typeDestination, navigate, beforeDispatch) }
                ?: CapabilityResult(if (parsed.unsupported.isNotEmpty()) "UNSUPPORTED" else "WAITING_INPUT",
                    JSONObject().put("missing_fields", JSONArray(parsed.missing)), message = parsed.message)
        }
        else -> CapabilityResult("UNSUPPORTED", message = "No hotel capability is registered for that operation.")
    }

    fun inspect(ctx: SkillContext, capability: String, inputs: JSONObject): CapabilityResult? = when (capability) {
        "hotel.resolve_inputs" -> resolve(inputs)
        "hotel.search" -> prepare(inputs).stay?.let { HotelSearch.inspect(ctx.screen(), it, inputs) }
        else -> null
    }

    fun verificationError(capability: String, inputs: JSONObject, result: CapabilityResult): String? {
        if (result.state != "COMPLETED") return "The hotel capability did not complete."
        return when (capability) {
            "hotel.resolve_inputs" -> {
                val expected = prepare(inputs).stay ?: return "Owner stay details are incomplete or invalid."
                val facts = inputs.optJSONObject("owner_facts") ?: inputs
                if (result.evidence.optString("source_package") != "local.owner_inputs" || result.evidence.optBoolean("research_used_for_choices") ||
                    result.evidence.optString("owner_facts_sha256") != hash(canonicalFacts(facts))) "Resolved stay lacks owner-input provenance."
                else if (!sameStay(result.output, expected.json())) "Resolved stay differs from the supplied owner choices." else null
            }
            "hotel.search" -> prepare(inputs).stay?.let { HotelSearch.verificationError(it, result) } ?: "Stay fields are missing."
            else -> "No hotel verifier is registered."
        }
    }

    fun answer(capability: String, output: JSONObject): String {
        if (capability == "hotel.resolve_inputs") return "Supplied hotel stay details validated."
        val results = output.optJSONArray("results") ?: JSONArray()
        val lines = (0 until results.length()).joinToString("\n") { index -> results.getJSONObject(index).let { "• ${it.getString("name")}: ${it.getString("visible_details")}" } }
        return "Verified hotel search: ${output.getString("destination")}, ${output.getString("check_in")} to ${output.getString("check_out")}; " +
            "${output.getInt("rooms")} rooms, ${output.getInt("adults")} adults, ${output.getInt("children")} children.\n$lines\nVisible result cards only. No reservation was made."
    }

    internal fun sameStay(actual: JSONObject, expected: JSONObject): Boolean = (listOf("package") + keys).all { key ->
        val a = actual.opt(key)
        val b = expected.opt(key)
        if (a is JSONArray && b is JSONArray) a.toString() == b.toString() else a == b && a?.javaClass == b?.javaClass
    }
    private fun canonicalFacts(facts: JSONObject) = facts.keys().asSequence().filter { it != "_input_bind" }.sorted()
        .joinToString("|\n") { "$it=${facts.get(it)}" }
    internal fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Explicit owner facts only; a city, season, date or party size is never invented from research. */
    internal fun applyOwnerInputs(facts: JSONObject, request: String, overwrite: Boolean) {
        val sources = facts.optJSONObject("input_sources") ?: JSONObject().also { facts.put("input_sources", it) }
        fun put(key: String, value: Any) {
            if (overwrite || !facts.has(key) || facts.isNull(key)) {
                facts.put(key, value)
                sources.put(key, JSONObject().put("basis", "explicit owner input").put("request", request))
            }
        }
        Regex("\\b(?:hotels?|stays?|accommodation)\\s+(?:in|near|around|at)\\s+(.+?)(?=\\s+(?:on|from|for|check[ -]?in|with|using|via|in (?:the )?(?:app|makemytrip))\\b|[;]|$)", i)
            .find(request)?.groupValues?.get(1)?.trim(' ', '.', ',')?.takeIf { it.isNotBlank() &&
                !Regex("^(?:me|here|nearby|somewhere|anywhere|(?:a |an )?(?:good|best|suitable|interesting) (?:season|destination).*)$", i).matches(it) }
            ?.let { put("destination", it) }
        data class Token(val value: String, val range: IntRange)
        val dates = mutableListOf<Token>()
        iso.findAll(request).forEach { dates += Token(it.value, it.range) }
        fun named(pattern: Regex, monthFirst: Boolean) = pattern.findAll(request).forEach { m ->
            val day = m.groupValues[if (monthFirst) 2 else 1].toInt()
            val name = m.groupValues[if (monthFirst) 1 else 2]
            val number = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(name.take(3).lowercase(Locale.ROOT)) + 1
            dates += Token("%04d-%02d-%02d".format(Locale.ROOT, m.groupValues[3].toInt(), number, day), m.range)
        }
        named(namedDay, false); named(namedMonth, true)
        val ordered = dates.sortedBy { it.range.first }
        ordered.forEach { token ->
            val prefix = request.substring(0, token.range.first)
            when {
                Regex("check[ -]?out\\s*(?:date|on|is|:|=)?\\s*$", i).containsMatchIn(prefix) -> put("check_out", token.value)
                Regex("check[ -]?in\\s*(?:date|on|is|:|=)?\\s*$", i).containsMatchIn(prefix) -> put("check_in", token.value)
            }
        }
        if (ordered.size == 2 && Regex("^\\s*(?:to|until|through|–|—|-)\\s*$", i)
                .matches(request.substring(ordered[0].range.last + 1, ordered[1].range.first))) {
            put("check_in", ordered[0].value); put("check_out", ordered[1].value)
        } else if (ordered.size == 1 && !Regex("check[ -]?out\\s*(?:date|on|is|:|=)?\\s*$", i).containsMatchIn(request.substring(0, ordered[0].range.first))) put("check_in", ordered[0].value)
        for ((key, noun) in listOf("adults" to "adults?", "rooms" to "rooms?", "children" to "(?:children|child|kids?)", "nights" to "nights?"))
            Regex("\\b(\\d+)\\s+$noun\\b", i).find(request)?.groupValues?.get(1)?.toIntOrNull()?.let { put(key, it) }
        if (Regex("\\b(?:no|zero)\\s+(?:children|kids?)\\b", i).containsMatchIn(request)) put("children", 0)
        Regex("\\b(?:children|kids?)\\s+(?:aged?|ages?)\\s+([\\d, &and]+)", i).find(request)?.groupValues?.get(1)?.let { values ->
            put("child_ages", JSONArray(Regex("\\d+").findAll(values).mapNotNull { it.value.toIntOrNull() }.toList()))
        }
        val unsupported = mutableListOf<String>()
        if (Regex("\\b(?:budget|under|below|maximum price|max price)\\b", i).containsMatchIn(request)) unsupported += "budget filtering"
        if (Regex("\\b(?:free cancellation|breakfast|star hotel|stars|pool|pet[ -]friendly|accessible room)\\b", i).containsMatchIn(request)) unsupported += "requested property filters"
        if (unsupported.isNotEmpty()) put("unsupported_constraints", JSONArray(unsupported))
    }

    fun selfCheck(): String {
        val today = LocalDate.of(2026, 10, 20)
        val request = "find hotels in Porto, Portugal from 2026-11-02 to 2026-11-04, 2 adults, 1 room, no children"
        val supplied = suppliedInputs(request)
        val parsed = prepare(supplied, today)
        check(parsed.stay?.destination == "Porto, Portugal" && parsed.stay.children == 0)
        check(parsed.stay.checkIn == LocalDate.of(2026, 11, 2) && parsed.stay.checkOut == LocalDate.of(2026, 11, 4))
        check(prepare(suppliedInputs("research hotels in an interesting destination in a good season").put("research_answer", "Choose a city in May, 2 adults and 1 room."), today).missing.containsAll(listOf("check_in", "check_out", "adults", "rooms", "children")))
        check(prepare(suppliedInputs("find hotels in Paris on 28 October 2026 for 2 nights, 2 adults, 1 room, no children"), today).stay?.checkOut == LocalDate.of(2026, 10, 30))
        val child = suppliedInputs("find hotels in Rome from 2026-11-02 to 2026-11-04, 2 adults, 1 room, 2 children aged 4 and 8")
        check(prepare(child, today).stay?.childAges == listOf(4, 8))
        check(prepare(suppliedInputs("find hotels in Rome from 2026-11-02 to 2026-11-04, 2 adults, 1 room, 1 child"), today).missing == listOf("child_ages"))
        check(prepare(suppliedInputs("find hotels in Rome on 31 April 2027, 2 adults, 1 room, no children"), today).stay == null)
        check(prepare(suppliedInputs("find hotels in Rome on 28 October for 2 nights, 2 adults, 1 room, no children"), today).stay == null)
        check(prepare(suppliedInputs("find hotels in Rome from 2026-11-04 to 2026-11-02, 2 adults, 1 room, no children"), today).errors.isNotEmpty())
        check(prepare(suppliedInputs("find hotels in Rome from 2025-11-02 to 2025-11-04, 2 adults, 1 room, no children"), today).errors.any { "past" in it })
        check(prepare(suppliedInputs("find hotels in Rome from 2026-11-02 to 2026-11-04, 2 adults, 3 rooms, no children"), today).errors.isNotEmpty())
        check(prepare(suppliedInputs(request + " with breakfast under INR 5000"), today).unsupported.size == 2)
        check(!isHotelRequest("message Sam to find hotels in Paris") && isHotelRequest(request) && requestsResearch("research hotels in Kyoto using Gemini"))
        check(isHotelRequest("hotel in Kyoto on a good season") && requestsResearch("hotel in Kyoto on a good season"))
        check(prepare(suppliedInputs("find hotels near me"), today).missing.contains("destination"))
        val recommendations = suppliedInputs("research a hotel in a good season").put("research_answer", "Use Porto, 2026-11-02 to 2026-11-04, 2 adults, 1 room, no children")
        check(prepare(recommendations, today).missing.containsAll(listOf("destination", "check_in", "check_out", "adults", "rooms", "children")))
        val nullableCheckout = suppliedInputs("find hotels in Paris on 28 October 2026 for 2 nights, 2 adults, 1 room, no children")
        nullableCheckout.getJSONObject("owner_facts").put("check_out", JSONObject.NULL)
        check(prepare(nullableCheckout, today).stay?.checkOut == LocalDate.of(2026, 10, 30))
        val result = resolve(supplied, today)
        check(result.state == "COMPLETED" && result.evidence.optString("source_package") == "local.owner_inputs")
        check(!result.evidence.optBoolean("research_used_for_choices"))
        return "ok"
    }
}
