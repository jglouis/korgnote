package com.korgnote.common

import com.korgnote.common.org.*
import com.korgnote.common.org.OrgFootnoteDefinition
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeParsesMetadataKeywordsTests {
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

    /** Emacs allows a keyword to be indented, and the spans and `todoKeywordsOf` already did. */
    @Test
    fun anIndentedKeywordIsAKeyword() {
        val text = "  #+TODO: A | B\n* A Task\n  #+CAPTION: Shown\nwords\n"
        val doc = text.toOrgDocument()
        assertEquals("#+TODO: A | B", assertIs<OrgKeyword>(doc.children[0]).content)
        assertEquals(listOf("#+TODO: A | B", "#+CAPTION: Shown"), doc.metadata)
        assertEquals(setOf("A"), doc.todoKeywords.todo)
        val section = assertIs<OrgSection>(doc.children[1])
        assertEquals("A", section.todo)
        assertEquals("#+CAPTION: Shown", assertIs<OrgKeyword>(section.children[0]).content)
        assertEquals("  #+CAPTION: Shown\n", text.substring(section.children[0].range))
        assertEquals("words", assertIs<OrgRaw>(section.children[1]).content)
    }

    /** The line that ends a footnote's text is the line the main loop reads next as a keyword. */
    @Test
    fun anIndentedKeywordEndsAFootnoteAndIsNotText() {
        val doc = "[fn:1] Words\n  #+RESULTS:\n".toOrgDocument()
        assertEquals("Words", assertIs<OrgFootnoteDefinition>(doc.children[0]).text)
        assertEquals("#+RESULTS:", assertIs<OrgKeyword>(doc.children[1]).content)
    }
}
