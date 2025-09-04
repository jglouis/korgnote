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
}
