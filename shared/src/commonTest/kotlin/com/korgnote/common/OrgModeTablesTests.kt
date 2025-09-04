package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgModeTablesTests {
    @Test
    fun parsesTablesAsConsecutivePipes() {
        val text = """
            | Name | Age |
            | Bob  |  30 |
            | Ann  |  25 |
        """.trimIndent()
        val doc = text.toOrgDocument()
        assertEquals(1, doc.children.size)
        val table = assertIs<OrgTable>(doc.children[0])
        // Given the simplified model, rows are folded into one OrgTableRow with cells containing joined content
        val row = table.rows
        val cells = row.cells
        // Expect three cells corresponding to three input lines joined per row
        assertEquals(3, cells.size)
        assertEquals("Name | Age", (assertIs<OrgRaw>(cells[0])).content)
        assertEquals("Bob | 30", (assertIs<OrgRaw>(cells[1])).content)
        assertEquals("Ann | 25", (assertIs<OrgRaw>(cells[2])).content)
    }
}
