package dev.layatest.agent

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar fields remain deterministic; incomplete requests need a clarification before dispatch. */
internal object CalendarRequest {
    data class Event(
        val title: String,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val allDay: Boolean,
        val inferredYear: Boolean
    )

    data class Result(val event: Event? = null, val error: String? = null)

    private fun rx(value: String) = Regex(value, RegexOption.IGNORE_CASE)
    private const val CALENDAR = "cal(?:endar|ender|ander)"
    private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val ORDINAL = "(?:st|nd|rd|th)?"
    private val quoted = Regex("[\"“]([^\"”]+)[\"”]|'([^'\\n]+)'")
    private val monthDay = rx("\\b($MONTH)\\.?\\s*(?:the\\s+)?(\\d{1,2})$ORDINAL(?!\\d)(?:(?:\\s*,\\s*|\\s+)(\\d{4})\\b)?")
    private val dayMonth = rx("(?<!\\d)(\\d{1,2})$ORDINAL\\s+(?:of\\s+)?($MONTH)\\.?(?![a-z])(?:(?:\\s*,\\s*|\\s+)(\\d{4})\\b)?")
    private val isoDate = Regex("(?<![\\d-])(\\d{4})-(\\d{2})-(\\d{2})(?![\\d-])")
    private val relativeDate = rx("\\b(day after tomorrow|today|tomorrow)\\b")
    private val clock = rx("(?<![\\w:])(?:\\d{1,2}(?::\\d{2})?\\s*[ap]\\.?m\\.?|\\d{1,2}:\\d{2})(?![\\w:])|\\b(?:noon|midnight)\\b")
    private val duration = rx("\\bfor\\s+((?:\\d+(?:\\.\\d+)?\\s*(?:hours?|hrs?|minutes?|mins?)\\b\\s*(?:and\\s*)?)+)")
    private val durationPart = rx("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?)\\b")
    private val allDay = rx("\\b(?:all[ -]day|whole day|entire day)\\b")
    private val block = rx("\\b(?:block|reserve)\\b")
    private val recurrence = rx("\\b(?:every|daily|weekly|monthly|yearly|annually|weekdays?|weekends?|recurr\\w*|repeat\\w*)\\b")
    private val creation = rx("\\b(?:schedule|create|add|make)\\b.*\\b(?:event|appointment|meeting)\\b")
    private val unsaved = rx("\\b(?:do\\s+not|don['’]t|never)\\s+(?:save|create|add|schedule|block|commit)\\b|" +
        "\\bwithout\\s+(?:saving|creating|adding|scheduling|blocking)\\b|\\b(?:draft|preview|review)[ -]only\\b|" +
        "\\bbefore\\s+(?:you\\s+)?(?:save|saving|create|creating|add|adding|schedule|scheduling|block|blocking)\\b|" +
        "\\b(?:let\\s+me\\s+(?:review|approve)|(?:show|ask|confirm|review)\\b.*\\bfirst)\\b")

    fun matches(request: String): Boolean {
        val text = request.trim().replace(rx("^(?:(?:hey\\s+)?friday[, ]+)?(?:please\\s+|(?:can|could|will|would) you\\s+)*"), "")
        // A message or search containing an event instruction is still a message or search.
        if (rx("^(?:write|draft|compose|message|text|send|e-?mail|search|google|look up|research|summari[sz]e|tell)\\b").containsMatchIn(text)) return false
        return rx("^(?:block|reserve)\\s+(?:(?:out|off|my|the|a|some)\\s+)*(?:(?:(?:google|work|personal|shared|family)\\s+)?$CALENDAR|day|time|\\d)\\b").containsMatchIn(text) ||
            rx("^(?:schedule|create|add|make)\\b.*\\b(?:$CALENDAR|event|appointment|meeting)\\b").containsMatchIn(text) ||
            (rx("^open\\b.*\\b$CALENDAR\\b").containsMatchIn(text) &&
                (block.containsMatchIn(text) || creation.containsMatchIn(text)))
    }

