package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconciliation table from `docs/05-sync-protocol.md`, one case per row.
 *
 * The fake server counts the requests it is asked for, which is how "a cold start fetches only what
 * it lacks" becomes something a test can assert rather than something the code claims.
 */
class SyncEngineTests {

    /** A server that behaves like the v1 API, and remembers what was asked of it. */
    private class FakeServer(
        val files: MutableMap<String, Pair<String, String>> = mutableMapOf(),
    ) : SyncApi {
        var generation = 1L
        val fetched = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var manifestRequests = 0
        var deletedPaths = mutableListOf<String>()
        var completeManifests = true

        /** Events the stream will deliver once, then finish, so tests are not left waiting. */
        val pending = mutableListOf<Pair<String, Boolean>>()

        override suspend fun events(onChange: suspend (String, Boolean) -> Unit) {
            pending.forEach { (path, deleted) -> onChange(path, deleted) }
            pending.clear()
        }

        fun put(path: String, text: String, etag: String = "e-$path-${text.length}") {
            files[path] = etag to text
        }

        override suspend fun manifest(since: Long?): RemoteManifest {
            manifestRequests++
            return RemoteManifest(
                generation = generation,
                complete = completeManifests,
                entries = files.map { (path, value) -> RemoteEntry(path, value.first) },
                deleted = deletedPaths.toList(),
            )
        }

        override suspend fun fetch(path: String): RemoteFile? {
            fetched.add(path)
            val (etag, text) = files[path] ?: return null
            return RemoteFile(path, etag, text)
        }

        override suspend fun push(path: String, content: String, ifMatch: String?): PushResult {
            pushed.add(path)
            val current = files[path]
            if (ifMatch != null && current != null && current.first != ifMatch) {
                return PushResult.Conflict(current.first)
            }
            val etag = "e-$path-${content.length}-pushed"
            files[path] = etag to content
            generation++
            return PushResult.Pushed(etag)
        }

        override suspend fun delete(path: String, ifMatch: String): Boolean {
            deleted.add(path)
            files.remove(path)
            generation++
            return true
        }
    }

    private fun setUp(): Triple<FakeServer, DocumentStore, SyncEngine> {
        val server = FakeServer()
        val store = DocumentStore(InMemoryReplicaStorage())
        return Triple(server, store, SyncEngine(store, server))
    }

    /** "Cold start transfers a manifest plus only stale files." */
    @Test
    fun aColdStartFetchesEverythingOnceAndThenNothing() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        server.put("b.org", "* B\n")

        val first = engine.sync()
        assertContentEquals(listOf("a.org", "b.org"), first.pulled.sorted())
        assertEquals(listOf("a.org", "b.org"), server.fetched.sorted())

