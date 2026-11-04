package com.korgnote.server

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store exists so the server stops believing a snapshot taken at startup. These cover the two
 * things that made that dangerous: not seeing an external edit, and overwriting one.
 */
class FileStoreTest {

    private lateinit var root: java.nio.file.Path
    private lateinit var store: FileStore

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("korgnote-store")
        store = FileStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun writeFile(relative: String, text: String) {
        val file = root.resolve(relative)
        file.parent?.createDirectories()
        file.writeText(text)
    }

    @Test
    fun listsOnlyOrgFilesIncludingNestedOnes() {
        writeFile("inbox.org", "* TODO one\n")
        writeFile("projects/work.org", "* TODO two\n")
        writeFile("notes.txt", "not an org file")

        assertEquals(listOf("inbox.org", "projects/work.org"), store.list().map { it.path })
    }

    @Test
    fun seesAFileCreatedAfterTheStoreWasBuilt() {
        assertEquals(emptyList(), store.list().map { it.path })
        writeFile("later.org", "* TODO appeared\n")
        assertEquals(listOf("later.org"), store.list().map { it.path })
    }

    @Test
    fun seesAnEditMadeOutsideTheServer() {
        writeFile("inbox.org", "* TODO before\n")
        assertEquals("* TODO before\n", store.read("inbox.org"))

        writeFile("inbox.org", "* TODO after\n")
        assertEquals("* TODO after\n", store.read("inbox.org"))
    }

    @Test
    fun theEtagFollowsTheContent() {
        writeFile("inbox.org", "* TODO before\n")
        val before = store.meta("inbox.org")!!.etag

        writeFile("inbox.org", "* TODO after\n")
        assertNotEquals(before, store.meta("inbox.org")!!.etag)

        writeFile("inbox.org", "* TODO before\n")
        assertEquals(before, store.meta("inbox.org")!!.etag, "the same content should hash the same")
    }

    @Test
    fun writingReplacesTheContentAndReportsTheNewEtag() {
        writeFile("inbox.org", "* TODO before\n")
        val original = store.meta("inbox.org")!!

        val result = store.write("inbox.org", "* TODO after\n", original.etag)

        val written = assertIs<WriteResult.Written>(result)
        assertEquals("* TODO after\n", root.resolve("inbox.org").readText())
        assertNotEquals(original.etag, written.meta.etag)
    }

    @Test
    fun writingRefusesWhenTheFileChangedUnderneath() {
        writeFile("inbox.org", "* TODO before\n")
        val staleEtag = store.meta("inbox.org")!!.etag

        // Someone edits it in Emacs, or Syncthing delivers a new version.
        writeFile("inbox.org", "* TODO edited elsewhere\n")

        val result = store.write("inbox.org", "* TODO from the app\n", staleEtag)

        assertIs<WriteResult.Conflict>(result)
        assertEquals(
            "* TODO edited elsewhere\n",
            root.resolve("inbox.org").readText(),
            "the external edit must survive",
        )
    }

    @Test
    fun writingWithoutABaselineIsAllowed() {
        writeFile("inbox.org", "* TODO before\n")
        assertIs<WriteResult.Written>(store.write("inbox.org", "* TODO after\n", expectedEtag = null))
    }

    @Test
    fun writingRejectsPathsOutsideTheRoot() {
        writeFile("inbox.org", "* TODO one\n")
        val outside = createTempDirectory("korgnote-outside").resolve("secret.org")
        outside.writeText("private\n")

        for (attempt in listOf("../secret.org", "../../secret.org", "a/../../secret.org", outside.toString())) {
            val result = store.write(attempt, "clobbered\n", null)
            assertTrue(
                result is WriteResult.Invalid || result is WriteResult.NotFound,
                "traversal attempt should not be accepted: $attempt (was $result)",
            )
        }
        assertEquals("private\n", outside.readText(), "nothing outside the root may be touched")
        outside.parent.toFile().deleteRecursively()
    }

    @Test
    fun readingRejectsPathsOutsideTheRoot() {
        assertNull(store.read("../secret.org"))
        assertNull(store.meta("../secret.org"))
    }

    @Test
    fun aNoteOverTheLimitIsTooLarge() {
        assertIs<WriteResult.TooLarge>(store.put("big.org", "x".repeat(32 * 1024 * 1024 + 1)))
    }

