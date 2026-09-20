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
        // Everything before the heading stays at document level; everything after it belongs to it.
        assertEquals(4, doc.children.size)
        assertIs<OrgKeyword>(doc.children[0])
        val intro = assertIs<OrgRaw>(doc.children[1]); assertEquals("Intro para line 1\nintro line 2", intro.content)
        val listDash = assertIs<OrgList>(doc.children[2]); assertEquals('-', listDash.bulletType); assertEquals(2, listDash.children.size)
        val section = assertIs<OrgSection>(doc.children[3]); assertEquals("Head", section.title)
        // The table under the heading is a real node now. It used to be swallowed into the section's
        // raw content string, which is exactly what moving bodies into child nodes fixes.
        assertEquals(3, section.children.size)
        assertEquals("under head", assertIs<OrgRaw>(section.children[0]).content)
        val table = assertIs<OrgTable>(section.children[1]); assertEquals(2, table.rows.size)
        assertEquals("End", assertIs<OrgRaw>(section.children[2]).content)
    }

    @Test
    fun mixedContentCheckBoxSectionsParagraphs() {
        val text = "* Website Redesign\n** Milestones\n- [ ] Wireframes\n- [ ] Prototype\n- [ ] Launch"
        val doc = text.toOrgDocument()
        // One top-level heading, owning the deeper heading, which in turn owns the list.
        assertEquals(1, doc.children.size)
        val top = assertIs<OrgSection>(doc.children[0]); assertEquals("Website Redesign", top.title); assertEquals("", top.bodyText())
        val sec = top.subsections.single(); assertEquals("Milestones", sec.title); assertEquals("", sec.bodyText())
        val list = assertIs<OrgList>(sec.children.single()); assertEquals('-', list.bulletType); assertEquals(3, list.children.size)
        val c1 = assertIs<OrgCheckboxItem>(list.children[0]); assertEquals(false, c1.checked); assertEquals("Wireframes", c1.text)
        val c2 = assertIs<OrgCheckboxItem>(list.children[1]); assertEquals(false, c2.checked); assertEquals("Prototype", c2.text)
        val c3 = assertIs<OrgCheckboxItem>(list.children[2]); assertEquals(false, c3.checked); assertEquals("Launch", c3.text)
    }
}
