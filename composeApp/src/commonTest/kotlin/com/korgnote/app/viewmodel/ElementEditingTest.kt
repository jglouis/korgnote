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
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(path to initialText))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, repository), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openFile() {
        viewModel.server.load()
        viewModel.onFileSelected(path)
    }

    @Test
    fun addingAHeadingAtTheEndSavesIt() = runTest {
        openFile()

        viewModel.editor.addAtEnd()
        viewModel.editor.onDraftChanged("* TODO Buy milk\n")
        viewModel.editor.commit()

        assertTrue(viewModel.note.content.endsWith("* TODO Buy milk\n"), viewModel.note.content)
        assertEquals(initialText + "* TODO Buy milk\n", viewModel.note.content)
        assertEquals(viewModel.note.content, repository.saves.last().second)
        assertNull(viewModel.editor.editingIndex)
    }

    @Test
    fun addingAHeadingAfterAnElementSavesItInPlace() = runTest {
        openFile()
        val firstTask = 1 // preamble, first task, second task

        viewModel.editor.addAfter(firstTask)
        viewModel.editor.onDraftChanged("* TODO Inserted\n")
        viewModel.editor.commit()

        assertEquals(
            initialText.replace("\n* TODO Second task", "\n* TODO Inserted\n* TODO Second task"),
            viewModel.note.content,
        )
        assertEquals(viewModel.note.content, repository.saves.last().second)
    }

    @Test
    fun cancellingAnAddedHeadingRemovesItAgain() = runTest {
        openFile()

        viewModel.editor.addAtEnd()
        viewModel.editor.onDraftChanged("* TODO Never mind\n")
        viewModel.editor.cancel()

        assertEquals(initialText, viewModel.note.content)
        assertTrue(repository.saves.isEmpty(), "cancelling must not write anything")
        assertNull(viewModel.editor.editingIndex)
    }

    @Test
    fun editingAnExistingElementSavesOnlyThatElement() = runTest {
        openFile()

        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* TODO First task\nRewritten body.\n\n")
        viewModel.editor.commit()

        assertEquals(
            initialText.replace("Body of the first task.", "Rewritten body."),
            viewModel.note.content,
        )
        assertEquals(viewModel.note.content, repository.saves.last().second)
    }

    @Test
    fun cancellingAnExistingElementLeavesTheDocumentAlone() = runTest {
        openFile()

        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* TODO First task\nDiscarded.\n")
        viewModel.editor.cancel()

        assertEquals(initialText, viewModel.note.content)
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun theCaretStartsAfterTheStarsOfANewHeading() = runTest {
        openFile()

        viewModel.editor.addAtEnd()

        assertEquals("* \n", viewModel.editor.draft)
        assertEquals(2, viewModel.editor.draftCaret)
    }

    @Test
    fun addingTwoHeadingsInARowKeepsBoth() = runTest {
        openFile()

        viewModel.editor.addAtEnd()
        viewModel.editor.onDraftChanged("* TODO One\n")
        viewModel.editor.commit()

        viewModel.editor.addAtEnd()
        viewModel.editor.onDraftChanged("* TODO Two\n")
        viewModel.editor.commit()

        assertEquals(initialText + "* TODO One\n* TODO Two\n", viewModel.note.content)
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
        viewModel.server.load()
        viewModel.onFileSelected("project.org")
    }

    @Test
    fun addingBelowAHeadingGoesAfterItsWholeSubtree() = runTest {
        openProject()

        viewModel.editor.addAfter(0) // Project
        viewModel.editor.onDraftChanged("* Sibling\n")
        viewModel.editor.commit()

        assertEquals(
            projectText.replace("* Other", "* Sibling\n* Other"),
            viewModel.note.content,
            "a sibling inserted before the steps would have taken them over",
        )
    }

    @Test
    fun addingASubheadingMakesAFirstChild() = runTest {
        openProject()

        viewModel.editor.addSubheading(0)
        assertEquals("** \n", viewModel.editor.draft)
        viewModel.editor.onDraftChanged("** Step zero\n")
        viewModel.editor.commit()

        assertEquals(
            projectText.replace("** Step one", "** Step zero\n** Step one"),
            viewModel.note.content,
        )
    }

    @Test
    fun addingBelowThePreambleMakesTheFirstHeading() = runTest {
        openFile()

        viewModel.editor.addAfter(0)
        viewModel.editor.onDraftChanged("* TODO Zeroth task\n")
        viewModel.editor.commit()

        assertEquals(
            initialText.replace("\n* TODO First task", "\n* TODO Zeroth task\n* TODO First task"),
            viewModel.note.content,
        )
    }

    @Test
    fun savingAndAddingBelowKeepsTheDraftAndOpensASibling() = runTest {
        openFile()

        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* TODO First task\nEdited.\n\n")
        viewModel.editor.commitAndAddBelow()

        val saved = initialText.replace("Body of the first task.", "Edited.")
        assertEquals(saved, repository.saves.last().second, "the draft is saved before anything else")
        assertEquals(2, viewModel.editor.editingIndex)
        assertEquals("* \n", viewModel.editor.draft)

        viewModel.editor.onDraftChanged("* TODO Right after\n")
        viewModel.editor.commit()
        assertEquals(
            saved.replace("\n* TODO Second task", "\n* TODO Right after\n* TODO Second task"),
            viewModel.note.content,
        )
    }

    @Test
    fun savingAndAddingBelowFollowsTheLastHeadingInTheDraft() = runTest {
        openFile()

        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* TODO First task\n** Typed child\n")
        viewModel.editor.commitAndAddBelow()

        assertEquals("** \n", viewModel.editor.draft, "a sibling of the typed child, at its level")
        viewModel.editor.onDraftChanged("** Another child\n")
        viewModel.editor.commit()
        assertEquals(
            initialText.replace(
                "* TODO First task\nBody of the first task.\n\n",
                "* TODO First task\n** Typed child\n** Another child\n",
            ),
            viewModel.note.content,
        )
    }

    @Test
    fun typingAHeadingLineInADraftAddsAHeadingOnSave() = runTest {
        openFile()

        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* TODO First task\nBody of the first task.\n* TODO Typed\n\n")
        viewModel.editor.commit()

        assertEquals(
            listOf("TODO First task", "TODO Typed", "TODO Second task"),
            viewModel.note.content.let { text -> text.orgElements().drop(1).map { it.title } },
        )
    }

    @Test
    fun discardingASubheadingRemovesIt() = runTest {
        openProject()

        viewModel.editor.addSubheading(0)
        viewModel.editor.cancel()

        assertEquals(projectText, viewModel.note.content)
    }

    // --- Deleting a heading ---

    @Test
    fun deletingAHeadingRemovesItsSubtreeAndSaves() = runTest {
        openProject()

        assertTrue(viewModel.editor.deleteSection(0))

        assertEquals("* Other\n", viewModel.note.content)
        assertEquals("* Other\n", repository.files["project.org"], "pushed like any edit")
    }

    @Test
    fun deletingASubheadingLeavesItsParentAndSiblings() = runTest {
        openProject()

        viewModel.editor.deleteSection(1) // Step one

        assertEquals(projectText.replace("** Step one\n", ""), viewModel.note.content)
    }

    @Test
    fun deletingIsRefusedWhileAnElementIsBeingEdited() = runTest {
        openProject()
        viewModel.editor.beginEdit(3)

        assertFalse(viewModel.editor.deleteSection(1))

        assertEquals(projectText, viewModel.note.content)
        assertEquals(3, viewModel.editor.editingIndex, "the draft is left alone")
    }

    @Test
    fun thePreambleCannotBeDeleted() = runTest {
        openFile()

        assertFalse(viewModel.editor.deleteSection(0))
        assertEquals(initialText, viewModel.note.content)
        assertTrue(repository.saves.isEmpty())
    }

    // --- Moving, promoting and demoting a heading ---
    // Elements of projectText: Project, Step one, Step two, Other.

    @Test
    fun movingAHeadingDownTakesItsSubtreePastTheNextSibling() = runTest {
        openProject()

        assertTrue(viewModel.editor.moveSection(0, up = false))

        assertEquals("* Other\n* Project\nProject body.\n** Step one\n** Step two\n", viewModel.note.content)
        assertEquals(viewModel.note.content, repository.files["project.org"], "pushed like any edit")
        assertEquals(1, viewModel.note.revealElementIndex, "kept in sight where it went")
    }

    @Test
    fun movingASubheadingUpStaysUnderItsParent() = runTest {
        openProject()

        assertTrue(viewModel.editor.moveSection(2, up = true))
        assertEquals(projectText.replace("** Step one\n** Step two", "** Step two\n** Step one"), viewModel.note.content)

        assertFalse(viewModel.editor.moveSection(1, up = true), "the first child has no sibling above")
        assertFalse(viewModel.editor.moveSection(2, up = false), "nor the last one below")
    }

    @Test
    fun foldsGoWithAMovedSubtree() = runTest {
        openProject()
        viewModel.folds.toggle(0) // Project

        viewModel.editor.moveSection(0, up = false)

        assertEquals(setOf(1), viewModel.folds.folded)
    }

    @Test
    fun promotingAndDemotingRestarTheSubtree() = runTest {
        openProject()

        assertTrue(viewModel.editor.demoteSection(0))
        assertEquals(
            "** Project\nProject body.\n*** Step one\n*** Step two\n* Other\n",
            viewModel.note.content,
        )
        assertTrue(viewModel.editor.promoteSection(0))
        assertEquals(projectText, viewModel.note.content)
        assertFalse(viewModel.editor.promoteSection(0), "a level-1 heading has nowhere to go")
    }

    @Test
    fun aFoldSurvivesItsHeadingBeingDemoted() = runTest {
        openProject()
        viewModel.folds.toggle(0)

        viewModel.editor.demoteSection(0)

        assertEquals(setOf(0), viewModel.folds.folded, "the heading line changed, the fold did not")
    }

    @Test
    fun tappingATodoKeywordMarksItDoneAndSavesIt() = runTest {
        openFile()

        viewModel.cycleTodoAt(initialText.indexOf("* TODO Second"), this)
        testScheduler.advanceUntilIdle()

        assertEquals(initialText.replace("* TODO Second", "* DONE Second"), viewModel.note.content)
        assertEquals(viewModel.note.content, repository.saves.last().second)
    }

    @Test
    fun tappingADoneKeywordGoesBackToTodoRatherThanClearingIt() = runTest {
        openFile()
        val heading = initialText.indexOf("* TODO First")

        viewModel.cycleTodoAt(heading, this)
        viewModel.cycleTodoAt(heading, this)
        testScheduler.advanceUntilIdle()

        assertEquals(initialText, viewModel.note.content)
    }

    @Test
    fun restructuringIsRefusedWhileAnElementIsBeingEdited() = runTest {
        openProject()
        viewModel.editor.beginEdit(3)

        assertFalse(viewModel.editor.moveSection(0, up = false))
        assertFalse(viewModel.editor.demoteSection(0))

        assertEquals(projectText, viewModel.note.content)
        assertEquals(3, viewModel.editor.editingIndex)
    }
}
