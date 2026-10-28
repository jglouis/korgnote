package com.korgnote.common.org.table

import kotlin.math.sqrt

/**
 * A table's lines as its formulas see them, from a row of cells per line and null for a rule.
 *
 * Rows are numbered from 1 over the lines that have cells (`@1` is usually the header); rules are
 * counted on their own (`@I` is the first). Org counts one more rule than the table shows, past its
 * last line, and so does this: `@II..@III` reaches the end of a table that has no closing rule.
 */
internal class TableLayout(val rows: List<List<String>?>) {
    val lineCount = rows.size
    val columnCount = rows.maxOfOrNull { it?.size ?: 0 } ?: 0

    /** The line of each row, `@1` first. */
    val dataLines: List<Int> = rows.indices.filter { rows[it] != null }

    private fun isRule(line: Int) = line == lineCount || rows[line] == null

    fun isDataLine(line: Int) = line in 0 until lineCount && rows[line] != null

    /** The number `@n` of the row on [line]. */
    fun rowNumber(line: Int): Int = dataLines.indexOf(line) + 1

    fun cell(line: Int, column: Int): String = rows[line]?.getOrNull(column - 1)?.trim().orEmpty()

    /**
     * The first line a formula's column formulas fill: the one after the rule under the header, or
     * the first when no rule sets a header apart.
     */
    val firstBodyLine: Int = run {
        val header = dataLines.firstOrNull() ?: return@run 0
        val rule = (header + 1 until lineCount).firstOrNull { rows[it] == null } ?: return@run 0
        // Without a row under that rule, Emacs starts from the top.
        (rule + 1 until lineCount).firstOrNull { rows[it] != null } ?: 0
    }

    /** The line [desc] names — a row or a rule — seen from [current], as `org-table--descriptor-line` reads it. */
    fun line(desc: String?, current: Int): Int {
        if (desc.isNullOrEmpty()) return current
        if (desc.all { it.isDigit() }) {
            val n = desc.toInt()
            if (n == 0) return current
            return dataLines.getOrNull(n - 1) ?: outside("@$desc", "the table has ${dataLines.size} rows")
        }
        if (desc.all { it == '<' }) return dataLines.getOrNull(desc.length - 1) ?: outside("@$desc", "the table has ${dataLines.size} rows")
        if (desc.all { it == '>' }) return dataLines.getOrNull(dataLines.size - desc.length) ?: outside("@$desc", "the table has ${dataLines.size} rows")
        val match = DESCRIPTOR.matchEntire(desc) ?: throw FormulaError("“@$desc” is not a row Org understands.")
        val (hlineSign, hlines, offsetSign, offset) = match.destructured
        if (hlines.isNotEmpty() && offset.isNotEmpty() && offsetSign.isEmpty()) throw FormulaError("“@$desc” is not a row Org understands.")
        var line = current
        if (hlines.isNotEmpty()) {
            var count = hlines.length
            val backwards = hlineSign == "-"
            if (hlineSign.isEmpty()) {
                line = 0
                if (isRule(0)) count--
            }
            line = step(count, line, backwards, rules = true, desc)
        }
        if (offset.isNotEmpty()) line = step(offset.toInt(), line, offsetSign == "-", rules = false, desc)
        return line
    }

    /** The line [count] rules (or rows) away from [start], as `org-table--row-type` finds it. */
    private fun step(count: Int, start: Int, backwards: Boolean, rules: Boolean, desc: String): Int {
        val lines = lineCount + 1
        var i = start
        repeat(count) {
            do { i += if (backwards) -1 else 1 } while (i in 0 until lines && isRule(i) != rules)
        }
        if (i < 0 || i >= lines) outside("@$desc", "it leads out of the table")
        // The rule past the last line does not exist: it stands for the last line itself.
        return if (i == lines - 1) i - 1 else i
    }

    /** The column [desc] names, seen from [current]. */
    fun column(desc: String?, current: Int): Int {
        val column = when {
            desc.isNullOrEmpty() -> current
            desc.all { it == '<' } -> desc.length
            desc.all { it == '>' } -> columnCount - desc.length + 1
            else -> {
                val n = desc.toInt()
                when {
                    n == 0 -> current
                    desc[0] == '+' || desc[0] == '-' -> current + n
                    else -> n
                }
            }
        }
        if (column !in 1..columnCount) outside("\$$desc", "the table has $columnCount columns")
        return column
    }

