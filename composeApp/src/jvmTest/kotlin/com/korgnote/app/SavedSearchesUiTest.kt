package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A search saved from the search field and run again from the search tab's drawer, in the app
 * shell: the drawer there holds the saved searches rather than the files.
 */
@OptIn(ExperimentalTestApi::class)
class SavedSearchesUiTest {
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("work.org" to "* TODO Send the invoice\n", "home.org" to "* Fix the tap\n"))
        viewModel = NotesViewModel(SyncEngine(store, api), store)
        runBlocking { viewModel.server.load() }
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
                    single { NotesSourceChoice(get(), DocumentStore(InMemoryReplicaStorage())) }
                    viewModel { viewModel }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun aSearchSavedUnderANameRunsAgainFromTheDrawer() = runComposeUiTest {
        setContent { App() }
        onNodeWithText("Search").performClick()
        runOnIdle { viewModel.search.onQueryChanged("invoice") }

        onNodeWithContentDescription("Save search").performClick()
        onNodeWithText("Save").performClick()

        assertEquals(listOf(SavedSearch("invoice", "invoice")), viewModel.savedSearches.all)
        onNodeWithContentDescription("Remove saved search").assertExists()

        runOnIdle { viewModel.search.onQueryChanged("") }
        onNodeWithContentDescription("Saved searches").performClick()
        onNodeWithText("invoice").performClick()

        waitForIdle()
        assertEquals("invoice", viewModel.search.query)
        assertEquals(listOf("work.org"), viewModel.search.results.map { it.path })
        // Closed on the run, with the results behind it.
        onNodeWithText("Saved searches").assertIsNotDisplayed()
    }

    @Test
    fun theBookmarkOfASavedSearchForgetsIt() = runComposeUiTest {
        runOnIdle { viewModel.savedSearches.save("invoice", "Invoices") }
        setContent { App() }
        onNodeWithText("Search").performClick()
        runOnIdle { viewModel.search.onQueryChanged("invoice") }

        onNodeWithContentDescription("Remove saved search").performClick()

        assertEquals(emptyList(), viewModel.savedSearches.all)
        onNodeWithContentDescription("Save search").assertExists()
    }
}
