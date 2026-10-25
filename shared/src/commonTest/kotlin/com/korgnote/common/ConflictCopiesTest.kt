package com.korgnote.common

import com.korgnote.common.sync.conflictCopyName
import com.korgnote.common.sync.conflictCopyOriginal
import com.korgnote.common.sync.conflictsIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictCopiesTest {

    @Test
    fun aCopyIsNamedBesideItsNote() {
        assertEquals("notes/idea.sync-conflict-20260921-101500.org", conflictCopyName("notes/idea.org", "20260921-101500"))
    }

    @Test
    fun aCopyLeadsBackToItsNote() {
        assertEquals("notes/idea.org", conflictCopyOriginal("notes/idea.sync-conflict-20260921-101500.org"))
        assertEquals("notes/idea.org", conflictCopyOriginal("notes/idea.sync-conflict-20260921-101500-2.org"))
    }

    /** Syncthing's copies name the device too; they are the same kind of conflict. */
    @Test
    fun aSyncthingCopyIsRecognised() {
        assertEquals("inbox.org", conflictCopyOriginal("inbox.sync-conflict-20250103-091512-7HNXQ2L.org"))
    }

    @Test
    fun anOrdinaryNoteIsNotACopy() {
        assertNull(conflictCopyOriginal("inbox.org"))
        assertNull(conflictCopyOriginal("sync-conflict-notes.org"))
        assertNull(conflictCopyOriginal("a.sync-conflict-unknown.org"))
    }

    @Test
    fun conflictsListEachNotesCopiesNewestFirst() {
        val paths = listOf(
            "a.org",
            "a.sync-conflict-20260101-000000.org",
            "a.sync-conflict-20260921-101500.org",
            "b.org",
            // Its note is gone, so it is just a note now.
            "c.sync-conflict-20260921-101500.org",
        )

        assertEquals(
            mapOf("a.org" to listOf("a.sync-conflict-20260921-101500.org", "a.sync-conflict-20260101-000000.org")),
            conflictsIn(paths),
        )
    }
}
