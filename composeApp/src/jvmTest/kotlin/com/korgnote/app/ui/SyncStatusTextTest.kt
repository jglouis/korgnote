package com.korgnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    fun aDayIsWrittenOutAsOne() = runComposeUiTest {
        setContent { SyncStatusText(unreachable = null, syncing = false, lastSynced = Clock.System.now() - 1.days) }

        onNodeWithText("Last synced a day ago", substring = true).assertExists()
    }

    @Test
    fun theCloudSaysHowLongAgoInShort() = runComposeUiTest {
        var lastSynced by mutableStateOf(Clock.System.now() - 30.seconds)
        setContent { ServerStatusIcon(unreachable = null, syncing = false, lastSynced = lastSynced, onRetry = {}) }

        for ((ago, short) in listOf(30.seconds to "now", 5.minutes to "5m", 3.hours to "3h", 2.days to "2d")) {
            lastSynced = Clock.System.now() - ago
            waitForIdle()
            onNodeWithText(short).assertExists()
        }
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
