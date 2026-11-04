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
import kotlin.test.assertTrue

/** Clocking in and out from the open note, with the clock running in another note stopped first. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockingTest {

    private val work = "work.org"
    private val home = "home.org"
    private var time = LocalDateTime(2026, 11, 1, 9, 30)
    private val shown = mutableListOf<List<ClockRunning>>()
    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        repository = FakeSyncApi(
            mutableMapOf(
                work to "* Report\n* Review\n",
                home to "* Garden\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 08:00]\n:END:\n",
            ),
        )
        val store = DocumentStore(InMemoryReplicaStorage())
        val notifier = object : ClockNotifier {
            override fun show(clocks: List<ClockRunning>) {
                shown += clocks
            }
        }
        viewModel = NotesViewModel(
            SyncEngine(store, repository), store, now = { time },
            background = UnconfinedTestDispatcher(main.scheduler), clockNotifier = notifier,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aClockStartedInEmacsIsFoundAndShown() = runTest {
        viewModel.server.load()
        assertEquals(listOf("Garden"), viewModel.clocks.running.map { it.title })
        assertEquals(listOf("Garden"), shown.last().map { it.title })
    }

    @Test
    fun clockingInStopsTheClockRunningInAnotherNote() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.clockActions.clockInAt(viewModel.note.content.indexOf("* Review"))
        advanceUntilIdle()

        assertEquals("* Report\n* Review\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]\n:END:\n", repository.files[work])
        assertEquals(
            "* Garden\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 08:00]--[2026-11-01 Sun 09:30] =>  1:30\n:END:\n",
            repository.files[home],
        )
        assertEquals(listOf(work to "Review"), viewModel.clocks.running.map { it.path to it.title })
    }

    @Test
    fun clockingInStopsTheClockRunningAboveInTheSameNote() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)
        viewModel.clockActions.clockInAt(0)
        advanceUntilIdle()

        time = LocalDateTime(2026, 11, 1, 10, 0)
        viewModel.clockActions.clockInAt(viewModel.note.content.indexOf("* Review"))
        advanceUntilIdle()

        assertEquals(
            "* Report\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 09:30]--[2026-11-01 Sun 10:00] =>  0:30\n:END:\n" +
                "* Review\n:LOGBOOK:\nCLOCK: [2026-11-01 Sun 10:00]\n:END:\n",
            repository.files[work],
        )
    }

    @Test
    fun theBarStopsAClockInANoteThatIsNotOpen() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.clockActions.clockOut(viewModel.clocks.running.single(), at = LocalDateTime(2026, 11, 1, 8, 45))
        advanceUntilIdle()

        assertTrue(repository.files[home]!!.contains("--[2026-11-01 Sun 08:45] =>  0:45"))
        assertEquals(emptyList(), viewModel.clocks.running)
        assertEquals(emptyList(), shown.last())
    }

    @Test
    fun cancellingLeavesTheNoteAsItWas() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)
        viewModel.clockActions.clockInAt(0)
        advanceUntilIdle()

        viewModel.clockActions.cancelClockAt(0)
        advanceUntilIdle()

        assertEquals("* Report\n* Review\n", repository.files[work])
    }

    @Test
    fun theRecentTasksAreTheLatestStoppedFirstWithoutTheOneRunning() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)
        // Report, then Review: Garden was stopped by the first, Report by the second.
        viewModel.clockActions.clockInAt(0)
        advanceUntilIdle()
        time = LocalDateTime(2026, 11, 1, 10, 0)
        viewModel.clockActions.clockInAt(viewModel.note.content.indexOf("* Review"))
        advanceUntilIdle()

        assertEquals(listOf("Report", "Garden"), viewModel.clocks.recent.map { it.title })

        // Back to the task the interruption took over from.
        time = LocalDateTime(2026, 11, 1, 10, 20)
        val report = viewModel.clocks.recent.first()
        viewModel.clockActions.clockIn(report.path, report.headingStart)
        advanceUntilIdle()
        assertEquals(listOf("Report"), viewModel.clocks.running.map { it.title })
        assertEquals(listOf("Review", "Garden"), viewModel.clocks.recent.map { it.title })
    }
}
