package com.korgnote.common.org.table

/** Something to look at in a table's formulas. */
data class FormulaNote(
    /** The formula's place in the table's list, or -1 when the note is about the whole table. */
    val formula: Int,
    val kind: Kind,
    val message: String,
    /** The formula rewritten so that the note no longer holds, when such a rewrite is safe. */
    val fix: String? = null,
) {
    enum class Kind {
        /** The formula cannot be computed, so the table cannot be recalculated. */
        Problem,

        /** Cells hold something else than their formula gives: the table was edited since it was computed. */
        Outdated,

        /** The formula works, but probably not as meant, or not for long. */
        Suggestion,
    }
}

/**
 * What is worth knowing about [formulas] over [rows]: what cannot be computed, what is out of date,
 * and what is likely a slip — the same mistakes that are easy to make, and hard to see, in Emacs.
 */
fun reviewFormulas(rows: List<List<String>?>, formulas: List<String>): List<FormulaNote> {
    if (formulas.isEmpty()) return emptyList()
    val notes = mutableListOf<FormulaNote>()
    when (val recalculation = recalculate(rows, formulas)) {
        is Recalculation.Failed -> recalculation.problems.distinctBy { it.formula }.sortedBy { it.formula }.forEach {
            notes += FormulaNote(it.formula, FormulaNote.Kind.Problem, it.message)
        }
        is Recalculation.Done -> {
            val outdated = outdatedCells(rows, recalculation.rows)
            if (outdated > 0) {
                notes += FormulaNote(
                    -1,
                    FormulaNote.Kind.Outdated,
                    if (outdated == 1) "A cell does not hold what its formula gives. Recalculate to update it."
                    else "$outdated cells do not hold what their formulas give. Recalculate to update them.",
                )
            }
        }
    }
    notes += oddOnesOut(rows, formulas)
    notes += fixedRows(rows, formulas)
    return notes
}

/** How many cells [computed] changes from [rows], their spacing aside. */
private fun outdatedCells(rows: List<List<String>?>, computed: List<List<String>?>): Int =
    rows.zip(computed).sumOf { (before, after) ->
        if (before == null || after == null) 0 else before.zip(after).count { (a, b) -> a.trim() != b.trim() }
    }

/**
 * Formulas for cells of one row that read rows unlike their siblings: the third of four averages
 * reading from `@III` while the others read from `@II` is likely a slip.
 */
private fun oddOnesOut(rows: List<List<String>?>, formulas: List<String>): List<FormulaNote> {
    val layout = TableLayout(rows)
    val cell = Regex("""^@([^$]+)\$(\d+)$""")
    data class Sibling(val index: Int, val row: String, val column: String, val right: String)
    val siblings = formulas.mapIndexedNotNull { index, text ->
        val target = text.substringBefore('=').trim()
        val match = cell.matchEntire(target) ?: return@mapIndexedNotNull null
        Sibling(index, match.groupValues[1], match.groupValues[2], text.substringAfter('=').trim())
    }
    val notes = mutableListOf<FormulaNote>()
    for ((_, group) in siblings.groupBy { it.row }) {
        // What each reads, with its own column and the rows it names left out.
        fun shape(s: Sibling) = s.right.replace("\$${s.column}", "\$C").replace(ROWS, "@R")
        fun reads(s: Sibling) = s.right.replace("\$${s.column}", "\$C")
        for ((_, same) in group.groupBy(::shape)) {
            if (same.size < 3) continue
            val counts = same.groupingBy(::reads).eachCount()
            val (usual, count) = counts.maxByOrNull { it.value } ?: continue
            if (count < 2 || counts.values.count { it == count } > 1) continue
            for (odd in same.filter { reads(it) != usual }) {
                val fixed = usual.replace("\$C", "\$${odd.column}")
                val line = runCatching { layout.line(odd.row, 0) }.getOrNull() ?: continue
                val theirs = describeRowsRead(layout, usual.replace("\$C", "\$${odd.column}"), line)
                val its = describeRowsRead(layout, odd.right, line)
                notes += FormulaNote(
                    odd.index,
                    FormulaNote.Kind.Suggestion,
                    if (its != null && theirs != null && its != theirs) "Unlike the other formulas of this row, this one reads $its, where they read $theirs."
                    else "This formula reads other rows than its siblings on the same row.",
                    fix = "@${odd.row}\$${odd.column}=$fixed",
                )
            }
        }
    }
    return notes
}

