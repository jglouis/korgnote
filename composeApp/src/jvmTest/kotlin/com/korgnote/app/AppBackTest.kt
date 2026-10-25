package com.korgnote.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
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
import kotlin.test.assertFalse

/**
 * The system's back inside the app shell: it closes the drawer before anything else, and otherwise
 * returns from a followed link.
 *
 * Back is pressed through a dispatcher of the test's own, which is how Android's back button and
 * gesture reach the app's handlers too.
 */
@OptIn(ExperimentalTestApi::class)
class AppBackTest {
    private lateinit var viewModel: NotesViewModel

    private class BackButton : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    private val back = BackButton()
    private val dispatcher = NavigationEventDispatcher().apply { addInput(back) }
    private val owner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = dispatcher
    }

    private fun ComposeUiTest.showApp() = setContent {
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { App() }
    }

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(
            mutableMapOf(
                "start.org" to "* Start\nSee [[file:linked.org][the linked note]].\n",
                "linked.org" to "* Linked\n",
            )
        )
        viewModel = NotesViewModel(SyncEngine(store, api), store)
        runBlocking { viewModel.server.load() }
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
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
    fun backClosesTheDrawerAndLeavesTheNoteAlone() = runComposeUiTest {
        showApp()
        runOnIdle { viewModel.onFileSelected("start.org") }
        runOnIdle { viewModel.links.follow("file:linked.org") }

        onNodeWithContentDescription("Open drawer").performClick()
        onNodeWithText("Filter files").assertIsDisplayed()

        runOnIdle { back.press() }

        onNodeWithText("Filter files").assertIsNotDisplayed()
        // Taken by the drawer, not by the link history behind it.
        assertEquals("linked.org", viewModel.note.path)
    }

    @Test
    fun backReturnsFromAFollowedLink() = runComposeUiTest {
        showApp()
        runOnIdle { viewModel.onFileSelected("start.org") }
        runOnIdle { viewModel.links.follow("file:linked.org") }
        waitForIdle()
        assertEquals("linked.org", viewModel.note.path)

        runOnIdle { back.press() }

        waitForIdle()
        assertEquals("start.org", viewModel.note.path)
        assertFalse(viewModel.links.canGoBack)
    }
}
