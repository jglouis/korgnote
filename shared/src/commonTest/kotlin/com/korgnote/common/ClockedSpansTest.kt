package com.korgnote.common

import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.clockedSpans
import com.korgnote.common.org.agenda.on
import com.korgnote.common.org.agenda.report
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clocked time as the calendar draws it, day by day, and as its report sums it. */
class ClockedSpansTest {
    private val work = """
        * TODO Report
        :LOGBOOK:
        CLOCK: [2026-11-02 Mon 09:00]--[2026-11-02 Mon 10:30] =>  1:30
        CLOCK: [2026-11-01 Sun 23:00]--[2026-11-02 Mon 01:00] =>  2:00
        :END:
        ** Figures
        :LOGBOOK:
        CLOCK: [2026-11-02 Mon 14:00]
        :END:
        * Notes, no clocks
    """.trimIndent() + "\n"
    private val home = "* Garden\n:LOGBOOK:\nCLOCK: [2026-11-03 Tue 08:00]--[2026-11-03 Tue 08:20] =>  0:20\n:END:\n"
    private val spans = clockedSpans(mapOf("work.org" to work, "home.org" to home, "plain.org" to "* Nothing\n"))
    private val now = LocalDateTime(2026, 11, 2, 15, 15)

    @Test
    fun eachClockIsASpanOnItsOwnHeading() {
        assertEquals(
            listOf("Report" to "TODO", "Report" to "TODO", "Figures" to null, "Garden" to null),
            spans.sortedBy { it.file != "work.org" }.map { it.title to it.todo },
        )
        val figures = spans.single { it.title == "Figures" }
        assertEquals(work.indexOf("** Figures"), figures.headingOffset)
        assertEquals(null, figures.end)
    }

    @Test
    fun aDayHasItsPartOfEachSpanAndARunningClockRunsToNow() {
        val monday = spans.on(LocalDate(2026, 11, 2), now)
        assertEquals(
            listOf(Triple("Report", 0, 60), Triple("Report", 9 * 60, 10 * 60 + 30), Triple("Figures", 14 * 60, 15 * 60 + 15)),
            monday.map { Triple(it.span.title, it.startMinute, it.endMinute) },
        )
        // Sunday has the hour before midnight.
        assertEquals(listOf(23 * 60 to 24 * 60), spans.on(LocalDate(2026, 11, 1), now).map { it.startMinute to it.endMinute })
    }

    @Test
    fun theReportSumsEachHeadingOverThePeriod() {
        val report = spans.report(LocalDate(2026, 11, 2), LocalDate(2026, 11, 3), now)
        assertEquals(
            listOf(Triple("Garden", 20, false), Triple("Report", 60 + 90, false), Triple("Figures", 75, true)),
            report.map { Triple(it.title, it.minutes, it.running) },
        )
        // Only the days asked for: Sunday's hour is left out above, and is all there is on Sunday.
        assertEquals(listOf(60), spans.report(LocalDate(2026, 11, 1), LocalDate(2026, 11, 1), now).map { it.minutes })
    }

    @Test
    fun theIndexKeepsItsClocksFromDayToDay() {
        val index = AgendaIndex.of(mapOf("work.org" to work), AgendaOptions(today = LocalDate(2026, 11, 2)))
        assertEquals(3, index.clocks.size)
        assertEquals(3, index.asOf(LocalDate(2026, 11, 3)).clocks.size)
    }
}
