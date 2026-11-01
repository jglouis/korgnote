package com.korgnote.common

import com.korgnote.common.org.OrgBlock
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDescriptionItem
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgListItem
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.orderedNumbers
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What a list item holds, as Org reads it: everything indented past its bullet — more paragraphs,
 * blocks, tables, lists — until a line indented no further, a heading, or two blank lines.
 */
class OrgListItemContentsTests {

    @Test
    fun aSecondParagraphStaysInItsItemAndTheNumbersRunOn() {
        val text = "1. First item\n\n   Its second paragraph,\n   on two lines.\n2. Second item\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(listOf(1, 2), list.orderedNumbers())
        val first = assertIs<OrgListItem>(list.children[0])
        assertEquals("First item", first.text)
        val paragraph = assertIs<OrgRaw>(first.children.single())
        assertEquals("Its second paragraph,\non two lines.", paragraph.content)
        // The item covers its contents; the paragraph its own lines.
        assertEquals("1. First item\n\n   Its second paragraph,\n   on two lines.\n", text.substring(first.range))
        assertEquals("   Its second paragraph,\n   on two lines.\n", text.substring(paragraph.range))
    }

    @Test
    fun aBlockUnderAnItemIsTheItems() {
        val text = "- Run this:\n  #+begin_src sh\n  ls -l\n  #+end_src\n- Then this\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(2, list.children.size)
        val block = assertIs<OrgBlock>(list.children[0].children.single())
        assertEquals("src", block.type)
        assertEquals("  #+begin_src sh\n  ls -l\n  #+end_src\n", text.substring(block.range))
    }

    @Test
    fun aBlocksOwnLinesNeedNoIndent() {
        // Inside a greater element, indentation does not count.
        val text = "- Output:\n  #+begin_example\nnot indented\n  #+end_example\n- Next\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(2, list.children.size)
        assertEquals("not indented\n", assertIs<OrgBlock>(list.children[0].children.single()).body)
    }

    @Test
    fun aTableUnderAnItemIsTheItems() {
        val text = "- Prices:\n  | apples | 3 |\n  | pears  | 4 |\n- Next\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals(2, assertIs<OrgTable>(list.children[0].children.single()).rows.size)
        assertEquals(2, list.children.size)
    }

    @Test
    fun textAfterANestedListBelongsToTheItemAboveIt() {
        val text = "- Outer\n  - inner one\n  - inner two\n  Back in the outer item.\n- Next\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        val outer = assertIs<OrgListItem>(list.children[0])
        val nested = assertIs<OrgList>(outer.children[0])
        assertEquals(listOf("inner one", "inner two"), nested.children.map { assertIs<OrgListItem>(it).text })
        assertEquals("Back in the outer item.", assertIs<OrgRaw>(outer.children[1]).content)
        assertEquals(2, list.children.size)
    }

    @Test
    fun aLineIndentedNoFurtherThanTheBulletEndsTheList() {
        val doc = "- First item\nnot indented\n- Second item\n".toOrgDocument()
        assertEquals(listOf("OrgList", "OrgRaw", "OrgList"), doc.children.map { it::class.simpleName })
    }

    @Test
    fun twoBlankLinesEndTheItemAndTheList() {
        val doc = "- First\n\n  More of it.\n\n\n  Not any more.\n".toOrgDocument()
        val list = assertIs<OrgList>(doc.children[0])
        assertEquals("More of it.", assertIs<OrgRaw>(list.children.single().children.single()).content)
        assertIs<OrgRaw>(doc.children[1])
    }

    @Test
    fun aHeadingEndsTheList() {
        val doc = "* Heading\n- Item\n  more\n* Next heading\n".toOrgDocument()
        val sections = doc.children.filterIsInstance<OrgSection>()
        assertEquals(2, sections.size)
        val list = assertIs<OrgList>(sections[0].children.single())
        assertEquals("Item\nmore", assertIs<OrgListItem>(list.children.single()).text)
    }

    @Test
    fun checkboxAndDescriptionItemsHoldContentsToo() {
        val text = "- [ ] Pack\n\n  Passport first.\n- Tent :: the big one\n\n  Check the poles.\n"
        val list = assertIs<OrgList>(text.toOrgDocument().children.single())
        assertEquals("Passport first.", assertIs<OrgRaw>(assertIs<OrgCheckboxItem>(list.children[0]).children.single()).content)
        assertEquals("Check the poles.", assertIs<OrgRaw>(assertIs<OrgDescriptionItem>(list.children[1]).children.single()).content)
    }

    @Test
    fun theFileReadsBackAsItWas() {
        val text = "1. a\n\n   b\n   #+begin_quote\n   q\n   #+end_quote\n2. c\n   | x |\n"
        assertEquals(text, text.toOrgDocument().text)
    }
}
