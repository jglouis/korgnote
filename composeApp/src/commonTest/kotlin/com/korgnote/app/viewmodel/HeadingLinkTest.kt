package com.korgnote.app.viewmodel

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
 * Linking to any heading, as org-roam does: by its `:ID:`, which a heading without one is given
 * where it is — in another note at once, in the open note with the element's save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadingLinkTest {

    private val open = "journal.org"
    private val other = "project.org"

    private val journal = "* Today\nMet Bob.\n* Later\n"
    private val project = "#+TITLE: Project\n* Plan\n** Step one\n* Done\n:PROPERTIES:\n:ID:       done-id\n:END:\n"

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
        var ids = 0
        val viewModel = NotesViewModel(
            SyncEngine(store, server),
            store,
            newId = { "new-${++ids}" },
            background = UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.server.load()
        viewModel.onFileSelected(open)
        advanceUntilIdle()
        viewModel.editor.beginEdit(0)
        return viewModel to server
    }

    private fun NotesViewModel.target(title: String) = linkTargets().single { it.title == title }

    @Test
    fun theTargetsAreEveryHeadingAndEveryOtherNote() {
        runTest {
            val (viewModel, _) = editing()
            val listed = viewModel.linkTargets().map { "${it.where} / ${it.title}" }
            assertEquals(
                listOf("journal / Today", "journal / Later", "project.org / Project", "Project / Plan", "Project › Plan / Step one", "Project / Done"),
                listed,
            )
        }
    }

    @Test
    fun aHeadingWithAnIdIsLinkedByIt() = runTest {
        val (viewModel, server) = editing()

        viewModel.insertLinkTo(viewModel.target("Done"))

        assertEquals("* Today\nMet Bob.\n[[id:done-id][Done]]", viewModel.editor.draft)
        assertEquals(project, server.files.getValue(other), "nothing to give it")
    }

    @Test
    fun aHeadingInAnotherNoteIsGivenAnIdThereAtOnce() = runTest {
        val (viewModel, server) = editing()

        viewModel.insertLinkTo(viewModel.target("Step one"))
        advanceUntilIdle()

        assertTrue(viewModel.editor.draft.endsWith("[[id:new-1][Step one]]"))
        val withId = "** Step one\n:PROPERTIES:\n:ID:       new-1\n:END:\n"
        assertTrue(server.files.getValue(other).contains(withId), "written and pushed")
        assertTrue(viewModel.library[other]!!.text.contains(withId))
    }

    @Test
    fun aHeadingInTheOpenNoteGetsItsIdWithTheSave() = runTest {
        val (viewModel, server) = editing()

        viewModel.insertLinkTo(viewModel.target("Later"))
        assertFalse(server.files.getValue(open).contains("new-1"), "not before the save")
        viewModel.editor.commit()
        advanceUntilIdle()

        assertEquals(
            "* Today\nMet Bob.\n[[id:new-1][Later]]\n* Later\n:PROPERTIES:\n:ID:       new-1\n:END:\n",
            server.files.getValue(open),
        )
    }

    @Test
    fun theHeadingBeingEditedGetsItsIdInTheDraft() = runTest {
        val (viewModel, _) = editing()

        viewModel.insertLinkTo(viewModel.target("Today"))

        assertEquals("* Today\n:PROPERTIES:\n:ID:       new-1\n:END:\nMet Bob.\n[[id:new-1][Today]]", viewModel.editor.draft)
        assertEquals(viewModel.editor.draft.length, viewModel.editor.draftCaret, "the caret is after the link")
    }

    @Test
    fun tickingABoxCountsItInItsHeadingsCookie() = runTest {
        val (viewModel, _) = editing()
        viewModel.editor.cancel()
        viewModel.editor.beginEdit(1)
        viewModel.editor.onDraftChanged("* Later [/]\n- [ ] one\n- [ ] two\n")
        viewModel.editor.commit()
        assertTrue(viewModel.note.content.contains("* Later [0/2]\n"), "the save fills it in")

        viewModel.toggleCheckboxAt(viewModel.note.content.indexOf("- [ ] one"), this)
        advanceUntilIdle()

        assertTrue(viewModel.note.content.contains("* Later [1/2]\n- [X] one\n"))
    }
}
