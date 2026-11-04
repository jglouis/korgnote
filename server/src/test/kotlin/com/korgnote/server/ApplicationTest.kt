package com.korgnote.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.log
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.*

class ApplicationTest {

    /** The sample notes, which are test resources and do not ship. */
    private val samples get() = FileStore(File(javaClass.classLoader.getResource("org/")!!.toURI()).toPath())

    @Test
    fun testRoot() = testApplication {
        application {
            module(samples)
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testOrgTree() = testApplication {
        application {
            module(samples)
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
            module(samples)
        }
        val response = client.get("/api/v1/files/notes.org")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    /** Nothing to fall back on: an empty library, which a phone's edits are not written into. */
    @Test
    fun aMissingNotesDirectoryIsAnEmptyLibrary() = testApplication {
        application { module(ServerConfig(notesDir = File("temp_notes_does_not_exist")).openStore(log)) }
        val manifest = client.get("/api/v1/manifest")
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertTrue(manifest.bodyAsText().contains("\"entries\":[]"), manifest.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/files/a.org") { setBody("* a\n") }.status)
        assertFalse(File("temp_notes_does_not_exist").exists())
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

            testApplication {
                application {
                    module(FileStore(tempDir.toPath()))
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

            testApplication {
                application {
                    module(FileStore(tempDir.toPath()))
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
            
            val config = ServerConfig.fromEnvironment({ if (it == ServerConfig.NOTES_DIR_PROPERTY) "~/temp_notes_tilde_test" else null }, { null })

            testApplication {
                application {
                    module(config.openStore(log))
                }
                val response = client.get("/api/v1/manifest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("test.org"))
            }
        } finally {
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
            val path = "%$varToTest%/temp_notes_env_test"
            val config = ServerConfig.fromEnvironment({ if (it == ServerConfig.NOTES_DIR_PROPERTY) path else null }, System::getenv)

            testApplication {
                application {
                    module(config.openStore(log))
                }
                val response = client.get("/api/v1/manifest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("env.org"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // --- KORGNOTE_REQUIRE_NOTES_DIR (T23) ---

    /** A mistyped volume path must stop the server, not quietly serve an empty library. */
    @Test
    fun aRequiredNotesDirectoryThatIsMissingFailsStartup() {
        val config = ServerConfig(notesDir = File("temp_notes_does_not_exist"), requireNotesDir = true)
        val failure = assertFails {
            testApplication {
                application { module(config.openStore(log)) }
                startApplication()
            }
        }
        assertTrue(failure.message.orEmpty().contains("does not exist"), failure.message)
    }

    @Test
    fun aRequiredNotesDirectoryThatIsReadOnlyFailsStartup() {
        val tempDir = File("temp_notes_read_only")
        tempDir.mkdirs()
        tempDir.setWritable(false)
        val config = ServerConfig(notesDir = tempDir, requireNotesDir = true)
        try {
            val failure = assertFails {
                testApplication {
                    application { module(config.openStore(log)) }
                    startApplication()
                }
            }
            assertTrue(failure.message.orEmpty().contains("not writable"), failure.message)
        } finally {
            tempDir.setWritable(true)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun aRequiredNotesDirectoryThatExistsIsServed() {
        val tempDir = File("temp_notes_required")
        tempDir.mkdirs()
        File(tempDir, "only.org").writeText("* Only")
        val config = ServerConfig(notesDir = tempDir, requireNotesDir = true)
        try {
            testApplication {
                application { module(config.openStore(log)) }
                val body = client.get("/api/v1/manifest").bodyAsText()
                assertTrue(body.contains("only.org"))
                assertFalse(body.contains("inbox.org"), "the samples must not be mixed in")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // --- KORGNOTE_DISABLE_WEB ---

    private val noNotes get() = FileStore(java.nio.file.Path.of("temp_no_notes_here"), readOnly = true)

    @Test
    fun theWebClientIsServedAtTheRootWhenPresent() = withWebClient { webDir ->
        val config = ServerConfig(webClientDir = webDir.path)
        testApplication {
            application { module(noNotes, webClient = config.findWebClient(log)) }
            assertTrue(client.get("/").bodyAsText().contains("web client"))
        }
    }

    @Test
    fun theWebClientCanBeTurnedOff() = withWebClient { webDir ->
        val config = ServerConfig(webClientDir = webDir.path, serveWebClient = false)
        testApplication {
            application { module(noNotes, webClient = config.findWebClient(log)) }
            val body = client.get("/").bodyAsText()
            assertFalse(body.contains("web client"))
            assertTrue(body.startsWith("Ktor:"))
        }
    }

    private fun withWebClient(block: (File) -> Unit) {
        val webDir = File("temp_web_client")
        webDir.mkdirs()
        File(webDir, "index.html").writeText("<html>web client</html>")
        try {
            block(webDir)
        } finally {
            webDir.deleteRecursively()
        }
    }
}
