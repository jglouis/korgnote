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

    private fun newStorage() = FileSystemReplicaStorage(
        "${NSTemporaryDirectory()}korgnote-${NSUUID().UUIDString}".also { roots.add(it) },
    )

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private class Server(path: String) : SyncApi {
        val files = mutableMapOf(path to "* Note\n")
        val pushed = mutableListOf<String>()
        var refusesPushes = false

        override suspend fun manifest(since: Long?) =
            RemoteManifest(1, complete = true, entries = files.map { (path, text) -> RemoteEntry(path, "e-${text.hashCode()}") })

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
    private suspend fun storageWithBothSpellingsOnRecord() = newStorage().also { storage ->
        val etag = "e-${"* Note\n".hashCode()}"
        storage.write(together, "* Note\n")
        storage.writeBase(together, "* Note\n")
        storage.writeMeta(
            ReplicaMeta(files = mapOf(together to FileMeta(together, etag), apart to FileMeta(apart, etag))),
        )
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
