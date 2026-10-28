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

    private fun aligned(text: String) = parseOrgTable(text.trimIndent().split('\n')).toAlignedLines().joinToString("\n")

    // Each expected table below is what Emacs 31.1's `org-table-align` made of the same cells.

    @Test
    fun linksTakeTheWidthOfWhatTheyShow() {
        assertEquals(
            """
            | who  | link          |
            |------+---------------|
            | [[id:abc][Name]] | [[Plain link]]    |
            | x    | [[https://example.org/a/long/path][Site]] and more |
            """.trimIndent(),
            aligned(
                """
                | who | link |
                |-|-|
                | [[id:abc][Name]] | [[Plain link]] |
                | x | [[https://example.org/a/long/path][Site]] and more |
                """,
            ),
        )
    }

    @Test
    fun wideCharactersTakeTwoColumnsAndAccentsNone() {
        assertEquals(
            "| 日本 | é | 😀 | ❤️ | 👍🏽 | ☕ |\n| x    | y | z  | w | v    | t  |",
            aligned("| 日本 | é | 😀 | ❤️ | 👍🏽 | ☕ |\n| x | y | z | w | v | t |"),
        )
    }

    @Test
    fun aColumnOfMostlyNumbersIsAlignedRightAsAWhole() {
        assertEquals(
            """
            |     a | b |    c | d |
            |-------+---+------+---|
            |     1 | x |    1 |   |
            |     2 | y | text |   |
            | three | 3 | 1:30 |   |
            |   Nan | 4 | 0x1F |   |
            """.trimIndent(),
            aligned(
                """
                | a | b | c | d |
                |-+-+-+-|
                | 1 | x | 1 | |
                | 2 | y | text | |
                | three | 3 | 1:30 | |
                | Nan | 4 | 0x1F | |
                """,
            ),
        )
    }

    @Test
    fun cookiesSetAColumnsAlignmentAndShortRowsAreFilled() {
        assertEquals(
            "|  <c>   | <r> | <l> |\n|   ab   |   x | 12  |\n| abcdef |  yy | 3   |",
            aligned("| <c> | <r> | <l> |\n| ab | x | 12 |\n| abcdef | yy | 3 |"),
        )
        assertEquals(
            "| a              | b | c |\n| 1              |   |   |\n|----------------+---+---|\n| long cell here | 2 |   |",
            aligned("| a | b | c |\n| 1 |\n|---|\n| long cell here | 2 |"),
        )
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
