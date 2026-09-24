package com.korgnote.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sync API's contract, as `docs/05-sync-protocol.md` defines it.
 *
 * Every case here is one a client will depend on: a conditional fetch that saves bandwidth, a
 * conditional push that refuses to clobber, and a manifest that can be diffed.
 */
class SyncApiV1Test {

    private val roots = mutableListOf<File>()

    private fun notesDir(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("korgnote-v1").toFile().also { roots.add(it) }
        files.forEach { (name, text) ->
            File(dir, name).apply { parentFile.mkdirs(); writeText(text) }
        }
        return dir
    }

    @AfterTest
    fun cleanUp() = roots.forEach { it.deleteRecursively() }

    private fun ApplicationTestBuilder.serve(dir: File) {
        System.setProperty("notes.dir", dir.absolutePath)
        application { module(ServerSecurity.open()) }
    }

    @Test
    fun theManifestListsEveryFileWithItsEtag() = testApplication {
        serve(notesDir("a.org" to "* A\n", "sub/b.org" to "* B\n"))

        val body = client.get("/api/v1/manifest").bodyAsText()

        assertTrue(body.contains("\"complete\":true"), body)
        assertTrue(body.contains("a.org"))
        assertTrue(body.contains("sub/b.org"))
    }

    /** The bandwidth saver: nothing changed, so send nothing. */
    @Test
    fun aConditionalGetReturns304WhenNothingChanged() = testApplication {
        serve(notesDir("a.org" to "* A\n"))

        val first = client.get("/api/v1/files/a.org")
        assertEquals(HttpStatusCode.OK, first.status)
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)
        assertEquals("* A\n", first.bodyAsText())

        val second = client.get("/api/v1/files/a.org") {
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals("", second.bodyAsText())
    }

    @Test
    fun aPushWithTheCurrentEtagSucceedsAndReturnsTheNewOne() = testApplication {
        serve(notesDir("a.org" to "* A\n"))
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!

        val response = client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* A edited\n")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("etag"))
        assertEquals("* A edited\n", client.get("/api/v1/files/a.org").bodyAsText())
    }

    /** The one that protects an edit made in Emacs while the client was typing. */
    @Test
    fun aPushWithAStaleEtagIsRefusedWith409() = testApplication {
        val dir = notesDir("a.org" to "* A\n")
        serve(dir)
        val stale = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!

        // Someone else edits the file.
        File(dir, "a.org").writeText("* Changed in Emacs\n")

        val response = client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfMatch, stale)
            setBody("* A from the client\n")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertNotNull(response.headers[HttpHeaders.ETag], "the client needs the current etag to merge")
        assertEquals("* Changed in Emacs\n", File(dir, "a.org").readText(), "nothing was overwritten")
    }

    @Test
    fun createOnlyRefusesAnExistingFile() = testApplication {
        serve(notesDir("a.org" to "* A\n"))

        val response = client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfNoneMatch, "*")
            setBody("* new\n")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun createOnlyMakesANewFileIncludingItsDirectory() = testApplication {
        val dir = notesDir()
        serve(dir)

        val response = client.put("/api/v1/files/projects/new.org") {
            header(HttpHeaders.IfNoneMatch, "*")
            setBody("* Captured\n")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("* Captured\n", File(dir, "projects/new.org").readText())
    }

    @Test
    fun deletingMovesTheFileToTrashRatherThanUnlinkingIt() = testApplication {
        val dir = notesDir("gone.org" to "* Gone\n")
        serve(dir)
        val etag = client.get("/api/v1/files/gone.org").headers[HttpHeaders.ETag]!!

        val response = client.delete("/api/v1/files/gone.org") {
            header(HttpHeaders.IfMatch, etag)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(!File(dir, "gone.org").exists())
        val trashed = File(dir, ".korgnote-trash").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, trashed.size, "the note should be recoverable")
        assertEquals("* Gone\n", trashed.single().readText())
    }

    @Test
    fun deletingWithoutAnEtagIsRefused() = testApplication {
        serve(notesDir("a.org" to "* A\n"))
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.delete("/api/v1/files/a.org").status,
        )
    }

    @Test
    fun trashedNotesAreNotPartOfTheLibrary() = testApplication {
        val dir = notesDir("a.org" to "* A\n")
        serve(dir)
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!
        client.delete("/api/v1/files/a.org") { header(HttpHeaders.IfMatch, etag) }

        val manifest = client.get("/api/v1/manifest").bodyAsText()
        assertTrue(!manifest.contains("a.org"), manifest)
    }

    @Test
    fun theBundleStreamsOneJsonObjectPerFile() = testApplication {
        serve(notesDir("a.org" to "* A\n", "b.org" to "* B\n"))

        val lines = client.get("/api/v1/bundle").bodyAsText().trim().lines()

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.contains("\"path\"") && it.contains("\"etag\"") && it.contains("\"text\"") })
    }

    @Test
    fun aManifestSinceTheCurrentGenerationIsEmptyAndIncomplete() = testApplication {
        serve(notesDir("a.org" to "* A\n"))
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!
        client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* A2\n")
        }

        // Generation 1 was that write; asking since 1 should report no further changes.
        val body = client.get("/api/v1/manifest?since=1").bodyAsText()
        assertTrue(body.contains("\"complete\":false"), body)
        assertTrue(body.contains("\"entries\":[]"), body)
    }

    @Test
    fun anUnknownGenerationFallsBackToAFullManifest() = testApplication {
        serve(notesDir("a.org" to "* A\n"))

        val body = client.get("/api/v1/manifest?since=999").bodyAsText()
        assertTrue(body.contains("\"complete\":true"), body)
        assertTrue(body.contains("a.org"))
    }

    @Test
    fun aPushBumpsTheGenerationSoClientsCanDiff() = testApplication {
        serve(notesDir("a.org" to "* A\n"))
        val before = client.get("/api/v1/manifest").bodyAsText()
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!

        client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* A2\n")
        }

        val after = client.get("/api/v1/manifest").bodyAsText()
        assertTrue(before != after, "the generation must move when a file changes")
    }

    @Test
    fun pathsThatEscapeTheLibraryAreRefused() = testApplication {
        serve(notesDir("a.org" to "* A\n"))

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/files/../../etc/passwd.org").status,
        )
    }

    /** The app percent-encodes each segment (T20), so a name with a space is still one note. */
    @Test
    fun anEncodedPathAddressesTheNoteItNames() = testApplication {
        val dir = notesDir()
        serve(dir)

        val created = client.put("/api/v1/files/archive/old%20notes.org") {
            header(HttpHeaders.IfNoneMatch, "*")
            setBody("* Kept\n")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals("* Kept\n", File(dir, "archive/old notes.org").readText())
        assertEquals("* Kept\n", client.get("/api/v1/files/archive/old%20notes.org").bodyAsText())
    }

    /** A delete made against an old version must not take a newer edit with it. */
    @Test
    fun deletingAVersionThatHasSinceChangedIsRefused() = testApplication {
        val dir = notesDir("a.org" to "* A\n")
        serve(dir)
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!
        File(dir, "a.org").writeText("* A, edited in Emacs\n")

        val response = client.delete("/api/v1/files/a.org") { header(HttpHeaders.IfMatch, etag) }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("* A, edited in Emacs\n", File(dir, "a.org").readText())
    }
}
