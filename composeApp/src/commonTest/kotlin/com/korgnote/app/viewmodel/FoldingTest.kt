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

/**
 * Folding is view state, but it has rules that can hide what the user asked to see: a fold must
 * stay on its heading when the text above it moves, and anything that takes the user to an element
 * — a jump, an edit, an insertion — must open the folds around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoldingTest {

    private val path = "project.org"

    // Elements: 0 Project, 1 Step one, 2 Detail, 3 Step two, 4 Other
    private val text = """
        * Project
        ** Step one
        *** Detail
        Needle in the detail.
        ** Step two
        * Other
    """.trimIndent() + "\n"

    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to text, "other.org" to "* Elsewhere\n"))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, repository), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openFile(scrollToOffset: Int? = null) {
        viewModel.server.load()
        viewModel.onFileSelected(path, scrollToOffset)
    }

    @Test
    fun everythingStartsExpandedAndAToggleFlipsOneHeading() = runTest {
        openFile()
        assertEquals(emptySet(), viewModel.folds.folded)

        viewModel.folds.toggle(1)
        assertEquals(setOf(1), viewModel.folds.folded)

        viewModel.folds.toggle(1)
        assertEquals(emptySet(), viewModel.folds.folded)
    }

    @Test
    fun aFoldStaysOnItsHeadingWhenAHeadingIsAddedAbove() = runTest {
        openFile()
        viewModel.folds.toggle(3) // Step two

        viewModel.editor.addSubheading(0)
        viewModel.editor.onDraftChanged("** Step zero\n")
        viewModel.editor.commit()

        assertEquals(setOf(4), viewModel.folds.folded, "Step two moved down one, and its fold with it")
    }

    @Test
    fun foldingIsNeverWrittenToTheNote() = runTest {
        openFile()
        viewModel.folds.toggle(0)

        assertEquals(text, viewModel.note.content)
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun aJumpIntoAFoldOpensItsAncestorsAndItself() = runTest {
        openFile()
        viewModel.folds.toggle(0)
        viewModel.folds.toggle(1)
        viewModel.folds.toggle(2)
        viewModel.folds.toggle(4)

        openFile(scrollToOffset = text.indexOf("Needle"))

        assertEquals(2, viewModel.note.scrollToElementIndex)
        assertEquals(setOf(4), viewModel.folds.folded, "the fold elsewhere is left alone")
    }

    @Test
    fun editingInsideAFoldOpensIt() = runTest {
        openFile()
        viewModel.folds.toggle(0)

        viewModel.editor.beginEdit(2)

        assertEquals(emptySet(), viewModel.folds.folded)
    }

    @Test
    fun addingASubheadingUnderAFoldedHeadingOpensIt() = runTest {
        openFile()
        viewModel.folds.toggle(1)

        viewModel.editor.addSubheading(1)
        assertEquals(emptySet(), viewModel.folds.folded)

        viewModel.editor.cancel()
        assertEquals(emptySet(), viewModel.folds.folded, "discarding does not fold again")
    }

    @Test
    fun foldsBelongToTheirFile() = runTest {
        openFile()
        viewModel.folds.toggle(0)

        viewModel.onFileSelected("other.org")
        assertEquals(emptySet(), viewModel.folds.folded)

        viewModel.onFileSelected(path)
        assertEquals(setOf(0), viewModel.folds.folded)
    }
}
