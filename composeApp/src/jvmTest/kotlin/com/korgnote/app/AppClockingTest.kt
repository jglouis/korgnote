package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clocking a heading in from its long-press menu, and out from the bar, in the real app shell. */
@OptIn(ExperimentalTestApi::class)
class AppClockingTest {
    private val tasks = "tasks.org"
    private val files = mutableMapOf(
        tasks to "* Write the report\n:PROPERTIES:\n:Effort: 2:00\n:END:\n:LOGBOOK:\n" +
            "CLOCK: [2026-10-31 Sat 09:00]--[2026-10-31 Sat 10:00] =>  1:00\n:END:\n",
    )
    private var time = LocalDateTime(2026, 11, 1, 9, 30)

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, FakeSyncApi(files)), store, now = { time })
        runBlocking { viewModel.server.load() }
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
                    single { NotesSourceChoice(get(), DocumentStore(InMemoryReplicaStorage())) }
                    viewModel { viewModel }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun aHeadingIsClockedInFromItsMenuAndOutFromTheBar() = runComposeUiTest {
        viewModel.onFileSelected(tasks)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Write the report", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // What was clocked before, against the effort.
        onNodeWithContentDescription("1\u00A0h / 2\u00A0h clocked").assertExists()

        onAllNodesWithText("Write the report", substring = true)[0].performTouchInput { longClick() }
        onNodeWithText("Clock in").performClick()

        waitUntil(timeoutMillis = 5_000) { viewModel.clocks.running.isNotEmpty() }
        assertEquals(
            "* Write the report\n:PROPERTIES:\n:Effort: 2:00\n:END:\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n" +
                "CLOCK: [2026-10-31 Sat 09:00]--[2026-10-31 Sat 10:00] =>  1:00\n:END:\n",
            viewModel.note.content,
        )
        // The bar, on every tab, says what runs.
        onNodeWithText("Running for 0\u00A0min").assertExists()

        time = LocalDateTime(2026, 11, 1, 10, 15)
        onAllNodesWithContentDescription("Clock out")[0].performClick()

        waitUntil(timeoutMillis = 5_000) { viewModel.clocks.running.isEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("1\u00A0h\u00A045 / 2\u00A0h clocked").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(true, viewModel.note.content.contains("CLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:15] =>  0:45\n"))

        // Stopped a moment ago: the bar offers to clock back in.
        onNodeWithText("Stopped at 10:15").assertExists()
        time = LocalDateTime(2026, 11, 1, 10, 30)
        onNodeWithContentDescription("Clock back in").performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.clocks.running.isNotEmpty() }
        assertEquals(true, viewModel.note.content.contains(":LOGBOOK:\nCLOCK: [2026-11-01 Sun 10:30]\n"))
    }

    @Test
    fun anEffortIsSetFromTheMenuAndTheTotalIsShownAgainstIt() = runComposeUiTest {
        viewModel.onFileSelected(tasks)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Write the report", substring = true).fetchSemanticsNodes().isNotEmpty() }

        onAllNodesWithText("Write the report", substring = true)[0].performTouchInput { longClick() }
        onNodeWithText("Set effort…").performClick()
        onNodeWithText("30 min").performClick()

        waitUntil(timeoutMillis = 5_000) { viewModel.note.content.contains(":Effort: 0:30\n") }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("1 h / 30 min clocked").fetchSemanticsNodes().isNotEmpty() }

        // Removed, it takes its drawer with it when nothing else was in it.
        onAllNodesWithText("Write the report", substring = true)[0].performTouchInput { longClick() }
        onNodeWithText("Set effort…").performClick()
        onNodeWithText("Remove the effort").performClick()
        waitUntil(timeoutMillis = 5_000) { !viewModel.note.content.contains(":PROPERTIES:") }
        assertEquals(
            "* Write the report\n:LOGBOOK:\nCLOCK: [2026-10-31 Sat 09:00]--[2026-10-31 Sat 10:00] =>  1:00\n:END:\n",
            viewModel.note.content,
        )
    }
}
