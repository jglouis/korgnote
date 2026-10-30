package com.korgnote.common

import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDescriptionItem
import com.korgnote.common.org.OrgFootnoteDefinition
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.joinOrgLines
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.toOrgString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Text filled to a `fill-column` in Emacs: an item or a footnote broken over several lines is still
 * one item or one footnote, and is shown as one run of text that wraps to the screen.
 */
class OrgFilledLinesTests {

    @Test
    fun anItemsIndentedLinesAreItsText() {
        val text = "- a long item that Emacs\n  filled over two lines\n- the next item\n"
        val list = text.toOrgDocument().children.single() as OrgList

        assertEquals(
            listOf("a long item that Emacs\nfilled over two lines", "the next item"),
            list.children.map { (it as OrgListItem).text },
        )
        assertEquals(text.indexOf("- the next"), list.children[1].range.first)
        assertEquals(text, text.toOrgDocument().text)
    }

    @Test
    fun aLineThatIsNotIndentedEndsTheList() {
        val text = "- an item\na paragraph after it\n"
        val children = text.toOrgDocument().children

        assertEquals("an item", ((children[0] as OrgList).children.single() as OrgListItem).text)
        assertEquals("a paragraph after it", (children[1] as OrgRaw).content)
    }

    @Test
    fun aNestedItemsLinesStayWithIt() {
        val text = "- outer\n  - inner item that\n    goes on\n  - inner two\n- outer two\n"
        val list = text.toOrgDocument().children.single() as OrgList

        assertEquals(listOf("outer", "outer two"), list.children.map { (it as OrgListItem).text })
        val nested = (list.children[0] as OrgListItem).children.single() as OrgList
        assertEquals(listOf("inner item that\ngoes on", "inner two"), nested.children.map { (it as OrgListItem).text })
    }

    @Test
    fun anItemsTextEndsWhereANestedListBegins() {
        val text = "- outer that\n  goes on\n  - inner\n"
        val outer = (text.toOrgDocument().children.single() as OrgList).children.single() as OrgListItem

        assertEquals("outer that\ngoes on", outer.text)
        assertEquals("inner", ((outer.children.single() as OrgList).children.single() as OrgListItem).text)
    }

    @Test
    fun aCheckboxAndADescriptionCarryOnToo() {
        val text = "- [ ] buy the long\n  list of things\n- term :: a definition\n  that goes on\n"
        val list = text.toOrgDocument().children.single() as OrgList

        assertEquals("buy the long\nlist of things", assertIs<OrgCheckboxItem>(list.children[0]).text)
        val description = assertIs<OrgDescriptionItem>(list.children[1])
        assertEquals("term", description.term)
        assertEquals("a definition\nthat goes on", description.description)
    }

    @Test
    fun anIndentedBlockOrHeadingIsNotPartOfTheItem() {
        val text = "- item\n  #+BEGIN_SRC sh\n  ls\n  #+END_SRC\n* Heading\n"
        val children = text.toOrgDocument().children

        assertEquals("item", ((children[0] as OrgList).children.single() as OrgListItem).text)
        assertIs<OrgSection>(children.last())
    }

    @Test
    fun aFootnoteCarriesOnUntilABlankLine() {
        val text = "[fn:1] a footnote that\nwas filled\n\nnot the footnote\n"
        val children = text.toOrgDocument().children
        val footnote = children[0] as OrgFootnoteDefinition

        assertEquals("a footnote that\nwas filled", footnote.text)
        assertEquals(0 until text.indexOf("\nnot"), footnote.range)
        assertEquals("not the footnote", (children[1] as OrgRaw).content)
    }

    @Test
    fun aMultiLineItemPrintsIndentedUnderItsBullet() {
        val text = "- one that\n  goes on\n  - nested\n- two"

        assertEquals(text, text.toOrgDocument().toOrgString())
    }

    @Test
    fun linesAreJoinedWithSpaces() {
        assertEquals("one two three", joinOrgLines("one\n  two \nthree"))
    }

    @Test
    fun aDoubleBackslashKeepsItsLineBreak() {
        assertEquals("first line\nsecond line and more", joinOrgLines("first line \\\\\nsecond line\nand more"))
        assertEquals("ends with a break", joinOrgLines("ends with a break\\\\  "))
    }
}
