package com.korgnote.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.*

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testOrgTree() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/v1/manifest")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Should contain some of our resource files in the tree
        assertTrue(body.contains("notes.org"))
        assertTrue(body.contains("inbox.org"))
    }

    @Test
    fun testContent() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/v1/files/notes.org")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testFileSystemLoading() {
        val tempDir = File("temp_notes")
        tempDir.mkdirs()
        try {
            val subDir = File(tempDir, "Sub")
            subDir.mkdirs()
            File(tempDir, "root.org").writeText("* Root")
            File(subDir, "leaf.org").writeText("* Leaf")

            System.setProperty("notes.dir", tempDir.path)

            testApplication {
                application {
                    module()
                }
                val response = client.get("/api/v1/manifest")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("root.org"))
                assertTrue(body.contains("Sub"))
                assertTrue(body.contains("leaf.org"))

                val contentResponse = client.get("/api/v1/files/Sub/leaf.org")
                assertEquals(HttpStatusCode.OK, contentResponse.status)
                assertEquals("* Leaf", contentResponse.bodyAsText())
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveContent() {
        val tempDir = File("temp_save_test")
        tempDir.mkdirs()
        try {
            val file = File(tempDir, "save.org")
            file.writeText("* Initial")

            System.setProperty("notes.dir", tempDir.path)

            testApplication {
                application {
                    module()
                }
                
                val newContent = "* Updated Content"
                val response = client.put("/api/v1/files/save.org") {
                    setBody(newContent)
                }
                
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(newContent, file.readText())
                
                // Also verify it's updated in the GET response
                val getResponse = client.get("/api/v1/files/save.org")
                assertEquals(newContent, getResponse.bodyAsText())
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTildeExpansion() {
        val home = System.getProperty("user.home")
        val tempDir = File(home, "temp_notes_tilde_test")
        tempDir.mkdirs()
        try {
            File(tempDir, "test.org").writeText("* Tilde")
            
            System.setProperty("notes.dir", "~/temp_notes_tilde_test")

            testApplication {
                application {
                    module()
                }
                val response = client.get("/api/v1/manifest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("test.org"))
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEnvVarExpansion() {
        val homePath = System.getenv("USERPROFILE") ?: System.getenv("HOME")
        if (homePath == null) return // Skip if we can't find a home env var
        
        val tempDir = File(homePath, "temp_notes_env_test")
        tempDir.mkdirs()
        try {
            File(tempDir, "env.org").writeText("* EnvVar")
            
            // Use %USERPROFILE% if on Windows, otherwise $HOME won't be expanded by my current simple logic
            // But the user specifically mentioned %HOMEPATH%, so let's test that style.
            val varToTest = if (System.getenv("USERPROFILE") != null) "USERPROFILE" else "HOME"
            System.setProperty("notes.dir", "%$varToTest%/temp_notes_env_test")

            testApplication {
                application {
                    module()
                }
                val response = client.get("/api/v1/manifest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("env.org"))
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }

    // --- KORGNOTE_REQUIRE_NOTES_DIR (T23) ---

    /** A mistyped volume path must stop the server, not quietly serve the samples. */
    @Test
    fun aRequiredNotesDirectoryThatIsMissingFailsStartup() {
        System.setProperty("notes.dir", "temp_notes_does_not_exist")
        try {
            val failure = assertFails {
                testApplication {
                    application { module(requireNotesDir = true) }
                    startApplication()
                }
            }
            assertTrue(failure.message.orEmpty().contains("does not exist"), failure.message)
        } finally {
            System.clearProperty("notes.dir")
        }
    }

    @Test
    fun aRequiredNotesDirectoryThatIsReadOnlyFailsStartup() {
        val tempDir = File("temp_notes_read_only")
        tempDir.mkdirs()
        tempDir.setWritable(false)
        System.setProperty("notes.dir", tempDir.path)
        try {
            val failure = assertFails {
                testApplication {
                    application { module(requireNotesDir = true) }
                    startApplication()
                }
            }
            assertTrue(failure.message.orEmpty().contains("not writable"), failure.message)
        } finally {
            System.clearProperty("notes.dir")
            tempDir.setWritable(true)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun aRequiredNotesDirectoryThatExistsIsServed() {
        val tempDir = File("temp_notes_required")
        tempDir.mkdirs()
        File(tempDir, "only.org").writeText("* Only")
        System.setProperty("notes.dir", tempDir.path)
        try {
            testApplication {
                application { module(requireNotesDir = true) }
                val body = client.get("/api/v1/manifest").bodyAsText()
                assertTrue(body.contains("only.org"))
                assertFalse(body.contains("inbox.org"), "the samples must not be mixed in")
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }

    // --- KORGNOTE_DISABLE_WEB ---

    @Test
    fun theWebClientIsServedAtTheRootWhenPresent() = withWebClient {
        testApplication {
            application { module() }
            assertTrue(client.get("/").bodyAsText().contains("web client"))
        }
    }

    @Test
    fun theWebClientCanBeTurnedOff() = withWebClient {
        testApplication {
            application { module(serveWebClient = false) }
            val body = client.get("/").bodyAsText()
            assertFalse(body.contains("web client"))
            assertTrue(body.startsWith("Ktor:"))
        }
    }

    private fun withWebClient(block: () -> Unit) {
        val webDir = File("temp_web_client")
        webDir.mkdirs()
        File(webDir, "index.html").writeText("<html>web client</html>")
        System.setProperty("wasm.dir", webDir.path)
        try {
            block()
        } finally {
            System.clearProperty("wasm.dir")
            webDir.deleteRecursively()
        }
    }
}
