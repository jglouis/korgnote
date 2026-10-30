package com.korgnote.app.widgets

import com.korgnote.app.ui.AGENDA_LIST_DAYS
import com.korgnote.app.ui.AgendaSection
import com.korgnote.app.ui.KeywordSwatches
import com.korgnote.app.ui.TagColors
import com.korgnote.app.ui.agendaList
import com.korgnote.app.ui.buildOrgInlineString
import com.korgnote.app.ui.hhmm
import com.korgnote.app.ui.monthDay
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaKind
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.DaySpan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/*
 * What the home-screen widgets show, worked out without the platform's widget toolkit so it can be
 * tested as plain functions, as the calendar's own layout is.
 */

/**
 * The words the widgets are written in. The platform has them, in the phone's language: a widget is
 * drawn by the launcher with the app closed, away from the app's own strings.
 */
interface WidgetWords {
    val overdue: String
    val today: String
    val tomorrow: String
    val notSyncedYet: String

    /** `Updated 14:32`, `Updated Sat 20:00`, `Updated 3 Sep`: [whenSynced] is the rest. */
    fun updated(whenSynced: String): String

    /** `Updated yesterday 09:05`. */
    fun updatedYesterday(time: String): String
    fun weekdayShort(day: DayOfWeek): String

    /** As a title, `October`. */
    fun month(month: Month): String
    fun monthShort(month: Month): String

    val deadline: String
    fun dueIn(days: Int): String
    fun overdueBy(days: Int): String
    fun scheduledAgo(days: Int): String
    val closed: String
    val starts: String
    val continues: String
    val ends: String
}

/** The agenda as of [today], from the entries the app last handed over. */
fun widgetAgenda(entries: List<AgendaEntry>, today: LocalDate): AgendaIndex =
    AgendaIndex(entries, AgendaOptions(today = today))

/**
 * The agenda widget's list: what is overdue, then the week from today, as the app's agenda list
 * shows it with finished entries hidden.
 */
fun AgendaIndex.widgetSections(today: LocalDate): List<AgendaSection> =
    agendaList(today, AGENDA_LIST_DAYS, today, showDone = false)

/** How many entries the calendar widget marks on [date]: each on its own date, unfinished. */
fun AgendaIndex.widgetCount(date: LocalDate): Int = monthDay(date, showDone = false).size

/**
 * `TODO [#A] Title`, as plain text: a widget draws no spans, so a link reads as its description and
 * emphasis loses its markers, as in reading mode.
 */
fun AgendaOccurrence.plainHeadline(): String = buildString {
    entry.todo?.let { append(it).append(' ') }
    append(plainTitle())
}

/** [plainHeadline] without its keyword, for a widget that shows the keyword as a label of its own. */
fun AgendaOccurrence.plainTitle(): String = buildString {
    entry.priority?.let { append("[#$it] ") }
    append(buildOrgInlineString(entry.title, viewMode = true).text)
}

/** The colours of the agenda's keywords, as the app's calendar has them. */
fun AgendaIndex.widgetKeywordSwatches(): KeywordSwatches =
    KeywordSwatches(entries.filter { !it.done }.mapNotNull { it.todo })

/** [occurrence]'s keyword colour, or null when it has none. */
fun KeywordSwatches.of(occurrence: AgendaOccurrence): TagColors.Swatch? =
    occurrence.entry.todo?.let { of(it, occurrence.entry.done) }

/** A section's heading in the agenda widget: `Overdue`, `Today`, `Tomorrow`, or `Fri 2 Oct`. */
fun widgetSectionTitle(date: LocalDate?, today: LocalDate, words: WidgetWords): String = when {
    date == null -> words.overdue
    date == today -> words.today
    date == today.plus(1, DateTimeUnit.DAY) -> words.tomorrow
    else -> "${words.weekdayShort(date.dayOfWeek)} ${date.day} ${words.monthShort(date.month)}"
}

/**
 * When the notes the widgets show last matched where they are kept: the time on the day, the day
 * and time within the week, the date beyond — enough to see at a glance that they are old.
 */
fun updatedLabel(lastSynced: LocalDateTime?, now: LocalDateTime, words: WidgetWords): String {
    if (lastSynced == null) return words.notSyncedYet
    val days = lastSynced.date.daysUntil(now.date)
    val time = lastSynced.time.hhmm()
    return when {
        days <= 0 -> words.updated(time)
        days == 1 -> words.updatedYesterday(time)
        days < 7 -> words.updated("${words.weekdayShort(lastSynced.date.dayOfWeek)} $time")
        else -> words.updated("${lastSynced.date.day} ${words.monthShort(lastSynced.date.month)}")
    }
}

/** `September 2026`, for the calendar widget's title. */
fun monthTitle(date: LocalDate, words: WidgetWords): String = "${words.month(date.month)} ${date.year}"

/**
 * What kind of date this is and how it stands relative to today, as the app's agenda says it:
 * "Deadline", "Due in 3 d.", or which day of a range it is — or null where the date says it all.
 */
fun AgendaOccurrence.widgetStatus(today: LocalDate, words: WidgetWords): String? {
    val until = daysUntilDeadline
    return when (entry.kind) {
        AgendaKind.Deadline -> when {
            until == null || until == 0 -> words.deadline
            until > 0 -> words.dueIn(until)
            else -> words.overdueBy(-until)
        }

        AgendaKind.Scheduled ->
            if (isCarriedOver) words.scheduledAgo(entry.stamp.start.daysUntil(today)) else spanStatus(words)

        AgendaKind.Closed -> words.closed
        AgendaKind.Timestamp -> spanStatus(words)
    }
}

private fun AgendaOccurrence.spanStatus(words: WidgetWords): String? = when (span) {
    DaySpan.Single -> null
    DaySpan.Start -> words.starts
    DaySpan.Middle -> words.continues
    DaySpan.End -> words.ends
}
