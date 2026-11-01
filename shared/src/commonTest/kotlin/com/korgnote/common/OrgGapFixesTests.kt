package com.korgnote.common

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgFixedWidth
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.agenda.OrgDuration
import com.korgnote.common.org.agenda.OrgRepeater
import com.korgnote.common.org.agenda.OrgTimeUnit.DAY
import com.korgnote.common.org.agenda.RepeatKind
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.org.agenda.parseOrgTimestamp
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.orderedNumbers
import com.korgnote.common.org.orgSpans
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.toOrgString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The four bugs the Org syntax gap review found: habit repeaters, lists across a blank line and
 * `[@n]` counters, commented and archived subtrees in the agenda, and fixed-width lines.
 */
class OrgGapFixesTests {

    // --- Habit repeaters: `.+2d/4d` ---

    @Test
    fun aHabitRepeaterIsARepeater() {
        val stamp = parseOrgTimestamp("<2026-09-20 Sun .+2d/4d>")!!
        assertEquals(OrgRepeater(RepeatKind.Restart, OrgDuration(2, DAY), habitMax = OrgDuration(4, DAY)), stamp.repeater)
    }

    @Test
    fun aHabitMarkedDoneMovesOnAndKeepsItsRange() {
        val text = "* TODO Run\nSCHEDULED: <2026-10-20 Tue .+2d/4d>\n:PROPERTIES:\n:STYLE: habit\n:END:\n"
        val doc = text.toOrgDocument()
        val section = doc.allNodes().filterIsInstance<OrgSection>().first()
        val result = doc.changeTodoState(section, "DONE", LocalDateTime(2026, 10, 24, 14, 5)).text
        assertTrue(result.startsWith("* TODO Run\nSCHEDULED: <2026-10-26 Mon .+2d/4d>\n"), result)
    }

    @Test
    fun reschedulingAHabitKeepsItsRepeater() {
        assertEquals("<2026-12-24 Thu .+2d/4d>", restamp("<2026-09-23 Wed .+2d/4d>", LocalDate(2026, 12, 24), null))
    }

    @Test
    fun aHabitRepeatsInTheAgenda() {
        val entry = agendaEntries("habits.org", "* TODO Run\nSCHEDULED: <2026-09-20 Sun .+2d/4d>\n").single()
        assertEquals(OrgDuration(2, DAY), entry.stamp.repeater?.every)
    }

    // --- Lists: one blank line, and counters ---

    @Test
    fun oneBlankLineBetweenItemsKeepsTheList() {
        val text = "1. first\n\n2. second\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(listOf("first", "second"), list.children.map { assertIs<OrgListItem>(it).text })
        assertEquals(listOf(1, 2), list.orderedNumbers())
        // The list covers the gap; each item keeps only its own line.
        assertEquals("1. first\n\n2. second\n", text.substring(list.range))
        assertEquals("1. first\n", text.substring(list.children[0].range))
        assertEquals("2. second\n", text.substring(list.children[1].range))
    }

    @Test
    fun twoBlankLinesEndTheList() {
        val doc = "- a\n\n\n- b\n".toOrgDocument()
        assertEquals(2, doc.children.filterIsInstance<OrgList>().size)
    }

    @Test
    fun aBlankLineThenTextEndsTheList() {
        val doc = "- a\n\nA paragraph.\n".toOrgDocument()
        assertIs<OrgList>(doc.children[0])
        assertIs<OrgRaw>(doc.children[1])
    }

    @Test
    fun aNestedListAfterABlankLineStaysNested() {
        val text = "- parent\n\n  - child\n\n  - sibling\n\n- next\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(2, list.children.size)
        val nested = assertIs<OrgList>(list.children[0].children.single())
        assertEquals(listOf("child", "sibling"), nested.children.map { assertIs<OrgListItem>(it).text })
    }

    @Test
    fun aCounterSetsTheNumberAndTheRestCountOn() {
        val list = assertIs<OrgList>("1. a\n2. [@5] b\n3. c\n".toOrgDocument().children.single())
        assertEquals(listOf(1, 5, 6), list.orderedNumbers())
        assertEquals("b", assertIs<OrgListItem>(list.children[1]).text)
        assertEquals(5, assertIs<OrgListItem>(list.children[1]).counter)
    }