    private fun outside(reference: String, why: String): Nothing =
        throw FormulaError("$reference is outside the table: $why.")

    private companion object {
        // A sign before I's counts rules from the current line; a sign before digits, rows.
        val DESCRIPTOR = Regex("""(?:([-+]?)(I+))?(?:([-+]?)([0-9]+))?""")
    }
}

/** What a formula evaluates to before it is written: a number, a cell not yet read, or several. */
private sealed interface Value {
    data class Number(val value: CalcNumber) : Value

    /** A cell's text, read as a number only when something computes with it, as `if` may not. */
    data class Cell(val text: String, val line: Int, val column: Int) : Value

    data class Several(val items: List<Value>) : Value
}

/** Evaluates one formula for one cell of [layout]: the one at [line], in [column]. */
private class Evaluation(
    private val layout: TableLayout,
    private val line: Int,
    private val column: Int,
    private val numbers: Boolean,
) {
    fun evaluate(expr: Expr): Value = when (expr) {
        is Expr.Number -> Value.Number(expr.value)
        Expr.RowNumber -> Value.Number(CalcNumber.Whole(layout.rowNumber(line).toLong()))
        Expr.ColumnNumber -> Value.Number(CalcNumber.Whole(column.toLong()))
        is Expr.Field -> field(layout.line(expr.row, line), layout.column(expr.column, column))
        is Expr.Range -> range(expr)
        is Expr.Columns -> columns(expr)
        is Expr.Negate -> Value.Number(-number(evaluate(expr.operand)))
        is Expr.Binary -> binary(expr)
        is Expr.Call -> call(expr)
    }

    /** A single cell: an empty one counts as zero, as Org passes `(0)` to Calc. */
    private fun field(at: Int, column: Int): Value {
        var line = at
        // A rule names the row after it.
        while (line < layout.lineCount && !layout.isDataLine(line)) line++
        if (line >= layout.lineCount) throw FormulaError("A reference leads past the last row of the table.")
        val text = layout.cell(line, column)
        return when {
            text.isEmpty() -> Value.Number(CalcNumber.Whole(0))
            numbers -> Value.Number(CalcNumber.leadingNumber(text))
            else -> Value.Cell(text, line, column)
        }
    }

    /** The cells of a rectangle, row by row; the empty ones are left out, as Org leaves them out. */
    private fun range(expr: Expr.Range): Value {
        val r1 = layout.line(expr.row1, line)
        val r2 = layout.line(expr.row2, line)
        val c1 = layout.column(expr.column1, column)
        val c2 = layout.column(expr.column2, column)
        if (r1 == r2 && c1 == c2) return field(r1, c1)
        var first = minOf(r1, r2)
        var last = maxOf(r1, r2)
        // From a rule, a range starts at the row after it and ends at the row before it.
        while (first < layout.lineCount && !layout.isDataLine(first)) first++
        while (last >= 0 && !layout.isDataLine(last)) last--
        if (first >= layout.lineCount || last < 0) throw FormulaError("A range leads out of the table.")
        val items = (minOf(first, last)..maxOf(first, last)).filter { layout.isDataLine(it) }.flatMap { l ->
            (minOf(c1, c2)..maxOf(c1, c2)).map { c -> l to c }
        }
        return several(items)
    }

    /**
     * `$a..$b`: Org takes these cells as they come, without putting the columns in order, so the
     * range is empty when [Expr.Columns.last] is the column before [Expr.Columns.first].
     */
    private fun columns(expr: Expr.Columns): Value {
        fun index(desc: String): Int = when {
            desc[0] == '<' || desc[0] == '>' -> layout.column(desc, column)
            desc[0] == '+' || desc[0] == '-' -> column + desc.toInt()
            else -> desc.toInt()
        }
        val start = index(expr.first) - 1
        val end = index(expr.last)
        if (start >= end && start >= 0 && end <= layout.columnCount) {
            throw FormulaError("\$${expr.first}..\$${expr.last} runs backwards, so Org reads no cell from it; write \$${expr.last}..\$${expr.first}.")
        }
        if (start < 0 || end > layout.columnCount) {
            throw FormulaError("\$${expr.first}..\$${expr.last} is outside the table, which has ${layout.columnCount} columns.")
        }
        return several((start + 1..end).map { line to it })
    }

    private fun several(cells: List<Pair<Int, Int>>): Value = Value.Several(
        cells.mapNotNull { (l, c) ->
            val text = layout.cell(l, c)
            when {
                text.isEmpty() -> null
                numbers -> Value.Number(CalcNumber.leadingNumber(text))
                else -> Value.Cell(text, l, c)
            }
        },
    )

    private fun binary(expr: Expr.Binary): Value {
        val a = number(evaluate(expr.left))
        val b = number(evaluate(expr.right))
        fun truth(holds: Boolean) = Value.Number(CalcNumber.Whole(if (holds) 1 else 0))
        return when (expr.operator) {
            "+" -> Value.Number(a + b)
            "-" -> Value.Number(a - b)
            "*" -> Value.Number(a * b)
            "/" -> Value.Number(a / b ?: divisionByZero())
            "%" -> Value.Number(a.mod(b) ?: divisionByZero())
            "\\" -> Value.Number(a.intDiv(b) ?: divisionByZero())
            "^" -> Value.Number(a.pow(b) ?: throw FormulaError("${a}^$b is not a real number."))
            "==" -> truth(a.compareTo(b) == 0)
            "!=" -> truth(a.compareTo(b) != 0)
            "<" -> truth(a < b)
            ">" -> truth(a > b)
            "<=" -> truth(a <= b)
            ">=" -> truth(a >= b)
            "&&" -> truth(!a.isZero && !b.isZero)
            "||" -> truth(!a.isZero || !b.isZero)
            else -> throw FormulaError("Unknown operator ${expr.operator}.")
        }
    }

    private fun call(expr: Expr.Call): Value {
        val name = expr.name
        if (name == "if") {
            if (expr.arguments.size != 3) throw FormulaError("if(…) needs three parts: a condition, its value when true, when false.")
            val condition = number(evaluate(expr.arguments[0]))
            return evaluate(expr.arguments[if (condition.isZero) 2 else 1])
        }
        val values = expr.arguments.map { evaluate(it) }
        if (name.startsWith("v") || name == "min" || name == "max") {
            val items = values.flatMap { if (it is Value.Several) it.items else listOf(it) }.map { number(it) }
            if (items.isEmpty() && name != "vsum" && name != "vcount") {
                throw FormulaError("$name(…) has no number to work on: every cell it reads is empty.")
            }
            return Value.Number(
                when (name) {
                    "vsum" -> items.fold(CalcNumber.Whole(0) as CalcNumber) { sum, n -> sum + n }
                    "vcount" -> CalcNumber.Whole(items.size.toLong())
                    "vmean" -> (items.reduce { sum, n -> sum + n } / CalcNumber.Whole(items.size.toLong()))!!
                    "vmedian" -> items.sorted().let { sorted ->
                        val middle = sorted.size / 2
                        if (sorted.size % 2 == 1) sorted[middle] else ((sorted[middle - 1] + sorted[middle]) / CalcNumber.Whole(2))!!
                    }
                    "vmin", "min" -> items.reduce { best, n -> if (n < best) n else best }
                    "vmax", "max" -> items.reduce { best, n -> if (n > best) n else best }
                    else -> throw FormulaError("The function $name is not supported here.")
                },
            )
        }
        fun single(): CalcNumber {
            if (values.size != 1) throw FormulaError("$name(…) takes one value.")
            return number(values[0])
        }
        return Value.Number(
            when (name) {
                "abs" -> single().abs()
                "floor" -> single().floor()
                "ceil" -> single().ceil()
                "trunc" -> single().truncate()
                "sqrt" -> squareRoot(single())
                "round" -> when (values.size) {
                    1 -> number(values[0]).round()
                    2 -> {
                        val places = number(values[1]) as? CalcNumber.Whole
                            ?: throw FormulaError("round(…, places) needs a whole number of places.")
                        number(values[0]).round(places.value.toInt())
                    }
                    else -> throw FormulaError("round(…) takes a value and, if wanted, a number of places.")
                }
                else -> throw FormulaError("The function $name is not supported here.")
            },
        )
    }

    private fun squareRoot(n: CalcNumber): CalcNumber {
        if (n.signum() < 0) throw FormulaError("sqrt($n) is not a real number.")
        if (n is CalcNumber.Whole) {
            val root = sqrt(n.value.toDouble()).toLong()
            for (r in root - 1..root + 1) if (r >= 0 && r * r == n.value) return CalcNumber.Whole(r)
        }
        return CalcNumber.fromDouble(sqrt(n.toDouble()))
    }

    fun number(value: Value): CalcNumber = when (value) {
        is Value.Number -> value.value
        is Value.Cell -> CalcNumber.parse(value.text) ?: throw FormulaError(
            "Row ${layout.rowNumber(value.line)}, column ${value.column} holds “${value.text}”, which is not a number.",
        )
        is Value.Several -> throw FormulaError("A range has to go in a function, such as vsum(…) or vmean(…).")
    }

    private fun divisionByZero(): Nothing = throw FormulaError("Division by zero.")
}

