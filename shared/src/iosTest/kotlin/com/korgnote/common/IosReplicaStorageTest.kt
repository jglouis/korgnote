package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileSystemReplicaStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS storage, run on a simulator.
 *
 * This exists because the write path was once broken in a way no amount of compiling could catch:
 * `content as NSString` compiles happily and throws at runtime. Only executing it proves it works.
 */
@OptIn(ExperimentalForeignApi::class)
class IosReplicaStorageTest {

    private fun newRoot(): String = NSTemporaryDirectory() + "korgnote-" + NSUUID().UUIDString()

    @Test
    fun writesAndReadsBackOnARealFilesystem() = runTest {
        val store = DocumentStore(FileSystemReplicaStorage(newRoot()))

        store.save("inbox.org", "* TODO Buy milk\n")

        assertEquals("* TODO Buy milk\n", store.read("inbox.org"))
        assertTrue(store.isDirty("inbox.org"))
    }

    @Test
    fun anOfflineEditSurvivesARestart() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).apply {
            acceptFromServer("inbox.org", "* From server\n", etag = "v1")
            save("inbox.org", "* Edited offline\n")
        }

        val afterRestart = DocumentStore(FileSystemReplicaStorage(root))
        assertEquals("* Edited offline\n", afterRestart.read("inbox.org"))
        assertEquals("* From server\n", afterRestart.base("inbox.org"))
        assertTrue(afterRestart.isDirty("inbox.org"))
    }

    @Test
    fun nestedPathsAndListingWork() = runTest {
        val store = DocumentStore(FileSystemReplicaStorage(newRoot()))
        store.save("projects/work.org", "* Plan\n")
        store.save("inbox.org", "* In\n")

        assertEquals(listOf("inbox.org", "projects/work.org"), store.paths())
    }
}
