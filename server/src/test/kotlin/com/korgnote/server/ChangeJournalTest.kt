package com.korgnote.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal is an optimisation, never the source of truth: whenever it cannot answer, the caller
 * must fall back to a full manifest. These tests pin down exactly when that happens.
 */
class ChangeJournalTest {

    @Test
    fun everyChangeGetsTheNextGeneration() = runTest {
        val journal = ChangeJournal()
        assertEquals(1, journal.record("a.org", "e1").generation)
        assertEquals(2, journal.record("b.org", "e2").generation)
        assertEquals(2, journal.generation)
    }

    @Test
    fun aCaughtUpClientIsToldNothingChanged() = runTest {
        val journal = ChangeJournal()
        journal.record("a.org", "e1")
        assertEquals(emptyList(), journal.changesSince(1))
    }

    @Test
    fun aClientOneBehindGetsJustThatChange() = runTest {
        val journal = ChangeJournal()
        journal.record("a.org", "e1")
        journal.record("b.org", "e2")

        val changes = journal.changesSince(1)
        assertEquals(listOf("b.org"), changes?.map { it.path })
    }

    /** A restarted server, or a client away longer than the log reaches. */
    @Test
    fun aGenerationTheJournalCannotCoverAsksForAFullManifest() = runTest {
        val journal = ChangeJournal(capacity = 2)
        repeat(5) { journal.record("f$it.org", "e$it") }

        assertNull(journal.changesSince(1), "trimmed history must force a full manifest")
        assertNull(journal.changesSince(99), "a generation from the future must too")
    }

    @Test
    fun anEmptyJournalCanOnlyAnswerForGenerationZero() = runTest {
        val journal = ChangeJournal()
        assertEquals(emptyList(), journal.changesSince(0))
        assertNull(journal.changesSince(3))
    }

    @Test
    fun deletionsAreRecordedAsSuch() = runTest {
        val journal = ChangeJournal()
        journal.record("gone.org", etag = null, deleted = true)
        assertTrue(journal.changesSince(0)!!.single().deleted)
    }

    @Test
    fun theLogIsBoundedSoAMemoryLeakIsNotPossible() = runTest {
        val journal = ChangeJournal(capacity = 3)
        repeat(100) { journal.record("f$it.org", "e") }
        assertEquals(3, journal.changesSince(97)?.size)
    }
}
