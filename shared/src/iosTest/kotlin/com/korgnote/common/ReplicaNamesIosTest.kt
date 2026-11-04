package com.korgnote.common

import com.korgnote.common.replica.DeleteResult
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FetchResult
import com.korgnote.common.replica.FileMeta
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.PushCondition
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.ReplicaMeta
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS hands a name back with its accents apart, `e` and then the accent, whichever way it was given,
 * and treats both spellings as one file. The server's names are the other spelling on most systems,
 * and to a server on Linux the two are different files. So the name a note was recorded under is the
 * name it keeps, against a real directory in the simulator's sandbox.
 */
@OptIn(ExperimentalForeignApi::class)
class ReplicaNamesIosTest {
    private val together = "Café/Über.org"
    private val apart = "Café/Über.org"

    private val roots = mutableListOf<String>()

    private fun newRoot() = "${NSTemporaryDirectory()}korgnote-${NSUUID().UUIDString}".also { roots.add(it) }

    private fun newStorage() = FileSystemReplicaStorage(newRoot())

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private class Server(path: String) : SyncApi {
        val files = mutableMapOf(path to "* Note\n")
        val pushed = mutableListOf<String>()
        var refusesPushes = false

        /** As the real one does: a phone with a generation hears only of what moved, here nothing. */
        var answersWithDeltas = false

        override suspend fun manifest(since: Long?) =
            if (answersWithDeltas && since != null) {
                RemoteManifest(1, complete = false, entries = emptyList())
            } else {
                RemoteManifest(1, complete = true, entries = files.map { (path, text) -> RemoteEntry(path, "e-${text.hashCode()}") })
            }

        override suspend fun fetch(path: String): FetchResult {
            val text = files[path] ?: return FetchResult.Gone
            return FetchResult.Fetched(RemoteFile(path, "e-${text.hashCode()}", text))
        }

        override suspend fun push(path: String, content: String, condition: PushCondition): PushResult {
            if (refusesPushes) return PushResult.Failed("503 Service Unavailable")
            pushed += path
            files[path] = content
            return PushResult.Pushed("e-${content.hashCode()}")
        }

        override suspend fun delete(path: String, ifMatch: String): DeleteResult {
            files.remove(path)
            return DeleteResult.Deleted
        }
    }

    @Test
    fun aNoteIsListedUnderTheNameItWasSavedAs() = runTest {
        val store = DocumentStore(newStorage())

        store.save(together, "* Note\n")

        assertEquals(listOf(together), store.paths())
    }

    @Test
    fun aNameTheServerHoldsApartStaysApart() = runTest {
        val store = DocumentStore(newStorage())

        store.save(apart, "* Note\n")

        assertEquals(listOf(apart), store.paths())
    }

    @Test
    fun aFileNoRecordKnowsIsListedWithItsAccentsTogether() = runTest {
        val storage = newStorage()

        storage.write(apart, "* Note\n")

        assertEquals(listOf(together), storage.list())
    }

    /** Sent back, the name would be a second copy of the note on a server on Linux. */
    @Test
    fun aPulledNoteIsNotSentBackUnderItsOtherSpelling() = runTest {
        val server = Server(together)
        val store = DocumentStore(newStorage())
        val engine = SyncEngine(store, server)

        engine.sync()
        engine.sync()

        assertEquals(emptyList(), server.pushed)
        assertEquals(setOf(together), server.files.keys)
    }

    /** Every cycle after the first took it for deleted on the server, and took it off the phone. */
    @Test
    fun aPulledNoteStaysThroughLaterCycles() = runTest {
        val store = DocumentStore(newStorage())
        val engine = SyncEngine(store, Server(together))

        repeat(4) {
            engine.sync()
            assertEquals("* Note\n", store.read(together))
            assertEquals(listOf(together), store.paths())
        }
    }

    @Test
    fun aNameTheServerHoldsApartIsNotSentBackEither() = runTest {
        val server = Server(apart)
        val store = DocumentStore(newStorage())
        val engine = SyncEngine(store, server)

        engine.sync()
        engine.sync()

        assertEquals(emptyList(), server.pushed)
        assertEquals("* Note\n", store.read(apart))
    }

