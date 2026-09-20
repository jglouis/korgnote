package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeCheckboxListItemsTests {
    @Test
    fun parsesCheckboxListItems() {
        val text = """
            - [ ] Task one
            - [x] Task two
            - Plain item
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(1, doc.children.size)
        val list = assertIs<OrgList>(doc.children[0])
        assertEquals('-', list.bulletType)
        assertEquals(3, list.children.size)
        val item1 = assertIs<OrgCheckboxItem>(list.children[0])
        assertEquals(false, item1.checked)
        assertEquals("Task one", item1.text)
        val item2 = assertIs<OrgCheckboxItem>(list.children[1])
        assertEquals(true, item2.checked)
        assertEquals("Task two", item2.text)
        val item3 = assertIs<OrgListItem>(list.children[2])
        assertEquals("Plain item", item3.text)
    }
}
