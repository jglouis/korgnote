package com.korgnote.common

import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.cancelClock
import com.korgnote.common.org.edit.clockIn
import com.korgnote.common.org.edit.clockOut
import com.korgnote.common.org.edit.clocksOf
import com.korgnote.common.org.edit.effort
import com.korgnote.common.org.edit.effortMinutes
import com.korgnote.common.org.edit.runningClocks
import com.korgnote.common.org.edit.setEffort
import com.korgnote.common.org.edit.updateClockDurations
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Clocking, written as Emacs's `org-clock-in` and `org-clock-out` write it. */
class ClockingTest {

    private val nine = LocalDateTime(2026, 11, 1, 9, 30)
    private val quarterToEleven = LocalDateTime(2026, 11, 1, 10, 45)

    private fun OrgDocument.heading(title: String) = allNodes().filterIsInstance<OrgSection>().first { it.title == title }

    private fun clockedIn(text: String, title: String = "Task", at: LocalDateTime = nine): String {
        val doc = text.toOrgDocument()
        return doc.clockIn(doc.heading(title), at).text
    }

    private fun clockedOut(text: String, title: String = "Task", at: LocalDateTime = quarterToEleven): String {
        val doc = text.toOrgDocument()
        return doc.clockOut(doc.heading(title), at).text
    }

    @Test
    fun clockingInMakesALogbookUnderTheHeading() {
        assertEquals(
            "* TODO Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n:END:\nBody\n",
            clockedIn("* TODO Task\nBody\n"),
        )
    }

    @Test
    fun theLogbookGoesAfterThePlanningLineAndTheProperties() {
        val text = "* Task\nSCHEDULED: <2026-11-01 Sun>\n:PROPERTIES:\n:ID: x\n:END:\nBody\n"
        assertEquals(
            "* Task\nSCHEDULED: <2026-11-01 Sun>\n:PROPERTIES:\n:ID: x\n:END:\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n:END:\nBody\n",
            clockedIn(text),
        )
    }

    @Test
    fun anIndentedEntryGetsAnIndentedDrawer() {
        val text = "* Task\n  SCHEDULED: <2026-11-01 Sun>\n"
        assertEquals(
            "* Task\n  SCHEDULED: <2026-11-01 Sun>\n  :LOGBOOK:\n  CLOCK: [2026-11-01 Sun 09:30]\n  :END:\n",
            clockedIn(text),
        )
    }

    @Test
    fun aNewClockGoesAtTheTopOfAnExistingLogbook() {
        val text = "* Task\n:LOGBOOK:\n- State \"DONE\"       from \"TODO\"       [2026-10-30 Fri 08:00]\n:END:\n"
        assertEquals(
            "* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n- State \"DONE\"       from \"TODO\"       [2026-10-30 Fri 08:00]\n:END:\n",
            clockedIn(text),
        )
    }

    @Test
    fun aFileWithoutAFinalNewlineStillGetsWholeLines() {
        assertEquals("* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n:END:\n", clockedIn("* Task"))
    }

    @Test
    fun clockingInTwiceIsClockingInOnce() {
        val once = clockedIn("* Task\n")
        assertEquals(once, clockedIn(once))
    }

    @Test
    fun clockingOutWritesTheEndAndTheSumAsEmacsDoes() {
        val running = clockedIn("* Task\nBody\n")
        assertEquals(
            "* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:45] =>  1:15\n:END:\nBody\n",
            clockedOut(running),
        )
    }

    @Test
    fun aClockOverTenHoursIsNotPadded() {
        val running = clockedIn("* Task\n")
        assertTrue(clockedOut(running, at = LocalDateTime(2026, 11, 2, 12, 0)).contains("=> 26:30\n"))
    }

    @Test
    fun clockingOutKeepsTheStartAsItWasWritten() {
        val text = "* Task\n  :LOGBOOK:\n  CLOCK: [2026-11-01 dim. 09:30]\n  :END:\n"
        assertEquals(
            "* Task\n  :LOGBOOK:\n  CLOCK: [2026-11-01 dim. 09:30]--[2026-11-01 Sun 10:45] =>  1:15\n  :END:\n",
            clockedOut(text),
        )
    }

    @Test
    fun aStopBeforeTheStartStopsAtTheStart() {
        val running = clockedIn("* Task\n")
        assertTrue(clockedOut(running, at = LocalDateTime(2026, 11, 1, 9, 0)).contains("--[2026-11-01 Sun 09:30] =>  0:00"))
    }

    @Test
    fun clockingOutWithNoClockRunningChangesNothing() {
        val doc = "* Task\n".toOrgDocument()
        assertSame(doc, doc.clockOut(doc.heading("Task"), quarterToEleven))
    }

    @Test
    fun cancellingRemovesTheClockAndAnEmptyDrawer() {
        val doc = clockedIn("* Task\nBody\n").toOrgDocument()
        assertEquals("* Task\nBody\n", doc.cancelClock(doc.heading("Task")).text)
    }

