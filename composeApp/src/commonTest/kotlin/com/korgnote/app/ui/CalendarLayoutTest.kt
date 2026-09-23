package com.korgnote.app.ui

import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOccurrence
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the calendar views decide before anything is drawn (T17): which occurrences each view shows,
 * how timed ones share an hour grid, and how the agenda list is grouped and labelled.
 */
class CalendarLayoutTest {
    private val today = LocalDate(2026, 9, 23)

    private fun index(text: String) = AgendaIndex.of(mapOf("notes.org" to text), AgendaOptions(today = today))

    private fun day(d: Int) = LocalDate(2026, 9, d)

    private fun timed(vararg events: String): List<AgendaOccurrence> =
        index(events.joinToString("\n") { "* $it" }).on(today)

    private fun List<TimedBlock>.byTitle() = associate { it.occurrence.entry.title to (it.column to it.columns) }

    // --- Hour grid ---

    @Test
    fun eventsThatDoNotOverlapEachTakeTheWholeWidth() {
        val blocks = layoutTimed(timed("A <2026-09-23 Wed 09:00-10:00>", "B <2026-09-23 Wed 10:00-11:00>"))
        assertEquals(mapOf("A" to (0 to 1), "B" to (0 to 1)), blocks.byTitle())
    }

    @Test
    fun overlappingEventsShareTheWidth() {
        val blocks = layoutTimed(
            timed(
                "Design review <2026-09-23 Wed 10:00-11:30>",
                "One-to-one <2026-09-23 Wed 11:00-11:45>",
                "Lunch <2026-09-23 Wed 12:30-13:30>",
            ),
        )
        assertEquals(
            mapOf("Design review" to (0 to 2), "One-to-one" to (1 to 2), "Lunch" to (0 to 1)),
            blocks.byTitle(),
        )
    }

    @Test
    fun aFreedColumnIsReused() {
        // C starts after A ends but while B still runs: it takes A's column, and all three are a
        // single cluster of two columns.
        val blocks = layoutTimed(
            timed(
                "A <2026-09-23 Wed 09:00-10:00>",
                "B <2026-09-23 Wed 09:30-11:00>",
                "C <2026-09-23 Wed 10:00-10:30>",
            ),
        )
        assertEquals(mapOf("A" to (0 to 2), "B" to (1 to 2), "C" to (0 to 2)), blocks.byTitle())
    }

    @Test
    fun aShortEventIsDrawnTallEnoughForItsTitle() {
        val block = layoutTimed(timed("Stand-up <2026-09-23 Wed 09:15-09:30>")).single()
        assertEquals(9 * 60 + 15, block.startMinute)
        assertEquals(9 * 60 + 15 + MIN_EVENT_MINUTES, block.endMinute)
    }

    @Test
    fun untimedAndMultiDayEntriesStayOffTheGrid() {
        val shown = index(
            """
            * TODO Report
            DEADLINE: <2026-09-23 Wed>
            * Conference
            <2026-09-22 Tue 09:00>--<2026-09-24 Thu 17:00>
            * Call <2026-09-23 Wed 15:00>
            """.trimIndent(),
        ).on(today)
        assertEquals(listOf("Call"), layoutTimed(shown).map { it.occurrence.entry.title })
        assertEquals(setOf("Report", "Conference"), shown.filter { it.isAllDay }.map { it.entry.title }.toSet())
    }

    // --- Which occurrences a view shows ---

    @Test
    fun deadlineWarningsShowOnTodayOnly() {
        val agenda = index("* TODO Tax return\nDEADLINE: <2026-09-26 Sat>\n")
        assertEquals(listOf(3), agenda.shownOn(today, today, showDone = true).map { it.daysUntilDeadline })
        assertEquals(emptyList(), agenda.shownOn(day(24), today, showDone = true))
        assertEquals(listOf(0), agenda.shownOn(day(26), today, showDone = true).map { it.daysUntilDeadline })
    }

    @Test
    fun theMonthShowsEachEntryOnItsOwnDateOnly() {
        val agenda = index("* TODO Tax return\nDEADLINE: <2026-09-26 Sat>\n* TODO Old\nSCHEDULED: <2026-09-20 Sun>\n")
        assertEquals(emptyList(), agenda.monthDay(today, showDone = true))
        assertEquals(listOf("Tax return"), agenda.monthDay(day(26), showDone = true).map { it.entry.title })
        assertEquals(listOf("Old"), agenda.monthDay(day(20), showDone = true).map { it.entry.title })
    }

    @Test
    fun doneEntriesCanBeHidden() {
        val agenda = index("* DONE Water plants <2026-09-23 Wed>\n* TODO Feed cat <2026-09-23 Wed>\n")
        assertEquals(2, agenda.shownOn(today, today, showDone = true).size)
        assertEquals(listOf("Feed cat"), agenda.shownOn(today, today, showDone = false).map { it.entry.title })
        assertEquals(listOf("Feed cat"), agenda.monthDay(today, showDone = false).map { it.entry.title })
    }

    // --- Agenda list ---

    @Test
    fun theListPutsOverdueFirstThenEachDayWithSomethingOnIt() {
        val agenda = index(
            """
            * TODO Call the bank
            SCHEDULED: <2026-09-18 Fri>
            * Dentist <2026-09-25 Fri 14:30>
            """.trimIndent(),
        )
        val sections = agenda.agendaList(today, 7, today, showDone = true)
        assertEquals(listOf(null, today, day(25)), sections.map { it.date })
        assertEquals(listOf("Call the bank"), sections[0].occurrences.map { it.entry.title })
        assertEquals(emptyList(), sections[1].occurrences, "today has a section even when empty")
    }

    @Test
    fun aWeekWithoutTodayHasNoOverdueSection() {
        val agenda = index("* TODO Call the bank\nSCHEDULED: <2026-09-18 Fri>\n* Dentist <2026-10-01 Thu>\n")
        val sections = agenda.agendaList(day(28), 7, today, showDone = true)
        assertEquals(listOf(LocalDate(2026, 10, 1)), sections.map { it.date })
    }

    // --- Labels ---

    @Test
    fun labelsSayWhatKindOfDateAndHowFarAway() {
        val agenda = index(
            """
            * TODO Due today
            DEADLINE: <2026-09-23 Wed>
            * TODO Due soon
            DEADLINE: <2026-09-26 Sat>
            * TODO Missed
            DEADLINE: <2026-09-21 Mon>
            * TODO Late
            SCHEDULED: <2026-09-18 Fri>
            * Meeting <2026-09-23 Wed 10:00-11:30>
            """.trimIndent(),
        )
        val labels = agenda.shownOn(today, today, showDone = true).associate { it.entry.title to it.statusLabel(today) }
        assertEquals("Deadline", labels["Due today"])
        assertEquals("Due in 3 d.", labels["Due soon"])
        assertEquals("2 d. overdue", labels["Missed"])
        assertEquals("Scheduled 5 d. ago", labels["Late"])
        assertNull(labels["Meeting"])
        assertEquals("10:00–11:30", agenda.on(today).first { it.entry.title == "Meeting" }.timeLabel())
    }

    @Test
    fun eachModeStepsByItsOwnPeriod() {
        assertEquals(day(24), CalendarMode.Day.next(today))
        assertEquals(day(30), CalendarMode.Week.next(today))
        assertEquals(day(30), CalendarMode.Agenda.next(today))
        assertEquals(LocalDate(2026, 8, 23), CalendarMode.Month.prev(today))
        assertEquals(day(21), today.startOfWeek())
        assertTrue(CalendarMode.entries.first() == CalendarMode.Agenda, "the agenda list is the default and comes first")
    }
}
