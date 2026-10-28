package com.korgnote.app.viewmodel

import com.korgnote.common.org.table.Recalculation
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
    fun aTablesFormulasComeAlongAndAreWrittenBackRecalculated() = runTest {
        val budget = "budget.org"
        val source = "| Item | Cost |\n|------+------|\n| Rent |  100 |\n|------+------|\n|      |    0 |\n#+TBLFM: @>$2=vsum(@I..@-I)\nAfter.\n"
        server.files[budget] = source
        viewModel.server.load()
        viewModel.onFileSelected(budget)

        viewModel.beginTableEdit(source.indexOf("Rent"))
        val grid = viewModel.tableEdit!!.grid
        assertEquals(listOf("@>$2=vsum(@I..@-I)"), grid.formulas)

        val edited = grid.withRowInserted(3).withCell(3, 0, "Food").withCell(3, 1, "20")
        val recalculated = edited.recalculated() as Recalculation.Done
        viewModel.commitTableEdit(edited.copy(rows = recalculated.rows))

        assertEquals(
            "| Item | Cost |\n|------+------|\n| Rent |  100 |\n| Food |   20 |\n|------+------|\n|      |  120 |\n#+TBLFM: @>$2=vsum(@I..@-I)\nAfter.\n",
            viewModel.note.content,
        )
    }

    @Test
    fun aFormulaLineChangedMeanwhileIsNotOverwritten() = runTest {
        val budget = "budget.org"
        val source = "| Item | Cost |\n| Rent |  100 |\n#+TBLFM: $2=1\n"
        server.files[budget] = source
        viewModel.server.load()
        viewModel.onFileSelected(budget)
        viewModel.beginTableEdit(source.indexOf("Rent"))
        val grid = viewModel.tableEdit!!.grid

        viewModel.note.content = source.replace("$2=1", "$2=2")
        viewModel.commitTableEdit(grid.withCell(1, 0, "Rent!"))

        assertEquals(source.replace("$2=1", "$2=2"), viewModel.note.content)
    }

    @Test
    fun aTapOutsideATableOpensNothing() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(path)

        viewModel.beginTableEdit(text.indexOf("That"))

        assertNull(viewModel.tableEdit)
    }
}
