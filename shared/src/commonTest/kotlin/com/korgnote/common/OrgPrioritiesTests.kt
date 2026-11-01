package com.korgnote.common

import com.korgnote.common.org.DEFAULT_PRIORITIES
import com.korgnote.common.org.OrgPriorities
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.agenda
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.setPriority
import com.korgnote.common.org.prioritiesOf
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Priorities: what a file allows, setting one on a heading, and the agenda's order. */
class OrgPrioritiesTests {

    @Test
    fun aFileWithoutTheLineHasOrgsOwn() {
        assertEquals(OrgPriorities('A', 'C', 'B'), prioritiesOf("* Heading\n"))
        assertEquals(listOf('A', 'B', 'C'), DEFAULT_PRIORITIES.range)
    }

    @Test
    fun theLineSetsTheRangeAndTheDefault() {
        assertEquals(OrgPriorities('A', 'E', 'C'), prioritiesOf("#+PRIORITIES: A E C\n* x\n"))
        assertEquals(listOf('1', '2', '3', '4', '5'), prioritiesOf("#+priorities: 1 5 3").range)
    }

    @Test
    fun aLineThatCannotBeReadIsIgnored() {
        for (line in listOf("#+PRIORITIES: C A B", "#+PRIORITIES: A C", "#+PRIORITIES: A 5 C", "#+PRIORITIES: A C D", "#+PRIORITIES: 10 64 30")) {
            assertEquals(DEFAULT_PRIORITIES, prioritiesOf(line), line)
        }
    }

    @Test
    fun theOrderIsMeasuredFromTheDefault() {
        assertEquals(-1, DEFAULT_PRIORITIES.orderOf('A'))
        assertEquals(0, DEFAULT_PRIORITIES.orderOf(null))
        assertEquals(0, DEFAULT_PRIORITIES.orderOf('B'))
        assertEquals(1, DEFAULT_PRIORITIES.orderOf('C'))
    }

    private fun prioritised(line: String, priority: Char?): String {
        val doc = "$line\nBody.\n".toOrgDocument()
        val heading = doc.allNodes().filterIsInstance<OrgSection>().first()
        return doc.setPriority(heading, priority).text.substringBefore('\n')
    }

    @Test
    fun aPriorityGoesAfterTheKeywordOrFirstInTheTitle() {
        assertEquals("* TODO [#A] Write it", prioritised("* TODO Write it", 'A'))
        assertEquals("** [#B] Plain heading :work:", prioritised("** Plain heading :work:", 'B'))
        assertEquals("* TODO [#C]", prioritised("* TODO", 'C'))
    }

    @Test
    fun aPriorityIsReplacedOrRemovedAndNothingElseMoves() {
        assertEquals("* TODO [#C] Write it   :work:", prioritised("* TODO [#A] Write it   :work:", 'C'))
        assertEquals("* TODO Write it   :work:", prioritised("* TODO [#A] Write it   :work:", null))
        assertEquals("* Plain", prioritised("* [#B] Plain", null))
        assertEquals("* Plain", prioritised("* Plain", null), "nothing to remove")
    }

    @Test
    fun theAgendaPutsTheImportantFirstWithinATime() {
        val day = LocalDate(2026, 9, 25)
        val text = """
            * TODO [#C] Later
            SCHEDULED: <2026-09-25 Fri>
            * TODO No cookie
            SCHEDULED: <2026-09-25 Fri>
            * TODO [#A] First
            SCHEDULED: <2026-09-25 Fri>
            * TODO [#C] At nine
            SCHEDULED: <2026-09-25 Fri 09:00>
        """.trimIndent()
        val titles = agenda(mapOf("n.org" to text), day, day, AgendaOptions(today = day)).map { it.entry.title }
        // All-day before timed, as before; within the all-day ones, A, then the default, then C.
        assertEquals(listOf("First", "No cookie", "Later", "At nine"), titles)
    }

    @Test
    fun theDefaultIsThatOfTheFile() {
        val day = LocalDate(2026, 9, 25)
        // Here the default is A: a heading with no cookie ranks with [#A], ahead of [#B].
        val text = "#+PRIORITIES: A C A\n* TODO [#B] Second\nSCHEDULED: <2026-09-25 Fri>\n* TODO Uncookied\nSCHEDULED: <2026-09-25 Fri>\n"
        val titles = agenda(mapOf("n.org" to text), day, day, AgendaOptions(today = day)).map { it.entry.title }
        assertEquals(listOf("Uncookied", "Second"), titles)
    }
}
