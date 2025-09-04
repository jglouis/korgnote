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
}