/**
 * Formulas that name rows by number: right today, they read the wrong rows once a row is added. Each
 * is rewritten — `@>` for the last row, the rules around a block of rows for a range — when the
 * rewrite computes exactly what the formula computes now.
 */
private fun fixedRows(rows: List<List<String>?>, formulas: List<String>): List<FormulaNote> {
    val layout = TableLayout(rows)
    val before = recalculate(rows, formulas) as? Recalculation.Done ?: return emptyList()
    val notes = mutableListOf<FormulaNote>()
    val lastLine = layout.dataLines.lastOrNull() ?: return emptyList()
    for ((index, text) in formulas.withIndex()) {
        val target = text.substringBefore('=').trim()
        var rewritten = text
        Regex("""^@(\d+)(\$\d+)$""").matchEntire(target)?.let { match ->
            if (layout.dataLines.getOrNull(match.groupValues[1].toInt() - 1) == lastLine && isTotalRow(layout, lastLine)) {
                rewritten = "@>" + match.groupValues[2] + "=" + text.substringAfter('=')
            }
        }
        rewritten = RANGE_OF_ROWS.replace(rewritten) { match ->
            val (first, firstColumn, last, lastColumn) = match.destructured
            val from = ruleBefore(layout, first.toInt())
            val to = ruleAfter(layout, last.toInt())
            if (from == null || to == null) match.value
            else "@${"I".repeat(from)}$firstColumn..@${"I".repeat(to)}$lastColumn"
        }
        if (rewritten == text) continue
        val after = recalculate(rows, formulas.toMutableList().also { it[index] = rewritten }) as? Recalculation.Done ?: continue
        if (after.rows != before.rows) continue
        notes += FormulaNote(
            index,
            FormulaNote.Kind.Suggestion,
            "This formula names rows by number, so adding a row makes it read the wrong ones. It can follow the table's lines instead.",
            fix = rewritten,
        )
    }
    return notes
}

/**
 * Whether [line] is a row of totals: under a rule of its own, with rows of the table's body above
 * it. A one-row table computed across is not, and `@>` would mean nothing more there than `@2`.
 */
private fun isTotalRow(layout: TableLayout, line: Int): Boolean {
    if (line == 0 || layout.rows[line - 1] != null) return false
    return layout.dataLines.any { it in layout.firstBodyLine until line - 1 }
}

/** Which rule (`@I` the first) stands right above row [row], if one does. */
private fun ruleBefore(layout: TableLayout, row: Int): Int? {
    val line = layout.dataLines.getOrNull(row - 1) ?: return null
    if (line == 0 || layout.rows[line - 1] != null) return null
    return layout.rows.take(line).count { it == null }
}

/** Which rule stands right below row [row] — the one Org counts past the table's end included. */
private fun ruleAfter(layout: TableLayout, row: Int): Int? {
    val line = layout.dataLines.getOrNull(row - 1) ?: return null
    if (line + 1 < layout.lineCount && layout.rows[line + 1] != null) return null
    return layout.rows.take(line + 1).count { it == null } + 1
}

private val ROWS = Regex("""@[-+]?I*[-+]?[0-9<>]*""")
private val RANGE_OF_ROWS = Regex("""@(\d+)(\$\d+)?\.\.@(\d+)(\$\d+)?""")

