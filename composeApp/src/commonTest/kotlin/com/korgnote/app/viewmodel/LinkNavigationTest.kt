package com.korgnote.app.viewmodel

import com.korgnote.common.org.orgElements
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** Following links and coming back (T21), through the view model; the rules are in `OrgLinksTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkNavigationTest {

    private val project = """
        :PROPERTIES:
        :ID:       project-id
        :END:
        #+TITLE: Project

        * Background
        * Plan
        :PROPERTIES:
        :ID:       plan-id
        :END:
        The plan.
    """.trimIndent() + "\n"

    private val daily = """
        * Morning
        * Work
        Read [[id:plan-id][the plan]] and [[file:../project.org][the project]].
        Then [[*Morning]], [[id:missing]] and [[file:../project.org::*Renamed]].
    """.trimIndent() + "\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = FakeSyncApi(mutableMapOf("project.org" to project, "daily/today.org" to daily))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = NotesViewModel(SyncEngine(store, server), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openDaily() {
        viewModel.server.load()
        viewModel.onFileSelected("daily/today.org")
    }

    private fun elementAt(text: String, marker: String) =
        text.orgElements().indexOfFirst { text.indexOf(marker) in it.start until it.endExclusive }

    @Test
    fun anIdLinkOpensItsNoteAtTheHeading() = runTest {
        openDaily()

        viewModel.links.follow("id:plan-id")

        assertEquals("Selected: project.org", viewModel.note.label)
        assertEquals(elementAt(project, "* Plan"), viewModel.note.scrollToElementIndex)
    }

    @Test
    fun backReturnsToWhereTheLinkWasTapped() = runTest {
        openDaily()
        val work = elementAt(daily, "* Work")
        viewModel.links.onReadingPositionChanged(work)
        assertFalse(viewModel.links.canGoBack)

        viewModel.links.follow("file:../project.org")
        assertTrue(viewModel.links.canGoBack)
        viewModel.links.goBack()

        assertEquals("Selected: daily/today.org", viewModel.note.label)
        assertEquals(work, viewModel.note.scrollToElementIndex)
        assertFalse(viewModel.links.canGoBack)
    }

    @Test
    fun aLinkWithinTheNoteMovesWithinIt() = runTest {
        openDaily()

        viewModel.links.follow("*Morning")

        assertEquals("Selected: daily/today.org", viewModel.note.label)
        assertEquals(0, viewModel.note.scrollToElementIndex)
        assertTrue(viewModel.links.canGoBack)
    }

    @Test
    fun backSkipsANoteDeletedSince() = runTest {
        openDaily()
        viewModel.links.follow("file:../project.org")
        viewModel.deleteNote("daily/today.org")

        viewModel.links.goBack()

        assertEquals("Selected: project.org", viewModel.note.label, "there is nothing left to go back to")
        assertFalse(viewModel.links.canGoBack)
    }

    @Test
    fun aBrokenLinkSaysSoAndGoesNowhere() = runTest {
        openDaily()

        assertFalse(viewModel.links.follow("id:missing"))

        assertEquals("Selected: daily/today.org", viewModel.note.label)
        assertTrue(viewModel.lastErrorMessage.contains("Nothing in the notes"), viewModel.lastErrorMessage)
        assertFalse(viewModel.links.canGoBack)
    }

    @Test
    fun aLinkToSomethingOtherThanANoteSaysWhyItGoesNowhere() = runTest {
        openDaily()

        for ((link, reason) in listOf(
            "kbd:C-c C-c" to "keys to press in Emacs",
            "mu4e:msgid:123@example.org" to "mu4e: link, which only Emacs can follow",
            "./photo.jpeg" to "only notes are synced",
            "file:scan.pdf" to "only notes are synced",
        )) {
            viewModel.clearError()
            assertFalse(viewModel.links.follow(link), link)
            assertTrue(viewModel.lastErrorMessage.contains(reason), viewModel.lastErrorMessage)
        }
        assertEquals("Selected: daily/today.org", viewModel.note.label)
        assertFalse(viewModel.links.canGoBack)
    }

    @Test
    fun aLinkTappedWithNoNoteOpenStillGoes() = runTest {
        viewModel.server.load()
        viewModel.note.close()

        assertTrue(viewModel.links.follow("id:plan-id"))

        assertEquals("Selected: project.org", viewModel.note.label)
        assertFalse(viewModel.links.canGoBack, "there is nowhere to go back to")
    }

    @Test
    fun aRenamedHeadingStillOpensTheNoteAndSaysWhatWasMissing() = runTest {
        openDaily()

        viewModel.links.follow("file:../project.org::*Renamed")

        assertEquals("Selected: project.org", viewModel.note.label)
        assertTrue(viewModel.lastErrorMessage.contains("*Renamed"), viewModel.lastErrorMessage)
    }

    @Test
    fun anOpenNoteListsWhatLinksToIt() = runTest {
        openDaily()
        viewModel.onFileSelected("project.org")

        // The id link, the file link, and the file link whose heading search no longer matches.
        assertEquals(listOf("Work", "Work", "Work"), viewModel.links.backlinks.map { it.context })
        assertEquals(setOf("daily/today.org"), viewModel.links.backlinks.map { it.path }.toSet())

        viewModel.links.openBacklink(viewModel.links.backlinks.first())
        assertEquals("Selected: daily/today.org", viewModel.note.label)
        assertEquals(elementAt(daily, "* Work"), viewModel.note.scrollToElementIndex)
        assertTrue(viewModel.links.canGoBack, "back returns to the note the backlink was listed in")
    }

    @Test
    fun completionLinksFromTheNoteBeingEdited() = runTest {
        openDaily()

        val context = viewModel.completion.context
        assertEquals("daily/today.org", context.currentPath)
        assertEquals(mapOf("project.org" to "project-id"), context.noteIds)
        assertEquals("Project", context.noteTitles["project.org"])
    }
}
