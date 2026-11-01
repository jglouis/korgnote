package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Choosing a heading's priority from its long-press menu, in the real app shell. */
@OptIn(ExperimentalTestApi::class)
class AppPriorityTest {
    private val tasks = "tasks.org"
    private val files = mutableMapOf(tasks to "#+PRIORITIES: A D C\n* TODO [#B] Write it\n* Plain heading\n")

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, FakeSyncApi(files)), store)
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
    fun aHeadingWithoutOneGetsAPriorityFromItsMenu() = runComposeUiTest {
        viewModel.onFileSelected(tasks)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Plain heading", substring = true).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Plain heading", substring = true).performTouchInput { longClick() }
        onNodeWithText("Priority…").performClick()
        // The file's own range, A to D, and none.
        onNodeWithText("[#D]").assertExists()
        onNodeWithText("No priority").assertExists()
        onNodeWithText("[#A]").performClick()

        waitUntil(timeoutMillis = 5_000) { viewModel.note.content.contains("* [#A] Plain heading") }
        assertEquals("#+PRIORITIES: A D C\n* TODO [#B] Write it\n* [#A] Plain heading\n", viewModel.note.content)
    }

    @Test
    fun aHeadingShowsItsPriorityCookie() = runComposeUiTest {
        viewModel.onFileSelected(tasks)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Write it", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The parser keeps the cookie apart from the title; the heading still shows it, where it is written.
        onNodeWithText("TODO [#B] Write it").assertExists()
    }
}
