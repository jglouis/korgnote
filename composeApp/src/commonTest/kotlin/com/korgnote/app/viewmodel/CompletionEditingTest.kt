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

    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(path to initialText))
        viewModel = FileTreeViewModel(SyncEngine(store, api), store, today = { LocalDate(2026, 9, 23) })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openAndEdit(draft: String, caret: Int = draft.length) {
        viewModel.loadFromServer()
        viewModel.onFileSelected(path)
        viewModel.beginElementEdit(1)
        viewModel.onElementDraftChanged(TextFieldValue(draft, TextRange(caret)))
    }

    @Test
    fun typingAHeadingKeywordOffersTheDocumentsKeywords() = runTest {
        openAndEdit("* TO")

        assertEquals(listOf("TODO"), viewModel.completionSuggestions.map { it.label })
    }

    @Test
    fun acceptingRewritesTheDraftAndMovesTheCaret() = runTest {
        openAndEdit("* TO")

        viewModel.acceptCompletion(viewModel.completionSuggestions.single())

        assertEquals("* TODO ", viewModel.elementDraft)
        assertEquals("* TODO ".length, viewModel.elementDraftCaret)
    }

    /** The caret belongs inside the block, not after it — which is what `caretOffset` is for. */
    @Test
    fun acceptingABlockTemplateLeavesTheCaretInsideIt() = runTest {
        openAndEdit("<s")

        viewModel.acceptCompletion(viewModel.completionSuggestions.single())

        assertEquals("#+BEGIN_SRC\n\n#+END_SRC", viewModel.elementDraft)
        assertEquals("#+BEGIN_SRC\n".length, viewModel.elementDraftCaret)
    }

    @Test
    fun theKeyboardAcceptsWhateverItMovedTo() = runTest {
        openAndEdit("* Buy milk :")
        val second = viewModel.completionSuggestions[1].label

        viewModel.moveCompletionSelection(1)
        assertTrue(viewModel.acceptSelectedCompletion())

        assertEquals("* Buy milk $second", viewModel.elementDraft)
    }

    @Test
    fun movingWrapsAroundInBothDirections() = runTest {
        openAndEdit("* Buy milk :")
        val count = viewModel.completionSuggestions.size
        assertTrue(count > 1)

        viewModel.moveCompletionSelection(-1)
        assertEquals(count - 1, viewModel.selectedSuggestion)
        viewModel.moveCompletionSelection(1)
        assertEquals(0, viewModel.selectedSuggestion)
    }

    @Test
    fun acceptingWithNothingShowingDoesNothing() = runTest {
        openAndEdit("Just prose")

        assertTrue(viewModel.completionSuggestions.isEmpty())
        assertEquals(false, viewModel.acceptSelectedCompletion())
        assertEquals("Just prose", viewModel.elementDraft)
    }

    @Test
    fun dismissingHidesTheSuggestionsUntilTheNextKeystroke() = runTest {
        openAndEdit("* TO")

        viewModel.dismissCompletion()
        assertTrue(viewModel.completionSuggestions.isEmpty())

        viewModel.onElementDraftChanged(TextFieldValue("* TOD", TextRange(5)))
        assertEquals(listOf("TODO"), viewModel.completionSuggestions.map { it.label })
    }

    @Test
    fun aSelectionIsNotACaretSoNothingIsOffered() = runTest {
        // The word "TO" selected rather than a caret after it: completing would delete it silently.
        openAndEdit("* TO")
        viewModel.onElementDraftChanged(TextFieldValue("* TO", TextRange(2, 4)))

        assertTrue(viewModel.completionSuggestions.isEmpty())
    }

    @Test
    fun thePickerAsksBeforeItInserts() = runTest {
        openAndEdit("* Buy milk\nSCHEDULED: ")
        val picker = viewModel.completionSuggestions.single { it.kind == CompletionItem.Kind.DATE_PICKER }

        viewModel.acceptCompletion(picker)
        // Nothing has gone into the draft yet; the dialog is open.
        assertEquals("* Buy milk\nSCHEDULED: ", viewModel.elementDraft)
        assertEquals(picker, viewModel.pendingDatePicker)

        viewModel.onDatePicked(LocalDate(2026, 12, 24))

        assertEquals("* Buy milk\nSCHEDULED: <2026-12-24 Thu>", viewModel.elementDraft)
        assertNull(viewModel.pendingDatePicker)
    }

    @Test
    fun dismissingThePickerLeavesTheDraftAlone() = runTest {
        openAndEdit("* Buy milk\nDEADLINE: ")
        viewModel.acceptCompletion(viewModel.completionSuggestions.single { it.kind == CompletionItem.Kind.DATE_PICKER })

        viewModel.dismissDatePicker()

        assertEquals("* Buy milk\nDEADLINE: ", viewModel.elementDraft)
        assertNull(viewModel.pendingDatePicker)
    }

    @Test
    fun endingTheEditClearsTheStrip() = runTest {
        openAndEdit("* TO")
        assertTrue(viewModel.completionSuggestions.isNotEmpty())

        viewModel.cancelElementEdit()

        assertTrue(viewModel.completionSuggestions.isEmpty())
        assertNull(viewModel.editingElementIndex)
    }

    /** Committing goes through the same path, and the strip must not survive it either. */
    @Test
    fun committingClearsTheStripToo() = runTest {
        openAndEdit("* TODO First task :work:\n")
        viewModel.onElementDraftChanged(TextFieldValue("* TODO First task :", TextRange("* TODO First task :".length)))
        assertTrue(viewModel.completionSuggestions.isNotEmpty())

        viewModel.commitElementEdit()

        assertTrue(viewModel.completionSuggestions.isEmpty())
        assertNull(viewModel.editingElementIndex)
    }
}
