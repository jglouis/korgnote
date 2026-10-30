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
                result is WriteResult.Rejected || result is WriteResult.NotFound,
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
    fun onlyOrgFilesCanBeWritten() {
        writeFile("notes.txt", "plain\n")
        assertIs<WriteResult.Rejected>(store.write("notes.txt", "changed\n", null))
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

            assertIs<WriteResult.Rejected>(result)
            assertTrue(outside.toFile().list().isNullOrEmpty(), "nothing may be written outside the root")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
