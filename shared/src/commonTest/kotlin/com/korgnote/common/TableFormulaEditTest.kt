package com.korgnote.common

import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.replaceOrgTable
import com.korgnote.common.org.edit.tableFormulaLine
import com.korgnote.common.org.table.FormulaNote
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.describeFormula
import com.korgnote.common.org.table.reviewFormulas
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A table's formulas as the grid editor keeps them: read, renumbered, checked, written back. */
class TableFormulaEditTest {

    private val expenses = table(
        """
        | who | what | amount |
        |-----+------+--------|
        | me  | rent |    100 |
        | you | food |     20 |
        |-----+------+--------|
        |     |      |    120 |
        """,
    )

    private fun grid(vararg formulas: String) = TableGrid(expenses, formulas.toList())

    @Test
    fun insertingAColumnRenumbersTheColumnsAfterIt() {
        assertEquals(listOf("@>$4=vsum(@I$4..@II$4)"), grid("@>$3=vsum(@I$3..@II$3)").withColumnInserted(1).formulas)
        assertEquals(listOf("@>$3=vsum(@I$3..@II$3)"), grid("@>$3=vsum(@I$3..@II$3)").withColumnInserted(3).formulas)
    }

    @Test
    fun movingAColumnTakesItsReferencesAlong() {
        assertEquals(listOf("$2=$3*2"), grid("$3=$2*2").withColumnMoved(1, left = false).formulas)
    }

    @Test
    fun deletingAColumnDropsWhatFilledItAndMarksWhatReadIt() {
        val grid = grid("$3=$2*2", "@>$2=vcount(@I..@II)").withColumnRemoved(1)
        assertEquals(listOf("$2=\$INVALID*2"), grid.formulas)
        assertIs<Recalculation.Failed>(grid.recalculated())
    }

    @Test
    fun rowsAreRenumberedAsInEmacs() {
        // A row inserted above the total moves it from @4 to @5; the range still ends where it did,
        // which is why ranges are better written between rules.
        assertEquals(listOf("@5$3=vsum(@2$3..@3$3)"), grid("@4$3=vsum(@2$3..@3$3)").withRowInserted(4).formulas)
        assertEquals(listOf("@3$3=@2$3"), grid("@2$3=@3$3").withRowMoved(2, up = false).formulas)
        assertEquals(
            listOf("@3$3=vsum(@2$3..@INVALID$3)"),
            grid("@4$3=vsum(@2$3..@3$3)", "@3$3=1").withRowRemoved(3).formulas,
        )
    }

    @Test
    fun rulesAreRenumberedToo() {
        assertEquals(listOf("@>$3=vsum(@II..@-I)"), grid("@>$3=vsum(@I..@-I)").withRuleInserted(0).formulas)
        assertEquals(listOf("@>$3=vsum(@I$3..@-I)"), grid("@>$3=vsum(@II$3..@-I)").withRowRemoved(1).formulas)
        assertEquals(listOf("@>$3=vsum(@INVALID..@-I)"), grid("@>$3=vsum(@I..@-I)").withRowRemoved(1).formulas)
    }

    @Test
    fun aRowAddedAboveATotalIsCounted() {
        val grid = grid("@>$3=vsum(@I..@-I)").withRowInserted(4).withCell(4, 2, "5")
        val result = grid.recalculated()
        assertIs<Recalculation.Done>(result)
        assertEquals("125", result.rows.last()!![2])
    }

    @Test
    fun aTotalCanBeAddedInOneGo() {
        val bare = TableGrid(table("| a | n |\n|---+---|\n| x | 1 |\n| y | 2 |"))
        val summed = bare.withSummary(1, "vsum")
        assertEquals(table("| a | n |\n|---+---|\n| x | 1 |\n| y | 2 |\n|---+---|\n| | |"), summed.rows)
        assertEquals(listOf("@>$2=vsum(@I..@-I)"), summed.formulas)
        assertEquals("3", (summed.recalculated() as Recalculation.Done).rows.last()!![1])

        // A second total goes in the same row, and one for the same cell replaces the first.
        val averaged = summed.withSummary(1, "vmean")
        assertEquals(summed.rows, averaged.rows)
        assertEquals(listOf("@>$2=vmean(@I..@-I)"), averaged.formulas)
    }

