package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.FileTreeViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The calendar tab inside the real app shell, not on its own.
 *
 * The screen's own tests give it a fixed size. In the app it sits in the shell's content column,
 * which scrolls for most screens — and inside a scroll a screen that fills the space left gets
 * none, so the calendar showed its header over nothing. Only rendering the shell catches that.
 */
@OptIn(ExperimentalTestApi::class)
class AppCalendarTest {
    // Today by the real clock, since the calendar opens on today.
    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

    @BeforeTest
    fun setUp() {
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf("work.org" to "* Design review <$today 10:00-11:00>\n"))
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
                    viewModel { FileTreeViewModel(SyncEngine(store, api), store) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun theCalendarTabShowsTodaysEntries() = runComposeUiTest {
        setContent { App() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("work.org", substring = true).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Calendar").performClick()

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Design review").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("10:00–11:00").fetchSemanticsNodes().isNotEmpty())
    }
}
