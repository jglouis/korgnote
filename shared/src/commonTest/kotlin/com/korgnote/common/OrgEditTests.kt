package com.korgnote.common

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.PlanningKind
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.cycleTodo
import com.korgnote.common.org.edit.demote
import com.korgnote.common.org.edit.promote
import com.korgnote.common.org.edit.replace
import com.korgnote.common.org.edit.setPlanning
import com.korgnote.common.org.edit.setTags
import com.korgnote.common.org.edit.setTodo
import com.korgnote.common.org.edit.toggleCheckbox
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural edits (T7).
 *
 * Every test is the same shape: text in, text out, and an assertion that *only* the intended line
 * moved. That is the property the whole model was rebuilt for — the original bug was a save that
 * rewrote lines nobody had edited.
 */
class OrgEditTests {

    private val document = """
        #+TITLE: Edits
        #+TODO: TODO NEXT | DONE

        * TODO Parent                       :work:
        SCHEDULED: <2026-09-21 Mon>
        Body with   deliberate   spacing.

        ** Child
        - [ ] first
        - [x] second

        * Another
        Tail.
    """.trimIndent() + "\n"

    private val doc = document.toOrgDocument()

    private fun section(title: String) =
        doc.allNodes().filterIsInstance<OrgSection>().first { it.title == title }

    private fun checkbox(text: String) =
        doc.allNodes().filterIsInstance<OrgCheckboxItem>().first { it.text == text }

    /** Asserts the only difference is [before] becoming [after], leaving every other line alone. */
    private fun assertOnlyLineChanged(result: String, before: String, after: String) {
        val originalLines = document.split('\n')
        val resultLines = result.split('\n')
        val changed = originalLines.zip(resultLines).filter { (a, b) -> a != b }
        assertEquals(1, changed.size, "expected one changed line, got $changed")
        assertEquals(before, changed.single().first)
        assertEquals(after, changed.single().second)
        assertEquals(originalLines.size, resultLines.size, "line count must not change")
    }

    // --- The primitive ---

    /** I4 of docs/04-org-core.md. */
    @Test
    fun replaceIsExactlyATextReplacement() {
        val range = document.indexOf("Tail.") until (document.indexOf("Tail.") + 5)
        assertEquals(
            document.replaceRange(range, "Done."),
            doc.replace(range, "Done.").text,
        )
    }

    // --- Checkboxes ---

    @Test
    fun tickingACheckboxChangesOnlyItsMarker() {
        val result = doc.toggleCheckbox(checkbox("first")).text
        assertOnlyLineChanged(result, "- [ ] first", "- [x] first")
    }

    @Test
    fun untickingACheckboxChangesOnlyItsMarker() {
        val result = doc.toggleCheckbox(checkbox("second")).text
        assertOnlyLineChanged(result, "- [x] second", "- [ ] second")
    }

    /** Node identity, not ordinals: the right box is ticked even with an identical one above it. */
    @Test
    fun theRightCheckboxIsTickedAmongDuplicates() {
        val text = "- [ ] same\n- [ ] same\n"
        val d = text.toOrgDocument()
        val second = d.allNodes().filterIsInstance<OrgCheckboxItem>()[1]
        assertEquals("- [ ] same\n- [x] same\n", d.toggleCheckbox(second).text)
    }

    /** Ported from the ordinal-based toggle this replaced: nothing else on the page moves. */
    @Test
    fun tickingPreservesUnrelatedWhitespaceExactly() {
        val text = "* TODO Task A  \n\n\n- [ ] First\n- [ ] Second  \n"
        val d = text.toOrgDocument()
        val second = d.allNodes().filterIsInstance<OrgCheckboxItem>()[1]

        assertEquals("* TODO Task A  \n\n\n- [ ] First\n- [x] Second  \n", d.toggleCheckbox(second).text)
    }

    /**
     * A node that was never parsed carries no range, so an edit against it does nothing instead of
     * writing at offset zero. An ordinal could not express this: it always pointed at *something*.
     */
    @Test
    fun anEditWithAHandBuiltNodeIsRefused() {
        val handBuilt = OrgCheckboxItem(checked = false, text = "never parsed")
        assertTrue(handBuilt.range.isEmpty())
        assertEquals(document, doc.toggleCheckbox(handBuilt).text)
    }

    // --- TODO keywords ---

    @Test
    fun settingATodoKeywordChangesOnlyTheHeading() {
        val result = doc.setTodo(section("Parent"), "DONE").text
        assertOnlyLineChanged(
            result,
            "* TODO Parent                       :work:",
            "* DONE Parent                       :work:",
        )
    }

