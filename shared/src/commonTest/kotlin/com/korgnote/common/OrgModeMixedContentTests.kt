package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeMixedContentTests {
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
        // With updated parser: keyword, intro paragraph, '-' list, section 'Head' with two paragraphs and a table inside its content (flattened as raw). Since our simple model keeps section content as raw text and stops at next heading, and here there is no next heading, we expect:
        // keyword, intro paragraph, '-' list, section, and remaining lines accumulated as section content.
        assertEquals(4, doc.children.size)
        assertIs<OrgKeyword>(doc.children[0])
        val intro = assertIs<OrgRaw>(doc.children[1]); assertEquals("Intro para line 1\nintro line 2", intro.content)
        val listDash = assertIs<OrgList>(doc.children[2]); assertEquals('-', listDash.bulletType); assertEquals(2, listDash.children.size)
        val section = assertIs<OrgSection>(doc.children[3]); assertEquals("Head", section.title)
        // Our parser captures section content raw until next heading; thus it includes the following lines
        assertEquals("under head\n| a | b |\n| 1 | 2 |\nEnd", section.content)
    }

    @Test
    fun mixedContentCheckBoxSectionsParagraphs() {
        val text = "* Website Redesign\n** Milestones\n- [ ] Wireframes\n- [ ] Prototype\n- [ ] Launch"
        val doc = text.toOrgDocument()
        // Updated expectation: sections are parsed for headings, and a list immediately following a heading is parsed as a list node, not swallowed as raw section content.
        assertEquals(3, doc.children.size)
        val top = assertIs<OrgSection>(doc.children[0]); assertEquals("Website Redesign", top.title); assertEquals("", top.content)
        val sec = assertIs<OrgSection>(doc.children[1]); assertEquals("Milestones", sec.title); assertEquals("", sec.content)
        val list = assertIs<OrgList>(doc.children[2]); assertEquals('-', list.bulletType); assertEquals(3, list.children.size)
        val c1 = assertIs<OrgCheckboxItem>(list.children[0]); assertEquals(false, c1.checked); assertEquals("Wireframes", c1.text)
        val c2 = assertIs<OrgCheckboxItem>(list.children[1]); assertEquals(false, c2.checked); assertEquals("Prototype", c2.text)
        val c3 = assertIs<OrgCheckboxItem>(list.children[2]); assertEquals(false, c3.checked); assertEquals("Launch", c3.text)
    }
}
