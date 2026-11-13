package com.korgnote.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.LastScreen
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.app.ui.CalendarMode
import com.korgnote.app.widgets.CalendarTarget
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The app opens where it was left: on the same tab, the calendar in the same view, and a note
 * still where it was read when another tab was looked at meanwhile.
 */
@OptIn(ExperimentalTestApi::class)
class AppResumeTest {
    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    private val settings = InMemorySettingsRepository()

    // In the agenda's second page, which starts a week from today.
    private val nextWeek = today.plus(8, DateTimeUnit.DAY)
    private val secondPage = today.plus(7, DateTimeUnit.DAY)
    private val longNote = (1..60).joinToString("") { "* Heading $it\nSome text under heading $it.\n" }

    private fun start() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("long.org" to longNote, "plans.org" to "* Dentist <$nextWeek 10:00>\n"))
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { settings }
                    single { NotesSourceChoice(get(), DocumentStore(InMemoryReplicaStorage())) }
                    viewModel { NotesViewModel(SyncEngine(store, api), store) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun theAppOpensOnTheTabAndCalendarViewItWasLeftOn() = runComposeUiTest {
        settings.setLastScreen(LastScreen(tab = "CALENDAR", calendarMode = "Week", calendarDate = "$today", savedOn = "$today"))
        start()
        setContent { App() }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Week").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Week").assertIsSelected()
    }

    @Test
    fun theCalendarTabGoesBackToTheViewItWasLeftIn() = runComposeUiTest {
        start()
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Heading 1").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Calendar").performClick()
        onNodeWithText("Month").performClick()
        onNodeWithText("File").performClick()
        onNodeWithText("Calendar").performClick()

        waitForIdle()
        onNodeWithText("Month").assertIsSelected()
        assertEquals("Month", settings.getLastScreen().calendarMode, "and kept for the next launch")
        assertEquals("CALENDAR", settings.getLastScreen().tab)
    }

    private fun ComposeUiTest.openTheCalendarPagedAWeekOn() {
        start()
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Heading 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Calendar").performClick()
        onNodeWithContentDescription("Next").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.getLastScreen().calendarDate == "$secondPage" }
    }

    @Test
    fun theAgendaOpensOnTodayHoweverFarItWasPaged() = runComposeUiTest {
        openTheCalendarPagedAWeekOn()

        onNodeWithText("File").performClick()
        onNodeWithText("Calendar").performClick()

        waitForIdle()
        onNodeWithText("Agenda").assertIsSelected()
        assertEquals("$today", settings.getLastScreen().calendarDate)
    }

    @Test
    fun theAgendaIsStillWhereANoteWasOpenedFromIt() = runComposeUiTest {
        openTheCalendarPagedAWeekOn()

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dentist").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Dentist").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.getLastScreen().tab == "FILE" }
        onNodeWithText("Calendar").performClick()

        waitForIdle()
        assertEquals("$secondPage", settings.getLastScreen().calendarDate, "back where the note was opened from")
        onNodeWithText("Dentist").assertIsDisplayed()

        // Gone back to once: opened from its tab after that, it is on today again.
        onNodeWithText("File").performClick()
        onNodeWithText("Calendar").performClick()
        waitForIdle()
        assertEquals("$today", settings.getLastScreen().calendarDate)
    }

    @Test
    fun anotherTabLookedAtSinceTheNoteWasOpenedPutsTheAgendaBackOnToday() = runComposeUiTest {
        openTheCalendarPagedAWeekOn()

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dentist").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Dentist").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.getLastScreen().tab == "FILE" }
        onNodeWithText("Settings").performClick()
        onNodeWithText("Calendar").performClick()

        waitForIdle()
        assertEquals("$today", settings.getLastScreen().calendarDate)
    }

    @Test
    fun theMonthIsStillWhereItWasPagedTo() = runComposeUiTest {
        start()
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Heading 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Calendar").performClick()
        onNodeWithText("Month").performClick()
        onNodeWithContentDescription("Next").performClick()
        waitUntil(timeoutMillis = 5_000) { settings.getLastScreen().calendarDate != "$today" }
        val pagedTo = settings.getLastScreen().calendarDate

        onNodeWithText("File").performClick()
        onNodeWithText("Calendar").performClick()

        waitForIdle()
        onNodeWithText("Month").assertIsSelected()
        assertEquals(pagedTo, settings.getLastScreen().calendarDate)
    }

    @Test
    fun theAppOpensTheAgendaOnTodayThoughItWasLeftPagedTheSameDay() = runComposeUiTest {
        settings.setLastScreen(LastScreen(tab = "CALENDAR", calendarMode = "Agenda", calendarDate = "$secondPage", savedOn = "$today"))
        start()
        setContent { App() }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Agenda").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Agenda").assertIsSelected()
        waitForIdle()
        assertEquals("$today", settings.getLastScreen().calendarDate)
    }

    @Test
    fun aPeriodIsOnlyKeptTheDayItWasSeenAndNeverTheAgendas() {
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val week = CalendarTarget(CalendarMode.Week, secondPage)
        val agenda = CalendarTarget(CalendarMode.Agenda, secondPage)

        assertEquals(week, reopenedCalendar(week, seenOn = today, today = today))
        assertEquals(week.copy(date = today), reopenedCalendar(week, seenOn = yesterday, today = today), "left overnight")
        assertEquals(week.copy(date = today), reopenedCalendar(week, seenOn = null, today = today))

        assertEquals(agenda.copy(date = today), reopenedCalendar(agenda, seenOn = today, today = today))
        assertEquals(agenda, reopenedCalendar(agenda, seenOn = today, today = today, returning = true))
        assertEquals(
            agenda.copy(date = today),
            reopenedCalendar(agenda, seenOn = yesterday, today = today, returning = true),
            "a note left open overnight is not gone back from to last week",
        )
        assertEquals(null, reopenedCalendar(null, seenOn = today, today = today))
    }

    @Test
    fun aNoteIsStillWhereItWasReadAfterAnotherTabWasLookedAt() = runComposeUiTest {
        start()
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Heading 1").fetchSemanticsNodes().isNotEmpty() }

        // The note's list, not the drawer's file tree: one item per heading.
        onNode(
            hasScrollToIndexAction() and SemanticsMatcher("has 60 rows") {
                it.config.getOrElseNullable(SemanticsProperties.CollectionInfo) { null }?.rowCount == 60
            },
        ).performScrollToIndex(40)
        waitForIdle()
        onNodeWithText("Heading 41").assertIsDisplayed()

        onNodeWithText("Calendar").performClick()
        waitForIdle()
        onNodeWithText("File").performClick()
        waitForIdle()

        onNodeWithText("Heading 41").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Heading 1").fetchSemanticsNodes().isEmpty(), "not back at the top")
    }
}
