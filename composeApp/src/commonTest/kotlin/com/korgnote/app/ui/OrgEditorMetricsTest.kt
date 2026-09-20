package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Styling applied inside the editor must never change text metrics.
 *
 * A metric-changing span makes the text field mis-place its caret on the keystroke that first
 * applies it: typing the space that turns a line into a heading changes the line's metrics, the
 * caret fails to advance, and because a space has no glyph nothing appears to happen until the next
 * character is typed. Bold is included — it selects a different font face. Verified on desktop, iOS
 * and web; Android uses a different text backend and is unaffected.
 *
 * Reading mode does not go through [OrgSyntaxHighlightTransformation] and keeps the full styling.
 */
class OrgEditorMetricsTest {

    private val heading = Color.Red

    private val samples = listOf(
        "* TODO Buy milk",
        "* ",
        "* TODO ",
        "** DONE Nested                      :work:",
        "* TODO [#A] Prioritised [1/3]",
        "- [ ] a checkbox",
        "  + term :: definition",
        "SCHEDULED: <2026-09-20 Sun>",
        "#+TITLE: Notes",
        "| a | b |",
        "Some *bold* and /italic/ and ~code~ and =verbatim= text",
        ":PROPERTIES:",
        "#+BEGIN_SRC kotlin",
        OrgSampleDocument,
    )

    private fun edited(text: String) =
        OrgSyntaxHighlightTransformation(heading).filter(AnnotatedString(text)).text

    @Test
    fun editorStylingNeverChangesTextMetrics() {
        for (sample in samples) {
            for (span in edited(sample).spanStyles) {
                val where = "span ${span.start}..${span.end} of \"$sample\""
                assertEquals(TextUnit.Unspecified, span.item.fontSize, "fontSize set on $where")
                assertNull(span.item.fontWeight, "fontWeight set on $where")
                assertNull(span.item.fontStyle, "fontStyle set on $where")
                assertNull(span.item.fontFamily, "fontFamily set on $where")
                assertEquals(TextUnit.Unspecified, span.item.letterSpacing, "letterSpacing set on $where")
            }
        }
    }

    @Test
    fun editorStylingPreservesTheTextExactly() {
        for (sample in samples) {
            assertEquals(sample, edited(sample).text)
        }
    }

    @Test
    fun editorStylingStillColoursThings() {
        val styled = edited("* TODO Buy milk")
        assertTrue(
            styled.spanStyles.any { it.item.color == OrgColors.todo },
            "the TODO keyword should still be coloured",
        )
        assertTrue(
            styled.spanStyles.any { it.item.color == heading },
            "the heading should still be coloured",
        )
    }

    @Test
    fun editorStylingKeepsAttributesThatCannotMoveGlyphs() {
        // Backgrounds and decorations are painted over the same glyphs, so they survive the
        // projection: blocks stay shaded and links stay underlined while editing.
        val block = edited("#+BEGIN_SRC kotlin\nval x = 1\n#+END_SRC")
        assertTrue(
            block.spanStyles.any { it.item.background != Color.Unspecified },
            "block shading should survive",
        )
        val link = edited("see [[https://orgmode.org][Org]]")
        assertTrue(
            link.spanStyles.any { it.item.textDecoration != null },
            "link underlining should survive",
        )
    }

    @Test
    fun readingModeKeepsItsFullStyling() {
        val read = buildOrgHighlightedString("* TODO Buy milk", heading, viewMode = true)
        assertTrue(
            read.spanStyles.any { it.item.fontSize != TextUnit.Unspecified },
            "reading mode should still scale headings",
        )
    }
}
