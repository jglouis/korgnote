package com.korgnote.common

import com.korgnote.common.org.table.FormulaCell
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.formulaCells
import com.korgnote.common.org.table.recalculate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A table written as Org writes one, as rows of trimmed cells and null for a rule. */
internal fun table(text: String): List<List<String>?> = text.trimIndent().lines().map { line ->
    val trimmed = line.trim()
    if (trimmed.startsWith("|-")) null
    else trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
}

/**
 * Each expected table is what Emacs 31.1 wrote when recalculating the same table and formulas with
 * `org-table-recalculate-buffer-tables`, alignment aside.
 */
class TableCalcTest {

    private fun computed(rows: List<List<String>?>, vararg formulas: String): List<List<String>?> {
        val result = recalculate(rows, formulas.toList())
        assertIs<Recalculation.Done>(result, "$result")
        return result.rows.map { row -> row?.map { it.trim() } }
    }

    private fun problems(rows: List<List<String>?>, vararg formulas: String): List<String> {
        val result = recalculate(rows, formulas.toList())
        assertIs<Recalculation.Failed>(result)
        return result.problems.map { it.message }
    }

    @Test
    fun multiplicationBindsTighterThanDivisionAsInCalc() {
        val rows = table(
            """
            | a | b | c | d | e | f |
            |---+---+---+---+---+---|
            | 2 | 3 | 4 |   |   |   |
            | 7 | 2 | 0.5 | | |   |
            | 12 | 3 | 2 | |  |   |
            """,
        )
        assertEquals(
            table(
                """
                |  a | b |   c |          d |          e |    f |
                |----+---+-----+------------+------------+------|
                |  2 | 3 |   4 | 0.16666667 | 0.66666667 |    4 |
                |  7 | 2 | 0.5 |         7. |        3.5 | 48.5 |
                | 12 | 3 |   2 |          2 |          4 | 1726 |
                """,
            ),
            computed(rows, "$4=$1/$2*$3", "$5=$1/$2", "$6=$1^$2 - $3"),
        )
    }

    @Test
    fun totalsReadUpToTheRuleAboveThemAndFormatTheirResult() {
        val rows = table(
            """
            | item | price | vat | total |
            |------+-------+-----+-------|
            | a    |  2000 | 0.21 |      |
            | b    | 167.70 |    0 |      |
            | c    |   320 |    0 |      |
            | d    | 9793.56 | 0.06 |    |
            |------+-------+-----+-------|
            | sum  |       |     |       |
            """,
        )
        assertEquals(
            table(
                """
                | item |    price |   vat |     total |
                |------+----------+-------+-----------|
                | a    |     2000 |  0.21 |     2420. |
                | b    |   167.70 |     0 |     167.7 |
                | c    |      320 |     0 |       320 |
                | d    |  9793.56 |  0.06 | 10381.174 |
                |------+----------+-------+-----------|
                | sum  | 12281.26 | 0.068 |  13288.87 |
                """,
            ),
            computed(rows, "$4=$2*(1+$3)", "@>$2=vsum(@I..@-I)", "@>$4=vsum(@I..@-I);%.2f", "@>$3=vmean(@I..@-I);%.3f"),
        )
    }

    @Test
    fun firstAndLastRowsAndColumns() {
        val rows = table(
            """
            | x | y | z |
            |---+---+---|
            | 1 | 10 |  |
            | 2 | 20 |  |
            | 3 | 30 |  |
            | 4 | 40 |  |
            """,
        )
        assertEquals(
            table(
                """
                | x |  y |  30 |
                |---+----+-----|
                | 1 | 10 |  12 |
                | 2 | 20 |  43 |
                | 3 | 30 |  94 |
                | 4 | 40 | 165 |
                """,
            ),
            computed(rows, "$>=$<*$2 + @#", "@<$>=@>$2 - @<<$2"),
        )
    }

    @Test
    fun theNumbersModeReadsTextAsZero() {
        val rows = table(
            """
            | v | n |
            |---+---|
            | 12abc | |
            | none | |
            | 2.5kg | |
            | | |
            """,
        )
        assertEquals(
            table(
                """
                | v     |  n |
                |-------+----|
                | 12abc | 24 |
                | none  |  0 |
                | 2.5kg | 5. |
                |       |  0 |
                """,
            ),
            computed(rows, "$2=$1*2;N"),
        )
    }

    @Test
    fun roundingAndSummaryFunctions() {
        val rows = table(
            """
            | v | r1 | r2 | f | c |
            |---+----+----+---+---|
            | 2.567 | | | | |
            | -2.5 | | | | |
            | 1234.5678 | | | | |
            | 0.125 | | | | |
            |---+----+----+---+---|
            | | | | | |
            """,
        )
        assertEquals(
            table(
                """
                |         v |   r1 |      r2 |    f |    c |
                |-----------+------+---------+------+------|
                |     2.567 |    3 |    2.57 |    2 |    3 |
                |      -2.5 |   -3 |    -2.5 |   -3 |   -2 |
                | 1234.5678 | 1235 | 1234.57 | 1234 | 1235 |
                |     0.125 |    0 |    0.13 |    0 |    1 |
                |-----------+------+---------+------+------|
                |     1.346 |    4 | 1234.57 |   -3 | 1237 |
                """,
            ),
            computed(
                rows,
                "$2=round($1)", "$3=round($1, 2)", "$4=floor($1)", "$5=ceil($1)",
                "@>$1=vmedian(@I..@II)", "@>$2=vcount(@I..@II)", "@>$3=vmax(@I..@II)", "@>$4=vmin(@I..@II)",
                "@>$5=vsum(@I..@II);%d",
            ),
        )
    }