    @Test
    fun removingATodoKeywordLeavesNoDoubleSpace() {
        val result = doc.setTodo(section("Parent"), null).text
        assertOnlyLineChanged(
            result,
            "* TODO Parent                       :work:",
            "* Parent                       :work:",
        )
    }

    @Test
    fun addingATodoKeywordToAPlainHeading() {
        val result = doc.setTodo(section("Another"), "TODO").text
        assertOnlyLineChanged(result, "* Another", "* TODO Another")
    }

    @Test
    fun cyclingGoesThroughTheKeywordsThenBackToNone() {
        val config = OrgTodoConfig(todo = setOf("TODO", "NEXT"), done = setOf("DONE"))
        val parent = section("Parent")

        val next = doc.cycleTodo(parent, config)
        assertTrue(next.text.contains("* NEXT Parent"))

        val done = next.cycleTodo(next.allNodes().filterIsInstance<OrgSection>().first { it.title == "Parent" }, config)
        assertTrue(done.text.contains("* DONE Parent"))

        val none = done.cycleTodo(done.allNodes().filterIsInstance<OrgSection>().first { it.title == "Parent" }, config)
        assertTrue(none.text.contains("* Parent"), none.text)
    }

    // --- State changes, logged ---

    private val afternoon = LocalDateTime(2026, 10, 24, 14, 5)

    private fun OrgDocument.heading(title: String) =
        allNodes().filterIsInstance<OrgSection>().first { it.title == title }

    /** Org's own note, word for word, so Emacs reads it back; after the planning line, as Org puts it. */
    @Test
    fun aStateChangeIsLoggedUnderThePlanningLine() {
        val result = doc.changeTodoState(section("Parent"), "DONE", afternoon).text

        assertTrue(
            result.contains(
                "* DONE Parent                       :work:\n" +
                    "SCHEDULED: <2026-09-21 Mon>\n" +
                    "- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\n" +
                    "Body with   deliberate   spacing.\n"
            ),
            result,
        )
    }

    @Test
    fun aHeadingWithoutAKeywordIsLoggedAsFromNothing() {
        val result = doc.changeTodoState(section("Another"), "TODO", afternoon).text

        assertTrue(
            result.endsWith("* TODO Another\n- State \"TODO\"       from              [2026-10-24 Sat 14:05]\nTail.\n"),
            result,
        )
    }

    /** Newest first, as Org keeps them. */
    @Test
    fun aSecondChangeGoesAboveTheFirst() {
        val done = doc.changeTodoState(section("Parent"), "DONE", afternoon)
        val reopened = done.changeTodoState(done.heading("Parent"), "TODO", LocalDateTime(2026, 10, 25, 9, 0)).text

        assertTrue(
            reopened.contains(
                "SCHEDULED: <2026-09-21 Mon>\n" +
                    "- State \"TODO\"       from \"DONE\"       [2026-10-25 Sun 09:00]\n" +
                    "- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\n"
            ),
            reopened,
        )
    }

    @Test
    fun theNoteGoesBelowThePropertyDrawer() {
        val d = "* TODO Task\n:PROPERTIES:\n:ID: abc\n:END:\nBody.\n".toOrgDocument()

        val result = d.changeTodoState(d.heading("Task"), "DONE", afternoon).text

        assertEquals(
            "* DONE Task\n:PROPERTIES:\n:ID: abc\n:END:\n" +
                "- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\nBody.\n",
            result,
        )
    }

    @Test
    fun anExistingLogbookTakesTheNote() {
        val d = ("* TODO Task\nSCHEDULED: <2026-09-21 Mon>\n:LOGBOOK:\n" +
            "- State \"TODO\"       from              [2026-10-01 Thu 08:00]\n:END:\nBody.\n").toOrgDocument()

        val result = d.changeTodoState(d.heading("Task"), "DONE", afternoon).text

        assertEquals(
            "* DONE Task\nSCHEDULED: <2026-09-21 Mon>\n:LOGBOOK:\n" +
                "- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\n" +
                "- State \"TODO\"       from              [2026-10-01 Thu 08:00]\n:END:\nBody.\n",
            result,
        )
    }

    @Test
    fun logdrawerStartsALogbook() {
        val d = "#+STARTUP: overview logdrawer\n* TODO Task\nBody.\n".toOrgDocument()

        val result = d.changeTodoState(d.heading("Task"), "DONE", afternoon).text

        assertEquals(
            "#+STARTUP: overview logdrawer\n* DONE Task\n:LOGBOOK:\n" +
                "- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\n:END:\nBody.\n",
            result,
        )
    }

