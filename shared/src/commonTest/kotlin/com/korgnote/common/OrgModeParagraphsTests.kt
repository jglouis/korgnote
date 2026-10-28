package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeParagraphsTests {
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
    fun aLineStartingWithBoldTextIsAParagraphNotAHeading() {
        val text = "* Heading\n*bold* at the start\nand more"
        val doc = text.toOrgDocument()
        val section = assertIs<OrgSection>(doc.children.single())
        val paragraph = assertIs<OrgRaw>(section.children.single())
        assertEquals("*bold* at the start\nand more", paragraph.content)
        assertEquals(1, text.orgElements().size)
    }
}
