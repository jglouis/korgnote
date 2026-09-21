package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same contract as [InMemoryDocumentStoreTest], against a real directory.
 *
 * Running one set of expectations against both implementations is what makes the in-memory store a
 * trustworthy stand-in for the web build and for every other test.
 */
class FileSystemDocumentStoreTest : DocumentStoreContract() {
    private val roots = mutableListOf<File>()

    private fun newRoot(): File =
        Files.createTempDirectory("korgnote-replica").toFile().also { roots.add(it) }

    override suspend fun storage(): ReplicaStorage = FileSystemReplicaStorage(newRoot())

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    /** T8's acceptance criterion: an edit made offline is still there after a restart. */
    @Test
    fun anOfflineEditSurvivesARestart() = runTest {
        val root = newRoot()

        // First run: the app syncs, then the user edits with the server unreachable.
        DocumentStore(FileSystemReplicaStorage(root)).apply {
            acceptFromServer("inbox.org", "* TODO From server\n", etag = "v1", generation = 3)
            save("inbox.org", "* TODO Edited on a train\n")
        }

        // Restart: a brand new store over the same directory, nothing carried in memory.
        val afterRestart = DocumentStore(FileSystemReplicaStorage(root))

        assertEquals("* TODO Edited on a train\n", afterRestart.read("inbox.org"))
        assertTrue(afterRestart.isDirty("inbox.org"), "the edit is still unsynced after a restart")
        assertEquals("* TODO From server\n", afterRestart.base("inbox.org"), "the ancestor survives")
        assertEquals("v1", afterRestart.meta("inbox.org")?.etag)
    }

    /** The working copy is an ordinary folder of ordinary files, so Emacs and Syncthing still work. */
    @Test
    fun theWorkingCopyIsPlainFilesOnDisk() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).save("projects/work.org", "* Plan\n")

        val onDisk = File(root, "notes/projects/work.org")
        assertTrue(onDisk.isFile, "expected a real file at ${onDisk.path}")
        assertEquals("* Plan\n", onDisk.readText())
    }

    @Test
    fun metadataIsStoredBesideTheReplicaNotInsideIt() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).save("inbox.org", "* One\n")

        assertTrue(File(root, ".korgnote/meta.json").isFile)
        // Nothing but .org files in the working copy: a stray meta file would sync to the server.
        val strays = File(root, "notes").walkTopDown().filter { it.isFile && it.extension != "org" }
        assertEquals(emptyList(), strays.toList(), "the working copy must hold notes only")
    }

    /** A half-written or corrupt meta.json must not make the notes unreadable. */
    @Test
    fun corruptMetadataDoesNotLoseTheNotes() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).save("inbox.org", "* Important\n")
        File(root, ".korgnote/meta.json").writeText("{ this is not json")

        val recovered = DocumentStore(FileSystemReplicaStorage(root))
        assertEquals("* Important\n", recovered.read("inbox.org"))
        assertEquals(null, recovered.meta("inbox.org"))
    }
}

/** Where the replica lands on each desktop platform. */
class DesktopReplicaRootTest {
    @Test
    fun followsEachPlatformsConvention() {
        assertEquals(
            "/Users/x/Library/Application Support/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Mac OS X", "/Users/x") { null }.path,
        )
        assertEquals(
            "/home/x/.local/share/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Linux", "/home/x") { null }.path,
        )
        assertEquals(
            "/data/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Linux", "/home/x") { "/data" }.path,
        )
    }
}
