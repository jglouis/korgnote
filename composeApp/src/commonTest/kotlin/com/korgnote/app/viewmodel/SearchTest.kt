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
 * calendar entry does, and that the drawer's filter speaks the same query language.
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

    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(work to workText, home to homeText))
        viewModel = FileTreeViewModel(SyncEngine(store, api), store)
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
        viewModel.onSearchQueryChanged(query)
        advanceUntilIdle()
    }

    private fun TestScope.filterFiles(query: String) {
        viewModel.onFilterQueryChanged(query)
        advanceUntilIdle()
    }

    @Test
    fun searchesEveryFileInTheReplica() = runTest {
        viewModel.loadFromServer()

        search("the")

        assertTrue(viewModel.searchResults.any { it.path == work })
        assertTrue(viewModel.searchResults.any { it.path == home })
    }

    @Test
    fun openingAResultShowsItsFileAtItsElement() = runTest {
        viewModel.loadFromServer()
        search("kitchen")

        viewModel.openSearchHit(viewModel.searchResults.single { it.path == home })

        assertEquals(homeText, viewModel.selectedFileContent)
        // preamble, Repairs, Fix the tap — the match is in the body of the last one.
        assertEquals(2, viewModel.scrollToElementIndex)
    }

    /** The regression the calendar jump already had: resolve against the file being opened. */
    @Test
    fun openingAResultResolvesAgainstTheNewFile() = runTest {
        viewModel.loadFromServer()
        viewModel.onFileSelected(work)
        search("drips")

        viewModel.openSearchHit(viewModel.searchResults.single())

        assertEquals(homeText, viewModel.selectedFileContent)
        assertEquals(2, viewModel.scrollToElementIndex)
    }

    @Test
    fun resultsFollowTheReplica() = runTest {
        viewModel.loadFromServer()
        search("marmalade")
        assertEquals(0, viewModel.searchResults.size)

        viewModel.onFileSelected(home)
        viewModel.onContentChanged(homeText + "* Buy marmalade\n")
        viewModel.saveCurrentFile()

        assertEquals(1, viewModel.searchResults.size)
    }

    @Test
    fun theDrawerFilterStillMatchesFileNames() = runTest {
        viewModel.loadFromServer()

        filterFiles("home")

        assertEquals(listOf(home), viewModel.filteredTree.filePaths())
    }

    @Test
    fun theDrawerFilterUnderstandsTags() = runTest {
        viewModel.loadFromServer()

        filterFiles("tag:work")

        // Only the file with a heading carrying the tag, even though neither file is named "work"
        // in a way the tag query looks at.
        assertEquals(listOf(work), viewModel.filteredTree.filePaths())
    }

    @Test
    fun anEmptyFilterKeepsTheWholeTree() = runTest {
        viewModel.loadFromServer()

        filterFiles("tag:work")
        viewModel.onFilterQueryChanged("")

        assertEquals(listOf(home, work), viewModel.filteredTree.filePaths().sorted())
    }

    // --- Searching waits for the typing to stop ---

    @Test
    fun theScanWaitsForAPauseInTheTyping() = runTest {
        viewModel.loadFromServer()

        viewModel.onSearchQueryChanged("kitchen")
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertTrue(viewModel.searchResults.isEmpty(), "searched while the typing was still going")

        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(1, viewModel.searchResults.size)
    }

    /** Which is the point: typing a word costs one scan, not one per letter. */
    @Test
    fun eachKeystrokeReplacesThePendingScan() = runTest {
        viewModel.loadFromServer()

        viewModel.onSearchQueryChanged("kitch")
        advanceTimeBy(150.milliseconds)
        viewModel.onSearchQueryChanged("kitchen")
        advanceTimeBy(150.milliseconds)
        runCurrent()
        // 300 ms since the first keystroke, but only 150 since the last: nothing has run.
        assertTrue(viewModel.searchResults.isEmpty())

        advanceUntilIdle()
        // And what finally ran was the finished word.
        assertEquals(1, viewModel.searchResults.size)
        assertEquals("kitchen", viewModel.searchQuery)
    }

    @Test
    fun clearingTheQueryTakesEffectAtOnce() = runTest {
        viewModel.loadFromServer()
        search("kitchen")
        assertTrue(viewModel.searchResults.isNotEmpty())

        viewModel.onSearchQueryChanged("")

        // No waiting: an empty box means an empty list, now.
        assertTrue(viewModel.searchResults.isEmpty())
    }

    @Test
    fun clearingTheFilterRestoresTheTreeAtOnce() = runTest {
        viewModel.loadFromServer()
        filterFiles("tag:work")
        assertEquals(listOf(work), viewModel.filteredTree.filePaths())

        viewModel.onFilterQueryChanged("")

        assertEquals(listOf(home, work), viewModel.filteredTree.filePaths().sorted())
    }

    /** Stale results beat a blank list that flickers on every letter. */
    @Test
    fun theLastResultsStayWhileTheNextScanIsPending() = runTest {
        viewModel.loadFromServer()
        search("kitchen")
        val before = viewModel.searchResults

        viewModel.onSearchQueryChanged("kitchen sink")
        advanceTimeBy(100.milliseconds)
        runCurrent()

        assertEquals(before, viewModel.searchResults)
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