    @Test
    fun theFormulaLineIsReadAndWrittenWithTheTable() {
        val text = "Before\n| a | b |\n|---+---|\n| 1 | 2 |\n#+TBLFM: \$2=\$1*2\nAfter\n"
        val table = text.toOrgDocument().nodeAt<OrgTable>(text.indexOf('|'))!!
        val line = tableFormulaLine(text, table.range.last + 1)!!
        assertEquals(listOf("\$2=\$1*2"), line.formulas)

        val grid = TableGrid.of(table, line.formulas)
        // Untouched, the line stays as it was written, spacing and all.
        val spaced = text.replace("#+TBLFM: \$2=\$1*2", "#+tblfm:  \$2=\$1*2")
        assertEquals(spaced, replaceOrgTable(spaced, table.range, grid))

        assertEquals(
            "Before\n| a | b |\n|---+---|\n| 1 | 2 |\n#+TBLFM: \$2=\$1*2::@>\$1=vsum(@I..@-I)\nAfter\n",
            replaceOrgTable(text, table.range, grid.withFormulas(grid.formulas + "@>\$1=vsum(@I..@-I)")),
        )
        assertEquals(
            "Before\n| a | b |\n|---+---|\n| 1 | 2 |\nAfter\n",
            replaceOrgTable(text, table.range, grid.withFormulas(emptyList())),
        )
    }

    @Test
    fun aTableGetsAFormulaLineWhenItHadNone() {
        val text = "  | a | b |\n  | 1 | 2 |\nAfter\n"
        val table = text.toOrgDocument().nodeAt<OrgTable>(text.indexOf('|'))!!
        assertNull(tableFormulaLine(text, table.range.last + 1))
        assertEquals(
            "  | a | b |\n  | 1 | 2 |\n  #+TBLFM: \$2=\$1*2\nAfter\n",
            replaceOrgTable(text, table.range, TableGrid.of(table, listOf("\$2=\$1*2"))),
        )
        val last = "| a | b |"
        val lastTable = last.toOrgDocument().nodeAt<OrgTable>(0)!!
        assertEquals("| a | b |\n#+TBLFM: \$2=1", replaceOrgTable(last, lastTable.range, TableGrid.of(lastTable, listOf("\$2=1"))))
    }

    @Test
    fun theReviewFindsWhatIsLikelyAMistake() {
        val exams = table(
            """
            |------+---+---+---+---|
            | name | a | b | c | d |
            |------+---+---+---+---|
            | x    | 1 | 2 | 3 | 4 |
            |------+---+---+---+---|
            | y    | 5 | 6 | 7 | 8 |
            |------+---+---+---+---|
            |      | 3 | 4 | 7 | 6 |
            |------+---+---+---+---|
            """,
        )
        val formulas = listOf(
            "@>$2=vmean(@II$2..@IIII$2)",
            "@>$3=vmean(@II$3..@IIII$3)",
            "@>$4=vmean(@III$4..@IIII$4)",
            "@>$5=vmean(@II$5..@IIII$5)",
        )
        assertEquals(
            listOf(
                FormulaNote(
                    2,
                    FormulaNote.Kind.Suggestion,
                    "Unlike the other formulas of this row, this one reads row 3, where they read rows 2–3.",
                    fix = "@>$4=vmean(@II$4..@IIII$4)",
                ),
            ),
            reviewFormulas(exams, formulas),
        )
        val edited = exams.mapIndexed { i, row -> if (i == 3) row!!.toMutableList().also { it[1] = "2" } else row }
        assertEquals(
            FormulaNote(-1, FormulaNote.Kind.Outdated, "A cell does not hold what its formula gives. Recalculate to update it."),
            reviewFormulas(edited, formulas).first(),
        )
    }

    @Test
    fun rowsNamedByNumberCanFollowTheLinesInstead() {
        val notes = reviewFormulas(expenses, listOf("@4$3=vsum(@2$3..@3$3)"))
        assertEquals("@>$3=vsum(@I$3..@II$3)", notes.single().fix)
    }

    @Test
    fun formulasReadInWords() {
        assertEquals("‹amount›, last row = sum(‹amount›, rows 2–3)", describeFormula(expenses, "@>$3=vsum(@I..@-I)"))
        assertEquals("‹amount›, every row = ‹amount› of the row above * 2  (shown as %.2f)", describeFormula(expenses, "$3=@-1$3 * 2;%.2f"))
        assertEquals("‹what›, row 2 = ‹who› to ‹amount›", describeFormula(expenses, "@2$2=$1..$3"))
    }
}
