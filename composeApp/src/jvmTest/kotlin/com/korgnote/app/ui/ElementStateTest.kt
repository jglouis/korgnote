package com.korgnote.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What is open on a heading belongs to that heading. An element's item is its index, so a heading
 * added above hands the item to a neighbour, and what was open must not land on that neighbour; a
 * change within the element, which is still the same heading, leaves it open.
 */
@OptIn(ExperimentalTestApi::class)
class ElementStateTest {
    private val alpha = "* Alpha\n:NOTES:\nalpha secret\n:END:\n"
    private val beta = "* Beta\n:NOTES:\nbeta secret\n:END:\n- [ ] Tick me\n"
    private val text = alpha + beta

    /** What the sync brings: a heading above the two, moving each one index down. */
    private val withOneAbove = "* New\n" + text

    private var shown by mutableStateOf(text)
    private val effortSetAt = mutableListOf<Int>()
    private val prioritySetAt = mutableListOf<Int>()

    private fun ComposeUiTest.showDocument() = setContent {
        OrgDocumentView(
            text = shown,
            actions = ElementActions(
                onEditElement = {},
                onSetEffort = { offset, _ -> effortSetAt += offset },
                onSetPriority = { offset, _ -> prioritySetAt += offset },
                onAddHeadingBelow = {},
            ),
        )
    }

    private fun ComposeUiTest.openBetasDrawer() {
        // Each heading's drawer has the same name; Beta's is the second.
        onAllNodes(hasContentDescription("Expand drawer"))[1].performClick()
        onNodeWithText("beta secret", substring = true).assertExists()
    }

    @Test
    fun anOpenDrawerDoesNotLandOnTheNeighbourWhenAHeadingIsAddedAbove() = runComposeUiTest {
        showDocument()
        openBetasDrawer()

        shown = withOneAbove
        waitForIdle()

        onNodeWithText("alpha secret", substring = true).assertDoesNotExist()
    }

    @Test
    fun aDrawerStaysOpenWhenACheckboxInItsElementIsTicked() = runComposeUiTest {
        showDocument()
        openBetasDrawer()

        shown = text.replace("- [ ]", "- [X]")
        waitForIdle()

        onNodeWithText("beta secret", substring = true).assertExists()
    }

    @Test
    fun aDrawerStaysOpenWhenItsHeadingsKeywordChanges() = runComposeUiTest {
        showDocument()
        openBetasDrawer()

        shown = text.replace("* Beta", "* TODO Beta")
        waitForIdle()

        onNodeWithText("beta secret", substring = true).assertExists()
    }

    /** A tick moves the heading's `[0/1]`: the same heading, whatever its title now reads. */
    @Test
    fun aDrawerStaysOpenWhenItsHeadingsCookieMoves() = runComposeUiTest {
        shown = "* Beta [0/1]\n:NOTES:\nbeta secret\n:END:\n- [ ] Tick me\n"
        setContent { OrgDocumentView(text = shown) }
        onNodeWithText("NOTES", substring = true).performClick()
        waitForIdle()

        shown = "* Beta [1/1]\n:NOTES:\nbeta secret\n:END:\n- [X] Tick me\n"
        waitForIdle()

        onNodeWithText("beta secret", substring = true).assertExists()
    }

    @Test
    fun theLongPressMenuStaysOpenWhenItsHeadingsCookieMoves() = runComposeUiTest {
        shown = "* Beta [40%]\n- [ ] Tick me\n"
        showDocument()
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Set effort…").assertExists()

        shown = "* Beta [50%]\n- [ ] Tick me\n"
        waitForIdle()

        onNodeWithText("Set effort…").assertExists()
    }

    @Test
    fun aDrawerClosesWhenTheTitleBesideItsCookieChanges() = runComposeUiTest {
        shown = "* Beta [0/1]\n:NOTES:\nbeta secret\n:END:\n"
        setContent { OrgDocumentView(text = shown) }
        onNodeWithText("NOTES", substring = true).performClick()
        waitForIdle()

        shown = "* Alpha [0/1]\n:NOTES:\nalpha secret\n:END:\n"
        waitForIdle()

        onNodeWithText("alpha secret", substring = true).assertDoesNotExist()
    }

    @Test
    fun theLongPressMenuDoesNotLandOnTheNeighbourWhenAHeadingIsAddedAbove() = runComposeUiTest {
        showDocument()
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Set effort…").assertExists()

        shown = withOneAbove
        waitForIdle()

        onNodeWithText("Set effort…").assertDoesNotExist()
    }

    @Test
    fun theEffortDialogDoesNotLandOnTheNeighbourWhenAHeadingIsAddedAbove() = runComposeUiTest {
        showDocument()
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Set effort…").performClick()
        onNodeWithText("How long should it take?").assertExists()

        shown = withOneAbove
        waitForIdle()

        onNodeWithText("How long should it take?").assertDoesNotExist()
        assertTrue(effortSetAt.isEmpty())
    }

    @Test
    fun thePriorityMenuDoesNotLandOnTheNeighbourWhenAHeadingIsAddedAbove() = runComposeUiTest {
        showDocument()
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Priority…").performClick()
        onNodeWithText("No priority").assertExists()

        shown = withOneAbove
        waitForIdle()

        onNodeWithText("No priority").assertDoesNotExist()
        assertTrue(prioritySetAt.isEmpty())
    }

    @Test
    fun theEffortDialogStaysOpenForAnEditInTheSameHeading() = runComposeUiTest {
        showDocument()
        onNodeWithText("Beta", substring = true).performTouchInput { longClick() }
        onNodeWithText("Set effort…").performClick()

        shown = text.replace("- [ ]", "- [X]")
        waitForIdle()
        onNodeWithText("15 min").performClick()

        assertEquals(listOf(text.indexOf("* Beta")), effortSetAt)
    }
}
