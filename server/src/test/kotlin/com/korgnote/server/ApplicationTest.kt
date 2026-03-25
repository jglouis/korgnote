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
        val response = client.get("/api/org-tree-paths")
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
        val response = client.get("/api/content/notes.org")
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
                val response = client.get("/api/org-tree-paths")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("root.org"))
                assertTrue(body.contains("Sub"))
                assertTrue(body.contains("leaf.org"))

                val contentResponse = client.get("/api/content/Sub/leaf.org")
                assertEquals(HttpStatusCode.OK, contentResponse.status)
                assertEquals("* Leaf", contentResponse.bodyAsText())
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
                val response = client.get("/api/org-tree-paths")
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
                val response = client.get("/api/org-tree-paths")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("env.org"))
            }
        } finally {
            System.clearProperty("notes.dir")
            tempDir.deleteRecursively()
        }
    }
}