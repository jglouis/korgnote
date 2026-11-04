package com.korgnote.common

import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.link.matching
import com.korgnote.common.org.link.withFileId
import com.korgnote.common.org.link.withHeadingId
import com.korgnote.common.org.orgNoteTitle
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTargetsTest {

    private val project = """
        :PROPERTIES:
        :ID:       note-id
        :END:
        #+TITLE: Project
        * TODO [#A] Plan [1/2] :work:
        :PROPERTIES:
        :ID:       plan-id
        :END:
        ** DONE Read [[https://example.com][the spec]]
        ** TODO Write
        * Notes
    """.trimIndent() + "\n"

    private val targets = linkTargetsOf(
        mapOf("project.org" to project.toOrgDocument(), "a/inbox.org" to "* Call Bob\n".toOrgDocument()),
    )

    @Test
    fun everyNoteAndHeadingIsATargetWithItsOutline() {
        val listed = targets.map { listOf(it.path, it.where, it.title, it.id ?: "-") }
        assertEquals(
            listOf(
                listOf("a/inbox.org", "a/inbox.org", "inbox", "-"),
                listOf("a/inbox.org", "inbox", "Call Bob", "-"),
                listOf("project.org", "project.org", "Project", "note-id"),
                listOf("project.org", "Project", "Plan", "plan-id"),
                listOf("project.org", "Project › Plan", "Read the spec", "-"),
                listOf("project.org", "Project › Plan", "Write", "-"),
                listOf("project.org", "Project", "Notes", "-"),
            ),
            listed,
        )
        val write = targets.single { it.title == "Write" }
        assertEquals(project.indexOf("** TODO Write"), write.offset)
        assertEquals("[[id:x][Write]]", write.linkWith("x"))
    }

    @Test
    fun aQueryMatchesWordsAnywhereAndPutsTheCurrentNoteFirst() {
        assertEquals(
            listOf("Read the spec", "Write"),
            targets.matching("project plan", "x.org").filter { it.outline.isNotEmpty() }.map(LinkTarget::title),
        )
        assertEquals(listOf("Call Bob"), targets.matching("bob", "").map(LinkTarget::title))
        assertEquals("a/inbox.org", targets.matching("", "a/inbox.org").first().path)
        assertEquals("project.org", targets.matching("", "project.org").first().path)
    }

    @Test
    fun aHeadingWithoutADrawerGetsOneUnderItsPlanningLine() {
        val text = "* Meeting\nSCHEDULED: <2026-10-27 Tue>\nBody.\n* Next\n"
        assertEquals(
            "* Meeting\nSCHEDULED: <2026-10-27 Tue>\n:PROPERTIES:\n:ID:       new\n:END:\nBody.\n* Next\n",
            withHeadingId(text, 0, "new"),
        )
        assertEquals("* A\n* B\n:PROPERTIES:\n:ID:       new\n:END:\n", withHeadingId("* A\n* B\n", 4, "new"))
        assertEquals("* A\n:PROPERTIES:\n:ID:       new\n:END:\n", withHeadingId("* A", 0, "new"))
    }

    @Test
    fun aHeadingWithADrawerGetsTheIdAddedToItAndOneWithAnIdKeepsIt() {
        val text = "* Place\n:PROPERTIES:\n:LOCATION: Paris\n:END:\n"
        assertEquals("* Place\n:PROPERTIES:\n:LOCATION: Paris\n:ID:       new\n:END:\n", withHeadingId(text, 0, "new"))
        val offset = project.indexOf("* TODO [#A] Plan")
        assertEquals(project, withHeadingId(project, offset, "other"))
        assertEquals(project, withHeadingId(project, 3, "not a heading"))
    }

    @Test
    fun aNoteGetsItsOwnIdAtTheTop() {
        assertEquals(":PROPERTIES:\n:ID:       new\n:END:\n#+TITLE: T\n* H\n", withFileId("#+TITLE: T\n* H\n", "new"))
        assertEquals(
            ":PROPERTIES:\n:ROAM_ALIASES: x\n:ID:       new\n:END:\n",
            withFileId(":PROPERTIES:\n:ROAM_ALIASES: x\n:END:\n", "new"),
        )
        assertEquals(project, withFileId(project, "other"))
        assertNull(targets.first { it.path == "a/inbox.org" }.id)
    }

    /** A blank `#+TITLE:` names nothing; the line under it is not its value. */
    @Test
    fun aBlankTitleFallsBackToTheFileName() {
        val note = linkTargetsOf(mapOf("notes/a.org" to "#+TITLE:\n* Inbox\n".toOrgDocument())).first()
        assertEquals("a", note.noteTitle)
    }

    @Test
    fun anIndentedTitleIsTheNotesTitle() {
        val note = linkTargetsOf(mapOf("notes/a.org" to "  #+TITLE: Garden\n".toOrgDocument())).first()
        assertEquals("Garden", note.noteTitle)
    }

    @Test
    fun aNoteTitleIsTheFirstOneWithAValueOutsideABlock() {
        assertEquals("Garden", orgNoteTitle("#+title:   Garden  \n"))
        assertEquals("Garden", orgNoteTitle("  #+TITLE: Garden\n"))
        assertEquals("Garden", orgNoteTitle("* Heading\n#+TITLE: Garden\n"))
        assertEquals("Garden", orgNoteTitle("#+TITLE:\n#+TITLE: Garden\n"))
        assertEquals("Garden", orgNoteTitle("#+BEGIN_SRC org\n#+TITLE: Code\n#+END_SRC\n#+TITLE: Garden\n"))
        assertEquals(null, orgNoteTitle("#+TITLE:\n* Inbox\n"))
        assertEquals(null, orgNoteTitle("#+TITLES: no\n# #+TITLE: no\n"))
    }

    /** A heading whose `:ID:` is blank has none: it is offered as needing one, and gets it there. */
    @Test
    fun aBlankIdIsFilledInSoTheLinkMadeToItLands() {
        val text = "* Plan\n:PROPERTIES:\n:ID:\n:END:\n"
        assertNull(linkTargetsOf(mapOf("a.org" to text.toOrgDocument())).last().id)

        val given = withHeadingId(text, 0, "new-id")
        assertEquals("* Plan\n:PROPERTIES:\n:ID:       new-id\n:END:\n", given)
        assertEquals(OrgLinkResolution.Note("a.org", given.indexOf(":ID:")), OrgLinkIndex(mapOf("a.org" to given)).resolve("id:new-id", "a.org"))
    }

    @Test
    fun aBlankFileIdIsFilledInToo() {
        assertEquals(":PROPERTIES:\n  :ID:       new-id\n:END:\n* H\n", withFileId(":PROPERTIES:\n  :ID:   \n:END:\n* H\n", "new-id"))
    }

    /** Everything after `:ID:` is the id, as Org reads a property: what is offered is what resolves. */
    @Test
    fun anIdWithASpaceIsOfferedAndResolved() {
        val text = ":PROPERTIES:\n:ID: note id\n:END:\n* Plan\n:PROPERTIES:\n:ID: a b\n:END:\n"
        val offered = linkTargetsOf(mapOf("a.org" to text.toOrgDocument()))
        assertEquals(listOf("note id", "a b"), offered.map { it.id })

        val index = OrgLinkIndex(mapOf("a.org" to text))
        assertEquals(OrgLinkResolution.Note("a.org", text.indexOf(":ID: a b")), index.resolve("id:a b", "a.org"))
        assertEquals("note id", index.fileIdOf("a.org"))
    }
}
