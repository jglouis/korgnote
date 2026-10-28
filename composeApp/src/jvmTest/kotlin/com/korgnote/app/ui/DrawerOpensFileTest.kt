package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole path a reader takes: the drawer's tree, built by the view model from the replica, with
 * a click on one of its rows opening that document.
 *
 * The pieces either side of the click are covered elsewhere; what is not covered anywhere else is
 * that the path a row hands back is the path the view model can open.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DrawerOpensFileTest {

    private val inbox = "inbox.org"
    private val journal = "journal/2025-01-01.org"
    private val inboxText = "* TODO Buy milk\n"
    private val journalText = "* Went for a walk\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(inbox to inboxText, journal to journalText))
        viewModel = NotesViewModel(SyncEngine(store, api), store)
        runBlocking { viewModel.server.load() }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun clickingARowOpensThatDocument() = runComposeUiTest {
        setContent {
            FileTree(root = viewModel.search.filteredTree, onFileSelected = { viewModel.onFileSelected(it) })
        }

        onNodeWithText("journal").performClick()
        onNodeWithText("2025-01-01.org").performClick()

        assertEquals(journalText, viewModel.note.content)
        assertEquals("Selected: $journal", viewModel.note.label)
    }

    @Test
    fun clickingARowOfTheFilteredTreeOpensThatDocument() = runComposeUiTest {
        viewModel.search.onFilterQueryChanged("2025")
        setContent {
            FileTree(
                root = viewModel.search.filteredTree,
                onFileSelected = { viewModel.onFileSelected(it) },
                expandAll = viewModel.search.filterQuery.isNotBlank(),
            )
        }

        onNodeWithText("inbox.org").assertDoesNotExist()
        onNodeWithText("2025-01-01.org").performClick()

        assertEquals(journalText, viewModel.note.content)
    }
}
