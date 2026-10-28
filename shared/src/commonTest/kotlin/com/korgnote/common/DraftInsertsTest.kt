package com.korgnote.common

import com.korgnote.common.org.edit.DraftInsert
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.fitsIn
import com.korgnote.common.org.edit.insertInto
import com.korgnote.common.org.edit.insertTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DraftInsertsTest {

    /** [what] inserted with the caret at `|` in [marked]; the result with its caret marked the same way. */
    private fun insert(marked: String, what: DraftInsert): String? {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        val result = insertInto(text, caret, what) ?: return null
        return result.text.substring(0, result.caret) + "|" + result.text.substring(result.caret)
    }

    @Test
    fun aLinkOpensAtTheCaret() {
        assertEquals("* Call\nsee [[|", insert("* Call\nsee |", DraftInsert.LINK))
    }

    @Test
    fun aTagGoesAfterTheHeadingsTitleOrItsOtherTags() {
        assertEquals("* Call mum :|\nBody", insert("* Call mum  \nBo|dy", DraftInsert.TAG))
        assertEquals("* Call mum   :family:|\n", insert("* Call| mum   :family:\n", DraftInsert.TAG))
    }

    @Test
    fun scheduleWritesThePlanningLineOrGoesToTheDateOnIt() {
        assertEquals("* Call\nSCHEDULED: |\nBody", insert("* Call|\nBody", DraftInsert.SCHEDULE))
        assertEquals("* Call\nSCHEDULED: <2026-10-26 Mon>|\n", insert("* |Call\nSCHEDULED: <2026-10-26 Mon>\n", DraftInsert.SCHEDULE))
        assertEquals("* Call\nSCHEDULED: | DEADLINE: <2026-10-30 Fri>\n", insert("* |Call\nDEADLINE: <2026-10-30 Fri>\n", DraftInsert.SCHEDULE))
    }

    @Test
    fun locationAddsTheDrawerOrThePropertyOrGoesToIt() {
        assertEquals(
            "* Lunch\nSCHEDULED: <2026-10-26 Mon>\n:PROPERTIES:\n:LOCATION: |\n:END:\nBody",
            insert("* Lunch|\nSCHEDULED: <2026-10-26 Mon>\nBody", DraftInsert.LOCATION),
        )
        assertEquals("* Lunch\n:PROPERTIES:\n:LOCATION: |\n:END:", insert("* Lunch|", DraftInsert.LOCATION))
        assertEquals(
            "* Lunch\n:PROPERTIES:\n:ID: x\n:LOCATION: |\n:END:\n",
            insert("* Lunch|\n:PROPERTIES:\n:ID: x\n:END:\n", DraftInsert.LOCATION),
        )
        assertEquals(
            "* Lunch\n:PROPERTIES:\n:LOCATION: Flagey|\n:END:\n",
            insert("* |Lunch\n:PROPERTIES:\n:LOCATION: Flagey\n:END:\n", DraftInsert.LOCATION),
        )
    }

    @Test
    fun aCheckboxTakesAnEmptyLineFollowsAnItemAndNeverSplitsTheHead() {
        assertEquals("* List\n- [ ] |\n", insert("* List\n|\n", DraftInsert.CHECKBOX))
        assertEquals("* List\n  - [ ] a\n  - [ ] |\nmore", insert("* List\n  - [ ] a|\nmore", DraftInsert.CHECKBOX))
        assertEquals("* List\nSCHEDULED: <x>\n- [ ] |", insert("* List|\nSCHEDULED: <x>\n", DraftInsert.CHECKBOX))
    }

    /** [grid] inserted with the caret at `|` in [marked], as the Table button does once it is saved. */
    private fun table(marked: String, grid: TableGrid): String {
        val caret = marked.indexOf('|')
        val result = insertTable(marked.removeRange(caret, caret + 1), caret, grid)
        return result.text.substring(0, result.caret) + "^" + result.text.substring(result.caret)
    }

    private val budget = TableGrid(listOf(listOf("Item", "Cost"), null, listOf("Rent", "100")), listOf("@>$2=vsum(@I..@-I)"))

    @Test
    fun aTableTakesAnEmptyLineOrGoesUnderTheCaretsLineAtItsIndentation() {
        assertEquals(
            "* Budget\n| Item | Cost |\n|------+------|\n| Rent |  100 |\n#+TBLFM: @>$2=vsum(@I..@-I)^\nAfter",
            table("* Budget\n|\nAfter", budget),
        )
        assertEquals(
            "* Budget\n  Costs:\n  | Item | Cost |\n  |------+------|\n  | Rent |  100 |\n  #+TBLFM: @>$2=vsum(@I..@-I)^",
            table("* Budget\n  Cos|ts:", budget),
        )
    }

    @Test
    fun aTableNeverSplitsTheHead() {
        assertEquals(
            "* Budget\nSCHEDULED: <x>\n|   |   |\n|---+---|\n|   |   |^",
            table("* Bud|get\nSCHEDULED: <x>\n", TableGrid.starter()),
        )
    }

    @Test
    fun theHeadingsOwnThingsNeedAHeading() {
        assertFalse(DraftInsert.TAG.fitsIn("#+TITLE: Notes\n"))
        assertNull(insert("#+TITLE: |Notes\n", DraftInsert.LOCATION))
    }
}
