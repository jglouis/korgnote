package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Something landing while the replica is being read or written, which a phone's disk takes long
 * enough for: reading the note's text after such a wait read whatever had happened meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteRaceTest {
    private val a = "a.org"
    private val b = "b.org"
    private val textA = "* TODO First\n- [ ] one\n"
    private val textB = "* Other note\n"

    private lateinit var api: FakeSyncApi
    private lateinit var storage: StallingReplicaStorage
    private lateinit var store: DocumentStore
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        api = FakeSyncApi(mutableMapOf(a to textA, b to textB))
        storage = StallingReplicaStorage()
        store = DocumentStore(storage)
        viewModel = NotesViewModel(
            SyncEngine(store, api),
            store,
            background = UnconfinedTestDispatcher(main.scheduler),
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.opened(path: String) {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
    }

    private fun editFirstElement(text: String) {
        viewModel.editor.beginEdit(0)
        viewModel.editor.onDraftChanged(TextFieldValue(text))
    }

    /** A reload that read the note before the save must not put that text back, nor have it saved. */
    @Test
    fun aSaveDuringAReloadKeepsTheEdit() = runTest {
        opened(a)
        val edited = "* DONE First\n- [ ] one\n"
        editFirstElement(edited)
        // The reload reads the note, then shows it from a second read.
        val reloadRead = storage.stallRead(a, skipping = 1)
        launch { viewModel.server.load() }
        runCurrent()

        val saveRead = storage.stallRead(a)
        launch { viewModel.editor.commit() }
        runCurrent()
        reloadRead.complete(Unit)
        runCurrent()
        saveRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(edited, store.read(a))
        assertEquals(edited, viewModel.note.content)
    }

    @Test
    fun anotherNoteOpenedDuringASaveDoesNotGoIntoTheNoteSaved() = runTest {
        opened(a)
        val edited = "* DONE First\n- [ ] one\n"
        editFirstElement(edited)
        val saveRead = storage.stallRead(a)
        launch { viewModel.editor.commit() }
        runCurrent()

        viewModel.onFileSelected(b)
        runCurrent()
        saveRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(edited, store.read(a))
        assertEquals(textB, store.read(b))
        assertEquals(textB, viewModel.note.content)
    }

    /** Until the other note's text has arrived the screen still shows this one's. */
    @Test
    fun aTickWhileAnotherNoteOpensDoesNotWriteThisNoteOverIt() = runTest {
        opened(a)
        val opening = storage.stallRead(b)
        viewModel.onFileSelected(b)
        runCurrent()

        viewModel.changes.toggleCheckboxAt(textA.indexOf("- [ ]"))
        opening.complete(Unit)
        advanceUntilIdle()

        assertEquals(textB, store.read(b))
        assertEquals(textB, viewModel.note.content)
    }

    @Test
    fun aTickDuringAReloadStaysOnScreen() = runTest {
        opened(a)
        val reloadRead = storage.stallRead(a, skipping = 1)
        launch { viewModel.server.load() }
        runCurrent()

        viewModel.changes.toggleCheckboxAt(textA.indexOf("- [ ]"))
        reloadRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(textA.replace("[ ]", "[X]"), viewModel.note.content)
        assertEquals(textA.replace("[ ]", "[X]"), store.read(a))
    }

    /** The write is still on its way when the reload reads: the reload must not read what it replaces. */
    @Test
    fun aReloadWaitsForAWriteUnderWay() = runTest {
        opened(a)
        // Offline, a sync does not wait for the replica's lock before the reload.
        api.online = false
        val disk = storage.stallWrite(a)
        viewModel.changes.toggleCheckboxAt(textA.indexOf("- [ ]"))
        runCurrent()

        launch { viewModel.server.load() }
        // Why the sync failed is read from the app's resources, on a thread of their own.
        while (viewModel.server.unreachable == null) {
            withContext(Dispatchers.Default) { delay(5.milliseconds) }
            runCurrent()
        }
        assertEquals(textA.replace("[ ]", "[X]"), viewModel.library[a]?.text, "the library while it waits")
        disk.complete(Unit)
        advanceUntilIdle()

        assertEquals(textA.replace("[ ]", "[X]"), viewModel.library[a]?.text)
        assertEquals(textA.replace("[ ]", "[X]"), viewModel.note.content)
    }

    @Test
    fun aNoteOpenedDuringAReloadIsNotGivenTheTextOfTheOneBefore() = runTest {
        opened(a)
        val reloadRead = storage.stallRead(a, skipping = 1)
        launch { viewModel.server.load() }
        runCurrent()

        viewModel.onFileSelected(b)
        runCurrent()
        reloadRead.complete(Unit)
        advanceUntilIdle()

        assertEquals(b, viewModel.note.path)
        assertEquals(textB, viewModel.note.content)
    }
}
