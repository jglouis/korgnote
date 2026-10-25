package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** Notes pinned for quick access: kept in settings, followed through renames, opened at launch. */
@OptIn(ExperimentalCoroutinesApi::class)
class PinnedNotesTest {

    private val agenda = "agenda.org"
    private val todo = "todo.org"
    private val archive = "archive.org"

    private lateinit var server: FakeSyncApi
    private lateinit var store: DocumentStore
    /** What settings hold, as the view model last saved it. */
    private var saved = emptyList<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(mutableMapOf(agenda to "* Agenda\n", todo to "* TODO Things\n", archive to "* Old\n"))
        store = DocumentStore(InMemoryReplicaStorage())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NotesViewModel(
        SyncEngine(store, server),
        store,
        pinnedNotes = { saved },
        savePinnedNotes = { saved = it },
    )

    @Test
    fun pinningKeepsTheNotesInSettingsInTheOrderTheyWerePinned() = runTest {
        val vm = viewModel()
        vm.server.load()

        vm.pins.toggle(todo)
        vm.pins.toggle(agenda)

        assertEquals(listOf(todo, agenda), saved)
        assertEquals(listOf(todo, agenda), vm.pins.shown)
        assertTrue(vm.pins.isPinned(agenda))

        vm.pins.toggle(todo)
        assertEquals(listOf(agenda), saved)
        assertFalse(vm.pins.isPinned(todo))
    }

    @Test
    fun theFirstPinnedNoteOpensAtLaunch() = runTest {
        saved = listOf(todo, agenda)

        val vm = viewModel()
        vm.server.load()

        assertEquals(todo, vm.note.path, "rather than the alphabetically first note")
    }

    @Test
    fun withNothingPinnedTheFirstNoteOpensAsBefore() = runTest {
        val vm = viewModel()
        vm.server.load()

        assertEquals(agenda, vm.note.path)
    }

    @Test
    fun aRenamedNoteKeepsItsPinAndItsPlace() = runTest {
        saved = listOf(agenda, todo)
        val vm = viewModel()
        vm.server.load()

        assertTrue(vm.renameNote(agenda, "plans/agenda"))

        assertEquals(listOf("plans/agenda.org", todo), saved)
        assertEquals(listOf("plans/agenda.org", todo), vm.pins.shown)
    }

    @Test
    fun aDeletedNoteIsUnpinned() = runTest {
        saved = listOf(agenda, todo)
        val vm = viewModel()
        vm.server.load()

        vm.deleteNote(todo)

        assertEquals(listOf(agenda), saved)
    }

    @Test
    fun aPinWhoseNoteIsMissingIsHiddenButKept() = runTest {
        saved = listOf("gone.org", todo)
        val vm = viewModel()

        assertEquals(emptyList(), vm.pins.shown, "nothing is shown before the notes are read")
        vm.server.load()

        assertEquals(listOf(todo), vm.pins.shown)
        assertEquals(todo, vm.note.path, "the first pin that is there opens")
        assertEquals(listOf("gone.org", todo), saved, "it may yet arrive")
    }

    @Test
    fun pinnedNotesAreLabelledByNameUnlessTwoShareIt() {
        assertEquals(
            listOf("agenda.org", "work/todo.org", "home/todo.org"),
            pinLabels(listOf("agenda.org", "work/todo.org", "home/todo.org")),
        )
    }
}
