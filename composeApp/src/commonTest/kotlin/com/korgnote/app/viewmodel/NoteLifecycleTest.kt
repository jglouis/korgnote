package com.korgnote.app.viewmodel

import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
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
 * Creating, renaming and deleting notes from the app (T20), through the view model and the sync
 * engine to a fake server. The reconciliation rules themselves are tested in `SyncEngineTests`;
 * what is tested here is that the app drives them, and what it shows afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteLifecycleTest {

    private val existing = "notes.org"
    private val existingText = "#+TITLE: Notes\n\n* TODO Something\n"

    private lateinit var server: FakeSyncApi
    private lateinit var store: DocumentStore
    private lateinit var viewModel: FileTreeViewModel
    private var inbox = "inbox"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(mutableMapOf(existing to existingText))
        store = DocumentStore(InMemoryReplicaStorage())
        viewModel = FileTreeViewModel(SyncEngine(store, server), store, newNotesFolder = { inbox })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun Node.paths(): List<String> = when (this) {
        is Folder -> children.flatMap { it.paths() }
        is File -> listOf(path)
        else -> emptyList()
    }

    @Test
    fun aNewNoteIsCreatedInTheInboxOpenedAtItsFirstHeadingAndPushed() = runTest {
        viewModel.loadFromServer()

        val path = viewModel.createNote("Call the plumber")

        assertEquals("inbox/call_the_plumber.org", path)
        assertEquals("Selected: inbox/call_the_plumber.org", viewModel.selectedFileLabel)
        assertTrue("inbox/call_the_plumber.org" in viewModel.tree.paths())
        assertEquals("#+TITLE: Call the plumber\n\n", server.files[path], "pushed without waiting for an edit")
        assertEquals(1, viewModel.editingElementIndex, "straight into the first heading")
        assertEquals("* \n", viewModel.elementDraft)

        viewModel.onElementDraftChanged("* TODO Ask about the boiler\n")
        viewModel.commitElementEdit()
        assertEquals("#+TITLE: Call the plumber\n\n* TODO Ask about the boiler\n", server.files[path])
    }

    @Test
    fun aNoteCreatedOfflineReachesTheServerOnTheNextSync() = runTest {
        viewModel.loadFromServer()
        server.online = false

        val path = viewModel.createNote("Offline idea")
        assertNotNull(path)
        assertTrue(path in viewModel.tree.paths(), "it exists here at once")
        assertNull(server.files[path])

        server.online = true
        viewModel.loadFromServer()
        assertEquals("#+TITLE: Offline idea\n\n", server.files[path])
    }

    @Test
    fun aNoteIsNotCreatedOverAnother() = runTest {
        inbox = ""
        viewModel.loadFromServer()

        assertNull(viewModel.createNote("Notes"))

        assertEquals(existingText, server.files[existing])
        assertTrue(viewModel.lastErrorMessage.contains("already"), viewModel.lastErrorMessage)
    }

    @Test
    fun aTitleWithNothingInItIsRefused() = runTest {
        viewModel.loadFromServer()

        assertNull(viewModel.createNote("  ?! "))
        assertNull(viewModel.newNotePathFor(""))
    }

    @Test
    fun renamingTheOpenNoteMovesItEverywhereAndKeepsItOpen() = runTest {
        viewModel.loadFromServer()
        viewModel.onFileSelected(existing)
        viewModel.toggleFold(1)

        assertTrue(viewModel.renameNote(existing, "archive/old notes"))

        val target = "archive/old notes.org"
        assertEquals(existingText, server.files[target])
        assertNull(server.files[existing])
        assertEquals(listOf(target), viewModel.tree.paths())
        assertEquals("Selected: $target", viewModel.selectedFileLabel)
        assertEquals(existingText, viewModel.selectedFileContent)
        assertEquals(setOf(1), viewModel.foldedElements, "the fold moved with the note")
    }

    @Test
    fun renamingKeepsAnUnsyncedEdit() = runTest {
        viewModel.loadFromServer()
        server.online = false
        store.save(existing, "#+TITLE: Notes\n\n* TODO Edited offline\n")

        assertTrue(viewModel.renameNote(existing, "renamed.org"))
        server.online = true
        viewModel.loadFromServer()

        assertEquals("#+TITLE: Notes\n\n* TODO Edited offline\n", server.files["renamed.org"])
        assertNull(server.files[existing])
    }

    @Test
    fun renamingRefusesATakenOrInvalidName() = runTest {
        server.files["other.org"] = "* Other\n"
        viewModel.loadFromServer()

        assertFalse(viewModel.renameNote(existing, "other.org"))
        assertFalse(viewModel.renameNote(existing, "../outside.org"))

        assertEquals(existingText, server.files[existing])
        assertEquals("* Other\n", server.files["other.org"])
    }

    @Test
    fun deletingTheOpenNoteRemovesItFromBothSidesAndOpensAnother() = runTest {
        server.files["other.org"] = "* Other\n"
        viewModel.loadFromServer()
        viewModel.onFileSelected(existing)

        viewModel.deleteNote(existing)

        assertNull(server.files[existing])
        assertEquals(listOf("other.org"), viewModel.tree.paths())
        assertEquals("Selected: other.org", viewModel.selectedFileLabel)
        viewModel.loadFromServer()
        assertEquals(listOf("other.org"), viewModel.tree.paths(), "it does not come back")
    }

    @Test
    fun aDeleteWarnsAboutUnsyncedEditsAndMadeOfflineStillHappens() = runTest {
        viewModel.loadFromServer()
        assertFalse(viewModel.hasUnsyncedChanges(existing))
        server.online = false
        store.save(existing, "#+TITLE: Notes\n\n* Unsynced\n")
        assertTrue(viewModel.hasUnsyncedChanges(existing))

        viewModel.deleteNote(existing)
        assertEquals(emptyList(), viewModel.tree.paths())
        server.online = true
        viewModel.loadFromServer()

        assertNull(server.files[existing])
        assertEquals(emptyList(), viewModel.tree.paths())
    }

    @Test
    fun deletingTheLastNoteLeavesNothingSelected() = runTest {
        viewModel.loadFromServer()
        viewModel.deleteNote(existing)

        assertEquals("No file selected", viewModel.selectedFileLabel)
        assertEquals("", viewModel.selectedFileContent)
    }
}
