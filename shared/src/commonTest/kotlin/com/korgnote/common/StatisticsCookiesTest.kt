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
}
