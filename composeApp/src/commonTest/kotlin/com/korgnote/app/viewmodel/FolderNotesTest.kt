package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.NoteFolder
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notes as a folder of the user's that other apps write to, with no server anywhere. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderNotesTest {

    /** The folder, in memory; another app writes to it with [writeOutside]. */
    private class Folder(vararg files: Pair<String, String>) : NoteFolder {
        private var clock = 0L
        val files = files.associate { (path, text) -> path to (text to FileStamp(++clock, text.length.toLong())) }.toMutableMap()
        var reachable = true

        fun writeOutside(path: String, text: String) {
            files[path] = text to FileStamp(++clock, text.length.toLong())
        }

        private fun check() = check(reachable) { "The folder is gone." }

        override suspend fun list() = check().let { files.map { (path, file) -> FolderEntry(path, file.second) }.sortedBy { it.path } }
        override suspend fun read(path: String) = check().let { files[path]?.first }
        override suspend fun write(path: String, content: String) =
            check().let { FileStamp(++clock, content.length.toLong()).also { files[path] = content to it } }
        override suspend fun delete(path: String) = check().let { files.remove(path); Unit }
        override suspend fun stamp(path: String) = check().let { files[path]?.second }
    }

    private lateinit var folder: Folder
    private lateinit var vm: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        folder = Folder("inbox.org" to "* TODO Call the bank\n")
        val storage = FolderNoteStorage(folder) { "20261029-120000" }
        // The sync engine is there only because the view model takes one: it is never used.
        val replica = DocumentStore(InMemoryReplicaStorage())
        vm = NotesViewModel(SyncEngine(replica, FakeSyncApi(mutableMapOf("server.org" to "* Server\n"))), DocumentStore(storage), folder = storage)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theNotesAreTheFolderAndTheServerIsNeverAsked() = runTest {
        vm.server.load()

        assertEquals(setOf("inbox.org"), vm.library.paths)
        assertEquals("inbox.org", vm.note.path)
        assertTrue(vm.server is FolderSync)
    }

    @Test
    fun aSaveGoesStraightIntoTheFolder() = runTest {
        vm.server.load()
        vm.note.content = "* DONE Call the bank\n"

        vm.saveCurrentFile()

        assertEquals("* DONE Call the bank\n", folder.files["inbox.org"]?.first)
    }

    @Test
    fun anEditMadeInTheFolderShowsOnTheNextLook() = runTest {
        vm.server.load()
        folder.writeOutside("inbox.org", "* TODO Call the bank before noon\n")

        vm.server.retry()

        assertEquals("* TODO Call the bank before noon\n", vm.note.content)
    }

    @Test
    fun aSaveOverAnEditMadeElsewhereKeepsBothAndSaysSo() = runTest {
        vm.server.load()
        folder.writeOutside("inbox.org", "* TODO Call the bank, from the laptop\n")
        vm.note.content = "* DONE Call the bank\n"

        vm.saveCurrentFile()

        assertEquals("* TODO Call the bank, from the laptop\n", folder.files["inbox.org"]?.first)
        assertEquals(listOf("inbox.sync-conflict-20261029-120000.org"), vm.library.conflicts["inbox.org"])
        assertTrue(vm.lastErrorMessage.contains("inbox.org changed in the folder"))
        // On screen at once is the folder's version, which the banner says the note holds.
        assertEquals("* TODO Call the bank, from the laptop\n", vm.note.content)
    }

    @Test
    fun aFolderThatGoesAwayIsReportedAndItsSaveKept() = runTest {
        vm.server.load()
        folder.reachable = false
        vm.note.content = "* DONE Call the bank\n"

        vm.saveCurrentFile()
        assertNotNull(vm.server.unreachable)

        folder.reachable = true
        vm.server.retry()

        assertNull(vm.server.unreachable)
        assertEquals("* DONE Call the bank\n", folder.files["inbox.org"]?.first)
    }
}
