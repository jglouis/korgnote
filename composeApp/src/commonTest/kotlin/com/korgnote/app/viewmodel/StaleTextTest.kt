package com.korgnote.app.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A change made on text the replica has already moved past: a sync pulled an edit from Emacs, or a
 * background worker filed a capture, and the app has not read the notes again yet. Written as it was
 * made, the change went out as an edit of the server's newest version, and whatever had come in was
 * gone with no conflict copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleTextTest {
    private val open = "open.org"
    private val work = "work.org"
    private val workText = "* TODO Report\nbody\n\n* TODO Other\nother body\n"
    private val openText = "* Open\n- [ ] one\n\n- [ ] two\n\nfar away\n"
    private val time = LocalDateTime(2026, 11, 2, 9, 30)

    private lateinit var api: FakeSyncApi
    private lateinit var store: DocumentStore
    private lateinit var engine: SyncEngine
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        api = FakeSyncApi(mutableMapOf(open to openText, work to workText))
        store = DocumentStore(StallingReplicaStorage())
        engine = SyncEngine(store, api) { "20261102-093000" }
        viewModel = NotesViewModel(
            engine, store, now = { time },
            background = UnconfinedTestDispatcher(main.scheduler),
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.opened(path: String = open) {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        advanceUntilIdle()
    }

    /** An edit made in Emacs that this device has synced, without the app having read it yet. */
    private suspend fun syncedFromEmacs(path: String, text: String) {
        api.files[path] = text
        engine.sync()
    }

    /** Until something is said: the words come from the app's resources, on a thread of their own. */
    private suspend fun TestScope.advanceUntilSaid() {
        while (viewModel.lastErrorMessage.isEmpty()) {
            withContext(Dispatchers.Default) { delay(5.milliseconds) }
            runCurrent()
        }
        advanceUntilIdle()
    }

    // --- A note that is not open, as from the calendar ---

    @Test
    fun aKeywordSetFromTheCalendarKeepsAnEditThatCameIn() = runTest {
        opened()
        val emacs = workText.replace("other body", "other body, from Emacs")
        syncedFromEmacs(work, emacs)

        viewModel.stateNotes.setTodo(work, 0, "DONE")
        advanceUntilIdle()

        assertEquals(emacs.replace("* TODO Report", "* DONE Report"), api.files[work])
        assertEquals(emacs.replace("* TODO Report", "* DONE Report"), viewModel.library.text(work))
    }

    /** The calendar's offset is into the text it read: the keyword goes on the heading it showed. */
    @Test
    fun aKeywordSetFromTheCalendarFindsItsHeadingWhenTheTextMoved() = runTest {
        opened()
        val emacs = "* TODO From Emacs\n\n$workText"
        syncedFromEmacs(work, emacs)

        viewModel.stateNotes.setTodo(work, workText.indexOf("* TODO Other"), "DONE")
        advanceUntilIdle()

        assertEquals(emacs.replace("* TODO Other", "* DONE Other"), api.files[work])
    }

    @Test
    fun aKeywordSetFromTheCalendarKeepsACaptureFiledInTheBackground() = runTest {
        opened()
        // As a capture is filed: after what is there.
        val captured = "$workText* Filed\n"
        store.save(work, captured)

        viewModel.stateNotes.setTodo(work, 0, "DONE")
        advanceUntilIdle()

        assertEquals(captured.replace("* TODO Report", "* DONE Report"), store.read(work))
    }

    @Test
    fun aKeywordOnALineChangedElsewhereIsRefusedAndTheOtherEditKept() = runTest {
        opened()
        val emacs = workText.replace("* TODO Report", "* WAITING Report")
        syncedFromEmacs(work, emacs)

        viewModel.stateNotes.setTodo(work, 0, "DONE")
        advanceUntilSaid()

        assertEquals(emacs, api.files[work])
        assertEquals(emacs, store.read(work))
        assertEquals(emacs, viewModel.library.text(work), "the library shows what the replica holds")
        assertTrue(viewModel.lastErrorMessage.contains("also changed elsewhere"), viewModel.lastErrorMessage)
    }

    @Test
    fun aHeadingRefiledIntoANoteKeepsAnEditThatCameIn() = runTest {
        opened()
        val emacs = workText.replace("other body", "other body, from Emacs")
        syncedFromEmacs(work, emacs)
        val target = LinkTarget(path = work, noteTitle = "work", outline = emptyList(), title = "Report", offset = 0, id = null)

        viewModel.changes.refile(0, target)
        advanceUntilIdle()

        val saved = store.read(work)!!
        assertTrue(saved.contains("from Emacs"), saved)
        assertTrue(saved.contains("Open"), saved)
    }

    @Test
    fun anIdGivenToAHeadingLinkedToKeepsAnEditThatCameIn() = runTest {
        opened()
        val emacs = workText.replace("other body", "other body, from Emacs")
        syncedFromEmacs(work, emacs)
        viewModel.editor.beginEdit(0)

        viewModel.linkForm.insertLinkTo(LinkTarget(path = work, noteTitle = "work", outline = emptyList(), title = "Report", offset = 0, id = null))
        advanceUntilIdle()

        val saved = store.read(work)!!
        assertTrue(saved.contains("from Emacs"), saved)
        assertTrue(saved.contains(":ID:"), saved)
    }

    // --- The open note ---

    @Test
    fun aTickOnTheOpenNoteKeepsAnEditThatCameIn() = runTest {
        opened()
        val emacs = openText.replace("far away", "far away, from Emacs")
        syncedFromEmacs(open, emacs)

        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] one"))
        advanceUntilIdle()

        val both = emacs.replace("- [ ] one", "- [X] one")
        assertEquals(both, api.files[open])
        assertEquals(both, store.read(open))
        assertEquals(both, viewModel.note.content)
        assertEquals(both, viewModel.library.text(open))
    }

    @Test
    fun twoTicksInARowOnStaleTextAreBothKept() = runTest {
        opened()
        val emacs = openText.replace("far away", "far away, from Emacs")
        syncedFromEmacs(open, emacs)

        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] one"))
        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] two"))
        advanceUntilIdle()

        val all = emacs.replace("- [ ] one", "- [X] one").replace("- [ ] two", "- [X] two")
        assertEquals(all, store.read(open))
        assertEquals(all, viewModel.note.content)
    }

    @Test
    fun aTickOnALineChangedElsewhereIsRefusedAndTheOtherEditShown() = runTest {
        opened()
        val emacs = openText.replace("- [ ] one", "- [ ] one, from Emacs")
        syncedFromEmacs(open, emacs)

        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] one"))
        advanceUntilSaid()

        assertEquals(emacs, store.read(open))
        assertEquals(emacs, viewModel.note.content)
        assertTrue(viewModel.lastErrorMessage.contains("also changed elsewhere"), viewModel.lastErrorMessage)
    }

    @Test
    fun aSavedElementKeepsAnEditThatCameIn() = runTest {
        opened()
        val emacs = openText.replace("far away", "far away, from Emacs")
        syncedFromEmacs(open, emacs)
        viewModel.editor.beginEdit(0)
        viewModel.editor.onDraftChanged(TextFieldValue(viewModel.editor.draft.replace("* Open", "* Open, edited")))

        viewModel.editor.commit()

        assertEquals(emacs.replace("* Open", "* Open, edited"), store.read(open))
        assertEquals(emacs.replace("* Open", "* Open, edited"), viewModel.note.content)
    }

    /** Text that was typed is not dropped as a tick is: it goes beside the note, as a sync's conflict does. */
    @Test
    fun aSavedElementThatCannotBeMergedGoesToAConflictCopy() = runTest {
        opened()
        val emacs = openText.replace("* Open", "* Open, from Emacs")
        syncedFromEmacs(open, emacs)
        viewModel.editor.beginEdit(0)
        viewModel.editor.onDraftChanged(TextFieldValue(viewModel.editor.draft.replace("* Open", "* Open, typed here")))

        viewModel.editor.commit()
        advanceUntilSaid()

        assertEquals(emacs, store.read(open))
        assertEquals(emacs, viewModel.note.content)
        val copy = store.paths().single { it.contains("sync-conflict") }
        assertTrue(store.read(copy)!!.contains("typed here"), store.read(copy)!!)
        assertEquals(listOf(copy), viewModel.library.conflicts[open])
    }

    /** Nothing moved: what is on screen is what gets written, byte for byte. */
    @Test
    fun aTickOnTextThatDidNotMoveWritesJustThat() = runTest {
        opened()

        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] one"))
        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] two"))
        advanceUntilIdle()

        assertEquals(openText.replace("[ ]", "[X]"), store.read(open))
        assertEquals(openText.replace("[ ]", "[X]"), viewModel.note.content)
    }

    // --- An edit discarded over what came in ---

    /** Merged over the note from before, what came in looks added twice: once by each side. */
    @Test
    fun anEditDiscardedOverWhatCameInLeavesNothingToAddTwice() = runTest {
        opened()
        viewModel.editor.beginEdit(0)
        val emacs = "$openText* From Emacs\n"
        api.files[open] = emacs
        viewModel.server.load()
        advanceUntilIdle()

        viewModel.editor.cancel()
        assertEquals(emacs, viewModel.note.content)

        viewModel.editor.addAtEnd()
        viewModel.editor.onDraftChanged("* From the phone")
        viewModel.editor.commit()
        advanceUntilIdle()

        assertEquals("$emacs* From the phone", store.read(open))
        assertEquals("$emacs* From the phone", api.files[open])
    }

    @Test
    fun aTickAfterAnEditDiscardedOverWhatCameInKeepsTheTick() = runTest {
        opened()
        viewModel.editor.beginEdit(0)
        // Beside the box, where a merge over the note from before would call the two a conflict.
        val emacs = openText.replace("- [ ] one\n\n", "- [ ] one\nfrom Emacs\n\n")
        api.files[open] = emacs
        viewModel.server.load()
        advanceUntilIdle()
        viewModel.editor.cancel()

        viewModel.changes.toggleCheckboxAt(emacs.indexOf("- [ ] one"))
        advanceUntilIdle()

        assertEquals(emacs.replace("- [ ] one", "- [X] one"), store.read(open))
        assertEquals(emacs.replace("- [ ] one", "- [X] one"), viewModel.note.content)
    }

    // --- A note whose text has not arrived ---

    /** What stands in for a note until it is read must never take its place, however it was edited. */
    @Test
    fun aSaveOfAPlaceholderKeepsTheNoteItStoodInFor() = runTest {
        // A note the server does not hold, or the sync after the save would put it right by itself.
        val here = "only-here.org"
        store.save(here, openText)
        viewModel.note.beginOpening(here, hasText = false)
        viewModel.note.content = "* Typed on the placeholder\n"

        viewModel.changes.saveCurrentFile()
        advanceUntilSaid()

        assertEquals(openText, store.read(here))
        val copy = store.paths().single { "sync-conflict" in it }
        assertEquals("* Typed on the placeholder\n", store.read(copy), "what was typed is kept beside it")
    }

    @Test
    fun aNoteThatWasNeverWrittenIsWrittenBySavingIt() = runTest {
        val new = "only-here.org"
        viewModel.note.beginOpening(new, hasText = false)
        viewModel.note.content = "* The first words\n"

        viewModel.changes.saveCurrentFile()
        advanceUntilIdle()

        assertEquals("* The first words\n", store.read(new))
    }

    // --- A heading added and not yet saved ---

    /** On screen and nowhere else until it is saved: a tick would write it, and its discard undo the tick. */
    @Test
    fun aTickWhileAHeadingIsBeingAddedIsRefusedAndLeavesTheNote() = runTest {
        opened()
        viewModel.editor.addAtEnd()
        val onScreen = viewModel.note.content

        viewModel.changes.toggleCheckboxAt(openText.indexOf("- [ ] one"))
        advanceUntilSaid()

        assertEquals(openText, store.read(open))
        assertEquals(onScreen, viewModel.note.content)
        assertTrue(viewModel.lastErrorMessage.contains("editing"), viewModel.lastErrorMessage)
    }

    // --- Another note that moved on, reached from this one ---

    @Test
    fun aLinkToAHeadingWhoseNoteChangedElsewhereSaysItWasNotMade() = runTest {
        opened()
        viewModel.editor.beginEdit(0)
        val target = viewModel.linkForm.linkTargets().single { it.title == "Report" }
        syncedFromEmacs(work, workText.replace("* TODO Report", "* TODO Report, from Emacs"))

        viewModel.linkForm.insertLinkTo(target)
        advanceUntilSaid()

        assertTrue(viewModel.lastErrorMessage.contains("also changed elsewhere"), viewModel.lastErrorMessage)
    }

    @Test
    fun aRefileWhoseSourceWasKeptBesideDoesNotSayItMoved() = runTest {
        opened()
        val target = viewModel.changes.refileTargets(0).single { it.title == "Report" }
        val emacs = openText.replace("* Open", "* Open, from Emacs")
        syncedFromEmacs(open, emacs)

        viewModel.changes.refile(0, target)
        advanceUntilSaid()

        assertEquals(emacs, store.read(open))
        assertTrue(viewModel.lastErrorMessage.contains("beside"), viewModel.lastErrorMessage)
    }
}
