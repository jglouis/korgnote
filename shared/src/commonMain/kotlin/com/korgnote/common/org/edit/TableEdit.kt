package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow
import com.korgnote.common.org.toAlignedLines

/**
 * A table as its editor holds it: a row of cell texts per line, and null for a `|---+---|` rule.
 *
 * Editing an Org table as text means keeping its pipes lined up by hand, which a phone keyboard
 * makes all but impossible. As a grid, a cell is a field, and the pipes and padding are written
 * when the table is put back — aligned as Emacs aligns them.
 *
 * Every row has [columnCount] cells, so the editor never has to ask what a missing one means.
 */
data class TableGrid(val rows: List<List<String>?>) {

    val columnCount: Int = rows.maxOfOrNull { it?.size ?: 0 } ?: 0

    init {
        require(rows.all { it == null || it.size == columnCount }) { "every row has every column" }
    }

    fun withCell(row: Int, column: Int, text: String): TableGrid =
        TableGrid(rows.mapIndexed { r, cells -> if (r == row && cells != null) cells.replaced(column, text) else cells })

    /** An empty row, at [at]; a table with no columns yet gets one. */
    fun withRowInserted(at: Int): TableGrid =
        TableGrid(rows.inserted(at, List(columnCount.coerceAtLeast(1)) { "" }))

    /** A rule, `|---+---|`, at [at]: what separates a header from the rows under it. */
    fun withRuleInserted(at: Int): TableGrid = TableGrid(rows.inserted(at, null))

    fun withRowRemoved(at: Int): TableGrid = TableGrid(rows.filterIndexed { r, _ -> r != at })

    /** The row at [at] swapped with the one above it ([up]) or below; unchanged at an end. */
    fun withRowMoved(at: Int, up: Boolean): TableGrid {
        val other = if (up) at - 1 else at + 1
        if (at !in rows.indices || other !in rows.indices) return this
        return TableGrid(rows.toMutableList().also { it[at] = rows[other]; it[other] = rows[at] })
    }

    fun withColumnInserted(at: Int): TableGrid =
        if (rows.none { it != null }) withRowInserted(0)
        else TableGrid(rows.map { it?.inserted(at, "") })

    /** Without the column at [at]; the last column cannot go, since a table of no columns has no rows. */
    fun withColumnRemoved(at: Int): TableGrid =
        if (columnCount <= 1) this else TableGrid(rows.map { cells -> cells?.filterIndexed { c, _ -> c != at } })

    /** The column at [at] swapped with the one to its left ([left]) or right; unchanged at an edge. */
    fun withColumnMoved(at: Int, left: Boolean): TableGrid {
        val other = if (left) at - 1 else at + 1
        if (at !in 0 until columnCount || other !in 0 until columnCount) return this
        return TableGrid(rows.map { cells -> cells?.toMutableList()?.also { it[at] = cells[other]; it[other] = cells[at] } })
    }

    /**
     * The table as Org writes it, every line starting with [indent]. A `|` typed into a cell would
     * split it in two, so it is written `\vert`, which Org shows as a bar; a line break would end
     * the table, so it becomes a space. Nothing when no row is left.
     */
    fun toOrgLines(indent: String = ""): List<String> {
        if (rows.none { it != null }) return emptyList()
        val table = OrgTable(rows.map { cells ->
            if (cells == null) OrgTableRow(emptyList(), isRule = true)
            else OrgTableRow(cells.map { OrgRaw(it.toCellText()) as OrgNode })
        })
        return table.toAlignedLines().map { indent + it }
    }

    companion object {
        fun of(table: OrgTable): TableGrid {
            val raw = table.rows.map { row ->
                if (row.isRule) null else row.cells.map { ((it as? OrgRaw)?.content ?: "").replace(VERT, "|") }
            }
            val columns = raw.maxOfOrNull { it?.size ?: 0 } ?: 0
            return TableGrid(raw.map { cells -> cells?.let { it + List(columns - it.size) { "" } } })
        }
    }
}

/**
 * [text] with the table spanning [range] — whole lines, as the parser gives a table — written as
 * [grid] says, at the indentation its first line had. A grid with no rows left removes the table.
 */
fun replaceOrgTable(text: String, range: IntRange, grid: TableGrid): String {
    val old = text.substring(range.first, range.last + 1)
    val indent = old.takeWhile { it == ' ' || it == '\t' }
    val lines = grid.toOrgLines(indent)
    // The range runs to the next line's start, so it ends with the table's own newline unless the
    // table is the last thing in the file and has none.
    val end = if (old.endsWith('\n')) "\n" else ""
    val replacement = if (lines.isEmpty()) "" else lines.joinToString("\n") + end
    return text.replaceRange(range.first, range.last + 1, replacement)
}

private const val VERT = "\\vert{}"

private fun String.toCellText(): String = replace("|", VERT).replace('\n', ' ').trim()

private fun <T> List<T>.inserted(at: Int, item: T): List<T> =
    toMutableList().also { it.add(at.coerceIn(0, size), item) }

private fun List<String>.replaced(at: Int, item: String): List<String> =
    mapIndexed { i, old -> if (i == at) item else old }
