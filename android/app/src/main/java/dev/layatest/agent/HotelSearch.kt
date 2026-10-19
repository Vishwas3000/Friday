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

    fun run(ctx: SkillContext, state: JSONObject, navigate: (AppInfo, String, String?) -> Outcome): String {
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
        if (find(screen, "btnDone") != null && !ctx.back()) return pending(state, "Return to the hotel search form to continue.")
        screen = ctx.screen()
        if (find(screen, "hotel_landing_main_screen") == null) {
            if (navigate(app, "open the Hotels search form only; do not search, select a hotel, reserve or pay", null) == Outcome.STOPPED || ctx.stopped)
                return "Stopped. The hotel search is saved."
            screen = ctx.screen()
        }
        if (screen.pkg != PKG || find(screen, "hotel_landing_main_screen") == null)
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
            if (navigate(app, "enter the supplied destination and select its matching city/locality suggestion; return to the hotel form; do not search or book", destination) == Outcome.STOPPED || ctx.stopped)
                return "Stopped. The hotel search is saved."
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
        ctx.waitUntil(10000) {
            screen = ctx.screen()
            results = readResults(screen)
            results.length() > 0
        }
        if (ctx.stopped) return "Stopped. The hotel search is saved."
        state.put("hotel_results", results).put("hotel_results_captured_at", System.currentTimeMillis())
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

    private fun missingInputs(hotel: JSONObject) = listOf("destination", "check_in", "check_out", "adults", "rooms", "children").filter { key ->
        when (key) {
            "adults", "rooms" -> hotel.optInt(key, 0) < 1
            "children" -> hotel.isNull(key) || hotel.optInt(key, -1) < 0
            else -> (hotel.opt(key) as? String).isNullOrBlank()
        }
    }

    /** Only explicit owner facts enter the stay; Gemini's answer is never parsed here. */
    private fun applyOwnerInputs(hotel: JSONObject, request: String, overwrite: Boolean) {
        val sources = hotel.optJSONObject("input_sources") ?: JSONObject().also { hotel.put("input_sources", it) }
        fun put(key: String, value: Any) {
            if (overwrite || hotel.isNull(key)) {
                hotel.put(key, value)
                sources.put(key, JSONObject().put("basis", "explicit owner input").put("request", request.take(2000)))
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
        val b = node.optJSONArray("bounds") ?: return false
        if (b.length() != 4 || b.optInt(2) <= b.optInt(0) || b.optInt(3) <= b.optInt(1)) return false
        val target = ScreenCandidate(text(node).take(100), node.optString("class").substringAfterLast('.'), node.optBoolean("clickable"),
            node.optBoolean("long_clickable"), node.optBoolean("editable"), node.optBoolean("scrollable"), node.optBoolean("checkable"),
            node.optBoolean("checked"), node.optBoolean("selected"), true, IntArray(4) { b.getInt(it) }, id(node))
        return ctx.tap(target)
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
        if (screen.pkg != PKG || find(screen, "hotel_landing_main_screen") != null) return result
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
        return "ok"
    }
}
