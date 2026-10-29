package com.korgnote.app.ui

import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.DaySpan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/*
 * What the calendar views show, worked out without Compose so it can be tested as plain functions:
 * which occurrences a view shows on a day, how timed ones share the width of an hour grid, how the
 * agenda list is grouped, and how an occurrence describes itself.
 */

enum class CalendarMode {
    Agenda, Day, Week, Month;

    fun next(date: LocalDate): LocalDate = when (this) {
        Day -> date.plus(1, DateTimeUnit.DAY)
        Agenda, Week -> date.plus(7, DateTimeUnit.DAY)
        Month -> date.plus(1, DateTimeUnit.MONTH)
    }

    fun prev(date: LocalDate): LocalDate = when (this) {
        Day -> date.minus(1, DateTimeUnit.DAY)
        Agenda, Week -> date.minus(7, DateTimeUnit.DAY)
        Month -> date.minus(1, DateTimeUnit.MONTH)
    }

    /**
     * [periods] steps of [next] from [date], or of [prev] when negative — counted from [date] in one
     * go, so the 31st three months on is the 31st again where that month has one, not wherever
     * stepping through a shorter month in between left it.
     */
    fun shift(date: LocalDate, periods: Int): LocalDate = when (this) {
        Day -> date.plus(periods, DateTimeUnit.DAY)
        Agenda, Week -> date.plus(7 * periods, DateTimeUnit.DAY)
        Month -> date.plus(periods, DateTimeUnit.MONTH)
    }
}

/** How many days the agenda list shows from its first day: a week, as `org-agenda` does. */
const val AGENDA_LIST_DAYS = 7

/** Monday of this date's week. */
fun LocalDate.startOfWeek(): LocalDate = minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/**
 * The weeks a month grid shows for [date]'s month, Monday first: those with a day of the month in
 * them and no more, since a week of next month's days only would take room from the month's own.
 */
fun monthWeeks(date: LocalDate): List<List<LocalDate>> {
    val first = LocalDate(date.year, date.month, 1)
    val start = first.startOfWeek()
    val weeks = start.daysUntil(first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)) / 7 + 1
    return (0 until weeks).map { week -> (0 until 7).map { start.plus(week * 7 + it, DateTimeUnit.DAY) } }
}

/**
 * The occurrences a day view, a week column or the agenda list shows on [date].
 *
 * A deadline's advance warning is shown on today only, as in `org-agenda`: the engine gives one
 * for every day of the warning period, and repeating it on each of those days would only say the
 * same thing seven times. Everything overdue is carried to today by the engine already.
 */
fun AgendaIndex.shownOn(date: LocalDate, today: LocalDate, showDone: Boolean): List<AgendaOccurrence> =
    on(date).filter { occurrence ->
        val warning = (occurrence.daysUntilDeadline ?: 0) > 0
        (!warning || date == today) && (showDone || !occurrence.entry.done)
    }

/**
 * What the month grid shows on [date]: each entry on its own date only. A month cell has room for
 * what happens that day, not for reminders about other days.
 */
fun AgendaIndex.monthDay(date: LocalDate, showDone: Boolean): List<AgendaOccurrence> =
    on(date).filter { it.isOnOwnDate && (showDone || !it.entry.done) }

/**
 * True when an occurrence belongs in the all-day strip rather than on the hour grid: it has no
 * time, or it is a day of a multi-day range — drawn as one bar across its days, the way calendars
 * draw a trip, rather than as a block from its start time to midnight.
 */
val AgendaOccurrence.isAllDay: Boolean get() = time == null || span != DaySpan.Single

// --- The hour grid -----------------------------------------------------------------------------

/**
 * The shortest a timed entry is drawn: one without an end time, or a fifteen-minute stand-up,
 * still needs room for its title. Overlap is judged on the drawn extent, since that is what could
 * cover another block.
 */
const val MIN_EVENT_MINUTES = 30

