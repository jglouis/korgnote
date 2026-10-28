package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.common.org.edit.TableGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The table editor with formulas: computed on demand only, and only when every formula can be. */
@OptIn(ExperimentalTestApi::class)
class TableEditorFormulaTest {
    private var saved: TableGrid? = null

    private val rows = listOf(
        listOf("a", "b", "c"),
        null,
        listOf("2", "3", ""),
        listOf("4", "5", ""),
    )

    private fun ComposeUiTest.show(vararg formulas: String) = setContent {
        MaterialTheme {
            TableEditorDialog(initial = TableGrid(rows, formulas.toList()), onSave = { saved = it }, onDismiss = {})
        }
    }

    @Test
    fun recalculateFillsTheComputedCells() = runComposeUiTest {
        show("$3=$1*$2")
        onNodeWithText("Cells are out of date. Tap to see the formulas.").assertExists()

        onNodeWithText("Recalculate").performClick()
        onNodeWithText("Recalculated.").assertExists()
        onNodeWithText("Save").performClick()

        assertEquals(listOf("6", "20"), saved!!.rows.drop(2).map { it!![2] })
        assertEquals(listOf("$3=$1*$2"), saved!!.formulas)
    }

    @Test
    fun aFormulaThatCannotBeComputedStopsTheWholeRecalculation() = runComposeUiTest {
        show("$3=$1*$2", "@>$1=vsum(@2$1..@14$1)")
        onNodeWithText("A formula cannot be computed. Tap to see the formulas.").assertExists()

        onNodeWithText("Recalculate").performClick()

        onNodeWithText("Not recalculated: a formula cannot be computed.").assertExists()
        onNodeWithText("@14 is outside the table: the table has 3 rows.").assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithText("Save").performClick()
        assertEquals(rows, saved!!.rows)
    }

    @Test
    fun aColumnCanBeTotalledFromItsMenu() = runComposeUiTest {
        show()

        onNodeWithContentDescription("Column 2 actions").performClick()
        onNodeWithText("Sum at bottom").performClick()
        onNodeWithText("Recalculate").performClick()
        onNodeWithText("Save").performClick()

        assertEquals(listOf("@>$2=vsum(@I..@-I)"), saved!!.formulas)
        assertEquals("8", saved!!.rows.last()!![1])
    }

    @Test
    fun aComputedCellOpensItsFormulaInWords() = runComposeUiTest {
        show("$3=$1*$2")

        onNodeWithContentDescription("Row 3, column 3, computed").performClick()

        onNodeWithText("Edit formula").assertExists()
        onNodeWithText("c, every row = a*b").assertExists()
        onNodeWithText("Gives 6, 20").assertExists()
        onNodeWithText("Cancel").performClick()
        assertNull(saved)
    }
}
