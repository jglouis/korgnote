package com.korgnote.common.org.capture

import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.complete.restamp
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * An event, as a calendar describes one: from an `.ics` file ([calendarEventFrom]) or the phone's
 * own calendar. Times are already in the zone the device is in, which is the only one an Org
 * timestamp knows.
 */
data class CalendarEvent(
    val summary: String,
    val start: EventTime,
    /** Exclusive, as calendars count it: an all-day event on the 3rd ends on the 4th. */
    val end: EventTime? = null,
    val location: String? = null,
    val description: String? = null,
    val url: String? = null,
    /** Org's repeater for it, `+1w`, when its rule is one Org can say. */
    val repeater: String? = null,
    /** Its `RRULE` as the calendar wrote it, which [repeater] is read from. */
    val rule: String? = null,
)

sealed interface EventTime {
    data class AllDay(val date: LocalDate) : EventTime
    data class At(val time: LocalDateTime) : EventTime
}

/** [event] as a capture: its summary for a title, when it is as an Org timestamp, where it is. */
fun capturedFrom(event: CalendarEvent): Captured = Captured(
    title = event.summary,
    link = event.url,
    text = event.description.orEmpty(),
    location = event.location,
    time = orgTimeOf(event),
)

/**
 * When [event] happens, as Org writes it: `<2026-11-03 Tue 14:00-15:00>` within a day,
 * `<2026-11-03 Tue>--<2026-11-05 Thu>` across days, with the repeater in the stamp when there is
 * one. A range across days takes no repeater: Org repeats only a single stamp.
 */
fun orgTimeOf(event: CalendarEvent): String {
    fun repeating(stamp: String) =
        event.repeater?.let { stamp.dropLast(1) + " " + it + stamp.last() } ?: stamp
    return when (val start = event.start) {
        is EventTime.AllDay -> {
            // The day before the end, which calendars do not count; an end not after the start is
            // a one-day event that said so badly.
            val last = (event.end as? EventTime.AllDay)?.date?.minus(DatePeriod(days = 1))
            if (last == null || last <= start.date) {
                repeating(start.date.orgStamp(active = true))
            } else {
                start.date.orgStamp(active = true) + "--" + last.orgStamp(active = true)
            }
        }
        is EventTime.At -> {
            val end = when (val end = event.end) {
                is EventTime.At -> end.time
                is EventTime.AllDay -> null
                null -> null
            }?.takeIf { it > start.time }
            when {
                end == null -> repeating(start.time.orgStamp(active = true))
                end.date == start.time.date ->
                    repeating(restamp("", start.time.date, start.time.time, end.time))
                else -> start.time.orgStamp(active = true) + "--" + end.orgStamp(active = true)
            }
        }
    }
}

/**
 * The first event in [ics], an iCalendar file, with its times moved into [zone]; null when it holds
 * none or the event has no start.
 *
 * Only what a capture needs is read, and read forgivingly: a time zone this device does not know
 * leaves the time as written, and a rule Org cannot repeat leaves the event once.
 */
