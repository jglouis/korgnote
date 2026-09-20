package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeStarBulletListTests {
    @Test
    fun parsesStarLinesAsSections() {
        val text = """
            * Heading One
            Paragraph one line 1
            Paragraph one line 2
            
            * Heading Two
            Content two
        """.trimIndent()
        val doc = text.toOrgDocument()
        // With updated parser, lines starting with '*' are parsed as sections
        assertEquals(2, doc.children.size)
        val sec1 = assertIs<OrgSection>(doc.children[0]); assertEquals("Heading One", sec1.title)
        assertEquals("Paragraph one line 1\nParagraph one line 2", sec1.bodyText())
        val sec2 = assertIs<OrgSection>(doc.children[1]); assertEquals("Heading Two", sec2.title)
        assertEquals("Content two", sec2.bodyText())
    }
}
