package com.korgnote.common

import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.edit.Progress
import com.korgnote.common.org.edit.STATISTICS_COOKIE
import com.korgnote.common.org.edit.progressOf
import com.korgnote.common.org.edit.updateStatisticsCookies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StatisticsCookiesTest {

    private val keywords = OrgTodoConfig(todo = setOf("TODO", "DOING"), done = setOf("DONE", "CANCELED"))

    private fun update(text: String) = updateStatisticsCookies(text, keywords)

    @Test
    fun aHeadingCountsItsKeywordedSubHeadings() {
        val text = """
            * Project [/]
            ** DONE One
            ** TODO Two
            ** CANCELED Three
            ** Just notes
            *** DONE Deeper, not counted
        """.trimIndent()
        assertEquals(text.replace("[/]", "[2/3]"), update(text))
    }

    @Test
    fun aPercentCookieStaysAPercent() {
        val text = "* Project [10%]\n** DONE One\n** TODO Two\n** TODO Three\n"
        assertEquals(text.replace("[10%]", "[33%]"), update(text))
    }

    @Test
    fun aHeadingWithoutKeywordedChildrenCountsItsCheckboxes() {
        val text = """
            * Groceries [0/0]
            - [X] milk
            - [ ] eggs
              - [X] nested, not counted
            - plain item
            - [x] bread
        """.trimIndent()
        assertEquals(text.replace("[0/0]", "[2/3]"), update(text))
    }

    @Test
    fun aListItemCountsTheCheckboxesUnderIt() {
        val text = """
            * Trip
            - [ ] Pack [%]
              - [X] socks
              - [ ] boots
            - Shopping [/]
              - [X] map
        """.trimIndent()
        val expected = text.replace("Pack [%]", "Pack [50%]").replace("Shopping [/]", "Shopping [1/1]")
        assertEquals(expected, update(text))
    }

    @Test
    fun everythingElseIsLeftExactlyAsItWas() {
        val text = "* TODO [#A] Plan [1/1]  :work:\nSCHEDULED: <2026-10-27 Tue>\n** DONE Step\n\n\nTrailing   \n"
        assertSame(text, update(text), "an up-to-date cookie is not rewritten")
        val noCookie = "* TODO Plan\n** DONE Step\n"
        assertSame(noCookie, update(noCookie))
    }

    @Test
    fun timestampsPrioritiesAndCheckboxesAreNotCookies() {
        for (notACookie in listOf("[2026-10-27 Tue]", "[#A]", "[X]", "[ ]", "[-]", "[[id:x][y]]")) {
            assertNull(STATISTICS_COOKIE.find(notACookie), notACookie)
        }
    }

    @Test
    fun aCookieReadsAsProgress() {
        assertEquals(Progress(2, 5), STATISTICS_COOKIE.find("[2/5]")?.let(::progressOf))
        assertEquals(Progress(40, 100), STATISTICS_COOKIE.find("[40%]")?.let(::progressOf))
        assertNull(STATISTICS_COOKIE.find("[/]")?.let(::progressOf))
        assertNull(STATISTICS_COOKIE.find("[%]")?.let(::progressOf))
        assertEquals(0, Progress(0, 0).percent)
    }

    @Test
    fun givenWhatItWasOnlyTheCookiesOverTheChangeAreUpdated() {
        val before = "* Garden [0/1]\n** TODO Dig\n* Kitchen [5/9]\n** DONE Paint\n"
        // Added at the very end of the Garden's subtree, just where the Kitchen starts.
        val after = before.replace("** TODO Dig\n", "** TODO Dig\n** TODO Water\n")

        assertEquals(
            after.replace("[0/1]", "[0/2]"),
            updateStatisticsCookies(after, keywords, before = before),
        )
    }

    /** `:COOKIE_DATA: checkbox` counts the boxes even where keyworded sub-headings exist. */
    @Test
    fun cookieDataCanAskForCheckboxes() {
        val text = """
            * Trip [/]
            :PROPERTIES:
            :COOKIE_DATA: checkbox
            :END:
            - [X] Tickets
            - [ ] Hotel
            - [ ] Visa
            ** DONE Pack
        """.trimIndent()
        assertEquals(text.replace("[/]", "[1/3]"), update(text))
    }

    /** `:COOKIE_DATA: todo recursive` counts keyworded headings at every depth below. */
    @Test
    fun cookieDataCanCountKeywordsAllTheWayDown() {
        val text = """
            * Project [/]
            :PROPERTIES:
            :COOKIE_DATA: todo recursive
            :END:
            ** TODO One
            *** DONE One and a half
            ** DONE Two
        """.trimIndent()
        assertEquals(text.replace("[/]", "[2/3]"), update(text))
    }

    /** `:COOKIE_DATA: checkbox recursive` counts the boxes of nested lists too. */
    @Test
    fun cookieDataCanCountCheckboxesAllTheWayDown() {
        val text = """
            * Move [/]
            :PROPERTIES:
            :COOKIE_DATA: checkbox recursive
            :END:
            - [ ] Pack
              - [X] Books
              - [X] Kitchen
            - [ ] Clean
        """.trimIndent()
        assertEquals(text.replace("[/]", "[2/4]"), update(text))
    }
}
