package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.sync.ChunkChoice
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the reader sees when a save meets an edit made on the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictTest {

    private val path = "notes.org"
    private val original = "* TODO First\nbody one\n\n* TODO Second\nbody two\n"

    private lateinit var api: FakeSyncApi
    private lateinit var store: DocumentStore
    private lateinit var vm: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeSyncApi(mutableMapOf(path to original))
        store = DocumentStore(InMemoryReplicaStorage())
        vm = NotesViewModel(SyncEngine(store, api) { "20260921-101500" }, store)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * A save merged with an Emacs edit must show the merged text; showing only this device's, the
     * next save would push it back and quietly undo the Emacs edit.
     */
    @Test
    fun aSaveThatMergesShowsTheMergedNote() = runTest {
        vm.server.load()
        api.files[path] = original.replace("body two", "body two, from Emacs!")

        vm.note.content = original.replace("body one", "body one, from the phone")
        vm.changes.saveCurrentFile()

        assertTrue(vm.note.content.contains("from Emacs"), vm.note.content)
        assertTrue(vm.note.content.contains("from the phone"), vm.note.content)

        vm.note.content = vm.note.content.replace("TODO Second", "DONE Second")
        vm.changes.saveCurrentFile()
        assertTrue(api.files[path]!!.contains("from Emacs"), "the Emacs edit survives the next save")
    }

    @Test
    fun aSaveThatConflictsShowsTheServersVersionAndSaysWhy() = runTest {
        vm.server.load()
        api.files[path] = original.replace("TODO First", "WAITING First")

        vm.note.content = original.replace("TODO First", "DONE First")
        vm.changes.saveCurrentFile()

        assertEquals(original.replace("TODO First", "WAITING First"), vm.note.content)
        assertTrue(vm.lastErrorMessage.contains("server's version is kept"), vm.lastErrorMessage)
        assertEquals(original.replace("TODO First", "DONE First"), store.read("notes.sync-conflict-20260921-101500.org"))
    }

    private val copy = "notes.sync-conflict-20260921-101500.org"
    private val mine = original.replace("TODO First", "DONE First")
    private val theirs = original.replace("TODO First", "WAITING First")

    /** A conflict made by saving, with its copy already on the server. */
    private suspend fun conflict() {
        vm.server.load()
        api.files[path] = theirs
        vm.note.content = mine
        vm.changes.saveCurrentFile()
        vm.server.pushQuietly()
    }

    @Test
    fun aConflictShowsUntilItsCopyIsGone() = runTest {
        conflict()

        assertEquals(mapOf(path to listOf(copy)), vm.library.conflicts)
        assertEquals(path, vm.noteFiles.conflictOriginalOf(copy))
        assertNull(vm.noteFiles.conflictOriginalOf(path))
        val chunk = vm.noteFiles.conflictComparison(copy)!!.chunks.single()
        assertEquals("* WAITING First", chunk.heading)
        assertEquals(listOf("* WAITING First"), chunk.note)
        assertEquals(listOf("* DONE First"), chunk.copy)
    }

    @Test
    fun keepingTheNoteDeletesTheCopyEverywhere() = runTest {
        conflict()
        vm.onFileSelected(copy)

        vm.noteFiles.discardConflictCopy(copy)

        assertEquals(emptyMap(), vm.library.conflicts)
        assertFalse(copy in api.files, "the server deletes it too, into its trash")
        assertEquals(theirs, api.files[path])
        assertEquals(path, vm.note.path, "the reader is taken to the note")
        assertEquals(theirs, vm.note.content)
    }

    @Test
    fun usingTheCopyMakesItTheNoteEverywhere() = runTest {
        conflict()
        vm.onFileSelected(copy)

        assertTrue(vm.noteFiles.useConflictCopy(copy))

        assertEquals(emptyMap(), vm.library.conflicts)
        assertEquals(mine, api.files[path], "pushed as an edit of the server's version")
        assertFalse(copy in api.files)
        assertEquals(path, vm.note.path)
        assertEquals(mine, vm.note.content)
    }

    @Test
    fun settlingChunkByChunkMakesTheResultTheNoteEverywhere() = runTest {
        conflict()
        vm.onFileSelected(path)

        assertTrue(vm.noteFiles.resolveConflictCopy(vm.noteFiles.conflictComparison(copy)!!, listOf(ChunkChoice.Edited("* NEXT First"))))

        val resolved = original.replace("TODO First", "NEXT First")
        assertEquals(emptyMap(), vm.library.conflicts)
        assertEquals(resolved, api.files[path], "pushed as an edit of the server's version")
        assertFalse(copy in api.files)
        assertEquals(path, vm.note.path)
        assertEquals(resolved, vm.note.content)
    }

    @Test
    fun choicesMadeOnVersionsThatSinceChangedAreRefused() = runTest {
        conflict()
        val comparison = vm.noteFiles.conflictComparison(copy)!!
        store.save(copy, mine.replace("body two", "body two, edited meanwhile"))

        assertFalse(vm.noteFiles.resolveConflictCopy(comparison, listOf(ChunkChoice.Copy)))

        assertEquals(theirs, store.read(path))
        assertEquals(mapOf(path to listOf(copy)), vm.library.conflicts)
    }

    @Test
    fun onlyACopyCanBeUsed() = runTest {
        conflict()
        assertFalse(vm.noteFiles.useConflictCopy(path))
        assertEquals(theirs, api.files[path])
    }
}
