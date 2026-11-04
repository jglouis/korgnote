package com.korgnote.common

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplacingMove
import com.korgnote.common.replica.ReplicaStorage
import com.korgnote.common.replica.moveOver
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

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
            acceptFromServer("inbox.org", "* TODO From server\n", etag = "v1")
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

    /** As every version so far wrote it: pretty-printed, and with fields nothing reads any more. */
    @Test
    fun metadataWrittenByEarlierVersionsStillLoads() = runTest {
        val root = newRoot()
        File(root, ".korgnote").mkdirs()
        File(root, ".korgnote/meta.json").writeText(
            """
            {
                "generation": 1758300000421,
                "files": {
                    "inbox.org": {
                        "path": "inbox.org",
                        "size": 2310,
                        "mtime": 1758300000000,
                        "etag": "9f2a",
                        "dirty": true,
                        "lastSyncedGeneration": 1758300000000
                    },
                    "gone.org": {
                        "path": "gone.org",
                        "etag": "41bc",
                        "deleted": true
                    }
                }
            }
            """.trimIndent(),
        )

        val store = DocumentStore(FileSystemReplicaStorage(root))

        assertEquals(1758300000421, store.generation())
        assertEquals("9f2a", store.meta("inbox.org")?.etag)
        assertTrue(store.isDirty("inbox.org"))
        assertEquals(listOf("gone.org"), store.tombstones().map { it.path })
    }

    /**
     * Nothing on Android or iOS opens a replica twice, but two copies of the desktop app can. A
     * record kept from before the other's write would be written back over it, and lose the flag
     * that says an edit still has to be pushed.
     */
    @Test
    fun aWriteByAnotherStoreOverTheSameFolderIsSeenAndKept() = runTest {
        val root = newRoot()
        val first = DocumentStore(FileSystemReplicaStorage(root))
        val second = DocumentStore(FileSystemReplicaStorage(root))
        first.save("a.org", "* A\n")
        assertEquals(listOf("a.org"), second.dirtyPaths())

        second.save("b.org", "* B\n")
        assertEquals(listOf("a.org", "b.org"), first.dirtyPaths())

        first.save("c.org", "* C\n")
        assertEquals(listOf("a.org", "b.org", "c.org"), second.dirtyPaths())
    }

    /** The same size, as when only an etag changed: the time is what tells. */
    @Test
    fun aRewriteOfTheSameSizeIsSeen() = runTest {
        val root = newRoot()
        val store = DocumentStore(FileSystemReplicaStorage(root))
        store.acceptFromServer("a.org", "* A\n", etag = "v1")
        assertEquals("v1", store.meta("a.org")?.etag)

        val file = File(root, ".korgnote/meta.json")
        val modified = file.lastModified()
        file.writeText(file.readText().replace("v1", "v2"))
        file.setLastModified(modified + 2_000)

        assertEquals("v2", store.meta("a.org")?.etag)
    }

    /** What the keeping is for: a file that did not move is not read again, let alone decoded. */
    @Test
    fun metadataIsNotReadAgainUntilTheFileMoves() = runTest {
        val root = newRoot()
        val store = DocumentStore(FileSystemReplicaStorage(root))
        store.acceptFromServer("a.org", "* A\n", etag = "v1")

        val file = File(root, ".korgnote/meta.json")
        val modified = file.lastModified()
        file.writeText(file.readText().replace("v1", "v2"))
        file.setLastModified(modified)

        assertEquals("v1", store.meta("a.org")?.etag)
    }

    /** Pretty-printed it was twice the size, for no reader: nobody opens it by hand. */
    @Test
    fun metadataIsWrittenCompactly() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).save("a.org", "* A\n")

        assertFalse('\n' in File(root, ".korgnote/meta.json").readText())
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

    /** A read that failed says nothing about the file, which is what a decoded copy stands for. */
    @Test
    fun aFailedReadOfTheMetadataIsNotRememberedForItsStamp() = runTest {
        val root = newRoot()
        DocumentStore(FileSystemReplicaStorage(root)).acceptFromServer("a.org", "* a\n", "etag-a")
        // A new instance, as after starting again, knows nothing of the file until it reads it.
        val storage = FileSystemReplicaStorage(root)
        val meta = File(root, ".korgnote/meta.json")
        check(meta.setReadable(false))
        try {
            assertEquals(emptyMap(), storage.readMeta().files)
        } finally {
            check(meta.setReadable(true))
        }

        assertEquals("etag-a", storage.readMeta().files["a.org"]?.etag)
    }
}

/** What a write leaves in place, whatever the filesystem makes of a rename. */
class MoveOverTest {
    private val folder = Files.createTempDirectory("korgnote-move").toFile()
    private val temp = File(folder, "note.org.tmp").apply { writeText("new") }
    private val target = File(folder, "note.org").apply { writeText("old") }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** Android 7 has no `java.nio.file`, so where a rename is refused it goes back to making room first. */
    @Test
    fun whereTheReplacingMoveCannotLoadTheTargetMakesWayForTheRename() {
        moveOver(temp, target, replacing = { _, _ -> throw NoClassDefFoundError("java/nio/file/Files") }) { from, to ->
            !to.exists() && from.renameTo(to)
        }

        assertEquals("new", target.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun aRenameIsAllItTakesWhereItReplaces() {
        moveOver(temp, target)

        assertEquals("new", target.readText())
        assertFalse(temp.exists())
    }

    /** As Windows does: no rename onto a file that exists, so it must never be taken out of the way. */
    @Test
    fun aFilesystemThatRefusesTheRenameNeverSeesTheTargetMissing() {
        moveOver(temp, target) { _, to -> if (to.exists()) false else fail("the target was deleted first") }

        assertEquals("new", target.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun aFilesystemWithNoAtomicMoveStillGetsTheFile() {
        ReplacingMove.move(temp, target) { from, _ -> throw AtomicMoveNotSupportedException(from.path, null, "not here") }

        assertEquals("new", target.readText())
        assertFalse(temp.exists())
    }
}

/** Where the replica lands on each desktop platform. */
class DesktopReplicaRootTest {
    @Test
    fun followsEachPlatformsConvention() {
        assertEquals(
            "/Users/x/Library/Application Support/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Mac OS X", "/Users/x") { null }.invariantSeparatorsPath,
        )
        assertEquals(
            "/home/x/.local/share/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Linux", "/home/x") { null }.invariantSeparatorsPath,
        )
        assertEquals(
            "/data/KorgNote",
            com.korgnote.common.replica.defaultDesktopReplicaRoot("Linux", "/home/x") { "/data" }.invariantSeparatorsPath,
        )
    }
}
