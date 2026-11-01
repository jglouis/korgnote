package com.korgnote.common

import com.korgnote.common.org.DEFAULT_PRIORITIES
import com.korgnote.common.org.OrgPriorities
import com.korgnote.common.org.OrgPriority
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.agenda
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.setPriority
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.prioritiesOf
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Priorities as Org's manual (5.4 Priorities) has them: values, ranges, setting one, the agenda's order. */
class OrgPrioritiesTests {

    private fun p(text: String) = OrgPriority.of(text)!!

    // --- Values ---

    @Test
    fun aPriorityIsAnUppercaseLetterOrANumberFromZeroTo64() {
        assertEquals("A", p("A").text)
        assertEquals("Z", p("Z").text)
        assertEquals("0", p("0").text)
        assertEquals("64", p("64").text)
        for (bad in listOf("a", "AA", "65", "-1", "", "1.5", "#A")) assertNull(OrgPriority.of(bad), bad)
    }

    @Test
    fun aSmallerValueIsAHigherPriority() {
        assertTrue(p("A") < p("B"))
        assertTrue(p("1") < p("2"))
        assertTrue(p("9") < p("10"), "compared as numbers, not text")
    }

    @Test
    fun aHeadingReadsLettersAndNumbers() {
        assertEquals(p("A"), orgHeadingOf("*** TODO [#A] Write letter to Sam Fortune", DEFAULT_TODO_KEYWORDS).priority)
        assertEquals(p("1"), orgHeadingOf("*** TODO [#1] Write letter to Sam Fortune", DEFAULT_TODO_KEYWORDS).priority)
        assertEquals(p("64"), orgHeadingOf("* [#64] Lowest", DEFAULT_TODO_KEYWORDS).priority)
        assertEquals("Write letter to Sam Fortune", orgHeadingOf("*** TODO [#10] Write letter to Sam Fortune", DEFAULT_TODO_KEYWORDS).title)
        // Not priorities: lower case, out of range. They stay in the title.
        assertNull(orgHeadingOf("* [#a] Lower", DEFAULT_TODO_KEYWORDS).priority)
        assertEquals("[#65] Too far", orgHeadingOf("* [#65] Too far", DEFAULT_TODO_KEYWORDS).title)
    }

    // --- Ranges ---

    @Test
    fun aFileWithoutTheLineHasOrgsThree() {
        assertEquals(OrgPriorities(p("A"), p("C"), p("B")), prioritiesOf("* Heading\n"))
        assertEquals(listOf(p("A"), p("B"), p("C")), DEFAULT_PRIORITIES.range)
    }

    @Test
    fun theLineSetsHighestLowestAndDefault() {
        assertEquals(OrgPriorities(p("A"), p("C"), p("B")), prioritiesOf("#+PRIORITIES: A C B\n* x\n"))
        val numeric = prioritiesOf("#+PRIORITIES: 1 10 5")
        assertEquals(OrgPriorities(p("1"), p("10"), p("5")), numeric)
        assertEquals((1..10).map { p(it.toString()) }, numeric.range)
        assertEquals(65, prioritiesOf("#+PRIORITIES: 0 64 32").range.size)
    }

    @Test
    fun aLineThatBreaksTheRulesIsIgnored() {
        for (line in listOf(
            "#+PRIORITIES: C A B", // the highest must come first
            "#+PRIORITIES: 10 1 5",
            "#+PRIORITIES: A C", // three values
            "#+PRIORITIES: A 5 C", // all letters or all numbers
            "#+PRIORITIES: A C D", // the default between the two
            "#+PRIORITIES: a c b", // uppercase
            "#+PRIORITIES: 1 65 5", // up to 64
        )) {
            assertEquals(DEFAULT_PRIORITIES, prioritiesOf(line), line)
        }
    }

    @Test
    fun theOrderIsMeasuredFromTheDefault() {
        assertEquals(-1, DEFAULT_PRIORITIES.orderOf(p("A")))
        assertEquals(0, DEFAULT_PRIORITIES.orderOf(null), "no cookie is the default")
        assertEquals(0, DEFAULT_PRIORITIES.orderOf(p("B")))
        assertEquals(1, DEFAULT_PRIORITIES.orderOf(p("C")))
    }

    // --- Setting one, C-c , ---

    private fun prioritised(line: String, priority: OrgPriority?): String {
        val doc = "$line\nBody.\n".toOrgDocument()
        val heading = doc.allNodes().filterIsInstance<OrgSection>().first()
        return doc.setPriority(heading, priority).text.substringBefore('\n')
    }

    @Test
    fun theCookieGoesRightAfterTheKeyword() {
        assertEquals("*** TODO [#A] Write letter to Sam Fortune", prioritised("*** TODO Write letter to Sam Fortune", p("A")))
        assertEquals("* TODO [#C]", prioritised("* TODO", p("C")))
    }

    @Test
    fun anyHeadingCanHaveOneNotOnlyATodoItem() {
        assertEquals("** [#B] Plain heading :work:", prioritised("** Plain heading :work:", p("B")))
    }

    @Test
    fun aCookieIsReplacedOrRemovedAndNothingElseMoves() {
        assertEquals("* TODO [#10] Write it   :work:", prioritised("* TODO [#A] Write it   :work:", p("10")))
        assertEquals("* TODO [#1] Write it", prioritised("* TODO [#10] Write it", p("1")))
        assertEquals("* TODO Write it   :work:", prioritised("* TODO [#A] Write it   :work:", null))
        assertEquals("* Plain", prioritised("* [#64] Plain", null))
        assertEquals("* Plain", prioritised("* Plain", null), "nothing to remove")
    }

    // --- The agenda's order ---

    private val day = LocalDate(2026, 9, 25)

    private fun titles(text: String) = agenda(mapOf("n.org" to text), day, day, AgendaOptions(today = day)).map { it.entry.title }

    @Test
    fun theAgendaPutsTheHighestFirstWithinATime() {
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
        // All-day before timed, as before; within the all-day ones, A, then no cookie as B, then C.
        assertEquals(listOf("First", "No cookie", "Later", "At nine"), titles(text))
    }

    @Test
    fun numericPrioritiesSortAsNumbers() {
        val text = "#+PRIORITIES: 1 10 5\n" + listOf("10", "2", "9", "1").joinToString("") {
            "* TODO [#$it] P$it\nSCHEDULED: <2026-09-25 Fri>\n"
        } + "* TODO No cookie\nSCHEDULED: <2026-09-25 Fri>\n"
        assertEquals(listOf("P1", "P2", "No cookie", "P9", "P10"), titles(text))
    }

    @Test
    fun theDefaultIsThatOfTheFile() {
        // Here the default is A: a heading without a cookie ranks with [#A], ahead of [#B].
        val text = "#+PRIORITIES: A C A\n* TODO [#B] Second\nSCHEDULED: <2026-09-25 Fri>\n* TODO Uncookied\nSCHEDULED: <2026-09-25 Fri>\n"
        assertEquals(listOf("Uncookied", "Second"), titles(text))
    }
}
