package com.korgnote.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.request.put

/**
 * These notes are also edited in Emacs and delivered by Syncthing, so the server has to answer from
 * the disk rather than from anything it read at startup — and it must refuse to overwrite a change
 * it never handed out.
 */
class ExternalEditsTest {

    private val notesDir = File("temp_external_edits_test")

    @BeforeTest
    fun setUp() {
        notesDir.mkdirs()
        System.setProperty("notes.dir", notesDir.path)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("notes.dir")
        notesDir.deleteRecursively()
    }

    @Test
    fun anEditMadeOutsideTheServerIsVisibleOnTheNextRequest() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        assertEquals("* TODO before\n", client.get("/api/v1/files/inbox.org").bodyAsText())

        file.writeText("* TODO edited in Emacs\n")

        assertEquals("* TODO edited in Emacs\n", client.get("/api/v1/files/inbox.org").bodyAsText())
    }

    @Test
    fun aFileCreatedOutsideTheServerAppearsInTheTree() = testApplication {
        File(notesDir, "first.org").writeText("* one\n")
        application { module() }

        assertTrue(client.get("/api/v1/manifest").bodyAsText().contains("first.org"))

        File(notesDir, "second.org").writeText("* two\n")

        assertTrue(
            client.get("/api/v1/manifest").bodyAsText().contains("second.org"),
            "a file added after startup should show up",
        )
    }

    @Test
    fun aFileCreatedOutsideTheServerAppearsInOrgContent() = testApplication {
        File(notesDir, "first.org").writeText("* one\n")
        application { module() }
        client.get("/api/v1/bundle")

        File(notesDir, "second.org").writeText("* two\n")

        assertTrue(client.get("/api/v1/bundle").bodyAsText().contains("second.org"))
    }

    @Test
    fun savingDoesNotOverwriteAnEditTheClientNeverSaw() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        // The client reads the file, keeping the version it was given...
        val etag = client.get("/api/v1/files/inbox.org").headers[HttpHeaders.ETag]!!
        // ...then it changes on disk underneath...
        file.writeText("* TODO edited in Emacs\n")
        // ...and the client saves what it had, naming the version it edited.
        val response = client.put("/api/v1/files/inbox.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* TODO from the app\n")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            "* TODO edited in Emacs\n",
            file.readText(),
            "the edit made elsewhere must survive",
        )
    }

    @Test
    fun savingAgainAfterRereadingSucceeds() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        val stale = client.get("/api/v1/files/inbox.org").headers[HttpHeaders.ETag]!!
        file.writeText("* TODO edited in Emacs\n")
        assertEquals(
            HttpStatusCode.Conflict,
            client.put("/api/v1/files/inbox.org") {
                header(HttpHeaders.IfMatch, stale)
                setBody("* nope\n")
            }.status,
        )

        // Re-reading gives the current version, so the next save is accepted.
        val current = client.get("/api/v1/files/inbox.org").headers[HttpHeaders.ETag]!!
        val response = client.put("/api/v1/files/inbox.org") {
            header(HttpHeaders.IfMatch, current)
            setBody("* TODO reconciled\n")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("* TODO reconciled\n", file.readText())
    }

    @Test
    fun readsCarryAnEtagAndSavesCanUseIt() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        val read = client.get("/api/v1/files/inbox.org")
        val etag = read.headers[HttpHeaders.ETag]
        assertNotNull(etag, "reads should advertise the version they served")

        val accepted = client.put("/api/v1/files/inbox.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* TODO after\n")
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("* TODO after\n", file.readText())

        val staleAttempt = client.put("/api/v1/files/inbox.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* TODO stale\n")
        }
        assertEquals(HttpStatusCode.Conflict, staleAttempt.status)
        assertEquals("* TODO after\n", file.readText())
    }

    @Test
    fun savingRejectsPathsOutsideTheNotesDirectory() = testApplication {
        File(notesDir, "inbox.org").writeText("* TODO one\n")
        val outside = File(notesDir.parentFile, "outside_secret.org")
        outside.writeText("private\n")
        application { module() }

        try {
            val response = client.post("/api/v1/files/../outside_secret.org") { setBody("clobbered\n") }

            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                    response.status == HttpStatusCode.UnprocessableEntity ||
                    response.status == HttpStatusCode.BadRequest,
                "traversal should be refused, was ${response.status}",
            )
            assertEquals("private\n", outside.readText(), "nothing outside the notes directory may be written")
        } finally {
            outside.delete()
        }
    }
}
