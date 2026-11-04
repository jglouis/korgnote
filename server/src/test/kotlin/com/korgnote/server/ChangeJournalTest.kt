package com.korgnote.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The journal is an optimisation, never the source of truth: whenever it cannot answer, the caller
 * must fall back to a full manifest. These tests pin down exactly when that happens.
 */
class ChangeJournalTest {

    @Test
    fun everyChangeGetsTheNextGeneration() = runTest {
        val journal = ChangeJournal(start = 0)
        assertEquals(1, journal.record("a.org", "e1").generation)
        assertEquals(2, journal.record("b.org", "e2").generation)
        assertEquals(2, journal.generation)
    }

    @Test
    fun aCaughtUpClientIsToldNothingChanged() = runTest {
        val journal = ChangeJournal(start = 0)
        journal.record("a.org", "e1")
        assertEquals(JournalDelta(1, emptyList()), journal.changesSince(1))
    }

    @Test
    fun aClientOneBehindGetsJustThatChange() = runTest {
        val journal = ChangeJournal(start = 0)
        journal.record("a.org", "e1")
        journal.record("b.org", "e2")

        val delta = journal.changesSince(1)
        assertEquals(listOf("b.org"), delta?.changes?.map { it.path })
        assertEquals(2, delta?.generation)
    }

    /** A restarted server, or a client away longer than the log reaches. */
    @Test
    fun aGenerationTheJournalCannotCoverAsksForAFullManifest() = runTest {
        val journal = ChangeJournal(capacity = 2, start = 0)
        repeat(5) { journal.record("f$it.org", "e$it") }

        assertNull(journal.changesSince(1), "trimmed history must force a full manifest")
        assertNull(journal.changesSince(99), "a generation from the future must too")
    }

    @Test
    fun anEmptyJournalCanOnlyAnswerForTheGenerationItStartedAt() = runTest {
        val journal = ChangeJournal(start = 100)
        assertEquals(JournalDelta(100, emptyList()), journal.changesSince(100))
        assertNull(journal.changesSince(0))
        assertNull(journal.changesSince(103))
    }

    @Test
    fun deletionsAreRecordedAsSuch() = runTest {
        val journal = ChangeJournal(start = 0)
        journal.record("gone.org", etag = null, deleted = true)
        assertTrue(journal.changesSince(0)!!.changes.single().deleted)
    }

    @Test
    fun theLogIsBoundedSoAMemoryLeakIsNotPossible() = runTest {
        val journal = ChangeJournal(capacity = 3, start = 0)
        repeat(100) { journal.record("f$it.org", "e") }
        assertEquals(3, journal.changesSince(97)?.changes?.size)
    }

    /**
     * The journal is in memory, so a restart starts a new one. A client still holding a generation
     * the old one handed out must get a full manifest from it, never a delta: counted from zero
     * again, the new journal would soon pass that number and answer with only the changes after
     * it, leaving the client without every change made in between.
     */
    @Test
    fun aRestartedServerNeverAnswersForAGenerationTheOldOneHandedOut() = runTest {
        val before = ChangeJournal()
        repeat(3) { before.record("f$it.org", "e$it") }
        val handedOut = before.generation

        // A restart takes longer than this; it is only here so the two do not start together.
        Thread.sleep(10)
        val after = ChangeJournal()
        repeat(10) { after.record("g$it.org", "e$it") }

        assertNull(after.changesSince(handedOut), "the new journal must not pretend to know what changed")
    }

    /**
     * A phone that went away without closing its stream stops reading it long before the server can
     * tell. Its buffer filling must not hold up the save that is announcing the next change, and with
     * it every other device: events are hints, and the manifest is what a client catches up from.
     */
    @Test
    fun aStreamNobodyIsReadingDoesNotHoldUpWriters() = runBlocking {
        val journal = ChangeJournal(start = 0)
        val subscribed = CompletableDeferred<Unit>()
        val stalled = launch(Dispatchers.Default) {
            journal.events.onSubscription { subscribed.complete(Unit) }.collect { awaitCancellation() }
        }
        subscribed.await()
        try {
            val finished = withTimeoutOrNull(5.seconds) {
                repeat(1_000) { journal.record("f$it.org", "e$it") }
            }
            assertNotNull(finished, "only ${journal.generation} changes were recorded before writers froze")
        } finally {
            stalled.cancel()
        }
    }

    /**
     * Past what is buffered, a slow reader loses events, and an app pulls only the notes it hears of.
     * So the stream ends at the first one lost: an app that loses its stream runs a full cycle.
     */
    @Test
    fun aReaderThatFallsBehindSeesItsEventsEndAtTheFirstOneLost() = runBlocking {
        val journal = ChangeJournal(start = 0)
        val seen = mutableListOf<Long>()
        val reader = launch(Dispatchers.Default) {
            journal.eventsUntilOneIsMissed().collect {
                delay(1)
                seen += it.generation
            }
        }
        delay(200)
        repeat(1_000) { journal.record("f$it.org", "e$it") }

        assertNotNull(withTimeoutOrNull(10.seconds) { reader.join() }, "the events never ended")
        assertTrue(seen.size < 1_000, "nothing was lost, so this tells nothing")
        assertEquals(seen.indices.map { it + 1L }, seen)
    }

    /** Devices and the watcher record at once. None of that may read as an event lost. */
    @Test
    fun aReaderThatKeepsUpWithManyWritersSeesEveryEvent() = runBlocking {
        val journal = ChangeJournal(start = 0)
        val seen = AtomicInteger()
        val reader = launch(Dispatchers.Default) { journal.eventsUntilOneIsMissed().collect { seen.incrementAndGet() } }
        delay(200)
        withContext(Dispatchers.Default) {
            repeat(8) { writer ->
                launch { repeat(25) { journal.record("w$writer-$it.org", "e") } }
            }
        }

        assertNotNull(withTimeoutOrNull(5.seconds) { while (seen.get() < 200) delay(5) }, "saw ${seen.get()} of 200")
        assertTrue(reader.isActive, "the events ended with none lost")
        reader.cancel()
    }

    /** Only where a note ended up matters: deleted and made again, it is there. */
    @Test
    fun aPathChangedSeveralTimesIsReportedOnceAsItsLatestChange() = runTest {
        val journal = ChangeJournal(start = 0)
        journal.record("a.org", "e1")
        journal.record("a.org", etag = null, deleted = true)
        journal.record("a.org", "e2")

        val change = journal.changesSince(0)!!.changes.single()
        assertEquals("e2", change.etag)
        assertEquals(false, change.deleted)
    }
}