fun calendarEventFrom(ics: String, zone: TimeZone): CalendarEvent? {
    val properties = mutableListOf<IcsProperty>()
    var depth = 0
    var inEvent = false
    for (line in unfold(ics)) {
        val property = icsProperty(line) ?: continue
        when {
            property.name == "BEGIN" && property.value.equals("VEVENT", ignoreCase = true) && !inEvent -> {
                inEvent = true
                depth = 0
            }
            !inEvent -> Unit
            // An alarm, or anything else nested in the event, has properties of its own.
            property.name == "BEGIN" -> depth++
            property.name == "END" && depth > 0 -> depth--
            property.name == "END" -> break
            depth == 0 -> properties += property
        }
    }
    if (!inEvent) return null
    fun first(name: String) = properties.firstOrNull { it.name == name }
    val start = first("DTSTART")?.let { eventTime(it, zone) } ?: return null
    return CalendarEvent(
        summary = first("SUMMARY")?.text().orEmpty().replace('\n', ' ').trim(),
        start = start,
        end = first("DTEND")?.let { eventTime(it, zone) },
        location = first("LOCATION")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        description = first("DESCRIPTION")?.text()?.let(::withoutInvitationFooter)?.takeIf { it.isNotEmpty() },
        url = first("URL")?.value?.trim()?.takeIf { it.isNotEmpty() },
        repeater = first("RRULE")?.let { orgRepeaterFor(it.value, start) },
        rule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Org's repeater for [rule], an `RRULE`: `+1w`, `+2m`. Null for a rule Org cannot say with one —
 * several days of the week, the second Tuesday of the month — and for one that stops, by date or
 * by count, which Org's repeaters never do: repeating it for ever would fill the agenda with
 * meetings that were over.
 */
fun orgRepeaterFor(rule: String, start: EventTime): String? {
    val parts = rule.split(';').mapNotNull { part ->
        val at = part.indexOf('=')
        if (at <= 0) null else part.substring(0, at).trim().uppercase() to part.substring(at + 1).trim().uppercase()
    }.toMap()
    val unit = when (parts["FREQ"]) {
        "DAILY" -> "d"
        "WEEKLY" -> "w"
        "MONTHLY" -> "m"
        "YEARLY" -> "y"
        else -> return null
    }
    val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
    if (interval < 1) return null
    val date = when (start) {
        is EventTime.AllDay -> start.date
        is EventTime.At -> start.time.date
    }
    for ((key, value) in parts) {
        val sameAsStart = when (key) {
            "FREQ", "INTERVAL", "WKST" -> true
            "BYDAY" -> value == ICS_DAYS[date.dayOfWeek.ordinal]
            "BYMONTHDAY" -> value.toIntOrNull() == date.day
            "BYMONTH" -> value.toIntOrNull() == date.month.ordinal + 1
            else -> false
        }
        if (!sameAsStart) return null
    }
    return "+$interval$unit"
}

private val ICS_DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * [event] as an iCalendar file of one event, which [calendarEventFrom] reads back as the same event:
 * how an event picked from the phone's calendar travels in a capture, as one shared from Mail does.
 * Times are written floating, in no zone, since they are already the device's.
 */
fun icsOf(event: CalendarEvent): String = buildString {
    fun line(text: String) = append(text).append("\r\n")
    fun escaped(text: String) = text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    fun stamp(name: String, time: EventTime) = when (time) {
        is EventTime.AllDay -> line("$name;VALUE=DATE:${icsDate(time.date)}")
        is EventTime.At -> line("$name:${icsDate(time.time.date)}T${pad(time.time.hour)}${pad(time.time.minute)}${pad(time.time.second)}")
    }
    line("BEGIN:VCALENDAR")
    line("VERSION:2.0")
    line("PRODID:-//KorgNote//Capture//EN")
    line("BEGIN:VEVENT")
    stamp("DTSTART", event.start)
    event.end?.let { stamp("DTEND", it) }
    line("SUMMARY:${escaped(event.summary)}")
    event.location?.let { line("LOCATION:${escaped(it)}") }
    event.description?.let { line("DESCRIPTION:${escaped(it)}") }
    event.url?.let { line("URL:$it") }
    event.rule?.let { line("RRULE:$it") }
    line("END:VEVENT")
    line("END:VCALENDAR")
}

private fun pad(value: Int) = value.toString().padStart(2, '0')

private fun icsDate(date: LocalDate) = "${date.year.toString().padStart(4, '0')}${pad(date.month.ordinal + 1)}${pad(date.day)}"

/** One content line: `DTSTART;TZID=Europe/Paris:20261103T140000`. */
private class IcsProperty(val name: String, val parameters: Map<String, String>, val value: String) {
    /** The value as text, with iCalendar's escapes undone. */
    fun text(): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

/** The file's lines with folding undone: a line that starts with a space or tab continues the last. */
private fun unfold(ics: String): List<String> {
    val lines = mutableListOf<String>()
    for (raw in ics.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        if ((raw.startsWith(' ') || raw.startsWith('\t')) && lines.isNotEmpty()) {
            lines[lines.lastIndex] = lines.last() + raw.substring(1)
        } else if (raw.isNotBlank()) {
            lines += raw
        }
    }
    return lines
}

/** [line] read as a property, or null when it is not one. A colon inside a quoted parameter is not the value's. */
private fun icsProperty(line: String): IcsProperty? {
    var quoted = false
    var colon = -1
    for (i in line.indices) {
        when (line[i]) {
            '"' -> quoted = !quoted
            ':' -> if (!quoted) { colon = i; break }
        }
    }
    if (colon <= 0) return null
    val head = line.substring(0, colon).split(';')
    val parameters = head.drop(1).mapNotNull { parameter ->
        val at = parameter.indexOf('=')
        if (at <= 0) null else parameter.substring(0, at).uppercase() to parameter.substring(at + 1).trim('"')
    }.toMap()
    return IcsProperty(head[0].trim().uppercase(), parameters, line.substring(colon + 1))
}

private val ICS_DATE = Regex("^(\\d{4})(\\d{2})(\\d{2})$")
private val ICS_DATE_TIME = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(Z?)$")

/**
 * A `DTSTART` or `DTEND` as a time in [zone]: UTC when it ends in `Z`, its `TZID`'s when the
 * device knows that zone, and as written otherwise — a floating time, or a zone named the Windows
 * way, which is likelier to be the reader's own than not.
 */
private fun eventTime(property: IcsProperty, zone: TimeZone): EventTime? {
    val value = property.value.trim()
    ICS_DATE.matchEntire(value)?.let { match ->
        val (year, month, day) = match.destructured
        return runCatching { EventTime.AllDay(LocalDate(year.toInt(), month.toInt(), day.toInt())) }.getOrNull()
    }
    val match = ICS_DATE_TIME.matchEntire(value) ?: return null
    val (year, month, day, hour, minute, second, utc) = match.destructured
    val written = runCatching {
        LocalDateTime(LocalDate(year.toInt(), month.toInt(), day.toInt()), LocalTime(hour.toInt(), minute.toInt(), second.toInt()))
    }.getOrNull() ?: return null
    val from = when {
        utc.isNotEmpty() -> TimeZone.UTC
        else -> property.parameters["TZID"]?.let { runCatching { TimeZone.of(it) }.getOrNull() }
    }
    val local = from?.let { runCatching { written.toInstant(it).toLocalDateTime(zone) }.getOrNull() } ?: written
    return EventTime.At(local)
}

/**
 * [description] without the footer Google Calendar puts under an invitation's: a rule of `-::~:~::~`
 * and the meeting link and help text below it, which are not what anyone captures an event for.
 */
private fun withoutInvitationFooter(description: String): String {
    val at = description.indexOf("-::~:~::~")
    return (if (at >= 0) description.substring(0, at) else description).trim()
}
