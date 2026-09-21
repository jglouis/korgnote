package com.korgnote.app.viewmodel

import com.korgnote.common.fs.Folder
import com.korgnote.common.fs.TreeUtils
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
import kotlin.test.assertTrue
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine

/**
 * Editing and adding elements is a small state machine over the document text, and getting it wrong
 * silently loses what the user typed — so it is covered here rather than left to manual testing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElementEditingTest {

    private val path = "notes.org"

    private val initialText = """
        #+TITLE: Notes

        * TODO First task
        Body of the first task.

        * TODO Second task
    """.trimIndent() + "\n"

    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to initialText))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = FileTreeViewModel(SyncEngine(store, repository), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openFile() {
        viewModel.loadFromServer()
        viewModel.onFileSelected(path)
    }

    @Test
    fun addingAHeadingAtTheEndSavesIt() = runTest {
        openFile()

        viewModel.addElementAtEnd()
        viewModel.onElementDraftChanged("* TODO Buy milk\n")
        viewModel.commitElementEdit()

        assertTrue(viewModel.selectedFileContent.endsWith("* TODO Buy milk\n"), viewModel.selectedFileContent)
        assertEquals(initialText + "* TODO Buy milk\n", viewModel.selectedFileContent)
        assertEquals(viewModel.selectedFileContent, repository.saves.last().second)
        assertNull(viewModel.editingElementIndex)
    }

    @Test
    fun addingAHeadingAfterAnElementSavesItInPlace() = runTest {
        openFile()
        val firstTask = 1 // preamble, first task, second task

        viewModel.addElementAfter(firstTask)
        viewModel.onElementDraftChanged("* TODO Inserted\n")
        viewModel.commitElementEdit()

        assertEquals(
            initialText.replace("\n* TODO Second task", "\n* TODO Inserted\n* TODO Second task"),
            viewModel.selectedFileContent,
        )
        assertEquals(viewModel.selectedFileContent, repository.saves.last().second)
    }

    @Test
    fun cancellingAnAddedHeadingRemovesItAgain() = runTest {
        openFile()

        viewModel.addElementAtEnd()
        viewModel.onElementDraftChanged("* TODO Never mind\n")
        viewModel.cancelElementEdit()

        assertEquals(initialText, viewModel.selectedFileContent)
        assertTrue(repository.saves.isEmpty(), "cancelling must not write anything")
        assertNull(viewModel.editingElementIndex)
    }

    @Test
    fun editingAnExistingElementSavesOnlyThatElement() = runTest {
        openFile()

        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged("* TODO First task\nRewritten body.\n\n")
        viewModel.commitElementEdit()

        assertEquals(
            initialText.replace("Body of the first task.", "Rewritten body."),
            viewModel.selectedFileContent,
        )
        assertEquals(viewModel.selectedFileContent, repository.saves.last().second)
    }

    @Test
    fun cancellingAnExistingElementLeavesTheDocumentAlone() = runTest {
        openFile()

        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged("* TODO First task\nDiscarded.\n")
        viewModel.cancelElementEdit()

        assertEquals(initialText, viewModel.selectedFileContent)
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun theCaretStartsAfterTheStarsOfANewHeading() = runTest {
        openFile()

        viewModel.addElementAtEnd()

        assertEquals("* \n", viewModel.elementDraft)
        assertEquals(2, viewModel.elementDraftCaret)
    }

    @Test
    fun addingTwoHeadingsInARowKeepsBoth() = runTest {
        openFile()

        viewModel.addElementAtEnd()
        viewModel.onElementDraftChanged("* TODO One\n")
        viewModel.commitElementEdit()

        viewModel.addElementAtEnd()
        viewModel.onElementDraftChanged("* TODO Two\n")
        viewModel.commitElementEdit()

        assertEquals(initialText + "* TODO One\n* TODO Two\n", viewModel.selectedFileContent)
    }
}
