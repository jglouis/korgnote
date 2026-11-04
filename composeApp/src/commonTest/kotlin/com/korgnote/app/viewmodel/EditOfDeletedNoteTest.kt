package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A note deleted on another device while an element of it is being edited here. Save used to clear
 * the editor and then write nothing, since the note had been closed under it; what was typed was gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditOfDeletedNoteTest {
    private val path = "notes.org"
    private val other = "other.org"
    private val text = "* First\nbody\n"

    private lateinit var api: FakeSyncApi
    private lateinit var store: DocumentStore
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        api = FakeSyncApi(mutableMapOf(path to text, other to "* Other\n"))
        store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(
            SyncEngine(store, api),
            store,
            background = UnconfinedTestDispatcher(main.scheduler),
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.editing(draft: String?) {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
        viewModel.editor.beginEdit(0)
        draft?.let { viewModel.editor.onDraftChanged(TextFieldValue(it)) }
    }

    private suspend fun TestScope.deletedElsewhere() {
        api.files.remove(path)
        viewModel.server.load()
        advanceUntilIdle()
    }

    @Test
    fun theEditStaysOpenOnItsNote() = runTest {
        editing("* First, typed\nbody\n")

        deletedElsewhere()

        assertEquals(path, viewModel.note.path)
        assertEquals(0, viewModel.editor.editingIndex)
        assertEquals("* First, typed\nbody\n", viewModel.editor.draft)
        assertEquals(text, viewModel.note.content)
    }

    @Test
    fun savingBringsTheNoteBackWithWhatWasTyped() = runTest {
        editing("* First, typed\nbody\n")
        deletedElsewhere()

        viewModel.editor.commit()
        advanceUntilIdle()

        assertNull(viewModel.editor.editingIndex)
        assertEquals(path, viewModel.note.path)
        assertEquals("* First, typed\nbody\n", store.read(path))
        assertEquals("* First, typed\nbody\n", api.files[path], "the other device gets it back")
        assertTrue(path in viewModel.library.paths, "it is in the drawer again")
        assertTrue(viewModel.lastErrorMessage.contains(path), viewModel.lastErrorMessage)
    }

    @Test
    fun discardingTheEditLeavesTheNoteDeleted() = runTest {
        editing("* First, typed\nbody\n")
        deletedElsewhere()

        viewModel.editor.cancel()
        advanceUntilIdle()

        assertEquals(other, viewModel.note.path)
        assertEquals("* Other\n", viewModel.note.content)
        assertNull(store.read(path))
        assertTrue(path !in api.files, "the deletion stands")
    }

    @Test
    fun anEditWithNothingTypedGoesWithTheNote() = runTest {
        editing(draft = null)

        deletedElsewhere()

        assertNull(viewModel.editor.editingIndex)
        assertEquals(other, viewModel.note.path)
    }

    @Test
    fun anEditWithNothingTypedGoesWithTheLastNote() = runTest {
        api.files.remove(other)
        editing(draft = null)

        deletedElsewhere()

        assertNull(viewModel.editor.editingIndex)
        assertNull(viewModel.note.path)
        assertEquals("", viewModel.note.content)
    }

    /** A tick is not worth undoing a deletion for: only what was typed brings a note back. */
    @Test
    fun aTickDoesNotBringANoteBack() = runTest {
        viewModel.server.load()
        api.files[path] = "- [ ] one\n"
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()

        // Gone from the replica, and the app has not read it again yet.
        store.removeLocally(path)
        viewModel.changes.toggleCheckboxAt(0)
        advanceUntilIdle()

        assertNull(store.read(path))
    }
}
