package com.korgnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading mode's gestures (T24): a tap edits, unless it lands on something that has a tap of its
 * own; a long-press offers the ways of adding a heading; a chevron folds a heading's subtree.
 */
@OptIn(ExperimentalTestApi::class)
class ReadingModeInteractionTest {

    // Elements: 0 Project, 1 Step one, 2 Other
    private val text = """
        * Project
        - [ ] Buy milk
        ** Step one
        Step body.
        * Other
    """.trimIndent() + "\n"

    @Test
    fun aTapOnTextEditsThatElement() = runComposeUiTest {
        val edited = mutableListOf<Int>()
        setContent { OrgDocumentView(text = text, onEditElement = { edited += it }) }

        onNodeWithText("Step body.").performClick()

        assertEquals(listOf(1), edited)
    }

    @Test
    fun aTapOnACheckboxTogglesItAndDoesNotEdit() = runComposeUiTest {
        val edited = mutableListOf<Int>()
        val toggled = mutableListOf<Int>()
        setContent {
            OrgDocumentView(text = text, onEditElement = { edited += it }, onToggleCheckbox = { toggled += it })
        }

        onAllNodes(isToggleable()).onFirst().performClick()

        assertEquals(listOf(text.indexOf("- [ ]")), toggled)
        assertTrue(edited.isEmpty(), "the checkbox's tap is its own")
    }

    @Test
    fun aLongPressOffersBothWaysOfAddingAHeading() = runComposeUiTest {
        val below = mutableListOf<Int>()
        val sub = mutableListOf<Int>()
        val edited = mutableListOf<Int>()
        setContent {
            OrgDocumentView(
                text = text,
                onEditElement = { edited += it },
                onAddHeadingBelow = { below += it },
                onAddSubheading = { sub += it },
            )
        }

        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Add heading below").performClick()
        onNodeWithText("Step body.").performTouchInput { longClick() }
        onNodeWithText("Add sub-heading").performClick()

        assertEquals(listOf(1), below)
        assertEquals(listOf(1), sub)
        assertTrue(edited.isEmpty(), "a long-press opens the menu, not the editor")
    }

    @Test
    fun collapsingAHeadingHidesItsBodyAndDescendants() = runComposeUiTest {
        var folded by mutableStateOf(emptySet<Int>())
        setContent {
            OrgDocumentView(
                modifier = Modifier.fillMaxSize(),
                text = text,
                foldedElements = folded,
                onToggleFold = { folded = if (it in folded) folded - it else folded + it },
            )
        }
        // Other has nothing under it, so it has nothing to fold.
        assertEquals(2, onAllNodesWithContentDescription("Collapse", substring = true).fetchSemanticsNodes().size)

        onNodeWithContentDescription("Collapse Project").performClick()

        assertEquals(setOf(0), folded)
        assertEquals(0, onAllNodes(isToggleable()).fetchSemanticsNodes().size, "the body is hidden")
        onNodeWithText("Step body.").assertDoesNotExist()
        onNodeWithContentDescription("Expand Project").performClick()
        onNodeWithText("Step body.").assertExists()
    }
}
