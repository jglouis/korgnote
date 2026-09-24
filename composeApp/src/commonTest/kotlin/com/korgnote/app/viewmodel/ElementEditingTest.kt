package com.korgnote.app.viewmodel

import com.korgnote.common.org.orgElements
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

    // --- Every way of adding a heading (T24 moved them; none may be lost) ---

    private val projectText = """
        * Project
        Project body.
        ** Step one
        ** Step two
        * Other
    """.trimIndent() + "\n"

    private suspend fun openProject() {
        repository.files["project.org"] = projectText
        viewModel.loadFromServer()
        viewModel.onFileSelected("project.org")
    }

    @Test
    fun addingBelowAHeadingGoesAfterItsWholeSubtree() = runTest {
        openProject()

        viewModel.addElementAfter(0) // Project
        viewModel.onElementDraftChanged("* Sibling\n")
        viewModel.commitElementEdit()

        assertEquals(
            projectText.replace("* Other", "* Sibling\n* Other"),
            viewModel.selectedFileContent,
            "a sibling inserted before the steps would have taken them over",
        )
    }

    @Test
    fun addingASubheadingMakesAFirstChild() = runTest {
        openProject()

        viewModel.addSubheading(0)
        assertEquals("** \n", viewModel.elementDraft)
        viewModel.onElementDraftChanged("** Step zero\n")
        viewModel.commitElementEdit()

        assertEquals(
            projectText.replace("** Step one", "** Step zero\n** Step one"),
            viewModel.selectedFileContent,
        )
    }

    @Test
    fun addingBelowThePreambleMakesTheFirstHeading() = runTest {
        openFile()

        viewModel.addElementAfter(0)
        viewModel.onElementDraftChanged("* TODO Zeroth task\n")
        viewModel.commitElementEdit()

        assertEquals(
            initialText.replace("\n* TODO First task", "\n* TODO Zeroth task\n* TODO First task"),
            viewModel.selectedFileContent,
        )
    }

    @Test
    fun savingAndAddingBelowKeepsTheDraftAndOpensASibling() = runTest {
        openFile()

        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged("* TODO First task\nEdited.\n\n")
        viewModel.commitElementEditAndAddBelow()

        val saved = initialText.replace("Body of the first task.", "Edited.")
        assertEquals(saved, repository.saves.last().second, "the draft is saved before anything else")
        assertEquals(2, viewModel.editingElementIndex)
        assertEquals("* \n", viewModel.elementDraft)

        viewModel.onElementDraftChanged("* TODO Right after\n")
        viewModel.commitElementEdit()
        assertEquals(
            saved.replace("\n* TODO Second task", "\n* TODO Right after\n* TODO Second task"),
            viewModel.selectedFileContent,
        )
    }

    @Test
    fun savingAndAddingBelowFollowsTheLastHeadingInTheDraft() = runTest {
        openFile()

        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged("* TODO First task\n** Typed child\n")
        viewModel.commitElementEditAndAddBelow()

        assertEquals("** \n", viewModel.elementDraft, "a sibling of the typed child, at its level")
        viewModel.onElementDraftChanged("** Another child\n")
        viewModel.commitElementEdit()
        assertEquals(
            initialText.replace(
                "* TODO First task\nBody of the first task.\n\n",
                "* TODO First task\n** Typed child\n** Another child\n",
            ),
            viewModel.selectedFileContent,
        )
    }

    @Test
    fun typingAHeadingLineInADraftAddsAHeadingOnSave() = runTest {
        openFile()

        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged("* TODO First task\nBody of the first task.\n* TODO Typed\n\n")
        viewModel.commitElementEdit()

        assertEquals(
            listOf("TODO First task", "TODO Typed", "TODO Second task"),
            viewModel.selectedFileContent.let { text -> text.orgElements().drop(1).map { it.title } },
        )
    }

    @Test
    fun discardingASubheadingRemovesIt() = runTest {
        openProject()

        viewModel.addSubheading(0)
        viewModel.cancelElementEdit()

        assertEquals(projectText, viewModel.selectedFileContent)
    }
}
