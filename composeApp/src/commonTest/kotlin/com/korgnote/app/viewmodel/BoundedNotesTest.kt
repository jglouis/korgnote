package com.korgnote.app.viewmodel

import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.Reminder
import com.korgnote.common.index.IndexPart
import com.korgnote.common.index.NoteBudgets
import com.korgnote.common.index.NoteIndex
import com.korgnote.common.index.NoteIndexFile
import com.korgnote.common.index.factsOf
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.replica.contentHash
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.NoteFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The notes when they do not fit in memory, which is decided here by the budgets rather than by making
 * tests allocate gigabytes: a library that does not fit has to do everything one that fits does, only
 * with a moment's wait, and the calendar without even that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedNotesTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A folder in which zbig.org takes until [gate] says so to read, and [failing] cannot be read at all. */
    private class SlowFolder(private val files: Map<String, Pair<String, FileStamp>>, private val gated: Boolean = true) : NoteFolder {
        val gate = CompletableDeferred<Unit>()
        val failing = mutableSetOf<String>()
        var written = mutableMapOf<String, String>()
        override suspend fun list() = files.map { (path, file) -> FolderEntry(path, file.second) }.sortedBy { it.path }
        override suspend fun read(path: String): String? {
            if (path in failing) throw IllegalStateException("cannot be reached")
            if (gated && path == "zbig.org") gate.await()
            return files[path]?.first
        }
        override suspend fun write(path: String, content: String): FileStamp {
            written[path] = content
            return FileStamp(2, content.length.toLong())
        }
        override suspend fun delete(path: String) = error("not in this test")
        override suspend fun stamp(path: String) = files[path]?.second
    }

    /** Work sent here stays half done until this test says. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    private class MemoryIndexFile : NoteIndexFile {
        val parts = mutableMapOf<IndexPart, List<String>>()
        override suspend fun read(part: IndexPart, line: (String) -> Unit) = parts[part].orEmpty().forEach(line)
        override suspend fun write(part: IndexPart, lines: Sequence<String>) {
            parts[part] = lines.toList()
        }
    }

    /** Nothing fits but the note on screen: every note has to be read each time it is wanted. */
    private val nothingFits = NoteBudgets(texts = 0, parses = 0, folderTexts = 0, facts = 1L shl 30, maxNoteBytes = 1L shl 30)

    private val notes = (1..20).associate { i ->
        "n${i + 10}.org" to "#+TITLE: Note $i\n* TODO Heading $i\nSCHEDULED: <2026-11-05 Thu>\nThe word common is in every note, and word$i only here.\n"
    }

    private fun library(storage: StallingReplicaStorage, budgets: NoteBudgets = nothingFits, file: NoteIndexFile? = null) =
        NoteLibrary(DocumentStore(storage), if (file != null) NoteIndex(file, budgets) else NoteIndex(budgets = budgets), budgets)

    private fun storageOf(files: Map<String, String> = notes) = StallingReplicaStorage(InMemoryReplicaStorage(files))

    private fun viewModel(
        storage: StallingReplicaStorage,
        file: NoteIndexFile = MemoryIndexFile(),
        budgets: NoteBudgets = nothingFits,
        background: CoroutineDispatcher = UnconfinedTestDispatcher(),
    ): NotesViewModel {
        val store = DocumentStore(storage)
        return NotesViewModel(
            SyncEngine(store, FakeSyncApi()),
            store,
            today = { LocalDate(2026, 11, 5) },
            background = background,
            noteIndex = NoteIndex(file, budgets),
            noteBudgets = budgets,
        )
    }

    @Test
    fun aNoteThatDoesNotFitIsStillThereWhenAskedFor() = runTest {
        val storage = storageOf()
        val library = library(storage)
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        assertEquals(notes.keys, library.paths)
        assertNull(library.textAtHand("n11.org"), "not kept")
        assertEquals(notes["n11.org"], library.text("n11.org"))
        assertEquals("Heading 1", assertNotNull(library.document("n11.org")).children.filterIsInstance<com.korgnote.common.org.OrgSection>().single().title)
    }

    @Test
    fun theFactsDoNotNeedTheTextsToBeKept() = runTest {
        val storage = storageOf()
        val library = library(storage)
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        assertEquals(notes.mapValues { (path, text) -> factsOf(path, text) }, library.facts)
    }

    @Test
    fun theNoteOnScreenStaysHoweverLittleFits() = runTest {
        val storage = storageOf()
        val library = library(storage)
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        library.keepAtHand("n11.org")
        library.document("n11.org")
        library.document("n12.org")

        assertEquals(notes["n11.org"], library.textAtHand("n11.org"))
        assertNotNull(library.parsedAtHand("n11.org"))
        assertNull(library.textAtHand("n12.org"))
    }

    /** What makes a library that fits exactly as quick as if there were no limit. */
    @Test
    fun aLibraryThatFitsIsKeptWhole() = runTest {
        val storage = storageOf()
        val library = library(storage, NoteBudgets.of(heap = 256L * 1024 * 1024))
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        assertEquals(notes, library.atHand())
        assertTrue(library.notAtHand().isEmpty())
    }

    /** Coming back to the same notes reads none of them, with or without room for them. */
    @Test
    fun aSecondRunReadsNothingToKnowTheFacts() = runTest {
        val file = MemoryIndexFile()
        val here = UnconfinedTestDispatcher(testScheduler)
        library(storageOf(), file = file).reload(parseOn = here)

        val storage = storageOf()
        val library = library(storage, file = file)
        library.loadIndex()
        assertEquals(notes.keys, library.facts.keys, "known before anything is listed")
        assertEquals(notes.keys, library.paths, "and listed from what was kept")

        library.list()
        assertEquals(0L, library.bytesToScan())
        storage.read.clear()
        library.scan(here)
        assertEquals(emptyList(), storage.read)
    }

    /** The calendar, as soon as the app opens, with no note read and none listed. */
    @Test
    fun theCalendarIsThereBeforeAnyNoteIsRead() = runTest {
        val file = MemoryIndexFile()
        viewModel(storageOf(), file).also { first ->
            first.server.load()
            advanceUntilIdle()
            assertEquals(20, first.agenda.index.entries.size)
        }

        val storage = storageOf()
        val second = viewModel(storage, file)
        // Nothing has told it to load: all it has done is start.
        advanceUntilIdle()

        assertEquals(20, second.agenda.index.entries.size)
        assertEquals(emptyList(), storage.read)
    }

    /** A calendar with nothing on a day must not say so while the notes are still being read. */
    @Test
    fun itIsStillReadingUntilTheNotesAreKnown() = runTest {
        val storage = storageOf()
        val library = library(storage)
        assertTrue(library.stillReading, "nothing known yet")
        library.loadDated()
        assertTrue(library.stillReading, "nothing was kept from a last run")

        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))
        assertFalse(library.stillReading)
    }

    @Test
    fun whatWasKeptIsEnoughToSayWhatIsPlanned() = runTest {
        val file = MemoryIndexFile()
        library(storageOf(), file = file).reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        val library = library(storageOf(), file = file)
        library.loadDated()
        assertFalse(library.stillReading)
    }

    /** Only what changed: what makes it instant at the second start and every one after. */
    @Test
    fun aNoteThatChangedWhileTheAppWasClosedIsTheOnlyOneRead() = runTest {
        val file = MemoryIndexFile()
        val here = UnconfinedTestDispatcher(testScheduler)
        library(storageOf(), file = file).reload(parseOn = here)

        val changed = notes + ("n11.org" to "* TODO Moved\nSCHEDULED: <2026-11-06 Fri>\n")
        val storage = storageOf(changed)
        val library = library(storage, file = file)
        library.loadIndex()
        library.list()
        storage.read.clear()
        library.scan(here)

        assertEquals(listOf("n11.org"), storage.read)
        assertEquals("Moved", library.facts.getValue("n11.org").agenda.single().title)
    }

    /** With every note at hand a search is whole at once: no job, no bar, as it always was. */
    @Test
    fun aSearchOverWhatFitsIsWholeAtOnce() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage, budgets = NoteBudgets.of(heap = 256L * 1024 * 1024))
        vm.server.load()
        advanceUntilIdle()
        storage.read.clear()

        vm.search.run("common")

        assertEquals(20, vm.search.results.size)
        assertFalse(vm.search.searching)
        assertEquals(emptyList(), storage.read, "nothing was read for it")
    }

    @Test
    fun aSearchFindsWhatIsNotAtHand() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()

        vm.search.run("word7")
        advanceUntilIdle()

        assertEquals(listOf("n17.org"), vm.search.results.map { it.path })
        assertFalse(vm.search.searching)
    }

    /** What is in memory comes first, and the rest comes in below it as it is read. */
    @Test
    fun aSearchShowsWhatIsAtHandFirstAndTheRestAsItComes() = runTest {
        // A read stalled and let go of here comes back through the same scheduler.
        val shared = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(shared)
        val storage = storageOf()
        val vm = viewModel(storage, background = shared)
        vm.server.load()
        advanceUntilIdle()
        // The open note is what is at hand here.
        vm.onFileSelected("n20.org")
        advanceUntilIdle()
        val gate = storage.stallRead("n11.org")

        vm.search.run("common")
        runCurrent()

        assertEquals(listOf("n20.org"), vm.search.results.map { it.path }, "at once")
        assertTrue(vm.search.searching)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(20, vm.search.results.size)
        assertEquals("n20.org", vm.search.results.first().path)
        assertFalse(vm.search.searching)
    }

    /** The notes changed and the search runs again: the pass under way must not also go on and land. */
    @Test
    fun aSearchRunAgainDoesNotCountTwice() = runTest {
        val shared = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(shared)
        val storage = storageOf()
        val vm = viewModel(storage, background = shared)
        vm.server.load()
        advanceUntilIdle()
        val gate = storage.stallRead("n12.org")
        vm.search.run("common")
        runCurrent()
        assertTrue(vm.search.searching)
        storage.read.clear()

        vm.search.refresh()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(storage.read.distinct(), storage.read, "the first pass read no further")
        val paths = vm.search.results.map { it.path }
        assertEquals(paths.distinct(), paths, "each note once")
        assertEquals(20, paths.size)
    }

    /**
     * Over a first scan, the smallest notes stay in memory rather than the last ones read, which are the
     * biggest: they are the most notes for the room, and what a search then finds at once.
     */
    @Test
    fun aScanKeepsTheSmallestNotesAtHand() = runTest {
        val files = (1..9).associate { i -> "n$i.org" to "* Heading\n" + "x".repeat(i * 100) + "\n" }
        val room = (100 + 200 + 300 + 3 * 20L) * 2 + 3 * 128 + 10
        val library = library(storageOf(files), nothingFits.copy(texts = room))

        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        assertEquals(setOf("n1.org", "n2.org", "n3.org"), library.atHand().keys)
    }

    /** So many megabytes at hand would hold up the screen: they are gone through away from it. */
    @Test
    fun aLotInMemoryIsNotSearchedOnTheSpot() = runTest {
        val long = "* Heading\nfindme\n" + "x".repeat(9 * 1024 * 1024) + "\n"
        val storage = storageOf(mapOf("long.org" to long, "short.org" to "* Other\nfindme too\n"))
        val background = StandardTestDispatcher(testScheduler)
        val store = DocumentStore(storage)
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()),
            store,
            background = background,
            noteBudgets = NoteBudgets.of(heap = 1L shl 30),
        )
        vm.server.load()
        advanceUntilIdle()
        assertEquals(setOf("long.org", "short.org"), vm.library.atHand().keys)

        vm.search.run("findme")
        assertEquals(emptyList(), vm.search.results, "none of it was done here")
        assertTrue(vm.search.searching)

        advanceUntilIdle()
        assertEquals(setOf("long.org", "short.org"), vm.search.results.map { it.path }.toSet())
        assertFalse(vm.search.searching)
    }

    /** Another letter typed stops the reading: the old search must not go on reading, nor land. */
    @Test
    fun aNewSearchStopsTheOldOne() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        // Not the first: that one has been opened, and is at hand.
        val gate = storage.stallRead("n12.org")

        vm.search.run("common")
        runCurrent()
        assertTrue(vm.search.searching)
        storage.read.clear()

        vm.search.run("word3")
        // The first is cancelled while it waits on that read, and this one reads through to the end.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("n13.org"), vm.search.results.map { it.path })
        assertFalse(vm.search.searching)
    }

    @Test
    fun clearingTheSearchStopsIt() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        val gate = storage.stallRead("n12.org")
        vm.search.run("common")
        runCurrent()
        assertTrue(vm.search.searching)

        vm.search.onQueryChanged("")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), vm.search.results)
        assertFalse(vm.search.searching)
    }

    /** From the calendar, on a note nowhere in memory: the edit is made, on the note as it is. */
    @Test
    fun aKeywordIsSetInANoteThatIsNotAtHand() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        val entry = vm.agenda.index.entries.first { it.file == "n11.org" }

        vm.stateNotes.setTodo(entry.file, entry.headingOffset, "DONE")
        advanceUntilIdle()

        val written = assertNotNull(storage.read("n11.org"))
        assertTrue("* DONE Heading 1" in written, written)
        assertEquals("The word common is in every note, and word1 only here.", written.substringAfter("<2026-11-05 Thu>\n").substringBefore('\n'))
        assertEquals(19, vm.agenda.index.entries.count { it.todo == "TODO" }, "and the calendar has heard")
    }

    @Test
    fun aNoteThatDoesNotFitOpensReadsAndSaves() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()

        vm.onFileSelected("n12.org")
        advanceUntilIdle()

        assertEquals(notes["n12.org"], vm.note.content)
        assertFalse(vm.note.loading)
    }

    /** A note says it will not open; nothing it stands in for can be saved over it. */
    @Test
    fun aNoteTooBigToHoldSaysSoAndIsNotRead() = runTest {
        val big = "* Big\n" + "x".repeat(10_000) + "\n"
        val storage = storageOf(notes + ("zbig.org" to big))
        val budgets = nothingFits.copy(maxNoteBytes = 1_000)
        val vm = viewModel(storage, budgets = budgets)
        vm.server.load()
        advanceUntilIdle()
        assertTrue("zbig.org" in vm.library.paths, "it is listed")
        storage.read.clear()

        vm.onFileSelected("zbig.org")
        advanceUntilIdle()

        assertEquals(emptyList(), storage.read)
        assertFalse("xxx" in vm.note.content)
        assertNull(vm.note.replicaText, "what is shown is not the note")
        assertFalse(vm.note.loading)
    }

    /**
     * The calendar's offset is into the text it knew. With that text gone and the note changed since,
     * the same offset is on another heading: the wrong line must not be changed.
     */
    @Test
    fun anEditOnANoteThatMovedOnSinceItWasShownIsRefusedAndTheNoteLeftAlone() = runTest {
        val note = "* TODO Report\nSCHEDULED: <2026-11-05 Thu>\n* TODO Other\nSCHEDULED: <2026-11-05 Thu>\n"
        val storage = storageOf(notes + ("work.org" to note))
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        val other = vm.agenda.index.entries.single { it.title == "Other" }
        // Synced from another computer, say, and not listed yet.
        val moved = "* TODO From elsewhere\n* TODO A longer heading that pushes everything along\n$note"
        storage.write("work.org", moved)

        vm.stateNotes.setTodo(other.file, other.headingOffset, "DONE")
        while (vm.lastErrorMessage.isEmpty()) {
            withContext(Dispatchers.Default) { delay(5.milliseconds) }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(moved, storage.read("work.org"), "nothing was changed")
        assertTrue(vm.lastErrorMessage.isNotEmpty(), "and it says so")
        // What is shown has caught up, so that trying again works.
        vm.clearError()
        val again = vm.agenda.index.entries.single { it.title == "Other" }
        vm.stateNotes.setTodo(again.file, again.headingOffset, "DONE")
        advanceUntilIdle()
        assertEquals(moved.replace("* TODO Other", "* DONE Other"), storage.read("work.org"))
    }

    /**
     * Listed, but it cannot be read, as with a cloud folder gone offline: what is on screen is the note
     * before it, and it must not end up in this one.
     */
    @Test
    fun aNoteThatCannotBeReadShowsNoOtherNoteAndCannotBeWrittenOver() = runTest {
        val folder = SlowFolder(notes.mapValues { (_, text) -> text to FileStamp(1, text.length.toLong()) }, gated = false)
        val storage = FolderNoteStorage(folder, textBudget = 0) { "20261105-120000" }
        val store = DocumentStore(storage)
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()), store, background = UnconfinedTestDispatcher(),
            noteIndex = NoteIndex(budgets = nothingFits), noteBudgets = nothingFits, folder = storage,
        )
        vm.server.load()
        advanceUntilIdle()
        vm.onFileSelected("n11.org")
        advanceUntilIdle()
        assertEquals(notes["n11.org"], vm.note.content)
        folder.failing += "n12.org"

        vm.onFileSelected("n12.org")
        advanceUntilIdle()

        assertFalse("Note 1\n" in vm.note.content, "the other note is not shown as this one: ${vm.note.content}")
        assertTrue(vm.note.refused)
        vm.editor.beginEdit(0)
        assertNull(vm.editor.editingIndex, "and it cannot be edited")
        vm.changes.saveCurrentFile()
        advanceUntilIdle()
        assertFalse("n12.org" in folder.written, "nothing was written over it")
        vm.server.stop()
    }

    /** What is on screen may be older than what a scan just found: an offset goes by what is on screen. */
    @Test
    fun whatIsAsShownGoesByWhatIsOnScreenNotTheLatestFacts() = runTest {
        val before = "* TODO Report\n* TODO Other\n"
        val storage = storageOf(mapOf("work.org" to before))
        val here = UnconfinedTestDispatcher(testScheduler)
        val library = library(storage)
        library.reload(parseOn = here)
        library.factsToShow()
        assertTrue(library.isAsShown("work.org", before))

        val after = "* TODO New first\n$before"
        storage.write("work.org", after)
        library.reload(parseOn = here)
        assertEquals(after, library.text("work.org"))

        assertFalse(library.isAsShown("work.org", after), "the scan knows, and the calendar does not yet")
        library.factsToShow()
        assertTrue(library.isAsShown("work.org", after))
    }

    /** A first scan takes minutes: a plan made from what it has found so far would cancel the rest. */
    @Test
    fun remindersWaitForEveryNoteToBeKnown() = runTest {
        val folder = SlowFolder(
            notes.mapValues { (_, text) -> text to FileStamp(1, text.length.toLong()) } +
                ("zbig.org" to ("* Big\n<2026-11-05 Thu>\n" to FileStamp(1, NotesViewModel.SCAN_WAITED_FOR + 1))),
        )
        val storage = FolderNoteStorage(folder, textBudget = 0) { "20261105-120000" }
        val store = DocumentStore(storage)
        val planned = mutableListOf<Int>()
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()), store,
            today = { LocalDate(2026, 11, 5) },
            notifications = object : NotificationScheduler {
                override suspend fun replaceAll(reminders: List<Reminder>) {
                    planned += reminders.size
                }
            },
            background = UnconfinedTestDispatcher(),
            folder = storage,
            noteIndex = NoteIndex(budgets = nothingFits), noteBudgets = nothingFits,
        )

        vm.server.load()
        runCurrent()
        assertTrue(vm.library.leftToScan > 0)
        assertEquals(emptyList(), planned, "nothing is replaced while notes are unknown")

        folder.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, planned.size, "and once, when all of them are")
        vm.server.stop()
    }

    /** A clock started is on the bar at once, and the next clock stops it, not 600 ms later. */
    @Test
    fun theClocksFollowASaveAtOnce() = runTest {
        val storage = storageOf()
        val library = library(storage)
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))

        library.put("n11.org", "* Clocked\n:LOGBOOK:\nCLOCK: [2026-11-05 Thu 08:00]\n:END:\n".toOrgDocument())

        assertEquals(listOf("Clocked"), library.facts.getValue("n11.org").running.map { it.title })
    }

    @Test
    fun aNoteDeletedRightAfterAnEditDoesNotStayListed() = runTest {
        val storage = storageOf()
        val store = DocumentStore(storage)
        val library = NoteLibrary(store, NoteIndex(budgets = nothingFits), nothingFits)
        val here = UnconfinedTestDispatcher(testScheduler)
        library.reload(parseOn = here)

        library.put("n11.org", "* Edited\n".toOrgDocument())
        storage.delete("n11.org")
        library.reload(parseOn = here)
        library.catchUp(here)

        assertFalse("n11.org" in library.paths)
        assertFalse("n11.org" in library.facts)
        assertFalse("n11.org" in library)
    }

    /** With the first look failing: nothing says the notes are gone, and their facts are all the app has. */
    @Test
    fun aFolderOutOfReachLeavesWhatWasKnown() = runTest {
        val file = MemoryIndexFile()
        library(storageOf(), file = file).reload(parseOn = UnconfinedTestDispatcher(testScheduler))
        val folder = SlowFolder(emptyMap(), gated = false)
        val outOfReach = object : NoteFolder by folder {
            override suspend fun list(): List<FolderEntry> = throw IllegalStateException("cannot be reached")
        }
        val storage = FolderNoteStorage(outOfReach, textBudget = 0) { "20261105-120000" }
        val library = NoteLibrary(DocumentStore(storage), NoteIndex(file, nothingFits), nothingFits)

        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))
        library.saveIndex()

        assertEquals(notes.keys, library.facts.keys)
        assertEquals(notes.keys, library.paths)
        assertFalse(library.loaded, "and nothing should act on them as if it had looked")
        assertTrue(file.parts.getValue(IndexPart.Dated).size > 1, "the file is left as it was")
    }

    /** A list that gets ahead of reading the rest of what was kept must not count it as never scanned. */
    @Test
    fun aListingBeforeTheRestIsReadDoesNotRescanIt() = runTest {
        val withUndated = notes + ("plain.org" to "* No date\n")
        val file = MemoryIndexFile()
        val here = UnconfinedTestDispatcher(testScheduler)
        library(storageOf(withUndated), file = file).reload(parseOn = here)

        val storage = storageOf(withUndated)
        val library = library(storage, file = file)
        library.loadDated()
        library.list()

        assertEquals(0L, library.bytesToScan())
    }

    /** Whatever went wrong may not go wrong next time, so it is not kept as if it were known. */
    @Test
    fun aNewNoteIsKnownAtOnce() = runTest {
        val storage = storageOf()
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()

        vm.noteFiles.openToday()
        advanceUntilIdle()

        val today = vm.note.path
        assertNotNull(today)
        assertTrue(today in vm.library.facts, "linked to and picked from, even with nothing typed in it yet")
    }

    /** A scan had read the note before the edit: what it found must not go back over the edit's facts. */
    @Test
    fun aScanDoesNotPutOldFactsBackOverAnEdit() = runTest {
        // A read stalled and let go of here comes back through the same scheduler.
        val shared = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(shared)
        val storage = storageOf()
        val library = NoteLibrary(DocumentStore(storage), NoteIndex(budgets = nothingFits), nothingFits, shared)
        library.list()
        val gate = storage.stallRead("n11.org")
        val scan = launch { library.scan() }
        runCurrent()

        library.put("n11.org", "* TODO Edited\n<2026-11-09 Mon>\n".toOrgDocument())
        library.catchUp()
        gate.complete(Unit)
        scan.join()

        assertEquals("Edited", library.facts.getValue("n11.org").agenda.single().title)
    }

    /** Deleted while its group was being read: the listing that found it gone is the last word. */
    @Test
    fun aScanDoesNotBringBackANoteThatWentMeanwhile() = runTest {
        // A read stalled and let go of here comes back through the same scheduler.
        val shared = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(shared)
        val storage = storageOf()
        val library = NoteLibrary(DocumentStore(storage), NoteIndex(budgets = nothingFits), nothingFits, shared)
        library.list()
        val gate = storage.stallRead("n11.org")
        val scan = launch { library.scan() }
        runCurrent()

        storage.delete("n11.org")
        library.list()
        gate.complete(Unit)
        scan.join()

        assertFalse("n11.org" in library.facts)
    }

    /** A save whose write is still on its way when a listing comes: its text is still the note's. */
    @Test
    fun aListingForgetsOnlyTheSavesThatLanded() = runTest {
        val storage = storageOf()
        val library = library(storage)
        library.reload(parseOn = UnconfinedTestDispatcher(testScheduler))
        val landed = library.savesSoFar

        library.put("n11.org", "* Not written yet\n".toOrgDocument())
        library.list(landed = landed)

        assertEquals("* Not written yet\n", library.text("n11.org"))
    }

    /** A note that can never be read must not stop reminders for all the others, for good. */
    @Test
    fun aNoteThatCannotBeReadDoesNotHoldUpTheReminders() = runTest {
        val folder = SlowFolder(notes.mapValues { (_, text) -> text to FileStamp(1, text.length.toLong()) }, gated = false)
        folder.failing += "n12.org"
        val storage = FolderNoteStorage(folder, textBudget = 0) { "20261105-120000" }
        val store = DocumentStore(storage)
        val planned = mutableListOf<Int>()
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()), store,
            today = { LocalDate(2026, 11, 5) },
            notifications = object : NotificationScheduler {
                override suspend fun replaceAll(reminders: List<Reminder>) {
                    planned += reminders.size
                }
            },
            background = UnconfinedTestDispatcher(),
            folder = storage,
            noteIndex = NoteIndex(budgets = nothingFits), noteBudgets = nothingFits,
        )

        vm.server.load()
        advanceUntilIdle()

        assertTrue(vm.library.everyNoteKnown)
        assertFalse("n12.org" in vm.library.facts)
        assertEquals(1, planned.size)
        vm.server.stop()
    }

    /** It could not be read once. Read after all, it must not stay refused until it is opened again. */
    @Test
    fun aNoteReadAfterAllCanBeEdited() = runTest {
        val folder = SlowFolder(notes.mapValues { (_, text) -> text to FileStamp(1, text.length.toLong()) }, gated = false)
        val storage = FolderNoteStorage(folder, textBudget = 0) { "20261105-120000" }
        val store = DocumentStore(storage)
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()), store, background = UnconfinedTestDispatcher(),
            noteIndex = NoteIndex(budgets = nothingFits), noteBudgets = nothingFits, folder = storage,
        )
        vm.server.load()
        advanceUntilIdle()
        folder.failing += "n12.org"
        vm.onFileSelected("n12.org")
        advanceUntilIdle()
        assertTrue(vm.note.refused)

        folder.failing.clear()
        vm.server.retry()
        advanceUntilIdle()

        assertEquals(notes["n12.org"], vm.note.content)
        assertFalse(vm.note.refused)
        vm.editor.beginEdit(0)
        assertNotNull(vm.editor.editingIndex)
        vm.editor.cancel()
        vm.server.stop()
    }

    /** The open note has an offset from the calendar like any other. */
    @Test
    fun aKeywordFromTheCalendarOnTheOpenNoteGoesByWhatIsShown() = runTest {
        val note = "* TODO Report\nSCHEDULED: <2026-11-05 Thu>\n* TODO Other\nSCHEDULED: <2026-11-05 Thu>\n"
        val storage = storageOf(notes + ("work.org" to note))
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        vm.onFileSelected("work.org")
        advanceUntilIdle()
        val other = vm.agenda.index.entries.single { it.title == "Other" }
        // On screen, and the facts have not caught up yet.
        val moved = "* TODO From elsewhere\n* TODO A longer heading that pushes everything along\n$note"
        vm.note.content = moved
        vm.note.replicaText = moved

        vm.stateNotes.setTodo(other.file, other.headingOffset, "DONE")
        advanceUntilIdle()

        assertEquals(moved, vm.note.content, "no heading changed on an offset from another text")
    }

    /** Facts being worked out from a save when the note is deleted: the note must not come back in them. */
    @Test
    fun aCatchUpDoesNotBringBackANoteThatWentMeanwhile() = runTest {
        val elsewhere = HeldDispatcher()
        val shared = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(shared)
        val storage = storageOf()
        val library = NoteLibrary(DocumentStore(storage), NoteIndex(budgets = nothingFits), nothingFits, elsewhere)
        library.reload(parseOn = shared)
        library.put("n11.org", "* Edited\n".toOrgDocument())
        val catchingUp = launch { library.catchUp() }
        runCurrent()

        storage.delete("n11.org")
        library.list()
        elsewhere.release()
        runCurrent()
        catchingUp.join()

        assertFalse("n11.org" in library.facts)
    }

    /** As for a keyword: an offset from the calendar, in a note that has moved on, is refused. */
    @Test
    fun aClockFromTheCalendarOnANoteThatMovedOnIsRefused() = runTest {
        val note = "* TODO Report\nSCHEDULED: <2026-11-05 Thu>\n* TODO Other\nSCHEDULED: <2026-11-05 Thu>\n"
        val storage = storageOf(notes + ("work.org" to note))
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        val other = vm.agenda.index.entries.single { it.title == "Other" }
        val moved = "* TODO From elsewhere\n* TODO A longer heading that pushes everything along\n$note"
        storage.write("work.org", moved)

        vm.clockActions.clockIn(other.file, other.headingOffset)
        while (vm.lastErrorMessage.isEmpty()) {
            withContext(Dispatchers.Default) { delay(5.milliseconds) }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(moved, storage.read("work.org"), "no clock on another heading")
    }

    /** The same on the open note, whose text may have come in ahead of the calendar. */
    @Test
    fun aClockFromTheCalendarOnTheOpenNoteGoesByWhatIsShown() = runTest {
        val note = "* TODO Report\nSCHEDULED: <2026-11-05 Thu>\n* TODO Other\nSCHEDULED: <2026-11-05 Thu>\n"
        val storage = storageOf(notes + ("work.org" to note))
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        vm.onFileSelected("work.org")
        advanceUntilIdle()
        val other = vm.agenda.index.entries.single { it.title == "Other" }
        val moved = "* TODO From elsewhere\n* TODO A longer heading that pushes everything along\n$note"
        vm.note.content = moved
        vm.note.replicaText = moved

        vm.clockActions.clockIn(other.file, other.headingOffset)
        while (vm.lastErrorMessage.isEmpty()) {
            withContext(Dispatchers.Default) { delay(5.milliseconds) }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(moved, vm.note.content, "no clock on another heading")
    }

    /**
     * Stopping the clock running elsewhere goes by its title, so a note that moved on is no reason not to,
     * and two clocks would run if it were.
     */
    @Test
    fun theClockRunningElsewhereStopsEvenInANoteThatMovedOn() = runTest {
        val running = "* Report\n:LOGBOOK:\nCLOCK: [2026-11-05 Thu 08:00]\n:END:\n"
        val next = "* TODO Other\nSCHEDULED: <2026-11-05 Thu>\n"
        val storage = storageOf(mapOf("running.org" to running, "next.org" to next))
        val vm = viewModel(storage)
        vm.server.load()
        advanceUntilIdle()
        assertEquals(listOf("running.org"), vm.clocks.running.map { it.path })
        val entry = vm.agenda.index.entries.single { it.title == "Other" }
        storage.write("running.org", "* Above it now\n$running")

        vm.clockActions.clockIn(entry.file, entry.headingOffset)
        advanceUntilIdle()

        assertTrue(storage.read("running.org")!!.contains("]--["), "stopped: ${storage.read("running.org")}")
        assertEquals(listOf("next.org"), vm.clocks.running.map { it.path })
    }

    /** Too much text to scan first: the screen does not wait for it. */
    @Test
    fun aBigLibraryIsNotWaitedForToBeShown() = runTest {
        val folder = SlowFolder(
            notes.mapValues { (_, text) -> text to FileStamp(1, text.length.toLong()) } +
                ("zbig.org" to ("* Big\n<2026-11-05 Thu>\n" to FileStamp(1, NotesViewModel.SCAN_WAITED_FOR + 1))),
        )
        val storage = FolderNoteStorage(folder, textBudget = 0) { "20261105-120000" }
        val store = DocumentStore(storage)
        val vm = NotesViewModel(
            SyncEngine(store, FakeSyncApi()),
            store,
            today = { LocalDate(2026, 11, 5) },
            background = UnconfinedTestDispatcher(),
            folder = storage,
            noteIndex = NoteIndex(budgets = nothingFits),
            noteBudgets = nothingFits,
        )

        // Reading the big note does not finish, and this comes back all the same, with the others known.
        vm.server.load()
        runCurrent()
        assertTrue(vm.library.leftToScan > 0, "still scanning")
        assertEquals(notes.keys + "zbig.org", vm.library.paths)
        assertEquals(16, vm.library.facts.count { it.value.agenda.isNotEmpty() }, "the groups read so far, for the calendar to fill up with")

        folder.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, vm.library.leftToScan)
        assertEquals(21, vm.agenda.index.entries.size)
        vm.server.stop()
    }

    /** A save reaches what is known about the note, with the same keywords as a read does. */
    @Test
    fun theFactsFollowASave() = runTest {
        val storage = storageOf()
        val library = library(storage)
        val here = UnconfinedTestDispatcher(testScheduler)
        library.reload(parseOn = here)

        library.put("n11.org", "* TODO Saved\n<2026-11-09 Mon>\n".toOrgDocument())
        library.catchUp(here)

        assertEquals("Saved", library.facts.getValue("n11.org").agenda.single().title)
    }

    /** What keeps a stamp from being trusted over what was just read: [contentHash] tells them apart. */
    @Test
    fun whatHasNoFilesToLookAtGoesByItsText() = runTest {
        val storage = InMemoryReplicaStorage(mapOf("a.org" to "* A\n"))
        assertEquals(FileStamp(contentHash("* A\n"), 4), storage.stamps()?.getValue("a.org"))
    }
}
