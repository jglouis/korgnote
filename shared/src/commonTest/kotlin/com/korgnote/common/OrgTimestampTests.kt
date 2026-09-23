package com.korgnote.common

import com.korgnote.common.org.agenda.OrgDelay
import com.korgnote.common.org.agenda.OrgDuration
import com.korgnote.common.org.agenda.OrgRepeater
import com.korgnote.common.org.agenda.OrgTimeUnit.DAY
import com.korgnote.common.org.agenda.OrgTimeUnit.HOUR
import com.korgnote.common.org.agenda.OrgTimeUnit.MONTH
import com.korgnote.common.org.agenda.OrgTimeUnit.WEEK
import com.korgnote.common.org.agenda.OrgTimeUnit.YEAR
import com.korgnote.common.org.agenda.OrgTimestamp
import com.korgnote.common.org.agenda.RepeatKind.CatchUp
import com.korgnote.common.org.agenda.RepeatKind.Cumulate
import com.korgnote.common.org.agenda.RepeatKind.Restart
import com.korgnote.common.org.agenda.orgTimestamps
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.orgStamp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The timestamp parser (T15): one row per construct in the syntax table of
 * `docs/07-calendar-agenda.md`, then the ways a stamp can be wrong.
 */
class OrgTimestampTests {
    private val sep20 = LocalDate(2026, 9, 20)
    private val sep22 = LocalDate(2026, 9, 22)

    /** Every row: the text, and what it must parse to, ignoring the source range. */
    private val rows: List<Pair<String, OrgTimestamp>> = listOf(
        "<2026-09-20 Sun>" to OrgTimestamp(sep20),
        "[2026-09-20 Sun]" to OrgTimestamp(sep20, active = false),
        "<2026-09-20 Sun 10:00>" to OrgTimestamp(sep20, startTime = LocalTime(10, 0)),
        "<2026-09-20 Sun 10:00-11:30>" to OrgTimestamp(sep20, LocalTime(10, 0), endTime = LocalTime(11, 30)),
        "<2026-09-20 Sun>--<2026-09-22 Tue>" to OrgTimestamp(sep20, end = sep22),
        "<2026-09-20 Sun 10:00>--<2026-09-22 Tue 12:00>" to
            OrgTimestamp(sep20, LocalTime(10, 0), end = sep22, endTime = LocalTime(12, 0)),
        // Both ends on one day is a time range, not a multi-day one.
        "<2026-09-20 Sun 10:00>--<2026-09-20 Sun 11:00>" to
            OrgTimestamp(sep20, LocalTime(10, 0), endTime = LocalTime(11, 0)),
        "[2026-09-20 Sun]--[2026-09-22 Tue]" to OrgTimestamp(sep20, end = sep22, active = false),
        "<2026-09-20 Sun +1w>" to OrgTimestamp(sep20, repeater = OrgRepeater(Cumulate, OrgDuration(1, WEEK))),
        "<2026-09-20 Sun ++1w>" to OrgTimestamp(sep20, repeater = OrgRepeater(CatchUp, OrgDuration(1, WEEK))),
        "<2026-09-20 Sun .+1d>" to OrgTimestamp(sep20, repeater = OrgRepeater(Restart, OrgDuration(1, DAY))),
        "<2026-09-20 Sun +2y>" to OrgTimestamp(sep20, repeater = OrgRepeater(Cumulate, OrgDuration(2, YEAR))),
        "<2026-09-20 Sun 09:00 +3h>" to
            OrgTimestamp(sep20, LocalTime(9, 0), repeater = OrgRepeater(Cumulate, OrgDuration(3, HOUR))),
        "<2026-09-20 Sun -2d>" to OrgTimestamp(sep20, delay = OrgDelay(OrgDuration(2, DAY), firstOnly = false)),
        "<2026-09-20 Sun --2d>" to OrgTimestamp(sep20, delay = OrgDelay(OrgDuration(2, DAY), firstOnly = true)),
        "<2026-09-20 Sun 10:00 +1m -3d>" to OrgTimestamp(
            sep20, LocalTime(10, 0),
            repeater = OrgRepeater(Cumulate, OrgDuration(1, MONTH)),
            delay = OrgDelay(OrgDuration(3, DAY), firstOnly = false),
        ),
        // Org accepts the repeater and the delay in either order.
        "<2026-09-20 Sun -3d +1m>" to OrgTimestamp(
            sep20,
            repeater = OrgRepeater(Cumulate, OrgDuration(1, MONTH)),
            delay = OrgDelay(OrgDuration(3, DAY), firstOnly = false),
        ),
        // The day name is optional, and in whatever language Emacs wrote it.
        "<2026-09-20>" to OrgTimestamp(sep20),
        "<2026-09-20 dim.>" to OrgTimestamp(sep20),
        "<2026-09-20 Sun 9:05>" to OrgTimestamp(sep20, startTime = LocalTime(9, 5)),
    )

