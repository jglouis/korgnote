package com.korgnote.common

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.replace
import com.korgnote.common.org.edit.setTodo
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.orgHeadingOf
import com.korgnote.common.org.readWith
import com.korgnote.common.org.search.searchOrg
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A heading's first word is its keyword only when the file's keywords say so, as in Org. */
class OrgHeadingKeywordsTest {

    private val events = OrgTodoConfig(todo = setOf("EVENT"), done = setOf("FINISHED"))

    @Test
    fun aCapitalisedWordTheFileDoesNotDeclareIsPartOfTheTitle() {
        val heading = orgHeadingOf("* NASA plans :space:", DEFAULT_TODO_KEYWORDS)
        assertNull(heading.todo)
        assertEquals("NASA plans", heading.title)
        assertEquals(listOf("space"), heading.tags)
    }

    @Test
    fun aDeclaredKeywordIsTheHeadingsKeyword() {
        val heading = orgHeadingOf("** FINISHED [#A] Party", events)
        assertEquals("FINISHED", heading.todo)
        assertEquals('A', heading.priority)
        assertEquals("Party", heading.title)
    }

    @Test
    fun aPriorityAfterAWordThatIsNoKeywordIsTitle() {
        val heading = orgHeadingOf("* NASA [#A] plans", DEFAULT_TODO_KEYWORDS)
        assertNull(heading.priority)
        assertEquals("NASA [#A] plans", heading.title)
    }

    @Test
    fun aHeadingThatIsOnlyItsKeywordHasAnEmptyTitle() {
        val heading = orgHeadingOf("* TODO", DEFAULT_TODO_KEYWORDS)
        assertEquals("TODO", heading.todo)
        assertEquals("", heading.title)
    }

    @Test
    fun aDocumentReadsItsHeadingsWithItsOwnKeywordsOverTheFallback() {
        val doc = "#+TODO: NEXT | SHIPPED\n* SHIPPED Release\n* FINISHED Party\n".toOrgDocument(events)
        val (release, party) = doc.children.filterIsInstance<OrgSection>()
        assertEquals("SHIPPED", release.todo)
        assertNull(party.todo)
        assertEquals("FINISHED Party", party.title)
    }

    /** Setting a keyword on `* NASA plans` used to replace `NASA`, which the parser took for one. */
    @Test
    fun settingAKeywordKeepsAWordThatIsNoKeyword() {
        val doc = "* NASA plans\n".toOrgDocument()
        assertEquals("* TODO NASA plans\n", doc.setTodo(doc.children.single() as OrgSection, "TODO").text)
    }

    @Test
    fun anEditReadsTheDocumentWithTheSameFallback() {
        val doc = "* FINISHED Party\n".toOrgDocument(events)
        val edited = doc.replace(doc.text.length until doc.text.length, "* EVENT Dentist\n")
        assertEquals(listOf("FINISHED", "EVENT"), edited.children.filterIsInstance<OrgSection>().map { it.todo })
    }

    @Test
    fun readingWithAnotherFallbackRereadsOnlyANoteThatDeclaresNothing() {
        val declares = "#+TODO: EVENT | FINISHED\n* FINISHED Party\n".toOrgDocument()
        assertSame(declares, declares.readWith(OrgTodoConfig(setOf("X"), setOf("Y"))))

        val relies = "* FINISHED Party\n".toOrgDocument()
        assertSame(relies, relies.readWith(DEFAULT_TODO_KEYWORDS))
        assertEquals("FINISHED", assertIs<OrgSection>(relies.readWith(events).children.single()).todo)
    }

    @Test
    fun searchNamesAHeadingByItsWholeTitle() {
        val hits = searchOrg(mapOf("space.org" to "* NASA plans\nMoon base.\n"), "moon")
        assertEquals(listOf("NASA plans"), hits.single().breadcrumb)
    }

    @Test
    fun aBacklinkUnderAHeadingNamesItWithoutItsKeyword() {
        val notes = mapOf(
            "a.org" to "#+TODO: EVENT | FINISHED\n",
            "b.org" to "* FINISHED Party\nSee [[file:c.org][c]].\n",
            "c.org" to "* C\n",
        )
        assertEquals("Party", OrgLinkIndex(notes).backlinksTo("c.org").single().context)
    }
}
