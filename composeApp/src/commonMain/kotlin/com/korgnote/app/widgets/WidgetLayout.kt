package com.korgnote.app.widgets

import com.korgnote.app.ui.AGENDA_LIST_DAYS
import com.korgnote.app.ui.AgendaSection
import com.korgnote.app.ui.agendaList
import com.korgnote.app.ui.buildOrgInlineString
import com.korgnote.app.ui.hhmm
import com.korgnote.app.ui.monthDay
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/*
 * What the home-screen widgets show, worked out without the platform's widget toolkit so it can be
 * tested as plain functions, as the calendar's own layout is.
 */

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
    entry.priority?.let { append("[#$it] ") }
    append(buildOrgInlineString(entry.title, viewMode = true).text)
}

/** A section's heading in the agenda widget: `Overdue`, `Today`, `Tomorrow`, or `Fri 2 Oct`. */
fun widgetSectionTitle(date: LocalDate?, today: LocalDate): String = when {
    date == null -> "Overdue"
    date == today -> "Today"
    date == today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
    else -> "${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }} ${date.day} ${date.monthShort}"
}

/**
 * When the notes the widgets show last matched where they are kept: the time on the day, the day
 * and time within the week, the date beyond — enough to see at a glance that they are old.
 */
fun updatedLabel(lastSynced: LocalDateTime?, now: LocalDateTime): String {
    if (lastSynced == null) return "Not synced yet"
    val days = lastSynced.date.daysUntil(now.date)
    val time = lastSynced.time.hhmm()
    return when {
        days <= 0 -> "Updated $time"
        days == 1 -> "Updated yesterday $time"
        days < 7 -> "Updated ${lastSynced.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }} $time"
        else -> "Updated ${lastSynced.date.day} ${lastSynced.date.monthShort}"
    }
}

/** `September 2026`, for the calendar widget's title. */
fun monthTitle(date: LocalDate): String =
    "${date.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${date.year}"

private val LocalDate.monthShort get() = month.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
