package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Comparing a note with its conflict copy, in the real app shell. */
@OptIn(ExperimentalTestApi::class)
class AppConflictTest {
    private val note = "a.org"
    private val copy = "a.sync-conflict-20260921-101500.org"
    private val files = mutableMapOf(note to "* Ours\n", copy to "* Theirs\n")

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
    fun theComparisonClosesWhenTheConflictIsSettledElsewhere() = runComposeUiTest {
        viewModel.onFileSelected(note)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Compare").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Compare").performClick()
        onNodeWithText("Compare versions").assertExists()

        // As another device settling it would: the copy goes, and the comparison has no two sides left.
        runBlocking { viewModel.noteFiles.discardConflictCopy(copy) }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Compare versions").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Compare").assertDoesNotExist()
    }
}
