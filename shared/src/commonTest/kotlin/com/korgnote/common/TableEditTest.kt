package com.korgnote.common

import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.replaceOrgTable
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class TableEditTest {

    private val text = """
        * Groceries
          | Item | Qty |
          |------+-----|
          | Milk | 2 |
        After the table.
    """.trimIndent() + "\n"

    private fun tableOf(source: String): OrgTable =
        source.toOrgDocument().nodeAt<OrgTable>(source.indexOf('|'))!!

    @Test
    fun aTableReadsAsAGridWithItsRules() {
        val grid = TableGrid.of(tableOf(text))

        assertEquals(listOf(listOf("Item", "Qty"), null, listOf("Milk", "2")), grid.rows)
        assertEquals(2, grid.columnCount)
    }

    @Test
    fun anEditedCellIsWrittenBackAlignedAtTheTablesIndentation() {
        val table = tableOf(text)
        val grid = TableGrid.of(table).withCell(2, 0, "Oat milk")

        assertEquals(
            """
            * Groceries
              | Item     | Qty |
              |----------+-----|
              | Oat milk |   2 |
            After the table.
            """.trimIndent() + "\n",
            replaceOrgTable(text, table.range, grid),
        )
    }

    @Test
    fun rowsAndColumnsComeAndGo() {
        val grid = TableGrid.of(tableOf(text))
            .withRowInserted(3)
            .withCell(3, 0, "Eggs")
            .withColumnInserted(2)
            .withCell(0, 2, "Shop")
            .withRowRemoved(1)

        assertEquals(listOf(listOf("Item", "Qty", "Shop"), listOf("Milk", "2", ""), listOf("Eggs", "", "")), grid.rows)
        assertEquals(listOf(listOf("Qty", "Shop"), listOf("2", ""), listOf("", "")), grid.withColumnRemoved(0).rows)
        assertEquals(listOf("Eggs", "", ""), grid.withRowMoved(2, up = true).rows[1])
        assertEquals(listOf("Qty", "Item", "Shop"), grid.withColumnMoved(1, left = true).rows[0])
    }

    @Test
    fun theLastColumnStays() {
        val grid = TableGrid(listOf(listOf("only")))

        assertEquals(grid, grid.withColumnRemoved(0))
    }

    @Test
    fun aBarInACellDoesNotSplitIt() {
        val source = "| a |\n"
        val table = tableOf(source)
        val written = replaceOrgTable(source, table.range, TableGrid.of(table).withCell(0, 0, "this | that"))

        assertEquals("| this \\vert{} that |\n", written)
        assertEquals(listOf(listOf("this | that")), TableGrid.of(tableOf(written)).rows)
    }

    @Test
    fun removingEveryRowRemovesTheTable() {
        val source = "Before\n| a |\nAfter\n"
        val table = tableOf(source)

        assertEquals("Before\nAfter\n", replaceOrgTable(source, table.range, TableGrid.of(table).withRowRemoved(0)))
    }

    @Test
    fun aTableAtTheEndOfAFileWithoutANewlineGetsNone() {
        val source = "| a | b |"
        val table = tableOf(source)

        assertEquals("| x | b |", replaceOrgTable(source, table.range, TableGrid.of(table).withCell(0, 0, "x")))
    }
}
