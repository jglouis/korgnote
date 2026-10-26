package com.korgnote.app.viewmodel

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import com.korgnote.common.org.edit.FileKeywords
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
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
    private lateinit var viewModel: NotesViewModel
    private var inbox = "inbox"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(mutableMapOf(existing to existingText))
        store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(
            SyncEngine(store, server),
            store,
            today = { LocalDate(2026, 10, 26) },
            newNotesFolder = { inbox },
            noteTemplate = { NoteTemplateSettings(author = "Me", extraHeader = "#+STARTUP: overview") },
            newId = { "id-1" },
        )
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
        viewModel.server.load()

        val path = viewModel.createNote("Call the plumber", tags = listOf("home", "urgent"))

        assertEquals("inbox/call_the_plumber.org", path)
        assertEquals("Selected: inbox/call_the_plumber.org", viewModel.note.label)
        assertTrue("inbox/call_the_plumber.org" in viewModel.library.tree.paths())
        val head = ":PROPERTIES:\n:ID:       id-1\n:END:\n#+TITLE: Call the plumber\n#+AUTHOR: Me\n" +
            "#+DATE: [2026-10-26 Mon]\n#+filetags: :home:urgent:\n#+STARTUP: overview\n\n"
        assertEquals(head, server.files[path], "pushed without waiting for an edit")
        assertEquals(1, viewModel.editor.editingIndex, "straight into the first heading")
        assertEquals("* \n", viewModel.editor.draft)

        viewModel.editor.onDraftChanged("* TODO Ask about the boiler\n")
        viewModel.editor.commit()
        assertEquals(head + "* TODO Ask about the boiler\n", server.files[path])
    }

    @Test
    fun aNoteCreatedOfflineReachesTheServerOnTheNextSync() = runTest {
        viewModel.server.load()
        server.online = false

        val path = viewModel.createNote("Offline idea")
        assertNotNull(path)
        assertTrue(path in viewModel.library.tree.paths(), "it exists here at once")
        assertNull(server.files[path])

        server.online = true
        viewModel.server.load()
        assertTrue(server.files[path]!!.contains("#+TITLE: Offline idea\n"))
    }

    @Test
    fun todayOpensTheDailyCreatingItInTheDailyFolderTheFirstTime() = runTest {
        viewModel.server.load()

        viewModel.openToday()

        val path = "daily/2026-10-26.org"
        assertEquals("Selected: $path", viewModel.note.label)
        assertTrue(server.files[path]!!.startsWith(":PROPERTIES:\n:ID:       id-1\n:END:\n#+TITLE: 2026-10-26\n"))
        assertEquals(1, viewModel.editor.editingIndex, "a new daily starts its first heading")
        viewModel.editor.onDraftChanged("* Standup\n")
        viewModel.editor.commit()

        viewModel.onFileSelected(existing)
        viewModel.openToday()
        assertEquals("Selected: $path", viewModel.note.label)
        assertNull(viewModel.editor.editingIndex, "one that exists is only opened")
        assertTrue(server.files[path]!!.endsWith("* Standup\n"), "and left as it was")
    }

    @Test
    fun theDetailsFormRewritesTheNotesKeywordsAndSavesIt() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(existing)
        assertEquals("Notes", viewModel.openNoteKeywords().title)

        viewModel.setOpenNoteKeywords(FileKeywords(title = "Notes", author = "Me", tags = listOf("home")))

        assertEquals("#+TITLE: Notes\n#+AUTHOR: Me\n#+filetags: :home:\n\n* TODO Something\n", server.files[existing])
    }

    @Test
    fun aNoteIsNotCreatedOverAnother() = runTest {
        inbox = ""
        viewModel.server.load()

        assertNull(viewModel.createNote("Notes"))

        assertEquals(existingText, server.files[existing])
        assertTrue(viewModel.lastErrorMessage.contains("already"), viewModel.lastErrorMessage)
    }

    @Test
    fun aTitleWithNothingInItIsRefused() = runTest {
        viewModel.server.load()

        assertNull(viewModel.createNote("  ?! "))
        assertNull(viewModel.newNotePathFor(""))
    }

    @Test
    fun renamingTheOpenNoteMovesItEverywhereAndKeepsItOpen() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(existing)
        viewModel.folds.toggle(1)

        assertTrue(viewModel.renameNote(existing, "archive/old notes"))

        val target = "archive/old notes.org"
        assertEquals(existingText, server.files[target])
        assertNull(server.files[existing])
        assertEquals(listOf(target), viewModel.library.tree.paths())
        assertEquals("Selected: $target", viewModel.note.label)
        assertEquals(existingText, viewModel.note.content)
        assertEquals(setOf(1), viewModel.folds.folded, "the fold moved with the note")
    }

    @Test
    fun renamingKeepsAnUnsyncedEdit() = runTest {
        viewModel.server.load()
        server.online = false
        store.save(existing, "#+TITLE: Notes\n\n* TODO Edited offline\n")

        assertTrue(viewModel.renameNote(existing, "renamed.org"))
        server.online = true
        viewModel.server.load()

        assertEquals("#+TITLE: Notes\n\n* TODO Edited offline\n", server.files["renamed.org"])
        assertNull(server.files[existing])
    }

    @Test
    fun renamingRefusesATakenOrInvalidName() = runTest {
        server.files["other.org"] = "* Other\n"
        viewModel.server.load()

        assertFalse(viewModel.renameNote(existing, "other.org"))
        assertFalse(viewModel.renameNote(existing, "../outside.org"))

        assertEquals(existingText, server.files[existing])
        assertEquals("* Other\n", server.files["other.org"])
    }

    @Test
    fun deletingTheOpenNoteRemovesItFromBothSidesAndOpensAnother() = runTest {
        server.files["other.org"] = "* Other\n"
        viewModel.server.load()
        viewModel.onFileSelected(existing)

        viewModel.deleteNote(existing)

        assertNull(server.files[existing])
        assertEquals(listOf("other.org"), viewModel.library.tree.paths())
        assertEquals("Selected: other.org", viewModel.note.label)
        viewModel.server.load()
        assertEquals(listOf("other.org"), viewModel.library.tree.paths(), "it does not come back")
    }

    @Test
    fun aDeleteWarnsAboutUnsyncedEditsAndMadeOfflineStillHappens() = runTest {
        viewModel.server.load()
        assertFalse(viewModel.hasUnsyncedChanges(existing))
        server.online = false
        store.save(existing, "#+TITLE: Notes\n\n* Unsynced\n")
        assertTrue(viewModel.hasUnsyncedChanges(existing))

        viewModel.deleteNote(existing)
        assertEquals(emptyList(), viewModel.library.tree.paths())
        server.online = true
        viewModel.server.load()

        assertNull(server.files[existing])
        assertEquals(emptyList(), viewModel.library.tree.paths())
    }

    @Test
    fun deletingTheLastNoteLeavesNothingSelected() = runTest {
        viewModel.server.load()
        viewModel.deleteNote(existing)

        assertEquals("No file selected", viewModel.note.label)
        assertEquals("", viewModel.note.content)
    }
}