    @Test
    fun rangesSkipRulesAndCanBeFilled() {
        val rows = table(
            """
            | g | s1 | s2 | m |
            |---+----+----+---|
            | a | 1 | 2 | |
            | b | 3 | 4 | |
            |---+----+----+---|
            | c | 5 | 6.5 | |
            |---+----+----+---|
            | t | | | |
            """,
        )
        assertEquals(
            table(
                """
                | g | s1 |  s2 |    m |
                |---+----+-----+------|
                | a |  1 |   2 |  1.5 |
                | b |  3 |   4 |  3.5 |
                |---+----+-----+------|
                | c |  5 | 6.5 | 5.75 |
                |---+----+-----+------|
                | t |  5 | 6.5 |  6.5 |
                """,
            ),
            computed(rows, "@2$4..@4$4=vmean($2..$3)", "@>$2..@>$3=vsum(@II..@IIII)", "@>$4=vmax(@I$2..@III$3)"),
        )
    }

    @Test
    fun divisionRemaindersComparisonsAndExponents() {
        val rows = table(
            """
            | a | b | div | idiv | mod | cmp | big | small |
            |---+---+-----+------+-----+-----+-----+-------|
            | 7 | 2 | | | | | | |
            | -7 | 3 | | | | | | |
            | 1 | 3 | | | | | | |
            | 10 | 4 | | | | | | |
            """,
        )
        assertEquals(
            table(
                """
                |  a | b |        div | idiv | mod | cmp |            big |         small |
                |----+---+------------+------+-----+-----+----------------+---------------|
                |  7 | 2 |        3.5 |    3 |   1 |   1 |  864197520000. |        3.5e-3 |
                | -7 | 3 | -2.3333333 |   -3 |   2 |   0 | -864197520000. | -2.3333333e-3 |
                |  1 | 3 | 0.33333333 |    0 |   1 |   0 |  123456790000. |  3.3333333e-4 |
                | 10 | 4 |        2.5 |    2 |   2 |   1 |   1.2345679e12 |        2.5e-3 |
                """,
            ),
            computed(
                rows,
                "$3=$1/$2", "$4=$1\\$2", "$5=$1%$2", "$6=if($1>$2 && $2>2, 1, 0) + ($1==7)",
                "$7=$1*1.0*123456789012", "$8=$1/$2/1000",
            ),
        )
    }

    @Test
    fun theRowAboveIsReachedAcrossARule() {
        val rows = table(
            """
            | d | idx | diff |
            |---+-----+------|
            | a | 486 | |
            | b | 571 | |
            |---+-----+------|
            | c | 656.5 | |
            """,
        )
        assertEquals(
            listOf("0", "85", "85.5"),
            computed(rows, "$3=if(@#>2, $2 - @-1$2, 0)").mapNotNull { it?.get(2) }.drop(1),
        )
    }

    @Test
    fun withoutAHeaderEveryRowIsComputed() {
        val rows = table(
            """
            | 1 | 2 | |
            | 3 | 4 | |
            """,
        )
        assertEquals(table("| 1 | 2 | 3 |\n| 3 | 4 | 7 |"), computed(rows, "$3=$1+$2"))
    }

    @Test
    fun aCellsOwnFormulaWinsOverItsColumns() {
        val rows = table(
            """
            | a | b |
            |---+---|
            | 1 |   |
            | 2 |   |
            |---+---|
            | t |   |
            """,
        )
        assertEquals(listOf("2", "4", "6"), computed(rows, "$2=$1*2", "@>$2=vsum(@I..@-I)").drop(2).mapNotNull { it?.get(1) })
    }

    @Test
    fun nothingIsWrittenWhenAFormulaCannotBeComputed() {
        val rows = table(
            """
            | who | what | amount |
            |-----+------+--------|
            | me  | rent |    100 |
            | you | food |     20 |
            |-----+------+--------|
            |     |      |        |
            """,
        )
        assertEquals(listOf("Row 2, column 2 holds “rent”, which is not a number."), problems(rows, "@4$2=vsum(@2$2..@3$2)"))
        assertTrue(problems(rows, "@>$3=vsum(@2$3..@14$3)").single().startsWith("@14 is outside the table"))
        assertEquals(
            listOf("In row 2, column 3: \$2..\$1 runs backwards, so Org reads no cell from it; write \$1..\$2."),
            problems(rows, "$3=vmean($2..$1)"),
        )
        assertTrue(problems(rows, "$3='(+ 1 2)").single().contains("Emacs Lisp"))
        assertTrue(problems(rows, "$3=\$rate * 2").single().contains("not supported"))
    }

    @Test
    fun theCellsFormulasFill() {
        val rows = table(
            """
            | a | b |
            |---+---|
            | 1 |   |
            |---+---|
            | t |   |
            """,
        )
        assertEquals(
            mapOf(FormulaCell(2, 1) to 0, FormulaCell(4, 1) to 1),
            formulaCells(rows, listOf("$2=$1*2", "@>$2=vsum(@I..@-I)")),
        )
    }
}
