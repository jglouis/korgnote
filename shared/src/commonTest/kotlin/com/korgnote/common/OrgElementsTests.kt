package com.korgnote.common

import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.replaceOrgElement
import com.korgnote.common.org.sliceOf
import com.korgnote.common.org.withNewOrgElement
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrgElementsTests {

    private val document = """
        #+TITLE: Notes
        #+TODO: TODO NEXT | DONE

        * TODO First task
        SCHEDULED: <2026-09-20 Sun>
        Body of the first task.
        - [ ] one
        - [x] two

        ** A child heading
        Child body.

        * TODO Second task
        - [ ] three
    """.trimIndent()

    @Test
    fun elementsCoverTheWholeDocumentWithoutGaps() {
        val elements = document.orgElements()
        assertEquals(0, elements.first().start)
        assertEquals(document.length, elements.last().endExclusive)
        for ((previous, next) in elements.zipWithNext()) {
            assertEquals(previous.endExclusive, next.start, "gap or overlap between $previous and $next")
        }
        assertEquals(document, elements.joinToString("") { it.sliceOf(document) })
    }

    @Test
    fun preambleIsTheElementBeforeTheFirstHeading() {
        val elements = document.orgElements()
        val preamble = elements.first()
        assertTrue(preamble.isPreamble)
        assertEquals("#+TITLE: Notes\n#+TODO: TODO NEXT | DONE\n\n", preamble.sliceOf(document))
    }

    @Test
    fun aChildHeadingIsItsOwnElement() {
        val elements = document.orgElements()
        val titles = elements.map { it.title }
        assertEquals(listOf("", "TODO First task", "A child heading", "TODO Second task"), titles)
        assertEquals(listOf(0, 1, 2, 1), elements.map { it.level })

        // The parent stops at the child heading: its own body only, children excluded.
        val first = elements[1].sliceOf(document)
        assertTrue(first.startsWith("* TODO First task"))
        assertTrue(first.contains("- [x] two"))
        assertTrue(!first.contains("A child heading"), "a child heading belongs to its own element")
    }

    @Test
    fun documentStartingWithAHeadingHasNoPreamble() {
        val elements = "* Only\nbody".orgElements()
        assertEquals(1, elements.size)
        assertEquals(1, elements.single().level)
    }

    @Test
    fun degenerateDocuments() {
        assertEquals(1, "".orgElements().size)
        assertEquals("", "".orgElements().single().sliceOf(""))
        assertEquals(1, "plain text".orgElements().size)
        assertEquals(listOf("", "a", "b"), "\n* a\n* b".orgElements().map { it.title })
    }

    /**
     * The splitter and the parser must agree on what a heading is: the range offered for editing is
     * the range written back, so a disagreement would splice over text the user never saw.
     */
    @Test
    fun headingCountMatchesTheParser() {
        val corpus = listOf(
            document,
            "* a\n** b\n*** c\n",
            "no headings here\njust text\n",
            "#+TITLE: x\n* a\n",
            "* a\n\n\n* b\n",
            "  * indented is not a heading\n* but this is\n",
            // A star inside a block is code, not a heading. If the splitter and the parser disagree
            // here, editing an element splices over the wrong range.
            "* real\n#+BEGIN_SRC kotlin\n* not a heading\n#+END_SRC\n* also real\n",
            "* real\n#+begin_example\n** neither is this\n#+end_example\n",
            "* real\n:PROPERTIES:\n:ID: 1\n:END:\n* second\n",
        )
        for (text in corpus) {
            // Headings nest now, so the parser's count is over the whole tree, not the top level.
            val parsedSections = text.toOrgDocument().walk().count { it is OrgSection }
            val splitHeadings = text.orgElements().count { !it.isPreamble }
            assertEquals(parsedSections, splitHeadings, "disagreement on:\n$text")
        }
    }

    @Test
    fun replacingAnElementLeavesTheRestByteIdentical() {
        val elements = document.orgElements()
        val target = elements[1]
        val original = target.sliceOf(document)
        val edited = original.replace("Body of the first task.", "Rewritten body.")

        val result = document.replaceOrgElement(target, original, edited)
        assertEquals(document.replace("Body of the first task.", "Rewritten body."), result)
    }

    @Test
    fun replacingRefusesWhenTheDocumentMovedUnderneath() {
        val elements = document.orgElements()
        val target = elements[1]
        assertNull(
            document.replaceOrgElement(target, "something the element never contained", "new"),
            "a stale range must not be written"
        )
    }

    @Test
    fun replacingKeepsTheFollowingHeadingOnItsOwnLine() {
        val elements = document.orgElements()
        val target = elements[1]
        val original = target.sliceOf(document)

        // The user removed the trailing newline while editing.
        val result = document.replaceOrgElement(target, original, "* TODO First task")
        assertTrue(result!!.contains("* TODO First task\n** A child heading"))
    }

    @Test
    fun replacingTheLastElementDoesNotAppendANewline() {
        val elements = document.orgElements()
        val last = elements.last()
        val result = document.replaceOrgElement(last, last.sliceOf(document), "* TODO Second task")
        assertEquals(true, result!!.endsWith("* TODO Second task"))
    }

    @Test
    fun appendingAHeadingAtTheEndAddsOneElement() {
        val before = document.orgElements().size
        val insertion = document.withNewOrgElement(document.length, level = 1)
        val elements = insertion.text.orgElements()

        assertEquals(before + 1, elements.size)
        assertEquals(elements.last().start, insertion.elementStart)
        assertEquals("* \n", elements.last().sliceOf(insertion.text))
        // The caret lands after the stars and the space, ready for the title.
        assertEquals("* ", insertion.text.substring(insertion.elementStart, insertion.caret))
    }

    @Test
    fun appendingWithoutATrailingNewlineKeepsTheHeadingOnItsOwnLine() {
        val text = "* One\nbody"
        val insertion = text.withNewOrgElement(text.length, level = 1)
        assertEquals("* One\nbody\n* \n", insertion.text)
        assertEquals(2, insertion.text.orgElements().size)
    }

    @Test
    fun insertingAfterAnElementPutsItBetweenTheNeighbours() {
        val elements = document.orgElements()
        val first = elements[1]
        val insertion = document.withNewOrgElement(first.endExclusive, level = first.level)

        assertEquals(
            listOf("", "TODO First task", "", "A child heading", "TODO Second task"),
            insertion.text.orgElements().map { it.title },
        )
        // Everything apart from the insertion is untouched.
        assertEquals(
            document,
            insertion.text.removeRange(insertion.elementStart, insertion.elementStart + "* \n".length),
        )
    }

    @Test
    fun insertingIntoAnEmptyDocument() {
        val insertion = "".withNewOrgElement(0, level = 1)
        assertEquals("* \n", insertion.text)
        assertEquals(2, insertion.caret)
    }

    @Test
    fun insertedHeadingUsesTheRequestedLevel() {
        val child = document.orgElements()[2]
        val insertion = document.withNewOrgElement(child.endExclusive, level = child.level)
        val inserted = insertion.text.orgElements().first { it.start == insertion.elementStart }
        assertEquals(2, inserted.level)
    }

}