/** The rows the first range in [right] reads when computing [line], as "rows 2–14". */
private fun describeRowsRead(layout: TableLayout, right: String, line: Int): String? {
    val range = REFERENCE.findAll(right).map { it.value }.firstOrNull { ".." in it && it.startsWith("@") } ?: return null
    val expr = runCatching { FormulaParser(range).parse() }.getOrNull() as? Expr.Range ?: return null
    return runCatching { describeRows(layout, expr, line) }.getOrNull()
}

private fun describeRows(layout: TableLayout, range: Expr.Range, line: Int): String? {
    var first = minOf(layout.line(range.row1, line), layout.line(range.row2, line))
    var last = maxOf(layout.line(range.row1, line), layout.line(range.row2, line))
    while (first < layout.lineCount && !layout.isDataLine(first)) first++
    while (last >= 0 && !layout.isDataLine(last)) last--
    if (first > last) return null
    val a = layout.rowNumber(first)
    val b = layout.rowNumber(last)
    return if (a == b) "row $a" else "rows $a–$b"
}

/** A reference as Org writes one, for finding them in a formula's text. */
private val REFERENCE = Regex(
    """@#|\$#|@[-+]?I*[-+]?(?:\d+|<+|>+)?(?:\$(?:[-+]?\d+|<+|>+))?(?:\.\.@?[-+]?I*[-+]?(?:\d+|<+|>+)?(?:\$(?:[-+]?\d+|<+|>+))?)?|\$(?:[-+]?\d+|<+|>+)(?:\.\.\$(?:[-+]?\d+|<+|>+))?""",
)

private val FRIENDLY_NAMES = mapOf(
    "vsum" to "sum", "vmean" to "mean", "vmedian" to "median", "vmin" to "min", "vmax" to "max", "vcount" to "count",
    "vprod" to "product", "vvar" to "variance", "vpvar" to "population variance",
    "vsdev" to "standard deviation", "vpsdev" to "population standard deviation",
)

/**
 * [formula] as words, with the columns called by their headers: `$6=min(20, $2+$3+$4)` reads
 * `‹total›, every row = min(20, ‹note 1›+‹note 2›+‹bonus›)`. Column names stand between ‹ and › so
 * that they can be told apart, and drawn apart. A formula that cannot be read comes back as written.
 */
