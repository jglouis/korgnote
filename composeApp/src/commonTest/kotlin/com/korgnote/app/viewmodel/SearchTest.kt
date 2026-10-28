package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Searching the replica and opening what it finds (T14).
 *
 * The engine itself is covered by `OrgSearchTests` in `shared`; what is at stake here is the part
 * that only the app has — that a result opens its file at the element holding the match, the way a
 * calendar entry does, and that the drawer's filter looks at file names and nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {

    private val work = "work.org"
    private val home = "home.org"

    private val workText = """
        #+TITLE: Work

        * Projects                                   :work:
        ** TODO Send the invoice
           The invoice is due on Friday.
        * Personal
        ** Groceries
           Buy milk and bread.
    """.trimIndent() + "\n"

    private val homeText = """
        #+TITLE: Home

        * Repairs
        ** TODO Fix the tap
           The tap in the kitchen drips.
    """.trimIndent() + "\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(work to workText, home to homeText))
        viewModel = NotesViewModel(SyncEngine(store, api), store)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Types into the search box and lets the debounce elapse.
     *
     * Searching waits for a pause in the typing, so a test that asserts straight after a keystroke
     * is asserting about the moment before the search, not after it.
     */
    private fun TestScope.search(query: String) {
        viewModel.search.onQueryChanged(query)
        advanceUntilIdle()
    }

    @Test
    fun searchesEveryFileInTheReplica() = runTest {
        viewModel.server.load()

        search("the")

        assertTrue(viewModel.search.results.any { it.path == work })
        assertTrue(viewModel.search.results.any { it.path == home })
    }

    @Test
    fun openingAResultShowsItsFileAtItsElement() = runTest {
        viewModel.server.load()
        search("kitchen")

        viewModel.search.openHit(viewModel.search.results.single { it.path == home })

        assertEquals(homeText, viewModel.note.content)
        // preamble, Repairs, Fix the tap — the match is in the body of the last one.
        assertEquals(2, viewModel.note.scrollToElementIndex)
    }

    /** The regression the calendar jump already had: resolve against the file being opened. */
    @Test
    fun openingAResultResolvesAgainstTheNewFile() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)
        search("drips")

        viewModel.search.openHit(viewModel.search.results.single())

        assertEquals(homeText, viewModel.note.content)
        assertEquals(2, viewModel.note.scrollToElementIndex)
    }

    @Test
    fun resultsFollowTheReplica() = runTest {
        viewModel.server.load()
        search("marmalade")
        assertEquals(0, viewModel.search.results.size)

        viewModel.onFileSelected(home)
        viewModel.note.content = homeText + "* Buy marmalade\n"
        viewModel.saveCurrentFile()

        assertEquals(1, viewModel.search.results.size)
    }

    @Test
    fun theDrawerFilterMatchesFileNames() = runTest {
        viewModel.server.load()

        // No waiting for the typing to stop: names are not a scan of the notes.
        viewModel.search.onFilterQueryChanged("home")

        assertEquals(listOf(home), viewModel.search.filteredTree.filePaths())
    }

    /** "kitchen" is in home.org's text, not its name; finding it is what search is for. */
    @Test
    fun theDrawerFilterDoesNotLookInsideTheNotes() = runTest {
        viewModel.server.load()

        viewModel.search.onFilterQueryChanged("kitchen")
        assertEquals(emptyList<String>(), viewModel.search.filteredTree.filePaths())

        viewModel.search.onFilterQueryChanged("tag:work")
        assertEquals(emptyList<String>(), viewModel.search.filteredTree.filePaths())
    }

    @Test
    fun anEmptyFilterKeepsTheWholeTree() = runTest {
        viewModel.server.load()

        viewModel.search.onFilterQueryChanged("home")
        viewModel.search.onFilterQueryChanged("")

        assertEquals(listOf(home, work), viewModel.search.filteredTree.filePaths().sorted())
    }

    // --- Searching waits for the typing to stop ---

    @Test
    fun theScanWaitsForAPauseInTheTyping() = runTest {
        viewModel.server.load()

        viewModel.search.onQueryChanged("kitchen")
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertTrue(viewModel.search.results.isEmpty(), "searched while the typing was still going")

        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(1, viewModel.search.results.size)
    }

    /** Which is the point: typing a word costs one scan, not one per letter. */
    @Test
    fun eachKeystrokeReplacesThePendingScan() = runTest {
        viewModel.server.load()

        viewModel.search.onQueryChanged("kitch")
        advanceTimeBy(150.milliseconds)
        viewModel.search.onQueryChanged("kitchen")
        advanceTimeBy(150.milliseconds)
        runCurrent()
        // 300 ms since the first keystroke, but only 150 since the last: nothing has run.
        assertTrue(viewModel.search.results.isEmpty())

        advanceUntilIdle()
        // And what finally ran was the finished word.
        assertEquals(1, viewModel.search.results.size)
        assertEquals("kitchen", viewModel.search.query)
    }

    @Test
    fun clearingTheQueryTakesEffectAtOnce() = runTest {
        viewModel.server.load()
        search("kitchen")
        assertTrue(viewModel.search.results.isNotEmpty())

        viewModel.search.onQueryChanged("")

        // No waiting: an empty box means an empty list, now.
        assertTrue(viewModel.search.results.isEmpty())
    }

    /** Stale results beat a blank list that flickers on every letter. */
    @Test
    fun theLastResultsStayWhileTheNextScanIsPending() = runTest {
        viewModel.server.load()
        search("kitchen")
        val before = viewModel.search.results

        viewModel.search.onQueryChanged("kitchen sink")
        advanceTimeBy(100.milliseconds)
        runCurrent()

        assertEquals(before, viewModel.search.results)
    }

    private fun Folder.filePaths(): List<String> {
        fun paths(node: Node): List<String> = when (node) {
            is File -> listOf(node.path)
            is Folder -> node.children.flatMap { paths(it) }
            else -> emptyList()
        }
        return paths(this)
    }
}
