package com.korgnote.server

import com.korgnote.common.pairing.sha256Hex
import io.ktor.client.request.delete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        application { module(FileStore(dir.toPath()), ServerAccess.localOnly()) }
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
    fun aDotFolderIsOutOfReachOfTheApi() = testApplication {
        val dir = notesDir("a.org" to "* A\n", ".stversions/a~1.org" to "* old A\n")
        serve(dir)

        val manifest = client.get("/api/v1/manifest").bodyAsText()
        assertTrue(!manifest.contains("stversions"), manifest)
        assertTrue(!client.get("/api/v1/bundle").bodyAsText().contains("stversions"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/files/.stversions/a~1.org").status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.put("/api/v1/files/.korgnote-trash/planted.org") { setBody("* planted\n") }.status,
        )
        assertTrue(!File(dir, ".korgnote-trash/planted.org").exists())
    }

    /**
     * The disk refusing is the server's problem, not the request's: a client must not be told its
     * path is invalid, and has no use for where the notes are on this computer. A folder named
     * `large` is there because the status used to be chosen by looking for that word in the message.
     */
    @Test
    fun aWriteTheDiskRefusesIsAServerErrorThatDoesNotSayWhere() = testApplication {
        val dir = notesDir("large/a.org" to "* A\n")
        val folder = File(dir, "large")
        serve(dir)
        val etag = client.get("/api/v1/files/large/a.org").headers[HttpHeaders.ETag]!!
        assertTrue(folder.setWritable(false))
        try {
            for (response in listOf(
                client.put("/api/v1/files/large/new.org") { setBody("* new\n") },
                client.put("/api/v1/files/large/a.org") {
                    header(HttpHeaders.IfMatch, etag)
                    setBody("* A edited\n")
                },
                client.delete("/api/v1/files/large/a.org") { header(HttpHeaders.IfMatch, etag) },
            )) {
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertTrue(!response.bodyAsText().contains(dir.path), response.bodyAsText())
            }
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun whatIsWrongWithARequestIsStillSaid() = testApplication {
        serve(notesDir("a.org" to "* A\n"))

        val invalid = client.put("/api/v1/files/notes.txt") { setBody("x") }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.delete("/api/v1/files/notes.txt") { header(HttpHeaders.IfMatch, "any") }.status,
        )
        val tooLarge = client.put("/api/v1/files/big.org") { setBody("x".repeat(32 * 1024 * 1024 + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
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

        // Asking since the generation that write made should report no further changes.
        val generation = manifestOf(client.get("/api/v1/manifest").bodyAsText())["generation"]!!.jsonPrimitive.long
        val body = client.get("/api/v1/manifest?since=$generation").bodyAsText()
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

    /**
     * The etag handed out must be the hash of the text handed out with it. Read apart, an edit landing
     * in between pairs the old etag with the new text, and the client's next push is a conflict with
     * itself.
     */
    @Test
    fun aFileAndItsEtagAreTheSameVersionWhileItIsBeingEdited() = testApplication {
        val dir = notesDir("a.org" to "* A\n")
        serve(dir)
        fun etagOf(text: String) = sha256Hex(text.encodeToByteArray()).take(32)

        val editing = AtomicBoolean(true)
        val editor = thread {
            var round = 0
            while (editing.get()) File(dir, "a.org").writeText("* A ${round++}\n".repeat(1 + round % 50))
        }
        try {
            repeat(300) {
                val file = client.get("/api/v1/files/a.org")
                assertEquals(etagOf(file.bodyAsText()), file.headers[HttpHeaders.ETag])
                val line = Json.parseToJsonElement(client.get("/api/v1/bundle").bodyAsText().trim()).jsonObject
                assertEquals(etagOf(line["text"]!!.jsonPrimitive.content), line["etag"]!!.jsonPrimitive.content)
            }
        } finally {
            editing.set(false)
            editor.join()
        }
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

    /** A quiet stream still says it is there, so that a client can tell it from a dead connection. */
    @Test
    fun aQuietChangeStreamSendsHeartbeats() = testApplication {
        val dir = notesDir("a.org" to "* A\n")
        application {
            routing {
                route("/api") { syncApiV1(FileStore(dir.toPath()), ChangeJournal(), heartbeatEvery = 100.milliseconds) }
            }
        }

        val lines = mutableListOf<String>()
        client.prepareGet("/api/v1/events").execute { response ->
            val body = response.bodyAsChannel()
            withTimeout(5.seconds) {
                while (lines.count { it == ": ping" } < 2) lines += body.readLine() ?: break
            }
        }

        assertEquals(": connected", lines.first())
        assertEquals(2, lines.count { it == ": ping" }, lines.toString())
    }

    private fun manifestOf(body: String) = Json.parseToJsonElement(body).jsonObject

    /**
     * A note deleted and then made again since the client last looked is there, not gone: a delta
     * that named it both changed and deleted had the client pull it and then remove it.
     */
    @Test
    fun aNoteDeletedAndMadeAgainIsListedAsThereNotAsDeleted() = testApplication {
        serve(notesDir("a.org" to "* A\n"))
        val since = manifestOf(client.get("/api/v1/manifest").bodyAsText())["generation"]!!.jsonPrimitive.long
        val etag = client.get("/api/v1/files/a.org").headers[HttpHeaders.ETag]!!
        client.delete("/api/v1/files/a.org") { header(HttpHeaders.IfMatch, etag) }
        client.put("/api/v1/files/a.org") {
            header(HttpHeaders.IfNoneMatch, "*")
            setBody("* A again\n")
        }

        val delta = manifestOf(client.get("/api/v1/manifest?since=$since").bodyAsText())

        assertEquals(listOf("a.org"), delta["entries"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals(emptyList(), delta["deleted"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
