package com.korgnote.common

import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.link.matching
import com.korgnote.common.org.link.withFileId
import com.korgnote.common.org.link.withHeadingId
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
}
