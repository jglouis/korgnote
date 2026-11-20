package com.korgnote.common

import com.korgnote.common.org.INLINE_REACH
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.orgSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A note can hold any line, and the patterns that read headings, list items and timestamps meet
 * every one of them, in the index in the background and on the screen as it is drawn. Each of these
 * lines once took them from seconds to minutes, and with it the screen or the agenda; now each is
 * one pass along the line. The limit is generous: what is tested is that the time is not quadratic.
 */
class OrgSyntaxSpeedTest {

    private fun inTime(what: String, block: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        block()
        val took = start.elapsedNow()
        assertTrue(took < 3.seconds, "$what took $took")
    }

    @Test
    fun aDescriptionTermAfterALongRunOfSpaces() {
        val spaces = " ".repeat(20_000)
        inTime("a list item with a long run of spaces") { assertNull(OrgSyntax.descriptionTerm.find("x$spaces" + "x")) }
        inTime("the same, starting with spaces, as the editor reads it") { assertNull(OrgSyntax.descriptionTerm.find(" [ ]$spaces" + "x")) }
        inTime("a term after the spaces") { assertNotNull(OrgSyntax.descriptionTerm.find("$spaces term :: description")) }
    }

    @Test
    fun tagsAfterALongRunOfSpaces() {
        val spaces = " ".repeat(40_000)
        inTime("a heading with a long run of spaces") { assertNull(OrgSyntax.tags.find("Title$spaces" + "x")) }
        inTime("tags after the spaces") { assertEquals(":a:b:", OrgSyntax.tags.find("Title$spaces:a:b:")?.groupValues?.get(1)) }
    }

    @Test
    fun aLineOfStampsThatNeverClose() {
        val line = "<2024-01-01 ".repeat(20_000)
        inTime("a line of open stamps") { assertNull(OrgSyntax.timestamp.find(line)) }
        inTime("the same, one closed at the end") { assertEquals("<2024-01-01 Mon>", OrgSyntax.timestamp.findAll("$line<2024-01-01 Mon>").single().value) }
    }

    /**
     * Highlighting looks at each bracket of a line through a window ([INLINE_REACH]): on Android a
     * look at the whole line copied it, so a line of 20,000 brackets took half a minute to open.
     * What is far into a long line is still found.
     */
    @Test
    fun aLineOfBracketsIsHighlightedInOnePassAndWhatIsFarIntoItIsFound() {
        inTime("a line of open brackets") { orgSpans("${"[<a ".repeat(20_000)}\n") }
        val filler = "word ".repeat(3_000)
        val line = "$filler[[https://example.com][a link]] $filler<2026-09-25 Fri> *bold* [2/5]"
        val found = orgSpans("$line\n").map { it.kind to line.substring(it.range.first, it.range.last + 1) }
        assertTrue(OrgSpanKind.LinkDescription to "a link" in found, "$found")
        assertTrue(OrgSpanKind.Timestamp to "<2026-09-25 Fri>" in found, "$found")
        assertTrue(found.any { it.first == OrgSpanKind.Bold && "bold" in it.second }, "$found")
        assertTrue(OrgSpanKind.Statistics to "[2/5]" in found, "$found")
    }

    @Test
    fun whatThePatternsReadIsUnchanged() {
        val term = OrgSyntax.descriptionTerm.find("  Coffee beans  :: roasted")!!
        assertEquals(listOf("  ", "Coffee beans", "  ::", " "), term.groupValues.drop(1))
        assertEquals("a", OrgSyntax.descriptionTerm.find("a :: b")?.groupValues?.get(2))
        assertNull(OrgSyntax.descriptionTerm.find("no term here"))
        assertNull(OrgSyntax.descriptionTerm.find("a ::b"))

        assertEquals(":work:urgent:", OrgSyntax.tags.find("Write report   :work:urgent:")?.groupValues?.get(1))
        assertEquals(":a:", OrgSyntax.tags.find(" :a:")?.groupValues?.get(1))
        assertNull(OrgSyntax.tags.find("Ratio 3:4"))

        assertEquals(
            listOf("<2026-09-25 Fri 10:00 +1w>", "[2026-09-24 Thu]"),
            OrgSyntax.timestamp.findAll("Meet <2026-09-25 Fri 10:00 +1w> as noted [2026-09-24 Thu]").map { it.value }.toList(),
        )
        assertEquals(
            listOf("<2026-09-25 Fri>", "<2026-09-27 Sun>"),
            OrgSyntax.timestamp.findAll("<2026-09-25 Fri>--<2026-09-27 Sun>").map { it.value }.toList(),
        )
    }
}
