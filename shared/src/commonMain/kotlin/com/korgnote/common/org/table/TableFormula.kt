package com.korgnote.common.org.table

/**
 * One formula of a table's `#+TBLFM:` line, as Org writes it: `$6=min(20, $2+$3+$4)` fills the sixth
 * column of every row, `@>$2=vsum(@II..@III)` one cell of the last row.
 *
 * A table's formulas are kept as the text of each — what [formulaEntries] splits the line into — so
 * that a table saved without touching them is written back exactly as it was read. They are parsed
 * when they are needed: to recalculate, to check them, to show them.
 */
internal class TableFormula(
    /** What the formula fills, as written: `$6`, `@>$2`, `@17$9..@26$9`. */
    val target: String,
    val expression: Expr,
    /** What follows the last `;`: a `printf` format for the result, as `%.2f`. */
    val format: String?,
    /** Whether the format asked for `N`: every cell read as a number, text as zero. */
    val numbers: Boolean,
) {
    companion object {
        private val entry = Regex("""(@[-+I<>0-9.$@]+|\$(?:[_a-zA-Z0-9]+|[<>]+)) *= *(.*[^ \t])""")

        /** [text], one entry of a `#+TBLFM:` line, or a [FormulaError] saying why it cannot be computed here. */
        fun parse(text: String): TableFormula {
            val match = entry.matchEntire(text.trim()) ?: throw FormulaError("This is not a formula Org reads: “$text”.")
            val target = match.groupValues[1]
            if (target.startsWith("$") && !target.drop(1).all { it.isDigit() || it == '<' || it == '>' }) {
                throw FormulaError("Named fields ($target) are not supported here; edit this formula in Emacs.")
            }
            var right = match.groupValues[2]
            var format: String? = null
            var numbers = false
            val semicolon = right.lastIndexOf(';')
            if (semicolon >= 0) {
                var modes = right.substring(semicolon + 1)
                right = right.substring(0, semicolon)
                if (Regex("""[pnfse]-?[0-9]+""").containsMatchIn(modes)) {
                    throw FormulaError("Calc display modes (;${modes.trim()}) are not supported here; use a format such as ;%.2f.")
                }
                Regex("""[tTUNLEDRFSu]""").findAll(modes).forEach { flag ->
                    if (flag.value != "N") throw FormulaError("The ;${flag.value} mode is not supported here; edit this formula in Emacs.")
                    numbers = true
                }
                modes = modes.replace("N", "")
                if (modes.isNotBlank()) {
                    if (formatLikeEmacs(modes, "0") == null) {
                        throw FormulaError("The format ;${modes.trim()} is not supported here; use one such as ;%.2f.")
                    }
                    format = modes
                }
            }
            if (right.trimStart().startsWith("'(")) throw FormulaError("Emacs Lisp formulas only run in Emacs.")
            return TableFormula(target, FormulaParser(right).parse(), format, numbers)
        }
    }
}

/** Why a formula cannot be computed, said so that whoever wrote it can fix it. */
class FormulaError(message: String) : Exception(message)

/** Why [formula], one entry of a `#+TBLFM:` line, cannot be read, or null when it can be. */
fun formulaSyntaxProblem(formula: String): String? =
    try {
        TableFormula.parse(formula)
        null
    } catch (e: FormulaError) {
        e.message
    }

/** The entries of a `#+TBLFM:` line's [value] — what follows the keyword — in order. */
fun formulaEntries(value: String): List<String> =
    value.split(Regex(" *:: *")).map { it.trim() }.filter { it.isNotEmpty() }

/** A formula's right-hand side, as Calc would read it once Org has put the cells' values in. */
internal sealed interface Expr {
    data class Number(val value: CalcNumber) : Expr

    /**
     * One cell: `$3` in the row being computed, `@2$3`, `@-1` (the column being computed), `@>$3`.
     * A null [row] is the row being computed, a null [column] the column.
     */
    data class Field(val row: String?, val column: String?) : Expr

    /** The cells from one corner to the other, row by row: `@2$3..@10$3`, `@II..@III`. */
    data class Range(val row1: String?, val column1: String?, val row2: String?, val column2: String?) : Expr

    /** Cells of the row being computed, from column [first] to column [last]: `$2..$5`. */
    data class Columns(val first: String, val last: String) : Expr

    /** `@#`: the number of the row being computed. */
    data object RowNumber : Expr

    /** `$#`: the number of the column being computed. */
    data object ColumnNumber : Expr

    data class Negate(val operand: Expr) : Expr
    data class Binary(val operator: String, val left: Expr, val right: Expr) : Expr
    data class Call(val name: String, val arguments: List<Expr>) : Expr
}

/**
 * Reads a formula's right-hand side. Operators bind as in Calc, not as in most languages: `*` binds
 * tighter than `/`, so `2/3*3` is `2/9` — Emacs computes it that way, so this does too.
 */
internal class FormulaParser(private val text: String) {
    private var pos = 0

    fun parse(): Expr {
        val expr = expression(0)
        skipSpaces()
        if (pos < text.length) fail("Unexpected “${text.substring(pos)}”.")
        return expr
    }

    private fun expression(minPrecedence: Int): Expr {
        var left = prefix()
        while (true) {
            skipSpaces()
            val op = BINARY.firstOrNull { text.startsWith(it.symbol, pos) } ?: break
            if (op.left < minPrecedence) break
            pos += op.symbol.length
            left = Expr.Binary(op.symbol, left, expression(op.right))
        }
        return left
    }

