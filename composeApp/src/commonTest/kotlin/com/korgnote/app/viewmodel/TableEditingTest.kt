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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A table opened as a grid from reading mode, and written back into the note. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableEditingTest {

    private val path = "shopping.org"
    private val text = "* Shopping\n| Item | Qty |\n| Milk | 2 |\nThat is all.\n"

    private lateinit var server: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(mutableMapOf(path to text))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, server), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSavedGridReplacesTheTableAlignedAndIsPushed() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)

        viewModel.beginTableEdit(text.indexOf("Milk"))
        val grid = viewModel.tableEdit!!.grid
        assertEquals(listOf(listOf("Item", "Qty"), listOf("Milk", "2")), grid.rows)

        viewModel.commitTableEdit(grid.withRowInserted(2).withCell(2, 0, "Bread").withCell(2, 1, "1"))

        val expected = "* Shopping\n| Item  | Qty |\n| Milk  |   2 |\n| Bread |   1 |\nThat is all.\n"
        assertEquals(expected, viewModel.note.content)
        assertEquals(expected, server.files[path])
        assertNull(viewModel.tableEdit)
    }

    /** Opening and closing a table is not an edit, so not even its alignment is touched. */
    @Test
    fun anUnchangedGridWritesNothing() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)

        viewModel.beginTableEdit(text.indexOf("Milk"))
        viewModel.commitTableEdit(viewModel.tableEdit!!.grid)

        assertEquals(text, viewModel.note.content)
    }

    @Test
    fun aTableWhoseTextMovedMeanwhileIsNotOverwritten() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        viewModel.beginTableEdit(text.indexOf("Milk"))
        val grid = viewModel.tableEdit!!.grid

        viewModel.note.content = "Inserted above.\n" + text
        viewModel.commitTableEdit(grid.withCell(1, 0, "Oat milk"))

        assertEquals("Inserted above.\n" + text, viewModel.note.content)
    }

    @Test
    fun aTapOutsideATableOpensNothing() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)

        viewModel.beginTableEdit(text.indexOf("That"))

        assertNull(viewModel.tableEdit)
    }
}