    @Test
    fun onlyOrgFilesCanBeWritten() {
        writeFile("notes.txt", "plain\n")
        assertIs<WriteResult.Invalid>(store.write("notes.txt", "changed\n", null))
        assertEquals("plain\n", root.resolve("notes.txt").readText())
    }

    @Test
    fun writingAMissingFileReportsNotFound() {
        assertIs<WriteResult.NotFound>(store.write("nothing-here.org", "x\n", null))
    }

    @Test
    fun aReadOnlyStoreRefusesWrites() {
        writeFile("inbox.org", "* TODO one\n")
        val readOnly = FileStore(root, readOnly = true)
        assertIs<WriteResult.ReadOnly>(readOnly.write("inbox.org", "changed\n", null))
        assertEquals("* TODO one\n", root.resolve("inbox.org").readText())
    }

    @Test
    fun aFolderThatRefusesTheWriteIsAFailureAndLeavesNothingBehind() {
        writeFile("sub/a.org", "* a\n")
        val folder = root.resolve("sub").toFile()
        assertTrue(folder.setWritable(false))
        try {
            assertIs<WriteResult.Failed>(store.put("sub/a.org", "* b\n"))
            assertIs<WriteResult.Failed>(store.delete("sub/a.org", ifMatch = null))
        } finally {
            folder.setWritable(true)
        }
        assertEquals("* a\n", root.resolve("sub/a.org").readText())
    }

    @Test
    fun aFailedWriteLeavesNoTemporaryFilesBehind() {
        writeFile("inbox.org", "* TODO one\n")
        store.write("inbox.org", "* TODO two\n", null)
        val strays = root.toFile().listFiles()?.filter { it.name.startsWith(".korgnote") }.orEmpty()
        assertTrue(strays.isEmpty(), "temporary files should be moved into place, not left: $strays")
    }

    private fun modeOf(relative: String): String =
        java.nio.file.attribute.PosixFilePermissions.toString(
            java.nio.file.Files.getPosixFilePermissions(root.resolve(relative)),
        )

