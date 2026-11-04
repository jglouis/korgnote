package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A keyword or priority picked from a menu, or a note's Save: the note changes at once, and the
 * agenda, links and search follow from a scan away from the main thread, as a tick's do — run at
 * once, the scan held the menu, and the note dialog, open for a second on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateChangeRefreshTest {

    private val path = "tasks.org"
    private val text = "#+TODO: TODO | DONE\n* TODO Call the plumber\nSCHEDULED: <2026-11-01 Sun>\n"
    private val day = LocalDate(2026, 11, 1)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theNoteChangesAtOnceAndTheAgendaFollows() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage())
        val viewModel = NotesViewModel(
            SyncEngine(store, FakeSyncApi(mutableMapOf(path to text))),
            store,
            today = { day },
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
        assertEquals("TODO", viewModel.agenda.index.on(day).single().entry.todo)

        viewModel.setTodoAt(text.indexOf("* TODO"), "DONE")

        assertTrue(viewModel.note.content.contains("* DONE Call the plumber"), "the note at once")
        assertEquals("TODO", viewModel.agenda.index.on(day).single().entry.todo, "the agenda a moment later")

        advanceUntilIdle()
        assertEquals("DONE", viewModel.agenda.index.on(day).single().entry.todo)
    }
}
