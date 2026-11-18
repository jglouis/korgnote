package com.korgnote.app.ui

import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// Made-up notes the calendar's previews share, around Monday 15 September 2025, so they never
// move with the real clock.

/** The day the calendar's previews are drawn on. */
internal val calendarPreviewToday = LocalDate(2025, 9, 15)

/** Late morning of [calendarPreviewToday]: after the stand-up, with a clock running since ten. */
internal val calendarPreviewNow = LocalDateTime(2025, 9, 15, 11, 20)

/**
 * A week of plans: timed and all-day entries, a deadline, a range across the weekend, a done one,
 * undated TODOs for the TODO list, and clocks, one of them running.
 */
internal fun previewCalendarAgenda(): AgendaIndex = AgendaIndex.of(
    mapOf(
        "work.org" to """
            #+TODO: TODO NEXT WAIT | DONE
            * NEXT Review pull requests :work:
            SCHEDULED: <2025-09-15 Mon>
            :LOGBOOK:
            CLOCK: [2025-09-15 Mon 08:15]--[2025-09-15 Mon 09:20] =>  1:05
            :END:
            * Stand-up <2025-09-15 Mon 09:30-09:45 +1d>
            * TODO [#A] Quarterly report :work:
            DEADLINE: <2025-09-18 Thu -5d>
            :LOGBOOK:
            CLOCK: [2025-09-15 Mon 10:00]
            CLOCK: [2025-09-12 Fri 14:00]--[2025-09-12 Fri 15:30] =>  1:30
            :END:
            * Team lunch <2025-09-17 Wed 12:00-13:30>
            * WAIT Feedback on the slides :work:
            * DONE Send the agenda :work:
            SCHEDULED: <2025-09-15 Mon>
        """.trimIndent(),
        "home.org" to """
            * TODO Pay invoices :admin:
            SCHEDULED: <2025-09-10 Wed>
            * Dentist <2025-09-15 Mon 14:30-15:15>
            * Holidays :travel:
            <2025-09-19 Fri>--<2025-09-23 Tue>
            * TODO Buy a bike lamp
            * TODO [#B] Book the train :travel:
            SCHEDULED: <2025-09-16 Tue>
        """.trimIndent(),
    ),
    AgendaOptions(today = calendarPreviewToday),
)
