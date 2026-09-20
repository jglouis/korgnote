package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.korgnote.common.org.OrgSyntax

/**
 * The highlighter must never produce a style run consisting only of whitespace.
 *
 * Skia's text backend — desktop, iOS and web, but not Android — measures such a run as zero width,
 * which made the spaces around a TODO keyword disappear while typing even though the text was
 * correct underneath. Whitespace therefore has to travel with an adjacent token rather than sit in
 * a run of its own.
 */
class OrgWhitespaceRunTest {

    private val heading = Color.Red

    /**
     * Splits [text] into maximal stretches covered by an identical set of spans — the runs the text
     * backend will shape — and returns those that are entirely whitespace.
     *
     * Runs containing a newline are ignored: line breaks legitimately sit in unstyled runs.
     */
    private fun AnnotatedString.whitespaceOnlyRuns(): List<String> {
        val boundaries = mutableSetOf(0, text.length)
        spanStyles.forEach { boundaries.add(it.start); boundaries.add(it.end) }
        return boundaries.sorted().zipWithNext()
            .map { (start, end) -> text.substring(start, end) }
            .filter { it.isNotEmpty() && '\n' !in it && it.isBlank() }
    }

    private fun highlighted(text: String) = buildOrgHighlightedString(text, heading)

    @Test
    fun headingsHaveNoWhitespaceOnlyRuns() {
        val lines = listOf(
            "* TODO Buy milk",
            "* DONE Something done",
            "** TODO Nested task",
            "* TODO [#A] Prioritised",
            "* TODO Tagged task                    :work:home:",
            "* Plain heading with no keyword",
            "*   TODO   Extra   spacing",
            "* ",
            "* TODO ",
        )
        for (line in lines) {
            val runs = highlighted(line).whitespaceOnlyRuns()
            assertTrue(runs.isEmpty(), "whitespace-only run(s) $runs in: \"$line\"")
        }
    }

    @Test
    fun listItemsHaveNoWhitespaceOnlyRuns() {
        val lines = listOf(
            "- [ ] a checkbox",
            "- [x] a done checkbox",
            "+ a plain bullet",
            "  - an indented bullet",
            "1. an ordered item",
        )
        for (line in lines) {
            val runs = highlighted(line).whitespaceOnlyRuns()
            assertTrue(runs.isEmpty(), "whitespace-only run(s) $runs in: \"$line\"")
        }
    }

    /**
     * Guards the fixed constructs at document scale.
     *
     * Only heading and list lines are checked: other constructs can still produce whitespace-only
     * runs — notably the gap between two adjacent inline markup spans (`*bold* /italic/`) and the
     * leading whitespace of a planning line. Those were not part of the reported problem and are
     * left for when they are seen; the fix pattern is the same.
     */
    @Test
    fun headingAndListLinesOfTheSampleDocumentAreClean() {
        val lines = OrgSampleDocument.lines().filter {
            OrgSyntax.heading.containsMatchIn(it) || OrgSyntax.listItem.containsMatchIn(it)
        }
        assertTrue(lines.isNotEmpty(), "the sample document should contain headings and list items")
        for (line in lines) {
            val runs = highlighted(line).whitespaceOnlyRuns()
            assertTrue(runs.isEmpty(), "whitespace-only run(s) $runs in: \"$line\"")
        }
    }

    @Test
    fun movingWhitespaceLeavesTheTextUntouched() {
        // The whole point is that only span boundaries moved, never a character.
        val lines = listOf(
            "* TODO Buy milk",
            "* TODO [#A] Tagged                      :work:",
            "*   TODO   Extra   spacing   ",
            "- [ ] a checkbox",
            OrgSampleDocument,
        )
        for (line in lines) {
            assertEquals(line, highlighted(line).text)
        }
    }
}