    @Test
    fun numbersComeFromPositionNotFromTheSource() {
        val list = assertIs<OrgList>("1. a\n1. b\n1. c\n".toOrgDocument().children.single())
        assertEquals(listOf(1, 2, 3), list.orderedNumbers())
    }

    @Test
    fun aCheckboxAfterACounterIsACheckbox() {
        val list = assertIs<OrgList>("1. [@3] [X] done\n".toOrgDocument().children.single())
        val item = assertIs<OrgCheckboxItem>(list.children.single())
        assertTrue(item.checked)
        assertEquals("done", item.text)
        assertEquals(3, item.counter)
    }

    @Test
    fun theCounterIsHighlightedWithTheBullet() {
        val text = "1. [@3] [ ] x"
        val spans = orgSpans(text)
        val bullet = spans.single { it.kind == OrgSpanKind.Bullet }
        assertEquals("1. [@3] ", text.substring(bullet.range))
        assertEquals("[ ]", text.substring(spans.single { it.kind == OrgSpanKind.Checkbox }.range))
    }

    @Test
    fun aPrintedListKeepsItsCounters() {
        val list = assertIs<OrgList>("1. a\n2. [@5] b\n".toOrgDocument().children.single())
        assertEquals("1. a\n5. [@5] b", list.toOrgString())
    }

    // --- COMMENT and ARCHIVE subtrees in the agenda ---

    @Test
    fun aCommentedSubtreeIsNotInTheAgenda() {
        val text = """
            * COMMENT Old plans
            SCHEDULED: <2026-09-25 Fri>
            ** Under it
            <2026-09-26 Sat>
            * TODO COMMENT Also commented
            <2026-09-27 Sun>
            * COMMENTARY is a word
            <2026-09-28 Mon>
            * Live
            <2026-09-29 Tue>
        """.trimIndent()
        assertEquals(listOf("COMMENTARY is a word", "Live"), agendaEntries("n.org", text).map { it.title })
    }

    @Test
    fun anArchivedSubtreeIsNotInTheAgenda() {
        val text = "* Old :ARCHIVE:\n** Kid\n<2026-09-26 Sat>\n* New\n<2026-09-27 Sun>\n"
        assertEquals(listOf("New"), agendaEntries("n.org", text).map { it.title })
        val archivedFile = "#+FILETAGS: :ARCHIVE:\n* Anything\n<2026-09-26 Sat>\n"
        assertEquals(emptyList(), agendaEntries("a.org", archivedFile))
    }

    @Test
    fun aCommentBlockIsHighlightedAsAComment() {
        val text = "#+begin_comment\nnot for now\n#+end_comment"
        val body = orgSpans(text).single { text.substring(it.range) == "not for now" }
        assertEquals(OrgSpanKind.Comment, body.kind)
    }

    // --- Fixed-width lines ---

    @Test
    fun fixedWidthLinesAreOneVerbatimNode() {
        val text = "Output:\n: $ ls -l\n:   total 8\n:\nAfter.\n"
        val doc = text.toOrgDocument()
        assertIs<OrgRaw>(doc.children[0])
        val fixed = assertIs<OrgFixedWidth>(doc.children[1])
        assertEquals(listOf("$ ls -l", "  total 8", ""), fixed.contents)
        assertEquals(": $ ls -l\n:   total 8\n:\n", text.substring(fixed.range))
        assertEquals(": $ ls -l\n:   total 8\n:", fixed.toOrgString())
        assertIs<OrgRaw>(doc.children[2])
    }

    @Test
    fun aFixedWidthLineIsMonospaceWithNoMarkupInside() {
        val text = ": [[https://example.com][link]] *not bold*"
        val spans = orgSpans(text)
        assertEquals(listOf(OrgSpanKind.BlockBodyMonospace), spans.map { it.kind })
    }

    @Test
    fun propertiesAndDrawersAreNotFixedWidth() {
        val doc = "* H\n:PROPERTIES:\n:ID: x\n:END:\n".toOrgDocument()
        assertTrue(doc.allNodes().none { it is OrgFixedWidth })
    }

    @Test
    fun aStampInAFixedWidthLineIsNotAnAppointment() {
        assertEquals(emptyList(), agendaEntries("n.org", "* Log\n: <2026-09-25 Fri>\n"))
    }
}