        // Nothing moved on either side: a second cycle must transfer no file at all.
        server.fetched.clear()
        val second = engine.sync()
        assertEquals(emptyList(), server.fetched, "an unchanged file must not be re-fetched")
        assertFalse(second.changedAnything)
        assertEquals("* A\n", store.read("a.org"))
    }

    @Test
    fun onlyTheChangedFileIsFetched() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        server.put("b.org", "* B\n")
        engine.sync()
        server.fetched.clear()

        server.put("b.org", "* B changed\n")
        val report = engine.sync()

        assertEquals(listOf("b.org"), server.fetched)
        assertEquals(listOf("b.org"), report.pulled)
        assertEquals("* B changed\n", store.read("b.org"))
    }

    @Test
    fun aLocalEditIsPushedAndStopsBeingDirty() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.save("a.org", "* A edited\n")
        val report = engine.sync()

        assertEquals(listOf("a.org"), report.pushed)
        assertEquals("* A edited\n", server.files["a.org"]?.second)
        assertFalse(store.isDirty("a.org"), "a pushed file is no longer dirty")
        assertEquals("* A edited\n", store.base("a.org"), "the base moves with the push")
    }

    /** Both sides moved: keep the local edit, flag it, and never overwrite. */
    @Test
    fun aDivergedFileIsFlaggedAndTheLocalEditIsKept() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.save("a.org", "* Edited here\n")
        server.put("a.org", "* Edited elsewhere\n")

        val report = engine.sync()

        assertEquals(listOf("a.org"), report.conflicted)
        assertEquals("* Edited here\n", store.read("a.org"), "the local edit must survive")
        assertEquals(listOf("a.org"), store.conflictedPaths())
        assertEquals(emptyList(), report.pulled, "a conflict must not silently pull over the edit")
    }

    @Test
    fun aFileDeletedOnTheServerIsRemovedLocally() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        server.files.remove("a.org")
        val report = engine.sync()

        assertEquals(listOf("a.org"), report.deletedRemotely)
        assertNull(store.read("a.org"))
    }

    /** The row that exists to protect work: deleted remotely, edited here. */
    @Test
    fun aFileDeletedRemotelyButEditedHereIsPushedBack() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.save("a.org", "* Still wanted\n")
        server.files.remove("a.org")

        val report = engine.sync()

        assertEquals(listOf("a.org"), report.pushed)
        assertEquals("* Still wanted\n", server.files["a.org"]?.second, "the edit is restored")
        assertEquals("* Still wanted\n", store.read("a.org"))
    }

    @Test
    fun aNoteCreatedOfflineIsPushedOnTheNextSync() = runTest {
        val (server, store, engine) = setUp()
        store.save("new.org", "* Captured offline\n")

        val report = engine.sync()

        assertEquals(listOf("new.org"), report.pushed)
        assertEquals("* Captured offline\n", server.files["new.org"]?.second)
        assertFalse(store.isDirty("new.org"))
    }

    /** A delta manifest says what changed, not what exists: absence must not delete anything. */
    @Test
    fun aDeltaManifestNeverDeletesLocalFiles() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        server.put("b.org", "* B\n")
        engine.sync()

        server.completeManifests = false
        server.files.remove("b.org")
        val report = engine.sync()

        assertEquals(emptyList(), report.deletedRemotely)
        assertEquals("* B\n", store.read("b.org"), "a delta must not be read as a deletion")
    }

    @Test
    fun anExplicitDeletionInADeltaIsApplied() = runTest {
        val (server, store, engine) = setUp()
        server.put("gone.org", "* Gone\n")
        engine.sync()

        server.completeManifests = false
        server.files.remove("gone.org")
        server.deletedPaths.add("gone.org")
        val report = engine.sync()

        assertEquals(listOf("gone.org"), report.deletedRemotely)
        assertNull(store.read("gone.org"))
    }

    /** An SSE hint must not clobber something being typed. */
    @Test
    fun atargetedPullSkipsAFileWithLocalEdits() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()
        store.save("a.org", "* Being edited\n")

        server.put("a.org", "* From elsewhere\n")
        assertFalse(engine.pull("a.org"))
        assertEquals("* Being edited\n", store.read("a.org"))
    }

    @Test
    fun atargetedPullAppliesWhenNothingIsLocal() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")

        assertTrue(engine.pull("a.org"))
        assertEquals("* A\n", store.read("a.org"))
    }

    /** The SSE path: a hint arrives and the replica follows it. */
    @Test
    fun anEventStreamHintPullsTheChangedFile() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        server.put("a.org", "* Changed in Emacs\n")
        val applied = mutableListOf<String>()
        server.pending.add("a.org" to false)
        engine.watch { applied.add(it) }

        assertEquals(listOf("a.org"), applied)
        assertEquals("* Changed in Emacs\n", store.read("a.org"))
    }

    /** A hint about a file being edited locally must not overwrite it. */
    @Test
    fun anEventStreamHintSkipsAFileWithLocalEdits() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()
        store.save("a.org", "* Mine\n")

        server.put("a.org", "* Theirs\n")
        val applied = mutableListOf<String>()
        server.pending.add("a.org" to false)
        engine.watch { applied.add(it) }

        assertEquals(emptyList(), applied)
        assertEquals("* Mine\n", store.read("a.org"))
    }

    /** An edit made while the push was in flight must not be marked as synced. */
    @Test
    fun anEditDuringAPushStaysDirty() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()
        store.save("a.org", "* First edit\n")

        store.markPushed("a.org", "some-etag", pushedContent = "* A different content\n")

        assertTrue(store.isDirty("a.org"), "the newer edit is still unsynced")
        assertEquals("* First edit\n", store.read("a.org"))
    }
}
