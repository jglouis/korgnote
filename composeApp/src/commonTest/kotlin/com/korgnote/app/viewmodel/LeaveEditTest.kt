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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening another note while an element is being edited: the edit must not follow to the other
 * note, where it showed over the element at the same place and its save was refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaveEditTest {
    private val first = "first.org"
    private val second = "second.org"
    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        repository = FakeSyncApi(mutableMapOf(first to "* One\n", second to "* Shopping\n- milk\n* Ideas\n"))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, repository), store, background = UnconfinedTestDispatcher(main.scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anEmptyHeadingBeingAddedIsDroppedWhenAnotherNoteOpens() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(first)
        advanceUntilIdle()
        viewModel.editor.addAtEnd()

        viewModel.onFileSelected(second)
        advanceUntilIdle()

        assertEquals(second, viewModel.note.path)
        assertNull(viewModel.editor.editingIndex)
        assertEquals("* Shopping\n- milk\n* Ideas\n", viewModel.note.content)
        // Nothing of it was left in the note it was added to.
        assertEquals("* One\n", repository.files[first])
    }

    @Test
    fun anEditWithChangesKeepsItsNote() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(first)
        advanceUntilIdle()
        viewModel.editor.beginEdit(0)
        viewModel.editor.onDraftChanged("* One, changed\n")
        assertEquals(0, viewModel.editor.editingIndex, "editing")
        assertTrue(!viewModel.editor.isUntouched, "touched")

        viewModel.onFileSelected(second)
        advanceUntilIdle()

        assertEquals(first, viewModel.note.path)
        assertEquals(0, viewModel.editor.editingIndex)
    }
}
