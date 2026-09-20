package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-line cache inside [OrgSyntaxHighlightTransformation] must be invisible: for any sequence
 * of edits, a reused instance has to produce exactly what a fresh one would for the same text.
 */
class OrgHighlightCacheTest {

    private val heading = Color.Red

    private fun transformation() = OrgSyntaxHighlightTransformation(heading)

    /**
     * Feeds [texts] through one transformation instance, asserting each result matches what a fresh
     * instance produces for the same text — reusing the cache must change nothing.
     */
    private fun assertMatchesFreshBuild(vararg texts: String) {
        val subject = transformation()
        for (text in texts) {
            val cached = subject.filter(AnnotatedString(text)).text
            val fresh = transformation().filter(AnnotatedString(text)).text
            assertEquals(fresh.text, cached.text, "text mismatch for:\n$text")
            assertEquals(fresh.spanStyles, cached.spanStyles, "span mismatch for:\n$text")
        }
    }

    private val document = """
        #+TITLE: Notes
        #+TODO: TODO NEXT | DONE

        * TODO Write the thing                                          :work:
        SCHEDULED: <2026-09-20 Sun 10:00>
        :PROPERTIES:
        :ID: abc-123
        :END:
        Some *bold* and /italic/ and ~code~ text with a [[https://example.com][link]].

        - [ ] first item
        - [X] second item

        #+BEGIN_SRC kotlin
        val x = 1  // * not a heading
        #+END_SRC

        | a | b |
        |---+---|
        | 1 | 2 |

        ** DONE Sub-heading
        Body text.
    """.trimIndent()

    @Test
    fun repeatedCallsOnIdenticalTextMatchFreshBuild() {
        assertMatchesFreshBuild(document, document, document)
    }

    @Test
    fun typingIntoALineMatchesFreshBuild() {
        val marker = "Body text."
        val edits = (0..5).map { i ->
            document.replace(marker, "Body text." + "x".repeat(i))
        }
        assertMatchesFreshBuild(*edits.toTypedArray())
    }

    @Test
    fun insertingAndDeletingLinesMatchesFreshBuild() {
        val withExtra = document.replace("Body text.", "Body text.\nA new line.\nAnother one.")
        val withOneRemoved = document.replace("- [ ] first item\n", "")
        assertMatchesFreshBuild(document, withExtra, document, withOneRemoved, document)
    }

    @Test
    fun openingABlockRestylesFollowingLines() {
        // Adding a #+BEGIN_SRC changes the block state every later line starts in, so cached lines
        // below the edit must not be reused.
        val opened = document.replace("Body text.", "#+BEGIN_SRC text\nBody text.")
        assertMatchesFreshBuild(document, opened, document)
    }

    @Test
    fun openingADrawerRestylesFollowingLines() {
        val opened = document.replace("Body text.", ":LOGBOOK:\nBody text.")
        assertMatchesFreshBuild(document, opened, document)
    }

    @Test
    fun changingTodoKeywordsRestylesWholeDocument() {
        val renamed = document.replace("#+TODO: TODO NEXT | DONE", "#+TODO: TODO LATER | CANCELLED")
        assertMatchesFreshBuild(document, renamed, document)
    }

    @Test
    fun editsAtDocumentEdgesMatchFreshBuild() {
        assertMatchesFreshBuild(
            document,
            "prefix line\n$document",
            "$document\ntrailing line",
            document.removePrefix("#+TITLE: Notes\n"),
        )
    }

    @Test
    fun degenerateDocumentsMatchFreshBuild() {
        assertMatchesFreshBuild("", "\n", "\n\n\n", "a", "a\n", "\na", document, "")
    }
}
