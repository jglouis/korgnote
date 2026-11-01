package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.viewmodel.ConflictComparison
import com.korgnote.common.sync.ChunkChoice
import com.korgnote.common.sync.conflictChunks
import kotlin.test.Test
import kotlin.test.assertEquals

/** The banner's buttons say what they do from where the reader stands, and do it. */
@OptIn(ExperimentalTestApi::class)
class ConflictBannerTest {

    private val copy = "a.sync-conflict-20260921-101500.org"

    @Test
    fun onTheNoteKeepingItDiscardsTheCopy() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            ConflictBanner(
                ShownConflict("a.org", copy, viewingCopy = false, moreCopies = 0),
                onCompare = { clicked += "compare" },
                onKeepNote = { clicked += "keep" },
                onUseCopy = { clicked += "use" },
            )
        }

        onNodeWithText("This note changed in two places at once").assertExists()
        onNodeWithText("Keep this one").performClick()
        onNodeWithText("Use the other").performClick()
        onNodeWithText("Compare").performClick()

        assertEquals(listOf("keep", "use", "compare"), clicked)
    }

    @Test
    fun onTheCopyUsingItReplacesTheNote() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            ConflictBanner(
                ShownConflict("a.org", copy, viewingCopy = true, moreCopies = 0),
                onCompare = { clicked += "compare" },
                onKeepNote = { clicked += "keep" },
                onUseCopy = { clicked += "use" },
            )
        }

        onNodeWithText("A conflicting version of a.org").assertExists()
        onNodeWithText("Use this one").performClick()
        onNodeWithText("Discard this one").performClick()

        assertEquals(listOf("use", "keep"), clicked)
    }

    @Test
    fun comparingSettlesEachChunkOnItsOwn() = runComposeUiTest {
        val note = "* TODO First\nbody\n* Second\nmore\n"
        val other = "* DONE First\nbody\n* Second\nmore, longer\n"
        val comparison = ConflictComparison("a.org", copy, note, other, conflictChunks(note, other, context = 0))
        val applied = mutableListOf<List<ChunkChoice>>()
        setContent {
            ConflictCompareDialog(comparison, onApply = { applied += it }, onDismiss = {})
        }

        onNodeWithText("2 changes left to choose").assertExists()
        onNodeWithText("Apply").assertIsNotEnabled()
        onAllNodesWithText("+ Copy")[0].performClick()
        onNodeWithText("1 change left to choose").assertExists()
        onAllNodesWithText("Both")[1].performClick()
        onNodeWithText("Apply").assertIsEnabled().performClick()

        assertEquals(listOf(listOf<ChunkChoice>(ChunkChoice.Copy, ChunkChoice.Both)), applied)
    }

    @Test
    fun takingAllOfOneSideChoosesEveryChunk() = runComposeUiTest {
        val note = "* TODO First\nbody\n* Second\nmore\n"
        val other = "* DONE First\nbody\n* Second\nmore, longer\n"
        val comparison = ConflictComparison("a.org", copy, note, other, conflictChunks(note, other, context = 0))
        val applied = mutableListOf<List<ChunkChoice>>()
        setContent {
            ConflictCompareDialog(comparison, onApply = { applied += it }, onDismiss = {})
        }

        onNodeWithText("All the note's").performClick()
        onNodeWithText("Apply").performClick()

        assertEquals(listOf(listOf<ChunkChoice>(ChunkChoice.Note, ChunkChoice.Note)), applied)
    }
}