    fun parse(request: String, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Result {
        if (!matches(request)) return Result()
        val text = request.trim().replace('–', '-').replace('—', '-')
        val quotes = quoted.findAll(text).toList()
        var fields = mask(text, quotes.map { it.range })
        fun fail(message: String) = Result(error = message)
        if (unsaved.containsMatchIn(fields)) return fail("I haven't saved an event. Please confirm when you want me to create it; calendar previews are not supported yet.")
        if (recurrence.containsMatchIn(fields)) return fail("Please create this as a single event, or specify a recurrence after recurrence support is available.")
        if (rx("\\b(?:except|excluding|unless|not all[ -]day|not the whole day)\\b").containsMatchIn(fields))
            return fail("Please clarify the requested exception before I create the event.")
        if (rx("\\b(?:remind(?:er)?|notify|notification|attendees?|invite|guests?|location|address)\\b").containsMatchIn(fields))
            return fail("I need to clarify the requested reminder, location or attendees before creating this event.")
        if (rx("\\b(?:work|personal|shared|family)\\s+$CALENDAR\\b|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}").containsMatchIn(fields))
            return fail("Which calendar account should I use? Selecting a named account is not supported yet.")

        data class DateField(val value: LocalDate, val range: IntRange, val inferred: Boolean)
        val dates = mutableListOf<DateField>()
        var invalidDate = false
        fun date(year: Int?, month: Int, day: Int, range: IntRange) {
            val resolved = if (year != null) runCatching { LocalDate.of(year, month, day) }.getOrNull() else {
                // The next February 29 can be several years away.
                (today.year..today.year + 8).firstNotNullOfOrNull { y ->
                    runCatching { LocalDate.of(y, month, day) }.getOrNull()?.takeIf { !it.isBefore(today) }
                }
            }
            if (resolved == null) invalidDate = true else dates += DateField(resolved, range, year == null)
        }
        isoDate.findAll(fields).forEach { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.range) }
        monthDay.findAll(fields).forEach { m -> date(m.groupValues[3].toIntOrNull(), monthNumber(m.groupValues[1]), m.groupValues[2].toInt(), m.range) }
        dayMonth.findAll(fields).filter { m -> dates.none { overlap(it.range, m.range) } }.forEach { m ->
            date(m.groupValues[3].toIntOrNull(), monthNumber(m.groupValues[2]), m.groupValues[1].toInt(), m.range)
        }
        relativeDate.findAll(fields).forEach { m ->
            val days = when (m.value.lowercase(Locale.ROOT)) { "today" -> 0L; "tomorrow" -> 1L; else -> 2L }
            dates += DateField(today.plusDays(days), m.range, false)
        }
        if (invalidDate) return fail("That date does not exist. Please give a valid day, month and year.")
        if (dates.isEmpty()) return fail("What date should I block? Please use a month name, YYYY-MM-DD, today or tomorrow.")
        if (dates.map { it.value }.distinct().size != 1) return fail("Please specify one event date. I cannot silently choose between multiple dates.")
        val day = dates.first().value
        if (day.isBefore(today)) return fail("That explicit date is in the past. Please confirm the intended future date.")
        fields = mask(fields, dates.map { it.range })
        if (rx("\\b(?:\\d{1,4}[/\\-]\\d{1,2}(?:[/\\-]\\d{1,4})?|\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})\\b").containsMatchIn(fields))
            return fail("Please write numeric dates as YYYY-MM-DD, or spell out the month so the date is unambiguous.")
        if (rx("\\b(?:this|next) year\\b|\\b\\d{4}\\b").containsMatchIn(mask(fields, duration.findAll(fields).map { it.range }.toList())))
            return fail("Please put the exact year next to the date.")
        if (dates.any { d -> rx("^\\s*(?:-|to|through|until|and|or)\\s*(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\b").containsMatchIn(text.substring(d.range.last + 1)) })
            return fail("Please specify one event date; date ranges need clarification.")
        for (d in dates) {
            val weekday = rx("\\b(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\\s*,?\\s*$").find(text.substring(0, d.range.first))
            if (weekday != null && DayOfWeek.of(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").indexOf(weekday.groupValues[1].take(3).lowercase(Locale.ROOT)) + 1) != day.dayOfWeek)
                return fail("The weekday does not match that date. Which one did you mean?")
        }

        val zones = rx("\\b(?:[A-Za-z_]+/[A-Za-z_/]+|(?:UTC|GMT)(?:[+-]\\d{1,2}(?::\\d{2})?)?|EST|EDT|PST|PDT|CST|CDT|MST|MDT|IST|JST|BST|CET|CEST|AEST|AEDT|NZST|NZDT)\\b").findAll(fields).toList()
        val localShort = day.atStartOfDay(zone).format(DateTimeFormatter.ofPattern("z", Locale.US))
        if (zones.any { z -> !z.value.equals(zone.id, true) && !z.value.equals(localShort, true) &&
                !runCatching { ZoneId.of(z.value.uppercase(Locale.ROOT), ZoneId.SHORT_IDS).rules == zone.rules }.getOrDefault(false) &&
                !(z.value.equals("UTC", true) && day.atStartOfDay(zone).offset.totalSeconds == 0) })
            return fail("I use the device time zone (${zone.id}). Please convert the requested time zone or change the device zone first.")
        fields = mask(fields, zones.map { it.range })
        val localTimePhrases = setOf("block", "reserve", "start", "end", "local", "device")
        val namedZone = rx("\\b([a-z]+)(?:\\s+(?:standard|daylight))?\\s+time\\b").findAll(fields)
            .any { it.groupValues[1].lowercase(Locale.ROOT) !in localTimePhrases }
        val timeGrammar = setOf("at", "and", "to", "until", "from", "on", "for", "named", "next", "the", "all", "with", "by", "in", "local")
        val unknownShortZone = clock.findAll(fields).any { c ->
            rx("^\\s*\\(?([a-z]{2,5})\\)?(?=\\s|$)").find(fields.substring(c.range.last + 1))
                ?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { it !in timeGrammar } == true
        }
        if (namedZone || unknownShortZone || rx("\\b(?:timezone|time zone)\\b").containsMatchIn(fields))
            return fail("Please confirm the event times in the device time zone (${zone.id}).")
        fields = mask(fields, rx("\\b(?:local|device)\\s+time\\b").findAll(fields).map { it.range }.toList())

        if (rx("\\b(?:seconds|secs)\\b|\\b\\d+(?:\\.\\d+)?\\s*(?:second|sec)\\b|\\b(?:for|and)\\s+(?:a|an|one)\\s+(?:second|sec)\\b").containsMatchIn(fields))
            return fail("Calendar durations currently support whole minutes. Please give a duration without seconds.")
        val durations = duration.findAll(fields).toList()
        if (durations.size > 1) return fail("Please give one duration or one end time.")
        val minutes = durations.firstOrNull()?.let { d ->
            runCatching {
                durationPart.findAll(d.value).fold(BigDecimal.ZERO) { sum, part ->
                    sum + part.groupValues[1].toBigDecimal() * if (part.groupValues[2].startsWith("h", true)) BigDecimal(60) else BigDecimal.ONE
                }.longValueExact()
            }.getOrNull()
        }
        if (durations.isNotEmpty() && (minutes == null || minutes <= 0)) return fail("Please give a positive duration in whole minutes.")
        fields = mask(fields, durations.map { it.range })
        val clocks = clock.findAll(fields).toList()
        if (clocks.size > 2) return fail("Please give just one start time and one end time.")
        val explicitAllDay = allDay.containsMatchIn(fields)
        val shouldBlock = block.containsMatchIn(fields)
        val times = clocks.map { time(it.value) }
        if (times.any { it == null }) return fail("Please give valid times with AM/PM or 24-hour HH:mm notation.")
        if (rx("\\b(?:at|from|between|starting(?: at)?)\\s+\\d").containsMatchIn(mask(fields, clocks.map { it.range })))
            return fail("Please give each time with AM/PM or 24-hour HH:mm notation.")
        if (explicitAllDay && (clocks.isNotEmpty() || durations.isNotEmpty())) return fail("Should this be all day, or use the specified times?")
        if (clocks.isEmpty() && (rx("\\b(?:morning|afternoon|evening|night|noon|midnight|hours?|minutes?)\\b").containsMatchIn(fields) || durations.isNotEmpty()))
            return fail("What start time should I use? Please give AM/PM or 24-hour HH:mm notation.")
        val isAllDay = clocks.isEmpty() && (explicitAllDay || shouldBlock)
        if (!isAllDay && clocks.isEmpty()) return fail("Should this be all day, or what start and end times should I use?")
        if (!isAllDay && clocks.size == 1 && minutes == null) return fail("What end time or duration should I use?")
        if (clocks.size == 2) {
            val between = fields.substring(clocks[0].range.last + 1, clocks[1].range.first)
            if (!rx("^\\s*(?:-|to|until|through|and)\\s*$").matches(between)) return fail("Please write a single time range, such as from 9 AM to 10 AM.")
        }
        val cleaned = mask(fields, clocks.map { it.range })
        val withoutApp = cleaned.replace(rx("\\b(?:in|using|with)\\s+(?:(?:my|the|google)\\s+)*$CALENDAR(?:\\s+app)?\\b"), "")
        if (rx("\\b(?:at|in)\\s+(?!(?:for|on|from|to|until|and|all|whole|entire)\\b)[a-z]").containsMatchIn(withoutApp))
            return fail("I need to clarify the requested place or other event detail before creating the event.")
        if (rx("\\b(?:for|until|to)\\s+\\d|\\b(?:next|following)\\s+(?:week|month|year)\\b").containsMatchIn(cleaned))
            return fail("I could not interpret all the requested date or duration information. Please give an exact date and time range.")
        val title = title(cleaned, quotes, shouldBlock)
            ?: return fail("What title should I give this event?")
        return try {
            val start = if (isAllDay) day.atStartOfDay(zone) else day.atTime(times.first()!!).atZone(zone)
            val end = when {
                isAllDay -> day.plusDays(1).atStartOfDay(zone)
                clocks.size == 1 -> start.plusMinutes(minutes!!)
                else -> {
                    val endTime = times[1]!!
                    val explicitNextDay = rx("\\b(?:next day|following day|overnight)\\b").containsMatchIn(fields)
                    val endDay = if (explicitNextDay || (endTime == LocalTime.MIDNIGHT && endTime <= times.first()!!)) day.plusDays(1) else day
                    val endValue = endDay.atTime(endTime).atZone(zone)
                    if (!endValue.isAfter(start)) return fail("The end time is not after the start. Does this end the following day?")
                    if (minutes != null && start.plusMinutes(minutes) != endValue) return fail("The duration and end time disagree. Which should I use?")
                    endValue
                }
            }
            // A daylight-saving gap changes LocalDateTime when atZone resolves it.
            if (!isAllDay && (start.toLocalTime() != times.first() || (clocks.size == 2 && end.toLocalTime() != times[1])))
                return fail("That time does not exist because the clocks change. Please choose another time.")
            if (!isAllDay && (zone.rules.getValidOffsets(start.toLocalDateTime()).size != 1 || zone.rules.getValidOffsets(end.toLocalDateTime()).size != 1))
                return fail("That time is ambiguous because the clocks change. Please choose an unambiguous time.")
            Result(Event(title, start, end, isAllDay, dates.any { it.inferred }))
        } catch (_: DateTimeException) {
            fail("The date or duration is outside the supported calendar range.")
        } catch (_: ArithmeticException) {
            fail("That duration is too large. Please give a shorter duration.")
        }
    }

    private fun title(cleaned: String, quotes: List<MatchResult>, blocked: Boolean): String? {
        if (quotes.size == 1) return quotes.single().groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        if (quotes.size > 1) return null
        val marker = rx("\\b(?:called|titled|named|for)\\s+(?:(?:my|the|a|an)\\s+)?(.+)").find(cleaned)
        val selected = marker?.groupValues?.get(1) ?: if (blocked) return "Busy" else cleaned
            .replace(rx("^(?:(?:hey\\s+)?friday[, ]+)?(?:please\\s+|(?:can|could|will|would) you\\s+)*(?:schedule|create|add|make)\\s+(?:(?:a|an|the|my|new)\\s+)*(?:(?:event|appointment|meeting)\\s+)?"), "")
        return selected
            .replace(rx("\\b(?:in|to|on|using|with)\\s+(?:(?:my|the|google)\\s+)*$CALENDAR(?:\\s+app)?\\b.*$"), "")
            .replace(rx("\\b(?:on|at|from|starting|between|all[ -]day|whole day|entire day|next day|following day|overnight)\\b.*$"), "")
            .trim(' ', '.', ',', ':', '-', ';')
            .takeIf { it.isNotBlank() && !it.equals("event", true) && !it.equals("appointment", true) }
    }

    private fun monthNumber(value: String): Int = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(value.take(3).lowercase(Locale.ROOT)) + 1
    private fun overlap(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last
    private fun mask(value: String, ranges: List<IntRange>): String {
        val chars = value.toCharArray()
        ranges.forEach { range -> range.forEach { chars[it] = ' ' } }
        return String(chars)
    }

    private fun time(value: String): LocalTime? {
        val s = value.lowercase(Locale.ROOT).replace(Regex("[ .]"), "")
        if (s == "midnight") return LocalTime.MIDNIGHT
        if (s == "noon") return LocalTime.NOON
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?([ap]m)?").matchEntire(s) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3]
        if (minute !in 0..59 || (suffix.isEmpty() && hour !in 0..23) || (suffix.isNotEmpty() && hour !in 1..12)) return null
        return LocalTime.of(if (suffix.isEmpty()) hour else hour % 12 + if (suffix == "pm") 12 else 0, minute)
    }

    fun selfCheck(): String {
        val today = LocalDate.of(2026, 10, 20)
        val zone = ZoneId.of("Asia/Kolkata")
        fun parse(text: String) = parse(text, today, zone)
        fun event(text: String): Event = parse(text).let { check(it.error == null) { "$text: ${it.error}" }; checkNotNull(it.event) }
        fun reject(text: String) { check(parse(text).error != null) { "Accepted: $text" } }
        val review = event("block my calendar on Jan 7 for my QA review")
        check(review.title == "QA review" && review.allDay && review.inferredYear)
        check(review.start.toLocalDate() == LocalDate.of(2027, 1, 7) && review.end.toLocalDate() == LocalDate.of(2027, 1, 8))
        val open = event("open calander app and go to January 7 and block the day")
        check(open.title == "Busy" && open.allDay && open.start == review.start)
        check(event("block my calender on 7th of January for QA review").start == review.start)
        check(event("block my calendar on Jan 7th, 2028").start.year == 2028)
        check(event("block my calendar on 2027-01-07").start == review.start)
        check(event("block my calendar tomorrow").start.toLocalDate() == today.plusDays(1))
        check(event("block my calendar today").start.toLocalDate() == today)
        check(event("block my calendar on February 29").start.toLocalDate() == LocalDate.of(2028, 2, 29))
        check(event("create an event called QA review on January 7 at 9 AM for 1 hour").let { it.title == "QA review" && !it.allDay && it.start.hour == 9 && it.end.hour == 10 })
        check(event("add an event \"QA review\" on 7 Jan from 13:30 to 14:45").let { it.title == "QA review" && it.start.minute == 30 && it.end.hour == 14 && it.end.minute == 45 })
        check(event("block time on January 7 from 11 PM to midnight").let { it.end.toLocalDate() == it.start.toLocalDate().plusDays(1) && it.end.hour == 0 })
        check(event("block time on January 7 at noon for 1 hour and 30 minutes").end.toLocalTime() == LocalTime.of(13, 30))
        check(event("block time on January 7 at 12 AM for 30 minutes").start.hour == 0)
        check(event("block time on January 7 at 9 AM IST for 1.5 hours").end.toLocalTime() == LocalTime.of(10, 30))
        for (qualifier in listOf("next day", "following day", "overnight")) {
            check(event("block time on January 7 from 9 AM to 10 AM $qualifier").let {
                it.start.toLocalDate() == LocalDate.of(2027, 1, 7) &&
                    it.end.toLocalDate() == LocalDate.of(2027, 1, 8) && it.end.hour == 10
            })
        }
        for (command in listOf("create an event", "add a meeting", "schedule an appointment", "make an event")) {
            check(event("open Calendar and $command called QA review on January 7 all day").let {
                it.title == "QA review" && it.allDay && it.start == review.start
            })
        }
        check(event("block time on January 7 at 9 AM local time for 1 hour").end.hour == 10)
        check(event("block time on January 7 from 9 AM to 10 AM called \"Overnight review\"").end.toLocalDate() == review.start.toLocalDate())
        reject("block my calendar on February 30")
        reject("block my calendar on April 31")
        reject("block my calendar on January 7 2026")
        reject("block my calendar on 01/07/2027")
        reject("block my calendar on January 7 and January 8")
        reject("block my calendar on January 7 and 8")
        reject("block my calendar on January 7 at 9")
        reject("block time on January 7 at 9 AM")
        reject("create an event called QA review on January 7")
        reject("create an event called QA review at 9 AM for 1 hour")
        reject("block time on January 7 from 9 AM to 8 AM")
        reject("block time on January 7 at 13 PM for 1 hour")
        reject("block time on January 7 from 9 AM to 10 AM for 2 hours")
        reject("block time on January 7 at 9 AM for 0 minutes")
        reject("block time on January 7 at 9 AM for 1 hour and 30 seconds")
        reject("block time on January 7 at 9 AM for 1 hour and thirty seconds")
        reject("block time on January 7 at 9 AM for 1 hour and a second")
        reject("block time on January 7 from 9 AM to 10 AM next day for 1 hour")
        reject("block my calendar all day on January 7 at 9 AM for 1 hour")
        reject("block my calendar every January 7")
        reject("block time on January 7 at 9 AM PST for 1 hour")
        reject("block time on January 7 at 9 AM Nepal time for 1 hour")
        reject("block time on January 7 using Nepal time at 9 AM for 1 hour")
        reject("block time on January 7 at 9 AM NPT for 1 hour")
        reject("block time on January 7 at 9 AM npt for 1 hour")
        reject("block my calendar on January 7 but do not save yet")
        reject("block my calendar on January 7 but don't save it")
        reject("block my calendar on January 7 without saving")
        reject("block my calendar on January 7, preview only")
        reject("block my calendar on January 7, review-only")
        reject("block my calendar on January 7, let me review before saving")
        reject("block my calendar on January 7, show me first")
        reject("block my work calendar on January 7")
        reject("block my calendar on Monday January 7")
        reject("block my calendar on January 7 except lunch")
        reject("block my calendar on January 7 but not all day")
        reject("block my calendar on January 7 at the office")
        reject("block time on January 7 from 9 AM to 10 AM in Bengaluru")
        check(event("block my Google calendar on January 7 for my QA review").title == "QA review")
        check(event("create an event called QA review on January 7 at 9 AM for 1 hour in Google Calendar").title == "QA review")
        for (month in listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")) {
            check(event("block my calendar on 7 $month").start.monthValue == monthNumber(month))
            check(event("block my calendar on ${month.take(3)} 7").start.monthValue == monthNumber(month))
        }
        listOf("open Calendar", "message Sam to block my calendar on Jan 7", "search for calendar events in January", "write an appointment email", "What is on my calendar tomorrow?").forEach {
            check(!matches(it) && parse(it).event == null)
        }
        return "ok"
    }
}
