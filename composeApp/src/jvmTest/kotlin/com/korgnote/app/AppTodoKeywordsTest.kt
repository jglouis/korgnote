package com.korgnote.app

import com.korgnote.app.viewmodel.NotesSourceChoice
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.OrgColors
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

/**
 * TODO keywords as reading mode draws them, in the real app shell.
 *
 * Reading mode renders one heading at a time from its own slice of the file, and the slice never
 * holds the file's `#+TODO:` lines — so until the file's keywords were handed down, a `FINISHED`
 * heading below the declaration was drawn as plain text.
 */
@OptIn(ExperimentalTestApi::class)
class AppTodoKeywordsTest {
    private val agenda = "agenda.org"
    private val meetings = "meetings.org"

    private val files = mutableMapOf(
        agenda to """
            #+TITLE: Agenda
            #+TODO: TODO DOING | DONE CANCELED
            #+TODO: EVENT | FINISHED

            * FINISHED Dentist
            * EVENT Birthday party
            * CANCELED Concert
        """.trimIndent() + "\n",
        // Declares nothing: it relies on the keywords the notes declare, as it does in Emacs.
        meetings to "* FINISHED Meeting with the board\n",
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

    /**
     * The colour [word] is drawn in, in the rendered text of [line] — the heading, not the
     * `#+TODO:` line above it, which names the same words.
     */
    private fun ComposeUiTest.colorOf(word: String, line: String): Color? {
        val node = onAllNodesWithText(line, substring = true).fetchSemanticsNodes().first()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().first { line in it.text }
        val at = text.text.indexOf(word)
        return text.spanStyles.lastOrNull { at >= it.start && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun keywordsDeclaredAtTheTopColourHeadingsBelowIt() = runComposeUiTest {
        viewModel.onFileSelected(agenda)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dentist", substring = true).fetchSemanticsNodes().isNotEmpty() }

        assertEquals(OrgColors.done, colorOf("FINISHED", "FINISHED Dentist"))
        assertEquals(OrgColors.done, colorOf("CANCELED", "CANCELED Concert"))
        assertEquals(OrgColors.todo, colorOf("EVENT", "EVENT Birthday party"))
    }

    @Test
    fun aFileThatDeclaresNothingUsesTheKeywordsOfTheNotes() = runComposeUiTest {
        viewModel.onFileSelected(meetings)
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Meeting with the board", substring = true).fetchSemanticsNodes().isNotEmpty() }

        assertEquals(OrgColors.done, colorOf("FINISHED", "FINISHED Meeting with the board"))
    }
}
