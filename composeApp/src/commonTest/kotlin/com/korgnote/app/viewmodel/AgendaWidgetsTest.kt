package com.korgnote.app.viewmodel

import com.korgnote.app.widgets.AgendaWidgets
import com.korgnote.common.org.agenda.AgendaEntry
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * What the home-screen widgets are handed: the agenda once the notes are read, again whenever it or
 * the sync time changes, and never the empty agenda of notes not read yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaWidgetsTest {
    private val work = "work.org"
    private val workText = "* Design review <2026-09-23 Wed 10:00>\n* Other\n"

    /** Every agenda the view model handed over, with its sync time, in order. */
    private val handedOver = mutableListOf<Pair<List<AgendaEntry>, Instant?>>()
    private val widgets = object : AgendaWidgets {
        override fun show(entries: List<AgendaEntry>, lastSynced: Instant?) {
            handedOver += entries to lastSynced
        }
    }
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(
            SyncEngine(store, FakeSyncApi(mutableMapOf(work to workText))),
            store,
            today = { LocalDate(2026, 9, 23) },
            widgets = widgets,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun nothingIsHandedOverBeforeTheNotesHaveBeenRead() {
        viewModel.agenda.refresh()
        assertEquals(emptyList(), handedOver)
    }

    @Test
    fun readingTheNotesHandsOverTheirAgendaAndWhenTheySynced() = runTest {
        viewModel.server.load()

        val (entries, lastSynced) = handedOver.single()
        assertEquals(listOf("Design review"), entries.map { it.title })
        assertNotNull(lastSynced)
    }

    @Test
    fun aRefreshThatChangesNothingLeavesThemAlone() = runTest {
        viewModel.server.load()
        viewModel.agenda.refresh()
        assertEquals(1, handedOver.size)
    }

    @Test
    fun anEditThatAddsAnAppointmentHandsOverTheNewAgenda() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.note.content = workText.replace("* Other", "* Lunch <2026-09-23 Wed 12:30>")
        viewModel.saveCurrentFile()

        assertEquals(listOf("Design review", "Lunch"), handedOver.last().first.map { it.title })
    }
}
