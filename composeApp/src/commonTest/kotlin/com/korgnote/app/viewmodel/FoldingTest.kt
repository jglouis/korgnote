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
    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to text, "other.org" to "* Elsewhere\n"))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = FileTreeViewModel(SyncEngine(store, repository), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openFile(scrollToOffset: Int? = null) {
        viewModel.loadFromServer()
        viewModel.onFileSelected(path, scrollToOffset)
    }

    @Test
    fun everythingStartsExpandedAndAToggleFlipsOneHeading() = runTest {
        openFile()
        assertEquals(emptySet(), viewModel.foldedElements)

        viewModel.toggleFold(1)
        assertEquals(setOf(1), viewModel.foldedElements)

        viewModel.toggleFold(1)
        assertEquals(emptySet(), viewModel.foldedElements)
    }

    @Test
    fun aFoldStaysOnItsHeadingWhenAHeadingIsAddedAbove() = runTest {
        openFile()
        viewModel.toggleFold(3) // Step two

        viewModel.addSubheading(0)
        viewModel.onElementDraftChanged("** Step zero\n")
        viewModel.commitElementEdit()

        assertEquals(setOf(4), viewModel.foldedElements, "Step two moved down one, and its fold with it")
    }

    @Test
    fun foldingIsNeverWrittenToTheNote() = runTest {
        openFile()
        viewModel.toggleFold(0)

        assertEquals(text, viewModel.selectedFileContent)
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun aJumpIntoAFoldOpensItsAncestorsAndItself() = runTest {
        openFile()
        viewModel.toggleFold(0)
        viewModel.toggleFold(1)
        viewModel.toggleFold(2)
        viewModel.toggleFold(4)

        openFile(scrollToOffset = text.indexOf("Needle"))

        assertEquals(2, viewModel.scrollToElementIndex)
        assertEquals(setOf(4), viewModel.foldedElements, "the fold elsewhere is left alone")
    }

    @Test
    fun editingInsideAFoldOpensIt() = runTest {
        openFile()
        viewModel.toggleFold(0)

        viewModel.beginElementEdit(2)

        assertEquals(emptySet(), viewModel.foldedElements)
    }

    @Test
    fun addingASubheadingUnderAFoldedHeadingOpensIt() = runTest {
        openFile()
        viewModel.toggleFold(1)

        viewModel.addSubheading(1)
        assertEquals(emptySet(), viewModel.foldedElements)

        viewModel.cancelElementEdit()
        assertEquals(emptySet(), viewModel.foldedElements, "discarding does not fold again")
    }

    @Test
    fun foldsBelongToTheirFile() = runTest {
        openFile()
        viewModel.toggleFold(0)

        viewModel.onFileSelected("other.org")
        assertEquals(emptySet(), viewModel.foldedElements)

        viewModel.onFileSelected(path)
        assertEquals(setOf(0), viewModel.foldedElements)
    }
}
