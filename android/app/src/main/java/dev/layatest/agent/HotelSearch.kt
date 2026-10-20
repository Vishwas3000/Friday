package dev.layatest.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A bounded search, ending at results. App defaults never supply missing stay details. */
internal object HotelSearch {
    private const val PKG = "com.makemytrip"
    private val money = Regex("(?:₹|INR|NPR|Rs\\.?)\\s*[\\d,]+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
    private val month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun run(ctx: SkillContext, state: JSONObject, typeDestination: (ScreenCandidate, String) -> Boolean,
            navigate: (AppInfo, String, String?) -> Outcome): String {
        val hotel = state.optJSONObject("hotel") ?: return pending(state, "Missing saved hotel details.")
        applyOwnerInputs(hotel, state.optString("original_request"), false)
        applyOwnerInputs(hotel, state.optString("latest_request"), true)
        applyOwnerInputs(hotel, ctx.request, true)
        val missing = missingInputs(hotel)
        val app = ctx.apps.installed.firstOrNull { it.pkg == PKG }
            ?: return pending(state, "MakeMyTrip is not installed; the hotel search is saved.")
        state.put("hotel_results_verified", false).put("stage", "hotel_search_pending")
        if (ctx.stopped) return "Stopped. The hotel search is saved."
        ctx.say("Opening MakeMyTrip hotels…")
        if (!ctx.open(app) || !ctx.waitUntil(5000) { ctx.foreground() == PKG })
            return pending(state, "MakeMyTrip could not be opened.")
        var screen = ctx.screen()
        if ((find(screen, "btnDone") != null || destinationInput(screen) != null || find(screen, "done_button") != null) && !ctx.back())
            return pending(state, "Return to the hotel search form to continue.")
        screen = ctx.screen()
        screen = revealResultsHeader(ctx, screen)
        repeat(3) {
            if (!formVisible(screen) && !ctx.stopped) {
                val edit = find(screen, "edit_search_container") ?: find(screen, "edit_icon")
                val hotels = nodes(screen).firstOrNull { it.optString("text").trim().equals("Hotels", true) && it.optBoolean("enabled") }
                val target = edit ?: hotels
                if (target != null && tapNode(ctx, screen, target)) {
                    ctx.waitUntil(3500) { screen = ctx.screen(); formVisible(screen) }
                    screen = ctx.screen()
                }
            }
        }
        if (!formVisible(screen)) {
            if (navigate(app, "open the Hotels search form only; do not search, select a hotel, reserve or pay", null) == Outcome.STOPPED || ctx.stopped)
                return "Stopped. The hotel search is saved."
            screen = ctx.screen()
        }
        if (!formVisible(screen))
            return pending(state, "The MakeMyTrip hotel form was not verified.")
        if (missing.isNotEmpty()) {
            state.put("missing_hotel_inputs", JSONArray(missing)).put("stage", "awaiting_hotel_inputs")
            return "MakeMyTrip's hotel form is open. Hotel search pending: please provide ${missing.joinToString(", ")}. The saved research and supplied stay details are preserved."
        }
        val destination = hotel.getString("destination").trim()
        val checkIn = runCatching { LocalDate.parse(hotel.getString("check_in")) }.getOrNull()
        val checkOut = runCatching { LocalDate.parse(hotel.getString("check_out")) }.getOrNull()
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn))
            return pending(state, "Provide valid ISO check-in and checkout dates; checkout must be after check-in.")
        if (checkIn.isBefore(LocalDate.now())) return pending(state, "The saved check-in date is in the past. Please provide new dates.")
        val adults = hotel.getInt("adults")
        val rooms = hotel.getInt("rooms")
        val children = hotel.getInt("children")
        if (adults < rooms || adults > 30 || rooms > 10 || children > 20)
            return pending(state, "Please confirm the occupancy: $rooms rooms, $adults adults, $children children.")
        if (!destinationMatches(field(screen, "location_box"), destination)) {
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
            state.put("hotel_destination_evidence", JSONObject().put("query", query).put("selected_suggestion", suggestion.label)
                .put("captured_at", System.currentTimeMillis()))
            if (ctx.stopped || ctx.foreground() != PKG || !ctx.tap(suggestion))
                return pending(state, "The verified destination suggestion could not be selected.")
            ctx.waitUntil(4000) { formDestinationMatches(ctx.screen(), destination) }
            screen = ctx.screen()
            if (!destinationMatches(field(screen, "location_box"), destination))
                return pending(state, "The destination was not verified as $destination. Please select the matching locality/city in MakeMyTrip.")
        }
        state.put("hotel_step", "dates")
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
        state.put("hotel_step", "occupancy")
        if (!occupancyMatches(field(screen, "room_and_guest_box"), rooms, adults, children)) {
            ctx.say("Setting rooms and guests…")
            if (!tap(ctx, screen, "room_and_guest_box")) return pending(state, "The rooms and guests picker could not be opened.")
            val goal = "set exactly $rooms rooms, $adults adults and $children children; apply the guest picker and return to the hotel form only; do not search, select hotels, reserve or pay; stop if child ages are required and unknown"
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
        state.put("hotel_form_evidence", evidence)
        if (screen.pkg != PKG || !destinationMatches(evidence.getString("destination"), destination) ||
            !dateMatches(evidence.getString("check_in"), checkIn) || !dateMatches(evidence.getString("check_out"), checkOut) ||
            !occupancyMatches(evidence.getString("occupancy"), rooms, adults, children))
            return pending(state, "The final search form does not match all supplied stay details. Search was not pressed.")
        ctx.say("Searching verified hotel stay…")
        state.put("hotel_step", "results").put("hotel_form_verified", true)
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
        state.put("hotel_results", results).put("hotel_results_captured_at", System.currentTimeMillis())
            .put("hotel_result_header_evidence", JSONObject().put("destination_and_stay", field(screen, "edit_search_container"))
                .put("dates_and_guests", field(screen, "dateGuestText"))
                .put("year_and_guest_breakdown_basis", "independently verified pre-search form"))
        if (results.length() == 0) return pending(state, "Search was submitted with verified stay details, but no hotel result cards could be verified. Availability, loading or a network problem may still need attention.")
        state.put("stage", "hotel_search_complete").put("hotel_results_verified", true)
            .put("hotel_result_scope", "visible result cards from one screen; no reservation")
            .put("hotel_budget_verified", false)
        val lines = (0 until results.length()).joinToString("\n") { i ->
            val result = results.getJSONObject(i)
            "• ${result.getString("name")}: ${result.getString("visible_details") }"
        }
        val budget = if (!hotel.isNull("budget") && hotel.optString("budget").isNotBlank())
            "\nBudget filtering was not verified; compare the displayed prices with your supplied budget." else ""
        return "Verified search: $destination, $checkIn to $checkOut; $rooms rooms, $adults adults, $children children.\nVisible hotel results (displayed prices and terms only):\n$lines$budget\nNo reservation was made."
    }