/**
 * A timed occurrence placed on the hour grid: its vertical extent in minutes since midnight, and
 * which of [columns] side-by-side columns it takes, so that overlapping events share the width
 * instead of covering each other.
 */
data class TimedBlock(
    val occurrence: AgendaOccurrence,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

private val LocalTime.minuteOfDay get() = hour * 60 + minute

/**
 * Lays out a day's timed occurrences.
 *
 * Events that overlap, directly or through a chain of others, form a cluster; each event takes the
 * leftmost column free at its start, and every event in a cluster is as wide as the cluster's
 * column count allows. It is the layout most calendars use, and it never lets two blocks overlap.
 */
fun layoutTimed(occurrences: List<AgendaOccurrence>): List<TimedBlock> {
    val timed = occurrences.filter { !it.isAllDay }.map { occurrence ->
        val start = occurrence.time!!.minuteOfDay
        val end = maxOf(occurrence.endTime?.minuteOfDay ?: start, start + MIN_EVENT_MINUTES)
        Triple(occurrence, start, end.coerceAtMost(24 * 60).coerceAtLeast(start + 1))
    }.sortedWith(compareBy({ it.second }, { -it.third }))

    val blocks = mutableListOf<TimedBlock>()
    var cluster = mutableListOf<Pair<Triple<AgendaOccurrence, Int, Int>, Int>>()
    var clusterEnd = -1
    val columnEnds = mutableListOf<Int>()

    fun flush() {
        val columns = columnEnds.size
        cluster.forEach { (event, column) -> blocks += TimedBlock(event.first, event.second, event.third, column, columns) }
        cluster = mutableListOf()
        columnEnds.clear()
    }

    for (event in timed) {
        if (event.second >= clusterEnd) flush()
        val free = columnEnds.indexOfFirst { it <= event.second }
        val column = if (free >= 0) free.also { columnEnds[it] = event.third } else columnEnds.size.also { columnEnds += event.third }
        cluster += event to column
        clusterEnd = maxOf(clusterEnd, event.third)
    }
    flush()
    return blocks
}

// --- The month's weeks -------------------------------------------------------------------------

/**
 * The heights of rows that need [natural] heights, to fill [total] between them: each as tall as it
 * needs, and the shorter ones raised to one level, as water fills a tray, until together they fill
 * it. Rows that need more than [total] already keep what they need, and whatever holds them scrolls.
 */
fun fillHeights(natural: List<Int>, total: Int): List<Int> {
    var room = total
    var rest = natural.size
    // The tallest keep their own height while it is over the level the rest would share.
    for (height in natural.sortedDescending()) {
        if (height <= room / rest) break
        room -= height
        rest--
    }
    val level = if (rest > 0) room / rest else 0
    return natural.map { maxOf(it, level) }
}

// --- Multi-day ranges --------------------------------------------------------------------------

/**
 * A multi-day range drawn as one bar over the days of a week row: from column [first] to [last] of
 * the days shown, on [lane] counting down from the top. [occurrence] is its first day on screen.
 *
 * [continuesBefore] and [continuesAfter] say the range goes on past the row, so the bar is squared
 * off on that side.
 */
data class RangeBar(
    val occurrence: AgendaOccurrence,
    val first: Int,
    val last: Int,
    val lane: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
) {
    val columns: IntRange get() = first..last
}

/**
 * The multi-day ranges among [days] — what each column of a row shows, in column order — as bars,
 * each in the topmost lane it fits in without covering another. A bar's title then has the width of
 * every day it crosses, where a chip per day had a day's.
 *
 * A range's days are known by their entry and by following on from one another, so a repeating
 * range that comes round twice in a row is two bars.
 */
fun layoutRangeBars(days: List<List<AgendaOccurrence>>): List<RangeBar> {
    class Open(val occurrence: AgendaOccurrence, val first: Int, var last: Int, var lastSpan: DaySpan)

    val bars = mutableListOf<Open>()
    days.forEachIndexed { column, occurrences ->
        for (occurrence in occurrences) {
            if (occurrence.span == DaySpan.Single) continue
            val continuing = if (occurrence.span == DaySpan.Start) null else bars.lastOrNull {
                it.occurrence.entry == occurrence.entry && it.last == column - 1 && it.lastSpan != DaySpan.End
            }
            if (continuing != null) {
                continuing.last = column
                continuing.lastSpan = occurrence.span
            } else {
                bars += Open(occurrence, column, column, occurrence.span)
            }
        }
    }
    // Earliest first, and of those the longest, so the long bars take the top lanes and keep them.
    val laneEnds = mutableListOf<Int>()
    return bars.sortedWith(compareBy<Open>({ it.first }, { -(it.last - it.first) })).map { bar ->
        val free = laneEnds.indexOfFirst { it < bar.first }
        val lane = if (free >= 0) free.also { laneEnds[it] = bar.last } else laneEnds.size.also { laneEnds += bar.last }
        RangeBar(
            occurrence = bar.occurrence,
            first = bar.first,
            last = bar.last,
            lane = lane,
            continuesBefore = bar.occurrence.span == DaySpan.Middle || bar.occurrence.span == DaySpan.End,
            continuesAfter = bar.lastSpan == DaySpan.Start || bar.lastSpan == DaySpan.Middle,
        )
    }
}

// --- The agenda list ---------------------------------------------------------------------------

/** A heading in the agenda list and what is under it. */
data class AgendaSection(
    /** Null for the overdue section, which is not a day. */
    val date: LocalDate?,
    val occurrences: List<AgendaOccurrence>,
)

/**
 * The agenda list for the [days] days from [from]: an overdue section first, when the range
 * includes today and anything is overdue, then one section per day that has something on it.
 * Today always has a section, even an empty one, so there is somewhere that says so.
 */
fun AgendaIndex.agendaList(from: LocalDate, days: Int, today: LocalDate, showDone: Boolean): List<AgendaSection> {
    val sections = mutableListOf<AgendaSection>()
    val range = (0 until days).map { from.plus(it, DateTimeUnit.DAY) }
    val overdue = if (today in range) shownOn(today, today, showDone).filter { it.isCarriedOver } else emptyList()
    if (overdue.isNotEmpty()) sections += AgendaSection(null, overdue)
    for (date in range) {
        val shown = shownOn(date, today, showDone).filter { !it.isCarriedOver }
        if (shown.isNotEmpty() || date == today) sections += AgendaSection(date, shown)
    }
    return sections
}

// --- How an occurrence describes itself --------------------------------------------------------

/** `10:00`, or `10:00–11:30` when it has an end time; null when it has no time. */
fun AgendaOccurrence.timeLabel(): String? = time?.let { start ->
    endTime?.let { "${start.hhmm()}–${it.hhmm()}" } ?: start.hhmm()
}

fun LocalTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/**
 * What kind of date this is and how it stands relative to today, as `org-agenda` says it:
 * "Deadline", "Due in 3 d.", "Scheduled 2 d. ago", or which day of a range it is — or null for a plain
 * appointment or scheduled item on its own day, where the date says it all.
 */
fun AgendaOccurrence.statusLabel(today: LocalDate): String? {
    val until = daysUntilDeadline
    return when (entry.kind) {
        AgendaKind.Deadline -> when {
            until == null || until == 0 -> "Deadline"
            until > 0 -> "Due in $until d."
            else -> "${-until} d. overdue"
        }

        AgendaKind.Scheduled -> if (isCarriedOver) {
            val late = entry.stamp.start.daysUntil(today)
            "Scheduled $late d. ago"
        } else spanLabel()

        AgendaKind.Closed -> "Closed"
        AgendaKind.Timestamp -> spanLabel()
    }
}

/** Where in a multi-day range this day is, or null for a single day. */
private fun AgendaOccurrence.spanLabel(): String? = when (span) {
    DaySpan.Single -> null
    DaySpan.Start -> "Starts"
    DaySpan.Middle -> "Continues"
    DaySpan.End -> "Ends"
}
