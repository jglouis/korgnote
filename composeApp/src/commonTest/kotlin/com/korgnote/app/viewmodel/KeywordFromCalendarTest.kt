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
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A heading's keyword changed from the calendar, in a note that need not be the open one. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeywordFromCalendarTest {
    private val open = "open.org"
    private val work = "work.org"
    private val time = LocalDateTime(2026, 11, 2, 9, 30)
    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        repository = FakeSyncApi(
            mutableMapOf(
                open to "* Something else\n",
                work to "#+TODO: TODO WAIT(w@) | DONE(d!)\n* TODO Report [0/1]\n** TODO Figures\n",
            ),
        )
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(
            SyncEngine(store, repository), store, now = { time },
            background = UnconfinedTestDispatcher(main.scheduler),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aKeywordIsSetInANoteThatIsNotOpenLoggedAndCounted() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(open)
        val text = repository.files[work]!!

        viewModel.stateNotes.setTodo(work, text.indexOf("** TODO Figures"), "DONE")
        advanceUntilIdle()

        assertEquals(
            "#+TODO: TODO WAIT(w@) | DONE(d!)\n* TODO Report [1/1]\n** DONE Figures\n" +
                "- State \"DONE\"       from \"TODO\"       [2026-11-02 Mon 09:30]\n",
            repository.files[work],
        )
        // The open note stays the one that was open.
        assertEquals(open, viewModel.note.path)
        assertEquals(setOf("TODO", "WAIT"), viewModel.stateNotes.keywordsOf(work).todo)
    }

    /** What a `[1/2]` counts as done is the note's own, whichever note happens to be open. */
    @Test
    fun aCookieCountsWithTheKeywordsOfItsOwnNote() = runTest {
        val own = "#+TODO: NEXT | FINISHED\n* Report [0/2]\n** NEXT Figures\n** FINISHED Table\n"
        repository.files[work] = own
        viewModel.server.load()
        viewModel.onFileSelected(open)

        viewModel.stateNotes.setTodo(work, own.indexOf("** NEXT Figures"), "FINISHED")
        advanceUntilIdle()

        assertEquals(true, repository.files[work]?.contains("* Report [2/2]"), repository.files[work])
    }

    @Test
    fun aKeywordThatAsksForANoteOpensItsNoteToAsk() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(open)

        viewModel.stateNotes.setTodo(work, repository.files[work]!!.indexOf("* TODO Report"), "WAIT")
        advanceUntilIdle()

        assertEquals(work, viewModel.note.path)
        assertEquals("WAIT", viewModel.stateNotes.pending?.keyword)
    }
}
