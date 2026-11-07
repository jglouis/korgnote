package com.korgnote.app.viewmodel

import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.org.agenda.occurrences
import com.korgnote.common.org.link.OrgLinkIndex
import com.korgnote.common.org.link.OrgLinkResolution
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.recalculate
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tour note: Org that does what it says when tapped, written once and never over the user's copy. */
@OptIn(ExperimentalCoroutinesApi::class)
class TourNoteTest {

    private val today = LocalDate(2026, 11, 7)
    private val path = "welcome_to_korgnote.org"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun tour(): Pair<TourWords, String> {
        val words = tourWords()
        return words to tourNoteText(words, id = "tour-id", today = today)
    }

    @Test
    fun itIsHeadedAsANewNote() = runTest {
        val (_, text) = tour()

        assertTrue(text.startsWith(":PROPERTIES:\n:ID:       tour-id\n:END:\n#+TITLE: Welcome to KorgNote\n#+DATE: [2026-11-07 Sat]\n"), text)
        // Parsing loses nothing, so the note reads back exactly as written.
        assertEquals(text, text.toOrgDocument().text)
    }

    @Test
    fun theDatedHeadingIsInTodaysAgendaWithNoTime() = runTest {
        val (words, text) = tour()

        val today = agendaEntries(path, text).occurrences(today, today, AgendaOptions(today = today))

        assertEquals(listOf(words.calendarHeading), today.map { it.entry.title })
        // No time, so no reminder fires from a tour.
        assertNull(today.single().time)
    }

    @Test
    fun theLinkLeadsToTheTable() = runTest {
        val (words, text) = tour()
        val link = Regex("""\[\[(\*[^]]+)]\[""").find(text)!!.groupValues[1]

        val target = OrgLinkIndex(mapOf(path to text)).resolve(link, path)

        val note = assertIs<OrgLinkResolution.Note>(target)
        assertEquals(path, note.path)
        assertNull(note.missedSearch)
        assertTrue(text.substring(note.offset!!).startsWith("* ${words.tableHeading}\n"), text.substring(note.offset!!))
    }

    @Test
    fun theTablesTotalIsWhatItsFormulaComputes() = runTest {
        val (_, text) = tour()
        val lines = text.lines()
        val rows = lines.filter { it.startsWith("|") }.map { line ->
            if (line.startsWith("|-")) null else line.trim('|').split('|').map { it.trim() }
        }
        val formula = lines.single { it.startsWith("#+TBLFM: ") }.removePrefix("#+TBLFM: ")

        val done = assertIs<Recalculation.Done>(recalculate(rows, listOf(formula)))

        assertEquals(rows, done.rows)
        assertEquals(listOf("Total", "5"), rows.last())
        // Aligned as Emacs aligns it, so that its first recalculation changes nothing else.
        assertEquals(1, lines.filter { it.startsWith("|") }.map { it.length }.distinct().size)
    }

    @Test
    fun theBoxesCookieCountsTheBoxes() = runTest {
        val (words, text) = tour()

        assertTrue("* ${words.boxesHeading} [0/${words.boxes.size}]\n" in text)
        assertEquals(words.boxes.size, Regex("""(?m)^- \[ ] """).findAll(text).count())
    }

    @Test
    fun openingWritesItAndShowsItWithoutStartingAHeading() = runTest {
        val store = DocumentStore(InMemoryReplicaStorage())
        val vm = NotesViewModel(SyncEngine(store, FakeSyncApi(mutableMapOf())), store, today = { today }, newId = { "tour-id" })
        vm.server.load()

        val opened = vm.noteFiles.openTour(tourWords())
        advanceUntilIdle()

        assertEquals(path, opened)
        assertEquals(path, vm.note.path)
        assertEquals(tour().second, store.read(path))
        assertNull(vm.editor.editingIndex)
    }

    @Test
    fun oneAlreadyThereIsOnlyOpened() = runTest {
        val ticked = "#+TITLE: Welcome to KorgNote\n* Tick the boxes [1/3]\n- [X] Tap a box\n"
        val store = DocumentStore(InMemoryReplicaStorage())
        val vm = NotesViewModel(SyncEngine(store, FakeSyncApi(mutableMapOf(path to ticked))), store, today = { today })
        vm.server.load()

        vm.noteFiles.openTour(tourWords())
        advanceUntilIdle()

        assertEquals(path, vm.note.path)
        assertEquals(ticked, store.read(path))
    }
}
