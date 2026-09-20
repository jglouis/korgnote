package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scoping styling to the visible range must change only *how much* is styled — never the text, and
 * never the layout metrics, or the document's height would shift as the window follows the viewport.
 */
class OrgViewportHighlightTest {

    private val heading = Color.Red

    private fun scoped(text: String, visible: IntRange?): AnnotatedString {
        val subject = OrgSyntaxHighlightTransformation(heading)
        subject.visibleChars = visible
        return subject.filter(AnnotatedString(text)).text
    }

    private val document = """
        #+TITLE: Notes

        * TODO Visible heading                                          :work:
        Some *bold* text with a [[https://example.com][link]].

        * TODO Far away heading                                         :home:
        More /italic/ text down here.
        - [ ] a checkbox
    """.trimIndent()

    @Test
    fun textIsPreservedWhateverTheWindow() {
        val windows = listOf(null, 0..10, 0..0, 40..80, 10_000..20_000, -50..5)
        for (window in windows) {
            assertEquals(document, scoped(document, window).text, "text changed for window $window")
        }
    }

    @Test
    fun scopingReducesSpanCount() {
        val full = scoped(document, null).spanStyles.size
        val narrow = scoped(document, 0..20).spanStyles.size
        assertTrue(narrow < full, "expected fewer spans when scoped (was $narrow vs $full)")
    }

    @Test
    fun visibleLinesAreStyledExactlyAsWhenUnscoped() {
        val full = scoped(document, null)
        val windowed = scoped(document, 0..60)
        // Every span the windowed pass produced inside the window must match the unscoped pass.
        val inWindow = windowed.spanStyles.filter { it.end <= 60 }
        for (span in inWindow) {
            assertTrue(
                full.spanStyles.any { it.start == span.start && it.end == span.end && it.item == span.item },
                "span $span is not present in the unscoped output"
            )
        }
    }

    @Test
    fun linesOutsideTheWindowAreLeftUnstyled() {
        // Nothing the editor applies changes metrics, so an unstyled line occupies exactly the
        // space a styled one would and the document does not shift as the window moves.
        val farHeadingStart = document.indexOf("* TODO Far away heading")

        assertTrue(
            scoped(document, 0..10).spanStyles.none {
                it.start <= farHeadingStart && it.end > farHeadingStart
            },
            "a heading outside the window should carry no spans",
        )
        assertTrue(
            scoped(document, null).spanStyles.any {
                it.start <= farHeadingStart && it.end > farHeadingStart
            },
            "the same heading should be styled when the window covers it",
        )
    }

    @Test
    fun blockStateSurvivesTheUnstyledRegion() {
        // The block opens far above the window: the visible body line can only be styled as block
        // content if state was advanced through the skipped lines.
        val filler = (1..40).joinToString("\n") { "filler line $it" }
        val text = "#+BEGIN_SRC kotlin\n$filler\nval visible = 1\n#+END_SRC\n"
        val bodyStart = text.indexOf("val visible = 1")

        val windowed = scoped(text, bodyStart..(bodyStart + 15))
        assertEquals(text, windowed.text)

        val bodySpans = windowed.spanStyles.filter { it.start <= bodyStart && it.end > bodyStart }
        assertTrue(
            bodySpans.any { it.item.background != Color.Unspecified },
            "a visible line inside a block must still be styled as block content"
        )
    }

    @Test
    fun drawerStateSurvivesTheUnstyledRegion() {
        val filler = (1..40).joinToString("\n") { ":KEY$it: value" }
        val text = ":PROPERTIES:\n$filler\n:LAST: value\n:END:\n"
        val lastStart = text.indexOf(":LAST: value")

        val windowed = scoped(text, lastStart..(lastStart + 10))
        assertEquals(text, windowed.text)
        assertTrue(
            windowed.spanStyles.any { it.start <= lastStart && it.end > lastStart },
            "a visible line inside a drawer must still be styled as drawer content"
        )
    }

    @Test
    fun editingWhileTheWindowMovesMatchesABuildFromScratch() {
        // Lines are cached with whether they were styled, so a window move reuses the lines that
        // did not cross its edge. Interleaving edits with moves is where that could go wrong.
        val subject = OrgSyntaxHighlightTransformation(heading)
        var text = document
        val windows = listOf(0..40, 30..90, 0..40, 60..150, 20..70)
        for ((step, window) in windows.withIndex()) {
            text = text.replace("Some *bold*", "Some *bold*" + "!".repeat(step))
            subject.visibleChars = window
            val reused = subject.filter(AnnotatedString(text)).text
            val fresh = scoped(text, window)
            assertEquals(fresh.text, reused.text, "text mismatch at step $step")
            assertEquals(fresh.spanStyles, reused.spanStyles, "span mismatch at step $step")
        }
    }

    @Test
    fun movingTheWindowMatchesABuildFromScratch() {
        // The cache is reused across window moves; each result must equal a fresh instance's.
        val subject = OrgSyntaxHighlightTransformation(heading)
        for (window in listOf(0..40, 40..120, 0..40, 100..200, null)) {
            subject.visibleChars = window
            val reused = subject.filter(AnnotatedString(document)).text
            val fresh = scoped(document, window)
            assertEquals(fresh.text, reused.text, "text mismatch for window $window")
            assertEquals(fresh.spanStyles, reused.spanStyles, "span mismatch for window $window")
        }
    }
}
