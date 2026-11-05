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
    var failWritesOf: (String) -> Boolean = { false }
    val failReadsOf = mutableSetOf<String>()

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
        if (path in failReadsOf) throw IllegalStateException("cannot be read")
        reads++
        return files[path]?.first
    }

    override suspend fun write(path: String, content: String): FileStamp {
        check()
        if (failWritesOf(path)) throw IllegalStateException("cannot be written")
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

    /** One whose texts never stay: how every note is, in a library much bigger than memory. */
    private fun forgetful(folder: FakeFolder) = FolderNoteStorage(folder, textBudget = 0) { "20261029-120000" }

    @Test
    fun theFolderIsListedAndEachNoteReadOnce() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "sub/b.org" to "* B\n", "readme.txt" to "no")
        val storage = storage(folder)

        assertEquals(listOf("a.org", "sub/b.org"), storage.list())
        assertEquals("* A\n", storage.read("a.org"))
        assertEquals("* A\n", storage.read("a.org"))
        assertEquals("* B\n", storage.read("sub/b.org"))
        // Both were read with the listing, and neither again since.
        assertEquals(2, folder.reads)
    }

    @Test
    fun aRescanReadsOnlyWhatMoved() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "b.org" to "* B\n", "c.org" to "* C\n")
        val storage = storage(folder)
        storage.list()
        folder.writeOutside("b.org", "* B, edited\n")

        val before = folder.reads

        storage.rescan()

        assertEquals(before, folder.reads, "looking takes no reading")
        assertEquals("* B, edited\n", storage.read("b.org"))
        assertEquals("* B, edited\n", storage.read("b.org"))
        assertEquals(before + 1, folder.reads, "read when asked for, and once")
    }

    /** Syncthing writing a file back as it was moves its stamp: telling takes only what it held. */
    @Test
    fun aFileWrittenBackUnchangedIsNoConflictEvenWithItsTextGone() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "b.org" to "* B\n")
        val storage = forgetful(folder)
        assertEquals("* A\n", storage.read("a.org"))
        folder.writeOutside("a.org", "* A\n")

        storage.write("a.org", "* A, edited\n")

        assertEquals(emptyList(), storage.takeConflicts())
        assertEquals("* A, edited\n", folder.files.getValue("a.org").first)
        assertEquals(setOf("a.org", "b.org"), folder.files.keys)
    }

    /** The copy could not be written: the next try must find the same change, and not save over it. */
    @Test
    fun aConflictCopyThatCouldNotBeWrittenIsTriedAgainAndNothingIsOverwritten() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = forgetful(folder)
        assertEquals("* A\n", storage.read("a.org"))
        folder.writeOutside("a.org", "* A, by another app\n")
        folder.failWritesOf = { "conflict" in it }

        storage.write("a.org", "* A, edited here\n")

        assertEquals("* A, by another app\n", folder.files.getValue("a.org").first)
        assertEquals(emptyList(), storage.takeConflicts(), "nothing has come of it yet")

        folder.failWritesOf = { false }
        storage.rescan()

        assertEquals("* A, by another app\n", folder.files.getValue("a.org").first, "still not overwritten")
        assertEquals(setOf("* A, by another app\n", "* A, edited here\n"), folder.files.values.map { it.first }.toSet())
        assertEquals(listOf("a.org"), storage.takeConflicts())
    }

    /** It moved and cannot be read, as with a cloud file offline: that is not the same as not having changed. */
    @Test
    fun aFileThatMovedAndCannotBeReadIsNotSavedOver() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = forgetful(folder)
        assertEquals("* A\n", storage.read("a.org"))
        folder.writeOutside("a.org", "* A, by another app\n")
        folder.failReadsOf += "a.org"

        storage.write("a.org", "* A, edited here\n")

        assertEquals("* A, by another app\n", folder.files.getValue("a.org").first)

        folder.failReadsOf.clear()
        storage.rescan()

        assertEquals("* A, by another app\n", folder.files.getValue("a.org").first)
        assertEquals(listOf("a.org"), storage.takeConflicts())
    }

    /** Never reached, with a save waiting: that one save is not all there is. */
    @Test
    fun aFolderNeverListedSaysNothingOfWhatIsInIt() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = storage(folder)
        folder.reachable = false

        storage.write("new.org", "* New\n")

        assertEquals(null, storage.stamps())
    }

    /** The whole point of the conflict copy, which must survive the text not being kept. */
    @Test
    fun aFileChangedOutsideStillGoesToAConflictCopyWithItsTextGone() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = forgetful(folder)
        assertEquals("* A\n", storage.read("a.org"))
        folder.writeOutside("a.org", "* A, by another app\n")

        storage.write("a.org", "* A, edited here\n")

        assertEquals(listOf("a.org"), storage.takeConflicts())
        assertEquals("* A, by another app\n", folder.files.getValue("a.org").first, "not overwritten")
        assertEquals(setOf("* A, by another app\n", "* A, edited here\n"), folder.files.values.map { it.first }.toSet())
    }

    @Test
    fun aListingReadsNoNotes() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "b.org" to "* B\n")
        val storage = storage(folder)

        assertEquals(setOf("a.org", "b.org"), storage.stamps()?.keys)
        storage.rescan()

        assertEquals(0, folder.reads)
    }

    @Test
    fun severalAreReadAtOnceAndKept() = runTest {
        val folder = FakeFolder("a.org" to "* A\n", "b.org" to "* B\n", "c.org" to "* C\n")
        val storage = storage(folder)

        assertEquals(mapOf("a.org" to "* A\n", "b.org" to "* B\n"), storage.readSeveral(listOf("a.org", "b.org", "gone.org")))
        assertEquals(2, folder.reads)
        assertEquals("* A\n", storage.read("a.org"))
        assertEquals(2, folder.reads, "kept")
    }

    /** A save not written yet is what the note holds, wherever it is asked for from. */
    @Test
    fun aSaveTheFolderRefusedIsWhatIsRead() = runTest {
        val folder = FakeFolder("a.org" to "* A\n")
        val storage = forgetful(folder)
        assertEquals("* A\n", storage.read("a.org"))
        folder.reachable = false

        storage.write("a.org", "* Not written yet\n")

        assertEquals("* Not written yet\n", storage.read("a.org"))
        assertEquals(mapOf("a.org" to "* Not written yet\n"), storage.readSeveral(listOf("a.org")))
        assertTrue("a.org" in storage.stamps().orEmpty())
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
        // What is shown now is what was written elsewhere, without waiting for a rescan; and a
        // save made on it goes in place.
        assertEquals("* A, from the laptop\n", storage.read("a.org"))
        storage.write("a.org", "* A, from the laptop, then the phone\n")
        assertEquals("* A, from the laptop, then the phone\n", folder.files["a.org"]?.first)
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
