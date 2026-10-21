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

    data class Result(val event: Event? = null, val error: String? = null, val missing: String? = null)

    private fun rx(value: String) = Regex(value, RegexOption.IGNORE_CASE)
    private const val CALENDAR = "cal(?:endar|ender|ander)"
    private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val WEEKDAY = "mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"
    private const val ORDINAL = "(?:st|nd|rd|th)?"
    private val quoted = Regex("[\"“]([^\"”]+)[\"”]|'([^'\\n]+)'")
    private val monthDay = rx("\\b($MONTH)\\.?\\s*(?:the\\s+)?(\\d{1,2})$ORDINAL(?!\\d)(?:(?:\\s*,\\s*|\\s+)(\\d{4})\\b)?")
    private val dayMonth = rx("(?<!\\d)(\\d{1,2})$ORDINAL\\s+(?:of\\s+)?($MONTH)\\.?(?![a-z])(?:(?:\\s*,\\s*|\\s+)(\\d{4})\\b)?")
    private val isoDate = Regex("(?<![\\d-])(\\d{4})-(\\d{2})-(\\d{2})(?![\\d-])")
    private val relativeDate = rx("\\b(day after tomorrow|today|tomorrow)\\b")
    private val weekdayDate = rx("\\b(?:(this|next|coming)\\s+)?($WEEKDAY)\\b\\.?")
    private val clock = rx("(?<![\\w:])(?:\\d{1,2}(?::\\d{2})?\\s*[ap]\\.?m\\.?|\\d{1,2}:\\d{2})(?![\\w:])|\\b(?:noon|midnight)\\b")
    private val zoneToken = rx("\\b(?:[A-Za-z_]+/[A-Za-z_/]+|(?:UTC|GMT)(?:[+-]\\d{1,2}(?::\\d{2})?)?|EST|EDT|PST|PDT|CST|CDT|MST|MDT|IST|JST|BST|CET|CEST|AEST|AEDT|NZST|NZDT)\\b")
    private val namedZone = rx("\\b([a-z]+)(?:\\s+(?:standard|daylight))?\\s+time\\b")
    private val shortZone = rx("^\\s*\\(?([a-z]{2,5})\\)?(?=\\s|$)")
    private val localTimePhrases = setOf("block", "reserve", "start", "end", "local", "device")
    private val timeGrammar = setOf("at", "and", "to", "until", "from", "on", "for", "named", "next", "the", "all", "with", "by", "in", "local")
    private val timeWithZone = rx("(?:(?<time>${clock.pattern})\\s+)?(?<zone>${zoneToken.pattern}|(?:local|device)\\s+time)")
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
        fun fail(message: String, missing: String? = null) = Result(error = message, missing = missing)
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
        for (m in weekdayDate.findAll(fields)) {
            val prefix = fields.substring(0, m.range.first)
            val previous = dates.filter { it.range.last < m.range.first }.maxByOrNull { it.range.last }
            val connected = previous != null && rx("^\\s*(?:and|or|to|through|until|-)\\s*$")
                .matches(fields.substring(previous.range.last + 1, m.range.first))
            val annotation = dates.firstOrNull { d ->
                d.range.first > m.range.last && rx("^\\s*,?\\s*$").matches(fields.substring(m.range.last + 1, d.range.first)) ||
                    d.range.last < m.range.first && rx("^\\s*,?\\s*$").matches(fields.substring(d.range.last + 1, m.range.first))
            }
            if (m.groupValues[1].isEmpty() && !connected && annotation == null &&
                !rx("\\b(?:on|$CALENDAR|event|appointment|meeting|day|time)(?:\\s+app)?\\s*$").containsMatchIn(prefix)) continue
            val weekday = weekdayNumber(m.groupValues[2])
            val offset = (weekday.value - today.dayOfWeek.value + 7) % 7
            val value = when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "this" -> today.plusDays((weekday.value - today.dayOfWeek.value).toLong())
                "next" -> today.plusDays(if (offset == 0) 7L else offset.toLong())
                else -> today.plusDays(offset.toLong())
            }
            if (annotation != null && (annotation.value.dayOfWeek != weekday || m.groupValues[1].isNotEmpty() && annotation.value != value))
                return fail("The weekday does not match that date. Which one did you mean?")
            dates += DateField(annotation?.value ?: value, m.range, false)
        }
        if (invalidDate) return fail("That date does not exist. Please give a valid day, month and year.")
        if (dates.isEmpty() && rx("\\b\\d{1,4}[/\\-]\\d{1,2}(?:[/\\-]\\d{1,4})?\\b").containsMatchIn(fields))
            return fail("Please write numeric dates as YYYY-MM-DD, or spell out the month so the date is unambiguous.")
        if (dates.isEmpty()) return fail("What date should I block? Please use a month name, YYYY-MM-DD, a weekday, today or tomorrow.", "date")
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
            val weekday = rx("\\b($WEEKDAY)\\.?\\s*,?\\s*$").find(text.substring(0, d.range.first))
            if (weekday != null && weekdayNumber(weekday.groupValues[1]) != day.dayOfWeek)
                return fail("The weekday does not match that date. Which one did you mean?")
        }

        val zones = zoneToken.findAll(fields).toList()
        val localShort = day.atStartOfDay(zone).format(DateTimeFormatter.ofPattern("z", Locale.US))
        val canCorrectZone = zoneRanges(fields).size == 1
        val zoneQuestion = "Please confirm the event time in ${zone.id} ($localShort). " +
            if (canCorrectZone) "Reply with $localShort to keep the requested time, or give the corrected time and zone."
            else "Send the full corrected calendar request."
        if (zones.any { z -> !z.value.equals(zone.id, true) && !z.value.equals(localShort, true) &&
                !runCatching { ZoneId.of(z.value.uppercase(Locale.ROOT), ZoneId.SHORT_IDS).rules == zone.rules }.getOrDefault(false) &&
                !(z.value.equals("UTC", true) && day.atStartOfDay(zone).offset.totalSeconds == 0) })
            return fail(zoneQuestion, "zone".takeIf { canCorrectZone })
        fields = mask(fields, zones.map { it.range })
        val hasNamedZone = namedZone.findAll(fields)
            .any { it.groupValues[1].lowercase(Locale.ROOT) !in localTimePhrases }
        val unknownShortZone = clock.findAll(fields).any { c ->
            shortZone.find(fields.substring(c.range.last + 1))
                ?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { it !in timeGrammar } == true
        }
        if (hasNamedZone || unknownShortZone || rx("\\b(?:timezone|time zone)\\b").containsMatchIn(fields))
            return fail(zoneQuestion, "zone".takeIf { canCorrectZone })
        fields = mask(fields, rx("\\b(?:local|device)\\s+time\\b").findAll(fields).map { it.range }.toList())

        if (rx("\\b(?:seconds|secs)\\b|\\b\\d+(?:\\.\\d+)?\\s*(?:second|sec)\\b|\\b(?:for|and)\\s+(?:a|an|one)\\s+(?:second|sec)\\b").containsMatchIn(fields))
            return fail("Calendar durations currently support whole minutes. Please give a duration without seconds.")
        val durations = duration.findAll(fields).toList()
        if (durations.size > 1) return fail("Please give one duration or one end time.")
        val minutes = durations.firstOrNull()?.let { durationMinutes(it.value) }
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
            return fail("What start time should I use? Please give AM/PM or 24-hour HH:mm notation.",
                if (durations.isNotEmpty() && !rx("\\b(?:morning|afternoon|evening|night)\\b").containsMatchIn(fields)) "start" else null)
        val isAllDay = clocks.isEmpty() && (explicitAllDay || shouldBlock)
        if (!isAllDay && clocks.isEmpty()) return fail("Should this be all day, or what start time should I use?", "start")
        if (!isAllDay && clocks.size == 1 && minutes == null) return fail("What end time or duration should I use?", "end")
        if (clocks.size == 2) {
            val between = fields.substring(clocks[0].range.last + 1, clocks[1].range.first)
            if (!rx("^\\s*(?:-|to|until|through|and)\\s*$").matches(between) &&
                !rx("^\\s*(?:to|for|called|titled|named)\\b.*\\buntil\\s*$").matches(between))
                return fail("Please write a single time range, such as from 9 AM to 10 AM.")
        }
        val cleaned = mask(fields, clocks.map { it.range })
        val withoutApp = cleaned.replace(rx("\\b(?:in|using|with)\\s+(?:(?:my|the|google)\\s+)*$CALENDAR(?:\\s+app)?\\b"), "")
        if (rx("\\b(?:at|in)\\s+(?!(?:for|on|from|to|until|and|called|titled|named|all|whole|entire)\\b)[a-z]").containsMatchIn(withoutApp))
            return fail("I need to clarify the requested place or other event detail before creating the event.")
        if (rx("\\b(?:for|until|to)\\s+\\d|\\b(?:next|following)\\s+(?:week|month|year)\\b").containsMatchIn(cleaned))
            return fail("I could not interpret all the requested date or duration information. Please give an exact date and time range.")
        val title = title(cleaned, quotes, shouldBlock)
            ?: return fail("What title should I give this event?", "title")
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

    /** A missing-field reply is data, never a new app instruction. The caller retains the parse reference date/zone. */
    fun continueRequest(previous: String, missing: String, answer: String): String? {
        if (!matches(previous) || answer.any(Char::isISOControl)) return null
        val reply = answer.trim().trimEnd('.', '!', '?').trimEnd()
        if (reply.isEmpty()) return null
        if (missing == "zone") {
            val replacement = timeWithZone.matchEntire(reply) ?: return null
            val fields = mask(previous, quoted.findAll(previous).map { it.range }.toList())
            val ranges = zoneRanges(fields)
            if (ranges.size != 1) return null
            val changes = mutableListOf(ranges.single() to replacement.groups["zone"]!!.value)
            replacement.groups["time"]?.value?.let { value ->
                if (time(value) == null) return null
                val prior = clock.findAll(fields).singleOrNull() ?: return null
                changes += prior.range to value
            }
            var corrected = previous
            for ((range, value) in changes.sortedByDescending { it.first.first }) corrected = corrected.replaceRange(range, value)
            return corrected
        }
        val fragment = when (missing) {
            "end" -> {
                val fullDuration = if (rx("^for\\s+").containsMatchIn(reply)) reply else "for $reply"
                if (duration.matches(fullDuration) && !rx("\\band\\s*$").containsMatchIn(reply) && (durationMinutes(fullDuration) ?: 0) > 0)
                    fullDuration
                else {
                    val end = reply.replace(rx("^(?:until|to)\\s+"), "")
                    val zoned = timeWithZone.matchEntire(end)
                    val value = zoned?.groups?.get("time")?.value ?: end
                    val prior = clock.findAll(mask(previous, quoted.findAll(previous).map { it.range }.toList())).singleOrNull() ?: return null
                    if (!clock.matches(value) || time(value) == null) return null
                    if (end == reply && time(value) == time(prior.value))
                        return previous + zoned?.groups?.get("zone")?.value?.let { " $it" }.orEmpty()
                    "until $end"
                }
            }
            "start" -> {
                if (allDay.matches(reply)) reply else {
                    val start = reply.replace(rx("^(?:at|from)\\s+"), "")
                    val value = timeWithZone.matchEntire(start)?.groups?.get("time")?.value ?: start
                    if (!clock.matches(value) || time(value) == null) return null
                    "at $start"
                }
            }
            "date" -> {
                val date = reply.replace(rx("^on\\s+"), "")
                if (!isoDate.matches(date) && !monthDay.matches(date) && !dayMonth.matches(date) &&
                    !relativeDate.matches(date) && !weekdayDate.matches(date)) return null
                "on $date"
            }
            "title" -> if (quoted.matches(reply)) "called $reply" else return null
            else -> return null
        }
        return "$previous $fragment"
    }

    private fun title(cleaned: String, quotes: List<MatchResult>, blocked: Boolean): String? {
        if (quotes.size == 1) return quotes.single().groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        if (quotes.size > 1) return null
        val marker = rx("\\b(?:(?:called|titled|named)\\s+(?:(?:my|the|a|an)\\s+)?|" +
            "for\\s+(?!(?:on|at|from|until|to|and|or)\\b)(?:(?:my|the|a|an)\\s+)?|" +
            "to\\s+(?!(?:and|or|for|on|at|in|from|until|through|called|titled|named|using|with|next|following|overnight|all|whole|entire|block|reserve|$CALENDAR)\\b))(\\S.*)").find(cleaned)
        val selected = marker?.groupValues?.get(1) ?: if (blocked) return "Busy" else cleaned
            .replace(rx("^(?:(?:hey\\s+)?friday[, ]+)?(?:please\\s+|(?:can|could|will|would) you\\s+)*(?:schedule|create|add|make)\\s+(?:(?:a|an|the|my|new)\\s+)*(?:(?:event|appointment|meeting)\\s+)?"), "")
        return selected
            .replace(rx("\\b(?:in|to|on|using|with)\\s+(?:(?:my|the|google)\\s+)*$CALENDAR(?:\\s+app)?\\b.*$"), "")
            .replace(rx("\\b(?:on|at|from|until|starting|between|all[ -]day|whole day|entire day|next day|following day|overnight)\\b.*$"), "")
            .trim(' ', '.', ',', ':', '-', ';')
            .takeIf { it.isNotBlank() && !it.equals("event", true) && !it.equals("appointment", true) }
    }

    private fun monthNumber(value: String): Int = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(value.take(3).lowercase(Locale.ROOT)) + 1
    private fun weekdayNumber(value: String): DayOfWeek = DayOfWeek.of(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").indexOf(value.take(3).lowercase(Locale.ROOT)) + 1)
    private fun zoneRanges(text: String): List<IntRange> {
        val fields = mask(text, listOf(isoDate, monthDay, dayMonth, relativeDate, weekdayDate)
            .flatMap { pattern -> pattern.findAll(text).map { it.range }.toList() })
        val ranges = zoneToken.findAll(fields).map { it.range }.toMutableList()
        ranges += namedZone.findAll(fields).filter { it.groupValues[1].lowercase(Locale.ROOT) !in localTimePhrases }.map { it.range }
        for (c in clock.findAll(fields)) {
            val start = c.range.last + 1
            val match = shortZone.find(fields.substring(start)) ?: continue
            val group = match.groups[1] ?: continue
            if (group.value.lowercase(Locale.ROOT) !in timeGrammar) ranges += (start + group.range.first)..(start + group.range.last)
        }
        val merged = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && overlap(last, range)) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        return merged
    }
    private fun durationMinutes(value: String): Long? = runCatching {
        durationPart.findAll(value).fold(BigDecimal.ZERO) { sum, part ->
            sum + part.groupValues[1].toBigDecimal() * if (part.groupValues[2].startsWith("h", true)) BigDecimal(60) else BigDecimal.ONE
        }.longValueExact()
    }.getOrNull()
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
        val reference = LocalDate.of(2026, 10, 21)
        val reported = "block my calendar for on Friday on 2:00 p.m. Indian time"
        check(parse(reported, reference, zone).missing == "zone")
        for (answer in listOf("2:00 p.m. ist", "IST", "2pm Asia/Kolkata", "2pm local time", "2:00 p.m. IST.")) {
            val corrected = checkNotNull(continueRequest(reported, "zone", answer))
            check(!corrected.contains("Indian time") && parse(corrected, reference, zone).missing == "end")
            val event = checkNotNull(parse(continueRequest(corrected, "end", "30 minutes")!!, reference, zone).event)
            check(event.title == "Busy" && event.start.toLocalDate() == LocalDate.of(2026, 10, 23) &&
                event.start.toLocalTime() == LocalTime.of(14, 0) && event.end.toLocalTime() == LocalTime.of(14, 30))
        }
        check(continueRequest(reported, "zone", "2pm IST and open Clock") == null)
        check(continueRequest(reported, "zone", "set an alarm for 2pm") == null)
        check(continueRequest(reported, "zone", "tomorrow 2pm IST") == null)
        check(parse("block my calendar on Friday at 2pm IST and Indian time", reference, zone).missing == null)
        val dateAfterTime = "block my calendar at 2pm January 7 Indian time"
        check(parse(continueRequest(dateAfterTime, "zone", "2pm IST")!!, reference, zone).missing == "end")
        val corrected = continueRequest(reported, "zone", "2:00 p.m. ist")!!
        check(parse(continueRequest(corrected, "end", "2:00 p.m. IST")!!, reference, zone).missing == "end")
        check(parse(continueRequest(corrected, "end", "until 2:30pm IST")!!, reference, zone).event!!.end.toLocalTime() == LocalTime.of(14, 30))
        val natural = "block my calender on friday at 2pm to meet some one"
        check(matches(natural))
        check(parse(natural, reference, zone).let { it.error == "What end time or duration should I use?" && it.missing == "end" })
        val meeting = parse("$natural for 30 minutes", reference, zone).event!!
        check(meeting.title == "meet some one" && !meeting.allDay && meeting.start.toLocalDate() == LocalDate.of(2026, 10, 23))
        check(meeting.start.toLocalTime() == LocalTime.of(14, 0) && meeting.end.toLocalTime() == LocalTime.of(14, 30) && meeting.start.zone == zone)
        check(parse("$natural for 1 hour", reference, zone).event!!.end.toLocalTime() == LocalTime.of(15, 0))
        check(parse("block my calendar on Wednesday at 2pm for 30 minutes", reference, zone).event!!.start.toLocalDate() == reference)
        check(parse("block my calendar next Wednesday at 2pm for 30 minutes", reference, zone).event!!.start.toLocalDate() == reference.plusDays(7))
        check(parse("block my calendar this Monday at 2pm for 30 minutes", reference, zone).event == null)
        check(parse("block my calendar on Friday and Saturday at 2pm for 30 minutes", reference, zone).event == null)
        check(parse("block my calendar next Friday at 2pm for 30 minutes", reference, zone).event!!.start.toLocalDate() == meeting.start.toLocalDate())
        check(parse("block my calendar on Friday at 2pm for 30 minutes", LocalDate.of(2026, 12, 31), zone).event!!.start.toLocalDate() == LocalDate.of(2027, 1, 1))
        for (answer in listOf("30 minutes", "for 30 minutes", "until 2:30pm", "to 14:30")) {
            val continued = continueRequest(natural, "end", answer)!!
            check(continued.startsWith(natural + " ") && parse(continued, reference, zone).event == meeting)
        }
        for (answer in listOf("open YouTube", "30 minutes and open WhatsApp", "until 2:30pm and tomorrow", "1 hour and", "0 minutes", "2"))
            check(continueRequest(natural, "end", answer) == null)
        val noDate = "block my calendar at 2pm to meet some one for 30 minutes"
        check(parse(noDate, reference, zone).missing == "date")
        check(parse(continueRequest(noDate, "date", "Friday")!!, reference, zone).event == meeting)
        check(continueRequest(noDate, "date", "Friday and Saturday") == null)
        val noStart = "block my calendar on Friday to meet some one for 30 minutes"
        check(parse(noStart, reference, zone).missing == "start")
        check(parse(continueRequest(noStart, "start", "at 2pm")!!, reference, zone).event == meeting)
        check(parse(continueRequest("create an event called QA on Friday", "start", "all day")!!, reference, zone).event!!.allDay)
        val noTitle = "create an event on Friday at 2pm for 30 minutes"
        check(parse(noTitle, reference, zone).missing == "title")
        check(parse(continueRequest(noTitle, "title", "\"QA meeting\"")!!, reference, zone).event!!.title == "QA meeting")
        check(continueRequest(noTitle, "title", "open WhatsApp") == null)
        check(parse("block my calendar on 01/07/2027", reference, zone).missing == null)
        check(parse("$natural but do not save yet", reference, zone).missing == null)
        check(parse("block my calendar on January 7 and Friday at 2pm for 30 minutes", reference, zone).event == null)
        check(parse("block my calendar tomorrow and Friday at 2pm for 30 minutes", reference, zone).event == null)
        check(parse("block my calendar on Friday, 2026-10-23 at 2pm for 30 minutes", reference, zone).event!!.start.toLocalDate() == meeting.start.toLocalDate())
        check(parse("Friday, block my calendar on Wednesday at 2pm for 30 minutes", reference, zone).event!!.start.toLocalDate() == reference)
        check(parse("block my calendar on Friday at 2pm for 30 minutes called \"Monday review\"", reference, zone).event!!.title == "Monday review")
        check(event("block time on January 7 from 9 AM to 10 AM for my review").title == "review")
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
