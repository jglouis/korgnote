package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Links changed through the link form rather than typed: the words they show, where they go, and
 * an address pasted while editing, which becomes a link by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkEditTest {

    private val open = "journal.org"
    private val other = "project.org"

    private val journal = "* Today\nRead [[https://old.example][the docs]] and [[id:done-id][Done]].\n"
    private val project = "#+TITLE: Project\n* Plan\n* Done\n:PROPERTIES:\n:ID:       done-id\n:END:\n"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.editing(): Pair<NotesViewModel, FakeSyncApi> {
        val store = DocumentStore(InMemoryReplicaStorage())
        val server = FakeSyncApi(mutableMapOf(open to journal, other to project))
        val viewModel = NotesViewModel(
            SyncEngine(store, server),
            store,
            newId = { "new-1" },
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(open)
        advanceUntilIdle()
        viewModel.editor.beginEdit(0)
        return viewModel to server
    }

    private fun NotesViewModel.caretAt(index: Int, end: Int = index) {
        editor.onDraftChanged(TextFieldValue(editor.draft, TextRange(index, end)))
    }

    @Test
    fun theFormOpensOnTheLinkTheCaretIsIn() = runTest {
        val (viewModel, _) = editing()
        viewModel.caretAt(viewModel.editor.draft.indexOf("old.example"))

        val edit = viewModel.linkToEdit()!!

        assertFalse(edit.isNew)
        assertEquals("https://old.example", edit.link.target)
        assertEquals("the docs", edit.link.description)
    }

    @Test
    fun awayFromALinkTheFormAddsOneWithTheSelectedWords() = runTest {
        val (viewModel, _) = editing()
        val start = viewModel.editor.draft.indexOf("Read")
        viewModel.caretAt(start, start + 4)

        val edit = viewModel.linkToEdit()!!

        assertTrue(edit.isNew)
        assertEquals("Read", edit.link.description)
    }

    @Test
    fun changingTheLabelKeepsWhereItGoes() = runTest {
        val (viewModel, _) = editing()
        viewModel.caretAt(viewModel.editor.draft.indexOf("old.example"))
        val edit = viewModel.linkToEdit()!!

        viewModel.saveLink(edit, edit.link.target, "the manual")

        assertEquals("* Today\nRead [[https://old.example][the manual]] and [[id:done-id][Done]].\n", viewModel.editor.draft)
    }

    @Test
    fun aNewLinkGoesInPlaceOfTheSelection() = runTest {
        val (viewModel, _) = editing()
        val start = viewModel.editor.draft.indexOf("Read")
        viewModel.caretAt(start, start + 4)
        val edit = viewModel.linkToEdit()!!

        viewModel.saveLink(edit, "file:~/Sync/invoice.pdf", edit.link.description)

        assertTrue(viewModel.editor.draft.startsWith("* Today\n[[file:~/Sync/invoice.pdf][Read]] [[https://old"))
        assertEquals("* Today\n[[file:~/Sync/invoice.pdf][Read]]".length, viewModel.editor.draftCaret)
    }

    @Test
    fun aNotePickedInTheFormIsLinkedByItsIdGivenOneFirst() = runTest {
        val (viewModel, server) = editing()
        viewModel.caretAt(viewModel.editor.draft.indexOf("done-id"))
        val edit = viewModel.linkToEdit()!!
        assertEquals("Done", viewModel.noteLinkTitle(edit.link.target))

        viewModel.saveLink(edit, "", "Planning", note = viewModel.linkTargets().single { it.title == "Plan" })
        advanceUntilIdle()

        assertTrue(viewModel.editor.draft.contains("and [[id:new-1][Planning]]."))
        assertTrue(server.files.getValue(other).contains("* Plan\n:PROPERTIES:\n:ID:       new-1\n:END:\n"))
    }

    @Test
    fun removingALinkLeavesItsWords() = runTest {
        val (viewModel, _) = editing()
        viewModel.caretAt(viewModel.editor.draft.indexOf("old.example"))

        viewModel.removeLink(viewModel.linkToEdit()!!)

        assertEquals("* Today\nRead the docs and [[id:done-id][Done]].\n", viewModel.editor.draft)
    }

    @Test
    fun aPastedAddressBecomesALinkInTheDraft() = runTest {
        val (viewModel, _) = editing()
        val draft = viewModel.editor.draft
        val at = draft.indexOf(" and ") + 1
        viewModel.caretAt(at, at + 3)

        val pasted = draft.replaceRange(at, at + 3, "https://example.com")
        viewModel.editor.onDraftChanged(TextFieldValue(pasted, TextRange(at + 19)))

        assertTrue(viewModel.editor.draft.contains("the docs]] [[https://example.com][and]] [[id:done-id]"), viewModel.editor.draft)
        assertEquals(at + "[[https://example.com][and]]".length, viewModel.editor.draftCaret)
    }
}
