package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Accepting suggestions while editing an element.
 *
 * This logic used to live inside the editor composable, where nothing could reach it. It moved here
 * when the strip was pinned to the bottom of the screen — the strip is nowhere near the field, so
 * the caret and the suggestions had to be state the screen shares — and moving it made it testable,
 * which is how the caret bug in the block templates would have been caught the first time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionEditingTest {

    private val path = "notes.org"

    // Three tags, so that moving between suggestions has somewhere to move to.
    private val initialText = """
        #+TITLE: Notes

        * TODO First task                            :work:
        * Second task                                :home:garden:
    """.trimIndent() + "\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(path to initialText))
        viewModel = NotesViewModel(SyncEngine(store, api), store, today = { LocalDate(2026, 9, 23) })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openAndEdit(draft: String, caret: Int = draft.length) {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged(TextFieldValue(draft, TextRange(caret)))
    }

    @Test
    fun typingAHeadingKeywordOffersTheDocumentsKeywords() = runTest {
        openAndEdit("* TO")

        assertEquals(listOf("TODO"), viewModel.completion.suggestions.map { it.label })
    }

    @Test
    fun acceptingRewritesTheDraftAndMovesTheCaret() = runTest {
        openAndEdit("* TO")

        viewModel.completion.accept(viewModel.completion.suggestions.single())

        assertEquals("* TODO ", viewModel.editor.draft)
        assertEquals("* TODO ".length, viewModel.editor.draftCaret)
    }

    /** The caret belongs inside the block, not after it — which is what `caretOffset` is for. */
    @Test
    fun acceptingABlockTemplateLeavesTheCaretInsideIt() = runTest {
        openAndEdit("<s")

        viewModel.completion.accept(viewModel.completion.suggestions.single())

        assertEquals("#+BEGIN_SRC\n\n#+END_SRC", viewModel.editor.draft)
        assertEquals("#+BEGIN_SRC\n".length, viewModel.editor.draftCaret)
    }

    @Test
    fun theKeyboardAcceptsWhateverItMovedTo() = runTest {
        openAndEdit("* Buy milk :")
        val second = viewModel.completion.suggestions[1].label

        viewModel.completion.moveSelection(1)
        assertTrue(viewModel.completion.acceptSelected())

        assertEquals("* Buy milk $second", viewModel.editor.draft)
    }

    @Test
    fun movingWrapsAroundInBothDirections() = runTest {
        openAndEdit("* Buy milk :")
        val count = viewModel.completion.suggestions.size
        assertTrue(count > 1)

        viewModel.completion.moveSelection(-1)
        assertEquals(count - 1, viewModel.completion.selectedIndex)
        viewModel.completion.moveSelection(1)
        assertEquals(0, viewModel.completion.selectedIndex)
    }

    @Test
    fun acceptingWithNothingShowingDoesNothing() = runTest {
        openAndEdit("Just prose")

        assertTrue(viewModel.completion.suggestions.isEmpty())
        assertEquals(false, viewModel.completion.acceptSelected())
        assertEquals("Just prose", viewModel.editor.draft)
    }

    @Test
    fun dismissingHidesTheSuggestionsUntilTheNextKeystroke() = runTest {
        openAndEdit("* TO")

        viewModel.completion.dismiss()
        assertTrue(viewModel.completion.suggestions.isEmpty())

        viewModel.editor.onDraftChanged(TextFieldValue("* TOD", TextRange(5)))
        assertEquals(listOf("TODO"), viewModel.completion.suggestions.map { it.label })
    }

    @Test
    fun aSelectionIsNotACaretSoNothingIsOffered() = runTest {
        // The word "TO" selected rather than a caret after it: completing would delete it silently.
        openAndEdit("* TO")
        viewModel.editor.onDraftChanged(TextFieldValue("* TO", TextRange(2, 4)))

        assertTrue(viewModel.completion.suggestions.isEmpty())
    }

    @Test
    fun thePickerAsksBeforeItInserts() = runTest {
        openAndEdit("* Buy milk\nSCHEDULED: ")
        val picker = viewModel.completion.suggestions.single { it.kind == CompletionItem.Kind.DATE_PICKER }

        viewModel.completion.accept(picker)
        // Nothing has gone into the draft yet; the dialog is open.
        assertEquals("* Buy milk\nSCHEDULED: ", viewModel.editor.draft)
        assertEquals(picker, viewModel.completion.pendingDatePicker)

        viewModel.completion.onDatePicked(LocalDate(2026, 12, 24))

        assertEquals("* Buy milk\nSCHEDULED: <2026-12-24 Thu>", viewModel.editor.draft)
        assertNull(viewModel.completion.pendingDatePicker)
    }

    @Test
    fun thePickerChangesTheStampUnderTheCaret() = runTest {
        openAndEdit("* Buy milk\nSCHEDULED: <2026-09-23 Wed 10:00 +1w>")
        val picker = viewModel.completion.suggestions.first()
        assertEquals(CompletionItem.Kind.DATE_PICKER, picker.kind)

        viewModel.completion.accept(picker)
        // The picker opens on what is written.
        assertEquals(LocalDate(2026, 9, 23), viewModel.completion.pickerStamp?.start)
        assertEquals(LocalTime(10, 0), viewModel.completion.pickerStamp?.startTime)

        viewModel.completion.onDatePicked(LocalDate(2026, 12, 24), LocalTime(18, 45))

        assertEquals("* Buy milk\nSCHEDULED: <2026-12-24 Thu 18:45 +1w>", viewModel.editor.draft)
    }

    @Test
    fun thePickerCanWriteATimeRange() = runTest {
        openAndEdit("* Design review\nSCHEDULED: <2026-09-23 Wed 10:00-11:00>")
        viewModel.completion.accept(viewModel.completion.suggestions.first())
        // The picker opens on the range as written.
        assertEquals(LocalTime(11, 0), viewModel.completion.pickerStamp?.endTime)

        viewModel.completion.onDatePicked(LocalDate(2026, 9, 24), LocalTime(14, 0), LocalTime(15, 30))

        assertEquals("* Design review\nSCHEDULED: <2026-09-24 Thu 14:00-15:30>", viewModel.editor.draft)
    }

    @Test
    fun dismissingThePickerLeavesTheDraftAlone() = runTest {
        openAndEdit("* Buy milk\nDEADLINE: ")
        viewModel.completion.accept(viewModel.completion.suggestions.single { it.kind == CompletionItem.Kind.DATE_PICKER })

        viewModel.completion.dismissDatePicker()

        assertEquals("* Buy milk\nDEADLINE: ", viewModel.editor.draft)
        assertNull(viewModel.completion.pendingDatePicker)
    }

    @Test
    fun endingTheEditClearsTheStrip() = runTest {
        openAndEdit("* TO")
        assertTrue(viewModel.completion.suggestions.isNotEmpty())

        viewModel.editor.cancel()

        assertTrue(viewModel.completion.suggestions.isEmpty())
        assertNull(viewModel.editor.editingIndex)
    }

    /** Committing goes through the same path, and the strip must not survive it either. */
    @Test
    fun committingClearsTheStripToo() = runTest {
        openAndEdit("* TODO First task :work:\n")
        viewModel.editor.onDraftChanged(TextFieldValue("* TODO First task :", TextRange("* TODO First task :".length)))
        assertTrue(viewModel.completion.suggestions.isNotEmpty())

        viewModel.editor.commit()

        assertTrue(viewModel.completion.suggestions.isEmpty())
        assertNull(viewModel.editor.editingIndex)
    }
}
