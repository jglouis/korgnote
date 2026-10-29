package com.korgnote.app.widgets

import com.korgnote.app.ui.CalendarMode
import com.korgnote.common.org.agenda.AgendaEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Home-screen widgets that show the agenda: Android's, and nothing elsewhere.
 *
 * They are handed the entries rather than what falls on each day, and work the days out when they
 * draw: what is overdue and which deadlines warn depend on what day it is, and a widget redraws at
 * midnight without the app.
 */
interface AgendaWidgets {
    /**
     * The agenda as the notes now have it, and when the notes last matched where they are kept —
     * null when that has not happened this run, in which case the widgets keep what they knew.
     */
    fun show(entries: List<AgendaEntry>, lastSynced: Instant?)
}

/** Desktop, web and iOS: no widgets, yet. */
object NoAgendaWidgets : AgendaWidgets {
    override fun show(entries: List<AgendaEntry>, lastSynced: Instant?) = Unit
}

/** Where a tap from outside the app asks the calendar to open: a view, on a day. */
data class CalendarTarget(val mode: CalendarMode, val date: LocalDate)

/**
 * Taps on a widget's calendar, from the platform code that receives them to the app. A process-wide
 * mailbox, like [com.korgnote.app.notifications.ReminderTaps], because a tap can start the app.
 */
object CalendarTaps {
    val pending = MutableStateFlow<CalendarTarget?>(null)
}

/**
 * Syncs a widget ran while the app may be open. The replica changed under the app's notes, and a
 * note left showing what it held before would be saved back over what the sync brought: the app
 * reads the notes again, as it does when the server says they changed.
 */
object WidgetSyncs {
    val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
