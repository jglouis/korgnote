package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeSimpleListsTests {
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
        // With updated parser: '-' and '+' are lists; '*' becomes sections
        assertEquals(4, doc.children.size)
        val list1 = assertIs<OrgList>(doc.children[0]); assertEquals('-', list1.bulletType); assertEquals(2, list1.children.size)
        assertEquals("item a", (assertIs<OrgRaw>(list1.children[0])).content)
        assertEquals("item b", (assertIs<OrgRaw>(list1.children[1])).content)

        val list2 = assertIs<OrgList>(doc.children[1]); assertEquals('+', list2.bulletType); assertEquals(2, list2.children.size)
        assertEquals("plus one", (assertIs<OrgRaw>(list2.children[0])).content)
        assertEquals("plus two", (assertIs<OrgRaw>(list2.children[1])).content)

        val sec1 = assertIs<OrgSection>(doc.children[2]); assertEquals("star one", sec1.title); assertEquals("", sec1.content)
        val sec2 = assertIs<OrgSection>(doc.children[3]); assertEquals("star two", sec2.title); assertEquals("", sec2.content)
    }
}
