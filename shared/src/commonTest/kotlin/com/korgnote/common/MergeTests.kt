package com.korgnote.common

import com.korgnote.common.sync.MergeResult
import com.korgnote.common.sync.conflictCopyName
import com.korgnote.common.sync.merge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three-way merge behind T11.
 *
 * The case that matters is the ordinary one: two devices adding notes under different headings of
 * the same file. Those are different lines, so they should merge with nobody being asked anything.
 */
class MergeTests {

    private fun merged(base: String, local: String, remote: String): String {
        val result = merge(base, local, remote)
        assertIs<MergeResult.Merged>(result, "expected a clean merge, got $result")
        return result.text
    }

    @Test
    fun identicalEditsOnBothSidesAreNotAConflict() {
        assertEquals("* A\n", merged("* Base\n", "* A\n", "* A\n"))
    }

    @Test
    fun anUntouchedSideYieldsTheOther() {
        val base = "* One\n* Two\n"
        assertEquals("* One\n* Changed\n", merged(base, base, "* One\n* Changed\n"))
        assertEquals("* Changed\n* Two\n", merged(base, "* Changed\n* Two\n", base))
    }

    /** The everyday case: two devices, two different headings. */
    @Test
    fun editsUnderDifferentHeadingsMergeCleanly() {
        val base = """
            * TODO First
            body one

            * TODO Second
            body two
        """.trimIndent()
        val local = base.replace("body one", "body one, edited on the phone")
        val remote = base.replace("body two", "body two, edited in Emacs")

        val result = merged(base, local, remote)

        assertTrue(result.contains("edited on the phone"), result)
        assertTrue(result.contains("edited in Emacs"), result)
    }

    @Test
    fun additionsAtOppositeEndsBothSurvive() {
        val base = "* Middle\n"
        val local = "* Added first\n* Middle\n"
        val remote = "* Middle\n* Added last\n"

        val result = merged(base, local, remote)

        assertTrue(result.contains("Added first"), result)
        assertTrue(result.contains("Added last"), result)
        assertTrue(result.contains("Middle"))
    }

    @Test
    fun aNewHeadingAppendedOnEachSideKeepsBoth() {
        val base = "* Shared\n"
        val result = merged(base, "* Shared\n* From phone\n", "* Shared\n* From laptop\n")

        assertTrue(result.contains("From phone"), result)
        assertTrue(result.contains("From laptop"), result)
    }

    /** The same line changed two ways cannot be resolved without asking. */
    @Test
    fun theSameLineChangedDifferentlyIsAConflict() {
        val result = merge("* TODO Task\n", "* DONE Task\n", "* NEXT Task\n")
        assertIs<MergeResult.Conflicted>(result)
    }

    @Test
    fun aConflictReportsHowManyRegionsDiverged() {
        val base = "a\nb\nc\nd\ne\n"
        val local = "a\nB-local\nc\nd\nE-local\n"
        val remote = "a\nB-remote\nc\nd\nE-remote\n"

        val result = merge(base, local, remote)
        assertIs<MergeResult.Conflicted>(result)
        assertEquals(2, result.regions)
    }

    /** A conflict in one place must not prevent the rest being reported as diverged too. */
    @Test
    fun aCleanEditElsewhereDoesNotRescueAConflict() {
        val base = "a\nb\nc\n"
        val local = "a\nLOCAL\nc\nappended locally\n"
        val remote = "a\nREMOTE\nc\n"

        assertIs<MergeResult.Conflicted>(merge(base, local, remote))
    }

    @Test
    fun deletingOnOneSideWhileTheOtherIsUnchangedApplies() {
        val base = "* One\n* Two\n* Three\n"
        assertEquals("* One\n* Three\n", merged(base, base, "* One\n* Three\n"))
    }

    @Test
    fun anEmptyBaseWithTheSameNewContentIsClean() {
        assertEquals("* Same\n", merged("", "* Same\n", "* Same\n"))
    }

    /** Nothing may ever be written with conflict markers in it. */
    @Test
    fun noOutputEverContainsConflictMarkers() {
        val result = merge("a\nb\n", "a\nlocal\n", "a\nremote\n")
        if (result is MergeResult.Merged) {
            assertTrue(!result.text.contains("<<<<"), "a note must never be handed back with markers")
        }
    }

    @Test
    fun conflictCopiesAreNamedLikeSyncthings() {
        assertEquals(
            "notes.sync-conflict-20260921-101500.org",
            conflictCopyName("notes.org", "20260921-101500"),
        )
        assertEquals(
            "projects/work.sync-conflict-20260921-101500.org",
            conflictCopyName("projects/work.org", "20260921-101500"),
        )
    }
}
