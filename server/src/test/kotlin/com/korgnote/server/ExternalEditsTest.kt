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

        assertEquals("* TODO before\n", client.get("/api/content/inbox.org").bodyAsText())

        file.writeText("* TODO edited in Emacs\n")

        assertEquals("* TODO edited in Emacs\n", client.get("/api/content/inbox.org").bodyAsText())
    }

    @Test
    fun aFileCreatedOutsideTheServerAppearsInTheTree() = testApplication {
        File(notesDir, "first.org").writeText("* one\n")
        application { module() }

        assertTrue(client.get("/api/org-tree-paths").bodyAsText().contains("first.org"))

        File(notesDir, "second.org").writeText("* two\n")

        assertTrue(
            client.get("/api/org-tree-paths").bodyAsText().contains("second.org"),
            "a file added after startup should show up",
        )
    }

    @Test
    fun aFileCreatedOutsideTheServerAppearsInOrgContent() = testApplication {
        File(notesDir, "first.org").writeText("* one\n")
        application { module() }
        client.get("/api/org-content")

        File(notesDir, "second.org").writeText("* two\n")

        assertTrue(client.get("/api/org-content").bodyAsText().contains("second.org"))
    }

    @Test
    fun savingDoesNotOverwriteAnEditTheClientNeverSaw() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        // The client reads the file...
        client.get("/api/content/inbox.org")
        // ...then it changes on disk underneath...
        file.writeText("* TODO edited in Emacs\n")
        // ...and the client saves what it had.
        val response = client.post("/api/content/inbox.org") { setBody("* TODO from the app\n") }

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

        client.get("/api/content/inbox.org")
        file.writeText("* TODO edited in Emacs\n")
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/api/content/inbox.org") { setBody("* nope\n") }.status,
        )

        // Re-reading establishes the new baseline, so the next save is accepted.
        client.get("/api/content/inbox.org")
        val response = client.post("/api/content/inbox.org") { setBody("* TODO reconciled\n") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("* TODO reconciled\n", file.readText())
    }

    @Test
    fun readsCarryAnEtagAndSavesCanUseIt() = testApplication {
        val file = File(notesDir, "inbox.org")
        file.writeText("* TODO before\n")
        application { module() }

        val read = client.get("/api/content/inbox.org")
        val etag = read.headers[HttpHeaders.ETag]
        assertNotNull(etag, "reads should advertise the version they served")

        val accepted = client.post("/api/content/inbox.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* TODO after\n")
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("* TODO after\n", file.readText())

        val stale = client.post("/api/content/inbox.org") {
            header(HttpHeaders.IfMatch, etag)
            setBody("* TODO stale\n")
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("* TODO after\n", file.readText())
    }

    @Test
    fun savingRejectsPathsOutsideTheNotesDirectory() = testApplication {
        File(notesDir, "inbox.org").writeText("* TODO one\n")
        val outside = File(notesDir.parentFile, "outside_secret.org")
        outside.writeText("private\n")
        application { module() }

        try {
            val response = client.post("/api/content/../outside_secret.org") { setBody("clobbered\n") }

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
