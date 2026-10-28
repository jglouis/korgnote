package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow
import com.korgnote.common.org.table.FormulaCell
import com.korgnote.common.org.table.FormulaReferences
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.formulaCells
import com.korgnote.common.org.table.formulaEntries
import com.korgnote.common.org.table.recalculate
import com.korgnote.common.org.table.summaryFormula
import com.korgnote.common.org.toAlignedLines

/**
 * A table as its editor holds it: a row of cell texts per line, and null for a `|---+---|` rule.
 *
 * Editing an Org table as text means keeping its pipes lined up by hand, which a phone keyboard
 * makes all but impossible. As a grid, a cell is a field, and the pipes and padding are written
 * when the table is put back — aligned as Emacs aligns them.
 *
 * Every row has [columnCount] cells, so the editor never has to ask what a missing one means.
 *
 * The table's [formulas] — the entries of its `#+TBLFM:` line — go with it: moving a row or a column
 * renumbers what they read, as Emacs does, so that they still read the same cells.
 */
data class TableGrid(val rows: List<List<String>?>, val formulas: List<String> = emptyList()) {

    val columnCount: Int = rows.maxOfOrNull { it?.size ?: 0 } ?: 0

    init {
        require(rows.all { it == null || it.size == columnCount }) { "every row has every column" }
    }

    fun withCell(row: Int, column: Int, text: String): TableGrid =
        copy(rows = rows.mapIndexed { r, cells -> if (r == row && cells != null) cells.replaced(column, text) else cells })

    /** An empty row, at [at]; a table with no columns yet gets one. */
    fun withRowInserted(at: Int): TableGrid {
        val line = at.coerceIn(0, rows.size)
        return TableGrid(
            rows.inserted(line, List(columnCount.coerceAtLeast(1)) { "" }),
            FormulaReferences.rowInserted(formulas, rowNumberAt(line)),
        )
    }

    /** A rule, `|---+---|`, at [at]: what separates a header from the rows under it. */
    fun withRuleInserted(at: Int): TableGrid {
        val line = at.coerceIn(0, rows.size)
        return TableGrid(rows.inserted(line, null), FormulaReferences.ruleInserted(formulas, ruleNumberAt(line)))
    }

    fun withRowRemoved(at: Int): TableGrid {
        if (at !in rows.indices) return this
        val formulas = if (rows[at] == null) FormulaReferences.ruleRemoved(formulas, ruleNumberAt(at))
        else FormulaReferences.rowRemoved(formulas, rowNumberAt(at))
        return TableGrid(rows.filterIndexed { r, _ -> r != at }, formulas)
    }

    /** The row at [at] swapped with the one above it ([up]) or below; unchanged at an end. */
    fun withRowMoved(at: Int, up: Boolean): TableGrid {
        val other = if (up) at - 1 else at + 1
        if (at !in rows.indices || other !in rows.indices) return this
        // Only two rows of cells change numbers; a row moved past a rule keeps its own.
        val formulas = if (rows[at] != null && rows[other] != null) {
            FormulaReferences.rowsSwapped(formulas, rowNumberAt(at), rowNumberAt(other))
        } else {
            formulas
        }
        return TableGrid(rows.toMutableList().also { it[at] = rows[other]; it[other] = rows[at] }, formulas)
    }

    fun withColumnInserted(at: Int): TableGrid {
        if (rows.none { it != null }) return withRowInserted(0)
        val column = at.coerceIn(0, columnCount)
        return TableGrid(rows.map { it?.inserted(column, "") }, FormulaReferences.columnInserted(formulas, column + 1))
    }

    /** Without the column at [at]; the last column cannot go, since a table of no columns has no rows. */
    fun withColumnRemoved(at: Int): TableGrid =
        if (columnCount <= 1 || at !in 0 until columnCount) this
        else TableGrid(rows.map { cells -> cells?.filterIndexed { c, _ -> c != at } }, FormulaReferences.columnRemoved(formulas, at + 1))

    /** The column at [at] swapped with the one to its left ([left]) or right; unchanged at an edge. */
    fun withColumnMoved(at: Int, left: Boolean): TableGrid {
        val other = if (left) at - 1 else at + 1
        if (at !in 0 until columnCount || other !in 0 until columnCount) return this
        return TableGrid(
            rows.map { cells -> cells?.toMutableList()?.also { it[at] = cells[other]; it[other] = cells[at] } },
            FormulaReferences.columnsSwapped(formulas, at + 1, other + 1),
        )
    }

    fun withFormulas(formulas: List<String>): TableGrid = copy(formulas = formulas)

