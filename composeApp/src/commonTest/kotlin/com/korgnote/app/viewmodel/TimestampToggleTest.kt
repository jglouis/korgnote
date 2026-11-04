package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Making a stamp active or inactive from the long-press menu: the note changes, the calendar follows
 * — an active date is in it and an inactive one is not — and the change reaches the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimestampToggleTest {

    private val path = "journal.org"

    private val text = """
        * Called the plumber
        Visit booked for [2026-09-25 Fri 10:00].
    """.trimIndent() + "\n"

    private val sep25 = LocalDate(2026, 9, 25)

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
        val viewModel = NotesViewModel(SyncEngine(store, server), store, background = UnconfinedTestDispatcher(testScheduler))
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
        return viewModel to server
    }

    @Test
    fun anInactiveStampMadeActiveIsInTheCalendarAndBackOut() = runTest {
        val (viewModel, server) = openedNote()
        assertTrue(viewModel.agenda.index.on(sep25).isEmpty())

        viewModel.changes.toggleTimestampAt(text.indexOf('['))
        advanceUntilIdle()
        assertTrue("Visit booked for <2026-09-25 Fri 10:00>." in viewModel.note.content, viewModel.note.content)
        assertEquals("Called the plumber", viewModel.agenda.index.on(sep25).single().entry.title)
        assertTrue("<2026-09-25 Fri 10:00>" in server.files.getValue(path))

        viewModel.changes.toggleTimestampAt(text.indexOf('['))
        advanceUntilIdle()
        assertEquals(text, viewModel.note.content)
        assertTrue(viewModel.agenda.index.on(sep25).isEmpty())
    }

    @Test
    fun anOffsetWithNoStampChangesNothing() = runTest {
        val (viewModel, _) = openedNote()
        viewModel.changes.toggleTimestampAt(text.indexOf("Visit"))
        assertEquals(text, viewModel.note.content)
    }
}
