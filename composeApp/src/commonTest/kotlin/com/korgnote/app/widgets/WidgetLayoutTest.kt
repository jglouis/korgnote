package com.korgnote.app.widgets

import com.korgnote.app.ui.KeywordSwatches
import com.korgnote.app.ui.monthWeeks
import com.korgnote.common.org.agenda.agendaEntries
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
        assertEquals("Overdue", widgetSectionTitle(null, today, English))
        assertEquals("Today", widgetSectionTitle(today, today, English))
        assertEquals("Tomorrow", widgetSectionTitle(LocalDate(2026, 9, 24), today, English))
        assertEquals("Fri 25 Sep", widgetSectionTitle(LocalDate(2026, 9, 25), today, English))
    }

    @Test
    fun theUpdatedLabelSaysMoreTheOlderItIs() {
        val now = LocalDateTime(2026, 9, 23, 18, 0)

        assertEquals("Not synced yet", updatedLabel(null, now, English))
        assertEquals("Updated 14:32", updatedLabel(LocalDateTime(2026, 9, 23, 14, 32), now, English))
        assertEquals("Updated yesterday 09:05", updatedLabel(LocalDateTime(2026, 9, 22, 9, 5), now, English))
        assertEquals("Updated Sat 20:00", updatedLabel(LocalDateTime(2026, 9, 19, 20, 0), now, English))
        assertEquals("Updated 3 Sep", updatedLabel(LocalDateTime(2026, 9, 3, 8, 0), now, English))
    }

    @Test
    fun theMonthIsTitledInTheWidgetsWords() {
        assertEquals("September 2026", monthTitle(today, English))
    }

    @Test
    fun aKeywordIsItsOwnLabelBesideTheTitle() {
        val overdue = widgetAgenda(entries, today).widgetSections(today).first().occurrences.single()

        assertEquals("[#A] Send the report", overdue.plainTitle())
        assertEquals("Scheduled 2 d. ago", overdue.widgetStatus(today, English))
    }

    @Test
    fun eachOpenKeywordHasAColourOfItsOwnAndAFinishedOneIsGrey() {
        val swatches = widgetAgenda(
            agendaEntries(mapOf("a.org" to "#+TODO: TODO EVENT | DONE\n* TODO a <2026-09-23 Wed>\n* EVENT b <2026-09-24 Thu>\n* DONE c <2026-09-22 Tue>\n")),
            today,
        ).widgetKeywordSwatches()

        assertNotEquals(swatches.of("TODO", done = false), swatches.of("EVENT", done = false))
        assertEquals(KeywordSwatches.DONE, swatches.of("DONE", done = true))
        assertNull(swatches.of("WAITING", done = false))
    }

    /** The words as the English widget has them. */
    private object English : WidgetWords {
        override val overdue = "Overdue"
        override val today = "Today"
        override val tomorrow = "Tomorrow"
        override val notSyncedYet = "Not synced yet"
        override fun updated(whenSynced: String) = "Updated $whenSynced"
        override fun updatedYesterday(time: String) = "Updated yesterday $time"
        override fun weekdayShort(day: DayOfWeek) = day.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
        override fun month(month: Month) = month.name.lowercase().replaceFirstChar { it.titlecase() }
        override fun monthShort(month: Month) = month(month).take(3)
        override val deadline = "Deadline"
        override fun dueIn(days: Int) = "Due in $days d."
        override fun overdueBy(days: Int) = "$days d. overdue"
        override fun scheduledAgo(days: Int) = "Scheduled $days d. ago"
        override val closed = "Closed"
        override val starts = "Starts"
        override val continues = "Continues"
        override val ends = "Ends"
    }
}