    /** What a phone holds after an earlier version: both spellings on record, for the one file. */
    private suspend fun storageWithBothSpellingsOnRecord(generation: Long = 0) = newStorage().also { storage ->
        val etag = "e-${"* Note\n".hashCode()}"
        storage.write(together, "* Note\n")
        storage.writeBase(together, "* Note\n")
        storage.writeMeta(
            ReplicaMeta(generation, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag))),
        )
    }

    /** The usual case: a phone with a generation, which would hear nothing of it for as long as the server runs. */
    @Test
    fun aPhoneUpgradedWithBothSpellingsSettlesItAtItsNextSync() = runTest {
        val root = newRoot()
        val server = Server(together).apply { answersWithDeltas = true }
        FileSystemReplicaStorage(root).write(together, "* Note\n")
        val etag = "e-${"* Note\n".hashCode()}"
        FileSystemReplicaStorage(root).writeMeta(
            ReplicaMeta(100, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag))),
        )
        val store = DocumentStore(FileSystemReplicaStorage(root))

        SyncEngine(store, server).sync()

        assertEquals(listOf(together), store.paths())
        assertEquals(setOf(together), store.records().keys)
        assertEquals(emptyList(), server.pushed)
        assertEquals("* Note\n", store.read(together))
    }

    @Test
    fun aNoteDeletedElsewhereGoesFromAPhoneUpgradedWithBothSpellings() = runTest {
        val root = newRoot()
        val server = Server(together).apply { answersWithDeltas = true; files.clear() }
        FileSystemReplicaStorage(root).write(together, "* Note\n")
        val etag = "e-${"* Note\n".hashCode()}"
        FileSystemReplicaStorage(root).writeMeta(
            ReplicaMeta(100, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag))),
        )
        val store = DocumentStore(FileSystemReplicaStorage(root))

        SyncEngine(store, server).sync()

        assertEquals(emptyList(), store.paths())
        assertEquals(null, store.read(together))
    }

    /** Once its first sync has settled which spelling is real: until then it is listed twice. */
    @Test
    fun aNoteDeletedHereGoesFromAPhoneUpgradedWithBothSpellings() = runTest {
        val root = newRoot()
        val server = Server(together).apply { answersWithDeltas = true }
        FileSystemReplicaStorage(root).write(together, "* Note\n")
        val etag = "e-${"* Note\n".hashCode()}"
        FileSystemReplicaStorage(root).writeMeta(
            ReplicaMeta(100, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag))),
        )
        val store = DocumentStore(FileSystemReplicaStorage(root))
        val engine = SyncEngine(store, server)
        engine.sync()

        store.delete(store.paths().single())
        engine.sync()

        assertEquals(emptyList(), store.paths())
        assertEquals(emptySet(), server.files.keys)
    }

    /** An edit an earlier version made under the spelling it listed: still there, wherever it is sent. */
    @Test
    fun anEditMadeUnderTheOtherSpellingIsKeptThroughTheUpgrade() = runTest {
        val root = newRoot()
        val server = Server(together).apply { answersWithDeltas = true }
        FileSystemReplicaStorage(root).write(apart, "* Note\nedited on the old version\n")
        val etag = "e-${"* Note\n".hashCode()}"
        FileSystemReplicaStorage(root).writeMeta(
            ReplicaMeta(100, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag, dirty = true))),
        )
        val store = DocumentStore(FileSystemReplicaStorage(root))

        SyncEngine(store, server).sync()

        assertEquals("* Note\nedited on the old version\n", store.read(together))
        assertTrue(server.files.values.any { "edited on the old version" in it })
    }

    /** Two notes to the server, one file here. Neither is pushed, and both stay on the server. */
    @Test
    fun aServerHoldingBothSpellingsKeepsBothThroughARestart() = runTest {
        val root = newRoot()
        val server = Server(together).apply { files[apart] = "* Note\n" }
        SyncEngine(DocumentStore(FileSystemReplicaStorage(root)), server).sync()

        val afterRestart = DocumentStore(FileSystemReplicaStorage(root))
        SyncEngine(afterRestart, server).sync()

        assertEquals(emptyList(), server.pushed)
        assertEquals(setOf(together, apart), server.files.keys)
        assertEquals(setOf(together, apart), afterRestart.records().keys)
    }

    /**
     * The one file must not go back to the other spelling's text: what the server holds under the
     * name edited would look reverted here, and the next edit made from it would replace that.
     */
    @Test
    fun anEditToOneOfTwoSpellingsTheServerHoldsSurvivesARestart() = runTest {
        val root = newRoot()
        val server = Server(together).apply { files[apart] = "* Note\n" }
        val store = DocumentStore(FileSystemReplicaStorage(root))
        val engine = SyncEngine(store, server)
        engine.sync()
        store.save(together, "* Note\nedited\n")
        engine.sync()

        val afterRestart = DocumentStore(FileSystemReplicaStorage(root))
        SyncEngine(afterRestart, server).sync()

        assertEquals("* Note\nedited\n", afterRestart.read(together))
        assertEquals("* Note\nedited\n", server.files[together])
        assertEquals("* Note\n", server.files[apart])
    }

    /** What an earlier version leaves on a server on Linux, with an edit it made under the spelling it listed. */
    @Test
    fun anEditMadeUnderTheOtherSpellingStaysOnThePhoneWhenTheServerHoldsBoth() = runTest {
        val root = newRoot()
        val server = Server(together).apply { files[apart] = "* Note\n" }
        FileSystemReplicaStorage(root).write(apart, "* Note\nedited on the old version\n")
        val etag = "e-${"* Note\n".hashCode()}"
        FileSystemReplicaStorage(root).writeMeta(
            ReplicaMeta(100, mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag, dirty = true))),
        )
        val store = DocumentStore(FileSystemReplicaStorage(root))

        SyncEngine(store, server).sync()

        assertEquals("* Note\nedited on the old version\n", store.read(together))
        assertEquals("* Note\nedited on the old version\n", server.files[together])
        assertTrue(store.dirtyPaths().isEmpty())
    }

    /** Deleting one of them leaves the other, whose text is the same file here. */
    @Test
    fun deletingOneOfTwoSpellingsTheServerHoldsLeavesTheOther() = runTest {
        val server = Server(together).apply { files[apart] = "* Note\n" }
        val store = DocumentStore(newStorage())
        val engine = SyncEngine(store, server)
        engine.sync()

        store.delete(apart)
        assertEquals("* Note\n", store.read(together))
        engine.sync()

        assertEquals("* Note\n", store.read(together))
        assertEquals(setOf(together), server.files.keys)
    }

    /** An unreadable `meta.json`, with the name as nearly every server has it: the edit is kept beside it. */
    @Test
    fun anEditWithNoRecordIsKeptAsAConflictCopy() = runTest {
        val server = Server(together)
        val store = DocumentStore(newStorage().also { it.write(apart, "* Note\nedit never pushed\n") })

        SyncEngine(store, server, timestamp = { "20260101-120000" }).sync()

        assertEquals("* Note\n", store.read(together))
        val copy = store.paths().single { it != together }
        assertEquals("* Note\nedit never pushed\n", store.read(copy))
        assertEquals("* Note\n", server.files[together])
    }

    /** Once settled, a phone goes back to deltas, or every cycle would cost a complete manifest. */
    @Test
    fun aPhoneWithOneSpellingOnRecordKeepsItsGeneration() = runTest {
        val root = newRoot()
        FileSystemReplicaStorage(root).write(together, "* Note\n")
        FileSystemReplicaStorage(root).writeMeta(ReplicaMeta(100, mapOf(together to FileMeta(together, "e"))))

        assertEquals(100, DocumentStore(FileSystemReplicaStorage(root)).generation())
    }

    @Test
    fun theSpellingAnEarlierVersionRecordedGoesWithoutTheNote() = runTest {
        val server = Server(together)
        val storage = storageWithBothSpellingsOnRecord()
        val store = DocumentStore(storage)

        SyncEngine(store, server).sync()

        assertEquals("* Note\n", store.read(together))
        assertEquals("* Note\n", storage.readBase(together))
        assertEquals(listOf(together), store.paths())
        assertEquals(setOf(together), store.records().keys)
        assertEquals(emptyList(), server.pushed)
    }

    @Test
    fun theSpellingAnEarlierVersionRecordedGoesWithoutAnEditNotYetSent() = runTest {
        val server = Server(together).apply { refusesPushes = true }
        val store = DocumentStore(storageWithBothSpellingsOnRecord())
        store.save(together, "* Note\nedited here\n")

        SyncEngine(store, server).sync()

        assertEquals("* Note\nedited here\n", store.read(together))
        assertEquals(listOf(together), store.dirtyPaths())
        assertEquals(setOf(together), store.records().keys)
    }

    @Test
    fun aDeletedNoteLeavesTheDisk() = runTest {
        val storage = newStorage()
        val store = DocumentStore(storage)
        store.save(together, "* Note\n")

        store.delete(together)

        assertTrue(storage.list().isEmpty())
        assertEquals(null, store.read(together))
    }

    /** Both on record, as above: deleting the note, the spelling left over must not keep it here. */
    @Test
    fun aDeletedNoteLeavesTheDiskWhateverElseIsOnRecord() = runTest {
        val server = Server(together)
        val storage = storageWithBothSpellingsOnRecord()
        val store = DocumentStore(storage)

        store.delete(together)
        SyncEngine(store, server).sync()

        assertEquals(null, store.read(together))
        assertTrue(storage.list().isEmpty())
        assertEquals(emptySet(), server.files.keys)
    }
}
