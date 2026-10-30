package com.korgnote.app

import com.korgnote.app.viewmodel.NotesSourceChoice
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
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

/** A tap on a link that leads nowhere says why, where the reader is looking, and stays put. */
@OptIn(ExperimentalTestApi::class)
class AppDeadLinkTest {
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("keys.org" to "* Keys\n[[kbd:][C-c C-c]]\n"))
        viewModel = NotesViewModel(SyncEngine(store, api), store)
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
    fun aKeyLinkSaysItLeadsNowhere() = runComposeUiTest {
        setContent { App() }
        runOnIdle { viewModel.onFileSelected("keys.org") }

        // At the start of the line, where the link is; the text's node is as wide as the screen.
        onNodeWithText("C-c C-c").performTouchInput { click(Offset(4f, centerY)) }

        // The message is looked up in the app's language before it shows.
        waitUntilAtLeastOneExists(hasText("names keys to press in Emacs", substring = true))
        onNodeWithText("names keys to press in Emacs", substring = true).assertIsDisplayed()
        assertEquals("keys.org", viewModel.note.path)
    }

    @Test
    fun aBrokenNoteLinkSaysSo() = runComposeUiTest {
        setContent { App() }
        runOnIdle { viewModel.onFileSelected("keys.org") }

        runOnIdle { viewModel.links.follow("id:missing") }

        // Clearing the message once shown must not take the snackbar down with it.
        waitUntilAtLeastOneExists(hasText("Nothing in the notes matches", substring = true))
        onNodeWithText("Nothing in the notes matches", substring = true).assertIsDisplayed()
    }
}
