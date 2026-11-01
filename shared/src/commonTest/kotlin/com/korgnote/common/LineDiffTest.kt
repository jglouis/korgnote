package com.korgnote.common

import com.korgnote.common.sync.DiffLine
import com.korgnote.common.sync.DiffLine.Kind.ADDED
import com.korgnote.common.sync.DiffLine.Kind.REMOVED
import com.korgnote.common.sync.DiffLine.Kind.SAME
import com.korgnote.common.sync.lineDiff
import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {

    @Test
    fun aChangedLineIsRemovedThenAdded() {
        assertEquals(
            listOf(DiffLine(REMOVED, "* TODO Task"), DiffLine(ADDED, "* DONE Task"), DiffLine(SAME, "body")),
            lineDiff("* TODO Task\nbody", "* DONE Task\nbody"),
        )
    }

    @Test
    fun linesAddedAtTheEndAreAdded() {
        assertEquals(
            listOf(DiffLine(SAME, "a"), DiffLine(ADDED, "b"), DiffLine(ADDED, "c")),
            lineDiff("a", "a\nb\nc"),
        )
    }
}
