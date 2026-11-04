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

    /** Enter on a box with nothing after it, not even a space, ends the list: it is an empty item. */
    @Test
    fun enterOnAnEmptyBoxWithNoTrailingSpaceEndsTheList() {
        assertEquals("- one\n|", enter("- one\n- [ ]|"))
        assertEquals("- one\n|", enter("- one\n- [ ] |"))
    }

    /** A `[@5]` makes the item 5, whatever it writes, so the next one is 6, as reading mode shows it. */
    @Test
    fun theNextNumberCountsOnFromACounter() {
        assertEquals("3. [@5] x\n6. |", enter("3. [@5] x|"))
        assertEquals("3) [@5] [ ] x\n6) [ ] |", enter("3) [@5] [ ] x|"))
    }

    /** A counter means nothing in a bulleted list, and is no text either. */
    @Test
    fun aCounterAloneIsAnEmptyItem() {
        assertEquals("|", enter("3. [@5] |"))
        assertEquals("- [@5] x\n- |", enter("- [@5] x|"))
    }

    /** Only a block's own end closes it, as the parser has it; this is still code. */
    @Test
    fun anotherBlocksEndDoesNotEndAVerbatimBlock() {
        assertNull(enter("#+BEGIN_SRC yaml\n#+END_QUOTE\n- foo|\n#+END_SRC"))
    }
}
