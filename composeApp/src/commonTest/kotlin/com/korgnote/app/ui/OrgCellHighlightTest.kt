package com.korgnote.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A table's cell, highlighted in the grid editor as the element editor highlights it in a table line. */
class OrgCellHighlightTest {
    private fun colorAt(text: String, word: String) = OrgCellHighlightTransformation.filter(AnnotatedString(text)).text
        .spanStyles.lastOrNull { text.indexOf(word) in it.start until it.end }?.item?.color

    @Test
    fun linksAndTimestampsAreColoured() {
        assertEquals(OrgColors.link, colorAt("see [[file:a.org][A]]", "A]]"))
        assertEquals(OrgColors.timestamp, colorAt("due <2026-09-23 Wed>", "2026"))
    }

    /** The caret is placed by offset, so nothing may be added or removed — nor change the glyphs' widths. */
    @Test
    fun theTextAndItsMetricsAreLeftAlone() {
        val text = "*bold* /it/ =code= [[x][y]] <2026-09-23 Wed> \\alpha"
        val transformed = OrgCellHighlightTransformation.filter(AnnotatedString(text))
        assertEquals(text, transformed.text.text)
        assertEquals(OffsetMapping.Identity, transformed.offsetMapping)
        assertTrue(transformed.text.spanStyles.isNotEmpty())
        assertTrue(transformed.text.spanStyles.all { it.item.fontWeight == null && it.item.fontFamily == null && it.item.fontStyle == null })
    }

    @Test
    fun plainWordsStayPlain() {
        assertTrue(OrgCellHighlightTransformation.filter(AnnotatedString("12.50")).text.spanStyles.isEmpty())
    }
}
