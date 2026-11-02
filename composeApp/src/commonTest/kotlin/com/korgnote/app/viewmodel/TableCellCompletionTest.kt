package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.korgnote.common.org.edit.DraftInsert
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
 * Completion in a cell of the table editor: what the element editor offers in the same cell of a
 * table line, and nothing that only the start of a line would ask for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableCellCompletionTest {
    private val path = "notes.org"
    private val initialText = "* TODO Groceries\n* Garden\n"

    private lateinit var viewModel: NotesViewModel
    private var cell = TextFieldValue("")
    private lateinit var completion: CompletionController

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, FakeSyncApi(mutableMapOf(path to initialText))), store, today = { LocalDate(2026, 9, 23) })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun type(text: String, caret: Int = text.length) {
        viewModel.server.load()
        viewModel.onFileSelected(path)
        completion = viewModel.completionFor(
            draft = { TableCellLine.of(cell) },
            replaceDraft = { line -> TableCellLine.cellOf(line)?.let { cell = it } },
        )
        cell = TextFieldValue(text, TextRange(caret))
        completion.onDraftChanged()
    }

    @Test
    fun aLinkIsOfferedTheNotesHeadingsAndAcceptedIntoTheCell() = runTest {
        type("see [[Gard")

        assertEquals(listOf("*Garden"), completion.suggestions.map { it.label })
        completion.accept(completion.suggestions.single())

        assertEquals("see [[*Garden]]", cell.text)
        assertEquals(cell.text.length, cell.selection.start)
    }

    @Test
    fun aBracketAsksForADate() = runTest {
        type("due <")

        completion.accept(completion.suggestions.first())

        assertEquals("due <2026-09-23 Wed>", cell.text)
    }

    @Test
    fun theDatePickerWritesWhereTheCaretIs() = runTest {
        type("from  on", caret = "from ".length)

        completion.pickDate()
        completion.onDatePicked(LocalDate(2026, 10, 1))

        assertEquals("from <2026-10-01 Thu> on", cell.text)
    }

    /** At the start of a line these begin a heading, a block or a keyword; a cell cannot hold one. */
    @Test
    fun whatOnlyALinesStartAsksForIsNotOffered() = runTest {
        for (text in listOf("* TO", "<s", "#+TI")) {
            type(text)
            assertTrue(completion.suggestions.isEmpty(), "$text offered ${completion.suggestions}")
        }
    }

    @Test
    fun theLinkInsertFitsInACell() = runTest {
        type("")

        assertTrue(DraftInsert.LINK in completion.inserts)
        completion.insert(DraftInsert.LINK)
        assertEquals("[[", cell.text)
        assertTrue(completion.suggestions.isNotEmpty())
    }

    @Test
    fun aLineThatLostItsBarIsNoCell() {
        assertNull(TableCellLine.cellOf(TextFieldValue("x")))
        assertEquals(TextFieldValue("a b", TextRange(1)), TableCellLine.cellOf(TextFieldValue("| a\nb", TextRange(3))))
    }
}
