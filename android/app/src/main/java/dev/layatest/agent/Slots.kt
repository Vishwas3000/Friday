package dev.layatest.agent

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A date range a question is about (inclusive) and how to say it back, e.g. "September". */
data class TimeRange(val start: LocalDate, val end: LocalDate, val label: String)

/**
 * Deterministic slot rules: the half of understanding Laya can't do, because it chooses and never
 * produces. Times, durations, dates and quoted text are parsed here; free-form slots ("jazz", "mom")
 * are offered to Laya as [spans] to choose from (research/09: trimming spans took exact slot match
 * from 0% to 77%). Run `slotsSelfCheck()` via the "selfcheck" bridge command.
 */
class Slots(val request: String, private val today: LocalDate = LocalDate.now()) {
    private val lower = request.lowercase(Locale.ROOT)

    /** Text inside straight or curly quotes, in order. */
    val quoted: List<String> = Regex("[\"“]([^\"”]+)[\"”]").findAll(request).map { it.groupValues[1] }.toList()

    /** "6 am", "7:30 pm", "at 7", "19:00" -> (hour 0-23, minute). */
    val clock: Pair<Int, Int>? = parseClock()

    /** "10 minutes", "1 hour 30 minutes", "90 seconds", "half an hour" -> seconds. */
    val durationSec: Int? = parseDuration()

    /** "last month", "this week", "yesterday", "in september", "last 30 days", "this year". */
    val timeRange: TimeRange? = parseRange()

    /** 1-4 word spans that start and end on a content word, for Laya to choose the slot from. */
    val spans: List<String> = run {
        val toks = Regex("[\\w']+").findAll(request).map { it.value }.toList()
        val out = LinkedHashSet<String>()
        for (n in 1..4) for (i in 0..toks.size - n) {
            val s = toks.subList(i, i + n)
            if (s.first().lowercase() !in STOP && s.last().lowercase() !in STOP) out += s.joinToString(" ")
        }
        out.toList()
    }

    /** The words after the first of [keys] (whole words), cut at a joining word: "navigate to MG road" -> "MG road". */
    fun after(vararg keys: String, cutAt: Set<String> = CUT): String? {
        for (k in keys) {
            val m = Regex("\\b${Regex.escape(k)}\\b", RegexOption.IGNORE_CASE).find(request) ?: continue
            val words = request.substring(m.range.last + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val kept = words.takeWhile { it.lowercase().trim(',', '.', '!', '?') !in cutAt }
            val s = kept.joinToString(" ").trim(',', '.', '!', '?', ' ')
            if (s.isNotEmpty()) return s
        }
        return null
    }

    private fun parseClock(): Pair<Int, Int>? {
        Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])").find(lower)?.let { m ->
            var h = m.groupValues[1].toInt() % 12
            if (m.groupValues[3].startsWith("p")) h += 12
            return h to (m.groupValues[2].toIntOrNull() ?: 0)
        }
        Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(lower)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h < 24) return h to m.groupValues[2].toInt()
        }
        // "at 7", "keep an alarm for 6" (Indian English) -- but not "for 10 minutes"
        Regex("\\b(at|for)\\s+(\\d{1,2})\\b(?!\\s*(min|minute|hour|hr|sec|day|%))").find(lower)?.let { m ->
            val h = m.groupValues[2].toInt()
            if (h < 24) return h to 0
        }
        return null
    }

    private fun parseDuration(): Int? {
        var sec = 0
        var t = lower
        if ("half an hour" in t) { sec += 1800; t = t.replace("half an hour", " ") }
        for ((pat, mult) in listOf("hours?|hrs?" to 3600, "minutes?|mins?" to 60, "seconds?|secs?" to 1)) {
            Regex("\\b($NUMBER)\\s*($pat)\\b").find(t)?.let { sec += number(it.groupValues[1]) * mult }
        }
        return sec.takeIf { it > 0 }
    }

    private fun number(w: String) = w.toIntOrNull() ?: WORD_NUMBERS.getValue(w.replace(Regex("\\s+"), " "))

    private fun parseRange(): TimeRange? {
        val monthName = { d: LocalDate -> d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        return when {
            Regex("\\b(last|previous|past) month\\b").containsMatchIn(lower) -> {
                val first = today.minusMonths(1).withDayOfMonth(1)
                TimeRange(first, first.with(TemporalAdjusters.lastDayOfMonth()), monthName(first))
            }
            Regex("\\bthis month\\b").containsMatchIn(lower) -> TimeRange(today.withDayOfMonth(1), today, "this month")
            Regex("\\b(last|previous) week\\b").containsMatchIn(lower) -> {
                val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                TimeRange(mon, mon.plusDays(6), "last week")
            }
            Regex("\\bthis week\\b").containsMatchIn(lower) ->
                TimeRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, "this week")
            Regex("\\byesterday\\b").containsMatchIn(lower) -> today.minusDays(1).let { TimeRange(it, it, "yesterday") }
            Regex("\\btoday\\b").containsMatchIn(lower) -> TimeRange(today, today, "today")
            Regex("\\bthis year\\b").containsMatchIn(lower) -> TimeRange(today.withDayOfYear(1), today, "this year")
            else -> {
                Regex("\\b(last|past) (\\d+) days\\b").find(lower)?.let { m ->
                    val n = m.groupValues[2].toLong()
                    return TimeRange(today.minusDays(n - 1), today, "the last $n days")
                }
                Month.values().firstOrNull { Regex("\\b${it.name.lowercase()}\\b").containsMatchIn(lower) }?.let { mo ->
                    // a month name means its most recent occurrence: "september" in October 2026 is Sep 2026
                    val year = if (mo.value <= today.monthValue) today.year else today.year - 1
                    val first = LocalDate.of(year, mo, 1)
                    val last = first.with(TemporalAdjusters.lastDayOfMonth()).let { if (it.isAfter(today)) today else it }
                    TimeRange(first, last, monthName(first))
                }
            }
        }
    }

    companion object {
        /** Words that never start or end a slot span (command words, fillers, time words). */
        val STOP = """a an the my me some to for of on in at from with and that this these i i'm i'll im be is are
was do did does how much many what what's whats where when who show play watch put open start set call ring
message text send email navigate take get book order buy track search look up find turn off increase decrease
make let know need want hear throw latest season songs song music videos video album last week month today
yesterday tonight now near best way going happening please""".split(Regex("\\s+")).toSet()
        /** Words that end an [after] slot: "message rahul that I'll be late" -> "rahul". */
        val CUT = setOf("that", "and", "please", "using", "with", "via", "know", "saying", "now", "today", "tomorrow", "on")

        // shortcut: "few" means 5 and "couple" 2; the answer says the number back, so the owner can correct it
        private val WORD_NUMBERS = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
            "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60,
            "few" to 5, "a few" to 5, "couple" to 2, "a couple" to 2, "a couple of" to 2, "couple of" to 2,
        )
        private val NUMBER = WORD_NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + "|\\d+"
    }
}

