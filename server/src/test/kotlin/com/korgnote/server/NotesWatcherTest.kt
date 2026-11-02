package com.korgnote.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The watcher against a real folder. A phone that syncs with `?since=` learns of an edit made on
 * the computer only through it, so a watcher that stops, or misses a folder, is a phone told that
 * nothing changed.
 *
 * The periodic rescan is off unless a test turns it on, so these show the events doing the work.
 * Where the platform has no native watch service (macOS), the JDK polls, so the waits are generous.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class NotesWatcherTest {

    private lateinit var root: Path
    private val journal = ChangeJournal()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("korgnote-watcher-test")
        root.resolve("a.org").writeText("* a\n")
        root.resolve("sub").createDirectories().resolve("b.org").writeText("* b\n")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun deletingAFolderDeletesItsNotesAndTheWatcherCarriesOn() = runBlocking {
        startWatching()

        val before = journal.generation
        root.resolve("sub").deleteRecursively()
        awaitChange(before, "deletion of sub/b.org") { it.path == "sub/b.org" && it.deleted }

        val after = journal.generation
        root.resolve("a.org").appendText("* edited after the folder went\n")
        awaitChange(after, "edit of a.org") { it.path == "a.org" && !it.deleted }
    }

    @Test
    fun renamingAFolderMovesItsNotesAndTheirNewPathsAreWatched() = runBlocking {
        startWatching()

        val before = journal.generation
        root.resolve("sub").moveTo(root.resolve("sub2"))
        awaitChange(before, "sub2/b.org") { it.path == "sub2/b.org" && !it.deleted }
        awaitChange(before, "deletion of sub/b.org") { it.path == "sub/b.org" && it.deleted }

        val after = journal.generation
        root.resolve("sub2/b.org").appendText("* edited in its new place\n")
        awaitChange(after, "edit of sub2/b.org") { it.path == "sub2/b.org" && !it.deleted }
    }

    @Test
    fun aFolderMovedInBringsTheNotesAlreadyInIt() = runBlocking {
        startWatching()
        val outside = Files.createTempDirectory("korgnote-watcher-outside")
        try {
            outside.resolve("project").createDirectories().resolve("deep").createDirectories()
                .resolve("d.org").writeText("* d\n")

            val before = journal.generation
            outside.resolve("project").moveTo(root.resolve("project"))
            awaitChange(before, "project/deep/d.org") { it.path == "project/deep/d.org" && !it.deleted }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun aNoteWrittenStraightIntoANewFolderIsSeen() = runBlocking {
        startWatching()

        val before = journal.generation
        root.resolve("new").createDirectories().resolve("c.org").writeText("* c\n")
        awaitChange(before, "new/c.org") { it.path == "new/c.org" && !it.deleted }
    }

    @Test
    fun theRescanFindsWhatNoEventReported() = runBlocking {
        // `.git` is not watched but is listed, so a note written there raises no event: only a walk
        // of the tree finds it, as it finds an edit whose event was dropped.
        val git = root.resolve(".git").createDirectories()
        startWatching(rescanEvery = 1.seconds)

        val before = journal.generation
        git.resolve("e.org").writeText("* e\n")
        awaitChange(before, ".git/e.org") { it.path == ".git/e.org" && !it.deleted }
    }

    /** Starts the watcher and waits until it is watching: until an edit it should see is journaled. */
    private suspend fun startWatching(rescanEvery: Duration = Duration.INFINITE) {
        NotesWatcher(root, FileStore(root), journal, rescanEvery).start(scope)
        val probe = root.resolve("probe.org")
        val start = journal.generation
        val seen = withTimeoutOrNull(WAIT) {
            var round = 0
            while (journal.changesSince(start)?.changes.orEmpty().none { it.path == "probe.org" }) {
                probe.writeText("* probe ${round++}\n")
                delay(250.milliseconds)
            }
        }
        assertNotNull(seen, "the watcher never started watching")
    }

    private suspend fun awaitChange(since: Long, what: String, matches: (ChangeEntry) -> Boolean) {
        val found = withTimeoutOrNull(WAIT) {
            while (journal.changesSince(since)?.changes.orEmpty().none(matches)) delay(100.milliseconds)
        }
        if (found == null) fail("no $what in the journal: ${journal.changesSince(since)?.changes}")
    }

    private companion object {
        val WAIT = 20.seconds
    }
}
