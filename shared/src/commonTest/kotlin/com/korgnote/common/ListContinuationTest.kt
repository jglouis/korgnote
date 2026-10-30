package com.korgnote.common

import com.korgnote.common.org.complete.CompletionResult
import com.korgnote.common.org.edit.continueList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListContinuationTest {

    /** Enter typed at the `|` in [marked]; the result with its caret marked the same way. */
    private fun enter(marked: String): String? {
        val caret = marked.indexOf('|')
        val before = marked.removeRange(caret, caret + 1)
        val after = before.substring(0, caret) + "\n" + before.substring(caret)
        return continueList(before, caret, after)?.marked()
    }

    private fun CompletionResult.marked() = text.substring(0, caret) + "|" + text.substring(caret)

    @Test
    fun enterAfterAnItemStartsTheNextWithTheSameBullet() {
        assertEquals("* H\n- milk\n- |", enter("* H\n- milk|"))
        assertEquals("  + eggs\n  + |\nmore", enter("  + eggs|\nmore"))
        assertEquals("  * nested\n  * |", enter("  * nested|"))
    }

    /** In a source or example block the text is code, and a `- ` in YAML is not an Org list. */
    @Test
    fun enterInsideAVerbatimBlockIsLeftAsTyped() {
        assertNull(enter("#+BEGIN_SRC yaml\nitems:\n  - foo|\n#+END_SRC"))
        assertNull(enter("#+begin_example\n- foo|\n#+end_example"))
        assertNull(enter("#+BEGIN_SRC markdown\n- |\n#+END_SRC"), "nor is an empty one taken away")
    }

    @Test
    fun aListInAQuoteOrAfterABlockStillContinues() {
        assertEquals("#+BEGIN_QUOTE\n- one\n- |\n#+END_QUOTE", enter("#+BEGIN_QUOTE\n- one|\n#+END_QUOTE"))
        assertEquals("#+BEGIN_SRC sh\nls\n#+END_SRC\n- one\n- |", enter("#+BEGIN_SRC sh\nls\n#+END_SRC\n- one|"))
    }

    @Test
    fun anOrderedListCountsOn() {
        assertEquals("1. one\n2. |", enter("1. one|"))
        assertEquals("9) nine\n10) |", enter("9) nine|"))
    }

    @Test
    fun aCheckboxItemIsFollowedByAnEmptyBox() {
        assertEquals("- [X] done\n- [ ] |", enter("- [X] done|"))
    }

    @Test
    fun enterOnAnEmptyItemEndsTheList() {
        assertEquals("- milk\n|", enter("- milk\n- |"))
        assertEquals("- [ ] a\n|\nafter", enter("- [ ] a\n- [ ] |\nafter"))
    }

    @Test
    fun enterInsideAnItemSplitsIt() {
        assertEquals("- milk\n- |eggs", enter("- milk|eggs"))
    }

    @Test
    fun anythingElseIsLeftAsTyped() {
        assertNull(enter("* Heading|"), "a star at the start of a line is a heading")
        assertNull(enter("plain text|"))
        assertNull(enter("-5 degrees|"))
        assertNull(continueList("- a", 3, "- ab"), "not a newline")
        assertNull(continueList("- a", 3, "- a\n\n"), "a paste, not one Enter")
    }
}
