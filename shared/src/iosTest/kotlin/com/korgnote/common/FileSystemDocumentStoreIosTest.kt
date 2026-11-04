package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The same contract as the other stores, against a real directory in the simulator's sandbox. */
@OptIn(ExperimentalForeignApi::class)
class FileSystemDocumentStoreIosTest : DocumentStoreContract() {
    private val roots = mutableListOf<String>()

    private fun newRoot(): String =
        "${NSTemporaryDirectory()}korgnote-${NSUUID().UUIDString}".also { roots.add(it) }

    override suspend fun storage(): ReplicaStorage = FileSystemReplicaStorage(newRoot())

    @AfterTest
    fun cleanUp() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    /** A save the disk refused must not report success: the edit would be believed safe. */
    @Test
    fun aSaveThatCouldNotBeWrittenFails() = runTest {
        val root = newRoot()
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
        // A file where the notes' folder should be, so nothing can be written under it.
        manager.createFileAtPath("$root/notes", contents = null, attributes = null)
        val store = DocumentStore(FileSystemReplicaStorage(root))

        assertFailsWith<IllegalStateException> { store.save("a.org", "* A\n") }
        assertEquals(null, store.read("a.org"))
    }

    /** Another store over the same folder writes: what this one knows must follow the file. */
    @Test
    fun aWriteByAnotherStoreIsSeen() = runTest {
        val root = newRoot()
        val first = DocumentStore(FileSystemReplicaStorage(root))
        val second = DocumentStore(FileSystemReplicaStorage(root))
        first.save("a.org", "* A\n")
        assertEquals(listOf("a.org"), second.dirtyPaths())

        second.save("b.org", "* B\n")

        assertEquals(listOf("a.org", "b.org"), first.dirtyPaths())
    }

    @Test
    fun aNoteSurvivesARestart() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).save("a.org", "* A\n")

        val afterRestart = DocumentStore(FileSystemReplicaStorage(root))
        assertEquals("* A\n", afterRestart.read("a.org"))
        assertFalse(afterRestart.dirtyPaths().isEmpty())
    }
}
