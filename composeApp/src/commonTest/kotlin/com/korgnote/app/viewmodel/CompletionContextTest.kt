package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the editor's completion is handed (T13).
 *
 * The engine is covered by `OrgCompleteTests` in `shared`; what only the app can get wrong is the
 * material — tags gathered from the whole replica rather than the open file, the open file's own
 * `#+TODO:` keywords, and both staying current as the notes change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionContextTest {

    private val work = "work.org"
    private val home = "home.org"

    private val workText = """
        #+TODO: TODO NEXT WAITING | DONE CANCELLED

        * Projects                                   :work:
        ** Invoices                                  :work:money:
        ** Meetings                                  :work:
    """.trimIndent() + "\n"

    private val homeText = """
        #+TITLE: Home

        * Repairs                                    :house:
    """.trimIndent() + "\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(work to workText, home to homeText))
        viewModel = NotesViewModel(SyncEngine(store, api), store, today = { LocalDate(2026, 9, 23) })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun tagsComeFromTheWholeReplicaMostUsedFirst() = runTest {
        viewModel.server.load()

        viewModel.onFileSelected(home)

        // `work` appears three times, the rest once each — and `house`, from the other file, is
        // offered while editing this one.
        assertEquals("work", viewModel.completion.context.knownTags.first())
        assertTrue(viewModel.completion.context.knownTags.containsAll(listOf("house", "money")))
    }

    @Test
    fun todoKeywordsComeFromTheOpenFile() = runTest {
        viewModel.server.load()

        viewModel.onFileSelected(work)
        assertContentEquals(listOf("TODO", "NEXT", "WAITING"), viewModel.completion.context.todoKeywords.todo.toList())
        assertContentEquals(listOf("DONE", "CANCELLED"), viewModel.completion.context.todoKeywords.done.toList())

        // The other file names no workflow, so completion there offers what the notes declare —
        // standing in for the editor-wide setting such a file relies on.
        viewModel.onFileSelected(home)
        assertContentEquals(listOf("TODO", "NEXT", "WAITING"), viewModel.completion.context.todoKeywords.todo.toList())
        assertContentEquals(listOf("DONE", "CANCELLED"), viewModel.completion.context.todoKeywords.done.toList())
    }

    @Test
    fun headingsAndPathsAreOfferedForLinks() = runTest {
        viewModel.server.load()

        viewModel.onFileSelected(work)

        assertContentEquals(listOf(home, work), viewModel.completion.context.filePaths)
        assertContentEquals(listOf("Projects", "Invoices", "Meetings"), viewModel.completion.context.headings)
    }

    @Test
    fun aNewTagIsOfferedAsSoonAsItIsSaved() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(home)
        assertTrue("garden" !in viewModel.completion.context.knownTags)

        viewModel.note.onContentChanged(homeText + "* Lawn :garden:\n")
        viewModel.saveCurrentFile()

        assertTrue("garden" in viewModel.completion.context.knownTags)
        assertTrue("Lawn" in viewModel.completion.context.headings)
    }

    @Test
    fun todayIsWhateverTheClockSays() = runTest {
        viewModel.server.load()

        assertEquals(LocalDate(2026, 9, 23), viewModel.completion.context.today)
    }
}
