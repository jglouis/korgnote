package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import com.korgnote.app.ui.File
import com.korgnote.app.ui.Folder
import com.korgnote.app.ui.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun searchesEveryFileInTheReplica() = runTest {
        viewModel.loadFromServer()

        viewModel.onSearchQueryChanged("the")

        assertTrue(viewModel.searchResults.any { it.path == work })
        assertTrue(viewModel.searchResults.any { it.path == home })
    }

    @Test
    fun openingAResultShowsItsFileAtItsElement() = runTest {
        viewModel.loadFromServer()
        viewModel.onSearchQueryChanged("kitchen")

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
        viewModel.onSearchQueryChanged("drips")

        viewModel.openSearchHit(viewModel.searchResults.single())

        assertEquals(homeText, viewModel.selectedFileContent)
        assertEquals(2, viewModel.scrollToElementIndex)
    }

    @Test
    fun resultsFollowTheReplica() = runTest {
        viewModel.loadFromServer()
        viewModel.onSearchQueryChanged("marmalade")
        assertEquals(0, viewModel.searchResults.size)

        viewModel.onFileSelected(home)
        viewModel.onContentChanged(homeText + "* Buy marmalade\n")
        viewModel.saveCurrentFile()

        assertEquals(1, viewModel.searchResults.size)
    }

    @Test
    fun theDrawerFilterStillMatchesFileNames() = runTest {
        viewModel.loadFromServer()

        viewModel.onFilterQueryChanged("home")

        assertEquals(listOf(home), viewModel.filteredTree.filePaths())
    }

    @Test
    fun theDrawerFilterUnderstandsTags() = runTest {
        viewModel.loadFromServer()

        viewModel.onFilterQueryChanged("tag:work")

        // Only the file with a heading carrying the tag, even though neither file is named "work"
        // in a way the tag query looks at.
        assertEquals(listOf(work), viewModel.filteredTree.filePaths())
    }

    @Test
    fun anEmptyFilterKeepsTheWholeTree() = runTest {
        viewModel.loadFromServer()

        viewModel.onFilterQueryChanged("tag:work")
        viewModel.onFilterQueryChanged("")

        assertEquals(listOf(home, work), viewModel.filteredTree.filePaths().sorted())
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