    /**
     * [column] (from 0) totalled at the bottom with [function] — `vsum`, `vmean` and the like — in a
     * last row of its own under a rule, which is added when the table has none. The formula reads
     * every row from the header down to that rule, so rows added later are counted; one that filled
     * the same cell before is replaced.
     */
    fun withSummary(column: Int, function: String): TableGrid {
        val header = rows.indexOfFirst { it != null }
        val headerRule = (header + 1 until rows.size).firstOrNull { rows[it] == null }
        val last = rows.indexOfLast { it != null }
        val hasSummaryRow = last > 0 && rows[last - 1] == null && last - 1 != headerRule
        val grid = when {
            hasSummaryRow -> this
            rows.lastOrNull() == null && rows.size - 1 != headerRule -> withRowInserted(rows.size)
            else -> withRuleInserted(rows.size).let { it.withRowInserted(it.rows.size) }
        }
        val summary = FormulaCell(grid.rows.indexOfLast { it != null }, column)
        val replaced = formulaCells(grid.rows, grid.formulas)[summary]?.takeIf { grid.formulas[it].trimStart().startsWith("@") }
        val kept = grid.formulas.filterIndexed { i, _ -> i != replaced }
        return grid.withFormulas(kept + summaryFormula(grid.rows, column + 1, function))
    }

    /**
     * Every formula computed again over the table, as Emacs's `C-u C-c *` does; see [recalculate].
     * The rows come back with the results in place, or nothing changes and the problems say why.
     */
    fun recalculated(): Recalculation = recalculate(rows, formulas)

    /** The number `@n` the row of cells at [line] has, or would have if one were inserted there. */
    private fun rowNumberAt(line: Int): Int = rows.take(line).count { it != null } + 1

    /** Which rule, `@I` being the first, the rule at [line] is, or would be. */
    private fun ruleNumberAt(line: Int): Int = rows.take(line).count { it == null } + 1

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
        fun of(table: OrgTable, formulas: List<String> = emptyList()): TableGrid {
            val raw = table.rows.map { row ->
                if (row.isRule) null else row.cells.map { ((it as? OrgRaw)?.content ?: "").replace(VERT, "|") }
            }
            val columns = raw.maxOfOrNull { it?.size ?: 0 } ?: 0
            return TableGrid(raw.map { cells -> cells?.let { it + List(columns - it.size) { "" } } }, formulas)
        }
    }
}

/** A table's `#+TBLFM:` line: where it is in the text, and its formulas. */
data class TableFormulaLine(
    /** Where the line starts — its indentation included — and ends, before its newline. */
    val range: IntRange,
    /** The line up to its first formula: indentation, keyword and the space after it. */
    val prefix: String,
    val formulas: List<String>,
)

private val formulaLinePattern = Regex("""(?:[ \t]*\n)*([ \t]*#\+TBLFM:[ \t]*)(.*)""", RegexOption.IGNORE_CASE)

/**
 * The `#+TBLFM:` line of the table that ends at [tableEnd] — the start of the line after it — or
 * null. As in Org, it is the first line after the table, blank lines aside; a second one, under it,
 * holds formulas set aside, and is not this one.
 */
fun tableFormulaLine(text: String, tableEnd: Int): TableFormulaLine? {
    if (tableEnd >= text.length) return null
    val match = formulaLinePattern.find(text, tableEnd)?.takeIf { it.range.first == tableEnd } ?: return null
    val prefix = match.groupValues[1]
    val value = match.groupValues[2]
    val start = match.range.last + 1 - value.length - prefix.length
    return TableFormulaLine(start until match.range.last + 1, prefix, formulaEntries(value))
}

/**
 * [text] with the table spanning [range] — whole lines, as the parser gives a table — written as
 * [grid] says, at the indentation its first line had. A grid with no rows left removes the table.
 *
 * Its `#+TBLFM:` line follows the grid's formulas: written under the table when it had none, removed
 * when none is left, and left exactly as it was when they did not change.
 */
fun replaceOrgTable(text: String, range: IntRange, grid: TableGrid): String {
    val old = text.substring(range.first, range.last + 1)
    val indent = old.takeWhile { it == ' ' || it == '\t' }
    val lines = grid.toOrgLines(indent)
    val formulas = if (lines.isEmpty()) emptyList() else grid.formulas
    val existing = tableFormulaLine(text, range.last + 1)
    var result = text
    if (existing != null && existing.formulas != formulas) {
        val lineEnd = existing.range.last + 1
        result = if (formulas.isEmpty()) {
            result.removeRange(existing.range.first, if (result.getOrNull(lineEnd) == '\n') lineEnd + 1 else lineEnd)
        } else {
            result.replaceRange(existing.range.first, lineEnd, existing.prefix + formulas.joinToString("::"))
        }
    }
    // The range runs to the next line's start, so it ends with the table's own newline unless the
    // table is the last thing in the file and has none.
    val end = if (old.endsWith('\n')) "\n" else ""
    var replacement = if (lines.isEmpty()) "" else lines.joinToString("\n") + end
    if (existing == null && formulas.isNotEmpty()) {
        val formulaLine = "$indent#+TBLFM: " + formulas.joinToString("::")
        replacement += if (end.isEmpty()) "\n$formulaLine" else "$formulaLine\n"
    }
    return result.replaceRange(range.first, range.last + 1, replacement)
}

private const val VERT = "\\vert{}"

private fun String.toCellText(): String = replace("|", VERT).replace('\n', ' ').trim()

private fun <T> List<T>.inserted(at: Int, item: T): List<T> =
    toMutableList().also { it.add(at.coerceIn(0, size), item) }

private fun List<String>.replaced(at: Int, item: String): List<String> =
    mapIndexed { i, old -> if (i == at) item else old }
