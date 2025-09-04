package com.korgnote.common

import com.korgnote.common.org.*
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
}
