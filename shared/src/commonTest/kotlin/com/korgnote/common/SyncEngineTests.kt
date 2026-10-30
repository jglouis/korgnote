package com.korgnote.common

import com.korgnote.common.replica.DeleteResult
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

        /**
         * When set, a delta manifest lists only these paths, as the real server's journal does: a
         * delta names what moved on the server, so a file only edited locally is absent from it.
         */
        var deltaEntries: Set<String>? = null

        /** Events the stream will deliver once, then finish, so tests are not left waiting. */
        val pending = mutableListOf<Pair<String, Boolean>>()

        override suspend fun events(onChange: suspend (String, Boolean) -> Unit) {
            pending.forEach { (path, deleted) -> onChange(path, deleted) }
            pending.clear()
        }

        fun put(path: String, text: String, etag: String = "e-$path-${text.hashCode()}") {
            files[path] = etag to text
        }

        override suspend fun manifest(since: Long?): RemoteManifest {
            manifestRequests++
            return RemoteManifest(
                generation = generation,
                complete = completeManifests,
                entries = files
                    .filterKeys { completeManifests || deltaEntries?.contains(it) != false }
                    .map { (path, value) -> RemoteEntry(path, value.first) },
                deleted = deletedPaths.toList(),
            )
        }

        override suspend fun fetch(path: String): RemoteFile? {
            fetched.add(path)
            val (etag, text) = files[path] ?: return null
            return RemoteFile(path, etag, text)
        }

        /** Whether this server bundles, and how many files a bundle stops at, as the real one caps it. */
        var bundles = false
        var bundleCap = Int.MAX_VALUE
        var bundleRequests = 0

        override suspend fun bundle(since: Long?): List<RemoteFile>? {
            if (!bundles) return null
            bundleRequests++
            return files.entries.sortedBy { it.key }.take(bundleCap).map { (path, value) -> RemoteFile(path, value.first, value.second) }
        }

        /** Every write in the order it arrived, as `PUT path` or `DELETE path`. */
        val log = mutableListOf<String>()

        /** Runs once, just before the next push is decided: an edit landing after the manifest. */
        var beforeNextPush: (() -> Unit)? = null

        override suspend fun push(path: String, content: String, ifMatch: String?, mustNotExist: Boolean): PushResult {
            beforeNextPush?.also { beforeNextPush = null }?.invoke()
            pushed.add(path)
            val current = files[path]
            if (ifMatch != null && current != null && current.first != ifMatch) {
                return PushResult.Conflict(current.first)
            }
            if (mustNotExist && current != null) return PushResult.Conflict(current.first)
            log.add("PUT $path")
            val etag = "e-$path-${content.hashCode()}"
            files[path] = etag to content
            generation++
            return PushResult.Pushed(etag)
        }

        /** A delete takes this long to answer, as over a network: long enough for cycles to overlap. */
        var deleteLatency = 0L
        var deleteRequests = 0

        override suspend fun delete(path: String, ifMatch: String): DeleteResult {
            deleteRequests++
            if (deleteLatency > 0) delay(deleteLatency)
            val current = files[path] ?: return DeleteResult.Gone
            if (current.first != ifMatch) return DeleteResult.Conflict(current.first)
            deleted.add(path)
            log.add("DELETE $path")
            files.remove(path)
            generation++
            return DeleteResult.Deleted
        }
    }

    private fun setUp(): Triple<FakeServer, DocumentStore, SyncEngine> {
        val server = FakeServer()
        val store = DocumentStore(InMemoryReplicaStorage())
        return Triple(server, store, SyncEngine(store, server) { "20260921-101500" })
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

    /**
     * Found on a phone: settling a conflict sent the copy's DELETE three times, a save, the change
     * stream and a retry each starting a cycle while the others were still waiting on the network.
     */
    @Test
    fun cyclesThatOverlapSendEachChangeOnce() = runTest {
        val (server, store, engine) = setUp()
        server.put("copy.org", "* Copy\n")
        engine.sync()
        store.delete("copy.org")
        server.deleteLatency = 100

        listOf(async { engine.sync() }, async { engine.sync() }, async { engine.sync() }).awaitAll()

        assertEquals(1, server.deleteRequests)
        assertEquals(listOf("DELETE copy.org"), server.log)
        assertEquals(emptyList(), store.tombstones())
    }

    @Test
    fun aColdStartOfManyNotesTakesThemInOneBundle() = runTest {
        val (server, store, engine) = setUp()
        server.bundles = true
        repeat(10) { server.put("n$it.org", "* Note $it\n") }

        val report = engine.sync()

        assertEquals(1, server.bundleRequests)
        assertEquals(emptyList(), server.fetched)
        assertEquals(10, report.pulled.size)
        assertEquals("* Note 3\n", store.read("n3.org"))
        assertFalse(store.isDirty("n3.org"))
        // Synced, so the next cycle has nothing to take.
        assertFalse(engine.sync().changedAnything)
        assertEquals(1, server.bundleRequests)
    }

    @Test
    fun whatABundleLeavesOutIsFetchedOneByOne() = runTest {
        val (server, store, engine) = setUp()
        server.bundles = true
        server.bundleCap = 6
        repeat(10) { server.put("n$it.org", "* Note $it\n") }

        engine.sync()

        assertEquals(4, server.fetched.size)
        assertEquals(10, store.paths().size)
    }

    @Test
    fun aFewNotesAreFetchedWithoutABundle() = runTest {
        val (server, _, engine) = setUp()
        server.bundles = true
        repeat(3) { server.put("n$it.org", "* Note $it\n") }

        engine.sync()

        assertEquals(0, server.bundleRequests)
        assertEquals(3, server.fetched.size)
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

    /**
     * T11's headline: two devices editing *different* headings converge with nobody asked anything.
     */
    @Test
    fun editsToDifferentHeadingsMergeWithoutAsking() = runTest {
        val (server, store, engine) = setUp()
        val original = "* TODO First\nbody one\n\n* TODO Second\nbody two\n"
        server.put("a.org", original)
        engine.sync()

        store.save("a.org", original.replace("body one", "body one, from the phone"))
        server.put("a.org", original.replace("body two", "body two, from Emacs"))

        val report = engine.sync()

        assertEquals(listOf("a.org"), report.pushed)
        assertEquals(emptyList(), report.conflicted)
        val result = store.read("a.org")!!
        assertTrue(result.contains("from the phone"), result)
        assertTrue(result.contains("from Emacs"), result)
        // And the server has the merged version too, so the other device converges on the next pull.
        assertEquals(result, server.files["a.org"]?.second)
    }

    /**
     * The other half: the same line changed twice keeps both versions, losing neither. The server
     * is the source of truth, so its version keeps the path and this device's goes beside it.
     */
    @Test
    fun theSameLineChangedTwiceYieldsAConflictCopyAndLosesNothing() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()

        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")

        val report = engine.sync()

        assertEquals(listOf("a.org"), report.conflicted)
        assertTrue(report.replicaMoved, "the note on screen changed")
        assertEquals("* NEXT Task\n", store.read("a.org"), "the server's version keeps the path")
        assertEquals(
            "* DONE Task\n",
            store.read("a.sync-conflict-20260921-101500.org"),
            "this device's version is kept beside it",
        )
        assertFalse(store.isDirty("a.org"), "the note is in step with the server again")
        assertEquals("* NEXT Task\n", store.base("a.org"))
        assertTrue(!store.read("a.org")!!.contains("<<<"), "never markers inside a note")
    }

    /** The server's file is never replaced by a version nobody merged, on this cycle or the next. */
    @Test
    fun aConflictNeverOverwritesTheServersVersion() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()
        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")

        engine.sync()
        engine.sync()

        assertEquals("* NEXT Task\n", server.files["a.org"]?.second)
        assertTrue(server.log.none { it == "PUT a.org" }, "${server.log}")
    }

    /** A conflict must be reported once, not on every cycle for ever. */
    @Test
    fun aConflictIsNotReportedAgainOnTheNextCycle() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()
        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")
        assertEquals(listOf("a.org"), engine.sync().conflicted)

        val second = engine.sync()
        assertEquals(emptyList(), second.conflicted, "the same conflict must not repeat")
    }

    /**
     * Emacs saving between the manifest and the push: the push is refused, and the save must be
     * merged in, never overwritten by the next cycle.
     */
    @Test
    fun anEditLandingDuringThePushIsMergedNotOverwritten() = runTest {
        val (server, store, engine) = setUp()
        val original = "* TODO First\nbody one\n\n* TODO Second\nbody two\n"
        server.put("a.org", original)
        engine.sync()

        store.save("a.org", original.replace("body one", "body one, from the phone"))
        server.beforeNextPush = { server.put("a.org", original.replace("body two", "body two, from Emacs")) }
        val report = engine.sync()
        engine.sync()

        assertEquals(listOf("a.org"), report.pushed)
        assertEquals(listOf("a.org"), report.merged)
        val result = server.files["a.org"]!!.second
        assertTrue(result.contains("from the phone"), result)
        assertTrue(result.contains("from Emacs"), result)
    }

    /** The same race on the same line: this device's version is set aside as a conflict copy. */
    @Test
    fun aConflictingEditLandingDuringThePushIsKept() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()

        store.save("a.org", "* DONE Task\n")
        server.beforeNextPush = { server.put("a.org", "* NEXT Task\n") }
        val report = engine.sync()
        engine.sync()

        assertEquals(listOf("a.org"), report.conflicted)
        assertEquals("* NEXT Task\n", server.files["a.org"]?.second)
        assertEquals("* DONE Task\n", server.files["a.sync-conflict-20260921-101500.org"]?.second)
    }

    /**
     * After a conflict the server's version is the base, so the server moving again is a plain pull
     * rather than the conflict already saved as a copy, raised a second time.
     */
    @Test
    fun aRecordedConflictDoesNotPoisonTheNextSync() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()
        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")
        assertEquals(listOf("a.org"), engine.sync().conflicted)

        server.put("a.org", "* NEXT Task, from Emacs\n")
        val second = engine.sync()

        assertEquals(emptyList(), second.conflicted)
        assertEquals(listOf("a.org"), second.pulled)
        assertEquals("* NEXT Task, from Emacs\n", store.read("a.org"))
    }

    /** Two conflicts on one note in the same second must not share, and so lose, a copy. */
    @Test
    fun conflictCopiesMadeInTheSameSecondKeepTheirOwnNames() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()
        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")
        engine.sync()

        store.save("a.org", "* WAIT Task\n")
        server.put("a.org", "* HOLD Task\n")
        engine.sync()

        assertEquals("* DONE Task\n", store.read("a.sync-conflict-20260921-101500.org"))
        assertEquals("* WAIT Task\n", store.read("a.sync-conflict-20260921-101500-2.org"))
    }

    /** The conflict copy is itself a note, so it syncs to the other device too. */
    @Test
    fun theConflictCopyIsPushedToTheServer() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* TODO Task\n")
        engine.sync()
        store.save("a.org", "* DONE Task\n")
        server.put("a.org", "* NEXT Task\n")
        engine.sync()

        engine.sync()
        assertTrue(
            server.files.containsKey("a.sync-conflict-20260921-101500.org"),
            "the other device must be able to see it: ${server.files.keys}",
        )
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

    /** A delta only lists what moved on the server; a local edit must still be pushed. */
    @Test
    fun aLocalEditIsPushedEvenWhenTheDeltaDoesNotMentionIt() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        server.put("b.org", "* B\n")
        engine.sync()

        server.completeManifests = false
        server.deltaEntries = emptySet()
        store.save("a.org", "* A edited\n")
        store.save("offline.org", "* Captured offline\n")
        val report = engine.sync()

        assertEquals(listOf("a.org", "offline.org"), report.pushed.sorted())
        assertEquals("* A edited\n", server.files["a.org"]?.second)
        assertEquals("* Captured offline\n", server.files["offline.org"]?.second)
        assertFalse(store.isDirty("a.org"))
        assertFalse(store.isDirty("offline.org"))
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

    // --- File lifecycle (T20) ---

    @Test
    fun aNoteDeletedHereIsDeletedOnTheServerAndStaysDeleted() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.delete("a.org")
        val report = engine.sync()

        assertEquals(listOf("a.org"), report.deletedLocally)
        assertNull(server.files["a.org"])
        assertEquals(emptyList(), store.tombstones(), "the tombstone is dropped once the server agrees")
        assertFalse(engine.sync().changedAnything, "and the note does not come back")
    }

    @Test
    fun aDeletionMadeOfflineSurvivesUntilTheNextSync() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.delete("a.org")
        // A full manifest still lists it; without the tombstone this would pull it straight back.
        assertEquals(listOf("a.org"), store.tombstones().map { it.path })
        engine.sync()

        assertNull(store.read("a.org"))
        assertNull(server.files["a.org"])
    }

    @Test
    fun aNoteEditedElsewhereComesBackRatherThanBeingDeleted() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.delete("a.org")
        server.put("a.org", "* A, edited on the laptop\n")
        val report = engine.sync()

        assertEquals(listOf("a.org"), report.pulled)
        assertEquals("* A, edited on the laptop\n", store.read("a.org"), "their edit is not lost")
        assertEquals("* A, edited on the laptop\n", server.files["a.org"]?.second)
        assertEquals(emptyList(), server.deleted)
    }

    @Test
    fun aDeletionOfANoteAlreadyDeletedElsewhereIsQuiet() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        store.delete("a.org")
        server.files.remove("a.org")
        val report = engine.sync()

        assertEquals(emptyList(), report.failed)
        assertEquals(emptyList(), store.tombstones())
    }

    @Test
    fun aDeletionIsSentWhenADeltaDoesNotMentionTheFile() = runTest {
        val (server, store, engine) = setUp()
        server.put("a.org", "* A\n")
        engine.sync()

        server.completeManifests = false
        server.deltaEntries = emptySet()
        store.delete("a.org")
        engine.sync()

        assertNull(server.files["a.org"])
    }

    @Test
    fun aNeverSyncedNoteIsDeletedWithoutTellingTheServer() = runTest {
        val (server, store, engine) = setUp()
        store.create("draft.org", "* Draft\n")

        store.delete("draft.org")
        engine.sync()

        assertEquals(emptyList(), store.tombstones())
        assertEquals(emptyList(), server.log)
    }

    @Test
    fun aRenameCreatesTheNewNoteBeforeDeletingTheOldOne() = runTest {
        val (server, store, engine) = setUp()
        server.put("old.org", "* Content\n")
        engine.sync()

        assertTrue(store.rename("old.org", "new.org"))
        val report = engine.sync()

        assertEquals(listOf("PUT new.org", "DELETE old.org"), server.log)
        assertEquals("* Content\n", server.files["new.org"]?.second)
        assertNull(server.files["old.org"])
        assertEquals(listOf("new.org"), report.pushed)
        assertEquals(listOf("old.org"), report.deletedLocally)
        assertEquals(listOf("new.org"), store.paths())
    }

    @Test
    fun aRenameCarriesUnsyncedEdits() = runTest {
        val (server, store, engine) = setUp()
        server.put("old.org", "* Content\n")
        engine.sync()

        store.save("old.org", "* Content, edited offline\n")
        store.rename("old.org", "new.org")
        engine.sync()

        assertEquals("* Content, edited offline\n", server.files["new.org"]?.second)
        assertNull(server.files["old.org"])
    }

    @Test
    fun aRenameRefusesATakenNameAndChangesNothing() = runTest {
        val (_, store, _) = setUp()
        store.create("a.org", "* A\n")
        store.create("b.org", "* B\n")

        assertFalse(store.rename("a.org", "b.org"))
        assertFalse(store.create("a.org", "* Other\n"))

        assertEquals("* A\n", store.read("a.org"))
        assertEquals("* B\n", store.read("b.org"))
    }

    @Test
    fun aNewNoteNeverReplacesOneMadeElsewhereAtTheSamePath() = runTest {
        val (server, store, engine) = setUp()
        engine.sync()
        store.create("inbox/idea.org", "* Mine\n")
        // Created on the laptop meanwhile; the delta that would have said so is not seen.
        server.completeManifests = false
        server.deltaEntries = emptySet()
        server.put("inbox/idea.org", "* Theirs\n")

        val report = engine.sync()

        assertEquals(listOf("inbox/idea.org"), report.conflicted)
        assertEquals("* Theirs\n", server.files["inbox/idea.org"]?.second, "theirs is not overwritten")
        assertEquals("* Theirs\n", store.read("inbox/idea.org"))
        val copy = store.paths().single { it.startsWith("inbox/idea.sync-conflict-") }
        assertEquals("* Mine\n", store.read(copy), "and mine is kept beside it")
    }

    @Test
    fun twoNotesCreatedAtTheSamePathAreNeverSplicedTogether() = runTest {
        val (server, store, engine) = setUp()
        engine.sync()
        val mine = ":PROPERTIES:\n:ID:       mine\n:END:\n#+TITLE: Plan\n"
        val theirs = ":PROPERTIES:\n:ID:       theirs\n:END:\n#+TITLE: Plan\n"
        store.create("plan.org", mine)
        // Created on another device meanwhile, and listed by the manifest this time.
        server.put("plan.org", theirs)

        val report = engine.sync()

        assertEquals(listOf("plan.org"), report.conflicted)
        assertEquals(theirs, server.files["plan.org"]?.second, "theirs is not overwritten")
        assertEquals(theirs, store.read("plan.org"), "nor merged with mine")
        val copy = store.paths().single { it.startsWith("plan.sync-conflict-") }
        assertEquals(mine, store.read(copy), "mine is kept beside it")
    }

    @Test
    fun theSameNoteCreatedOnTwoDevicesSimplyConverges() = runTest {
        val (server, store, engine) = setUp()
        engine.sync()
        store.create("plan.org", "* Plan\n")
        server.put("plan.org", "* Plan\n")

        val report = engine.sync()

        assertTrue(report.conflicted.isEmpty())
        assertEquals(listOf("plan.org"), store.paths().filter { it.startsWith("plan") })
        assertFalse(store.isDirty("plan.org"))
    }

    /** Found by [SyncEngineRandomTests]: the new note took over the old one's version, but had no base. */
    @Test
    fun aNoteMadeOverTheNameOfOneRenamedAwayIsNeverSplicedIntoItsEditedOriginal() = runTest {
        val (server, store, engine) = setUp()
        server.put("n0.org", ":ID: old\nt0\n")
        engine.sync()
        store.rename("n0.org", "n3.org")
        store.create("n0.org", ":ID: new\n")
        // Edited on another device meanwhile, before the rename reached the server.
        server.put("n0.org", ":ID: old\nt0\nedited elsewhere\n")

        val report = engine.sync()

        assertEquals(listOf("n0.org"), report.conflicted)
        assertEquals(":ID: old\nt0\nedited elsewhere\n", server.files["n0.org"]?.second, "the edit is kept")
        assertEquals(":ID: old\nt0\n", server.files["n3.org"]?.second, "the renamed note is there")
        val copy = store.paths().single { it.startsWith("n0.sync-conflict-") }
        assertEquals(":ID: new\n", store.read(copy), "and the new note beside it")
    }

    /**
     * A corrupt meta.json is read as empty, so the notes are there with no record of what was
     * synced. Unsynced edits among them must not be overwritten by the server's copy.
     */
    @Test
    fun aNoteWhoseRecordWasLostIsKeptBesideTheServersVersion() = runTest {
        val server = FakeServer()
        server.put("a.org", "* A\n")
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A, edited here\n")))
        val engine = SyncEngine(store, server) { "20260921-101500" }

        engine.sync()

        assertEquals("* A\n", store.read("a.org"))
        val copy = store.paths().single { it.startsWith("a.sync-conflict-") }
        assertEquals("* A, edited here\n", store.read(copy), "the edit is kept beside it")
    }

    @Test
    fun aNoteWhoseRecordWasLostButMatchesTheServerSimplyConverges() = runTest {
        val server = FakeServer()
        server.put("a.org", "* A\n")
        val store = DocumentStore(InMemoryReplicaStorage(mapOf("a.org" to "* A\n")))
        val engine = SyncEngine(store, server) { "20260921-101500" }

        val report = engine.sync()

        assertTrue(report.conflicted.isEmpty())
        assertEquals(listOf("a.org"), store.paths())
    }

    /**
     * A complete manifest says where the server is now, even behind where it was: a server whose
     * clock went back across a restart. Kept at the higher number, the client would ask for changes
     * since a generation the server reaches only later, and miss everything before it.
     */
    @Test
    fun aCompleteManifestSetsTheGenerationEvenBackwards() = runTest {
        val (server, store, engine) = setUp()
        server.generation = 50
        engine.sync()

        server.generation = 3
        engine.sync()

        assertEquals(3, store.generation())
    }
}
