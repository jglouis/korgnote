package com.korgnote.app.widgets

import com.korgnote.app.ui.monthWeeks
import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the home-screen widgets show, with today pinned to Wednesday 23 September 2026. */
class WidgetLayoutTest {
    private val today = LocalDate(2026, 9, 23)

    private val entries = agendaEntries(
        mapOf(
            "work.org" to """
                * TODO [#A] Send the [[https://example.com][report]] :work:
                SCHEDULED: <2026-09-21 Mon>
                * Design review <2026-09-23 Wed 10:00-11:00>
                * Dentist <2026-09-25 Fri 14:30>
                * DONE Water the plants
                SCHEDULED: <2026-09-24 Thu>
                * Holidays
                <2026-10-02 Fri>--<2026-10-05 Mon>
            """.trimIndent(),
        ),
    )

    @Test
    fun theAgendaListsWhatIsOverdueThenTheWeekWithoutWhatIsDone() {
        val sections = widgetAgenda(entries, today).widgetSections(today)

        assertEquals(
            listOf(
                null to listOf("TODO [#A] Send the report"),
                today to listOf("Design review"),
                LocalDate(2026, 9, 25) to listOf("Dentist"),
            ),
            sections.map { section -> section.date to section.occurrences.map { it.plainHeadline() } },
        )
    }

    @Test
    fun theAgendaMovesOnWithTheDayWithoutBeingHandedAnythingNew() {
        // The day after, the review is past and the dentist two days away; the overdue item is
        // still overdue, as it is not done.
        val tomorrow = LocalDate(2026, 9, 24)
        val sections = widgetAgenda(entries, tomorrow).widgetSections(tomorrow)

        assertEquals(listOf(null, tomorrow, LocalDate(2026, 9, 25)), sections.map { it.date })
    }

    @Test
    fun theCalendarCountsEachEntryOnItsOwnDayOnly() {
        val agenda = widgetAgenda(entries, today)

        // The overdue item is counted on its own day, not carried to today as the list does.
        assertEquals(1, agenda.widgetCount(LocalDate(2026, 9, 21)))
        assertEquals(1, agenda.widgetCount(today))
        assertEquals(0, agenda.widgetCount(LocalDate(2026, 9, 24)))
        // Every day of a range is marked.
        assertEquals(listOf(1, 1, 1, 1), (2..5).map { agenda.widgetCount(LocalDate(2026, 10, it)) })
    }

    @Test
    fun aMonthIsTheWeeksWithItsDaysInThem() {
        val weeks = monthWeeks(LocalDate(2026, 9, 23))

        // September 2026 starts on a Tuesday and ends on a Wednesday: five weeks, Monday first.
        assertEquals(5, weeks.size)
        assertEquals(LocalDate(2026, 8, 31), weeks.first().first())
        assertEquals(LocalDate(2026, 10, 4), weeks.last().last())
        assertEquals(listOf(DayOfWeek.MONDAY), weeks.map { it.first().dayOfWeek }.distinct())
    }

    @Test
    fun sectionsAreNamedFromToday() {
        assertEquals("Overdue", widgetSectionTitle(null, today))
        assertEquals("Today", widgetSectionTitle(today, today))
        assertEquals("Tomorrow", widgetSectionTitle(LocalDate(2026, 9, 24), today))
        assertEquals("Fri 25 Sep", widgetSectionTitle(LocalDate(2026, 9, 25), today))
    }

    @Test
    fun theUpdatedLabelSaysMoreTheOlderItIs() {
        val now = LocalDateTime(2026, 9, 23, 18, 0)

        assertEquals("Not synced yet", updatedLabel(null, now))
        assertEquals("Updated 14:32", updatedLabel(LocalDateTime(2026, 9, 23, 14, 32), now))
        assertEquals("Updated yesterday 09:05", updatedLabel(LocalDateTime(2026, 9, 22, 9, 5), now))
        assertEquals("Updated Sat 20:00", updatedLabel(LocalDateTime(2026, 9, 19, 20, 0), now))
        assertEquals("Updated 3 Sep", updatedLabel(LocalDateTime(2026, 9, 3, 8, 0), now))
    }
}
