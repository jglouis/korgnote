package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.FileTreeViewModel
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

    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(inbox to inboxText, journal to journalText))
        viewModel = FileTreeViewModel(SyncEngine(store, api), store)
        runBlocking { viewModel.loadFromServer() }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun clickingARowOpensThatDocument() = runComposeUiTest {
        setContent {
            FileTree(root = viewModel.filteredTree, onFileSelected = { viewModel.onFileSelected(it) })
        }

        onNodeWithText("2025-01-01.org").performClick()

        assertEquals(journalText, viewModel.selectedFileContent)
        assertEquals("Selected: $journal", viewModel.selectedFileLabel)
    }

    // There is deliberately no test here for clicking a row while the tree is filtered.
    //
    // The filter waits for a pause in the typing, and that pause is virtual time on the dispatcher
    // installed as Main, while this test body runs on the UI test's own clock. Waiting for it in
    // real time blocks the thread that would advance the virtual one, so the debounce never fires
    // and the wait for an idle composition never ends; advancing the scheduler by hand instead
    // risks the opposite hang, since Compose's frame clock keeps scheduling work and "idle" never
    // arrives. Either way the test spins rather than fails, which is worse than not having it.
    //
    // A test that owns the clock can do this properly, and `SearchTest` does: it drives the same
    // debounce through `runTest`. What is left here is what only a UI test can say — that a click
    // on a row opens that document.
}
