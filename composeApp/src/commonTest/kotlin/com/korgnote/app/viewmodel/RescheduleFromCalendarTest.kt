package com.korgnote.app.viewmodel

import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.org.agenda.AgendaKind
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An entry's date moved from the calendar's long-press menu, in a note that need not be open. */
@OptIn(ExperimentalCoroutinesApi::class)
class RescheduleFromCalendarTest {
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
                work to "* TODO Report\nSCHEDULED: <2026-11-03 Tue 10:00-11:00 +1w> DEADLINE: <2026-11-06 Fri -2d>\n" +
                    "* Trip\n<2026-11-20 Fri>--<2026-11-22 Sun>\n",
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

    private fun entry(title: String, kind: AgendaKind): AgendaEntry =
        viewModel.agenda.index.entries.single { it.file == work && it.title == title && it.kind == kind }

    @Test
    fun aScheduledEntryMovesKeepingItsRepeaterAndTheDeadlineBesideIt() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(open)

        viewModel.changes.reschedule(entry("Report", AgendaKind.Scheduled), LocalDate(2026, 11, 5), LocalTime(14, 0), LocalTime(15, 0))
        advanceUntilIdle()

        assertEquals(
            "* TODO Report\nSCHEDULED: <2026-11-05 Thu 14:00-15:00 +1w> DEADLINE: <2026-11-06 Fri -2d>\n" +
                "* Trip\n<2026-11-20 Fri>--<2026-11-22 Sun>\n",
            repository.files[work],
        )
        assertEquals(open, viewModel.note.path)
    }

    @Test
    fun aDeadlineAndARangeOverDaysMove() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.changes.reschedule(entry("Report", AgendaKind.Deadline), LocalDate(2026, 11, 13), null, null)
        advanceUntilIdle()
        viewModel.changes.reschedule(entry("Trip", AgendaKind.Timestamp), LocalDate(2026, 11, 27), null, null)
        advanceUntilIdle()

        assertEquals(
            "* TODO Report\nSCHEDULED: <2026-11-03 Tue 10:00-11:00 +1w> DEADLINE: <2026-11-13 Fri -2d>\n" +
                "* Trip\n<2026-11-27 Fri>--<2026-11-29 Sun>\n",
            repository.files[work],
        )
        assertEquals(viewModel.note.content, repository.files[work])
    }

    @Test
    fun anEntryThatChangedSinceIsLeftAsItIs() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(open)
        val stale = entry("Report", AgendaKind.Deadline)
        viewModel.changes.reschedule(stale, LocalDate(2026, 11, 13), null, null)
        advanceUntilIdle()
        val after = repository.files[work]

        // The same entry, as the calendar showed it before the first move.
        viewModel.changes.reschedule(stale, LocalDate(2026, 12, 1), null, null)
        advanceUntilIdle()

        assertEquals(after, repository.files[work])
    }
}
