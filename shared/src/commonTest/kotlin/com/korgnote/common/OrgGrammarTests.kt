package com.korgnote.common

import com.korgnote.common.org.OrgBlock
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgComment
import com.korgnote.common.org.OrgDescriptionItem
import com.korgnote.common.org.OrgDrawer
import com.korgnote.common.org.OrgFootnoteDefinition
import com.korgnote.common.org.OrgHorizontalRule
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The constructs T5 added to the grammar.
 *
 * Two things matter for each: it appears in the tree as itself, and the document still round-trips
 * byte for byte. The second is what makes it safe to teach the parser new things — an unknown or
 * half-understood construct must survive an edit rather than be rewritten.
 */
class OrgGrammarTests {

    private fun assertRoundTrips(text: String) =
        assertEquals(text, text.toOrgDocument().text, "round trip failed for:\n$text")

    @Test
    fun aSourceBlockIsOneOpaqueNode() {
        val text = "* Head\n#+BEGIN_SRC kotlin\nval x = 1\n#+END_SRC\n"
        val section = text.toOrgDocument().children.single() as OrgSection
        val block = section.children.filterIsInstance<OrgBlock>().single()

        assertEquals("SRC", block.type)
        assertEquals(" kotlin", block.parameters)
        assertEquals("val x = 1\n", block.body)
        assertRoundTrips(text)
    }

    /** The reason blocks are opaque: their contents are not Org. */
    @Test
    fun orgSyntaxInsideABlockIsNotParsed() {
        val text = "* Real\n#+BEGIN_SRC kotlin\n* not a heading\n- not a list\n#+END_SRC\n* Also real\n"
        val document = text.toOrgDocument()

        assertEquals(listOf("Real", "Also real"), document.children.filterIsInstance<OrgSection>().map { it.title })
        val block = (document.children.first() as OrgSection).children.filterIsInstance<OrgBlock>().single()
        assertTrue(block.body.contains("* not a heading"))
        assertRoundTrips(text)
    }

    @Test
    fun anUnterminatedBlockIsStillANodeAndStillRoundTrips() {
        val text = "#+BEGIN_EXAMPLE\nno end marker\n"
        val block = text.toOrgDocument().children.filterIsInstance<OrgBlock>().single()

        assertTrue(block.unterminated)
        assertRoundTrips(text)
    }

    @Test
    fun aPropertiesDrawerBecomesADrawerWithItsProperties() {
        val text = "* Head\n:PROPERTIES:\n:ID: abc-123\n:LOCATION: Brussels\n:END:\nBody.\n"
        val section = text.toOrgDocument().children.single() as OrgSection
        val drawer = section.children.filterIsInstance<OrgDrawer>().single()

        assertEquals("PROPERTIES", drawer.name)
        assertEquals(listOf("ID" to "abc-123", "LOCATION" to "Brussels"), drawer.properties)
        assertRoundTrips(text)
    }

    @Test
    fun anOrderedListIsMarkedOrdered() {
        val text = "1. first\n2. second\n3. third\n"
        val list = text.toOrgDocument().children.single() as OrgList

        assertTrue(list.ordered)
        assertEquals(listOf("first", "second", "third"), list.children.map { (it as OrgListItem).text })
        assertRoundTrips(text)
    }

    @Test
    fun aNestedListBelongsToTheItemAboveIt() {
        val text = "- outer one\n  - inner a\n  - inner b\n- outer two\n"
        val list = text.toOrgDocument().children.single() as OrgList

        assertEquals(listOf("outer one", "outer two"), list.children.map { (it as OrgListItem).text })
        val nested = (list.children.first() as OrgListItem).children.single() as OrgList
        assertEquals(listOf("inner a", "inner b"), nested.children.map { (it as OrgListItem).text })
        assertEquals(2, nested.indent)
        assertRoundTrips(text)
    }

    @Test
    fun aCheckboxItemCanOwnANestedList() {
        val text = "- [ ] parent task\n  - [x] sub task\n"
        val list = text.toOrgDocument().children.single() as OrgList
        val parent = list.children.single() as OrgCheckboxItem

        assertEquals("parent task", parent.text)
        val nested = parent.children.single() as OrgList
        assertEquals("sub task", (nested.children.single() as OrgCheckboxItem).text)
        assertRoundTrips(text)
    }

