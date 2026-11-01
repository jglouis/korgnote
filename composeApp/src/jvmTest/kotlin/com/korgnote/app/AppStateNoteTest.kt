package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** The note a keyword's `@` asks for, written in its dialog, in the real app shell. */
@OptIn(ExperimentalTestApi::class)
class AppStateNoteTest {
    private val tasks = "tasks.org"
    private val files = mutableMapOf(tasks to "#+TODO: TODO(t) WAIT(w@/!) | DONE(d!)\n* TODO Call the plumber\n")

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
    fun theNoteIsWrittenInItsDialogAndLogged() = runComposeUiTest {
        viewModel.onFileSelected(tasks)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Call the plumber", substring = true).fetchSemanticsNodes().isNotEmpty() }

        viewModel.setTodoAt(viewModel.note.content.indexOf("* TODO"), "WAIT")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Note for WAIT").fetchSemanticsNodes().isNotEmpty() }
        // The dialog's field, which takes the focus; the drawer's filter is a text field too.
        onNode(hasSetTextAction() and isFocused()).performTextInput("Waiting for his quote.")
        onNodeWithText("Save").performClick()

        waitUntil(timeoutMillis = 5_000) { viewModel.note.content.contains("* WAIT Call the plumber") }
        assertTrue(viewModel.note.content.endsWith(" \\\\\n  Waiting for his quote.\n"), viewModel.note.content)
    }
}
