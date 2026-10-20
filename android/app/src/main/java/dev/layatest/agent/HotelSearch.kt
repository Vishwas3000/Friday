package dev.layatest.agent

import dev.layatest.agent.coordinator.CapabilityResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/** A bounded app adapter. The coordinator owns task state, input questions, retries and publication. */
internal object HotelSearch {
    private const val PKG = HotelCapabilities.PACKAGE
    private val money = Regex("(?:\\p{Sc}|Rs\\.?|${Currency.getAvailableCurrencies().joinToString("|") { it.currencyCode }})\\s*[\\d,]+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
    private val month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun execute(ctx: SkillContext, stay: HotelCapabilities.Stay, inputs: JSONObject,
                typeDestination: (ScreenCandidate, String) -> Boolean,
                navigate: (AppInfo, String, String?) -> Outcome, beforeDispatch: () -> Boolean): CapabilityResult {
        fun allowed() = !ctx.stopped && beforeDispatch()
        val guarded = object : SkillContext by ctx {
            override fun open(app: AppInfo, fresh: Boolean) = allowed() && ctx.open(app, fresh)
            override fun tap(target: ScreenCandidate) = allowed() && ctx.tap(target)
            override fun scroll(direction: String) = allowed() && ctx.scroll(direction)
            override fun back() = allowed() && ctx.back()
        }
        val observed = JSONObject().put("hotel", stay.json())
        val message = drive(guarded, observed, { target, text -> allowed() && typeDestination(target, text) },
            { app, goal, text -> if (allowed()) navigate(app, goal, text) else Outcome.STOPPED })
        val output = stay.json().put("results", observed.optJSONArray("hotel_results") ?: JSONArray())
            .put("scope", "visible_result_cards").put("reservation_made", false)
        val evidence = baseEvidence(inputs).put("proof_kind", "verified_form_and_results")
            .put("form_state", observed.optJSONObject("hotel_form_state"))
            .put("form_captured_at", observed.optJSONObject("hotel_form_evidence")?.optLong("captured_at") ?: 0)
            .put("search_dispatched_at", observed.optLong("hotel_search_dispatched_at"))
            .put("destination_state", observed.optJSONObject("hotel_destination_state"))
            .put("selected_suggestion", observed.optJSONObject("hotel_destination_evidence")?.optString("selected_suggestion"))
            .put("results_state", observed.optJSONObject("hotel_results_state"))
            .put("query_basis", "same-attempt exact pre-search form plus matching post-search result header")
        if (ctx.stopped) return CapabilityResult("CANCELLED", output, evidence.put("verified", false), "Stopped. No reservation was made.")
        val candidate = CapabilityResult(output = output, evidence = evidence, message = message)
        val error = if (!observed.optBoolean("hotel_results_verified")) message else verificationError(stay, candidate)
        return if (error != null) CapabilityResult("BLOCKED", output, evidence.put("verified", false), error)
        else candidate.copy(message = HotelCapabilities.answer("hotel.search", output))
    }

    /** Reconciliation reads only the current result screen; missing hidden fields require safe search replay. */
    fun inspect(screen: Screen, stay: HotelCapabilities.Stay, inputs: JSONObject): CapabilityResult? {
        if (!fullResultQueryMatches(screen, stay) || readResults(screen).length() == 0) return null
        val output = stay.json().put("results", readResults(screen)).put("scope", "visible_result_cards").put("reservation_made", false)
        return CapabilityResult(output = output, evidence = baseEvidence(inputs).put("proof_kind", "full_result_screen").put("results_state", screen.state),
            message = HotelCapabilities.answer("hotel.search", output))
    }

    fun verificationError(stay: HotelCapabilities.Stay, result: CapabilityResult): String? {
        if (!HotelCapabilities.sameStay(result.output, stay.json())) return "The returned query differs from the supplied stay."
        if (result.evidence.optString("source_package") != stay.packageName) return "Hotel evidence came from another app."
        val raw = result.evidence.optJSONObject("results_state") ?: return "The raw result screen is missing."
        val screen = rawScreen(raw)
        if (result.evidence.optString("proof_kind") == "full_result_screen") {
            if (!fullResultQueryMatches(screen, stay)) return "The complete result query, including year and guest breakdown, was not read back."
        } else {
            if (result.evidence.optString("proof_kind") != "verified_form_and_results") return "Unregistered hotel verification proof."
            val form = result.evidence.optJSONObject("form_state")?.let(::rawScreen) ?: return "The exact pre-search form is missing."
            val place = field(form, "location_box")
            val selected = result.evidence.optJSONObject("destination_state")?.let(::rawScreen)?.let { destinationSuggestion(it, stay.destination) }
            val context = contextMatches(place, stay.destination) || (selected != null && selected.label == result.evidence.optString("selected_suggestion"))
            if (!formVisible(form) || !destinationMatches(place, stay.destination) || !context ||
                !dateMatches(field(form, "check_in_date"), stay.checkIn) || !dateMatches(field(form, "check_out_date"), stay.checkOut) ||
                !occupancyMatches(field(form, "room_and_guest_box"), stay.rooms, stay.adults, stay.children) || !agesMatch(form, stay.json()))
                return "The pre-search destination, dates/year, occupancy or child ages differ from the requested stay."
            val captured = result.evidence.optLong("form_captured_at")
            val dispatched = result.evidence.optLong("search_dispatched_at")
            if (captured <= 0 || dispatched < captured || result.evidence.optLong("captured_at") < dispatched) return "The form/search/result observation order is invalid."
            if (!resultHeaderMatches(screen, stay.destination, stay.checkIn, stay.checkOut, stay.rooms, stay.adults + stay.children)) return "The actual result header differs from the verified stay."
            val breakdown = field(screen, "dateGuestText")
            if (Regex("\\b(?:adults?|child(?:ren)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(breakdown) &&
                !occupancyMatches(breakdown, stay.rooms, stay.adults, stay.children)) return "The result guest breakdown differs from the supplied stay."
        }
        val actual = readResults(screen)
        if (actual.length() == 0 || actual.toString() != result.output.optJSONArray("results")?.toString()) return "Actual visible hotel result cards were not verified."
        if (result.output.optString("scope") != "visible_result_cards" || result.output.opt("reservation_made") != false) return "Only search-result handoff is supported."
        return null
    }

    private fun baseEvidence(inputs: JSONObject) = JSONObject().put("source_package", PKG).put("captured_at", System.currentTimeMillis())
        .put("input_bind", inputs.optString("_input_bind")).put("verified", true)
    private fun rawScreen(raw: JSONObject) = Screen(raw.optString("package").takeIf { it.isNotBlank() }, emptyList(), emptyList(), raw)
    private fun fullResultQueryMatches(screen: Screen, stay: HotelCapabilities.Stay): Boolean {
        val summary = field(screen, "dateGuestText")
        fun date(date: LocalDate) = "${date.dayOfMonth}\\s+${month.format(date)}[a-z]*\\s*['‘’]?(?:${date.year}|${date.year % 100})"
        return resultHeaderMatches(screen, stay.destination, stay.checkIn, stay.checkOut, stay.rooms, stay.adults + stay.children) &&
            contextMatches(field(screen, "edit_search_container"), stay.destination) &&
            Regex("\\b${date(stay.checkIn)}\\s*(?:-|–|—|to)\\s*${date(stay.checkOut)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(summary) &&
            occupancyMatches(summary, stay.rooms, stay.adults, stay.children) && agesMatch(screen, stay.json())
    }

    /** Compatibility entrypoint cannot resume or dispatch the retired workflow. */
    @Suppress("UNUSED_PARAMETER")
    fun run(ctx: SkillContext, state: JSONObject, typeDestination: (ScreenCandidate, String) -> Boolean,
            navigate: (AppInfo, String, String?) -> Outcome): String = ResearchWorkflow.migrationMessage()

    private fun drive(ctx: SkillContext, state: JSONObject, typeDestination: (ScreenCandidate, String) -> Boolean,
            navigate: (AppInfo, String, String?) -> Outcome): String {
        val hotel = state.optJSONObject("hotel") ?: return pending(state, "Missing saved hotel details.")
        val app = ctx.apps.installed.firstOrNull { it.pkg == PKG }
            ?: return pending(state, "MakeMyTrip is not installed; the hotel search is saved.")
        state.put("hotel_results_verified", false)
        if (ctx.stopped) return "Stopped. The hotel search is saved."
        ctx.say("Opening MakeMyTrip hotels…")
        if (!ctx.open(app) || !ctx.waitUntil(5000) { ctx.foreground() == PKG })
            return pending(state, "MakeMyTrip could not be opened.")
        var screen = ctx.screen()
        if ((find(screen, "btnDone") != null || destinationInput(screen) != null || find(screen, "done_button") != null) && !ctx.back())
            return pending(state, "Return to the hotel search form to continue.")
        screen = ctx.screen()
        screen = revealResultsHeader(ctx, screen)
        if (!formVisible(screen) && !ctx.stopped) {
            val edit = find(screen, "edit_search_container") ?: find(screen, "edit_icon")
            val hotels = nodes(screen).firstOrNull { it.optString("text").trim().equals("Hotels", true) && it.optBoolean("enabled") }
            val target = edit ?: hotels
            if (target != null && tapNode(ctx, screen, target)) ctx.waitUntil(3500) { formVisible(ctx.screen()) }
            screen = ctx.screen()
        }
        if (!formVisible(screen)) {
            if (navigate(app, "open the Hotels search form only; do not search, select a hotel, reserve or pay", null) == Outcome.STOPPED || ctx.stopped)
                return "Stopped. The hotel search is saved."
            screen = ctx.screen()
        }
        if (!formVisible(screen))
            return pending(state, "The MakeMyTrip hotel form was not verified.")
        val destination = hotel.getString("destination").trim()
        val checkIn = runCatching { LocalDate.parse(hotel.getString("check_in")) }.getOrNull()
        val checkOut = runCatching { LocalDate.parse(hotel.getString("check_out")) }.getOrNull()
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn))
            return pending(state, "Provide valid ISO check-in and checkout dates; checkout must be after check-in.")
        if (checkIn.isBefore(LocalDate.now())) return pending(state, "The saved check-in date is in the past. Please provide new dates.")
        val adults = hotel.getInt("adults")
        val rooms = hotel.getInt("rooms")
        val children = hotel.getInt("children")
        if (!destinationMatches(field(screen, "location_box"), destination) || !contextMatches(field(screen, "location_box"), destination)) {
            ctx.say("Setting hotel destination…")
            if (!tap(ctx, screen, "location_box")) return pending(state, "The hotel location field could not be opened.")
            val query = destinationQuery(destination)
            if (query.isBlank()) return pending(state, "The saved destination does not contain a usable city/locality.")
            if (!ctx.waitUntil(3500) { destinationInput(ctx.screen()) != null })
                return pending(state, "MakeMyTrip's destination input was not verified.")
            screen = ctx.screen()
            val input = destinationInput(screen) ?: return pending(state, "The destination input disappeared.")
            if (!tapNode(ctx, screen, input)) return pending(state, "The verified destination input could not be focused.")
            screen = ctx.screen()
            val focused = destinationInput(screen)?.let(::candidate) ?: return pending(state, "The focused destination field was not verified.")
            if (!typeDestination(focused, query)) return pending(state, "The exact destination could not be entered in the verified field.")
            ctx.say("Finding $query…")
            if (!ctx.waitUntil(6000) {
                    screen = ctx.screen()
                    destinationInput(screen)?.optString("text")?.trim()?.equals(query, true) == true &&
                        destinationSuggestion(screen, destination) != null
                }) return pending(state, "No exact $query suggestion with the requested country was verified. No alternative location was selected.")
            screen = ctx.screen()
            if (destinationInput(screen)?.optString("text")?.trim()?.equals(query, true) != true)
                return pending(state, "The destination search text changed before selection.")
            val suggestion = destinationSuggestion(screen, destination)
                ?: return pending(state, "The matching destination suggestion disappeared.")
            state.put("hotel_destination_state", screen.state)
            state.put("hotel_destination_evidence", JSONObject().put("query", query).put("selected_suggestion", suggestion.label)
                .put("captured_at", System.currentTimeMillis()))
            if (ctx.stopped || ctx.foreground() != PKG || !ctx.tap(suggestion))
                return pending(state, "The verified destination suggestion could not be selected.")
            ctx.waitUntil(4000) { formDestinationMatches(ctx.screen(), destination) }
            screen = ctx.screen()
            if (!destinationMatches(field(screen, "location_box"), destination))
                return pending(state, "The destination was not verified as $destination. Please select the matching locality/city in MakeMyTrip.")
        }
        ctx.say("Setting hotel dates…")
        if (!dateMatches(field(screen, "check_in_date"), checkIn) || !dateMatches(field(screen, "check_out_date"), checkOut)) {
            if (!tap(ctx, screen, "check_in_date")) return pending(state, "The check-in calendar could not be opened.")
            if (!ctx.waitUntil(4000) { find(ctx.screen(), "rlCheckin") != null }) return pending(state, "The date picker was not verified.")
            if (!selectDate(ctx, checkIn, true)) return pending(state, "Check-in $checkIn could not be selected and verified in the visible calendar.")
            if (!selectDate(ctx, checkOut, false)) return pending(state, "Checkout $checkOut could not be selected and verified in the visible calendar.")
            screen = ctx.screen()
            if (!dateMatches(field(screen, "rlCheckin"), checkIn) || !dateMatches(field(screen, "rlCheckout"), checkOut))
                return pending(state, "The calendar's date summary does not match the supplied stay.")
            if (!tap(ctx, screen, "btnDone")) return pending(state, "The calendar Done control is unavailable or disabled.")
            ctx.waitUntil(4000) { find(ctx.screen(), "check_in_date") != null }
            screen = ctx.screen()
        }
        if (!occupancyMatches(field(screen, "room_and_guest_box"), rooms, adults, children) || !agesMatch(screen, hotel)) {
            ctx.say("Setting rooms and guests…")
            if (!tap(ctx, screen, "room_and_guest_box")) return pending(state, "The rooms and guests picker could not be opened.")
            val goal = "set exactly $rooms rooms, $adults adults and $children children; apply the guest picker and return to the hotel form only; do not search, select hotels, reserve or pay; use child ages exactly ${hotel.getJSONArray("child_ages")}; stop if the requested ages cannot be set and read back"
            if (navigate(app, goal, null) == Outcome.STOPPED || ctx.stopped) return "Stopped. The hotel search is saved."
            screen = ctx.screen()
            if (!occupancyMatches(field(screen, "room_and_guest_box"), rooms, adults, children))
                return pending(state, "The requested occupancy was not verified ($rooms rooms, $adults adults, $children children). Complete the guest picker${if (children > 0) " and provide any required child ages" else ""} to continue.")
        }
        if (ctx.stopped) return "Stopped. The hotel search is saved."
        screen = ctx.screen()
        val evidence = JSONObject().put("destination", field(screen, "location_box"))
            .put("check_in", field(screen, "check_in_date")).put("check_out", field(screen, "check_out_date"))
            .put("occupancy", field(screen, "room_and_guest_box")).put("captured_at", System.currentTimeMillis())
        state.put("hotel_form_evidence", evidence).put("hotel_form_state", screen.state)
        val selectedContext = state.optJSONObject("hotel_destination_state")?.let(::rawScreen)?.let { destinationSuggestion(it, destination) }
        val contextVerified = contextMatches(evidence.getString("destination"), destination) ||
            (selectedContext != null && selectedContext.label == state.optJSONObject("hotel_destination_evidence")?.optString("selected_suggestion"))
        if (screen.pkg != PKG || !destinationMatches(evidence.getString("destination"), destination) ||
            !contextVerified ||
            !dateMatches(evidence.getString("check_in"), checkIn) || !dateMatches(evidence.getString("check_out"), checkOut) ||
            !occupancyMatches(evidence.getString("occupancy"), rooms, adults, children) || !agesMatch(screen, hotel))
            return pending(state, "The final search form does not match all supplied stay details. Search was not pressed.")
        ctx.say("Searching verified hotel stay…")
        state.put("hotel_search_dispatched_at", System.currentTimeMillis())
        if (!tap(ctx, screen, "search_cta")) return pending(state, "The Search control could not be activated.")
        var results = JSONArray()
        var headerRecoveryAttempted = false
        ctx.waitUntil(15000) {
            screen = ctx.screen()
            if (!headerRecoveryAttempted && find(screen, "edit_search_container") == null && readResults(screen).length() > 0) {
                headerRecoveryAttempted = true
                screen = revealResultsHeader(ctx, screen)
            }
            results = if (resultHeaderMatches(screen, destination, checkIn, checkOut, rooms, adults + children)) readResults(screen) else JSONArray()
            results.length() > 0
        }
        if (ctx.stopped) return "Stopped. The hotel search is saved."
        state.put("hotel_results", results).put("hotel_results_captured_at", System.currentTimeMillis()).put("hotel_results_state", screen.state)
            .put("hotel_result_header_evidence", JSONObject().put("destination_and_stay", field(screen, "edit_search_container"))
                .put("dates_and_guests", field(screen, "dateGuestText"))
                .put("year_and_guest_breakdown_basis", "independently verified pre-search form"))
        if (results.length() == 0) return pending(state, "Search was submitted with verified stay details, but no hotel result cards could be verified. Availability, loading or a network problem may still need attention.")
        state.put("hotel_results_verified", true)
            .put("hotel_result_scope", "visible result cards from one screen; no reservation")
            .put("hotel_budget_verified", false)
        return "Visible hotel result cards captured."
    }

    private fun pending(state: JSONObject, message: String): String {
        state.put("hotel_results_verified", false)
        return message
    }

    /** A retained result-list scroll can hide Edit Search. Read-only, at most three upward scrolls. */
    private fun revealResultsHeader(ctx: SkillContext, initial: Screen): Screen {
        var screen = initial
        repeat(3) {
            if (ctx.stopped || screen.pkg != PKG || ctx.foreground() != PKG || formVisible(screen) ||
                find(screen, "edit_search_container") != null || readResults(screen).length() == 0 ||
                Safety.isAuthPrompt(screen.pkg, nodes(screen).map(::text))) return screen
            ctx.say("Checking the hotel results header…")
            if (!ctx.scroll("up")) return screen
            screen = ctx.screen()
        }
        return screen
    }

    fun formVisible(screen: Screen): Boolean = screen.pkg == PKG &&
        listOf("location_box", "check_in_date", "check_out_date", "room_and_guest_box").all { find(screen, it) != null }

    fun formDestinationMatches(screen: Screen, destination: String): Boolean =
        formVisible(screen) && destinationMatches(field(screen, "location_box"), destination)

    private fun destinationQuery(destination: String) = destination.substringBefore(',').trim()

    private fun destinationInput(screen: Screen): JSONObject? = nodes(screen).filter {
        id(it) == "input_text" && it.optBoolean("editable") && it.optBoolean("enabled")
    }.singleOrNull()

    /** Known city rows: title and country subtitle must belong to the same bounded clickable row. */
    private fun destinationSuggestion(screen: Screen, destination: String): ScreenCandidate? {
        val query = normalized(destinationQuery(destination))
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return null
        val rows = nodes(screen).filter { row ->
            val bounds = row.optJSONArray("bounds")
            val children = row.optJSONArray("children")
            if (!row.optBoolean("clickable") || !row.optBoolean("enabled") || bounds == null ||
                bounds.optInt(3) - bounds.optInt(1) >= height / 3 || children == null) return@filter false
            val items = (0 until children.length()).map { children.getJSONObject(it) }
                .filter { it.optBoolean("visible") && it.optString("package") == PKG }
            val titles = items.filter { id(it) == "title" }
            val context = items.filter { id(it) == "sub_title" }.joinToString(" ") { it.optString("text") }
            titles.size == 1 && normalized(titles.single().optString("text")) == query &&
                contextMatches(context, destination)
        }
        return rows.singleOrNull()?.let(::candidate)
    }

    private fun resultHeaderMatches(screen: Screen, destination: String, checkIn: LocalDate, checkOut: LocalDate,
                                    rooms: Int, guests: Int): Boolean {
        if (screen.pkg != PKG || formVisible(screen)) return false
        val header = find(screen, "edit_search_container") ?: return false
        val children = header.optJSONArray("children") ?: return false
        val title = (0 until children.length()).map { children.getJSONObject(it) }.firstOrNull { id(it) == "title" }
            ?.let(::text).orEmpty()
        if (!destinationMatches(title, destination)) return false
        val stay = field(screen, "dateGuestText")
        val start = "${checkIn.dayOfMonth}\\s+${month.format(checkIn)}[a-z]*"
        val end = "${checkOut.dayOfMonth}\\s+${month.format(checkOut)}[a-z]*"
        val firstYear = "(?:\\s*['‘’]?(?:${checkIn.year}|${checkIn.year % 100}))?"
        val lastYear = "(?:\\s*['‘’]?(?:${checkOut.year}|${checkOut.year % 100}))?"
        if (!Regex("\\b$start$firstYear\\s*(?:-|–|—|to)\\s*$end$lastYear(?=\\s*(?:,|·|\\||$))", RegexOption.IGNORE_CASE).containsMatchIn(stay)) return false
        val shownYears = Regex("\\b(?:19|20)\\d{2}\\b").findAll(stay).map { it.value.toInt() }.toList()
        if (shownYears.any { it != checkIn.year && it != checkOut.year }) return false
        fun count(noun: String) = Regex("\\b(\\d+)\\s+$noun\\b", RegexOption.IGNORE_CASE).findAll(stay).map { it.groupValues[1].toIntOrNull() }.toList().singleOrNull()
        return count("rooms?") == rooms && count("guests?") == guests
    }

    private fun missingInputs(hotel: JSONObject) = listOf("destination", "check_in", "check_out", "adults", "rooms", "children").filter { key ->
        when (key) {
            "adults", "rooms" -> hotel.optInt(key, 0) < 1
            "children" -> hotel.isNull(key) || hotel.optInt(key, -1) < 0
            else -> (hotel.opt(key) as? String).isNullOrBlank()
        }
    }

    internal fun applyOwnerInputs(hotel: JSONObject, request: String, overwrite: Boolean) =
        HotelCapabilities.applyOwnerInputs(hotel, request, overwrite)

    private fun nodes(screen: Screen): List<JSONObject> {
        if (screen.pkg != PKG) return emptyList()
        val result = ArrayList<JSONObject>()
        fun walk(n: JSONObject) {
            if (!n.optBoolean("visible") || n.optString("package") != PKG) return
            result += n
            val children = n.optJSONArray("children") ?: return
            for (i in 0 until children.length()) walk(children.getJSONObject(i))
        }
        val windows = screen.state?.optJSONArray("windows") ?: return result
        for (i in 0 until windows.length()) windows.getJSONObject(i).let { if (it.optInt("type") == 1) walk(it.getJSONObject("root")) }
        return result
    }
    private fun id(node: JSONObject) = node.optString("resource_id").substringAfterLast('/')
    private fun find(screen: Screen, rid: String) = nodes(screen).firstOrNull { id(it) == rid }
    private fun text(node: JSONObject): String {
        val own = node.optString("text").ifBlank { node.optString("content_desc") }.trim()
        val children = node.optJSONArray("children") ?: return own
        return (listOf(own) + (0 until children.length()).map { children.getJSONObject(it).takeIf { child -> child.optBoolean("visible") && child.optString("package") == PKG }?.let(::text).orEmpty() }).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }
    private fun field(screen: Screen, rid: String) = find(screen, rid)?.let(::text).orEmpty()
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun destinationMatches(actual: String, expected: String): Boolean {
        val shown = normalized(actual)
        val wanted = normalized(destinationQuery(expected))
        return wanted.isNotBlank() && Regex("(?:^| )${Regex.escape(wanted)}(?: |$)").containsMatchIn(shown)
    }
    private fun contextMatches(actual: String, expected: String) = expected.split(',').drop(1).map { it.trim() }.filter { it.isNotBlank() }.all { context ->
        Regex("(?:^| )${Regex.escape(normalized(context))}(?: |$)").containsMatchIn(normalized(actual))
    }
    private fun dateMatches(actual: String, expected: LocalDate): Boolean {
        val value = normalized(actual)
        return Regex("\\b${expected.dayOfMonth}\\s+${month.format(expected).lowercase(Locale.ENGLISH)}[a-z]*\\b").containsMatchIn(value) &&
            Regex("\\b(?:${expected.year}|${expected.year % 100})\\b").containsMatchIn(value) &&
            Regex("\\b(?:19|20)\\d{2}\\b").findAll(actual).all { it.value == expected.year.toString() } &&
            Regex("['‘’](\\d{2})\\b").findAll(actual).all { it.groupValues[1].toInt() == expected.year % 100 }
    }
    private fun occupancyMatches(actual: String, rooms: Int, adults: Int, children: Int): Boolean {
        fun count(noun: String, expected: Int): Boolean {
            val values = Regex("\\b(\\d+)\\s+(?:$noun)\\b", RegexOption.IGNORE_CASE).findAll(actual).map { it.groupValues[1].toIntOrNull() }.toList()
            return values.size == 1 && values.single() == expected
        }
        return count("rooms?", rooms) && count("adults?", adults) && count("child(?:ren)?", children)
    }
    private fun agesMatch(screen: Screen, hotel: JSONObject): Boolean {
        if (hotel.getInt("children") == 0) return true
        val wanted = hotel.optJSONArray("child_ages") ?: return false
        val ages = nodes(screen).filter { Regex("child.*age|age.*child", RegexOption.IGNORE_CASE).containsMatchIn(id(it)) }
            .mapNotNull { Regex("(?:^|\\b)(\\d{1,2})\\s*(?:years?|yrs?)?\\s*$", RegexOption.IGNORE_CASE).find(it.optString("text"))?.groupValues?.get(1)?.toIntOrNull() }
        return ages.sorted() == (0 until wanted.length()).map { wanted.getInt(it) }.sorted()
    }

    private fun tap(ctx: SkillContext, screen: Screen, rid: String): Boolean = find(screen, rid)?.let { tapNode(ctx, screen, it) } ?: false
    private fun tapNode(ctx: SkillContext, screen: Screen, node: JSONObject): Boolean {
        if (ctx.stopped || screen.pkg != PKG || ctx.foreground() != PKG || !node.optBoolean("enabled") || !node.optBoolean("visible")) return false
        return candidate(node)?.let(ctx::tap) ?: false
    }
    private fun candidate(node: JSONObject): ScreenCandidate? {
        if (node.optString("package") != PKG || !node.optBoolean("enabled") || !node.optBoolean("visible")) return null
        val b = node.optJSONArray("bounds") ?: return null
        if (b.length() != 4 || b.optInt(2) <= b.optInt(0) || b.optInt(3) <= b.optInt(1)) return null
        return ScreenCandidate(text(node).take(100), node.optString("class").substringAfterLast('.'), node.optBoolean("clickable"),
            node.optBoolean("long_clickable"), node.optBoolean("editable"), node.optBoolean("scrollable"), node.optBoolean("checkable"),
            node.optBoolean("checked"), node.optBoolean("selected"), true, IntArray(4) { b.getInt(it) }, id(node))
    }
    private fun selectDate(ctx: SkillContext, expected: LocalDate, checkIn: Boolean): Boolean {
        val footer = if (checkIn) "rlCheckin" else "rlCheckout"
        var screen = ctx.screen()
        if (dateMatches(field(screen, footer), expected)) return true
        if (!tap(ctx, screen, footer)) return false
        val full = Regex("^${expected.dayOfMonth}\\s+${month.format(expected)}\\s+${expected.year}\\s+(?:Tap to select|Selected)$", RegexOption.IGNORE_CASE)
        var previous = ""
        repeat(10) {
            if (ctx.stopped) return false
            screen = ctx.screen()
            val date = nodes(screen).firstOrNull { it.optBoolean("enabled") && full.matches(it.optString("content_desc").trim()) }
            if (date != null) {
                if (!tapNode(ctx, screen, date)) return false
                return ctx.waitUntil(2500) { dateMatches(field(ctx.screen(), footer), expected) }
            }
            val signature = nodes(screen).map { it.optString("content_desc") }.filter { it.contains("Tap to select", true) }.joinToString("|")
            if (signature.isBlank() || signature == previous) return false
            previous = signature
            if (!ctx.scroll("down")) return false
        }
        return false
    }

    private fun readResults(screen: Screen): JSONArray {
        val result = JSONArray()
        if (screen.pkg != PKG || formVisible(screen)) return result
        val names = nodes(screen).filter { node ->
            val role = id(node).replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')
            Regex("\\b(?:hotel|property)\\b.*\\b(?:name|title)\\b", RegexOption.IGNORE_CASE).containsMatchIn(role) &&
                node.optString("text").isNotBlank()
        }.distinctBy { it.optString("text") }.take(5)
        val all = nodes(screen)
        for (name in names) {
            val bounds = name.optJSONArray("bounds") ?: continue
            val containers = all.filter { n ->
                val b = n.optJSONArray("bounds")
                val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: 0
                b != null && height > 0 && b.optInt(3) - b.optInt(1) < height * 0.65 &&
                    b.optInt(0) <= bounds.optInt(0) && b.optInt(1) <= bounds.optInt(1) && b.optInt(2) >= bounds.optInt(2) && b.optInt(3) >= bounds.optInt(3) &&
                    names.count { other -> other.optJSONArray("bounds")?.let { ob ->
                        b.optInt(0) <= ob.optInt(0) && b.optInt(1) <= ob.optInt(1) && b.optInt(2) >= ob.optInt(2) && b.optInt(3) >= ob.optInt(3)
                    } == true } == 1
            }.sortedBy { n -> n.getJSONArray("bounds").let { (it.optInt(2) - it.optInt(0)).toLong() * (it.optInt(3) - it.optInt(1)) } }
            val card = containers.firstOrNull { money.containsMatchIn(text(it)) && text(it).length <= 1800 } ?: continue
            result.put(JSONObject().put("name", name.getString("text")).put("visible_details", text(card).take(1200)))
        }
        return result
    }

    /** Synthetic parser/readback checks only; no app, model or device interaction. */
    fun selfCheck(): String {
        check(HotelCapabilities.selfCheck() == "ok")
        val day = LocalDate.of(2026, 10, 28)
        check(dateMatches("28 Oct ‘26, Wed", day))
        check(dateMatches("CHECK-IN DATE 28 Oct Wed, 2026", day))
        check(!dateMatches("28 Oct Wed, 2027", day))
        check(!dateMatches("28 Oct '27, 2026", day))
        check(!dateMatches("29 Oct Wed, 2026", day))
        check(occupancyMatches("1 Room, 2 Adults & 0 Children", 1, 2, 0))
        check(!occupancyMatches("1 Room, 2 Adults & 1 Child", 1, 2, 0))
        check(!occupancyMatches("1 Room, 2 Adults, 1 Adult, 0 Children", 1, 2, 0))
        val hotel = JSONObject().put("destination", "Porto, Portugal")
        val input = "check-in 2026-10-28 checkout 2026-10-30, 2 adults, 1 room"
        applyOwnerInputs(hotel, input, true)
        check(hotel.getString("check_in") == "2026-10-28" && hotel.getString("check_out") == "2026-10-30")
        check(hotel.getInt("adults") == 2 && hotel.getInt("rooms") == 1)
        check(missingInputs(hotel) == listOf("children"))
        check(hotel.getJSONObject("input_sources").getJSONObject("check_in").getString("request") == input)
        applyOwnerInputs(hotel, "no children", true)
        check(hotel.getInt("children") == 0 && missingInputs(hotel).isEmpty())
        applyOwnerInputs(hotel, "2026-11-02 to 2026-11-04, 3 adults", false)
        check(hotel.getString("check_in") == "2026-10-28" && hotel.getInt("adults") == 2)
        applyOwnerInputs(hotel, "2026-11-02 to 2026-11-04, 3 adults", true)
        check(hotel.getString("check_in") == "2026-11-02" && hotel.getString("check_out") == "2026-11-04" && hotel.getInt("adults") == 3)
        check(missingInputs(JSONObject()).size == 6)
        applyOwnerInputs(hotel, "test search 4 adults", true)
        check(hotel.getJSONObject("input_sources").getJSONObject("adults").getString("basis") == "explicit owner input")
        fun node(rid: String, value: String = "", children: JSONArray = JSONArray()) = JSONObject()
            .put("resource_id", rid).put("text", value).put("package", PKG).put("visible", true).put("children", children)
        fun screen(children: JSONArray): Screen = Screen(PKG, emptyList(), emptyList(), JSONObject()
            .put("package", PKG).put("screen", JSONArray(listOf(1080, 2400)))
            .put("windows", JSONArray().put(JSONObject().put("type", 1).put("root", node("root", children = children)))))
        val form = screen(JSONArray().put(node("location_box", "Porto")).put(node("check_in_date"))
            .put(node("check_out_date")).put(node("room_and_guest_box")))
        check(formVisible(form) && formDestinationMatches(form, "Porto, Portugal"))
        check(!formDestinationMatches(form, "Braga, Portugal"))
        val stay = node("dateGuestText", "28 Oct - 30 Oct, 1 room, 2 Guests")
        val header = node("edit_search_container", children = JSONArray().put(node("title", "Porto")).put(stay))
        val listing = screen(JSONArray().put(header))
        check(!formVisible(listing))
        check(resultHeaderMatches(listing, "Porto, Portugal", day, day.plusDays(2), 1, 2))
        check(!resultHeaderMatches(listing, "Braga, Portugal", day, day.plusDays(2), 1, 2))
        check(!resultHeaderMatches(listing, "Porto, Portugal", day, day.plusDays(2), 1, 3))
        stay.put("text", "28 Oct '27 - 30 Oct '27, 1 room, 2 Guests")
        check(!resultHeaderMatches(listing, "Porto, Portugal", day, day.plusDays(2), 1, 2))
        check(destinationQuery("Porto, Portugal") == "Porto")
        fun row(city: String, country: String) = node("", children = JSONArray().put(node("title", city)).put(node("sub_title", country)))
            .put("clickable", true).put("enabled", true).put("bounds", JSONArray(listOf(0, 469, 1080, 645)))
        val correct = row("Porto", "City in Portugal")
        val wrongCity = row("Madrid", "Spain")
        val queryOnly = node("", "Porto").put("clickable", true).put("enabled", true)
            .put("bounds", JSONArray(listOf(0, 316, 1080, 469)))
        check(destinationSuggestion(screen(JSONArray().put(queryOnly).put(wrongCity).put(correct)), "Porto, Portugal")?.label == "Porto City in Portugal")
        check(destinationSuggestion(screen(JSONArray().put(wrongCity)), "Porto, Portugal") == null)
        check(destinationSuggestion(screen(JSONArray().put(row("Porto", "Spain"))), "Porto, Portugal") == null)
        check(destinationSuggestion(screen(JSONArray().put(queryOnly)), "Porto, Portugal") == null)
        check(destinationSuggestion(screen(JSONArray().put(correct).put(row("Porto", "Portugal"))), "Porto, Portugal") == null)
        val namedStay = HotelCapabilities.Stay(PKG, "Porto, Portugal", day, day.plusDays(2), 2, 1, 0, emptyList())
        val formForProof = screen(JSONArray().put(node("location_box", "Porto, Portugal")).put(node("check_in_date", "28 Oct 2026"))
            .put(node("check_out_date", "30 Oct 2026")).put(node("room_and_guest_box", "1 Room, 2 Adults, 0 Children")))
        stay.put("text", "28 Oct - 30 Oct, 1 room, 2 Guests")
        val hotelName = node("hotel_name", "Synthetic Riverside Hotel").put("bounds", JSONArray(listOf(20, 500, 600, 560)))
        val price = node("price", "INR 5,000 per night").put("bounds", JSONArray(listOf(20, 580, 600, 640)))
        val card = node("hotel_card", children = JSONArray().put(hotelName).put(price)).put("bounds", JSONArray(listOf(0, 480, 1080, 700)))
        val actualResults = screen(JSONArray().put(header).put(card))
        check(readResults(actualResults).length() == 1)
        val output = namedStay.json().put("results", readResults(actualResults)).put("scope", "visible_result_cards").put("reservation_made", false)
        val evidence = baseEvidence(JSONObject()).put("proof_kind", "verified_form_and_results").put("form_state", formForProof.state)
            .put("form_captured_at", 1L).put("search_dispatched_at", 2L).put("results_state", actualResults.state)
        val proof = CapabilityResult(output = output, evidence = evidence)
        check(verificationError(namedStay, proof) == null)
        check(inspect(actualResults, namedStay, JSONObject()) == null) // The result header alone omits year/breakdown.
        formForProof.state!!.getJSONArray("windows").getJSONObject(0).getJSONObject("root").getJSONArray("children")
            .getJSONObject(1).put("text", "28 Oct 2027")
        check(verificationError(namedStay, proof) != null)
        check(!formVisible(Screen("another.app", emptyList(), emptyList(), formForProof.state)))
        return "ok"
    }
}