    /** A save used to leave every note readable by its owner only, whatever it was before. */
    @Test
    fun aSavedNoteKeepsItsPermissions() {
        writeFile("shared.org", "* A\n")
        java.nio.file.Files.setPosixFilePermissions(
            root.resolve("shared.org"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"),
        )

        store.put("shared.org", "* A, edited\n")

        assertEquals("rw-rw-r--", modeOf("shared.org"))
    }

    @Test
    fun aNoteCreatedFromTheAppIsReadableLikeTheOthers() {
        store.put("inbox/new.org", "* New\n", mustNotExist = true)

        assertEquals("rw-r--r--", modeOf("inbox/new.org"))
    }

    /**
     * A folder linked into the notes from elsewhere is outside them. Reading or editing a note
     * through it was already refused; creating one must be too, or a new note lands outside the root.
     */
    @Test
    fun aNoteCannotBeCreatedThroughALinkThatLeavesTheRoot() {
        val outside = createTempDirectory("korgnote-outside")
        try {
            java.nio.file.Files.createSymbolicLink(root.resolve("linked"), outside)

            val result = store.put("linked/new.org", "* escaped\n", mustNotExist = true)

            assertIs<WriteResult.Invalid>(result)
            assertTrue(outside.toFile().list().isNullOrEmpty(), "nothing may be written outside the root")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    // --- What the library is: Syncthing's old versions and the like are not notes ---

    @Test
    fun aNoteInADotFolderIsNotPartOfTheLibrary() {
        writeFile("a.org", "* a\n")
        writeFile(".stversions/a~20260101.org", "* old a\n")
        writeFile("sub/.hidden/b.org", "* b\n")

        assertEquals(listOf("a.org"), store.list().map { it.path })
        assertNull(store.meta(".stversions/a~20260101.org"))
        assertNull(store.read(".stversions/a~20260101.org"))
        assertNull(store.meta("sub/.hidden/b.org"))
    }

    @Test
    fun aDotFolderCannotBeWrittenToOrDeletedFrom() {
        writeFile(".korgnote-trash/old.org", "* old\n")

        assertIs<WriteResult.Invalid>(store.put(".korgnote-trash/planted.org", "* planted\n"))
        assertIs<WriteResult.Invalid>(store.put(".new/planted.org", "* planted\n", mustNotExist = true))
        assertIs<WriteResult.Invalid>(store.delete(".korgnote-trash/old.org", ifMatch = "any"))
        assertEquals(false, root.resolve(".korgnote-trash/planted.org").toFile().exists())
        assertEquals(false, root.resolve(".new").toFile().exists())
        assertEquals("* old\n", root.resolve(".korgnote-trash/old.org").readText())
    }

    /** The app's own folder storage hides these too, but a server's library has always included them. */
    @Test
    fun aDotFileOutsideADotFolderStaysInTheLibrary() {
        writeFile(".plan.org", "* plan\n")
        writeFile("sub/.other.org", "* other\n")

        assertEquals(listOf(".plan.org", "sub/.other.org"), store.list().map { it.path })
        assertEquals("* plan\n", store.read(".plan.org"))
    }

    /** Listed, it reached every phone as a note that could not be fetched. */
    @Test
    fun aLinkToAFileOutsideTheRootIsNotListed() {
        val outside = createTempDirectory("korgnote-outside")
        try {
            outside.resolve("o.org").writeText("* outside\n")
            java.nio.file.Files.createSymbolicLink(root.resolve("linked.org"), outside.resolve("o.org"))
            writeFile("a.org", "* a\n")

            assertEquals(listOf("a.org"), store.list().map { it.path })
            assertNull(store.meta("linked.org"))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun aLinkToANoteInsideTheRootIsANoteToo() {
        writeFile("a.org", "* a\n")
        java.nio.file.Files.createSymbolicLink(root.resolve("also-a.org"), root.resolve("a.org"))

        assertEquals(listOf("a.org", "also-a.org"), store.list().map { it.path })
        assertEquals("* a\n", store.read("also-a.org"))
    }

    // --- Two writers at once: the check and the move must be one step ---

    /** Runs [writers] at the same moment and returns what each came back with. */
    private fun <T> together(vararg writers: () -> T): List<T> {
        val start = java.util.concurrent.CyclicBarrier(writers.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(writers.size)
        try {
            return writers.map { writer -> pool.submit<T> { start.await(); writer() } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Two devices pushing from the same version: one wins and the other must merge, or an edit is lost. */
    @Test
    fun ofTwoPushesFromTheSameVersionOnlyOneWins() {
        repeat(ROUNDS) { round ->
            val base = "* base $round\n"
            writeFile("race.org", base)
            val etag = store.meta("race.org")!!.etag

            val results = together(
                { store.put("race.org", "* from one $round\n", ifMatch = etag) },
                { store.put("race.org", "* from two $round\n", ifMatch = etag) },
            )

            assertEquals(1, results.count { it is WriteResult.Written }, "round $round: $results")
            assertEquals(1, results.count { it is WriteResult.Conflict }, "round $round: $results")
        }
    }

    /** A note made at the same path on two devices: both must survive, one as a conflict copy. */
    @Test
    fun ofTwoCreationsOfTheSamePathOnlyOneWins() {
        repeat(ROUNDS) { round ->
            val path = "new-$round.org"

            val results = together(
                { store.put(path, "* from one\n", mustNotExist = true) },
                { store.put(path, "* from two\n", mustNotExist = true) },
            )

            assertEquals(1, results.count { it is WriteResult.Written }, "round $round: $results")
            assertEquals(1, results.count { it is WriteResult.Conflict }, "round $round: $results")
        }
    }

    /** A delete against a version a save has just replaced must not take the save to the trash. */
    @Test
    fun aDeleteAndAPushFromTheSameVersionDoNotBothWin() {
        repeat(ROUNDS) { round ->
            writeFile("race.org", "* base $round\n")
            val etag = store.meta("race.org")!!.etag

            val results = together(
                { store.put("race.org", "* edited $round\n", ifMatch = etag) },
                { store.delete("race.org", ifMatch = etag) },
            )

            assertEquals(1, results.count { it is WriteResult.Written }, "round $round: $results")
        }
    }

    private companion object {
        const val ROUNDS = 200
    }
}
