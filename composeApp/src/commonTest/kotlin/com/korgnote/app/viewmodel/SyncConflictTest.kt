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
        vm.saveCurrentFile()

        assertTrue(vm.note.content.contains("from Emacs"), vm.note.content)
        assertTrue(vm.note.content.contains("from the phone"), vm.note.content)

        vm.note.content = vm.note.content.replace("TODO Second", "DONE Second")
        vm.saveCurrentFile()
        assertTrue(api.files[path]!!.contains("from Emacs"), "the Emacs edit survives the next save")
    }

    @Test
    fun aSaveThatConflictsShowsTheServersVersionAndSaysWhy() = runTest {
        vm.server.load()
        api.files[path] = original.replace("TODO First", "WAITING First")

        vm.note.content = original.replace("TODO First", "DONE First")
        vm.saveCurrentFile()

        assertEquals(original.replace("TODO First", "WAITING First"), vm.note.content)
        assertTrue(vm.lastErrorMessage.contains("server's version is kept"), vm.lastErrorMessage)
        assertEquals(original.replace("TODO First", "DONE First"), store.read("notes.sync-conflict-20260921-101500.org"))
    }
}
