package com.korgnote.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlinx.datetime.TimeZone
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
    private val longNote = (1..60).joinToString("") { "* Heading $it\nSome text under heading $it.\n" }

    private fun start() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("long.org" to longNote))
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
