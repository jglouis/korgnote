package com.korgnote.common

import com.korgnote.common.org.OrgSpan
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.orgSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `orgSpans` is the single definition of Org syntax (D5): it says what a stretch of text *is*, and
 * the client decides what colour that is.
 */
class OrgSpansTests {

    private fun spansOf(text: String) = orgSpans(text)

    private fun List<OrgSpan>.kindsAt(text: String, token: String): List<OrgSpanKind> {
        val at = text.indexOf(token)
        require(at >= 0) { "token <$token> not in text" }
        return filter { it.range.first <= at && it.range.last >= at }.map { it.kind }
    }

    private fun List<OrgSpan>.rangeOf(kind: OrgSpanKind): IntRange =
        first { it.kind == kind }.range

    @Test
    fun aHeadingIsClassifiedIntoItsParts() {
        val text = "* TODO [#A] Write it up                :work:home:"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.Heading in spans.kindsAt(text, "Write"))
        assertTrue(OrgSpanKind.Stars in spans.kindsAt(text, "*"))
        assertTrue(OrgSpanKind.Todo in spans.kindsAt(text, "TODO"))
        assertTrue(OrgSpanKind.Priority in spans.kindsAt(text, "[#A]"))
        assertTrue(OrgSpanKind.Tag in spans.kindsAt(text, ":work:"))
    }

    /**
     * Colour-carrying spans absorb the whitespace beside them: a style run holding only whitespace
     * measures zero width on Skia, which moves the caret. See `OrgWhitespaceRunTest`.
     */
    @Test
    fun headingPartsAbsorbTheWhitespaceBesideThem() {
        val text = "* TODO Task"
        val spans = spansOf(text)
        val todo = spans.rangeOf(OrgSpanKind.Todo)

        // "* TODO" — the span reaches back over the space before TODO rather than leaving it bare.
        assertEquals(1, todo.first, "the space before TODO must be inside the span")
        assertEquals(text.indexOf("TODO") + 4 - 1, todo.last)
    }

    @Test
    fun blockContentsAreBodyNotOrg() {
        val text = "#+BEGIN_SRC kotlin\n* not a heading\n#+END_SRC\n"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.BlockDelimiter in spans.kindsAt(text, "#+BEGIN_SRC"))
        assertTrue(OrgSpanKind.BlockBodyMonospace in spans.kindsAt(text, "* not a heading"))
        assertTrue(OrgSpanKind.Heading !in spans.kindsAt(text, "* not a heading"))
        assertTrue(OrgSpanKind.BlockDelimiter in spans.kindsAt(text, "#+END_SRC"))
    }

    @Test
    fun drawersAndTheirPropertiesAreClassified() {
        val text = ":PROPERTIES:\n:ID: abc\n:END:\n"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.Drawer in spans.kindsAt(text, ":PROPERTIES:"))
        assertTrue(OrgSpanKind.DrawerKey in spans.kindsAt(text, ":ID:"))
        assertTrue(OrgSpanKind.DrawerValue in spans.kindsAt(text, "abc"))
    }

    @Test
    fun planningKeywordsAndTimestamps() {
        val text = "SCHEDULED: <2026-09-21 Mon>"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.Planning in spans.kindsAt(text, "SCHEDULED"))
        assertTrue(OrgSpanKind.PlanningKeyword in spans.kindsAt(text, "SCHEDULED:"))
        assertTrue(OrgSpanKind.Timestamp in spans.kindsAt(text, "<2026"))
    }

    @Test
    fun linksAreSplitIntoMarkupTargetAndDescription() {
        val text = "see [[https://orgmode.org][Org]] here"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.LinkTarget in spans.kindsAt(text, "https://orgmode.org"))
        assertTrue(OrgSpanKind.LinkDescription in spans.kindsAt(text, "Org]"))
        assertTrue(OrgSpanKind.Markup in spans.kindsAt(text, "[["))
    }

    @Test
    fun bareUrlsAreUrls() {
        val text = "go to https://example.com now"
        assertTrue(OrgSpanKind.Url in spansOf(text).kindsAt(text, "https://example.com"))
    }

    @Test
    fun emphasisIsClassifiedAndItsMarkersAreMarkup() {
        val text = "a *bold* and /italic/ and =verb= word"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.Bold in spans.kindsAt(text, "bold"))
        assertTrue(OrgSpanKind.Italic in spans.kindsAt(text, "italic"))
        assertTrue(OrgSpanKind.Verbatim in spans.kindsAt(text, "verb"))
        assertTrue(OrgSpanKind.Markup in spans.kindsAt(text, "*bold"))
    }

    @Test
    fun emphasisRequiresWordBoundaries() {
        val text = "snake_case_name and 2*3*4"
        val spans = spansOf(text)
        assertTrue(spans.none { it.kind == OrgSpanKind.Underline })
        assertTrue(spans.none { it.kind == OrgSpanKind.Bold })
    }

    @Test
    fun nestedEmphasisReportsItsDepth() {
        val text = "*bold with /italic/ inside*"
        val spans = spansOf(text)
        val italic = spans.first { it.kind == OrgSpanKind.Italic }
        val bold = spans.first { it.kind == OrgSpanKind.Bold }

        assertEquals(0, bold.nesting)
        assertEquals(1, italic.nesting, "emphasis inside emphasis is one level deeper")
    }

    @Test
    fun listBulletsAndCheckboxes() {
        val text = "- [ ] a task\n- plain\n1. ordered\n"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.Bullet in spans.kindsAt(text, "- ["))
        assertTrue(OrgSpanKind.Checkbox in spans.kindsAt(text, "[ ]"))
        assertTrue(OrgSpanKind.Bullet in spans.kindsAt(text, "1."))
    }

    @Test
    fun tablesKeywordsCommentsAndRules() {
        val text = "| a | b |\n|---+---|\n#+TITLE: x\n# note\n-----\n"
        val spans = spansOf(text)

        assertTrue(OrgSpanKind.TablePipe in spans.kindsAt(text, "|"))
        assertTrue(OrgSpanKind.TableRule in spans.kindsAt(text, "|---"))
        assertTrue(OrgSpanKind.Keyword in spans.kindsAt(text, "#+TITLE"))
        assertTrue(OrgSpanKind.Comment in spans.kindsAt(text, "# note"))
        assertTrue(OrgSpanKind.HorizontalRule in spans.kindsAt(text, "-----"))
    }

    @Test
    fun footnotesAndStatistics() {
        val text = "a [fn:1] and [2/3] done"
        val spans = spansOf(text)
        assertTrue(OrgSpanKind.Footnote in spans.kindsAt(text, "[fn:1]"))
        assertTrue(OrgSpanKind.Statistics in spans.kindsAt(text, "[2/3]"))
    }

    /** Every span must point inside the text it came from. */
    @Test
    fun everySpanIsInBounds() {
        val text = "#+TITLE: t\n* TODO [#A] H :tag:\nSCHEDULED: <2026-09-21 Mon>\n" +
            ":PROPERTIES:\n:ID: 1\n:END:\n- [ ] i\n| a | b |\n#+BEGIN_SRC k\nx\n#+END_SRC\n" +
            "*bold* [[t][d]] https://e.com [fn:1] [1/2]\n"
        for (span in spansOf(text)) {
            assertTrue(span.range.first >= 0, "negative start: $span")
            assertTrue(span.range.last < text.length, "out of bounds: $span in ${text.length} chars")
        }
    }

    /** Restricting to a window must not change how the lines inside it are classified. */
    @Test
    fun windowingKeepsBlockStateAndClassification() {
        val text = "#+BEGIN_SRC kotlin\n* not a heading\n#+END_SRC\n"
        val bodyStart = text.indexOf("* not")
        val windowed = orgSpans(text, bodyStart until (bodyStart + 5))

        val kinds = windowed.filter { it.range.first <= bodyStart && it.range.last >= bodyStart }.map { it.kind }
        assertTrue(
            OrgSpanKind.BlockBodyMonospace in kinds,
            "state must carry across lines outside the window",
        )
        assertTrue(OrgSpanKind.Heading !in kinds)
    }
}
