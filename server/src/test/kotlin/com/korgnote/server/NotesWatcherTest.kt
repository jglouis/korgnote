package com.korgnote.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The watcher is what makes an edit in Emacs visible to the clients.
 *
 * This is a real filesystem test with real timing, so it allows a generous window: the JDK's
 * `WatchService` falls back to polling on macOS, where the latency is seconds rather than
 * milliseconds. The measured figure is printed so a regression in that latency is visible.
 */
class NotesWatcherTest {

    private val roots = mutableListOf<File>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun notesDir(): File =
        Files.createTempDirectory("korgnote-watch").toFile().also { roots.add(it) }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun anEditMadeOutsideTheServerBecomesAChange() = runBlocking {
        val dir = notesDir()
        File(dir, "a.org").writeText("* Before\n")

        val store = FileStore(dir.toPath())
        val journal = ChangeJournal()
        NotesWatcher(dir.toPath(), store, journal).start(scope)

        // Give the watcher a moment to register before touching anything.
        kotlinx.coroutines.delay(500)
        val started = System.currentTimeMillis()
        File(dir, "a.org").writeText("* Edited in Emacs\n")

        val seen = withTimeoutOrNull(30_000) {
            journal.events.first { it.path == "a.org" }
        }
        val elapsed = System.currentTimeMillis() - started
        println("WATCH detected an external edit in ${elapsed}ms")

        assertNotNull(seen, "an external edit must reach the journal")
        assertEquals("a.org", seen.path)
        assertTrue(seen.generation > 0)
    }

    @Test
    fun aFileCreatedOutsideTheServerBecomesAChange() = runBlocking {
        val dir = notesDir()
        val store = FileStore(dir.toPath())
        val journal = ChangeJournal()
        NotesWatcher(dir.toPath(), store, journal).start(scope)

        kotlinx.coroutines.delay(500)
        File(dir, "fresh.org").writeText("* New\n")

        val seen = withTimeoutOrNull(30_000) {
            journal.events.first { it.path == "fresh.org" }
        }
        assertNotNull(seen, "a new file must reach the journal")
        assertEquals(false, seen.deleted)
    }
}
