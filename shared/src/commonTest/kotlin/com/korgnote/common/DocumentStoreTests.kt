package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour of the local replica, independent of where it is stored.
 *
 * `FileSystemDocumentStoreTest` runs this same contract against a real directory, which is what
 * makes the in-memory implementation a fair stand-in for the web build and for tests.
 */
abstract class DocumentStoreContract {

    abstract suspend fun storage(): ReplicaStorage

    private suspend fun store() = DocumentStore(storage())

    @Test
    fun aSavedDocumentCanBeReadBack() = runTest {
        val store = store()
        store.save("inbox.org", "* TODO Buy milk\n")

        assertEquals("* TODO Buy milk\n", store.read("inbox.org"))
        assertContentEquals(listOf("inbox.org"), store.paths())
    }

    /** A local save is never waiting on a network, so it is dirty until something pushes it. */
    @Test
    fun savingMarksTheDocumentDirty() = runTest {
        val store = store()
        store.save("inbox.org", "* TODO Buy milk\n")

        assertTrue(store.isDirty("inbox.org"))
        assertContentEquals(listOf("inbox.org"), store.dirtyPaths())
    }

    @Test
    fun contentFromTheServerIsNotDirtyAndBecomesTheBase() = runTest {
        val store = store()
        store.acceptFromServer("inbox.org", "* From server\n", etag = "abc")

        assertFalse(store.isDirty("inbox.org"))
        assertEquals("* From server\n", store.base("inbox.org"))
        assertEquals("abc", store.meta("inbox.org")?.etag)
    }

    @Test
    fun editingAfterASyncLeavesTheBaseAlone() = runTest {
        val store = store()
        store.acceptFromServer("inbox.org", "* From server\n")
        store.save("inbox.org", "* Edited locally\n")

        assertEquals("* Edited locally\n", store.read("inbox.org"))
        assertEquals("* From server\n", store.base("inbox.org"), "the merge ancestor must survive")
        assertTrue(store.isDirty("inbox.org"))
    }

    /** What used to be asked of a bulk refresh, and is of a bundle: local work outranks what arrived. */
    @Test
    fun takingManyFilesLeavesAloneThoseWithLocalWork() = runTest {
        val store = store()
        store.acceptFromServer("edited.org", "* Edited\n", etag = "e1")
        store.acceptFromServer("deleted.org", "* Deleted\n", etag = "e1")
        store.acceptFromServer("clean.org", "* Clean\n", etag = "e1")
        store.save("edited.org", "* Edited offline\n")
        store.delete("deleted.org")

        val taken = store.acceptAllUntouched(
            listOf(
                RemoteFile("edited.org", "e2", "* From server\n"),
                RemoteFile("deleted.org", "e2", "* From server\n"),
                RemoteFile("clean.org", "e2", "* From server\n"),
                RemoteFile("new.org", "e2", "* From server\n"),
            ),
        )

        assertEquals(listOf("clean.org", "new.org"), taken)
        assertEquals("* Edited offline\n", store.read("edited.org"))
        assertTrue(store.isDirty("edited.org"))
        assertNull(store.read("deleted.org"))
        assertEquals("e1", store.meta("deleted.org")?.etag)
        assertEquals("* From server\n", store.read("clean.org"))
        assertEquals("* From server\n", store.base("new.org"))
        assertEquals("e2", store.meta("new.org")?.etag)
        assertFalse(store.isDirty("new.org"))
    }

    @Test
    fun deletingRemovesTheDocumentItsBaseAndItsMetadata() = runTest {
        val store = store()
        store.acceptFromServer("gone.org", "* Gone\n")
        store.delete("gone.org")

        assertNull(store.read("gone.org"))
        assertNull(store.base("gone.org"))
        assertNull(store.meta("gone.org"))
        assertContentEquals(emptyList(), store.paths())
    }

    @Test
    fun pathsThatEscapeTheReplicaAreRefused() = runTest {
        val store = store()
        assertFailsWith<IllegalArgumentException> { store.save("../outside.org", "x") }
        assertFailsWith<IllegalArgumentException> { store.save("/absolute.org", "x") }
        assertFailsWith<IllegalArgumentException> { store.save("a/../../b.org", "x") }
    }

    @Test
    fun anUnknownDocumentReadsAsNull() = runTest {
        assertNull(store().read("nothing.org"))
    }

    @Test
    fun keepingTheServersVersionSetsThisOneAside() = runTest {
        val store = store()
        store.acceptFromServer("a.org", "* TODO Task\n", etag = "e1")
        store.save("a.org", "* DONE Task\n")

        val copy = store.keepServerVersion("a.org", "* NEXT Task\n", "e2", "20260921-101500")

        assertEquals("a.sync-conflict-20260921-101500.org", copy)
        assertEquals("* NEXT Task\n", store.read("a.org"))
        assertEquals("* NEXT Task\n", store.base("a.org"))
        assertEquals("e2", store.meta("a.org")?.etag)
        assertFalse(store.isDirty("a.org"))
        assertEquals("* DONE Task\n", store.read(copy!!))
        assertTrue(store.isDirty(copy), "the copy is pushed like any new note")
        assertNull(store.meta(copy)?.etag)
    }

