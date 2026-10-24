package com.korgnote.app.viewmodel

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Following the server's change stream, and how being unable to reach the server is shown.
 *
 * On virtual time, so the reconnection backoff is checked to the millisecond instead of slept
 * through: the stream opens at once, and after a failure 2 s, 4 s, 8 s … later, up to 30 s apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSyncTest {

    private val api = FakeSyncApi()
    private val store = DocumentStore(InMemoryReplicaStorage())
    private var replicaShown = 0
    private var message: String? = null

    private fun TestScope.serverSync() = ServerSync(
        backgroundScope,
        SyncEngine(store, api),
        onReplicaChanged = { replicaShown++ },
        showMessage = { message = it },
    )

    /** Runs whatever is due [millis] from now, the way [advanceTimeBy] alone does not. */
    private fun TestScope.after(millis: Long) {
        advanceTimeBy(millis.milliseconds)
        runCurrent()
    }

    @Test
    fun watchingTwiceOpensOneStream() = runTest {
        val server = serverSync()

        server.watch()
        server.watch()
        runCurrent()

        assertEquals(1, api.streamOpened)
    }

    @Test
    fun aChangeOnTheStreamIsPulledAndShown() = runTest {
        api.files["inbox.org"] = "* From Emacs\n"
        api.pending += "inbox.org" to false

        serverSync().watch()
        runCurrent()

        assertEquals("* From Emacs\n", store.read("inbox.org"))
        assertTrue(replicaShown > 0, "the change should reach the screen")
    }

    @Test
    fun aCleanEndReconnectsAfterASecond() = runTest {
        serverSync().watch()
        runCurrent()
        assertEquals(1, api.streamOpened)

        after(999)
        assertEquals(1, api.streamOpened)
        after(1)
        assertEquals(2, api.streamOpened)
    }

    @Test
    fun aDroppedStreamIsRetriedWithABackoffAndCatchesUpOnReconnecting() = runTest {
        api.dropStream = true
        serverSync().watch()
        runCurrent()
        assertEquals(1, api.streamOpened)

        // Missed while the stream was down: only the catch-up sync can bring it in.
        api.files["missed.org"] = "* Written while away\n"

        after(1999)
        assertEquals(1, api.streamOpened, "not before the backoff")
        after(1)
        assertEquals(2, api.streamOpened)
        assertEquals("* Written while away\n", store.read("missed.org"))

        after(3999)
        assertEquals(2, api.streamOpened, "the backoff doubles")
        after(1)
        assertEquals(3, api.streamOpened)
    }

    @Test
    fun theBackoffStopsGrowingAtThirtySeconds() = runTest {
        api.dropStream = true
        serverSync().watch()
        runCurrent()

        // Opens at 0, 2, 6, 14, 30 s, then every 30 s rather than 32, 64 … apart.
        after(90_000)
        assertEquals(7, api.streamOpened)
    }

    @Test
    fun anOfflineStartIsMarkedUnreachableAndStillShowsTheReplica() = runTest {
        api.online = false
        val server = serverSync()

        server.load()

        assertTrue(server.unreachable.orEmpty().startsWith("Cannot reach the server"), server.unreachable)
        assertNull(message, "being offline is not a message on top of the note")
        assertEquals(1, replicaShown)
    }

    @Test
    fun aPushThatCannotReachTheServerMarksItUnreachableUntilOneGetsThrough() = runTest {
        val server = serverSync()
        api.online = false

        server.push()
        assertNotNull(server.unreachable)
        assertNull(message)

        api.online = true
        server.push()
        assertNull(server.unreachable, "a push that gets through clears the indicator")
    }

    @Test
    fun aQuietPushSaysNothingEvenOffline() = runTest {
        api.online = false
        val server = serverSync()

        server.pushQuietly()

        assertNull(message)
        assertNotNull(server.unreachable)
        assertEquals(0, replicaShown)
    }

    @Test
    fun theStreamsCatchUpMarksTheServerUnreachableAndThenReachableAgain() = runTest {
        api.online = false
        val server = serverSync()
        server.watch()
        runCurrent()
        assertNull(server.unreachable, "a dropped stream alone is not proof of being offline")

        after(2000)
        assertNotNull(server.unreachable, "the catch-up sync failed")

        api.online = true
        after(4000)
        assertNull(server.unreachable)
    }

    @Test
    fun aRetryThatGetsThroughReconnectsTheStreamWithoutWaitingOutTheBackoff() = runTest {
        api.online = false
        val server = serverSync()
        server.load()
        server.watch()
        runCurrent()
        assertEquals(1, api.streamOpened)

        api.online = true
        server.retry()
        runCurrent()

        assertNull(server.unreachable)
        assertFalse(server.retrying)
        assertEquals(2, api.streamOpened, "reconnected at once, not 2 s later")
    }

    @Test
    fun aRetryThatStillCannotReachTheServerKeepsTheIndicator() = runTest {
        api.online = false
        val server = serverSync()

        server.retry()

        assertNotNull(server.unreachable)
        assertFalse(server.retrying)
        assertNull(message)
    }
}