    @Test
    fun cancellingKeepsADrawerWithOtherClocksInIt() {
        val closed = "CLOCK: [2026-10-31 Sat 09:00]--[2026-10-31 Sat 10:00] =>  1:00"
        val doc = "* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n$closed\n:END:\n".toOrgDocument()
        assertEquals("* Task\n:LOGBOOK:\n$closed\n:END:\n", doc.cancelClock(doc.heading("Task")).text)
    }

    @Test
    fun cancellingAtTheEndOfAFileWithoutANewline() {
        val doc = "* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n:END:".toOrgDocument()
        assertEquals("* Task\n", doc.cancelClock(doc.heading("Task")).text)
    }

    @Test
    fun aHeadingsTotalIncludesItsChildrenAndARunningClock() {
        val text = """
            * Project
            :LOGBOOK:
            CLOCK: [2026-10-31 Sat 09:00]--[2026-10-31 Sat 10:00] =>  1:00
            :END:
            ** Part
            :LOGBOOK:
            CLOCK: [2026-11-01 Sun 10:15]
            CLOCK: [2026-10-31 Sat 14:00]--[2026-10-31 Sat 14:20] =>  0:20
            :END:
        """.trimIndent() + "\n"
        val doc = text.toOrgDocument()
        // Its own clocks stop at its first sub-heading.
        assertEquals(1, doc.clocksOf(doc.heading("Project")).size)
        assertEquals(listOf("Part"), doc.runningClocks().map { it.title })
    }

    @Test
    fun theEffortIsReadFromTheProperties() {
        val doc = "* Task\n:PROPERTIES:\n:Effort:   1:30\n:END:\n".toOrgDocument()
        assertEquals("1:30", doc.heading("Task").effort)
    }

    private fun withEffort(text: String, minutes: Int?): String {
        val doc = text.toOrgDocument()
        return doc.setEffort(doc.heading("Task"), minutes).text
    }

    @Test
    fun anEffortIsWrittenAsEmacsWritesIt() {
        assertEquals("* Task\n:PROPERTIES:\n:Effort: 0:15\n:END:\nBody\n", withEffort("* Task\nBody\n", 15))
        // Under the planning line, into the drawer there is, in place of the one there was.
        assertEquals(
            "* Task\nSCHEDULED: <2026-11-02 Mon>\n:PROPERTIES:\n:ID: x\n:Effort: 2:00\n:END:\n",
            withEffort("* Task\nSCHEDULED: <2026-11-02 Mon>\n:PROPERTIES:\n:ID: x\n:END:\n", 120),
        )
        assertEquals("* Task\n:PROPERTIES:\n:Effort: 1:30\n:END:\n", withEffort("* Task\n:PROPERTIES:\n:EFFORT: 45\n:END:\n", 90))
    }

    @Test
    fun anEffortIsRemovedWithADrawerItLeavesEmpty() {
        assertEquals("* Task\nBody\n", withEffort("* Task\n:PROPERTIES:\n:Effort: 0:15\n:END:\nBody\n", null))
        assertEquals("* Task\n:PROPERTIES:\n:ID: x\n:END:\n", withEffort("* Task\n:PROPERTIES:\n:Effort: 0:15\n:ID: x\n:END:\n", null))
        assertEquals("* Task\n", withEffort("* Task\n", null))
    }

    @Test
    fun anEffortIsReadAsOrgReadsIt() {
        assertEquals(90, effortMinutes("1:30"))
        assertEquals(45, effortMinutes("45"))
        assertEquals(120, effortMinutes("2h"))
        assertEquals(30, effortMinutes("30min"))
        assertEquals(90, effortMinutes("1h 30min"))
        assertEquals(null, effortMinutes("a while"))
        assertEquals(null, effortMinutes(""))
    }

    @Test
    fun aClockEditedByHandIsSummedAgain() {
        val before = "* Task\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:45] =>  1:15\n:END:\n"
        val edited = before.replace("10:45]", "11:00]")
        assertEquals(edited.replace("=>  1:15", "=>  1:30"), updateClockDurations(edited, before))
    }

    @Test
    fun aClockWithoutASumGetsOne() {
        val text = "CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 09:35]\n"
        assertEquals("CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 09:35] =>  0:05\n", updateClockDurations(text))
    }

    @Test
    fun anEndBeforeTheStartIsSummedNegative() {
        val text = "CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 08:00] =>  1:15\n"
        assertEquals("CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 08:00] => -1:30\n", updateClockDurations(text))
    }

    @Test
    fun clocksAwayFromTheEditAreLeftAsTheyAre() {
        val wrong = "CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:45] =>  9:99"
        val before = "* A\n:LOGBOOK:\n$wrong\n:END:\n* B\nBody\n"
        val after = before.replace("Body", "Changed")
        assertEquals(after, updateClockDurations(after, before))
    }
}
