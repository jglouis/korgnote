package com.korgnote.app.viewmodel

import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.decodeSavedSearches
import com.korgnote.app.data.encodeSavedSearches
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

/** Searches kept by name: saved to settings, renamed and removed by their query, run at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedSearchesTest {

    private val work = "work.org"
    private val home = "home.org"

    private lateinit var server: FakeSyncApi
    private lateinit var store: DocumentStore
    /** What settings hold, as the view model last saved it. */
    private var saved = emptyList<SavedSearch>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(
            mutableMapOf(
                work to "* TODO Send the invoice :work:\n",
                home to "* TODO Fix the tap\n",
            ),
        )
        store = DocumentStore(InMemoryReplicaStorage())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NotesViewModel(
        SyncEngine(store, server),
        store,
        savedSearches = { saved },
        saveSavedSearches = { saved = it },
    )

    @Test
    fun savingKeepsTheSearchesInSettingsInTheOrderTheyWereSaved() {
        val vm = viewModel()

        vm.savedSearches.save("tag:work", "Work")
        vm.savedSearches.save("invoice", "Invoices")

        val expected = listOf(SavedSearch("Work", "tag:work"), SavedSearch("Invoices", "invoice"))
        assertEquals(expected, saved)
        assertEquals(expected, vm.savedSearches.all)
        // And a new session starts with them.
        assertEquals(expected, viewModel().savedSearches.all)
    }

    @Test
    fun savingAQueryAlreadySavedRenamesItInPlace() {
        val vm = viewModel()
        vm.savedSearches.save("tag:work", "Work")
        vm.savedSearches.save("invoice", "Invoices")

        vm.savedSearches.save("tag:work", "Office")

        assertEquals(listOf(SavedSearch("Office", "tag:work"), SavedSearch("Invoices", "invoice")), saved)
    }

    @Test
    fun aBlankNameIsTheQueryAndABlankQueryIsNotSaved() {
        val vm = viewModel()

        vm.savedSearches.save("  tag:work ", "  ")
        vm.savedSearches.save("   ", "Nothing")

        assertEquals(listOf(SavedSearch("tag:work", "tag:work")), saved)
        assertEquals("tag:work", vm.savedSearches.find(" tag:work")?.query)
    }

    @Test
    fun removingForgetsOnlyThatSearch() {
        val vm = viewModel()
        vm.savedSearches.save("tag:work", "Work")
        vm.savedSearches.save("invoice", "Invoices")

        vm.savedSearches.remove("tag:work")

        assertEquals(listOf(SavedSearch("Invoices", "invoice")), saved)
        assertNull(vm.savedSearches.find("tag:work"))
    }

    @Test
    fun runningASavedSearchSearchesAtOnce() = runTest {
        val vm = viewModel()
        vm.server.load()

        // No time is advanced: a saved search does not wait for typing to stop.
        vm.search.run("TODO tag:work")

        assertEquals("TODO tag:work", vm.search.query)
        assertEquals(listOf(work), vm.search.results.map { it.path })
    }

    @Test
    fun storedSearchesSurviveTheirOwnEncodingAndUnreadableOnesAreNone() {
        val searches = listOf(SavedSearch("Quoted\tname", "\"a phrase\" tag:work"), SavedSearch("x", "y"))

        assertEquals(searches, decodeSavedSearches(encodeSavedSearches(searches)))
        assertEquals(emptyList(), decodeSavedSearches(null))
        assertEquals(emptyList(), decodeSavedSearches("not json"))
    }
}