    private fun pending(state: JSONObject, message: String): String {
        state.put("stage", "hotel_search_pending").put("hotel_results_verified", false).put("hotel_pending_reason", message)
        return "Hotel search pending. $message The saved stay details are preserved."
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
        .replace(Regex("\\s+Nepal$", RegexOption.IGNORE_CASE), "").trim()

    private fun destinationInput(screen: Screen): JSONObject? = nodes(screen).filter {
        id(it) == "input_text" && it.optBoolean("editable") && it.optBoolean("enabled")
    }.singleOrNull()

    /** Known city rows: title and country subtitle must belong to the same bounded clickable row. */
    private fun destinationSuggestion(screen: Screen, destination: String): ScreenCandidate? {
        val query = normalized(destinationQuery(destination))
        val expectedCountry = destination.substringAfter(',', "").trim()
        val height = screen.state?.optJSONArray("screen")?.optInt(1) ?: return null
        val rows = nodes(screen).filter { row ->
            val bounds = row.optJSONArray("bounds")
            val children = row.optJSONArray("children")
            if (!row.optBoolean("clickable") || !row.optBoolean("enabled") || bounds == null ||
                bounds.optInt(3) - bounds.optInt(1) >= height / 3 || children == null) return@filter false
            val items = (0 until children.length()).map { children.getJSONObject(it) }
                .filter { it.optBoolean("visible") && it.optString("package") == PKG }
            val titles = items.filter { id(it) == "title" }
            val country = items.filter { id(it) == "sub_title" }.joinToString(" ") { it.optString("text") }
            titles.size == 1 && normalized(titles.single().optString("text")) == query &&
                (expectedCountry.isBlank() || Regex("\\b${Regex.escape(expectedCountry)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(country))
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
        return Regex("\\b$rooms\\s+rooms?\\b", RegexOption.IGNORE_CASE).containsMatchIn(stay) &&
            Regex("\\b$guests\\s+guests?\\b", RegexOption.IGNORE_CASE).containsMatchIn(stay)
    }

    private fun missingInputs(hotel: JSONObject) = listOf("destination", "check_in", "check_out", "adults", "rooms", "children").filter { key ->
        when (key) {
            "adults", "rooms" -> hotel.optInt(key, 0) < 1
            "children" -> hotel.isNull(key) || hotel.optInt(key, -1) < 0
            else -> (hotel.opt(key) as? String).isNullOrBlank()
        }
    }

    /** Only explicit owner facts enter the stay; Gemini's answer is never parsed here. */
    internal fun applyOwnerInputs(hotel: JSONObject, request: String, overwrite: Boolean) {
        val sources = hotel.optJSONObject("input_sources") ?: JSONObject().also { hotel.put("input_sources", it) }
        fun put(key: String, value: Any) {
            if (overwrite || hotel.isNull(key)) {
                hotel.put(key, value)
                val basis = if (Regex("\\btest\\s*search\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)) "diagnostic test setup" else "explicit owner input"
                sources.put(key, JSONObject().put("basis", basis).put("request", request.take(2000)))
            }
        }
        val range = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\s*(?:to|until|through|–|—)\\s*(\\d{4}-\\d{2}-\\d{2})\\b", RegexOption.IGNORE_CASE).find(request)
        range?.let { put("check_in", it.groupValues[1]); put("check_out", it.groupValues[2]) }
        for ((key, pattern) in listOf("check_in" to "check[ -]?in", "check_out" to "check[ -]?out")) {
            Regex("\\b$pattern\\s*(?:date|on|is|:|=)?\\s*(\\d{4}-\\d{2}-\\d{2})\\b", RegexOption.IGNORE_CASE)
                .find(request)?.let { put(key, it.groupValues[1]) }
        }
        for ((key, noun) in listOf("adults" to "adults?", "rooms" to "rooms?", "children" to "(?:children|child|kids?)")) {
            Regex("\\b(\\d{1,2})\\s+$noun\\b", RegexOption.IGNORE_CASE).find(request)?.let { put(key, it.groupValues[1].toInt()) }
        }
        if (Regex("\\b(?:no|zero)\\s+(?:children|kids?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)) put("children", 0)
    }

    private fun nodes(screen: Screen): List<JSONObject> {
        if (screen.pkg != PKG) return emptyList()
        val result = ArrayList<JSONObject>()
        fun walk(n: JSONObject) {
            if (n.optBoolean("visible") && n.optString("package") == PKG) result += n
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
        return (listOf(own) + (0 until children.length()).map { text(children.getJSONObject(it)) }).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }
    private fun field(screen: Screen, rid: String) = find(screen, rid)?.let(::text).orEmpty()
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun destinationMatches(actual: String, expected: String): Boolean {
        val shown = normalized(actual)
        val wanted = normalized(expected).removeSuffix(" nepal").trim()
        return wanted.isNotBlank() && Regex("(?:^| )${Regex.escape(wanted)}(?: |$)").containsMatchIn(shown)
    }
    private fun dateMatches(actual: String, expected: LocalDate): Boolean {
        val value = normalized(actual)
        return Regex("\\b${expected.dayOfMonth}\\s+${month.format(expected).lowercase(Locale.ENGLISH)}[a-z]*\\b").containsMatchIn(value) &&
            Regex("\\b(?:${expected.year}|${expected.year % 100})\\b").containsMatchIn(value)
    }
    private fun occupancyMatches(actual: String, rooms: Int, adults: Int, children: Int): Boolean =
        Regex("\\b$rooms\\s+rooms?\\b", RegexOption.IGNORE_CASE).containsMatchIn(actual) &&
            Regex("\\b$adults\\s+adults?\\b", RegexOption.IGNORE_CASE).containsMatchIn(actual) &&
            Regex("\\b$children\\s+child(?:ren)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(actual)

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
        val day = LocalDate.of(2026, 10, 28)
        check(dateMatches("28 Oct ‘26, Wed", day))
        check(dateMatches("CHECK-IN DATE 28 Oct Wed, 2026", day))
        check(!dateMatches("28 Oct Wed, 2027", day))
        check(!dateMatches("29 Oct Wed, 2026", day))
        check(occupancyMatches("1 Room, 2 Adults & 0 Children", 1, 2, 0))
        check(!occupancyMatches("1 Room, 2 Adults & 1 Child", 1, 2, 0))
        val hotel = JSONObject().put("destination", "Kathmandu, Nepal")
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
        check(hotel.getJSONObject("input_sources").getJSONObject("adults").getString("basis") == "diagnostic test setup")
        fun node(rid: String, value: String = "", children: JSONArray = JSONArray()) = JSONObject()
            .put("resource_id", rid).put("text", value).put("package", PKG).put("visible", true).put("children", children)
        fun screen(children: JSONArray): Screen = Screen(PKG, emptyList(), emptyList(), JSONObject()
            .put("screen", JSONArray(listOf(1080, 2400)))
            .put("windows", JSONArray().put(JSONObject().put("type", 1).put("root", node("root", children = children)))))
        val form = screen(JSONArray().put(node("location_box", "Kathmandu")).put(node("check_in_date"))
            .put(node("check_out_date")).put(node("room_and_guest_box")))
        check(formVisible(form) && formDestinationMatches(form, "Kathmandu, Nepal"))
        check(!formDestinationMatches(form, "Pokhara, Nepal"))
        val stay = node("dateGuestText", "28 Oct - 30 Oct, 1 room, 2 Guests")
        val header = node("edit_search_container", children = JSONArray().put(node("title", "Kathmandu")).put(stay))
        val listing = screen(JSONArray().put(header))
        check(!formVisible(listing))
        check(resultHeaderMatches(listing, "Kathmandu, Nepal", day, day.plusDays(2), 1, 2))
        check(!resultHeaderMatches(listing, "Pokhara, Nepal", day, day.plusDays(2), 1, 2))
        check(!resultHeaderMatches(listing, "Kathmandu, Nepal", day, day.plusDays(2), 1, 3))
        stay.put("text", "28 Oct '27 - 30 Oct '27, 1 room, 2 Guests")
        check(!resultHeaderMatches(listing, "Kathmandu, Nepal", day, day.plusDays(2), 1, 2))
        check(destinationQuery("Kathmandu, Nepal") == "Kathmandu")
        fun row(city: String, country: String) = node("", children = JSONArray().put(node("title", city)).put(node("sub_title", country)))
            .put("clickable", true).put("enabled", true).put("bounds", JSONArray(listOf(0, 469, 1080, 645)))
        val correct = row("Kathmandu", "City in Nepal")
        val wrongCity = row("Delhi", "India")
        val queryOnly = node("", "Kathmandu").put("clickable", true).put("enabled", true)
            .put("bounds", JSONArray(listOf(0, 316, 1080, 469)))
        check(destinationSuggestion(screen(JSONArray().put(queryOnly).put(wrongCity).put(correct)), "Kathmandu, Nepal")?.label == "Kathmandu City in Nepal")
        check(destinationSuggestion(screen(JSONArray().put(wrongCity)), "Kathmandu, Nepal") == null)
        check(destinationSuggestion(screen(JSONArray().put(row("Kathmandu", "India"))), "Kathmandu, Nepal") == null)
        check(destinationSuggestion(screen(JSONArray().put(queryOnly)), "Kathmandu, Nepal") == null)
        return "ok"
    }
}