/** A formula of a table that cannot be computed, and why. */
data class FormulaProblem(
    /** The formula's place in the table's list, or -1 for one about the whole table. */
    val formula: Int,
    val message: String,
)

/** A cell a formula fills: its line in the table (rules counted) and its column, both from 0. */
data class FormulaCell(val line: Int, val column: Int)

/** What recalculating a table's formulas gave. */
sealed interface Recalculation {
    /** The table's rows with every formula's result in its cell. */
    data class Done(val rows: List<List<String>?>) : Recalculation

    /** Nothing was computed, because of these. */
    data class Failed(val problems: List<FormulaProblem>) : Recalculation
}

/** A formula ready to compute, with the cells it fills. */
private class Planned(val index: Int, val formula: TableFormula, val cells: List<Pair<Int, Int>>, val isColumn: Boolean)

/**
 * Recomputes every one of [formulas] over [rows], as Emacs's `org-table-recalculate` does with a
 * prefix argument: formulas in the order of what they fill, column formulas first, row by row below
 * the header, then the formulas for single cells — each reading what those before it wrote. A cell
 * that a formula of its own fills is left alone by its column's formula.
 *
 * Any formula that cannot be computed fails the whole: Emacs would write `#ERROR`, or a sum of names,
 * into the table, and nothing should be written that has to be undone by hand.
 */