    private fun prefix(): Expr {
        skipSpaces()
        if (pos >= text.length) fail("The formula ends too early.")
        val c = text[pos]
        return when {
            c == '-' -> { pos++; Expr.Negate(expression(NEGATE)) }
            c == '+' -> { pos++; expression(NEGATE) }
            c == '(' -> {
                pos++
                val inner = expression(0)
                expect(')')
                inner
            }
            c == '@' -> reference()
            c == '$' -> columnReference()
            c.isDigit() || c == '.' -> number()
            c.isLetter() -> call()
            c == '"' -> fail("Text in quotes is not supported here.")
            c == '[' -> fail("Vectors written out ([…]) are not supported here.")
            else -> fail("Unexpected “$c”.")
        }
    }

    private fun number(): Expr {
        val match = NUMBER.matchAt(pos) ?: fail("Unexpected “${text.substring(pos)}”.")
        pos = match.range.last + 1
        return Expr.Number(CalcNumber.parse(match.value) ?: fail("“${match.value}” is not a number."))
    }

    private fun call(): Expr {
        val start = pos
        while (pos < text.length && text[pos].isLetterOrDigit()) pos++
        val name = text.substring(start, pos)
        skipSpaces()
        if (pos >= text.length || text[pos] != '(') {
            fail("“$name” is not something KorgNote can compute; only cells, numbers and functions such as vsum(…) are.")
        }
        if (name !in FUNCTIONS) fail("The function $name is not supported here; edit this formula in Emacs.")
        pos++
        val arguments = mutableListOf<Expr>()
        skipSpaces()
        if (text.getOrNull(pos) == ')') {
            pos++
        } else {
            while (true) {
                arguments += expression(0)
                skipSpaces()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    ')' -> { pos++; break }
                    else -> fail("Expected “,” or “)” in $name(…).")
                }
            }
        }
        return Expr.Call(name, arguments)
    }

    /** `@…`: a cell, or a range when `..` follows, as Org's `org-table-range-regexp` reads them. */
    private fun reference(): Expr {
        pos++ // @
        if (text.getOrNull(pos) == '#') { pos++; return Expr.RowNumber }
        val row1 = rowPart()
        val column1 = if (text.getOrNull(pos) == '$') { pos++; columnPart() } else null
        if (text.startsWith("..", pos)) {
            pos += 2
            if (text.getOrNull(pos) == '@') pos++
            val row2 = rowPart()
            val column2 = if (text.getOrNull(pos) == '$') { pos++; columnPart() } else null
            return Expr.Range(row1, column1, row2, column2)
        }
        if (row1.isEmpty() && column1 == null) fail("“@” has to name a row, as @2 or @>.")
        return Expr.Field(row1, column1)
    }

    /** `$…`: a column of the row being computed, or `$2..$5`. */
    private fun columnReference(): Expr {
        pos++ // $
        if (text.getOrNull(pos) == '#') { pos++; return Expr.ColumnNumber }
        if (text.getOrNull(pos)?.isLetter() == true) {
            fail("Named columns and constants (\$${text.drop(pos).takeWhile { it.isLetterOrDigit() || it == '_' }}) are not supported here.")
        }
        val first = columnPart()
        if (text.startsWith("..$", pos)) {
            pos += 3
            return Expr.Columns(first, columnPart())
        }
        return Expr.Field(null, first)
    }

    private fun rowPart(): String {
        if (text.getOrNull(pos) == '<' || text.getOrNull(pos) == '>') return repeated(text[pos])
        val match = ROW.matchAt(pos)!! // matches the empty string at worst
        pos = match.range.last + 1
        return match.value
    }

    private fun columnPart(): String {
        if (text.getOrNull(pos) == '<' || text.getOrNull(pos) == '>') return repeated(text[pos])
        val match = COLUMN.matchAt(pos) ?: fail("“$” has to name a column, as \$3 or \$>.")
        pos = match.range.last + 1
        return match.value
    }

    private fun repeated(c: Char): String {
        val start = pos
        while (text.getOrNull(pos) == c) pos++
        return text.substring(start, pos)
    }

    private fun Regex.matchAt(index: Int): MatchResult? = find(text, index)?.takeIf { it.range.first == index }

    private fun expect(c: Char) {
        skipSpaces()
        if (text.getOrNull(pos) != c) fail("Expected “$c”.")
        pos++
    }

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(message: String): Nothing = throw FormulaError(message)

    private class Operator(val symbol: String, val left: Int, val right: Int)

    companion object {
        /** Functions computed here, by their Calc names. */
        val FUNCTIONS = setOf(
            "vsum", "vmean", "vmedian", "vmin", "vmax", "vcount",
            "min", "max", "abs", "round", "floor", "ceil", "trunc", "sqrt", "if",
        )

        // Calc's binding powers (math-standard-opers): the left one decides whether an operator
        // takes what is on its left, the right one how much it takes on its right. Longer symbols
        // come first so that `<=` is not read as `<`.
        private val BINARY = listOf(
            Operator("==", 160, 161), Operator("!=", 160, 161), Operator("<=", 160, 161), Operator(">=", 160, 161),
            Operator("&&", 110, 111), Operator("||", 100, 101),
            Operator("^", 201, 200),
            Operator("*", 196, 195),
            Operator("/", 190, 191), Operator("%", 190, 191), Operator("\\", 190, 191),
            Operator("+", 180, 181), Operator("-", 180, 181),
            Operator("<", 160, 161), Operator(">", 160, 161),
        )
        private const val NEGATE = 197

        private val NUMBER = Regex("""(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
        private val ROW = Regex("""[-+]?I*[-+]?[0-9]*""")
        private val COLUMN = Regex("""[-+]?[0-9]+""")
    }
}