    @Test
    fun everyRowOfTheSyntaxTableParses() {
        for ((text, expected) in rows) {
            val parsed = parseOrgTimestamp(text)
            assertEquals(expected, parsed?.copy(range = IntRange.EMPTY), text)
            assertEquals(text.indices, parsed?.range, "range of $text")
        }
    }

    @Test
    fun malformedStampsAreNotStamps() {
        val notStamps = listOf(
            "<2026-02-30 Mon>", // no such day
            "<2026-13-01>", // no such month
            "<2026-09-20 Sun]", // brackets that do not pair
            "<2026-9-20>", // Org writes two digits
            "2026-09-20", // no brackets at all
            "<>",
            "<Sun 2026-09-20>",
        )
        for (text in notStamps) assertNull(parseOrgTimestamp(text), text)
    }

    @Test
    fun anImpossibleTimeLeavesTheDate() {
        // The date is what places an entry; a time that cannot be is dropped, not the stamp.
        assertEquals(OrgTimestamp(sep20), parseOrgTimestamp("<2026-09-20 Sun 25:00>")?.copy(range = IntRange.EMPTY))
        assertEquals(
            OrgTimestamp(sep20, LocalTime(10, 0)),
            parseOrgTimestamp("<2026-09-20 Sun 10:00-10:75>")?.copy(range = IntRange.EMPTY),
        )
    }

    @Test
    fun aRangeThatRunsBackwardsIsTwoStamps() {
        val text = "<2026-09-22 Tue>--<2026-09-20 Sun>"
        assertNull(parseOrgTimestamp(text))
        assertEquals(listOf(sep22, sep20), text.orgTimestamps().map { it.start })
    }

    @Test
    fun activeAndInactiveHalvesDoNotMakeARange() {
        val stamps = "<2026-09-20 Sun>--[2026-09-22 Tue]".orgTimestamps()
        assertEquals(listOf(true, false), stamps.map { it.active })
        assertEquals(listOf(null, null), stamps.map { it.end })
    }

    @Test
    fun rangesPointAtTheStampInTheWholeText() {
        val line = "SCHEDULED: <2026-09-20 Sun> DEADLINE: <2026-09-22 Tue -1d>"
        val document = "* TODO Thing\n$line\n"
        val stamps = line.orgTimestamps(offset = document.indexOf(line))
        assertEquals(listOf("<2026-09-20 Sun>", "<2026-09-22 Tue -1d>"), stamps.map { document.substring(it.range) })
    }

    @Test
    fun stampsAmongOtherTextAreAllFound() {
        val stamps = "Lunch <2026-09-20 Sun 12:00> then [2026-09-21 Mon] and <2026-09-22 Tue>--<2026-09-23 Wed>."
            .orgTimestamps()
        assertEquals(3, stamps.size)
        assertEquals(LocalDate(2026, 9, 23), stamps[2].end)
    }

    @Test
    fun whatCompletionWritesParsesBack() {
        // Completion writes stamps (T13) and the agenda reads them; the two have to agree.
        val date = LocalDate(2026, 2, 28)
        assertEquals(date, parseOrgTimestamp(date.orgStamp(active = true))?.start)
        assertEquals(false, parseOrgTimestamp(date.orgStamp(active = false))?.active)
    }
}
