package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrgSyntaxHighlightingTest {

    private val heading = Color.Red

    private fun AnnotatedString.hasColorAt(color: Color, range: IntRange) =
        spanStyles.any { it.item.color == color && it.start == range.first && it.end == range.last + 1 }

    private fun AnnotatedString.colorRanges(color: Color) =
        spanStyles.filter { it.item.color == color }.map { it.start until it.end }

    /**
     * A span of [color] covers [range], possibly extending past it.
     *
     * Colour spans deliberately absorb the whitespace next to their token: a style run holding only
     * whitespace is measured as zero width by the Skia text backend. See [OrgWhitespaceRunTest].
     */
    private fun AnnotatedString.coversColorAt(color: Color, range: IntRange) =
        spanStyles.any { it.item.color == color && it.start <= range.first && it.end >= range.last + 1 }

    @Test
    fun preservesTextExactly() {
        val src = "* TODO Task\nSCHEDULED: <2026-09-18>\n#+TITLE: x\n- [ ] item\n\nplain DONE"
        val out = buildOrgHighlightedString(src, heading)
        assertEquals(src, out.text)
    }

    @Test
    fun preservesTextExactlyForAllConstructs() {
        val out = buildOrgHighlightedString(OrgSampleDocument, heading)
        assertEquals(OrgSampleDocument, out.text)
    }

    @Test
    fun headingIsColoredWithHeadingColorAndTodoToken() {
        val out = buildOrgHighlightedString("* TODO Task", heading)
        val styles = out.spanStyles
        assertTrue(styles.any { it.item.color == heading && it.start == 0 && it.end == "* TODO Task".length })
        assertTrue(out.coversColorAt(OrgColors.todo, 2 until 6))
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

    @Test
    fun unknownUppercaseWordInHeadingIsNotATodoKeyword() {
        val out = buildOrgHighlightedString("* NASA launches", heading)
        assertTrue(out.spanStyles.none { it.item.color == OrgColors.todo || it.item.color == OrgColors.done })
    }

    @Test
    fun customTodoKeywordsFromDocumentAreUsed() {
        val src = "#+TODO: TODO DOING | DONE CANCELED\n* DOING work\n* CANCELED nope"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.coversColorAt(OrgColors.todo, src.indexOf("DOING work") until src.indexOf("DOING work") + 5))
        assertTrue(out.coversColorAt(OrgColors.done, src.indexOf("CANCELED nope") until src.indexOf("CANCELED nope") + 8))
    }

    @Test
    fun headingTagsPriorityAndStatisticsAreHighlighted() {
        val src = "** TODO [#A] Write it [1/3] :Dev:Home:"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.hasColorAt(OrgColors.stars, 0..1))
        assertTrue(out.coversColorAt(OrgColors.priority, src.indexOf("[#A]") until src.indexOf("[#A]") + 4))
        assertTrue(out.hasColorAt(OrgColors.statistics, src.indexOf("[1/3]") until src.indexOf("[1/3]") + 5))
        assertTrue(out.coversColorAt(OrgColors.tag, src.indexOf(":Dev:Home:") until src.length))
    }

    @Test
    fun linksAreHighlightedAndBracketsDimmedInEditMode() {
        val src = "see [[https://orgmode.org][Org]] and [[id:123]]"
        val out = buildOrgHighlightedString(src, heading)
        assertEquals(src, out.text)
        val linkRanges = out.colorRanges(OrgColors.link)
        assertTrue(src.indexOf("https://orgmode.org") until src.indexOf("https://orgmode.org") + 19 in linkRanges)
        assertTrue(src.indexOf("Org]]") until src.indexOf("Org]]") + 3 in linkRanges)
        assertTrue(src.indexOf("id:123") until src.indexOf("id:123") + 6 in linkRanges)
        assertTrue(out.colorRanges(OrgColors.markup).isNotEmpty())
    }

    @Test
    fun linksShowDescriptionOnlyAndAreClickableInViewMode() {
        val out = buildOrgInlineString("see [[https://orgmode.org][Org]] and [[file:notes.org][notes]] and [[Plain]]")
        assertEquals("see Org and notes and Plain", out.text)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        assertEquals("https://orgmode.org", (links.single().item as LinkAnnotation.Url).url)
        assertEquals(4, links.single().start)
        assertEquals(7, links.single().end)
    }

    @Test
    fun bareUrlsAreLinks() {
        val src = "go to https://example.com/a?b=1 now"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.hasColorAt(OrgColors.link, 6 until 6 + "https://example.com/a?b=1".length))
    }

    @Test
    fun emphasisIsStyledAndMarkersHiddenInViewMode() {
        val src = "*bold* /italic/ _under_ +gone+ =verb= ~code~"
        val edit = buildOrgHighlightedString(src, heading)
        assertEquals(src, edit.text)
        assertTrue(edit.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 1 && it.end == 5 })
        assertTrue(edit.spanStyles.any { it.item.fontStyle == FontStyle.Italic && edit.text.substring(it.start, it.end) == "italic" })
        assertTrue(edit.spanStyles.any { it.item.textDecoration == TextDecoration.Underline && edit.text.substring(it.start, it.end) == "under" })
        assertTrue(edit.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough && edit.text.substring(it.start, it.end) == "gone" })
        assertTrue(edit.spanStyles.any { it.item.color == OrgColors.verbatim && edit.text.substring(it.start, it.end) == "verb" })
        assertTrue(edit.spanStyles.any { it.item.color == OrgColors.code && edit.text.substring(it.start, it.end) == "code" })

        val view = buildOrgInlineString(src)
        assertEquals("bold italic under gone verb code", view.text)
        assertTrue(view.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 4 })
    }

    @Test
    fun emphasisRequiresWordBoundaries() {
        val out = buildOrgInlineString("snake_case_name and 2*3*4 and a/b/c")
        assertEquals("snake_case_name and 2*3*4 and a/b/c", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun timestampsAreHighlighted() {
        val src = "CLOSED: [2025-04-26 Sat 11:52] SCHEDULED: <2025-04-26 Sat 11:00>"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.hasColorAt(OrgColors.planning, 0 until src.length))
        assertTrue(out.hasColorAt(OrgColors.timestamp, 8 until 8 + "[2025-04-26 Sat 11:52]".length))
        assertTrue(out.hasColorAt(OrgColors.timestamp, src.indexOf('<') until src.length))
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && out.text.substring(it.start, it.end) == "SCHEDULED:" })
    }

    @Test
    fun tableLinesAreMonospaceWithColoredPipes() {
        val src = "| a | b |\n|---+---|"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && it.start == 0 && it.end == 9 })
        assertTrue(out.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && it.start == 10 && it.end == src.length })
        assertEquals(3, out.colorRanges(OrgColors.table).size)
        assertTrue(out.hasColorAt(OrgColors.tableRule, 10 until src.length))
    }

    @Test
    fun sourceBlocksAreMonospaceAndDelimitersDimmed() {
        val src = "#+BEGIN_SRC bash\necho *not bold*\n#+end_src\nafter *bold*"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.hasColorAt(OrgColors.blockDelimiter, 0 until 16))
        val body = out.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("echo *not bold*", out.text.substring(body.start, body.end))
        assertFalse(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && out.text.substring(it.start, it.end) == "not bold" })
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && out.text.substring(it.start, it.end) == "bold" })
        assertTrue(out.hasColorAt(OrgColors.blockDelimiter, src.indexOf("#+end_src") until src.indexOf("#+end_src") + 9))
    }

    @Test
    fun drawersAndPropertiesAreDimmed() {
        val src = ":PROPERTIES:\n:ID: 42\n:END:\nafter"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.hasColorAt(OrgColors.drawer, 0 until 12))
        assertTrue(out.hasColorAt(OrgColors.drawer, 13 until 20))
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && out.text.substring(it.start, it.end) == ":ID:" })
        assertTrue(out.hasColorAt(OrgColors.drawer, 21 until 26))
        assertTrue(out.spanStyles.none { it.start >= 27 })
    }

    @Test
    fun locationPropertyIsNotLinkedInEditMode() {
        val src = ":PROPERTIES:\n:LOCATION: 1600 Amphitheatre Pkwy\n:END:"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
    }

    @Test
    fun locationPropertyIsLinkedToGoogleMapsInViewMode() {
        val src = ":PROPERTIES:\n:LOCATION: 1600 Amphitheatre Pkwy, Mountain View\n:END:"
        val out = buildOrgHighlightedString(src, heading, viewMode = true)
        assertEquals(src, out.text)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(1, links.size)
        val url = (links.single().item as LinkAnnotation.Url).url
        assertEquals(googleMapsUrl("1600 Amphitheatre Pkwy, Mountain View"), url)
        assertTrue(url.startsWith("https://www.google.com/maps/search/?api=1&query="))
        val valueStart = src.indexOf(" 1600")
        val valueEnd = src.indexOf("\n:END:")
        assertEquals(valueStart, links.single().start)
        assertEquals(valueEnd, links.single().end)
    }

    @Test
    fun otherDrawerPropertiesAreNotLinkedInViewMode() {
        val src = ":PROPERTIES:\n:ID: 42\n:END:"
        val out = buildOrgHighlightedString(src, heading, viewMode = true)
        assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
    }

    @Test
    fun commentsFootnotesAndRulesAreHighlighted() {
        val src = "# note\nsee[fn:1]\n-----"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.spanStyles.any { it.item.color == OrgColors.comment && it.item.fontStyle == FontStyle.Italic && it.start == 0 && it.end == 6 })
        assertTrue(out.hasColorAt(OrgColors.footnote, 10 until 16))
        assertTrue(out.hasColorAt(OrgColors.horizontalRule, 17 until src.length))
    }

    @Test
    fun listBulletsAndDescriptionTermsAreHighlighted() {
        val src = "  + term :: definition\n1. numbered"
        val out = buildOrgHighlightedString(src, heading)
        assertTrue(out.coversColorAt(OrgColors.bullet, 2..2))
        assertTrue(out.coversColorAt(OrgColors.descriptionTerm, 4 until 8))
        assertTrue(out.coversColorAt(OrgColors.bullet, src.indexOf("1.") until src.indexOf("1.") + 2))
    }
}