fun recalculate(rows: List<List<String>?>, formulas: List<String>): Recalculation {
    val layout = TableLayout(rows)
    val problems = mutableListOf<FormulaProblem>()
    val plan = plan(layout, formulas, problems)
    if (problems.isNotEmpty()) return Recalculation.Failed(problems)

    val cells = rows.map { it?.toMutableList() }
    // Read as it is written, so that each formula sees what those before it computed.
    val live = TableLayout(cells)
    val fixed = plan.filterNot { it.isColumn }.flatMap { it.cells }.toSet()
    val columnFormulas = plan.filter { it.isColumn }
    val failed = mutableSetOf<Int>()

    fun compute(planned: Planned, line: Int, column: Int) {
        if (planned.index in failed) return
        try {
            val evaluation = Evaluation(live, line, column, planned.formula.numbers)
            val result = evaluation.evaluate(planned.formula.expression)
            val text = evaluation.number(result).toCalcString()
            val written = planned.formula.format?.let { formatLikeEmacs(it, text) } ?: text
            cells[line]!![column - 1] = written
        } catch (e: FormulaError) {
            failed += planned.index
            val where = "row ${layout.rowNumber(line)}, column $column"
            problems += FormulaProblem(planned.index, if (planned.isColumn) "In $where: ${e.message}" else e.message.orEmpty())
        }
    }

    for (line in layout.firstBodyLine until layout.lineCount) {
        if (!layout.isDataLine(line)) continue
        for (planned in columnFormulas) {
            val column = planned.cells.first().second
            if ((line to column) !in fixed) compute(planned, line, column)
        }
    }
    for (planned in plan.filterNot { it.isColumn }) {
        for ((line, column) in planned.cells) compute(planned, line, column)
    }
    return if (problems.isEmpty()) Recalculation.Done(cells.map { it?.toList() }) else Recalculation.Failed(problems)
}

/**
 * The cells each of [formulas] fills in [rows], by the formula's place in the list; a formula that
 * cannot be read fills none. What the editor marks as computed.
 */