    @Test
    fun keepingTheServersVersionMakesNoCopyOfTheSameText() = runTest {
        val store = store()
        store.save("a.org", "* Same\n")

        assertNull(store.keepServerVersion("a.org", "* Same\n", "e1", "20260921-101500"))
        assertContentEquals(listOf("a.org"), store.paths())
    }

    @Test
    fun aSaveIsRefusedWhenTheNoteMovedSinceItWasRead() = runTest {
        val store = store()
        store.save("a.org", "* Read\n")
        store.save("a.org", "* Typed meanwhile\n")

        assertFalse(store.saveIfUnchanged("a.org", expected = "* Read\n", content = "* Merged\n"))
        assertEquals("* Typed meanwhile\n", store.read("a.org"))
        assertTrue(store.saveIfUnchanged("a.org", expected = "* Typed meanwhile\n", content = "* Merged\n"))
        assertEquals("* Merged\n", store.read("a.org"))
    }

    @Test
    fun usingTheCopyReplacesTheNoteAndDeletesTheCopy() = runTest {
        val store = store()
        store.acceptFromServer("a.org", "* Theirs\n", etag = "e1")
        store.acceptFromServer("a.sync-conflict-20260921-101500.org", "* Mine\n", etag = "e2")

        assertTrue(store.replaceWithCopy("a.org", "a.sync-conflict-20260921-101500.org"))

        assertEquals("* Mine\n", store.read("a.org"))
        assertTrue(store.isDirty("a.org"), "pushed as an edit of the server's version")
        assertEquals("e1", store.meta("a.org")?.etag)
        assertContentEquals(listOf("a.org"), store.paths())
        assertEquals(
            listOf("a.sync-conflict-20260921-101500.org"),
            store.tombstones().map { it.path },
            "the server deletes the copy it has",
        )
    }

    @Test
    fun usingAMissingCopyChangesNothing() = runTest {
        val store = store()
        store.save("a.org", "* Theirs\n")

        assertFalse(store.replaceWithCopy("a.org", "a.sync-conflict-20260921-101500.org"))
        assertEquals("* Theirs\n", store.read("a.org"))
    }

    @Test
    fun aResolutionReplacesTheNoteAndDeletesTheCopy() = runTest {
        val store = store()
        val copy = "a.sync-conflict-20260921-101500.org"
        store.acceptFromServer("a.org", "* Theirs\n", etag = "e1")
        store.acceptFromServer(copy, "* Mine\n", etag = "e2")

        assertTrue(store.resolveConflict("a.org", copy, "* Theirs\n", "* Mine\n", "* Theirs\n* Mine\n"))

        assertEquals("* Theirs\n* Mine\n", store.read("a.org"))
        assertTrue(store.isDirty("a.org"), "pushed as an edit of the server's version")
        assertEquals("e1", store.meta("a.org")?.etag)
        assertEquals(listOf(copy), store.tombstones().map { it.path })
    }

    @Test
    fun aResolutionThatKeepsTheNoteOnlyDeletesTheCopy() = runTest {
        val store = store()
        val copy = "a.sync-conflict-20260921-101500.org"
        store.acceptFromServer("a.org", "* Theirs\n", etag = "e1")
        store.acceptFromServer(copy, "* Mine\n", etag = "e2")

        assertTrue(store.resolveConflict("a.org", copy, "* Theirs\n", "* Mine\n", "* Theirs\n"))

        assertFalse(store.isDirty("a.org"), "nothing to push for the note")
        assertEquals(listOf(copy), store.tombstones().map { it.path })
    }

    @Test
    fun aResolutionIsRefusedWhenEitherVersionMoved() = runTest {
        val store = store()
        val copy = "a.sync-conflict-20260921-101500.org"
        store.acceptFromServer("a.org", "* Theirs, again\n", etag = "e3")
        store.acceptFromServer(copy, "* Mine\n", etag = "e2")

        assertFalse(store.resolveConflict("a.org", copy, "* Theirs\n", "* Mine\n", "* Mine\n"))
        assertFalse(store.resolveConflict("a.org", copy, "* Theirs, again\n", "* Mine, edited\n", "* Mine\n"))

        assertEquals("* Theirs, again\n", store.read("a.org"))
        assertEquals("* Mine\n", store.read(copy))
        assertEquals(emptyList(), store.tombstones())
    }
}

class InMemoryDocumentStoreTest : DocumentStoreContract() {
    override suspend fun storage(): ReplicaStorage = InMemoryReplicaStorage()
}
