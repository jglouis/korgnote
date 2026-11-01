package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlin.test.assertTrue

/** A list item's further paragraphs and blocks, drawn inside the item in the real app shell. */
@OptIn(ExperimentalTestApi::class)
class AppListItemsTest {
    private val path = "steps.org"
    private val files = mutableMapOf(
        path to """
            * Steps
            1. First step

               Its second paragraph.
               #+begin_src sh
               make install
               #+end_src
            2. Second step
            3. [ ] Third step
        """.trimIndent() + "\n",
    )

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

    private fun androidx.compose.ui.test.ComposeUiTest.shown(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSecondStepIsNumberedTwoAndTheFirstKeepsItsContents() = runComposeUiTest {
        viewModel.onFileSelected(path)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { shown("First step") }

        assertTrue(shown("2. Second step"), "one list: the numbers run on")
        assertTrue(!shown("1. Second step"))
        assertTrue(shown("Its second paragraph."))
        assertTrue(shown("make install"))
        // A checkbox item keeps its number, before its box.
        assertTrue(onAllNodesWithText("3.").fetchSemanticsNodes().isNotEmpty(), "the checkbox step is numbered")
        assertTrue(shown("Third step"))
    }
}