fun describeFormula(rows: List<List<String>?>, formula: String): String {
    val layout = TableLayout(rows)
    val names = columnNames(rows)
    fun name(column: Int) = "‹" + (names.getOrNull(column - 1)?.takeIf { it.isNotBlank() } ?: "column $column") + "›"
    val target = formula.substringBefore('=').trim()
    val right = formula.substringAfter('=', "").trim()
    if (right.isEmpty()) return formula

    // Where the formula is computed, when that is a single place: what relative rows are seen from.
    val targetExpr = runCatching { FormulaParser(target).parse() }.getOrNull()
    val targetLine = (targetExpr as? Expr.Field)?.row?.let { runCatching { layout.line(it, 0) }.getOrNull() }
    val targetColumn = when {
        targetExpr is Expr.Field && targetExpr.column != null -> runCatching { layout.column(targetExpr.column, 1) }.getOrNull()
        targetExpr is Expr.Range -> runCatching { layout.column(targetExpr.column1, 1) }.getOrNull()
        else -> null
    }

    val what = when (targetExpr) {
        is Expr.Field -> when {
            targetExpr.row == null -> targetColumn?.let { name(it) + ", every row" }
            targetExpr.column == null -> rowName(targetExpr.row, layout)
            else -> targetColumn?.let { name(it) + ", " + rowName(targetExpr.row, layout) }
        }
        is Expr.Range -> targetColumn?.let { column ->
            val rowsFilled = runCatching { describeRows(layout, targetExpr, 0) }.getOrNull()
            name(column) + (rowsFilled?.let { ", $it" } ?: "")
        }
        else -> null
    } ?: target

    fun reference(text: String): String {
        val expr = runCatching { FormulaParser(text).parse() }.getOrNull() ?: return text
        return when (expr) {
            Expr.RowNumber -> "row number"
            Expr.ColumnNumber -> "column number"
            is Expr.Columns -> {
                val first = expr.first.toIntOrNull()?.takeIf { expr.first[0].isDigit() }
                val last = expr.last.toIntOrNull()?.takeIf { expr.last[0].isDigit() }
                if (first == null || last == null) text else "${name(first)} to ${name(last)}"
            }
            is Expr.Field -> {
                val column = runCatching { layout.column(expr.column, targetColumn ?: return text) }.getOrNull() ?: return text
                when {
                    expr.row == null || expr.row.isEmpty() -> name(column)
                    expr.row == "-1" -> name(column) + " of the row above"
                    expr.row == "+1" -> name(column) + " of the row below"
                    expr.row[0] == '+' || expr.row[0] == '-' || 'I' in expr.row -> name(column) + " (@${expr.row})"
                    else -> name(column) + ", " + rowName(expr.row, layout)
                }
            }
            is Expr.Range -> {
                val c1 = runCatching { layout.column(expr.column1, targetColumn ?: return text) }.getOrNull() ?: return text
                val c2 = runCatching { layout.column(expr.column2 ?: expr.column1, targetColumn ?: return text) }.getOrNull() ?: return text
                val columns = if (c1 == c2) name(c1) else "${name(c1)} to ${name(c2)}"
                val rowsRead = targetLine?.let { runCatching { describeRows(layout, expr, it) }.getOrNull() }
                    ?: if (expr.row1.isNullOrEmpty() && expr.row2.isNullOrEmpty()) null else "@${expr.row1}..@${expr.row2}"
                if (rowsRead == null) columns else "$columns, $rowsRead"
            }
            else -> text
        }
    }

    val words = REFERENCE.replace(right) { reference(it.value) }
        .let { text -> FRIENDLY_NAMES.entries.fold(text) { acc, (calc, friendly) -> acc.replace(Regex("""\b$calc\("""), "$friendly(") } }
    val format = right.substringAfterLast(';', "").takeIf { ';' in right }
    val shown = if (format != null) words.substringBeforeLast(';').trim() + "  (shown as $format)" else words
    return "$what = $shown"
}

private fun rowName(row: String, layout: TableLayout): String = when {
    row.all { it == '>' } -> if (row.length == 1) "last row" else "row ${layout.dataLines.size - row.length + 1}"
    row.all { it == '<' } -> "row ${row.length}"
    else -> "row $row"
}

/**
 * Each column's header — the first row of cells, when a rule sets it apart from the rows under it —
 * or nothing where the table has no header.
 */
fun columnNames(rows: List<List<String>?>): List<String> {
    val header = rows.indexOfFirst { it != null }
    if (header < 0) return emptyList()
    val ruleBelow = (header + 1 until rows.size).any { rows[it] == null }
    return if (ruleBelow) rows[header]!!.map { it.trim() } else emptyList()
}

/**
 * The formula that totals [column] (from 1) of a table at its last row with [function] — `vsum`,
 * `vmean` and the like — over [rowsAboveTotal]: they keep being read as rows are added.
 */
fun summaryFormula(rows: List<List<String>?>, column: Int, function: String): String =
    "@>\$$column=$function(${rowsAboveTotal(rows)})"

/**
 * The rows between a table's header and the rule above the cell being computed, as a total reads
 * them: `@II..@-I` under a header that rules frame, `@I..@-I` under one that a rule only ends, and
 * from the first row, `@<..@-I`, in a table with no header.
 */
fun rowsAboveTotal(rows: List<List<String>?>): String {
    val header = rows.indexOfFirst { it != null }
    val headerRule = (header + 1 until rows.size).firstOrNull { rows[it] == null }
    val from = if (header < 0 || headerRule == null) "@<" else "@" + "I".repeat(rows.take(headerRule + 1).count { it == null })
    return "$from..@-I"
}
