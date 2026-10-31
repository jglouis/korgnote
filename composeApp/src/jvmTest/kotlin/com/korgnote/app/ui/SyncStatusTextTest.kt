package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** The server's state as Settings writes it out, under the server's title. */
@OptIn(ExperimentalTestApi::class)
class SyncStatusTextTest {

    @Test
    fun saysWhenTheNotesLastSynced() = runComposeUiTest {
        setContent { SyncStatusText(unreachable = null, syncing = false, lastSynced = Clock.System.now() - 5.minutes) }

        onNodeWithText("Last synced 5 min ago, at ", substring = true).assertExists()
    }

    @Test
    fun aSaveSyncingDoesNotHideTheLastSync() = runComposeUiTest {
        setContent { SyncStatusText(unreachable = null, syncing = true, lastSynced = Clock.System.now() - 5.minutes) }

        onNodeWithText("Last synced 5 min ago", substring = true).assertExists()
    }

    @Test
    fun anUnreachableServerSaysWhyAndWhenItLastAnswered() = runComposeUiTest {
        setContent {
            SyncStatusText(unreachable = "The server did not answer.", syncing = false, lastSynced = Clock.System.now() - 5.minutes)
        }

        onNodeWithText("The server did not answer. Last synced 5 min ago", substring = true).assertExists()
    }

    @Test
    fun beforeTheFirstSync() = runComposeUiTest {
        setContent { SyncStatusText(unreachable = null, syncing = false, lastSynced = null) }
        onNodeWithText("Not synced yet.").assertExists()
    }

    @Test
    fun theFirstSyncUnderWay() = runComposeUiTest {
        setContent { SyncStatusText(unreachable = null, syncing = true, lastSynced = null) }
        onNodeWithText("Syncing with the server…").assertExists()
    }
}