    /** The heading may be the last line of a file that does not end in a newline. */
    @Test
    fun aHeadingOnTheLastLineStillGetsItsNoteOnALineOfItsOwn() {
        val d = "* TODO Task".toOrgDocument()

        val result = d.changeTodoState(d.heading("Task"), "DONE", afternoon).text

        assertEquals("* DONE Task\n- State \"DONE\"       from \"TODO\"       [2026-10-24 Sat 14:05]\n", result)
    }

    @Test
    fun clearingOrKeepingTheKeywordLogsNothing() {
        val cleared = doc.changeTodoState(section("Parent"), null, afternoon).text
        assertOnlyLineChanged(cleared, "* TODO Parent                       :work:", "* Parent                       :work:")

        assertEquals(document, doc.changeTodoState(section("Parent"), "TODO", afternoon).text)
    }

    // --- Tags ---

    @Test
    fun settingTagsReplacesOnlyTheTagBlock() {
        val result = doc.setTags(section("Parent"), listOf("home", "urgent")).text
        assertOnlyLineChanged(
            result,
            "* TODO Parent                       :work:",
            "* TODO Parent :home:urgent:",
        )
    }

    @Test
    fun clearingTagsRemovesThem() {
        val result = doc.setTags(section("Parent"), emptyList()).text
        assertOnlyLineChanged(
            result,
            "* TODO Parent                       :work:",
            "* TODO Parent",
        )
    }

    @Test
    fun addingTagsToAHeadingWithNone() {
        val result = doc.setTags(section("Another"), listOf("later")).text
        assertOnlyLineChanged(result, "* Another", "* Another :later:")
    }

    // --- Planning ---

    @Test
    fun changingAScheduledDateRewritesOnlyThatLine() {
        val result = doc.setPlanning(section("Parent"), PlanningKind.SCHEDULED, "<2026-10-01 Thu>").text
        assertOnlyLineChanged(
            result,
            "SCHEDULED: <2026-09-21 Mon>",
            "SCHEDULED: <2026-10-01 Thu>",
        )
    }

    @Test
    fun removingAPlanningLineRemovesTheWholeLine() {
        val result = doc.setPlanning(section("Parent"), PlanningKind.SCHEDULED, null).text
        assertTrue(!result.contains("SCHEDULED"))
        // Everything else survives, including the deliberately odd spacing.
        assertTrue(result.contains("Body with   deliberate   spacing."))
        assertTrue(result.contains("* TODO Parent                       :work:"))
    }

    @Test
    fun addingAPlanningLinePutsItUnderTheHeading() {
        val result = doc.setPlanning(section("Another"), PlanningKind.DEADLINE, "<2026-12-25 Fri>").text
        assertTrue(result.contains("* Another\nDEADLINE: <2026-12-25 Fri>\nTail."), result)
    }

    /** CLOSED is a planning kind like the others; Org writes it when a task is finished. */
    @Test
    fun aClosedTimestampCanBeSetAndCleared() {
        val closed = doc.setPlanning(section("Another"), PlanningKind.CLOSED, "[2026-09-21 Mon 18:00]")
        assertTrue(closed.text.contains("* Another\nCLOSED: [2026-09-21 Mon 18:00]\nTail."), closed.text)

        val section = closed.allNodes().filterIsInstance<OrgSection>().first { it.title == "Another" }
        assertEquals(document, closed.setPlanning(section, PlanningKind.CLOSED, null).text)
    }

    // --- Promote and demote ---

    @Test
    fun demotingTakesTheWholeSubtreeWithIt() {
        val result = doc.demote(section("Parent")).text

        assertTrue(result.contains("** TODO Parent"), result)
        assertTrue(result.contains("*** Child"), "a child must move with its parent")
        // The following top-level heading is untouched.
        assertTrue(result.contains("\n* Another\n"))
        assertTrue(result.contains("Body with   deliberate   spacing."))
    }

    @Test
    fun promotingReversesDemoting() {
        val demoted = doc.demote(section("Parent"))
        val parent = demoted.allNodes().filterIsInstance<OrgSection>().first { it.title == "Parent" }
        assertEquals(document, demoted.promote(parent).text)
    }

    @Test
    fun aTopLevelHeadingCannotBePromotedFurther() {
        assertEquals(document, doc.promote(section("Parent")).text)
    }

    /** Stars inside a block are code, and must not be restarred. */
    @Test
    fun demotingDoesNotTouchStarsInsideABlock() {
        val text = "* Head\n#+BEGIN_SRC kotlin\n* not a heading\n#+END_SRC\n"
        val d = text.toOrgDocument()
        val result = d.demote(d.allNodes().filterIsInstance<OrgSection>().single()).text

        assertTrue(result.contains("** Head"))
        assertTrue(result.contains("\n* not a heading\n"), "a star inside a block is code: $result")
    }
}
