package com.korgnote.common

import com.korgnote.common.org.cutOrgSubtree
import com.korgnote.common.org.orgSubtreeAtLevel
import com.korgnote.common.org.withOrgSubtreeFiled
import com.korgnote.common.org.withOrgSubtreeRefiled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Refiling a subtree, `C-c C-w`: cutting it, levelling it, and filing it elsewhere. */
class OrgRefileTests {

    private val note = """
        #+TITLE: Work
        * Inbox
        ** TODO Call Sam
        Ring before noon.
        *** Number
        ** Read the paper
        * Projects
        ** Website

        * Someday
    """.trimIndent() + "\n"

    @Test
    fun aCutTakesTheHeadingWithEverythingUnderIt() {
        val cut = note.cutOrgSubtree(2)!! // ** TODO Call Sam
        assertEquals("** TODO Call Sam\nRing before noon.\n*** Number\n", cut.subtree)
        assertEquals(note.replace("** TODO Call Sam\nRing before noon.\n*** Number\n", ""), cut.rest)
        assertEquals(2, cut.level)
        assertNull(note.cutOrgSubtree(0), "the preamble is no heading")
    }

    @Test
    fun aSubtreeIsLevelledByItsTopHeading() {
        assertEquals("* A\nbody\n** B\n", orgSubtreeAtLevel("** A\nbody\n*** B\n", 1))
        assertEquals("**** A\n***** B\n", orgSubtreeAtLevel("** A\n*** B\n", 4))
    }

    @Test
    fun filedUnderAHeadingItGoesAfterItsChildrenAndBeforeItsBlankLines() {
        val projects = note.indexOf("* Projects")
        val filed = "x.org".let { note.withOrgSubtreeFiled("* Garden\n** Seeds\n", projects) }
        assertEquals(
            note.replace("** Website\n\n", "** Website\n** Garden\n*** Seeds\n\n"),
            filed,
        )
    }

    @Test
    fun filedIntoANoteItGoesAtTheEndAsATopLevelHeading() {
        assertEquals("* A\n* Moved\n** Child\n", "* A\n".withOrgSubtreeFiled("*** Moved\n**** Child\n", null))
        assertEquals("* A\n* Moved\n", "* A".withOrgSubtreeFiled("* Moved\n", null), "a note without a final newline")
        assertEquals("* Moved\n", "".withOrgSubtreeFiled("** Moved\n", null))
    }

    @Test
    fun filedUnderAHeadingThatIsGoneIsRefused() {
        assertNull(note.withOrgSubtreeFiled("* X\n", 3))
    }

    @Test
    fun refiledWithinTheNoteToAHeadingBelow() {
        val refiled = note.withOrgSubtreeRefiled(2, note.indexOf("* Projects"))
        assertEquals(
            """
            #+TITLE: Work
            * Inbox
            ** Read the paper
            * Projects
            ** Website
            ** TODO Call Sam
            Ring before noon.
            *** Number

            * Someday
            """.trimIndent() + "\n",
            refiled,
        )
    }

    @Test
    fun refiledWithinTheNoteToAHeadingAbove() {
        val refiled = note.withOrgSubtreeRefiled(6, note.indexOf("* Inbox")) // ** Website
        assertEquals(
            """
            #+TITLE: Work
            * Inbox
            ** TODO Call Sam
            Ring before noon.
            *** Number
            ** Read the paper
            ** Website
            * Projects

            * Someday
            """.trimIndent() + "\n",
            refiled,
        )
    }

    @Test
    fun refiledToTheEndOfItsOwnNote() {
        val refiled = note.withOrgSubtreeRefiled(2, null)
        assertEquals(
            note.replace("** TODO Call Sam\nRing before noon.\n*** Number\n", "") + "* TODO Call Sam\nRing before noon.\n** Number\n",
            refiled,
        )
    }

    @Test
    fun aHeadingCannotBeRefiledIntoItself() {
        assertNull(note.withOrgSubtreeRefiled(1, note.indexOf("* Inbox")))
        assertNull(note.withOrgSubtreeRefiled(1, note.indexOf("*** Number")))
    }

    @Test
    fun theLastHeadingOfANoteWithoutAFinalNewlineMovesWhole() {
        val text = "* A\n* B\nlast line"
        assertEquals("* A\n** B\nlast line\n", text.withOrgSubtreeRefiled(1, 0))
    }
}
