package com.korgnote.common

import com.korgnote.common.sync.DiffLine
import com.korgnote.common.sync.DiffLine.Kind.ADDED
import com.korgnote.common.sync.DiffLine.Kind.REMOVED
import com.korgnote.common.sync.DiffLine.Kind.SAME
import com.korgnote.common.sync.diffHunks
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

    @Test
    fun theSameTextHasNoHunks() {
        assertEquals(emptyList(), diffHunks("* A\nbody\n", "* A\nbody\n"))
    }

    @Test
    fun eachHunkIsLabelledWithTheHeadingItFallsUnder() {
        val first = "#+TITLE: Notes\n\n* First\none\ntwo\nthree\nfour\nfive\nsix\n\n* Second\nalpha\nbeta\n"
        val second = first.replace("two", "TWO").replace("beta", "BETA")

        val hunks = diffHunks(first, second, context = 1)

        assertEquals(listOf("* First", "* Second"), hunks.map { it.heading })
        assertEquals(
            listOf(DiffLine(SAME, "one"), DiffLine(REMOVED, "two"), DiffLine(ADDED, "TWO"), DiffLine(SAME, "three")),
            hunks.first().lines,
        )
    }

    @Test
    fun contextReachingBackAcrossAHeadingDoesNotChangeTheLabel() {
        // The change is the first line under * Ideas; its context starts in * Work.
        val first = "* Work\n** TODO Call Alice\n\n* Ideas\nSome idea.\n"
        val second = first.replace("Some idea.", "Some idea, edited.")

        val hunks = diffHunks(first, second)

        assertEquals(listOf("* Ideas"), hunks.map { it.heading })
    }

    @Test
    fun aChangeAboveTheFirstHeadingHasNoHeading() {
        val hunks = diffHunks("#+TITLE: A\n\n* H\n", "#+TITLE: B\n\n* H\n", context = 0)
        assertEquals(listOf<String?>(null), hunks.map { it.heading })
    }

    @Test
    fun nearbyChangesShareOneHunk() {
        val hunks = diffHunks("a\nb\nc\nd\n", "A\nb\nc\nD\n", context = 1)
        assertEquals(1, hunks.size)
    }
}
