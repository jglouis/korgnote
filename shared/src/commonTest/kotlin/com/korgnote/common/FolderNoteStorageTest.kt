package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.NoteFolder
import com.korgnote.common.replica.isListedNotePath
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A folder in memory that other apps can write to behind the storage's back, and that can go away. */
private class FakeFolder(vararg files: Pair<String, String>) : NoteFolder {
    private var clock = 0L
    val files = files.associate { (path, text) -> path to (text to nextStamp(text)) }.toMutableMap()
    var reads = 0
    var reachable = true

    private fun nextStamp(text: String) = FileStamp(++clock, text.length.toLong())

    /** Another app writing [path]. */
    fun writeOutside(path: String, text: String) {
        files[path] = text to nextStamp(text)
    }

    private fun check() {
        if (!reachable) throw IllegalStateException("KorgNote may no longer use this folder.")
    }

    override suspend fun list(): List<FolderEntry> {
        check()
        return files.filterKeys(::isListedNotePath).map { (path, file) -> FolderEntry(path, file.second) }.sortedBy { it.path }
    }

    override suspend fun read(path: String): String? {
        check()
        reads++
        return files[path]?.first
    }

    override suspend fun write(path: String, content: String): FileStamp {
        check()
        return nextStamp(content).also { files[path] = content to it }
    }

    override suspend fun delete(path: String) {
        check()
        files.remove(path)
    }

    override suspend fun stamp(path: String): FileStamp? {
        check()
        return files[path]?.second
    }
}

class FolderNoteStorageTest {

    private fun storage(folder: FakeFolder) = FolderNoteStorage(folder) { "20261029-120000" }

    @Test
    fun theFolderIsListedAndEachNoteReadOnce() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "sub/b.org" to "* B\n", "readme.txt" to "no")
        val storage = storage(folder)

        assertEquals(listOf("a.org", "sub/b.org"), storage.list())
        assertEquals("* A\n", storage.read("a.org"))
        assertEquals("* A\n", storage.read("a.org"))
        assertEquals(1, folder.reads)
    }

    @Test
    fun hiddenFilesAndFoldersAreNotNotes() {
        assertTrue(isListedNotePath("notes/idea.org"))
        assertFalse(isListedNotePath(".stversions/idea~20261029.org"))
        assertFalse(isListedNotePath("notes/.#idea.org"))
        assertFalse(isListedNotePath("idea.org~"))
    }

    @Test
    fun aRescanNoticesAnEditMadeByAnotherApp() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        storage.read("a.org")

        assertFalse(storage.rescan())
        folder.writeOutside("a.org", "* A, from Emacs\n")
        folder.writeOutside("new.org", "* New\n")

        assertTrue(storage.rescan())
        assertEquals("* A, from Emacs\n", storage.read("a.org"))
        assertEquals(listOf("a.org", "new.org"), storage.list())
    }

    @Test
    fun aSaveOfAnUnchangedNoteGoesInPlace() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        storage.read("a.org")

        storage.write("a.org", "* A, edited\n")

        assertEquals("* A, edited\n", folder.files["a.org"]?.first)
        assertEquals(listOf("a.org"), storage.list())
        assertTrue(storage.takeConflicts().isEmpty())
        // Its own write is not news.
        assertFalse(storage.rescan())
    }

    @Test
    fun aSaveOverAnEditMadeElsewhereGoesToAConflictCopy() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        storage.read("a.org")
        folder.writeOutside("a.org", "* A, from the laptop\n")

        storage.write("a.org", "* A, from the phone\n")

        assertEquals("* A, from the laptop\n", folder.files["a.org"]?.first)
        assertEquals("* A, from the phone\n", folder.files["a.sync-conflict-20261029-120000.org"]?.first)
        assertEquals(listOf("a.org"), storage.takeConflicts())
        assertTrue(storage.takeConflicts().isEmpty())
    }

    @Test
    fun aFileWrittenBackUnchangedIsNoConflict() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        storage.read("a.org")
        // A sync app rewriting the file as it was: the stamp moves, the text does not.
        folder.writeOutside("a.org", "* A\n")

        storage.write("a.org", "* A, edited\n")

        assertEquals("* A, edited\n", folder.files["a.org"]?.first)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun aSaveTheFolderRefusesIsKeptAndWrittenOnceItIsBack() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        storage.read("a.org")
        folder.reachable = false

        storage.write("a.org", "* A, offline\n")

        assertNotNull(storage.problem)
        assertEquals("* A, offline\n", storage.read("a.org"))
        assertEquals(listOf("a.org"), storage.list())
        assertFalse(storage.rescan())

        folder.reachable = true
        storage.rescan()

        assertNull(storage.problem)
        assertEquals("* A, offline\n", folder.files["a.org"]?.first)
    }

    @Test
    fun anUnreachableFolderIsEmptyRatherThanAnError() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        folder.reachable = false
        val storage = storage(folder)

        assertEquals(emptyList(), storage.list())
        assertNull(storage.read("a.org"))
        assertNotNull(storage.problem)
    }

    @Test
    fun theStoreWorksOnTheFolderAndLeavesNothingButNotesInIt() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val store = DocumentStore(storage(folder))

        assertTrue(store.create("inbox/new.org", "* New\n"))
        assertTrue(store.rename("a.org", "b.org"))
        store.save("b.org", "* B\n")
        store.delete("inbox/new.org")

        assertEquals(mapOf("b.org" to "* B\n"), folder.files.mapValues { it.value.first })
        // Nothing to push: there is no server.
        assertFalse(store.isDirty("b.org"))
        assertEquals(listOf("b.org"), store.paths())
    }
}
