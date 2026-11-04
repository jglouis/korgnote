package com.korgnote.app.viewmodel

import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
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
import kotlin.test.assertSame
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

        override fun synced(at: Instant) {
            syncTimes += at
        }
    }
    private val syncTimes = mutableListOf<Instant>()
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
        viewModel.changes.saveCurrentFile()

        assertEquals(listOf("Design review", "Lunch"), handedOver.last().first.map { it.title })
    }

    /** Each push moves the sync time: two widgets drawn for every save that moves no date. */
    @Test
    fun aSyncThatMovesNoDateOnlySaysWhen() = runTest {
        viewModel.server.load()
        val handedOverAtFirst = handedOver.size

        viewModel.server.load()

        assertEquals(handedOverAtFirst, handedOver.size)
        assertEquals(1, syncTimes.size)
    }

    /** A sync usually brings one note: the others' entries are not looked for again. */
    @Test
    fun aNoteThatDidNotChangeIsNotScannedAgain() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage())
        val viewModel = NotesViewModel(SyncEngine(store, FakeSyncApi()), store)
        val first = viewModel.agenda.build(mapOf("a.org" to "* One\n<2026-11-01 Sun>\n", "b.org" to "* Two\n<2026-11-02 Mon>\n"), DEFAULT_TODO_KEYWORDS)

        val second = viewModel.agenda.build(mapOf("a.org" to "* One\n<2026-11-01 Sun>\n", "b.org" to "* Three\n<2026-11-02 Mon>\n"), DEFAULT_TODO_KEYWORDS)

        assertSame(first.entries.single { it.file == "a.org" }, second.entries.single { it.file == "a.org" })
        assertEquals("Three", second.entries.single { it.file == "b.org" }.title)
    }
}
