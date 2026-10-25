package com.korgnote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictShownTest {

    private val conflicts = mapOf(
        "a.org" to listOf("a.sync-conflict-20260921-101500.org", "a.sync-conflict-20260101-000000.org"),
    )

    @Test
    fun aNoteShowsItsNewestCopy() {
        assertEquals(
            ShownConflict("a.org", "a.sync-conflict-20260921-101500.org", viewingCopy = false, moreCopies = 1),
            conflictShown("a.org", conflicts),
        )
    }

    /** An older copy is settled from its own page as readily as the newest. */
    @Test
    fun aCopyShowsItself() {
        assertEquals(
            ShownConflict("a.org", "a.sync-conflict-20260101-000000.org", viewingCopy = true, moreCopies = 0),
            conflictShown("a.sync-conflict-20260101-000000.org", conflicts),
        )
    }

    @Test
    fun aNoteWithoutCopiesShowsNothing() {
        assertNull(conflictShown("b.org", conflicts))
        assertNull(conflictShown(null, conflicts))
    }
}