fun formulaCells(rows: List<List<String>?>, formulas: List<String>): Map<FormulaCell, Int> {
    val layout = TableLayout(rows)
    val plan = plan(layout, formulas, mutableListOf())
    val cells = mutableMapOf<FormulaCell, Int>()
    // Single cells win over their column's formula, as they do when computing.
    for (planned in plan.sortedBy { !it.isColumn }) {
        val targets = if (planned.isColumn) {
            val column = planned.cells.first().second
            (layout.firstBodyLine until layout.lineCount).filter { layout.isDataLine(it) }.map { it to column }
        } else {
            planned.cells
        }
        for ((line, column) in targets) cells[FormulaCell(line, column - 1)] = planned.index
    }
    return cells
}

/** The formulas in the order Emacs runs them, each with the cells it fills; what is wrong goes in [problems]. */
private fun plan(layout: TableLayout, formulas: List<String>, problems: MutableList<FormulaProblem>): List<Planned> {
    val marks = setOf("#", "*", "!", "^", "_", "$", "/")
    if (layout.dataLines.any { layout.cell(it, 1) in marks } && formulas.isNotEmpty()) {
        problems += FormulaProblem(-1, "This table marks rows for recalculation in its first column, which only Emacs supports.")
        return emptyList()
    }
    val planned = mutableListOf<Planned>()
    val claimed = mutableMapOf<Pair<Int, Int>, Int>()
    for ((index, text) in formulas.withIndex()) {
        try {
            val formula = TableFormula.parse(text)
            val target = formula.target
            if (Regex("""^@[-+]?I""").containsMatchIn(target)) throw FormulaError("A formula cannot fill a rule ($target).")
            if (target.startsWith("$")) {
                val column = layout.column(target.drop(1), 0)
                planned += Planned(index, formula, listOf(-1 to column), isColumn = true)
                continue
            }
            val cells = targetCells(layout, target)
            for (cell in cells) {
                claimed[cell]?.let { other ->
                    throw FormulaError("Formula ${other + 1} already fills row ${layout.rowNumber(cell.first)}, column ${cell.second}.")
                }
                claimed[cell] = index
            }
            planned += Planned(index, formula, cells, isColumn = false)
        } catch (e: FormulaError) {
            problems += FormulaProblem(index, e.message.orEmpty())
        }
    }
    val columns = planned.filter { it.isColumn }.groupBy { it.cells.first().second }
    for ((column, same) in columns) {
        if (same.size > 1) problems += FormulaProblem(same[1].index, "Column $column already has a formula (formula ${same[0].index + 1}).")
    }
    // `string<` on what each formula fills, as Emacs sorts them before running them.
    return planned.sortedWith { a, b -> a.formula.target.compareTo(b.formula.target) }
}

/** The cells a formula's [target] — `@2$3`, `@>$3`, `@4`, `@2$3..@5$3` — fills, as (line, column). */
private fun targetCells(layout: TableLayout, target: String): List<Pair<Int, Int>> {
    val expr = try {
        FormulaParser(target).parse()
    } catch (e: FormulaError) {
        throw FormulaError("“$target” is not a cell or a range a formula can fill.")
    }
    // Rows and columns counted from the cell being computed mean nothing for what a formula fills.
    fun fixed(desc: String?): String {
        if (desc.isNullOrEmpty() || desc[0] == '+' || desc[0] == '-' || 'I' in desc) {
            throw FormulaError("A formula has to fill fixed cells, as @2\$3 or @>\$3, not “$target”.")
        }
        return desc
    }
    return when (expr) {
        is Expr.Field -> {
            val line = layout.line(fixed(expr.row), 0)
            if (expr.column == null) (1..layout.columnCount).map { line to it }
            else listOf(line to layout.column(fixed(expr.column), 0))
        }
        is Expr.Range -> {
            val r1 = layout.line(fixed(expr.row1), 0)
            val r2 = layout.line(fixed(expr.row2), 0)
            val c1 = layout.column(fixed(expr.column1), 0)
            val c2 = layout.column(fixed(expr.column2 ?: expr.column1), 0)
            (minOf(r1, r2)..maxOf(r1, r2)).filter { layout.isDataLine(it) }.flatMap { line ->
                (minOf(c1, c2)..maxOf(c1, c2)).map { line to it }
            }
        }
        else -> throw FormulaError("“$target” is not a cell or a range a formula can fill.")
    }
}
