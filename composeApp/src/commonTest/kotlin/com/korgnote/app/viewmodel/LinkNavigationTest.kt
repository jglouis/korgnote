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

    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = FakeSyncApi(mutableMapOf("project.org" to project, "daily/today.org" to daily))
        val store = DocumentStore(InMemoryReplicaStorage())
        viewModel = FileTreeViewModel(SyncEngine(store, server), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun openDaily() {
        viewModel.loadFromServer()
        viewModel.onFileSelected("daily/today.org")
    }

    private fun elementAt(text: String, marker: String) =
        text.orgElements().indexOfFirst { text.indexOf(marker) in it.start until it.endExclusive }

    @Test
    fun anIdLinkOpensItsNoteAtTheHeading() = runTest {
        openDaily()

        viewModel.followLink("id:plan-id")

        assertEquals("Selected: project.org", viewModel.selectedFileLabel)
        assertEquals(elementAt(project, "* Plan"), viewModel.scrollToElementIndex)
    }

    @Test
    fun backReturnsToWhereTheLinkWasTapped() = runTest {
        openDaily()
        val work = elementAt(daily, "* Work")
        viewModel.onReadingPositionChanged(work)
        assertFalse(viewModel.canGoBack)

        viewModel.followLink("file:../project.org")
        assertTrue(viewModel.canGoBack)
        viewModel.goBack()

        assertEquals("Selected: daily/today.org", viewModel.selectedFileLabel)
        assertEquals(work, viewModel.scrollToElementIndex)
        assertFalse(viewModel.canGoBack)
    }

    @Test
    fun aLinkWithinTheNoteMovesWithinIt() = runTest {
        openDaily()

        viewModel.followLink("*Morning")

        assertEquals("Selected: daily/today.org", viewModel.selectedFileLabel)
        assertEquals(0, viewModel.scrollToElementIndex)
        assertTrue(viewModel.canGoBack)
    }

    @Test
    fun aBrokenLinkSaysSoAndGoesNowhere() = runTest {
        openDaily()

        viewModel.followLink("id:missing")

        assertEquals("Selected: daily/today.org", viewModel.selectedFileLabel)
        assertTrue(viewModel.lastErrorMessage.contains("Nothing in the notes"), viewModel.lastErrorMessage)
        assertFalse(viewModel.canGoBack)
    }

    @Test
    fun aRenamedHeadingStillOpensTheNoteAndSaysWhatWasMissing() = runTest {
        openDaily()

        viewModel.followLink("file:../project.org::*Renamed")

        assertEquals("Selected: project.org", viewModel.selectedFileLabel)
        assertTrue(viewModel.lastErrorMessage.contains("*Renamed"), viewModel.lastErrorMessage)
    }

    @Test
    fun anOpenNoteListsWhatLinksToIt() = runTest {
        openDaily()
        viewModel.onFileSelected("project.org")

        // The id link, the file link, and the file link whose heading search no longer matches.
        assertEquals(listOf("Work", "Work", "Work"), viewModel.backlinks.map { it.context })
        assertEquals(setOf("daily/today.org"), viewModel.backlinks.map { it.path }.toSet())

        viewModel.openBacklink(viewModel.backlinks.first())
        assertEquals("Selected: daily/today.org", viewModel.selectedFileLabel)
        assertEquals(elementAt(daily, "* Work"), viewModel.scrollToElementIndex)
        assertTrue(viewModel.canGoBack, "back returns to the note the backlink was listed in")
    }

    @Test
    fun completionLinksFromTheNoteBeingEdited() = runTest {
        openDaily()

        val context = viewModel.completionContext
        assertEquals("daily/today.org", context.currentPath)
        assertEquals(mapOf("project.org" to "project-id"), context.noteIds)
        assertEquals("Project", context.noteTitles["project.org"])
    }
}
