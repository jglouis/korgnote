package com.korgnote.common

import com.korgnote.common.org.edit.DraftLink
import com.korgnote.common.org.edit.LinkKind
import com.korgnote.common.org.edit.linkAt
import com.korgnote.common.org.edit.linkKindOf
import com.korgnote.common.org.edit.orgLinkOf
import com.korgnote.common.org.edit.pastedLink
import com.korgnote.common.org.edit.withLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkEditingTest {

    /** The link at `|` in [marked]. */
    private fun at(marked: String): DraftLink? {
        val caret = marked.indexOf('|')
        return linkAt(marked.removeRange(caret, caret + 1), caret)
    }

    /**
     * [pasted] put in place of the selection between the two `|` of [marked], or at the one `|`,
     * as the field reports it; what the editor then holds, its caret marked, or null when it keeps
     * what the field reported.
     */
    private fun paste(marked: String, pasted: String): String? {
        val first = marked.indexOf('|')
        val second = marked.indexOf('|', first + 1)
        val text = marked.replace("|", "")
        val end = if (second < 0) first else second - 1
        val after = text.replaceRange(first, end, pasted)
        val result = pastedLink(text, first, end, after) ?: return null
        return result.text.substring(0, result.caret) + "|" + result.text.substring(result.caret)
    }

    @Test
    fun theCaretInsideALinksBracketsIsOnIt() {
        assertEquals(DraftLink(4, 36, "https://example.com", "Example"), at("see [[ht|tps://example.com][Example]] now"))
        assertEquals(DraftLink(4, 36, "https://example.com", "Example"), at("see [[https://example.com][Example]|] now"))
        assertEquals(DraftLink(0, 12, "id:abc-1", ""), at("[[id:a|bc-1]]"))
    }

    @Test
    fun theCaretJustOutsideALinkIsNotOnIt() {
        assertNull(at("see |[[https://example.com][Example]] now"))
        assertNull(at("see [[https://example.com][Example]]| now"))
        assertNull(at("no link| here"))
    }

    @Test
    fun aBareAddressIsALinkWithNoDescription() {
        assertEquals(DraftLink(4, 23, "https://example.com", ""), at("see https://example.com|"))
        assertEquals(DraftLink(4, 23, "https://example.com", ""), at("see https://exa|mple.com and more"))
    }

    @Test
    fun onlyTheCaretsLineIsLookedAt() {
        assertEquals(DraftLink(9, 25, "id:2", "Second"), at("[[id:1]]\n[[id:2][Sec|ond]]"))
    }

    @Test
    fun aLinkShowsItsTargetWhenItHasNoOtherWords() {
        assertEquals("[[https://example.com]]", orgLinkOf(" https://example.com ", "  "))
        assertEquals("[[https://example.com]]", orgLinkOf("https://example.com", "https://example.com"))
        assertEquals("[[https://example.com][Example site]]", orgLinkOf("https://example.com", " Example\n site "))
    }

    @Test
    fun bracketsCannotEndALinkEarly() {
        assertEquals("[[https://example.com/a%5B1%5D][list (1)]]", orgLinkOf("https://example.com/a[1]", "list [1]"))
    }

    @Test
    fun withLinkReplacesTheRangeAndLeavesTheCaretAfterIt() {
        val result = withLink("see [[old]] now", 4, 11, "https://new.example", "New")
        assertEquals("see [[https://new.example][New]] now", result.text)
        assertEquals(32, result.caret)
    }

    @Test
    fun kindsAreTheWaysATargetIsChosen() {
        assertEquals(LinkKind.WEB, linkKindOf("https://example.com"))
        assertEquals(LinkKind.WEB, linkKindOf("mailto:me@example.com"))
        assertEquals(LinkKind.NOTE, linkKindOf("id:0b5c"))
        assertEquals(LinkKind.NOTE, linkKindOf("file:../notes/a.org::*Heading"))
        assertEquals(LinkKind.NOTE, linkKindOf("*A heading"))
        assertEquals(LinkKind.NOTE, linkKindOf("Some heading"))
        assertEquals(LinkKind.FILE, linkKindOf("file:~/Sync/invoice.pdf"))
        assertEquals(LinkKind.FILE, linkKindOf("~/Sync/photo.jpeg"))
        assertEquals(LinkKind.FILE, linkKindOf("file:./photo.jpeg"))
    }

    @Test
    fun aPastedAddressBecomesALink() {
        assertEquals("see [[https://example.com/a?b=1]]| now", paste("see | now", "https://example.com/a?b=1"))
    }

    @Test
    fun pastedOverWordsTheyBecomeWhatTheLinkShows() {
        assertEquals("read [[https://example.com][the docs]]| first", paste("read |the docs| first", "https://example.com"))
    }

    @Test
    fun spaceAroundThePasteIsKept() {
        assertEquals("see [[https://example.com]]| \n\nnext", paste("see|\nnext", " https://example.com \n"))
    }

    @Test
    fun selectedLinesDoNotBecomeADescription() {
        assertEquals("[[https://example.com]]|", paste("|one\ntwo|", "https://example.com"))
    }

    @Test
    fun typingAnAddressLeavesItBare() {
        assertNull(paste("see https://example.co|", "m"))
    }

    @Test
    fun otherPastesAreLeftAlone() {
        assertNull(paste("see |", "some words"))
        assertNull(paste("see |", "https://example.com and more"))
        assertNull(paste("see |", "id:abc"))
    }

    @Test
    fun anAddressStaysBareWhereOrgWantsItBare() {
        assertNull(paste(":PROPERTIES:\n:ROAM_REFS: |\n:END:", "https://example.com"))
        assertNull(paste("#+ROAM_REFS: |", "https://example.com"))
        assertNull(paste("#+BEGIN_SRC sh\ncurl |\n#+END_SRC", "https://example.com"))
        assertNull(paste("see [[|", "https://example.com"))
        assertNull(paste("see <|", "https://example.com"))
    }

    @Test
    fun afterABlockAPasteIsALinkAgain() {
        assertEquals(
            "#+BEGIN_SRC sh\nls\n#+END_SRC\n[[https://example.com]]|",
            paste("#+BEGIN_SRC sh\nls\n#+END_SRC\n|", "https://example.com"),
        )
    }
}