    @Test
    fun aPriorityCookieIsNotPartOfTheTitle() {
        val text = "* TODO [#A] Important thing                :work:\n"
        val section = text.toOrgDocument().children.single() as OrgSection

        assertEquals('A', section.priority)
        assertEquals("Important thing", section.title)
        assertEquals(listOf("work"), section.tags)
        assertRoundTrips(text)
    }

    @Test
    fun aHeadingWithoutAPriorityHasNone() {
        val section = "* TODO Plain\n".toOrgDocument().children.single() as OrgSection
        assertNull(section.priority)
        assertEquals("Plain", section.title)
    }

    @Test
    fun commentsRulesAndFootnotesBecomeTheirOwnNodes() {
        val text = "# a comment\n-----\n[fn:1] the footnote text\n"
        val children = text.toOrgDocument().children

        assertEquals("# a comment", children.filterIsInstance<OrgComment>().single().text)
        assertEquals(1, children.filterIsInstance<OrgHorizontalRule>().size)
        val footnote = children.filterIsInstance<OrgFootnoteDefinition>().single()
        assertEquals("1", footnote.label)
        assertEquals("the footnote text", footnote.text)
        assertRoundTrips(text)
    }

    @Test
    fun aDescriptionListItemKeepsTermAndDescription() {
        val text = "- Kotlin :: a language\n- Org :: a format\n"
        val list = text.toOrgDocument().children.single() as OrgList
        val items = list.children.filterIsInstance<OrgDescriptionItem>()

        assertEquals(listOf("Kotlin", "Org"), items.map { it.term })
        assertEquals(listOf("a language", "a format"), items.map { it.description })
        assertRoundTrips(text)
    }

    /**
     * A list must stop at the next heading.
     *
     * The list grammar matches `*` as a bullet, so a plain list happily swallowed every heading that
     * followed it — 272 headings across the real notes directory, invisible to a hand-written corpus
     * because the two constructs have to be adjacent for it to bite.
     */
    @Test
    fun aListStopsAtTheNextHeading() {
        val text = "* First\n- item one\n- item two\n* DONE Second                :tag:\n* Third\n"
        val document = text.toOrgDocument()

        assertEquals(
            listOf("First", "Second", "Third"),
            document.walk().filterIsInstance<OrgSection>().map { it.title },
        )
        val list = (document.children.first() as OrgSection).children.filterIsInstance<OrgList>().single()
        assertEquals(listOf("item one", "item two"), list.children.map { (it as OrgListItem).text })
        assertRoundTrips(text)
    }

    /** The splitter and the parser must still agree once the grammar knows more constructs. */
    @Test
    fun theSplitterAgreesWithTheParserOnTheNewConstructs() {
        val corpus = listOf(
            "* First\n- item\n* Second\n",
            "* A\n#+BEGIN_SRC\n* fake\n#+END_SRC\n* B\n",
            "* A\n:PROPERTIES:\n:ID: 1\n:END:\n* B\n",
            "- a\n+ b\n* Real\n",
            "1. one\n2. two\n* Real\n",
        )
        for (text in corpus) {
            assertEquals(
                text.toOrgDocument().walk().count { it is OrgSection },
                text.orgElements().count { !it.isPreamble },
                "parser and splitter disagree on:\n$text",
            )
        }
    }

    /** A different bullet starts a different list. */
    @Test
    fun aDifferentMarkerStartsANewList() {
        val lists = "- dash one\n+ plus one\n".toOrgDocument().children.filterIsInstance<OrgList>()
        assertEquals(listOf('-', '+'), lists.map { it.bulletType })
    }

    /** T5's acceptance: a corpus of the new constructs round-trips losslessly. */
    @Test
    fun theWholeGrammarRoundTrips() {
        val corpus = listOf(
            "#+TITLE: Everything\n# a comment\n-----\n\n* TODO [#B] Task              :home:\n" +
                ":PROPERTIES:\n:ID: 1\n:END:\nSCHEDULED: <2026-09-21 Mon>\n" +
                "Body.\n\n1. one\n2. two\n   - nested\n\n- [ ] todo\n- [x] done\n\n" +
                "#+BEGIN_SRC kotlin\n* not a heading\n#+END_SRC\n\n[fn:note] a footnote\n",
            "#+BEGIN_QUOTE\nquoted\n#+END_QUOTE\n",
            ":LOGBOOK:\nnever terminated\n",
            "- a :: b\n- c :: d\n",
            "1) alt marker\n2) again\n",
            "",
            "\n\n\n",
        )
        corpus.forEach { assertRoundTrips(it) }
    }
}
