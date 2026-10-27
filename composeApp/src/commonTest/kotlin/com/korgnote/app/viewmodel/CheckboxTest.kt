package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/**
 * Ticking a checkbox: the note changes at once, and what is derived from every note — search, the
 * agenda, links — catches up once the ticking stops, so a tap is not held up by a scan of them all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckboxTest {

    private val path = "groceries.org"

    private val text = """
        * Groceries
        - [ ] Milk
        - [ ] Bread
    """.trimIndent() + "\n"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.openedNote(): Pair<NotesViewModel, FakeSyncApi> {
        val store = DocumentStore(InMemoryReplicaStorage())
        val server = FakeSyncApi(mutableMapOf(path to text))
        val viewModel = NotesViewModel(
            SyncEngine(store, server),
            store,
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(path)
        viewModel.search.onQueryChanged("Milk")
        advanceUntilIdle()
        return viewModel to server
    }

    @Test
    fun theNoteChangesAtOnceAndTheRestOnceTheTickingStops() = runTest {
        val (viewModel, _) = openedNote()
        assertEquals("- [ ] Milk", viewModel.search.results.single().lineText)

        viewModel.toggleCheckboxAt(text.indexOf("- [ ] Milk"), this)

        assertTrue(viewModel.note.content.contains("- [X] Milk"))
        assertEquals("- [ ] Milk", viewModel.search.results.single().lineText, "not yet: a tick may follow")

        viewModel.toggleCheckboxAt(text.indexOf("- [ ] Bread"), this)
        advanceTimeBy(NotesViewModel.DERIVED_REFRESH_PAUSE / 2)
        assertEquals("- [ ] Milk", viewModel.search.results.single().lineText, "the second tick put it off")

        advanceUntilIdle()
        assertEquals("- [X] Milk", viewModel.search.results.single().lineText)
    }

    @Test
    fun aTickReachesTheServer() = runTest {
        val (viewModel, server) = openedNote()

        viewModel.toggleCheckboxAt(text.indexOf("- [ ] Milk"), this)
        advanceUntilIdle()

        assertTrue(server.files.getValue(path).contains("- [X] Milk"))
    }

    @Test
    fun aSaveMeanwhileRefreshesAtOnceAndNothingIsLeftPending() = runTest {
        val (viewModel, _) = openedNote()

        viewModel.toggleCheckboxAt(text.indexOf("- [ ] Milk"), this)
        viewModel.saveCurrentFile()

        assertEquals("- [X] Milk", viewModel.search.results.single().lineText)
    }
}
