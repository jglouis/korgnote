package com.korgnote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.pinch
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
import kotlin.test.assertTrue

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

    /**
     * The index is built when the notes change, and nothing need change overnight: an app left open
     * past midnight still holds yesterday's. What is overdue must follow the clock, not the index.
     */
    @Test
    fun whatIsOverdueFollowsTheClockPastMidnight() = runComposeUiTest {
        val builtYesterday = AgendaIndex.of(
            mapOf("life.org" to "* TODO Call the bank\nSCHEDULED: <2026-09-20 Sun>\n"),
            AgendaOptions(today = LocalDate(2026, 9, 22)),
        )
        setContent {
            MaterialTheme {
                CalendarScreen(
                    Modifier.size(390.dp, 800.dp),
                    agenda = builtYesterday,
                    initialMode = CalendarMode.Agenda,
                    now = now,
                )
            }
        }
        assertShown("Call the bank")
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

    /** Seven days do not fit across a phone: they scroll sideways, from today, and then on to the next week. */
    @Test
    fun theWeekScrollsSidewaysBeforeItTurns() = runComposeUiTest {
        show(CalendarMode.Week)
        onNodeWithTag("weekDay-2026-09-23").assertIsDisplayed()
        onNodeWithTag("weekDay-2026-09-21").assertIsNotDisplayed()

        // A long drag right brings Monday into view and stops there: the week stays where it is,
        // though the finger went further than half the width.
        onRoot().performTouchInput {
            down(Offset(100f, 500f))
            repeat(27) { moveBy(Offset(10f, 0f), delayMillis = 50) }
            up()
        }
        onNodeWithTag("weekDay-2026-09-21").assertIsDisplayed()
        assertShown("21–27 Sep 2026")

        // From its start, the next swipe turns to the week before.
        onRoot().performTouchInput { swipeRight(startX = 60f, endX = 380f) }
        assertShown("14–20 Sep 2026")
    }

    private fun ComposeUiTest.heightOf(text: String) = onNodeWithText(text).fetchSemanticsNode().size.height.toFloat()

    /** Two fingers pulled apart up and down stretch the hours, and pushed together squeeze them. */
    @Test
    fun aPinchStretchesAndSqueezesTheHours() = runComposeUiTest {
        show(CalendarMode.Day)
        val before = heightOf("Design review")

        onNodeWithTag("hourGrid").performTouchInput {
            pinch(Offset(150f, centerY - 50f), Offset(150f, centerY - 100f), Offset(250f, centerY + 50f), Offset(250f, centerY + 100f))
        }
        val stretched = heightOf("Design review")
        assertTrue(stretched > before * 1.5f, "stretched from $before to $stretched")
        assertShown("Wed 23 Sep 2026")

        onNodeWithTag("hourGrid").performTouchInput {
            pinch(Offset(150f, centerY - 100f), Offset(150f, centerY - 25f), Offset(250f, centerY + 100f), Offset(250f, centerY + 25f))
        }
        val squeezed = heightOf("Design review")
        assertTrue(squeezed < stretched / 2f, "squeezed from $stretched to $squeezed")
        assertShown("Wed 23 Sep 2026")
    }

    /** What is between the fingers stays between them. */
    @Test
    fun aPinchKeepsWhatIsBetweenTheFingersInPlace() = runComposeUiTest {
        show(CalendarMode.Day)
        val grid = onNodeWithTag("hourGrid").fetchSemanticsNode().positionInRoot
        fun middleOfTheReview() = onNodeWithText("Design review").fetchSemanticsNode().let { it.positionInRoot.y + it.size.height / 2f }
        val before = middleOfTheReview()

        // Fingers either side of the review, in the grid's own coordinates.
        val y = before - grid.y
        onNodeWithTag("hourGrid").performTouchInput {
            pinch(Offset(150f, y - 30f), Offset(150f, y - 60f), Offset(250f, y + 30f), Offset(250f, y + 60f))
        }
        assertEquals(before, middleOfTheReview(), 6f)
    }

    /** The hours stay as pinched on the next day, and in the week. */
    @Test
    fun thePinchIsKeptFromDayToWeek() = runComposeUiTest {
        show(CalendarMode.Day)
        onNodeWithTag("hourGrid").performTouchInput {
            pinch(Offset(150f, centerY - 50f), Offset(150f, centerY - 100f), Offset(250f, centerY + 50f), Offset(250f, centerY + 100f))
        }
        val stretched = heightOf("Design review")

        onNodeWithText("Week").performClick()
        assertEquals(stretched, heightOf("Design review"), 1f)
    }

    /** The same the other way: past Sunday, the next swipe turns to the week after. */
    @Test
    fun theWeekTurnsForwardFromItsEnd() = runComposeUiTest {
        show(CalendarMode.Week)
        onRoot().performTouchInput { swipeLeft(startX = 380f, endX = 60f) }
        onNodeWithTag("weekDay-2026-09-27").assertIsDisplayed()
        assertShown("21–27 Sep 2026")

        onRoot().performTouchInput { swipeLeft(startX = 380f, endX = 60f) }
        assertShown("28 Sep – 4 Oct 2026")
    }

    @Test
    fun aDayFullOfUntimedEntriesLeavesTheHoursOnScreen() = runComposeUiTest {
        val busy = AgendaIndex.of(
            mapOf("todo.org" to (1..20).joinToString("\n") { "* TODO Chore $it.\nSCHEDULED: <2026-09-23 Wed>" }),
            AgendaOptions(today = today),
        )
        setContent {
            MaterialTheme {
                CalendarScreen(Modifier.size(390.dp, 800.dp), agenda = busy, initialMode = CalendarMode.Day, now = now)
            }
        }
        onNodeWithText("TODO Chore 1.", substring = true).assertIsDisplayed()
        onNodeWithText("TODO Chore 20.", substring = true).assertIsNotDisplayed()
        onNodeWithText("12:00").assertIsDisplayed()

        onNodeWithTag("allDay").performTouchInput { swipeUp() }
        onNodeWithText("TODO Chore 20.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theMonthShowsOnlyItsOwnWeeks() = runComposeUiTest {
        show(CalendarMode.Month)
        onNodeWithTag("monthDay-2026-08-31").assertExists()
        onNodeWithTag("monthDay-2026-10-04").assertExists()
        onNodeWithTag("monthDay-2026-10-05").assertDoesNotExist()
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
