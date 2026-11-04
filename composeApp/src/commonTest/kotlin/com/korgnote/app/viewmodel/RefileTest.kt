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
import kotlin.test.assertFalse

/** Refiling from the open note, within it and into another one, as the long-press menu does. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefileTest {

    private val inbox = "inbox.org"
    private val projects = "projects.org"
    private val inboxText = "* TODO Call Sam\nRing before noon.\n** Number\n* Read the paper\n"
    private val projectsText = "#+TITLE: Projects\n* Website [0/1]\n** TODO Fix the menu\n\n* Garden\n"

    private lateinit var repository: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeSyncApi(mutableMapOf(inbox to inboxText, projects to projectsText))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, repository), store, background = UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openInbox() {
        viewModel.server.load()
        viewModel.onFileSelected(inbox)
    }

    @Test
    fun aHeadingRefiledIntoAnotherNoteLeavesThisOneAndIsSavedThere() = runTest {
        openInbox()
        val website = viewModel.changes.refileTargets(0).single { it.title == "Website" }

        viewModel.changes.refile(0, website)

        assertEquals("* Read the paper\n", viewModel.note.content)
        assertEquals("* Read the paper\n", repository.files[inbox])
        // Under Website, after its child, one level below it; its cookie counts the new task.
        assertEquals(
            "#+TITLE: Projects\n* Website [0/2]\n** TODO Fix the menu\n** TODO Call Sam\nRing before noon.\n*** Number\n\n* Garden\n",
            repository.files[projects],
        )
    }

    @Test
    fun aHeadingRefiledToANoteGoesAtItsEndAsATopLevelHeading() = runTest {
        openInbox()
        val note = viewModel.changes.refileTargets(2).single { it.isNote && it.path == projects }

        viewModel.changes.refile(2, note)

        assertEquals(projectsText + "* Read the paper\n", repository.files[projects])
        assertEquals("* TODO Call Sam\nRing before noon.\n** Number\n", repository.files[inbox])
    }

    @Test
    fun aHeadingRefiledWithinItsNoteMovesThere() = runTest {
        openInbox()
        val paper = viewModel.changes.refileTargets(0).single { it.title == "Read the paper" }

        viewModel.changes.refile(0, paper)

        assertEquals("* Read the paper\n** TODO Call Sam\nRing before noon.\n*** Number\n", repository.files[inbox])
    }

    @Test
    fun aHeadingIsNotOfferedAsAPlaceForItselfOrWhatIsUnderIt() = runTest {
        openInbox()
        val titles = viewModel.changes.refileTargets(0).filter { it.path == inbox && !it.isNote }.map { it.title }
        assertEquals(listOf("Read the paper"), titles)
    }

    @Test
    fun refilingIsRefusedWhileAnElementIsBeingEdited() = runTest {
        openInbox()
        val website = viewModel.changes.refileTargets(0).single { it.title == "Website" }
        viewModel.editor.beginEdit(2)

        viewModel.changes.refile(0, website)

        assertEquals(inboxText, viewModel.note.content)
        assertEquals(projectsText, repository.files[projects])
        assertFalse(viewModel.editor.editingIndex == null)
    }
}
