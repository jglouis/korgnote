package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
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
}
