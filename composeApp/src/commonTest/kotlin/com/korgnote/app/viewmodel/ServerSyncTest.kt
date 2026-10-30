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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

    private var clock = Instant.fromEpochSeconds(1_800_000_000)
    private var askedToSyncWhenOnline = 0

    /**
     * [plainReasons] for a sync the stream starts in the background: the app's own reason is read
     * from its resources on a thread of their own, which virtual time does not wait for.
     */
    private fun TestScope.serverSync(plainReasons: Boolean = false) = ServerSync(
        backgroundScope,
        SyncEngine(store, api),
        onReplicaChanged = { replicaShown++ },
        showMessage = { message = it },
        now = { clock },
        background = object : BackgroundSync {
            override fun whenOnline() {
                askedToSyncWhenOnline++
            }
        },
        reason = if (plainReasons) { t -> t.message.orEmpty() } else ::unreachableReason,
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
    fun aSyncThatGetsThroughIsStampedAndOneThatFailsIsNot() = runTest {
        val server = serverSync()
        assertNull(server.lastSynced)

        server.load()
        assertEquals(clock, server.lastSynced)
        assertFalse(server.syncing, "over once it returns")

        val synced = clock
        clock += 5.minutes
        api.online = false
        server.retry()
        assertEquals(synced, server.lastSynced, "offline, the last sync is still the one that got through")
        assertNotNull(server.unreachable)
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
        val server = serverSync(plainReasons = true)
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

    @Test
    fun returningToTheAppCatchesUpWithWhatADeadStreamMissed() = runTest {
        api.holdStream = true
        val server = serverSync()
        server.load()
        server.watch()
        runCurrent()

        // Written elsewhere while the connection was dead: no event will ever say so.
        api.files["missed.org"] = "* Written while away\n"
        after(10 * 60_000)
        assertNull(store.read("missed.org"), "the stream alone never brings it in")

        server.onResume()
        runCurrent()

        assertEquals("* Written while away\n", store.read("missed.org"))
        assertNull(server.unreachable)
    }

    @Test
    fun theResumeAtStartLeavesTheSyncToTheFirstLoad() = runTest {
        val server = serverSync()

        server.onResume()
        runCurrent()

        assertEquals(0, api.manifestRequests)
    }

    @Test
    fun returningToTheAppReconnectsTheStreamWithoutWaitingOutTheBackoff() = runTest {
        api.dropStream = true
        val server = serverSync()
        server.watch()
        after(30_000)
        val opened = api.streamOpened

        api.dropStream = false
        server.onResume()
        runCurrent()

        assertEquals(opened + 1, api.streamOpened, "reconnected at once, not up to 30 s later")
    }

    /**
     * An edit saved offline waits in the replica. With the app closed before the network returns,
     * only a sync in the background takes it to the server before the app is opened again.
     */
    @Test
    fun anEditThatCouldNotBeSentAsksForASyncOnceOnline() = runTest {
        val server = serverSync()
        api.online = false

        server.push { store.create("new.org", "* New\n") }

        assertEquals(1, askedToSyncWhenOnline)
    }

    @Test
    fun anEditThatWasSentAsksForNothing() = runTest {
        val server = serverSync()

        server.push { store.create("new.org", "* New\n") }

        assertEquals(0, askedToSyncWhenOnline)
    }
}
