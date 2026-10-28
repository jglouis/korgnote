package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T17's acceptance: a day with two timed meetings, an all-day range and a deadline renders in all
 * four views, and each view leads somewhere when tapped.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarScreenTest {
    private val today = LocalDate(2026, 9, 23)
    private val now = LocalDateTime(2026, 9, 23, 11, 20)

    private val agenda = AgendaIndex.of(
        mapOf(
            "work.org" to """
                * Design review <2026-09-23 Wed 10:00-11:30> :work:design:
                * Lunch with Sam <2026-09-23 Wed 12:30-13:30>
                * TODO Submit the grant proposal
                DEADLINE: <2026-09-23 Wed>
                * DONE Water plants
                SCHEDULED: <2026-09-23 Wed>
                * Call with the CEO of [[https://www.heliovision.be/][HelioVision]] about *pricing* <2026-09-23 Wed 16:00>
            """.trimIndent(),
            "life.org" to "* Conference in Lyon\n<2026-09-22 Tue>--<2026-09-24 Thu>\n",
        ),
        AgendaOptions(today = today),
    )

    private val opened = mutableListOf<AgendaEntry>()

    private fun ComposeUiTest.show(mode: CalendarMode) = setContent {
        MaterialTheme {
            CalendarScreen(
                Modifier.size(390.dp, 800.dp),
                agenda = agenda,
                onOpenEntry = { opened += it },
                initialMode = mode,
                now = now,
            )
        }
    }

    private fun ComposeUiTest.assertShown(text: String) =
        assertEquals(true, onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(), "\"$text\" is shown")

    @Test
    fun theAgendaListShowsTheWholeDay() = runComposeUiTest {
        show(CalendarMode.Agenda)
        assertShown("Today")
        assertShown("10:00–11:30")
        assertShown("Design review")
        assertShown("Lunch with Sam")
        assertShown("Conference in Lyon")
        assertShown("Deadline")
        assertShown("Submit the grant proposal")

        onNodeWithText("Lunch with Sam").performClick()
        assertEquals(listOf("Lunch with Sam"), opened.map { it.title })
    }

    @Test
    fun theAgendaListShowsTagsAsChips() = runComposeUiTest {
        show(CalendarMode.Agenda)
        onNodeWithText("work").assertExists()
        onNodeWithText("design").assertExists()
        onAllNodesWithText(":work:", substring = true).assertCountEquals(0)
    }

    @Test
    fun linksAndEmphasisReadAsTheyDoInTheNote() = runComposeUiTest {
        for (mode in CalendarMode.entries) {
            show(mode)
            if (mode == CalendarMode.Day || mode == CalendarMode.Week) {
                assertShown("Call with the CEO of HelioVision about pricing")
            }
            if (mode == CalendarMode.Agenda) assertShown("Call with the CEO of HelioVision about pricing")
            onAllNodesWithText("[[", substring = true).assertCountEquals(0)
            onAllNodesWithText("https://", substring = true).assertCountEquals(0)
            onAllNodesWithText("*pricing*", substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun theDayViewPutsMeetingsOnTheGridAndTheRestAboveIt() = runComposeUiTest {
        show(CalendarMode.Day)
        assertShown("all day")
        assertShown("Conference in Lyon")
        assertShown("Deadline: TODO Submit the grant proposal")
        assertShown("Design review")
        assertShown("Lunch with Sam")
        onNodeWithTag("nowLine").assertExists()

        onNodeWithText("Design review").performClick()
        assertEquals(listOf("Design review"), opened.map { it.title })
    }

    @Test
    fun theWeekViewShowsTheDayInItsColumn() = runComposeUiTest {
        show(CalendarMode.Week)
        assertShown("21–27 Sep 2026")
        // The range is one bar: titled where it starts, blank where it continues.
        onAllNodesWithText("Conference in Lyon").assertCountEquals(1)
        assertShown("Design review")
        assertShown("Lunch with Sam")
        assertShown("Submit the grant proposal")
        onNodeWithTag("nowLine").assertExists()
    }

    @Test
    fun theMonthViewShowsChipsAndOpensTheDay() = runComposeUiTest {
        show(CalendarMode.Month)
        assertShown("September 2026")
        assertShown("Submit the grant proposal")
        assertShown("Conference in Lyon")

        onNodeWithTag("monthDay-2026-09-23").performClick()
        assertShown("Wed 23 Sep 2026")
        assertShown("all day")
    }

    @Test
    fun doneEntriesAreHiddenUntilAskedFor() = runComposeUiTest {
        show(CalendarMode.Agenda)
        onAllNodesWithText("Water plants", substring = true).assertCountEquals(0)

        onNodeWithContentDescription("Show done").performClick()
        assertShown("Water plants")

        onNodeWithContentDescription("Hide done").performClick()
        onAllNodesWithText("Water plants", substring = true).assertCountEquals(0)
    }

    @Test
    fun theHeaderStepsThroughDays() = runComposeUiTest {
        show(CalendarMode.Day)
        onNodeWithContentDescription("Next").performClick()
        assertShown("Thu 24 Sep 2026")
        onNodeWithText("Today").performClick()
        assertShown("Wed 23 Sep 2026")
    }

    @Test
    fun aSwipeTurnsToTheNextDayAndBack() = runComposeUiTest {
        show(CalendarMode.Day)

        onRoot().performTouchInput { swipeLeft(startX = 350f, endX = 40f) }
        assertShown("Thu 24 Sep 2026")

        onRoot().performTouchInput { swipeRight(startX = 40f, endX = 350f) }
        assertShown("Wed 23 Sep 2026")
    }

    /** The page follows the finger, so a slow drag let go short of halfway goes back where it was. */
    @Test
    fun aShortSlowDragSettlesBackOnTheSameDay() = runComposeUiTest {
        show(CalendarMode.Day)

        onRoot().performTouchInput {
            // On the calendar, which is narrower than the test window.
            down(Offset(300f, 400f))
            repeat(6) { moveBy(Offset(-10f, 0f), delayMillis = 100) }
            up()
        }

        assertShown("Wed 23 Sep 2026")
        onAllNodesWithText("Thu 24 Sep 2026").assertCountEquals(0)

        // The same slow drag, taken past halfway, turns the page: where it is let go is what counts.
        onRoot().performTouchInput {
            down(Offset(350f, 400f))
            repeat(25) { moveBy(Offset(-10f, 0f), delayMillis = 100) }
            up()
        }
        assertShown("Thu 24 Sep 2026")
    }

    /** A day of the week or month is too narrow for `TODO` and the title both: the title stays. */
    @Test
    fun theGridsShowTheTitleWithoutItsKeyword() = runComposeUiTest {
        for (mode in listOf(CalendarMode.Week, CalendarMode.Month)) {
            show(mode)
            onNodeWithText("Submit the grant proposal").assertExists()
            onAllNodesWithText("TODO", substring = true).assertCountEquals(0)
            // Still there for a screen reader.
            onNodeWithContentDescription("TODO Submit the grant proposal").assertExists()
        }
    }

    @Test
    fun theAgendaAndTheDayStillSpellTheKeywordOut() = runComposeUiTest {
        for (mode in listOf(CalendarMode.Agenda, CalendarMode.Day)) {
            show(mode)
            assertShown("TODO Submit the grant proposal")
        }
    }

    @Test
    fun eachOpenKeywordHasItsOwnColour() {
        val colors = KeywordColors(listOf("TODO", "EVENT", "DOING", "WAIT"))
        fun entry(todo: String?, done: Boolean = false) = agenda.entries.first().copy(todo = todo, done = done)

        val open = listOf("TODO", "EVENT", "DOING", "WAIT").map { colors.of(entry(it)) }
        assertEquals(4, open.toSet().size, "TODO and EVENT, which a hash of the name confuses, included")
        assertEquals(null, colors.of(entry(null)))
        assertEquals(OrgColors.done, colors.of(entry("DONE", done = true)))
    }
}
