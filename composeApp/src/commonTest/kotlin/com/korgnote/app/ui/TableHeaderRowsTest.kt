package com.korgnote.app.ui

import com.korgnote.common.org.edit.TableGrid
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which rows the table editor draws bold: the ones the reading view treats as the header. */
class TableHeaderRowsTest {

    private val head = listOf("Item", "Qty")
    private val row = listOf("Milk", "2")

    @Test
    fun theRowsAboveTheFirstRuleAreTheHeader() {
        assertEquals(0 until 1, tableHeaderRows(TableGrid(listOf(head, null, row))))
    }

    /** As `org-table` writes a table it boxed: a rule above the header as well as below it. */
    @Test
    fun aRuleAboveTheHeaderDoesNotHideIt() {
        assertEquals(1 until 2, tableHeaderRows(TableGrid(listOf(null, head, null, row))))
    }

    @Test
    fun withoutARuleThereIsNoHeader() {
        assertEquals(IntRange.EMPTY, tableHeaderRows(TableGrid(listOf(head, row))))
    }
}
