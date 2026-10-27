package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

/** The link button, on a link, opens the link form; what is saved there is what the draft holds. */
@OptIn(ExperimentalTestApi::class)
class AppLinkEditTest {
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("reading.org" to "* Reading\nSee [[https://old.example][the docs]].\n"))
        viewModel = NotesViewModel(SyncEngine(store, api), store)
        runBlocking { viewModel.server.load() }
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
                    viewModel { viewModel }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun linkButton() = hasText("Edit link") or hasContentDescription("Edit link")

    @Test
    fun theLabelAndAddressOfALinkAreChangedInAForm() = runComposeUiTest {
        setContent { App() }
        runOnIdle {
            viewModel.onFileSelected("reading.org")
            viewModel.editor.beginEdit(0)
            val draft = viewModel.editor.draft
            viewModel.editor.onDraftChanged(TextFieldValue(draft, TextRange(draft.indexOf("old.example"))))
        }

        onNode(linkButton()).performClick()
        onNodeWithText("Remove link").assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("the docs")).performTextReplacement("the manual")
        onNode(hasSetTextAction() and hasText("https://old.example")).performTextReplacement("new.example/manual")
        onNodeWithText("Save").performClick()

        runOnIdle {
            assertEquals("* Reading\nSee [[https://new.example/manual][the manual]].\n", viewModel.editor.draft)
        }
    }

    @Test
    fun aNoteLinkNeedsANoteChosen() = runComposeUiTest {
        setContent { App() }
        runOnIdle {
            viewModel.onFileSelected("reading.org")
            viewModel.editor.beginEdit(0)
            val draft = viewModel.editor.draft
            viewModel.editor.onDraftChanged(TextFieldValue(draft, TextRange(draft.indexOf("old.example"))))
        }

        onNode(linkButton()).performClick()
        onNodeWithText("Note").performClick()

        onNodeWithText("No note chosen yet").assertIsDisplayed()
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun removingALinkKeepsItsWords() = runComposeUiTest {
        setContent { App() }
        runOnIdle {
            viewModel.onFileSelected("reading.org")
            viewModel.editor.beginEdit(0)
            val draft = viewModel.editor.draft
            viewModel.editor.onDraftChanged(TextFieldValue(draft, TextRange(draft.indexOf("old.example"))))
        }

        onNode(linkButton()).performClick()
        onNodeWithText("Remove link").performClick()

        runOnIdle { assertEquals("* Reading\nSee the docs.\n", viewModel.editor.draft) }
    }
}
