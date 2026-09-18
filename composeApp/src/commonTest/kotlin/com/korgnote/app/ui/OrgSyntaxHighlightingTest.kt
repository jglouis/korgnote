package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrgSyntaxHighlightingTest {

    private val heading = Color.Red

    @Test
    fun preservesTextExactly() {
        val src = "* TODO Task\nSCHEDULED: <2026-09-18>\n#+TITLE: x\n- [ ] item\n\nplain DONE"
        val out = buildOrgHighlightedString(src, heading)
        assertEquals(src, out.text)
    }

    @Test
    fun headingIsColoredWithHeadingColorAndTodoToken() {
        val out = buildOrgHighlightedString("* TODO Task", heading)
        val styles = out.spanStyles
        assertTrue(styles.any { it.item.color == heading && it.start == 0 && it.end == "* TODO Task".length })
        assertTrue(styles.any { it.item.color == OrgColors.todo && it.start == 2 && it.end == 6 })
    }

    @Test
    fun planningAndKeywordLinesAreColored() {
        val src = "SCHEDULED: <2026-09-18>\n#+TITLE: x"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.spanStyles.any { it.item.color == OrgColors.planning && it.start == 0 })
        assertTrue(out.spanStyles.any { it.item.color == OrgColors.keyword && it.start == src.indexOf("#+") })
    }

    @Test
    fun checkboxMarkerIsHighlighted() {
        val src = "- [x] done item"
        val out = buildOrgHighlightedString(src, heading)
        val cb = out.spanStyles.single { it.item.color == OrgColors.checkbox }
        assertEquals(2, cb.start)
        assertEquals(5, cb.end)
        assertEquals(FontWeight.Bold, cb.item.fontWeight)
    }

    @Test
    fun todoInsideWordIsNotHighlighted() {
        val out = buildOrgHighlightedString("TODOS are not tokens", heading)
        assertTrue(out.spanStyles.none { it.item.color == OrgColors.todo })
    }
}
