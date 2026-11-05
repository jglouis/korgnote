package com.korgnote.common.org.agenda

import kotlinx.serialization.Serializable
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.clocksOf
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * A stretch of time clocked on a heading — one `CLOCK:` line — for the calendar, which draws it in
 * the hours it took, and its report, which sums it. Emacs's agenda shows the same in its log mode.
 */
@Serializable
data class ClockedSpan(
    val file: String,
    /** Where the heading starts: the app opens the note there, as for an agenda entry. */
    val headingOffset: Int,
    val title: String,
    val todo: String?,
    val start: LocalDateTime,
    /** Null while the clock runs. */
    val end: LocalDateTime?,
)

/** Every clocked span in [files], by path. Notes with no `CLOCK:` in them are not parsed. */
fun clockedSpans(files: Map<String, String>, fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): List<ClockedSpan> =
    files.flatMap { (path, text) -> clockedSpans(path, text, fallback) }

/** The clocked spans of the note [text] at [path], each on the heading whose entry holds it. */
fun clockedSpans(path: String, text: String, fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): List<ClockedSpan> {
    if (!text.contains("CLOCK:")) return emptyList()
    val doc = text.toOrgDocument(fallback)
    return doc.allNodes().filterIsInstance<OrgSection>().flatMap { heading ->
        doc.clocksOf(heading).map { ClockedSpan(path, heading.range.first, heading.title, heading.todo, it.start, it.end) }
    }
}

/** The part of a clocked span on one day, in minutes since its midnight; the end is exclusive. */
data class ClockedPiece(val span: ClockedSpan, val startMinute: Int, val endMinute: Int) {
    val minutes: Int get() = endMinute - startMinute
}

/**
 * The parts of these spans on [date], earliest first: a span across midnight is in both days, and
 * one still running runs to [now]. A span that ends before it starts, or takes no time, is left out.
 */
fun List<ClockedSpan>.on(date: LocalDate, now: LocalDateTime): List<ClockedPiece> {
    val dayStart = minutesOf(LocalDateTime(date, MIDNIGHT))
    val dayEnd = minutesOf(LocalDateTime(date.plus(1, DateTimeUnit.DAY), MIDNIGHT))
    return mapNotNull { span ->
        val start = minutesOf(span.start)
        val end = minutesOf(span.end ?: now)
        val from = maxOf(start, dayStart)
        val to = minOf(end, dayEnd)
        if (to <= from) null else ClockedPiece(span, (from - dayStart).toInt(), (to - dayStart).toInt())
    }.sortedWith(compareBy({ it.startMinute }, { it.span.file }, { it.span.headingOffset }))
}

/** Time clocked on one heading over a period, for the report. */
data class ClockedTotal(
    val file: String,
    val headingOffset: Int,
    val title: String,
    val todo: String?,
    val minutes: Int,
    /** Its clock is running now, so the total is still going up. */
    val running: Boolean,
)

/**
 * The time clocked on each heading from [from] to [to], both whole days, a running clock counted
 * to [now]: Emacs's agenda clock report, by note in path order and by heading in the order of the
 * note. Headings with no time in the period are left out.
 */
fun List<ClockedSpan>.report(from: LocalDate, to: LocalDate, now: LocalDateTime): List<ClockedTotal> {
    val periodStart = minutesOf(LocalDateTime(from, MIDNIGHT))
    val periodEnd = minutesOf(LocalDateTime(to.plus(1, DateTimeUnit.DAY), MIDNIGHT))
    return groupBy { it.file to it.headingOffset }.mapNotNull { (_, spans) ->
        val minutes = spans.sumOf { span ->
            val start = maxOf(minutesOf(span.start), periodStart)
            val end = minOf(minutesOf(span.end ?: now), periodEnd)
            (end - start).coerceAtLeast(0)
        }.toInt()
        if (minutes <= 0) return@mapNotNull null
        val first = spans.first()
        ClockedTotal(first.file, first.headingOffset, first.title, first.todo, minutes, running = spans.any { it.end == null })
    }.sortedWith(compareBy({ it.file }, { it.headingOffset }))
}

private val MIDNIGHT = LocalTime(0, 0)

/** Minutes since the epoch, of the wall-clock time [at]: as the stamps are, with no zone. */
private fun minutesOf(at: LocalDateTime): Long = at.toInstant(TimeZone.UTC).epochSeconds / 60