/** Asserts for the slot rules; the "selfcheck" bridge command runs it on the phone. */
fun slotsSelfCheck(): String {
    val oct16 = LocalDate.of(2026, 10, 16)
    fun s(t: String) = Slots(t, oct16)
    check(s("set an alarm for 6 am").clock == (6 to 0))
    check(s("wake me up at 7:30 pm").clock == (19 to 30))
    check(s("wake me up at 7").clock == (7 to 0))
    check(s("start a 10 minute timer").durationSec == 600)
    check(s("timer for 1 hour 30 minutes").durationSec == 5400)
    check(s("start a 10 minute timer").clock == null)
    check(s("what were my expenses last month").timeRange == TimeRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "September"))
    check(s("how much money went out in september").timeRange?.label == "September")
    check(s("how much did I spend this week").timeRange?.start == LocalDate.of(2026, 10, 12))
    check(s("spent in the last 7 days").timeRange?.start == LocalDate.of(2026, 10, 10))
    check(s("play jazz music").spans == listOf("jazz"))
    check("arijit singh" in s("play some arijit singh songs").spans)
    check(s("navigate to MG road").after("to") == "MG road")
    check(s("message rahul that I'll be late").after("message") == "rahul")
    check(s("message rahul that I'll be late").after("that", cutAt = emptySet()) == "I'll be late")
    check(s("type \"hello world\" in notes").quoted == listOf("hello world"))
    check(s("keep an alarm for 6").clock == (6 to 0))
    check(s("timer for 10 minutes").clock == null && s("timer for 10 minutes").durationSec == 600)
    check(s("remind me in few mins").durationSec == 300)
    check(s("remind me in a couple of minutes").durationSec == 120)
    check(s("remind me in half an hour").durationSec == 1800)
    check(reminderText(s("remind me to call mom in 10 minutes")) == "call mom")
    check(reminderText(s("remind me in few mins")) == null)
    check(alarmAfter(java.time.LocalTime.of(5, 6, 50), 480) == (5 to 15))   // 05:14:50 rounds to 05:15
    check(alarmAfter(java.time.LocalTime.of(23, 58, 0), 300) == (0 to 3))   // wraps past midnight
    check(taskAfterOpening("open spotify", "Spotify") == null)
    check(taskAfterOpening("open the clock app and go to the stopwatch tab", "Clock") == "and go to the stopwatch tab")
    check(taskAfterOpening("open maps and search for MG road", "Maps") != null)
    return "ok"
}
