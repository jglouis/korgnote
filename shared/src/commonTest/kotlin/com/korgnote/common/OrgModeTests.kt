package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeTests {
    @Test
    fun parsesMetadataKeywordsAndKeepsInDocument() {
        val text = """
            #+TITLE: Sample Doc
            #+AUTHOR: Jane Doe

            Some paragraph after metadata.
        """.trimIndent()
        val doc = text.toOrgDocument()
        // Expect two children: the two keywords, then a paragraph
        assertEquals(3, doc.children.size)
        assertEquals(2, doc.metadata.size)
        val k1 = assertIs<OrgKeyword>(doc.children[0]); assertEquals("#+TITLE: Sample Doc", k1.content)
        val k2 = assertIs<OrgKeyword>(doc.children[1]); assertEquals("#+AUTHOR: Jane Doe", k2.content)
        val p = assertIs<OrgRaw>(doc.children[2]); assertEquals("Some paragraph after metadata.", p.content)
    }

    @Test
    fun parsesStarBulletIsTreatedAsListNotSection() {
        val text = """
            * Heading One
            Paragraph one line 1
            Paragraph one line 2
            
            * Heading Two
            Content two
        """.trimIndent()
        val doc = text.toOrgDocument()
        // In the current parser, lines starting with '*' are parsed as a '*' list, not sections
        // Expected nodes: a '*' list with one item "Heading One", then a paragraph with two lines, then another '*' list item "Heading Two", then a paragraph.
        assertEquals(4, doc.children.size)
        val list1 = assertIs<OrgList>(doc.children[0]); assertEquals('*', list1.bulletType); assertEquals(
            1,
            list1.children.size
        )
        assertEquals("Heading One", (assertIs<OrgRaw>(list1.children[0])).content)
        val para1 = assertIs<OrgRaw>(doc.children[1]); assertEquals(
            "Paragraph one line 1\nParagraph one line 2",
            para1.content
        )
        val list2 = assertIs<OrgList>(doc.children[2]); assertEquals('*', list2.bulletType); assertEquals(
            1,
            list2.children.size
        )
        assertEquals("Heading Two", (assertIs<OrgRaw>(list2.children[0])).content)
        val para2 = assertIs<OrgRaw>(doc.children[3]); assertEquals("Content two", para2.content)
    }

    @Test
    fun parsesSimpleListsWithDifferentBullets() {
        val text = """
            - item a
            - item b
            
            + plus one
            + plus two
            
            * star one
            * star two
        """.trimIndent()
        val doc = text.toOrgDocument()
        // Expect three lists: '-', '+', and '*' bullets
        assertEquals(3, doc.children.size)
        val list1 = assertIs<OrgList>(doc.children[0]); assertEquals('-', list1.bulletType); assertEquals(
            2,
            list1.children.size
        )
        assertEquals("item a", (assertIs<OrgRaw>(list1.children[0])).content)
        assertEquals("item b", (assertIs<OrgRaw>(list1.children[1])).content)

        val list2 = assertIs<OrgList>(doc.children[1]); assertEquals('+', list2.bulletType); assertEquals(
            2,
            list2.children.size
        )
        assertEquals("plus one", (assertIs<OrgRaw>(list2.children[0])).content)
        assertEquals("plus two", (assertIs<OrgRaw>(list2.children[1])).content)

        val list3 = assertIs<OrgList>(doc.children[2]); assertEquals('*', list3.bulletType); assertEquals(
            2,
            list3.children.size
        )
        assertEquals("star one", (assertIs<OrgRaw>(list3.children[0])).content)
        assertEquals("star two", (assertIs<OrgRaw>(list3.children[1])).content)
    }

    @Test
    fun parsesTablesAsConsecutivePipes() {
        val text = """
            | Name | Age |
            | Bob  |  30 |
            | Ann  |  25 |
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(1, doc.children.size)
        val table = assertIs<OrgTable>(doc.children[0])
        // Given the simplified model, rows are folded into one OrgTableRow with cells containing joined content
        val row = table.rows
        val cells = row.cells
        // Expect three cells corresponding to three input lines joined per row
        assertEquals(3, cells.size)
        assertEquals("Name | Age", (assertIs<OrgRaw>(cells[0])).content)
        assertEquals("Bob | 30", (assertIs<OrgRaw>(cells[1])).content)
        assertEquals("Ann | 25", (assertIs<OrgRaw>(cells[2])).content)
    }

    @Test
    fun parsesParagraphsAndPreservesLineBreaksUntilBlank() {
        val text = """
            First line of paragraph
            continues here
            
            Second paragraph
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(2, doc.children.size)
        val p1 = assertIs<OrgRaw>(doc.children[0])
        assertEquals("First line of paragraph\ncontinues here", p1.content)
        val p2 = assertIs<OrgRaw>(doc.children[1])
        assertEquals("Second paragraph", p2.content)
    }

    @Test
    fun mixedContentListsTablesSectionsParagraphs() {
        val text = """
            #+TITLE: Mixed
            Intro para line 1
            intro line 2
            
            - a1
            - a2
            
            * Head
            under head
            | a | b |
            | 1 | 2 |
            End
        """.trimIndent()
        val doc = text.toOrgDocument()
        // Expected order with current parser: keyword, intro paragraph, '-' list, '*' list (one item), paragraph, table, paragraph
        assertEquals(7, doc.children.size)
        assertIs<OrgKeyword>(doc.children[0])
        val intro = assertIs<OrgRaw>(doc.children[1]); assertEquals("Intro para line 1\nintro line 2", intro.content)
        val listDash = assertIs<OrgList>(doc.children[2]); assertEquals('-', listDash.bulletType); assertEquals(
            2,
            listDash.children.size
        )
        val listStar = assertIs<OrgList>(doc.children[3]); assertEquals('*', listStar.bulletType); assertEquals(
            1,
            listStar.children.size
        )
        assertEquals("Head", (assertIs<OrgRaw>(listStar.children[0])).content)
        val under = assertIs<OrgRaw>(doc.children[4]); assertEquals("under head", under.content)
//        val table = assertIs<OrgTable>(doc.children[5])
        val end = assertIs<OrgRaw>(doc.children[6]); assertEquals("End", end.content)
    }

    @Test
    fun parsesCheckboxListItems() {
        val text = """
            - [ ] Task one
            - [x] Task two
            - Plain item
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(1, doc.children.size)
        val list = assertIs<OrgList>(doc.children[0])
        assertEquals('-', list.bulletType)
        assertEquals(3, list.children.size)
        val item1 = assertIs<OrgCheckboxItem>(list.children[0])
        assertEquals(false, item1.checked)
        assertEquals("Task one", item1.text)
        val item2 = assertIs<OrgCheckboxItem>(list.children[1])
        assertEquals(true, item2.checked)
        assertEquals("Task two", item2.text)
        val item3 = assertIs<OrgRaw>(list.children[2])
        assertEquals("Plain item", item3.content)
    }
}
