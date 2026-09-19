package com.korgnote.common

import com.korgnote.common.org.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(3, table.rows.size)
        assertEquals(2, table.columnCount)
        assertEquals(listOf("Name", "Age"), table.rows[0].cells.map { assertIs<OrgRaw>(it).content })
        assertEquals(listOf("Bob", "30"), table.rows[1].cells.map { assertIs<OrgRaw>(it).content })
        assertEquals(listOf("Ann", "25"), table.rows[2].cells.map { assertIs<OrgRaw>(it).content })
    }

    @Test
    fun parsesHorizontalRulesAndEmptyCells() {
        val text = """
            |---+---|
            | a |   |
            |---+---|
        """.trimIndent()
        val table = parseOrgTable(text.split('\n'))
        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0].isRule)
        assertFalse(table.rows[1].isRule)
        assertTrue(table.rows[2].isRule)
        assertEquals(listOf("a", ""), table.rows[1].cells.map { assertIs<OrgRaw>(it).content })
    }

    @Test
    fun alignedLinesPadColumnsAndRightAlignNumbers() {
        val text = """
            |name|total|
            |-|
            |Bob|3|
            |Christine|12.5|
        """.trimIndent()
        val aligned = parseOrgTable(text.split('\n')).toAlignedLines()
        val expected = """
            | name      | total |
            |-----------+-------|
            | Bob       |     3 |
            | Christine |  12.5 |
        """.trimIndent()
        assertEquals(expected, aligned.joinToString("\n"))
    }

    @Test
    fun numericDetection() {
        assertTrue("42".isNumeric())
        assertTrue("-3.5".isNumeric())
        assertTrue("2,5".isNumeric())
        assertTrue("50%".isNumeric())
        assertFalse("".isNumeric())
        assertFalse("v2".isNumeric())
        assertFalse("1 2".isNumeric())
    }
}
