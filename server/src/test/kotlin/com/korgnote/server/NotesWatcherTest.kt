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
import kotlin.test.assertEquals
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
    private lateinit var watcher: NotesWatcher

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
        // A link is a note, and what it points at is its content, but `.git` is not watched: an edit
        // to the target there raises no event for the link. Only a walk of the tree finds it, as it
        // finds an edit whose event was dropped.
        val target = root.resolve(".git").createDirectories().resolve("target.org")
        target.writeText("* e\n")
        Files.createSymbolicLink(root.resolve("e.org"), target)
        startWatching(rescanEvery = 1.seconds)

        val before = journal.generation
        target.writeText("* e, edited\n")
        awaitChange(before, "e.org") { it.path == "e.org" && !it.deleted }

        // And it says so, rather than healing it quietly, once no event has come for it.
        val reported = withTimeoutOrNull(WAIT) { while (watcher.status.missed == 0) delay(100.milliseconds) }
        assertNotNull(reported, "the missed change was never reported")
        assertEquals(1, watcher.status.missed)
        assertEquals(listOf("e.org"), watcher.status.lastMissedPaths)
    }

    /** Syncthing keeps old versions in `.stversions`: neither a change to a note, nor a folder that moved. */
    @Test
    fun aDotFolderIsNotWatched() = runBlocking {
        val versions = root.resolve(".stversions").createDirectories()
        startWatching(rescanEvery = 1.seconds)
        assertEquals(2, watcher.status.folders, "the root and sub/")

        val before = journal.generation
        versions.resolve("a~1.org").writeText("* old a\n")
        root.resolve(".new").createDirectories().resolve("x.org").writeText("* x\n")
        // A real edit to wait for, by which time anything the two above caused has been seen.
        root.resolve("a.org").appendText("* edited\n")
        awaitChange(before, "edit of a.org") { it.path == "a.org" && !it.deleted }
        delay(GRACE + 2.seconds)

        assertEquals(listOf("a.org"), journal.changesSince(before)?.changes.orEmpty().map { it.path })
        assertEquals(0, watcher.status.rebuilds, "a dot folder appearing moved no folder of the library")
        assertEquals(0, watcher.status.missed)
    }

    @Test
    fun aSaveTheServerAlreadyAnnouncedIsNotCountedAsMissed() = runBlocking {
        startWatching(rescanEvery = 500.milliseconds)

        // What the sync API does on a save: write, then journal at once. The file event comes later,
        // and a check that runs in between must not take the save for an edit nobody announced.
        repeat(3) { round ->
            val file = root.resolve("sub/b.org")
            file.writeText("* b, saved through the API $round\n")
            journal.record("sub/b.org", FileStore(root).meta("sub/b.org")!!.etag)
            delay(1.seconds)
        }
        // Past the grace, so a wrong suspicion would have been reported by now.
        delay(GRACE + 2.seconds)
        assertEquals(0, watcher.status.missed, "missed: ${watcher.status.lastMissedPaths}")
    }

    @Test
    fun theStatusSaysWhatIsWatched() = runBlocking {
        startWatching()
        val status = watcher.status
        assertEquals(WatcherStatus.State.WATCHING, status.state)
        assertEquals(2, status.folders, "the root and sub/")
        assertEquals(3, status.notes, "a.org, sub/b.org and the probe")
        assertNotNull(status.lastCheckAt)
        assertNotNull(status.lastChangeAt)

        root.resolve("more").createDirectories()
        val rebuilt = withTimeoutOrNull(WAIT) { while (watcher.status.folders != 3) delay(100.milliseconds) }
        assertNotNull(rebuilt, "the new folder was never watched: ${watcher.status}")
        assertEquals(1, watcher.status.rebuilds)
    }

    /** Starts the watcher and waits until it is watching: until an edit it should see is journaled. */
    private suspend fun startWatching(rescanEvery: Duration = Duration.INFINITE) {
        watcher = NotesWatcher(root, FileStore(root), journal, rescanEvery, lateEventGrace = GRACE).also { it.start(scope) }
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

        /** Longer than the JDK's polling on macOS takes to report a change. */
        val GRACE = 4.seconds
    }
}
