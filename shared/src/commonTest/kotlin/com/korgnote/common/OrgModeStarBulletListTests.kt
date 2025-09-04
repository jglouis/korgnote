package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeStarBulletListTests {
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
}
