package com.korgnote.common

import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.sync.DiffLine
import com.korgnote.common.sync.DiffLine.Kind.ADDED
import com.korgnote.common.sync.DiffLine.Kind.REMOVED
import com.korgnote.common.sync.DiffLine.Kind.SAME
import com.korgnote.common.sync.isOrgHeading
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

    /** A conflict names the heading it is under; it has to be a heading to the parser too. */
    @Test
    fun aHeadingInADiffIsAHeadingToTheParser() {
        for (line in listOf("* Title", "** Title", "* ", "*\tTitle", "*", "*bold* text", " * item", "")) {
            assertEquals(
                line.toOrgDocument().children.singleOrNull() is OrgSection,
                line.isOrgHeading(),
                "on \"$line\"",
            )
        }
    }
}
